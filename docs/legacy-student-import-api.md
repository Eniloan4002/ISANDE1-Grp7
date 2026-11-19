# Legacy student CSV import — ICI2-37 (design, phase 1)

Design and schema only. **No parser, endpoint or service exists yet**; the routes below are a planned contract. Backend and docs only; no screens or styles.

## Purpose and scope

Bring student identity records from the legacy ICI Local CSV exports into the permanent `students` table, with per-row provenance, validation results, duplicate flagging and registrar review. In scope: student master fields only. Out of scope: grades, enrollments, guardians, addresses, requirements, documents, and automatic reconciliation sign-off.

## Status of this format — assumed, not validated

**The real legacy exports have not been received.** The columns, header aliases, date formats, encoding, delimiter and limits in this document are an *assumed* specification. They are to be validated on synthetic fixtures only. Real-file validation is pending; until then the seeded mapping carries `real_file_validated = false`, and no school policy or legacy value translation is presumed. When the real file arrives, adjust the stored mapping (no code change should be needed for header names, aliases, date formats, delimiter, encoding or value translations) and record the validation.

## Existing tables reused (V1/V3) and what V19 adds

| Existing | Used as |
| --- | --- |
| `import_batches` (source_name, checksum, status `staged/validated/imported/reconciled/failed`, source_count, imported_count, started_at, signed_off_*) | One uploaded file. `source_name` is the source file name. |
| `import_exceptions` (batch_id, row_number, error_code, detail, resolved) | One record per problem, batch-level or row-level. |

V19 (`V19__legacy_student_import_staging.sql`) adds only what was missing:

- `import_batches`: `source_type`, `mapping_id`, `mapping_snapshot`, `file_size_bytes`, `valid_count`, `invalid_count`, `flagged_count`, `skipped_count`, `imported_by`, `imported_at`, `version`; unique `(source_type, checksum)` while not `failed`; a count-balance check (`valid+invalid+flagged+skipped+imported = source_count` once validated) and a commit-actor check, both `NOT VALID` so any pre-existing rows are not rejected.
- `import_rows` (new): per-row provenance and status — `batch_id`, `row_number` (unique per batch), `status`, resolved `campus_id`, `raw_values`, `normalized_values`, `duplicate_evidence`, review decision/reason/actor/time, created `student_id` (unique), `version`, standard metadata columns.
- `import_exceptions`: `import_row_id`, `field_name`, `severity` (`error`/`warning`), bounded `detail` (1–1000 chars).
- `import_column_mappings` (new): stored, editable mapping and parse settings, with `is_default` (one per source type), `active`, `real_file_validated`, `version`.
- Permissions `imports.read` and `imports.manage` for `ROLE_ADMIN` and `ROLE_REGISTRAR`.

Provenance of an imported student is `import_rows.student_id → import_rows.batch_id → import_batches.source_name/checksum`, plus the row number and raw values. Each row's `raw_values` is the untouched header→text map as read; `normalized_values` is the parsed result. The file name is stored once per batch, not repeated on every row.

Audit: mappings and batches use the standard `audit_change` trigger. `import_rows` uses `import_row_audit`, which **omits `raw_values` and `normalized_values`** so personal data is not copied into `audit_logs`.

Reconciliation caveat: V1 only allows `status='reconciled'` when `source_count = imported_count` and a sign-off exists. Batches with invalid, flagged or skipped rows can therefore never reach `reconciled`; this design stops at `imported`. Reconciliation sign-off is not part of ICI2-37 unless the registrar wants it (see questions).

## Source file format (assumed)

CSV per RFC 4180: header row, quoted fields, doubled quotes, embedded newlines inside quotes. One file = one import batch. All values are read as text; nothing is trimmed from `raw_values`, values are trimmed (and Unicode NFC-normalized) for `normalized_values`.

