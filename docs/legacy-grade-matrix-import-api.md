# Legacy historical grade matrix import — ICI2-38

**Status:** all three phases are implemented: design, migration V21 (no V22 was needed), the CSV parser, header classification, the stored mapping service, dry-run staging, and the HTTP API with review, commit, void and discard (`/api/legacy-grade-imports`). The layout is still **assumed** and validated on synthetic data only; real-file validation is pending. Backend and docs only; no screens or styles.

## Purpose and scope

Bring historical (pre-system) grades from a legacy "grade matrix" export — one row per student, one column per subject and grading period — into permanent, provenance-tracked storage, so that Form 137/TOR generation (ICI2-35) and reconciliation (ICI2-40) can read them. It builds on ICI2-37: the batch, row, exception and stored-mapping design are reused, and students must already exist (imported through ICI2-37 or the Student Master).

In scope: parsing and validating the matrix, matching rows to existing students, matching subjects/years/terms/periods to existing setup rows, registrar review of identity problems, an atomic commit, and storage. Out of scope: creating students, subjects, academic years, terms or grading periods; computing averages or final ratings; transmutation; honors, guidance or INC workflows on legacy data; editing committed grades (only voiding); `.xlsx`; any screen.

## Status of this format — assumed, not validated

**The real legacy grade files have not been received.** The layout, header aliases, mark vocabulary, scale, encoding and limits in this document are an *assumed* specification, validated only against **synthetic** fixtures (invented data, `backend/src/test/resources/import/synthetic-grades-*.csv`). Real-file validation is pending. Until then the seeded mapping carries `real_file_validated = false`. When a real file arrives, an Administrator edits the stored mapping (aliases, header format, value maps, ignored columns, delimiter, encoding) and records the validation; code changes are needed only if the real layout is not one of the layouts below. No school policy, grading scale or translation of legacy values is presumed.

## Assumed matrix layout

One file = one campus = one batch (each campus has its own legacy database). Encoding, delimiter, quoting, header row, BOM and row numbering are exactly the ICI2-37 `CsvParser` rules (UTF-8 default or Windows-1252, `,` `;` or tab, a UTF-8 BOM is tolerated, UTF-16 is refused, 1-based record positions, the header row is row 1 by default).

**Synthetic example (default layout `subject_period_flat`):**

```
Student No,LRN,Last Name,First Name,Grade Level,SYNMATH - Q1,SYNMATH - Q2,SYNSCI - Q1,SYNSCI - Q2
SYN-0001,,Dela Cruz,Ana,Grade 7,88.50,90,INC,
```

### Columns: every column must be classified or ignored

A column is exactly one of: an **identity** column, a **context** column, a **grade** column (`Subject - Period` or a reported final), or listed in `settings.ignoredColumns`. Headers are normalized exactly as in ICI2-37 (NFKC, lower-case, non-alphanumeric runs → `_`; exact match only, never by position or similarity), and `ignoredColumns` entries are compared the same way.

