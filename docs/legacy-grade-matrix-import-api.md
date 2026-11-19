# Legacy historical grade matrix import — ICI2-38

**Status:** phases 1–2 are implemented: the design, migration V21, the CSV parser, header classification, the stored mapping service and the dry-run staging service (`GradeMatrixValidationService.stageGradeMatrix`). **There are no controllers, routes, commit step or void step yet** (phase 3); every route in "Planned API contract" is still a design. Backend and docs only; no screens or styles.

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

Row-level errors dominate: a row with any error is `invalid` even if its identity also needs review. Review reuses ICI2-37 (phase 3): `same_person` (with `studentId` from the evidence, reason mandatory) links the row; `skip` drops it; `distinct` is refused because a grade row can never create a student.

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

### Voiding frees the checksum

`import_batches` gains `voided_at`, `voided_by`, `void_reason` (all-or-none, grade batches in status `imported` only). A database trigger refuses to set `voided_at` while any record of the batch is still live, and a voided batch cannot change. The unique checksum index is now `(source_type, checksum) WHERE status <> 'failed' AND voided_at IS NULL`, so **once every record of a batch is voided the same file can be uploaded again**, and the batch, its rows and its voided records stay as history. Voiding some but not all records leaves the checksum blocked. (The void endpoint itself is phase 3.)

### Changes to ICI2-37 tables (V21)

