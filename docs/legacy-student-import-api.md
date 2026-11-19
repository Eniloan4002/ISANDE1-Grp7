# Legacy student CSV import — ICI2-37

All three phases are implemented: schema (V19, V20), parser/mapper/validation services, the shared duplicate matcher, and the HTTP API below. Backend and docs only; no screens or styles.

## Purpose and scope

Bring student identity records from the legacy ICI Local CSV exports into the permanent `students` table, with per-row provenance, validation results, duplicate flagging and registrar review. In scope: student master fields only. Out of scope: grades, enrollments, guardians, addresses, requirements, documents, and automatic reconciliation sign-off.

## Status of this format — assumed, not validated

**The real legacy exports have not been received.** The columns, header aliases, date formats, encoding, delimiter and limits in this document are an *assumed* specification, validated only against **synthetic** fixtures (`backend/src/test/resources/import/`, all invented data). Real-file validation is pending. Until then the seeded mapping carries `real_file_validated = false`, and no school policy or legacy value translation is presumed. When the real file arrives, an Administrator edits the stored mapping (header names, aliases, date formats, delimiter, encoding, value translations) — no code change should be needed — and records the validation.

## Existing tables reused (V1/V3) and what V19/V20 add

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

V20 (`V20__legacy_import_review_and_links.sql`) adds the review model on `import_rows`: `matched_student_id`; `review_decision` now allows `same_person`, `distinct` and `skip`; checks tie `same_person` to a linked student and `skip` to status `skipped`; reviewed rows can only be `valid`, `skipped` or `imported`; the "one row per student" unique index now applies only to rows that *created* a student (linked rows may point at an existing one).

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
- **Names:** by default names are **kept exactly as written** (only whitespace is cleaned). If a mapping sets `normalizeNameCase: true`, a name that is *entirely* upper-case or *entirely* lower-case is title-cased per word (after space, hyphen or apostrophe; roman numerals II–IX stay upper-case). Mixed-case names (`McDonald`, `dela Cruz`) are never altered. Set it to `false` to keep names exactly as written.
- **Dates:** exact match against the configured `dateFormats` only (four-digit year; two-digit month and day; `-`, `/` or `.` separator). Two-digit years, month names, spreadsheet serial numbers, times, single-digit months/days, impossible dates (02/30) and dates before 1900 are `INVALID_DATE`; today or later is `FUTURE_DATE`. A value readable under two configured formats with different results is `AMBIGUOUS_DATE`. A mapping cannot be saved with both `MM/dd/yyyy` and `dd/MM/yyyy` (same separator, day and month swapped).
- **Value maps** (`voucher`, `specialization`, `campus`, `sex`): source values are compared NFKC, lower-case, trimmed, whitespace-collapsed. A configured entry wins; otherwise a value equal to a canonical code (case-insensitive) maps to itself; anything else is `UNMAPPED_VALUE` (`UNKNOWN_CAMPUS` for campus). If the `sex` map is empty the value passes through; if it has entries, unlisted values are errors. Nothing is guessed.

### Parse settings (stored per mapping, not hardcoded)

| Setting | Assumed default | Notes |
| --- | --- | --- |
| `encoding` | `UTF-8` (BOM tolerated) | Or `windows-1252`. Never auto-detected; bytes invalid for the chosen encoding fail the file (`INVALID_ENCODING`). UTF-16 is refused. |
| `delimiter` | `,` | `;` and tab. |
| `headerRow` | `1` | 1–20; earlier records are skipped. |
| `dateFormats` | `yyyy-MM-dd`, `MM/dd/yyyy` | 1–5 formats. |
| `normalizeNameCase` | `false` | Optional per-mapping switch; see Names. |

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

## HTTP API

Base `/api/legacy-student-imports`, existing Bearer authentication, JSON (upload is `multipart/form-data`). The actor and campus always come from the authenticated account and its current role grant (`AccessPolicy`); the request never names an actor. Errors are RFC 9457 problem details (`status`, `detail`, plus `code`/`batchId` where noted).

