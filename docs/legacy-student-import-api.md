# Legacy student CSV import — ICI2-37

Phase 1 (schema + contract) and phase 2 (parser, mapper, validation and staging services, shared duplicate matcher) are implemented. **The HTTP endpoints and controller (phase 3) do not exist yet**; the routes below are a planned contract. Backend and docs only; no screens or styles.

## Purpose and scope

Bring student identity records from the legacy ICI Local CSV exports into the permanent `students` table, with per-row provenance, validation results, duplicate flagging and registrar review. In scope: student master fields only. Out of scope: grades, enrollments, guardians, addresses, requirements, documents, and automatic reconciliation sign-off.

## Status of this format — assumed, not validated

**The real legacy exports have not been received.** The columns, header aliases, date formats, encoding, delimiter and limits in this document are an *assumed* specification, validated only against **synthetic** fixtures (`backend/src/test/resources/import/`, all invented data). Real-file validation is pending. Until then the seeded mapping carries `real_file_validated = false`, and no school policy or legacy value translation is presumed. When the real file arrives, an Administrator edits the stored mapping (header names, aliases, date formats, delimiter, encoding, value translations) — no code change should be needed — and records the validation.

## Existing tables reused (V1/V3) and what V19 adds

| Existing | Used as |
| --- | --- |
| `import_batches` (source_name, checksum, status `staged/validated/imported/reconciled/failed`, source_count, imported_count, started_at, signed_off_*) | One uploaded file. `source_name` is the source file name. |
| `import_exceptions` (batch_id, row_number, error_code, detail, resolved) | One record per problem, batch-level (`row_number` null) or row-level. |

V19 (`V19__legacy_student_import_staging.sql`, not yet applied to any shared database) adds only what was missing:

- `import_batches`: `campus_id`, `source_type`, `mapping_id`, `mapping_snapshot`, `file_size_bytes`, `valid_count`, `invalid_count`, `flagged_count`, `skipped_count`, `imported_by`, `imported_at`, `version`; unique `(source_type, checksum)` while not `failed`; count-balance, commit-actor and campus-required checks, all `NOT VALID` so any pre-existing rows are not rejected.
- `import_rows` (new): `batch_id`, `row_number` (unique per batch), `status`, `campus_id`, `raw_values`, `normalized_values`, `duplicate_evidence`, review decision/reason/actor/time (a decision requires a non-blank reason), created `student_id` (unique), `version`, standard metadata columns.
- `import_exceptions`: `import_row_id`, `field_name`, `severity` (`error`/`warning`), bounded `detail` (1–1000 chars).
- `import_column_mappings` (new): stored, editable mapping and parse settings; `is_default` (one per source type), `active`, `real_file_validated`, `version`.
- Permissions `imports.read`, `imports.manage` (Registrar and Admin) and `imports.mappings.manage` (Admin only).

Provenance of an imported student is `import_rows.student_id → import_rows.batch_id → import_batches.source_name/checksum`, plus row number and raw values. The file name is stored once per batch. Audit: mappings and batches use the standard `audit_change` trigger; `import_rows` uses `import_row_audit`, which **omits `raw_values` and `normalized_values`** so personal data is not copied into `audit_logs`.

Reconciliation caveat: V1 only allows `status='reconciled'` when `source_count = imported_count` and a sign-off exists, so batches with invalid, flagged or skipped rows can never reach `reconciled`. This design stops at `imported`.

## Source file format (assumed)

CSV per RFC 4180 (header row, quoted fields, doubled quotes, embedded newlines in quotes, CRLF/LF/CR). One file = one campus = one import batch. Values are read as text and trimmed, NFC-normalized and whitespace-collapsed for `normalized_values`; `raw_values` keeps them as read. Fully blank lines are skipped. **Row numbers are 1-based record positions in the file** (the header is row 1 by default, so the first data row is 2).

### Campus

Each campus has its own legacy database, so an export normally has no campus column. **The upload supplies `campusId`**, which must be one the caller can import for, and every row belongs to it. A `campus` column is **optional**: if mapped and present, a non-blank value is resolved to a campus code; an unknown value is `UNKNOWN_CAMPUS`, and a value naming a different campus than the upload is `CAMPUS_MISMATCH` (a row error). A blank value or an absent column means "the upload's campus".

### Fields