| Target field | Assumed source meaning | Type / rule | Required | Maps to |
| --- | --- | --- | --- | --- |
| `studentNumber` | Legacy student number | text, 1–40 chars | **Required** | `students.student_number` |
| `lrn` | Legacy LRN | exactly 12 digits | Optional | `students.lrn` |
| `lastName` | Last name | text, ≤150, not blank | **Required** | `students.last_name` |
| `firstName` | First name | text, ≤150, not blank | **Required** | `students.first_name` |
| `middleName` | Middle name | text, ≤150 | Optional | `students.middle_name` |
| `birthDate` | Birth date | date (see formats), in the past | **Required** | `students.birth_date` |
| `sex` | Sex | text, ≤30, optional value map | Optional | `students.sex` |
| `previousSchool` | Previous school | text, ≤250 | Optional | `students.previous_school_name` |
| `voucher` | Voucher classification | value map to `QVR/ESC/ALS/REGULAR` | **Required** | `students.voucher_code` |
| `specialization` | Strand/specialization | value map to `programs.code` | **Required** | `students.program_id` (track derived exactly as `StudentService` does) |
| `campus` | Campus | value map to `campuses.code` | **Required** | `students.campus_id` |

"Required" for voucher, specialization and campus mirrors `StudentInput`, which cannot create a student without them; whether the legacy data always has them is an open question. Unmapped or unrecognised values for the three mapped fields (and sex, if a map is configured) are row errors; nothing is guessed. A value equal to a canonical code (case-insensitive, trimmed) maps to itself without needing an entry. Extra columns in the file are ignored (kept in `raw_values`); a missing *required* header fails the whole file (422).

Guardians, address, phone, email, household income, suffix and active flag are not in the import; created students use `active = true`, empty address and no guardians, so the registrar must complete them through the Student Master.

### Parse settings (stored per mapping, not hardcoded)

| Setting | Assumed default | Notes |
| --- | --- | --- |
| `encoding` | `UTF-8` (BOM tolerated) | Alternative: `windows-1252` for older exports. Never auto-guessed; invalid bytes for the chosen encoding fail the file (422). |
| `delimiter` | `,` | `;` and tab selectable. |
| `headerRow` | `1` | |
| `dateFormats` | `yyyy-MM-dd`, `MM/dd/yyyy` | Exact, ordered list. A mapping cannot be saved with both `MM/dd/yyyy` and `dd/MM/yyyy` (a value like 03/04/2010 is ambiguous). Two-digit years, month names and Excel serial numbers are rejected unless a format is added. Future dates and dates before 1900 are row errors. |

### Limits (proposed, configurable)

| Limit | Proposed |
| --- | --- |
| File size | 5 MB |
| Rows | 20,000 data rows |
| Columns / cell length | 64 columns / 1,000 chars |
| Content type | `text/csv` or `application/vnd.ms-excel`/`text/plain` with `.csv`; real content is validated, not only the name. |

The current `spring.servlet.multipart` limit is 1,000,000 bytes / 2 MB request; phase 2 must raise it (and keep the application-level limit authoritative, answering 413 itself).

## Column mapping

A mapping (`import_column_mappings`) holds `mapping` and `settings` JSON.

```json
{
  "fields": {
    "studentNumber": { "required": true, "aliases": ["student_number", "student_no", "..."] },
    "birthDate":     { "required": true, "aliases": ["birth_date", "dob", "..."] }
  },
  "valueMaps": {
    "voucher": { "<legacy value>": "QVR" },
    "specialization": { "<legacy value>": "STEM" },
    "campus": { "<legacy value>": "ILIGAN" },
    "sex": {}
  }
}
```

- Header resolution: source headers and aliases are normalized (Unicode NFKC, lower-case, trim, every run of non-alphanumerics → `_`). Each target field takes the single source header whose normalized form equals one of its aliases. Zero matches for a required field → file error `MISSING_COLUMN`; two source headers matching one field, or one header matching two fields → file error `AMBIGUOUS_HEADER`. Nothing is matched by position or fuzzy similarity.
- The default mapping (seeded, `is_default`, `real_file_validated=false`) contains the assumed aliases listed in V19 and **empty** value maps. It translates nothing beyond canonical codes.
- An upload names `mappingId` or uses the active default. The effective mapping and settings are copied into `import_batches.mapping_snapshot`; later edits never change a staged batch.
- Mapping edits require `version` (stale → 409). Mappings are global (not campus-scoped); proposed rule: only a caller holding `imports.manage` at every active campus may create/edit/activate one (see questions). Validation on save: known field names, required fields present, alias normalization collisions, value-map targets must exist in `vouchers`/`programs`/`campuses`, date format list valid.