| Method and path | Permission | Purpose |
| --- | --- | --- |
| `GET /mappings`, `GET /mappings/{id}` | `imports.read` | List / read stored mappings |
| `POST /mappings` | `imports.mappings.manage` (Admin) | Create (201) |
| `PUT /mappings/{id}` | `imports.mappings.manage` (Admin) | Edit, activate/deactivate, make default |
| `POST /batches` | `imports.manage` for `campusId` | Upload: **always a dry run** (201) |
| `GET /batches?campusId=&status=&page=&size=` | `imports.read` | History, newest first (size ≤ 100) |
| `GET /batches/{id}` | `imports.read` | Counts, mapping used, batch-level exceptions, error summary |
| `GET /batches/{id}/rows?status=&page=&size=` | `imports.read` | Staged rows (size ≤ 100) |
| `GET /batches/{id}/errors?severity=&rowNumber=&page=&size=` | `imports.read` | Row and batch problems |
| `POST /batches/{id}/rows/{rowId}/review` | `imports.manage` | Record a decision |
| `POST /batches/{id}/commit` | `imports.manage` | Create/link students |

A batch is visible only to holders of `imports.read` for its campus (otherwise 404); writes need `imports.manage` for that campus (otherwise 403). Registrars of another campus, and every role without these permissions (teacher, principal, guidance, cashier reference), get 403/404. Commit also needs `enrollment.manage` for the campus because students are created through `StudentService` (Registrar and Admin have both).

### Upload (dry run)

`POST /batches` with form fields `campusId`, optional `mappingId`, and `file` (`.csv`). It parses, validates, detects duplicates and stages rows. **Nothing is written to `students`.**

```
curl -H "Authorization: Bearer $TOKEN" -F campusId=1 -F file=@synthetic-basic.csv \
     http://127.0.0.1:8081/api/legacy-student-imports/batches
```
```json
201 { "id": 42, "sourceName": "synthetic-basic.csv", "status": "validated", "campusId": 1,
      "checksum": "9f2c…", "fileSizeBytes": 612, "sourceCount": 3, "validCount": 3, "invalidCount": 0,
      "flaggedCount": 0, "skippedCount": 0, "importedCount": 0, "version": 0,
      "startedAt": "2026-11-19T05:02:11Z", "createdBy": 7, "importedBy": null, "importedAt": null,
      "mapping": { "id": 1, "name": "Assumed default (unvalidated)", "version": 0, "realFileValidated": false },
      "batchExceptions": [], "errorSummary": [] }
```
Identical bytes already staged or imported (checksum) → `409 { "code": "DUPLICATE_FILE", "batchId": 42, "batchStatus": "validated" }`. A `failed` batch does not block re-upload.

### Rows and errors

```json
GET /batches/42/rows?status=duplicate_flagged
200 { "total": 1, "page": 0, "size": 25, "items": [ {
  "id": 311, "rowNumber": 2, "status": "duplicate_flagged", "version": 0,
  "rawValues": { "Student No": "SYN-…-01", "LRN": "99…01", "Last Name": "…" },
  "normalizedValues": { "studentNumber": "SYN-…-01", "lrn": "99…01", "birthDate": "2009-03-14", "voucherCode": "QVR", "programCode": "STEM", "track": "ACADEMIC", "campusId": 1 },
  "duplicateEvidence": [ { "kind": "student", "rule": "LRN_EXACT", "nameScore": 0.12, "campusVisible": true, "id": 118 } ],
  "review": null, "studentId": null,
  "messages": [ { "code": "DUPLICATE_CANDIDATE", "field": null, "severity": "warning", "detail": "Possible duplicate of an existing student (same LRN). Review before importing." } ] } ] }
```
`GET /batches/{id}/errors` returns `{ items: [{ id, rowNumber, rowId, code, field, severity, detail, resolved }], total, page, size }`; batch-level problems have a null `rowNumber`.

### Review

`POST /batches/{id}/rows/{rowId}/review` — `{ "version": <row version>, "decision": "same_person" | "distinct" | "skip", "reason": "…", "studentId": <required for same_person> }`. Reason is mandatory. Only while the batch is `validated`. Each decision bumps the row version and the batch version.

- **`same_person`** — the row describes an existing student. `studentId` must be one of that row's visible `student` evidence entries, active and in the caller's campus scope. The row becomes `valid` with a link; at commit it is marked `imported` against the existing student and **no student is created**.
- **`distinct`** — a different person; the row becomes `valid` and a student is created at commit. Refused (409) if any match shares the LRN (ICI2-13) or the student number already belongs to another student.
- **`skip`** — never imported; allowed on valid or flagged rows.
- Rows that matched a record at a campus the reviewer cannot view can be decided only by someone with `imports.manage` at every active campus (Administrator); others get 403.