| Target field | Assumed meaning | Type / rule | Required | Maps to |
| --- | --- | --- | --- | --- |
| `studentNumber` | Legacy student number | text, 1–40 chars | **Required** | `students.student_number` |
| `lrn` | Legacy LRN | exactly 12 digits (no stripping of spaces/dashes) | Optional | `students.lrn` |
| `lastName` | Last name | text, ≤150 | **Required** | `students.last_name` |
| `firstName` | First name | text, ≤150 | **Required** | `students.first_name` |
| `middleName` | Middle name | text, ≤150 | Optional | `students.middle_name` |
| `birthDate` | Birth date | date (formats below), 1900-01-01 up to yesterday | **Required** | `students.birth_date` |
| `sex` | Sex | text, ≤30; value map optional | Optional | `students.sex` |
| `previousSchool` | Previous school | text, ≤250 | Optional | `students.previous_school_name` |
| `voucher` | Voucher classification | value map or canonical code → `QVR/ESC/ALS/REGULAR` | **Required** | `students.voucher_code` |
| `specialization` | Strand/specialization | value map or canonical code → `programs.code` | **Required** | `students.program_id`; track derived by `StudentService.trackFor`, shared with the student form |
| `campus` | Campus | value map or canonical code → `campuses.code` | **Optional** | cross-check only (see Campus) |

A mapping cannot make the six required fields optional. Voucher and specialization stay required because `StudentInput` cannot create a student without them. Extra columns are ignored (kept in `raw_values`). Guardians, address, phone, email, household income, suffix and active flag are not imported; created students will be `active` with empty address and no guardians, to be completed in the Student Master.

### Normalization

- **Text:** NFC, trim, whitespace runs collapsed to one space. Accents are kept as written (matching ignores them). Control characters are `INVALID_CHARACTER`.
- **Names:** if `normalizeNameCase` is on (default), a name that is *entirely* upper-case or *entirely* lower-case is title-cased per word (after space, hyphen or apostrophe; roman numerals II–IX stay upper-case). Mixed-case names (`McDonald`, `dela Cruz`) are never altered. Set it to `false` to keep names exactly as written.
- **Dates:** exact match against the configured `dateFormats` only (four-digit year; two-digit month and day; `-`, `/` or `.` separator). Two-digit years, month names, spreadsheet serial numbers, times, single-digit months/days, impossible dates (02/30) and dates before 1900 are `INVALID_DATE`; today or later is `FUTURE_DATE`. A value readable under two configured formats with different results is `AMBIGUOUS_DATE`. A mapping cannot be saved with both `MM/dd/yyyy` and `dd/MM/yyyy` (same separator, day and month swapped).
- **Value maps** (`voucher`, `specialization`, `campus`, `sex`): source values are compared NFKC, lower-case, trimmed, whitespace-collapsed. A configured entry wins; otherwise a value equal to a canonical code (case-insensitive) maps to itself; anything else is `UNMAPPED_VALUE` (`UNKNOWN_CAMPUS` for campus). If the `sex` map is empty the value passes through; if it has entries, unlisted values are errors. Nothing is guessed.

### Parse settings (stored per mapping, not hardcoded)

| Setting | Assumed default | Notes |
| --- | --- | --- |
| `encoding` | `UTF-8` (BOM tolerated) | Or `windows-1252`. Never auto-detected; bytes invalid for the chosen encoding fail the file (`INVALID_ENCODING`). UTF-16 is refused. |
| `delimiter` | `,` | `;` and tab. |
| `headerRow` | `1` | 1–20; earlier records are skipped. |
| `dateFormats` | `yyyy-MM-dd`, `MM/dd/yyyy` | 1–5 formats. |
| `normalizeNameCase` | `true` | See Names. |

### Limits (configurable, `ici.import.*`)

| Limit | Default | Violation |
| --- | --- | --- |
| File size | 5,000,000 bytes | `FILE_TOO_LARGE` → 413, nothing persisted |
| Data rows | 20,000 | `TOO_MANY_ROWS` → 413, nothing persisted |
| Columns per row | 64 | `TOO_MANY_COLUMNS` → 422, failed batch recorded |
| Characters per cell | 1,000 | `CELL_TOO_LONG` → 422 (usually a wrong delimiter or quoting) |

`spring.servlet.multipart` is raised to 5 MB file / 6 MB request so the application limit is the one that answers.

## Column mapping

A mapping (`import_column_mappings`) holds `mapping` and `settings` JSON.

```json
{
  "fields": {
    "studentNumber": { "required": true, "aliases": ["student_number", "student_no", "..."] },
    "campus":        { "required": false, "aliases": ["campus", "campus_code", "branch"] }
  },
  "valueMaps": {
    "voucher": { "<legacy value>": "QVR" }, "specialization": { "<legacy value>": "STEM" },
    "campus": { "<legacy value>": "ILIGAN" }, "sex": {}
  }
}
```