- `import_batches`: `source_type` may be `legacy_grade_matrix_csv`; `upload_context jsonb`; the void columns and checksum index above; `UNIQUE(id, source_type)`.
- `import_column_mappings`: the new source type; one seeded, assumed, unvalidated default.
- `import_rows`: `source_type` (default `legacy_student_csv`, FK to the batch's type); a grade row can never carry the `distinct` (create) decision; `import_rows_student_created_once` applies only to student-import rows (grade rows set `student_id` to the student the grades belong to, and many rows may share one student).
- `import_exceptions.field_name` widened from 40 to 200 characters.
- Existing ICI2-37 services already filter by `source_type`, except the cross-campus candidate query in `ImportValidationService.stagedSql`, which does not; phase 3 should add `b.source_type = 'legacy_student_csv'` there (grade rows carry no LRN or birth date, so nothing matches today).

### How the rest of the system sees legacy grades

- **Current grades are untouched.** Honor roll, guidance, INC cases, grading windows, corrections and teacher views read `grades` only.
- **ICI2-35 renderer contract.** The renderer currently selects terms from `enrollments`. Its integration (ICI2-35 owner, not this story) adds a student-keyed section from this read:

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
| Upload/stage, revalidate, review, commit, void | **`imports.grades.manage`** (new, V21) for the batch's campus | Admin, Registrar |
| Create/edit mappings | `imports.mappings.manage` (existing) | Admin |

`imports.manage` is **not enough** for writes: it authorizes creating student identities, whereas this writes academic records printed on official documents; ICI2-37 already split off risk-different actions (`imports.mappings.manage`, `documents.manage`). A holder of `imports.manage` alone gets 403 from the grade staging service (covered by a test). **Reads** reuse `imports.read`: the audience is identical and the new routes are a separate controller filtered by `source_type`. Commit will not need `enrollment.manage` (no student is created).

## Planned API contract (phase 3 — design only)

Base `/api/legacy-grade-imports`, existing Bearer authentication; actor and campus always from the authenticated grant. Errors are RFC 9457 problem details with `code`/`batchId` as in ICI2-37; `ImportFileException` (`error().status`, `batchId()`, `problems()`) and `ImportConflictException` already carry what the handlers need.

| Method and path | Permission | Purpose |
| --- | --- | --- |
| `GET /mappings`, `GET /mappings/{id}` | `imports.read` | Mappings of this source type |
| `POST /mappings`, `PUT /mappings/{id}` | `imports.mappings.manage` | Same rules as ICI2-37 |
| `POST /batches` | `imports.grades.manage` for `campusId` | Upload; **always a dry run** (201). Form fields: `campusId`, optional `mappingId`, `academicYearId`, `termId`, `gradeLevel`, `section`, `file` (`.csv`) → `stageGradeMatrix` |
| `GET /batches`, `GET /batches/{id}` | `imports.read` | History; counts, mapping, upload context, batch exceptions, per-column record and blank counts |
| `GET /batches/{id}/rows`, `/errors` | `imports.read` | Staged rows (with `cells`), problems |
| `POST /batches/{id}/revalidate` | `imports.grades.manage` | `{ "version" }`; re-run identity/setup lookups for `invalid` and flagged rows of a `validated` batch (e.g. after importing missing students); never re-reads the file |
| `POST /batches/{id}/rows/{rowId}/review` | `imports.grades.manage` | `{ version, decision: same_person \| skip, reason, studentId }` |
| `POST /batches/{id}/commit` | `imports.grades.manage` | `{ "version", "expectedValidCount", "expectedRecordCount" }` |
| `POST /batches/{id}/void` | `imports.grades.manage` | `{ "version", "reason" }`; voids every live record of the batch and then sets the batch's void columns in one transaction, which frees the checksum |

**Dry run vs commit.** Staging writes only `import_batches`, `import_rows` (raw cells and `normalized_values.cells`) and `import_exceptions`; nothing is written to `legacy_grade_records` or `grades`. Commit is the only write to `legacy_grade_records`.

**Idempotency and conflicts.**
- Identical bytes already staged or imported and not voided → `409 DUPLICATE_FILE`; a `failed` batch or a fully voided one does not block a re-upload.
- Commit runs in one transaction under `FOR UPDATE NOWAIT` on the batch and refuses (409) unless the batch is `validated`, `version` matches, no row is still `duplicate_flagged`, and the expected counts equal the current counts. Re-check before inserting: the student still exists at the campus, the subject/term/period still exist, and no *different* live record now exists for a cell (rows demoted to `invalid`, new exceptions, 409, nothing imported).
- A cell already recorded with the **same** value or mark is warning `ALREADY_RECORDED` and is skipped; a **different** one is error `CONFLICTS_WITH_RECORDED` (void the older record first). Nothing is overwritten.
- Committing an imported batch again returns 200 with `"alreadyImported": true`. A concurrent commit gets 409.

**Status codes (as ICI2-37):** 200 reads/review/commit/void/revalidate (including idempotent repeats); 201 batch staged, mapping created; 400 bad parameters (unknown year/term, term outside the year, over-long grade level or section); 401; 403 missing permission or campus; 404; 409 stale version, duplicate file, flagged rows remain, count mismatch, concurrent commit, conflicts with recorded grades; 413 `FILE_TOO_LARGE`, `TOO_MANY_ROWS`, `TOO_MANY_CELLS` (nothing persisted); 415 not `.csv`; 422 `EMPTY_FILE`, `INVALID_ENCODING`, `MALFORMED_CSV`, `TOO_MANY_COLUMNS`, `CELL_TOO_LONG`, `MISSING_COLUMN`, `AMBIGUOUS_HEADER`, `UNMAPPED_COLUMN`, `UNKNOWN_SUBJECT`, `UNKNOWN_PERIOD`, `REPEATED_HEADER` (a failed batch is recorded and its id returned), or an unknown/inactive mapping (no batch).

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
| **Live overlap, same student, term, subject and period in `grades`** | warning `OVERLAPS_LIVE_GRADE` (one per row, with a count) | row |
| Student also has a live enrollment in that term | warning `OVERLAPS_LIVE_ENROLLMENT` | row |
| Row with no grade values | warning `NO_GRADES_ON_ROW` (row stays `valid`, 0 records) | row |
| Totals / averages | **No mismatch check.** Reported finals are stored as reported; the rounding, weighting and transmutation needed to recompute them are school policy and unknown. | — |

Warnings never change a row's status. Problems per row are capped (50 errors, 50 warnings, then `MORE_PROBLEMS`) so a flood of warnings cannot hide an error. `import_rows.status` values are those of ICI2-37; `normalized_values` is stored only for rows that are not `invalid`, and holds `studentId`, `matchedBy`, the row's `context` (year, term, grade level, section), the parsed `cells` (column, subject, period, kind, value or mark) and `blankCells`.

## Implementation map (ph.edu.ici.imports)

`GradeMatrixValidationService` (`stageGradeMatrix`), `GradeMatrixMappingService`, `GradeMatrixMapping` (parsed mapping), `GradeMatrixHeaders` (classification), `GradeReference` (existing setup rows), `GradeImportLimits`; reused: `CsvParser`, `ImportText`, `ImportLimits`, `ImportMappingService.MappingInput/StoredMapping`, `IdentityMatcher`. `ImportFileException` gained the grade-matrix codes (`UNMAPPED_COLUMN`, `UNKNOWN_SUBJECT`, `UNKNOWN_PERIOD`, `REPEATED_HEADER`, `TOO_MANY_CELLS`) and a `problems()` list.

## Phase plan

Phase 1: design and V21. **Phase 2 (done):** the parser, mapping service, staging and tests above. Phase 3: controller, revalidate/review/commit/void, API tests, the `stagedSql` source-type filter.

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