```json
POST …/rows/311/review { "version": 0, "decision": "same_person", "reason": "Same learner, existing record", "studentId": 118 }
200 { "row": { "id": 311, "status": "valid", "version": 1, "review": { "decision": "same_person", "matchedStudentId": 118, … } }, "batch": { …, "validCount": 3, "flaggedCount": 0, "version": 1 } }
```
Stale row version, an unlisted student, or a row that was never flagged → 409.

### Commit

`POST /batches/{id}/commit` — `{ "version": <batch version>, "expectedValidCount": 3 }`. In **one transaction**, under a `FOR UPDATE NOWAIT` lock on the batch:

1. Refuse (409) unless the batch is `validated`, `version` matches, **no row is still `duplicate_flagged`**, `expectedValidCount` equals the number of `valid` rows (including reviewed-distinct and linked rows), and there is at least one.
2. **Recheck** rows about to be created against records written after staging (students/applications changed since the batch started, plus student-number ownership). If any now conflict, nothing is imported: those rows are demoted to `duplicate_flagged` or `invalid` (with new exceptions), counts are updated, and the response is 409 so the registrar reviews again. Linked students that were archived or moved are demoted the same way.
3. Create each student through `StudentService.create` with the **legacy student number** (same validation and `trackFor` as the student form; `created_by`/audit actor is the committing user), or link `same_person` rows to the existing student. Rows become `imported` with `student_id`.
4. Set `imported_count`, `imported_by`, `imported_at`, status `imported`.

If any student fails, the whole transaction rolls back (409/422, `Nothing was imported`), the batch stays `validated`, and the same commit can be retried after fixing the cause.

```json
200 { "batchId": 42, "status": "imported", "created": 2, "linked": 1, "skipped": 0, "invalid": 0,
      "importedCount": 3, "version": 4, "alreadyImported": false }
```
**Idempotency:** committing an already imported batch returns `200` with the same counts and `"alreadyImported": true` regardless of the supplied `version`; it never creates anything again. A commit that arrives while another commit holds the batch gets `409 Another commit of this batch is in progress`.

### Mappings

```json
POST /mappings { "name": "Registrar export 2026", "mapping": { "fields": { … }, "valueMaps": { … } }, "settings": { "encoding": "UTF-8", "delimiter": ",", "headerRow": 1, "dateFormats": ["yyyy-MM-dd","MM/dd/yyyy"], "normalizeNameCase": false }, "isDefault": false, "active": true, "realFileValidated": false }
201 { "id": 5, "name": "Registrar export 2026", "version": 0, "active": true, "isDefault": false, "realFileValidated": false, "mapping": { … }, "settings": { … } }
```
`PUT /mappings/{id}` takes the same body plus `"version"` (required). Invalid content → 400 listing every problem. Name taken (case-insensitive) → 409. Stale version → 409. Unsetting or deactivating the current default → 409 (make another mapping the default instead). Deactivating a mapping used by an uncommitted (`validated`) batch → 409. An inactive or unknown `mappingId` at upload → 422 (`UNKNOWN_MAPPING`, no batch recorded).

### Status codes

| Code | When |
| --- | --- |
| 200 | Reads, review, commit (including an idempotent repeat), mapping edit |
| 201 | Batch staged, mapping created |
| 400 | Bad parameters or body (unknown status, page/size out of range, blank reason, missing `version`, unknown campus, invalid mapping) |
| 401 | No/invalid token |
| 403 | Missing permission for the endpoint or campus; Admin-only mapping writes; reviews needing cross-campus scope |
| 404 | Batch/row/mapping not found or not visible to the caller |
| 409 | `DUPLICATE_FILE`; stale version; mapping name taken; batch not `validated`; flagged rows remain; `expectedValidCount` mismatch; review of an ineligible row; shared LRN on `distinct`; records changed since staging; concurrent commit; database conflict while creating students |
| 413 | File over 5,000,000 bytes (`FILE_TOO_LARGE`) or more than 20,000 data rows (`TOO_MANY_ROWS`); nothing persisted |
| 415 | Upload that is not a `.csv` |
| 422 | `EMPTY_FILE`, `INVALID_ENCODING`, `MALFORMED_CSV`, `TOO_MANY_COLUMNS`, `CELL_TOO_LONG`, `MISSING_COLUMN`, `AMBIGUOUS_HEADER` (a `failed` batch is recorded and its id returned); unknown/inactive mapping (no batch); a row whose student cannot be created at commit |