- **Header resolution:** headers and aliases are normalized — Unicode NFKC, lower-case, trim, every run of non-alphanumeric characters becomes one `_`, and leading/trailing `_` are dropped (so `Student No.` equals the alias `student_no`). A target field takes the one source header whose normalized form equals one of its aliases. A required field with no match → `MISSING_COLUMN`; two headers matching one field, or one header matching two fields → `AMBIGUOUS_HEADER`. Both fail the whole file (422). Nothing is matched by position or similarity.
- **Default mapping:** seeded, `is_default`, `real_file_validated=false`, containing the assumed aliases and **empty** value maps (only canonical codes translate).
- **Snapshot:** a batch copies the effective mapping and settings into `mapping_snapshot` when staged. Editing a mapping later never changes a staged batch or its rows; only new uploads use the new version.
- **Validation on save** (`ImportMappingService.validate`): known fields and keys only; the six mandatory fields present, required and with at least one alias; at most 20 aliases of 100 characters; no alias shared by two fields; value-map targets must exist in `vouchers`/`programs`/`campuses` (sex: 1–30 characters); no source value listed twice after normalization; known settings only, encoding/delimiter from the allowed lists, `headerRow` 1–20, valid non-ambiguous date formats. Failures return 400 with every problem listed.
- **Who may change mappings:** only holders of `imports.mappings.manage` (Administrator) can create, edit, activate or deactivate. Registrars can list mappings and choose one at upload. Edits require the current `version` (409 if stale), names are unique case-insensitively, the default cannot be unset (choose another default), and a mapping used by an uncommitted (`validated`) batch cannot be deactivated.

## Planned API contract (phase 3, not implemented)

Base `/api/legacy-student-imports`, existing Bearer authentication. `imports.read` for reads, `imports.manage` for upload/review/commit, `imports.mappings.manage` for mapping writes. Permission and campus come from the same current role grant (`AccessPolicy.campuses`).

### Mappings
- `GET /mappings`, `GET /mappings/{id}` — `imports.read` or `imports.manage`.
- `POST /mappings` → 201, `PUT /mappings/{id}` (with `version`) — Administrator only; body `{ name, mapping, settings, isDefault, active, realFileValidated }`.

### Batches
- `POST /batches` — `multipart/form-data`: `file`, `campusId`, optional `mappingId`. **Always a dry run:** parse, validate, detect duplicates, stage rows; nothing is written to `students`. Returns 201 with the batch summary (`status: validated`, counts, mapping, checksum). Needs `imports.manage` for `campusId` (403 otherwise).
- `GET /batches?campusId=&page=` — history, 25 per page. A batch is visible to holders of `imports.read` for its campus; otherwise 404.
- `GET /batches/{id}`, `GET /batches/{id}/rows?status=&page=` — rows with `rawValues`, `normalizedValues`, messages (`code`, `field`, `severity`, `detail`) and duplicate evidence.
- `POST /batches/{id}/rows/{rowId}/review` — `{ version, decision: "same_person"|"distinct", reason }` (reason required) for `duplicate_flagged` rows. `same_person` → `skipped` (nothing inserted, existing record untouched). `distinct` → `valid`, refused with 409 when the LRN equals the matched record's (ICI2-13: distinct people cannot share an LRN).
- `POST /batches/{id}/commit` — `{ version, expectedValidCount }`. Inserts only `valid` rows in one transaction with `app.actor_id` set, records `student_id`, marks rows `imported`, sets counts/`imported_by/at` and status `imported`. `invalid`, `duplicate_flagged` and `skipped` rows are never inserted. Rows are re-checked at commit (campus scope, number/LRN uniqueness, duplicates against students created since staging); a row that now conflicts is flagged or invalidated instead of failing the batch. `expectedValidCount` guards against committing a different result than reviewed (409).

No delete/cancel route; a `validated` batch is inert, and an `imported` batch cannot be reverted by this API.

### Idempotency
- The SHA-256 of the file bytes is the checksum. Identical bytes while a non-`failed` batch exists → **409** `{ batchId, status }` (`ImportConflictException`); a `failed` batch does not block re-upload.
- Repeating `commit` on an `imported` batch returns 200 with the original result and inserts nothing; row status and the unique `student_id`/`student_number`/`lrn` constraints enforce this. A constraint violation becomes a row-level exception, never a 500.

### Status codes
| Code | When |
| --- | --- |
| 201 / 200 | Staged or created / reads, review, commit (including an idempotent repeat). |
| 400 | Bad parameters, unknown campus, invalid mapping. |
| 403 / 404 | No permission for the campus / not visible. |
| 409 | Identical file already staged or imported; stale `version`; commit of a non-`validated` batch; `expectedValidCount` mismatch; review of a non-flagged row; `distinct` with a shared LRN; mapping name taken or in use. |
| 413 | File over the size limit or more than the row limit. Nothing is persisted. |
| 415 | Not a CSV (phase 3 check). |
| 422 | Unreadable encoding/quoting, missing/ambiguous headers, too many columns, oversized cell, empty file; a `failed` batch with batch-level exceptions is recorded. Unknown/inactive mapping is 422 without a batch. |