| Kind | Fields | Rule |
| --- | --- | --- |
| Identity | `studentNumber`, `lrn` (at least one must have aliases in the mapping and a value on each row), optional `lastName`, `firstName` (cross-check only) | Header resolution as in ICI2-37. A header matching two fields, one field matched by two headers, or a header that is both a field alias and ignored is `AMBIGUOUS_HEADER` (422). The mapping cannot be saved with an ignored header that is also an alias. |
| Context (optional) | `schoolYear`, `term`, `gradeLevel`, `section` | See below. |
| Grade | any other column shaped `<subject><separator><period>` | Split at the **last** occurrence of `grid.separator` (default `" - "`) in the whitespace-collapsed header. A period label listed in `grid.reportedFinalLabels` (empty by default, e.g. `Final Grade`) makes a *reported final* column. Two columns resolving to the same subject and period (or both to the same subject's reported final) are `AMBIGUOUS_HEADER`. |
| Ignored | `settings.ignoredColumns` | Read into `raw_values`, otherwise dropped. |

A header that is none of these is **unclassified**. **One or more unclassified headers fail the whole file**: `UNMAPPED_COLUMN`, 422, a `failed` batch is recorded, and **every** offending header is named — in the error message and as its own batch-level `import_exceptions` record (`field_name` = the header, up to 200 characters) — so an Administrator can classify or ignore each. Unknown subjects (`UNKNOWN_SUBJECT`) and unknown grading periods (`UNKNOWN_PERIOD`) in headers are reported the same way, all in one pass. A blank header is harmless only when the column is empty in every row; otherwise it is unclassified (`(column N, no header)`).

### How school year, term, grade level and section are identified — both, and they must agree

| Item | From the upload | From the file | Rule |
| --- | --- | --- | --- |
| School year | `academicYearId` | `schoolYear` column, via `valueMaps.schoolYear` or equal to an `academic_years.label` | At least one source is required (file level: `MISSING_COLUMN`). Both present and resolving differently → row `CONTEXT_MISMATCH`; unknown value → row `UNKNOWN_SCHOOL_YEAR`. |
| Term | `termId` (its year must match `academicYearId` if both are sent; the year is implied) | `term` column, via `valueMaps.term` or equal to an `academic_terms.code` of the effective year | Same; unknown → row `UNKNOWN_TERM`. A file spanning several terms or years needs these columns; otherwise one upload = one term. |
| Grading period | — | always from the column header, via `valueMaps.period` or equal to a `grading_periods.code` | Header-level check against the terms the upload allows (`UNKNOWN_PERIOD`, file level); a row whose own term lacks the period gets row-level `UNKNOWN_PERIOD`. |
| Grade level | `gradeLevel` (≤30 chars) | `gradeLevel` column | At least one source is required; disagreement → `CONTEXT_MISMATCH`. Stored as text, like `sections.grade_level`. |
| Section | `section` (≤150, optional) | `section` column (optional) | Stored as recorded text (`section_name`); legacy sections are not matched to or created in `sections`. |

The effective upload context is stored in `import_batches.upload_context`. An upload naming a year or term that does not exist, or a term outside the stated year, is a 400.

### Marks and non-numeric values

| Cell | Result |
| --- | --- |
| A number `0`–`100` with at most two decimals (`.` or the mapping's `decimalSeparator`); `88.500` is accepted, nothing is rounded | `value` (stored with two decimals) |
| `INC`, `NG`, `DRP` (case-insensitive) or a legacy token that `valueMaps.mark` translates to one of them | `mark` |
| Blank / whitespace | **No record.** Never converted to `INC`, `NG` or 0. Counted per row (`blankCells`) and per batch. |
| Anything else: `W`, `N/A`, `85%`, `1,5` when `.` is the separator, a negative or >100 value, more than two decimals | Cell error; the whole row is `invalid` |

### Unsupported until a real file is seen (detected, never guessed)

- **Repeated header blocks** (a data row equal to the header): `REPEATED_HEADER`, 422, recorded.
- **Two-row header bands** (subject row over period row): `grid.layout` accepts only `subject_period_flat`; `two_row_band` is described but not implemented.
- **Multiple sheets:** CSV has none.

### CSV only; `.xlsx` is out of scope

ICI2-38 supports **CSV only**: registrars can export a sheet to CSV, the parser stays deterministic, and no spreadsheet dependency is added. If the real file turns out to be `.xlsx` only, add it later behind the same "grid of strings" interface; the least risky library is **fastexcel-reader** (read-only, streaming), with Apache POI's SAX API plus `ZipSecureFile` limits as the heavier alternative. Pin a current release and check advisories then.

### Limits (configurable)

| Limit | Default | Property | Violation |
| --- | --- | --- | --- |
| File size | 5,000,000 bytes | `ici.import.max-file-bytes` (shared) | `FILE_TOO_LARGE` → 413, nothing persisted |
| Data rows | 20,000 | `ici.import.max-rows` (shared) | `TOO_MANY_ROWS` → 413, nothing persisted |
| Characters per cell | 1,000 | `ici.import.max-cell-chars` (shared) | `CELL_TOO_LONG` → 422 |
| Columns | 300 (the student import's 64 is too small for subjects × periods) | `ici.import.grades.max-columns` | `TOO_MANY_COLUMNS` → 422, failed batch recorded |
| Grade cells with a value or mark | 250,000 | `ici.import.grades.max-cells` | `TOO_MANY_CELLS` → 413, nothing persisted |

These are engineering defaults, not school policy.

## Stored mapping (source type `legacy_grade_matrix_csv`)

`import_column_mappings` holds `mapping` and `settings` JSON, reused from ICI2-37 with one seeded, unvalidated default per source type. `GradeMatrixMappingService` lists/reads (`imports.read`), creates/edits (`imports.mappings.manage`, Administrator) and snapshots; a batch copies the effective mapping into `mapping_snapshot`, so later edits never change staged data.

```json
{
  "fields": {
    "studentNumber": { "required": false, "aliases": ["student_number", "student_no", "..."] },
    "lrn": {}, "lastName": {}, "firstName": {}, "schoolYear": {}, "term": {}, "gradeLevel": {}, "section": {}
  },
  "grid": { "layout": "subject_period_flat", "separator": " - ", "reportedFinalLabels": [] },
  "valueMaps": { "subject": {}, "period": {}, "term": {}, "schoolYear": {}, "mark": {} }
}
```
```json
{ "encoding": "UTF-8", "delimiter": ",", "headerRow": 1, "decimalSeparator": ".", "ignoredColumns": [] }
```

**Validation on save** (every problem is listed, 400): known keys only; `studentNumber` or `lrn` mapped; at most 20 aliases of 100 characters per field, no alias shared by two fields; `grid.layout` must be `subject_period_flat`, `separator` 1–10 non-blank characters, at most 20 `reportedFinalLabels`, none also a period value-map key; **every value-map target must be an existing row** — `subject` → `subjects.code`, `period` → some `grading_periods.code`, `term` → some `academic_terms.code`, `schoolYear` → `academic_years.label`, `mark` → `INC`/`NG`/`DRP` — and the import never creates one; no source value listed twice after normalization; `encoding` UTF-8 or windows-1252, `delimiter` `,` `;` or tab, `headerRow` 1–20, `decimalSeparator` `.` or `,` and different from the delimiter, `ignoredColumns` at most 100 unique names none of which is a field alias. Name uniqueness, version checks, the single default and "cannot deactivate a mapping used by an uncommitted batch" follow ICI2-37.

## Student matching

Rows link only to **existing** students; nothing is created. Candidates are limited to students of the batch's campus; a student at another campus (or with no campus) is reported as not found, with no disclosure.

1. `studentNumber` (exact after trim/NFC) → `students.student_number`.
2. else `lrn` (12 digits) → `students.lrn`.
3. If both are present they must identify the same student; the student's stored LRN, when set, must equal the file's.
4. If `lastName`/`firstName` are mapped and present, the lowest ICI2-13 `IdentityMatcher` similarity (accent-insensitive, threshold 0.8) must reach the threshold.

| Outcome | Row status | Code |
| --- | --- | --- |
| One consistent student | `valid`; `normalized_values.studentId`, `matchedBy` (`studentNumber` or `lrn`) | — |
| Matched student is inactive (archived) | `valid` | warning `STUDENT_INACTIVE` (history of graduates is expected) |
| No student at this campus | `invalid` | `STUDENT_NOT_FOUND` |
| Number and LRN identify different students, or the file's LRN differs from the stored one | `duplicate_flagged` (reused as "identity needs review"); `duplicate_evidence` entries `{kind: student, rule: STUDENT_NUMBER \| LRN \| LRN_MISMATCH, id}` | warning `AMBIGUOUS_STUDENT` |
| Name does not match the student found by identifier | `duplicate_flagged`, evidence rule `NAME_MISMATCH` with `nameScore` | warning `NAME_MISMATCH` |
| Neither identifier on the row | `invalid` | `REQUIRED_MISSING` |

Row-level errors dominate: a row with any error is `invalid` even if its identity also needs review. Review (`POST …/review`): `same_person` (with a `studentId` taken from the row's evidence, reason mandatory, and the student must still be at the batch's campus) links a flagged row and makes it `valid`; `skip` drops a valid or flagged row; `distinct` is refused with 409 because a grade row can never create a student (the V21 check `import_row_grade_never_creates` enforces the same in the database).

**Duplicates within a batch:** the dedupe key is the resolved cell `(student, term, subject, record kind, period)`. If a later row resolves to the same student and any cell key was already seen, that row is `invalid` with `DUPLICATE_IN_BATCH` (naming the earlier row number); both are never imported. Different terms for the same student on different rows are fine. Rows awaiting identity review are not deduplicated until they are reviewed (the unique indexes still protect commit). **A row is the unit:** one bad cell makes the whole row `invalid`; partial rows are never imported.

## Subject and term matching

All matching is exact, through the stored mapping, and uses **existing rows only**:

- **Subject:** the part of the header before the separator is looked up in `valueMaps.subject` (legacy label → `subjects.code`, keys normalized like ICI2-37 value maps); a label equal to a `subjects.code` (case-insensitive) maps to itself. Inactive subjects are accepted (retired subjects appear in history). Unknown → `UNKNOWN_SUBJECT` for the header (file level, all listed).
- **Grading period:** the part after the separator → `valueMaps.period` or an equal `grading_periods.code`; or a reported final if it is in `grid.reportedFinalLabels`. Unknown → `UNKNOWN_PERIOD`.
- **School year / term:** as in the context table; unknown cell values are row errors.
- The import **never** creates subjects, years, terms or periods, silently or otherwise; an Administrator creates them through the existing setup first.

## How historical grades are stored

**Decision: dedicated table `legacy_grade_records`; the `grades` table is not used.**

### Constraints that make reusing `grades` hard

| Constraint | Why it blocks reuse |
| --- | --- |
| `grades` → `class_memberships(class_id, enrollment_id, term_id)` → `class_offerings` + `enrollments` | A legacy grade would need a fabricated class offering (section, teacher) and enrollment per student-term. |
| `class_offerings.teacher_id NOT NULL → users`, `section_id NOT NULL → sections` | Legacy teachers are unknown; sections need `program_id`, `capacity > 0`, a term and a campus. Inventing them pollutes rosters and teacher views. |
| `enrollments.application_id NOT NULL UNIQUE`, `UNIQUE(student_id, term_id)` | Requires an application per legacy term; the `enrollment_guard` trigger demands orientation attendance and credentials for `enrolled`. |
| Section capacity guard, enrollment status counts | Fake enrollments distort capacity, cashier rosters and reports. |
| `grade_incomplete_case` trigger (V14) | Any submitted/finalized `INC` auto-opens an `incomplete_cases` row, which blocks document issuance and feeds guidance. |
| `grades` states and windows (`GradingWindowService`, `GradeResolutionService`) | Legacy grades have no submission, locks or deadlines; they would appear in finalization, correction and gradebooks. |
| Honor roll and guidance read `grades` through `class_memberships` | Legacy rows would enter honor and failing-grade signals. |
| `AcademicHistoryService` and the document renderer read grades via `enrollments` | Fake enrollments would show or block on `MISSING_CREDENTIALS`. |
| `audit_change` on `grades` stores `value`/`mark` in `audit_logs` | Imports must keep grade values out of the audit trail. |

What would be easy: `grades` already has the value/mark vocabulary (`numeric(5,2)` 0–100, `INC/NG/DRP`) and a unique key per cell. `legacy_grade_records` copies that vocabulary exactly, without the class/enrollment structure. **Trade-off:** ICI2-35 and ICI2-40 need a second read path (below) instead of getting legacy grades for free.

### `legacy_grade_records` (V21)

| Column | Notes |
| --- | --- |
| `student_id`, `campus_id`, `term_id`, `grading_period_id`, `subject_id` | existing rows only; composite FK `(grading_period_id, term_id)`; the academic year is derived from the term |
| `record_kind` | `period` (needs a period) or `reported_final` (no period): a final rating the **file contains**, stored as reported, never computed |
| `program_id`, `grade_level`, `section_name` | strand at the time (optional), recorded text |
| `value numeric(5,2)` 0–100, `mark` `INC/NG/DRP` | exactly one |
| `import_row_id`, `import_source_type`, `source_column` | provenance: matrix row, constant `legacy_grade_matrix_csv`, exact source header |
| `voided_at`, `voided_by`, `void_reason` | all-or-none; the only permitted change |
| metadata columns | `maintain_record_metadata()` |

**Provenance:** `import_row_id → import_rows.batch_id → import_batches.source_name/checksum/mapping_snapshot/upload_context`, plus `row_number` and `raw_values`. A composite FK `(import_row_id, import_source_type, student_id, campus_id)` → `import_rows` makes the database refuse a record whose row belongs to another student, campus or import source, or has not been committed.

**Integrity:** one live record per `(student, term, subject, period)` and per `(student, term, subject)` for reported finals (voided records excluded). A different value never overwrites; it conflicts. Committed records cannot be edited, deleted or truncated; the only change is a one-time void with actor and reason, after which the cell may be recorded again.

**Audit:** `legacy_grade_record_audit()` writes `audit_logs` **without `value` and `mark`**; `import_rows` audits already omit raw and normalized values. Row messages never echo grades, LRNs or birth dates.

### Void, discard and the checksum

- **Void** applies only to a **committed** (`imported`) batch. `import_batches` has `voided_at`, `voided_by`, `void_reason` (all-or-none, grade batches in status `imported` only). The API voids every live record of the batch and then sets the batch's void columns in one transaction; a database trigger refuses to set `voided_at` while any record of the batch is still live, and a voided batch cannot change. The unique checksum index is `(source_type, checksum) WHERE status <> 'failed' AND voided_at IS NULL`, so **once every record is voided the same file can be uploaded again**. The batch, its rows and its voided records stay as history.
- **Discard** applies to a batch that was staged (`validated`) but never committed. **Chosen handling: the batch moves to the existing status `failed`, and a batch-level `import_exceptions` record with code `DISCARDED` holds the reason** (actor and time come from the record's standard metadata). Why: `failed` already sits outside the checksum index, outside the count-balance check and outside every "awaiting commit" rule, so there is **no new status, no change to the V1 status check and no migration**; the rows are kept as history. The cost is that `failed` now has two causes; the API disambiguates with `discarded: true` (and `discardedAt`, `discardedBy`, `discardReason`) on the batch view, and a batch that failed on its own (file error) cannot be discarded.
- A partially voided batch cannot exist through the API (void is per batch), so the checksum is only freed by a full void or a discard.

### Changes to ICI2-37 tables (V21)

- `import_batches`: `source_type` may be `legacy_grade_matrix_csv`; `upload_context jsonb`; the void columns and checksum index above; `UNIQUE(id, source_type)`.
- `import_column_mappings`: the new source type; one seeded, assumed, unvalidated default.
- `import_rows`: `source_type` (default `legacy_student_csv`, FK to the batch's type); a grade row can never carry the `distinct` (create) decision; `import_rows_student_created_once` applies only to student-import rows (grade rows set `student_id` to the student the grades belong to, and many rows may share one student).
- `import_exceptions.field_name` widened from 40 to 200 characters.
- Existing ICI2-37 services already filter by `source_type`. The one exception, the cross-campus candidate query `ImportValidationService.stagedSql`, now filters `b.source_type = 'legacy_student_csv'` too; before, a staged grade row carrying an LRN could be offered as a duplicate of a student-import row (covered by a test that fails without the filter).

### How the rest of the system sees legacy grades

- **Current grades are untouched.** Honor roll, guidance, INC cases, grading windows, corrections and teacher views read `grades` only.
- **ICI2-35 renderer contract.** The renderer currently selects terms from `enrollments`. **Follow-up for the ICI2-35 owner (not done here; the renderer and the grades tables are unchanged).** The renderer selects terms only from `enrollments`, so legacy grades need this **second read path**, keyed by student rather than enrollment:

```sql
SELECT r.id, y.label AS academic_year, t.name AS term, t.code AS term_code, r.grade_level, r.section_name,
       s.code, s.name, s.units, gp.code AS period, gp.sequence, r.record_kind, r.value, r.mark,
       'legacy_import' AS source, b.id AS batch_id, b.source_name, b.checksum, ir.row_number
FROM legacy_grade_records r
JOIN academic_terms t ON t.id=r.term_id JOIN academic_years y ON y.id=t.academic_year_id
JOIN subjects s ON s.id=r.subject_id LEFT JOIN grading_periods gp ON gp.id=r.grading_period_id
JOIN import_rows ir ON ir.id=r.import_row_id JOIN import_batches b ON b.id=ir.batch_id
WHERE r.student_id=? AND r.voided_at IS NULL ORDER BY y.starts_on, t.starts_on, s.code, gp.sequence;
```

  Legacy rows have no `state`; the renderer shows them as already-final history **with `source = legacy_import` visible**, and keeps its rule that a non-numeric mark (INC/NG/DRP) is not certifiable. A view was deliberately not added: `FoundationAuthenticationTest` requires every public table or view to carry audit metadata columns.
- **Rule for ICI2-35 issuance (decided; the renderer is not changed in ICI2-38):** official issuance **fails closed only when the same student, term, subject and grading period has both a live grade (`grades`) and a live legacy record**. Any other overlap — the student also has a live enrollment in that term, or live grades for other subjects or periods of the term — is a **warning** on the document verification, not a blocker. Import time only warns (below).
- **ICI2-40 reconciliation** can join on `import_row_id`/`batch_id` and compare `value`/`mark` per cell.

## Permissions

| Action | Permission | Roles seeded |
| --- | --- | --- |
| Read batches, rows, errors, mappings | `imports.read` (existing) | Admin, Registrar |
| Upload/stage, review, commit, void, discard | **`imports.grades.manage`** (new, V21) for the batch's campus | Admin, Registrar |
| Create/edit mappings | `imports.mappings.manage` (existing) | Admin |

`imports.manage` is **not enough** for writes: it authorizes creating student identities, whereas this writes academic records printed on official documents; ICI2-37 already split off risk-different actions (`imports.mappings.manage`, `documents.manage`). A holder of `imports.manage` alone gets 403 from the grade staging service (covered by a test). **Reads** reuse `imports.read`: the audience is identical and the new routes are a separate controller filtered by `source_type`. Commit will not need `enrollment.manage` (no student is created).

## HTTP API (implemented)

Base `/api/legacy-grade-imports`, existing Bearer authentication, JSON (upload is `multipart/form-data`). Actor and campus always come from the authenticated account and its current grant (`AccessPolicy`: the permission and the campus come from the same grant); the request never names an actor. Errors are RFC 9457 problem details (`status`, `detail`, plus `code`, `batchId` or `problems` where noted), handled by the same `LegacyImportErrors` advice as ICI2-37.

| Method and path | Permission | Purpose |
| --- | --- | --- |
| `GET /mappings`, `GET /mappings/{id}` | `imports.read` | Mappings of the grade-matrix source type |
| `POST /mappings`, `PUT /mappings/{id}` | `imports.mappings.manage` (Admin) | Create (201) / edit, activate, make default (`version` required) |
| `POST /batches` | `imports.grades.manage` for `campusId` | Upload: **always a dry run** (201). Fields: `campusId`, optional `mappingId`, `academicYearId`, `termId`, `gradeLevel`, `section`, and `file` (`.csv`) |
| `GET /batches?campusId=&status=&page=&size=` | `imports.read` | History, newest first (size ≤ 100) |
| `GET /batches/{id}` | `imports.read` | Counts, mapping, upload context, batch exceptions, error summary, per-column record counts, live/voided record counts, void/discard info |
| `GET /batches/{id}/rows?status=&page=&size=` | `imports.read` | Staged rows with parsed `cells` (size ≤ 100) |
| `GET /batches/{id}/errors?severity=&rowNumber=&page=&size=` | `imports.read` | Row and batch problems and warnings |
| `POST /batches/{id}/rows/{rowId}/review` | `imports.grades.manage` | `same_person` or `skip` |
| `POST /batches/{id}/commit` | `imports.grades.manage` | Write the legacy records |
| `POST /batches/{id}/void` | `imports.grades.manage` | Void a committed batch |
| `POST /batches/{id}/discard` | `imports.grades.manage` | Discard a batch that was never committed |

A batch is visible only to holders of `imports.read` (or `imports.grades.manage`) for its campus, otherwise 404; writes need `imports.grades.manage` for that campus, otherwise 403. Student batches are never visible here, and grade batches never appear in `/api/legacy-student-imports`. `imports.manage` (the student import) does not authorize any grade write. The revalidate action from the phase-1 design was **not built**: after importing the missing students, discard the batch and upload the file again (discard frees the checksum).

### Upload (dry run)

```
curl -H "Authorization: Bearer $TOKEN" -F campusId=1 -F termId=14 -F gradeLevel="Grade 7" \
     -F file=@synthetic-grades-basic.csv http://127.0.0.1:8081/api/legacy-grade-imports/batches
```
```json
201 { "id": 77, "sourceName": "synthetic-grades-basic.csv", "status": "validated", "campusId": 1, "checksum": "4be1…", "fileSizeBytes": 412,
      "sourceCount": 3, "validCount": 3, "invalidCount": 0, "flaggedCount": 0, "skippedCount": 0, "importedCount": 0, "version": 0,
      "startedAt": "2026-11-19T07:41:09Z", "createdBy": 7, "importedBy": null, "importedAt": null,
      "mapping": { "id": 3, "name": "Assumed grade matrix default (unvalidated)", "version": 0, "realFileValidated": false },
      "uploadContext": { "academicYearId": 9, "termId": 14, "gradeLevel": "Grade 7" },
      "voidedAt": null, "voidedBy": null, "voidReason": null, "discarded": false,
      "batchExceptions": [], "errorSummary": [ { "code": "NO_GRADES_ON_ROW", "severity": "warning", "count": 1 } ],
      "recordCount": 7, "blankCellCount": 5, "liveRecordCount": 0, "voidedRecordCount": 0,
      "columns": [ { "column": "SYNMATH… - Q1", "records": 2 }, { "column": "SYNMATH… - Q2", "records": 2 } ] }
```

Nothing is written to `legacy_grade_records` or `grades`. Refusals: `400` unknown year/term, term outside the year, over-long grade level or section; `403` no `imports.grades.manage` for the campus; `409 DUPLICATE_FILE` (`{ "code": "DUPLICATE_FILE", "batchId": 77, "batchStatus": "validated" }`) for identical bytes of a batch that is staged or imported and not voided; `413` `FILE_TOO_LARGE`, `TOO_MANY_ROWS`, `TOO_MANY_CELLS` (nothing persisted); `415` not a `.csv`; `422` file errors (a `failed` batch is recorded and its id returned) such as:

```json
422 { "status": 422, "code": "UNMAPPED_COLUMN", "batchId": 78,
      "detail": "Columns the mapping neither classifies nor lists in ignoredColumns (classify or ignore each): ['Remarks', 'Age'].",
      "problems": [ { "code": "UNMAPPED_COLUMN", "header": "Remarks", "detail": "This column is not an identity or context column, …" },
                    { "code": "UNMAPPED_COLUMN", "header": "Age", "detail": "…" } ] }
```

An unknown or inactive `mappingId` is `422 UNKNOWN_MAPPING` (no batch).

### Review

`POST /batches/{id}/rows/{rowId}/review` — `{ "version": <row version>, "decision": "same_person" | "skip", "reason": "…", "studentId": <required for same_person> }`. A reason is mandatory; only while the batch is `validated`.

```json
POST …/rows/311/review { "version": 0, "decision": "same_person", "reason": "Same learner, confirmed against the paper record", "studentId": 118 }
200 { "row": { "id": 311, "rowNumber": 3, "status": "valid", "version": 1, "review": { "decision": "same_person", "matchedStudentId": 118, "reason": "…", "reviewedBy": 7, "reviewedAt": "…" }, … },
      "batch": { "id": 77, "validCount": 3, "flaggedCount": 0, "version": 1, … } }
```

`409`: stale row version; `distinct` (`"A grade row can never create a student. Link it to an existing student or skip it."`); a student that is not one of the row's candidates, or no longer at the campus; `same_person` on a row that is not flagged; skipping an already skipped or invalid row; any review of a batch that is not `validated`. `400`: missing reason, unknown decision, `same_person` without `studentId`.

### Commit

`POST /batches/{id}/commit` — `{ "version": <batch version>, "expectedValidCount": 3 }`. One transaction under `FOR UPDATE NOWAIT` on the batch:

1. `409` unless the batch is `validated`, `version` matches, **no row is still `duplicate_flagged`**, `expectedValidCount` equals the number of `valid` rows, and there is at least one. A voided or discarded batch is `409`.
2. **Recheck** every row to be imported against what changed since staging or review: the student is still at the campus (`STUDENT_UNAVAILABLE`), the term, subjects and periods still exist (`SETUP_UNAVAILABLE`), no *different* live record now exists for a cell (`CONFLICTS_WITH_RECORDED`), and, after reviews, no two rows now collide on one cell (`DUPLICATE_IN_BATCH`). If any row fails, those rows are demoted to `invalid` (with new exceptions and updated counts), **nothing is imported**, and the answer is `409` so the registrar reviews again. A cell that now matches an existing record exactly is skipped (`recordsAlreadyRecorded`), not an error.
3. Insert `legacy_grade_records` for every cell of every valid row, with the row (and through it batch, file name and checksum), the source column, grade level, section and the committing user; mark each row `imported` with its `student_id`; set the batch `imported` with `imported_by` and `imported_at`.

Any failure in the transaction rolls back everything (`409 … Nothing was imported`), and the same commit can be retried once the cause is fixed.

```json
200 { "batchId": 77, "status": "imported", "rowsImported": 3, "recordsCreated": 7, "recordsAlreadyRecorded": 0,
      "skipped": 0, "invalid": 0, "importedCount": 3, "version": 4, "alreadyImported": false }
```

**Idempotency:** committing an already imported (and not voided) batch returns `200` with `"alreadyImported": true` and the original counts, whatever `version` and `expectedValidCount` are sent; it never writes again. A commit that arrives while another holds the batch gets `409 Another commit of this batch is in progress`.

### Void

`POST /batches/{id}/void` — `{ "version", "reason" }` (reason 1–2000 characters). Only a committed batch (`409` otherwise; use discard before commit). In one transaction it voids every live record (`voided_at`, `voided_by`, `void_reason`), then the batch's own void columns; the batch stays `imported` and the same file can be uploaded again.

```json
200 { "batchId": 77, "status": "imported", "voided": true, "voidedRecords": 7, "voidedAt": "…", "version": 5, "alreadyVoided": false }
```

A repeat returns `200` with `"alreadyVoided": true` and `"voidedRecords": 0`. `409` stale version.

### Discard

`POST /batches/{id}/discard` — `{ "version", "reason" }` (reason 1–1000 characters). Only a `validated` batch that was never committed; the batch becomes `failed` with a `DISCARDED` exception (see "Void, discard and the checksum").

```json
200 { "batchId": 77, "status": "failed", "discarded": true, "version": 1, "alreadyDiscarded": false }
```

A repeat returns `200` with `"alreadyDiscarded": true`. `409`: a committed batch (void it instead), a batch that failed on its own, a stale version. After a discard the batch cannot be reviewed or committed (`409`).

### Mappings

`POST /mappings` `{ "name", "mapping", "settings", "isDefault", "active", "realFileValidated" }` → `201`; `PUT /mappings/{id}` takes the same body plus `"version"` (required, `400` if absent). Invalid content → `400` listing every problem; unknown value-map targets are named and nothing is created; name taken or stale version → `409`; unsetting or deactivating the current default, or deactivating a mapping used by an uncommitted batch → `409`.

### Status codes

| Code | When |
| --- | --- |
| 200 | Reads, review, commit (including an idempotent repeat), void, discard, mapping edit |
| 201 | Batch staged, mapping created |
| 400 | Bad parameters or body (unknown status/severity/decision, page or size out of range, blank reason, missing version, unknown year/term, invalid mapping) |
| 401 | No or invalid token |
| 403 | Missing permission for the endpoint or campus; Admin-only mapping writes |
| 404 | Batch, row or mapping not found, or not visible to the caller |
| 409 | `DUPLICATE_FILE`; stale version; mapping name taken; batch not in the required status; flagged rows remain; `expectedValidCount` mismatch; review refused (`distinct`, not a candidate, not flagged); records changed since staging (rows demoted); concurrent commit; database conflict while writing; void/discard of the wrong kind of batch |
| 413 | `FILE_TOO_LARGE`, `TOO_MANY_ROWS`, `TOO_MANY_CELLS`; nothing persisted |
| 415 | Upload that is not a `.csv` |
| 422 | `EMPTY_FILE`, `INVALID_ENCODING`, `MALFORMED_CSV`, `TOO_MANY_COLUMNS`, `CELL_TOO_LONG`, `MISSING_COLUMN`, `AMBIGUOUS_HEADER`, `UNMAPPED_COLUMN`, `UNKNOWN_SUBJECT`, `UNKNOWN_PERIOD`, `REPEATED_HEADER` (a failed batch is recorded and its id returned); unknown or inactive mapping (no batch) |

Messages never echo grades, LRNs or birth dates; only subject/period labels and codes. Grade values are in `import_rows` (raw and normalized) and `legacy_grade_records`, shown only to holders of `imports.read`, and kept out of `audit_logs`.

## Validation rules (implemented in staging)

| Rule | Code | Level |
| --- | --- | --- |
| Number outside 0–100 or negative | `VALUE_OUT_OF_RANGE` | cell → row `invalid` |
| More than two decimals (never rounded; matches `GradeService`) | `TOO_MANY_DECIMALS` | cell |
| Text with digits that is not a number or mark (`85%`, `1,5`) | `INVALID_VALUE` | cell |
| Other text not INC/NG/DRP and not translated by `valueMaps.mark` | `UNMAPPED_MARK` | cell |
| Control characters | `INVALID_CHARACTER` | cell/field |
| Blank | no record, never INC | counted |
| Header-derived problems | `UNMAPPED_COLUMN`, `UNKNOWN_SUBJECT`, `UNKNOWN_PERIOD`, `AMBIGUOUS_HEADER`, `MISSING_COLUMN`, `REPEATED_HEADER` | file |
| Unknown school year / term / period for the row; disagreement with the upload | `UNKNOWN_SCHOOL_YEAR`, `UNKNOWN_TERM`, `UNKNOWN_PERIOD`, `CONTEXT_MISMATCH` | row |
| Missing identifier, term, school year or grade level | `REQUIRED_MISSING` | row |
| `COLUMN_COUNT_MISMATCH`, `TOO_LONG`, `INVALID_LRN` | | row |
| Student matching | `STUDENT_NOT_FOUND` (error), `AMBIGUOUS_STUDENT`, `NAME_MISMATCH` (warnings → `duplicate_flagged`), `STUDENT_INACTIVE` (warning) | row |
| Same student, term, subject and period twice in a file | `DUPLICATE_IN_BATCH` | later row `invalid` |
| Identical / different value already recorded | warning `ALREADY_RECORDED` / error `CONFLICTS_WITH_RECORDED` | cell |
| **Live overlap, same student, term, subject and period in `grades` (drafts count as live)** | warning `OVERLAPS_LIVE_GRADE` (one per row; the detail has the count and the live state(s) such as `draft`, `finalized`, never the value) | row |
| Student also has a live enrollment in that term | warning `OVERLAPS_LIVE_ENROLLMENT` | row |
| Row with no grade values | warning `NO_GRADES_ON_ROW` (row stays `valid`, 0 records) | row |
| Totals / averages | **No mismatch check.** Reported finals are stored as reported; the rounding, weighting and transmutation needed to recompute them are school policy and unknown. | — |

Warnings never change a row's status. Problems per row are capped (50 errors, 50 warnings, then `MORE_PROBLEMS`) so a flood of warnings cannot hide an error. `import_rows.status` values are those of ICI2-37; `normalized_values` is stored only for rows that are not `invalid`, and holds `studentId`, `matchedBy`, the row's `context` (year, term, grade level, section), the parsed `cells` (column, subject, period, kind, value or mark) and `blankCells`.

## Implementation map (ph.edu.ici.imports)

`GradeMatrixImportController` (routes), `GradeMatrixValidationService` (`stageGradeMatrix`), `GradeMatrixBatchService` (reads, review, void, discard), `GradeMatrixCommitService` (commit), `GradeMatrixMappingService`, `GradeMatrixMapping` (parsed mapping), `GradeMatrixHeaders` (classification), `GradeReference` (existing setup rows), `GradeImportLimits`; reused: `CsvParser`, `ImportText`, `ImportLimits`, `ImportBatchService` (counts, row view, actor), `LegacyImportErrors` (now advises both controllers and adds `problems`), `IdentityMatcher`. `ImportFileException` has the grade-matrix codes and a `problems()` list.

Tests (synthetic fixtures `backend/src/test/resources/import/synthetic-grades-*.csv`, embedded PostgreSQL): `GradeMatrixStagingTest` (service level) and `GradeMatrixApiTest` (real local HTTP: permission matrix, upload refusals, review, commit, idempotency, concurrent commits, rollback by a temporary database trigger, commit-time recheck, immutability, void and discard with re-upload, audit privacy, the `stagedSql` filter).

## Phase plan

Phase 1: design and V21. Phase 2: parser, mapping service, staging. **Phase 3 (done):** controller, review, commit, void, discard, API tests, the `stagedSql` source-type filter. Follow-ups outside this story: the ICI2-35 second read path above and the ICI2-35 issuance rule; ICI2-40 reconciliation reads `legacy_grade_records` by `import_row_id`.

## Open questions for the registrar — these block the parser

Merged with ICI2-37's list (`legacy-student-import-api.md`), de-duplicated. Each answer fills a stored mapping; none needs code unless the real layout differs from the assumed one.

1. A real (even anonymized, few-row) export of **each** kind — students and grades — and which tool produced it: the **header names**; for grades, the **layout** (one combined header like `MATH 7 - Q1`, or subject row over period row), and whether there are **repeated blocks per class/section/year** or several sheets.
2. **Encoding, delimiter and file type** (UTF-8 vs Windows-1252; comma, semicolon or tab; CSV or `.xlsx` only).
3. **Which identifier** each grade row carries (student number, LRN, or only a name), and whether the legacy numbers equal the ones imported in ICI2-37.
4. **Where school year, term, grade level and section appear** (in the file, or only in a file name or title row), and how years/terms/periods are written (`2018-2019`, `SY 18-19`, `1st Sem`, `1st Quarter`).
5. **Mark vocabulary and scale**: are grades 0–100 with decimals, or another scale (1.0–5.0, letters)? What do `INC`, `NG`, `DRP`, `W`, `FA`, `P` and blank mean? (Storage supports only 0–100 and `INC/NG/DRP`.)
6. Does the grade file contain **final or average columns**; should they be stored as reported, and is a recomputation check wanted (needs the approved rounding and weighting)?
7. For students: the **date format** of birth dates, how **voucher** and **specialization** are written (to fill the value maps), whether there is a **campus column**, and whether exports are **one file per campus** (assumed).

### Later (do not block parsing)

- Keep the legacy student number as `students.student_number`, or issue new numbers? Is the legacy LRN always 12 digits; reject or import blank for placeholders?
- Should rows with blank voucher/specialization be rejected (current assumption) or imported with a registrar-assigned value?
- Is title-casing of all-caps names acceptable; are middle names ever initials; how are suffixes stored?
- Which campus owns a learner who appears in two campuses?
- Is a registrar sign-off / `reconciled` step wanted, and how do flagged or invalid rows count against it?
- Retention of raw legacy values in `import_rows` (students and grades) after commit.
- Should "same person" rows ever update the existing student, or is skip-only correct?
- The subject list and labels (to fill value maps); how a legacy `INC` is resolved (void and re-import vs a later supersede flow); teacher and section data (not stored).