## Planned API contract (not implemented)

Base `/api/legacy-student-imports`, existing Bearer authentication. `imports.read` for `GET`; `imports.manage` for everything else. Permission and campus must come from the same current role grant (`AccessPolicy.campuses`), as elsewhere.

### Mappings

- `GET /mappings`, `GET /mappings/{id}`
- `POST /mappings` → 201: `{ "name", "mapping", "settings", "isDefault" }`
- `PUT /mappings/{id}`: same body plus `version`, `active`; 409 on stale version; 409 if it would leave no active default, or deactivate a mapping referenced by an uncommitted batch.

### Batches

- `POST /batches` — `multipart/form-data`: `file` (required), `mappingId` (optional). **Always a dry run**: it parses, validates, runs duplicate detection and stages rows, but writes nothing to `students`. Returns 201 with the batch summary (`status: validated`, counts by row status, mapping, checksum). Rows are persisted so the same staged result can be committed without re-uploading. Batch-level failures (unreadable encoding, missing required header, unknown mapping) return 422 and record a `failed` batch with exceptions, so a corrected file can be re-uploaded.
- `GET /batches?page=` — history (25/page), newest first.
- `GET /batches/{id}` — summary, mapping snapshot, counts, batch-level exceptions.
- `GET /batches/{id}/rows?status=&page=` — staged rows with `rawValues`, `normalizedValues`, messages (`code`, `field`, `severity`, `detail`) and duplicate evidence.
- `POST /batches/{id}/rows/{rowId}/review` — `{ "version", "decision": "same_person" | "distinct", "reason" }` for `duplicate_flagged` rows. `same_person` → `skipped` (nothing inserted, existing record untouched). `distinct` → `valid` (eligible on commit), refused with 409 if the LRN equals the matched record's LRN (same rule as ICI2-13: distinct people cannot share an LRN).
- `POST /batches/{id}/commit` — `{ "version", "expectedValidCount" }`. **Commit semantics:** inserts only rows with status `valid`, in one transaction, setting `app.actor_id` like other services; writes `student_id`, marks rows `imported`, updates counts, `imported_by/at`, and the batch status to `imported`. `invalid`, `duplicate_flagged` and `skipped` rows are never inserted. Rows are re-checked at commit (campus scope, LRN/student-number uniqueness, duplicates against students created since staging); a row that now conflicts becomes `duplicate_flagged`/`invalid` instead of failing the batch, and the response lists them. `expectedValidCount` guards against committing a different result than the one the registrar reviewed (409 on mismatch). Commit is all-or-nothing for the rows it inserts.

There is no delete or cancel route; a batch left in `validated` is inert. An `imported` batch cannot be reverted by this API.

### Permissions and campus scope

- Registrar and Admin only (V19 seeds both permissions to `ROLE_REGISTRAR` and `ROLE_ADMIN`). Other roles get 403.
- Each row's resolved campus must be within the caller's `imports.manage` campuses; otherwise the row is `invalid` with `CAMPUS_OUT_OF_SCOPE` and its `raw_values` are **not stored** (`{}`), so one campus's registrar never retains another's personal data.
- Batch/row visibility for `imports.read`: rows only in the caller's campuses; a batch the caller has no row in returns 404.

### Idempotency

- The SHA-256 of the file bytes is the batch checksum. Uploading identical bytes while a non-`failed` batch exists returns **409** with `{ "batchId", "status" }` instead of staging again. A `failed` batch does not block re-upload.
- Repeating `commit` on an `imported` batch with the same `version` returns 200 with the original result and changes nothing; a second commit never inserts again (row status and the unique `student_id`/`student_number`/`lrn` constraints enforce this).
- `students.student_number` and `students.lrn` unique constraints are the last line of defence; a violation becomes a row-level exception, never a 500.

### Status codes

| Code | When |
| --- | --- |
| 201 | Batch staged / mapping created. |
| 200 | Reads, review, commit (including an idempotent repeat). |
| 400 | Bad parameters or body. |
| 403 / 404 | No permission / not in your campus scope. |
| 409 | Identical file already staged or imported; stale `version`; commit of a batch not in `validated`; `expectedValidCount` mismatch; review of a non-flagged row; `distinct` with a shared LRN. |
| 413 | File exceeds the size limit, or more rows than the row limit (distinct error codes). Nothing is persisted. |
| 415 | Not a CSV. |
| 422 | File cannot be read with the configured encoding/delimiter, or required headers are missing/ambiguous (a `failed` batch is recorded). |