Messages never echo LRNs, birth dates or other personal values; only short code-like values (voucher, strand, campus) are quoted. `import_rows` audits omit `raw_values`/`normalized_values`. Note that the `students` rows created at commit are audited by the existing student audit trigger like any other student.

## Row validation outcomes

Row status: `valid`, `invalid` (≥1 error), `duplicate_flagged`, `skipped` (set only by a `skip` review, never by staging) and `imported` (created or linked at commit). Codes: `REQUIRED_MISSING`, `INVALID_LRN`, `INVALID_DATE`, `AMBIGUOUS_DATE`, `FUTURE_DATE`, `TOO_LONG`, `INVALID_CHARACTER`, `UNMAPPED_VALUE`, `UNKNOWN_CAMPUS`, `CAMPUS_MISMATCH`, `COLUMN_COUNT_MISMATCH`, `STUDENT_NUMBER_CONFLICT`, `DUPLICATE_IN_BATCH`, warning `DUPLICATE_CANDIDATE`. `normalized_values` is stored only for rows that are not `invalid`.

## Duplicate handling

`ph.edu.ici.enrollment.IdentityMatcher` holds the ICI2-13 rules and is used by both `DuplicateService` (unchanged behavior) and import staging:

1. **LRN exact** — same non-null LRN.
2. **Name + birth date** — same birth date, last-name similarity ≥ 0.8 and first given-name token similarity ≥ 0.8 (accent-stripped, case-folded, letters only, normalized Levenshtein).

For each non-invalid row, candidates are searched across **every campus** in: `students`; live `applications` (not `rejected`/`withdrawn`; one linked to an already-matched student is not reported twice); uncommitted (`validated`) batches of **other** campuses; and other rows of the same file. Same-campus earlier batches are deliberately ignored so a corrected re-upload is not flagged against the registrar's own earlier staging; commit re-checks against created students. Candidates are fetched by LRN and birth date and compared only inside those buckets, so large files do not cost quadratic comparisons.

- A matching row is `duplicate_flagged` and **not inserted**; `duplicate_evidence` lists up to 20 entries `{ kind: student | application | staged_row | row, rule, nameScore, campusVisible, id/rowNumber/batchId }`, and a `DUPLICATE_CANDIDATE` warning is recorded per entry (first 10). Existing records are never merged, overwritten or updated.
- **Other campuses:** `campusVisible` is true only when the reviewer has `imports.read`/`imports.manage` for that record's campus. Otherwise only `kind`, `rule` and `nameScore` are shown (no id, batch or row), the message says the record is at a campus the reviewer cannot view, and review needs someone with scope over both sides (normally an Administrator). An import never moves a student between campuses.
- A student number already used by a *different* person → `invalid`, `STUDENT_NUMBER_CONFLICT`. The same number *and* a match → flagged.
- The same student number twice in a file for different people → the later row is `invalid`, `DUPLICATE_IN_BATCH`; for the same person both rows are flagged against each other.
- `duplicate_cases` is not reused: it requires an `application_id`. Import decisions live on `import_rows` (decision, reason, actor, time, linked student) and the audit log.

## Implementation map

`ph.edu.ici.imports`: `CsvParser` (decoding, RFC 4180, limits), `HeaderResolver`, `RowNormalizer`, `ImportMapping`/`ImportText`/`ReferenceData`/`RowProblem`/`ImportLimits`, `ImportMappingService` (list/get/validate/create/update/snapshot), `ImportValidationService` (`stage` and the commit-time `recheck`), `ImportBatchService` (reads, review), `ImportCommitService`, `LegacyImportController` and `LegacyImportErrors`. `ImportFileException` and `ImportConflictException` map to 413/422 and 409. Shared code: `enrollment/IdentityMatcher`, `StudentService.trackFor` and `StudentService.create(input, studentNumber)`.

Tests use synthetic fixtures under `backend/src/test/resources/import/` (see its README) with embedded PostgreSQL and real local HTTP.

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