Messages never echo LRNs, birth dates or other personal values; only short code-like values (voucher, strand, campus) are quoted.

## Row validation outcomes

Row status: `valid`, `invalid` (≥1 error), `duplicate_flagged`, `imported`, and `skipped` (set only by a `same_person` review, never by staging). Codes: `REQUIRED_MISSING`, `INVALID_LRN`, `INVALID_DATE`, `AMBIGUOUS_DATE`, `FUTURE_DATE`, `TOO_LONG`, `INVALID_CHARACTER`, `UNMAPPED_VALUE`, `UNKNOWN_CAMPUS`, `CAMPUS_MISMATCH`, `COLUMN_COUNT_MISMATCH`, `STUDENT_NUMBER_CONFLICT`, `DUPLICATE_IN_BATCH`, warning `DUPLICATE_CANDIDATE`. `normalized_values` is stored only for rows that are not `invalid`.

## Duplicate handling

`ph.edu.ici.enrollment.IdentityMatcher` holds the ICI2-13 rules and is used by both `DuplicateService` (unchanged behavior) and import staging:

1. **LRN exact** — same non-null LRN.
2. **Name + birth date** — same birth date, last-name similarity ≥ 0.8 and first given-name token similarity ≥ 0.8 (accent-stripped, case-folded, letters only, normalized Levenshtein).

For each non-invalid row, candidates are searched across **every campus** in: `students`; live `applications` (not `rejected`/`withdrawn`; one linked to an already-matched student is not reported twice); uncommitted (`validated`) batches of **other** campuses; and other rows of the same file. Same-campus earlier batches are deliberately ignored so a corrected re-upload is not flagged against the registrar's own earlier staging; commit re-checks against created students. Candidates are fetched by LRN and birth date and compared only inside those buckets, so large files do not cost quadratic comparisons.

- A matching row is `duplicate_flagged` and **not inserted**; `duplicate_evidence` lists up to 20 entries `{ kind: student | application | staged_row | row, rule, nameScore, campusVisible, id/rowNumber/batchId }`, and a `DUPLICATE_CANDIDATE` warning is recorded per entry (first 10). Existing records are never merged, overwritten or updated.
- **Other campuses:** `campusVisible` is true only when the reviewer has `imports.read`/`imports.manage` for that record's campus. Otherwise only `kind`, `rule` and `nameScore` are shown (no id, batch or row), the message says the record is at a campus the reviewer cannot view, and review needs someone with scope over both sides (normally an Administrator). An import never moves a student between campuses.
- A student number already used by a *different* person → `invalid`, `STUDENT_NUMBER_CONFLICT`. The same number *and* a match → flagged.
- The same student number twice in a file for different people → the later row is `invalid`, `DUPLICATE_IN_BATCH`; for the same person both rows are flagged against each other.
- `duplicate_cases` is not reused: it requires an `application_id`. Import decisions live on `import_rows` (decision, reason, actor, time) and the audit log.

## Implementation map (phase 2)

`ph.edu.ici.imports`: `CsvParser` (decoding, RFC 4180, limits), `HeaderResolver`, `RowNormalizer`, `ImportMapping`/`ImportText`/`ReferenceData`/`RowProblem`/`ImportLimits`, `ImportMappingService` (list/get/validate/create/update/snapshot), `ImportValidationService.stage(campusId, fileName, bytes, mappingId)`, and `ImportFileException`/`ImportConflictException` that phase 3 maps to 413/422/409.

## Open questions for the registrar — these block the parsers

1. A real (even anonymized, few-row) export to confirm the **header names**, and which tool produced it.
2. The **encoding and delimiter** (UTF-8 vs Windows-1252; comma vs semicolon vs tab).
3. The **date format** of birth dates (ISO, `MM/dd/yyyy`, or `dd/MM/yyyy`).
4. How **voucher** and **specialization** values are written in the legacy data (to fill the value maps).
5. Whether the file has a **campus column**.
6. Whether exports are **one file per campus** (assumed) or can contain several campuses.

### Later (do not block parsing)

- Keep the legacy student number as `students.student_number`, or issue new numbers?
- Is the legacy LRN always 12 digits; reject or import blank for placeholder values?
- Should rows with blank voucher/specialization be rejected (current assumption) or imported with a registrar-assigned value?
- Is title-casing of all-caps/all-lower names acceptable, and are middle names ever initials; how are suffixes (Jr., III) stored?
- Which campus owns a learner who appears in two campuses?
- Is a registrar sign-off / `reconciled` step wanted, and how do flagged or invalid rows count against it?
- Retention period for raw legacy values in `import_rows` after commit.
- Should "same person" rows ever update the existing student (e.g., fill a missing LRN), or is skip-only correct?