Error text must not echo full row contents; LRN and birth date are masked in messages.

## Row validation outcomes

Row status: `valid`, `invalid` (≥1 error), `duplicate_flagged`, `skipped` (reviewed `same_person`), `imported`. Codes (draft): `REQUIRED_MISSING`, `INVALID_LRN`, `INVALID_DATE`, `AMBIGUOUS_DATE`, `FUTURE_DATE`, `TOO_LONG`, `UNMAPPED_VALUE`, `UNKNOWN_CAMPUS`, `CAMPUS_OUT_OF_SCOPE`, `STUDENT_NUMBER_CONFLICT`, `DUPLICATE_IN_BATCH`, `DUPLICATE_CANDIDATE`, `COLUMN_COUNT_MISMATCH`.

## Duplicate handling

Reuse the ICI2-13 matching rules from `DuplicateService`, without changing their behavior:

1. **LRN exact** — same non-null LRN.
2. **Name + birth date** — same birth date, last-name similarity ≥ 0.8 and first given-name token similarity ≥ 0.8 (accent-stripped, case-folded, normalized Levenshtein).

Candidates are searched against all `students` and against live `applications` (not `rejected`/`withdrawn`), across every campus, and against other rows of the same batch. A matching row is **flagged, not inserted**; it gets status `duplicate_flagged` and `duplicate_evidence` entries `{kind: student|application|row, id, rule, score, campusVisible}`. Existing records are never merged, overwritten or updated by an import. `same_person` is therefore "skip, keep the existing record"; completing or correcting an existing student remains a Student Master action.

Additional rules:

- Student number already used by a *different* person (no LRN/name+DOB match) → `invalid`, `STUDENT_NUMBER_CONFLICT`. Same number *and* a match → flagged.
- Same person twice inside one file: all rows in the group are flagged against each other; none is inserted until reviewed.
- **Same student in two campuses' exports:** the second batch's row matches the student created from the first (or the first batch's staged row if not yet committed) and is flagged. Evidence from a campus outside the reviewer's scope shows only `campusVisible:false` plus the rule/score, no name or number, matching the `canReview` behaviour of the existing duplicate list. Such a row can be reviewed only by someone with scope over both sides (typically Admin). The import never moves a student between campuses.
- `duplicate_cases` is **not** reused: it requires an `application_id`, and import rows have no application. Decision history for import rows lives on `import_rows` (decision, reason, actor, time) and the audit log.
- Implementation note for phase 2: the matching code is private to `DuplicateService` and works on application rows. Extract a small shared matcher (same thresholds, same normalization) instead of copying it, keeping `DuplicateService` behavior and its tests unchanged.

## Open questions for the registrar

1. Can we get a real export (even a few anonymized rows) to confirm column names, delimiter, encoding and date format? Which tool produced it?
2. What are the legacy values for voucher classification, specialization and campus (so the value maps can be filled)?
3. Is `MM/dd/yyyy` the legacy date format, or `dd/MM/yyyy`, or ISO?
4. Is the legacy student number the number the school wants to keep as `students.student_number` (new students currently get a generated `STU-…` number)?
5. Is the "legacy LRN" always 12 digits? What about missing, placeholder or old-format values — reject, or import with no LRN?
6. If voucher, specialization or campus is blank in the legacy data, should the row be rejected (current assumption) or imported with a registrar-assigned value?
7. Does the legacy export have separate files per campus, or one file with a campus column? Which campus owns a student who appears in two campuses?
8. Should names with suffixes or "Jr."/"III" be split, and is "middle name" ever stored as an initial?
9. Who may edit the global column mapping — Admin only, or any Registrar?
10. Is a registrar sign-off/`reconciled` step wanted for imports, and how should rows left flagged or invalid count against it?
11. Retention: how long may raw legacy values remain in `import_rows` after a batch is committed?
12. Should flagged "same person" rows ever update the existing student (e.g., fill a missing LRN), or is skip-only correct?
