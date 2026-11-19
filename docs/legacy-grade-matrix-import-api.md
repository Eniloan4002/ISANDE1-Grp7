# Legacy historical grade matrix import — ICI2-38 (phase 1: design)

Phase 1 delivers this design and migration V21 only. **No parser, endpoint or service exists yet**; every route, code and limit below is a *planned* contract for phases 2 and 3. Backend and docs only; no screens or styles.

## Purpose and scope

Bring historical (pre-system) grades from a legacy "grade matrix" export — one row per student, one column per subject and grading period — into permanent, provenance-tracked storage, so that Form 137/TOR generation (ICI2-35) and reconciliation (ICI2-40) can read them. It builds on ICI2-37: the batch, row, exception and stored-mapping design are reused, and students must already exist (imported through ICI2-37 or the Student Master).

In scope: parsing and validating the matrix, matching rows to existing students, matching subjects/years/terms/periods to existing setup rows, registrar review of identity problems, an atomic commit, and storage. Out of scope: creating students, subjects, academic years, terms or grading periods; computing averages or final ratings; transmutation; honors, guidance or INC workflows on legacy data; editing committed grades (only voiding); any screen.

## Status of this format — assumed, not validated

**The real legacy grade files have not been received.** The layout, header aliases, mark vocabulary, scale, encoding and limits in this document are an *assumed* specification, validated only against **synthetic** fixtures (invented data, to be added under `backend/src/test/resources/import/` in phase 2). Real-file validation is pending. Until then the seeded mapping carries `real_file_validated = false`. When a real file arrives, an Administrator edits the stored mapping (aliases, header format, value maps, delimiter, encoding) and records the validation; code changes are needed only if the real layout is not one of the layouts below. No school policy, grading scale or translation of legacy values is presumed.

## Assumed matrix layout

One file = one campus = one batch (each campus has its own legacy database). Encoding, delimiter, quoting, header row, BOM and row numbering are exactly the ICI2-37 `CsvParser` rules (UTF-8 default or Windows-1252, `,` `;` or tab, 1-based record positions, the header row is row 1 by default).

**Synthetic example (default layout `subject_period_flat`):**

```
Student No,LRN,Last Name,First Name,Grade Level,MATH 7 - Q1,MATH 7 - Q2,SCI 7 - Q1,SCI 7 - Q2,SCI 7 - Final
SYN-0001,,Dela Cruz,Ana,Grade 7,88.50,90,INC,,89
```

### Columns

Every column of the file must be classified by the mapping. A column is exactly one of: an **identity** column, a **context** column, a **grade** column, or listed in `grid.ignoreColumns`. A column that is none of these fails the whole file (`UNMAPPED_COLUMN`, 422, listing the headers), so a typo can never silently drop grades.

| Kind | Fields | Rule |
| --- | --- | --- |
| Identity | `studentNumber`, `lrn` (at least one must have aliases in the mapping and a value on each row), optional `lastName`, `firstName` | Header resolution is the ICI2-37 `HeaderResolver` (NFKC, lower-case, non-alphanumeric runs → `_`; exact alias match only; `MISSING_COLUMN` / `AMBIGUOUS_HEADER` fail the file). |
| Context (optional) | `schoolYear`, `term`, `gradeLevel`, `section` | See below. |
| Grade | any other column named by the grid rule | `<subject><separator><period>` (e.g. `MATH 7 - Q1`); split at the **last** occurrence of `grid.separator`. Reported final ratings are columns whose period label is in `grid.reportedFinalLabels` (e.g. `Final`, empty by default). |

### How school year, term, grade level and section are identified — both, and they must agree

| Item | From the upload (`POST /batches` form field) | From the file | Rule |
| --- | --- | --- | --- |
| School year | `academicYearId` | `schoolYear` column, resolved through `valueMaps.schoolYear` or equal to an `academic_years.label` | At least one source is required. If both are present and resolve differently → row error `CONTEXT_MISMATCH`. Never silently prefer one. |
| Term | `termId` (must belong to that year) | `term` column, resolved through `valueMaps.term` or equal to an `academic_terms.code` of that year | Same. A file spanning several terms/years needs these columns; otherwise one upload = one term. |
| Grading period | — | always from the column header, resolved through `valueMaps.period` or equal to a `grading_periods.code` of the term | Required for period columns. |
| Grade level | `gradeLevel` (≤30 chars) | `gradeLevel` column | At least one source required (`grade_level` is not null in storage). Disagreement → `CONTEXT_MISMATCH`. Stored as text, like `sections.grade_level`. |
| Section | `section` (optional) | `section` column (optional) | Stored as recorded text (`section_name`); legacy sections are **not** matched to or created in `sections`. |

The effective upload context is stored in `import_batches.upload_context`.

### Marks and non-numeric values

| Cell | Result |
| --- | --- |
| A number `0`–`100` with at most two decimals (`.` or the mapping's `decimalSeparator`) | `value` |
| `INC`, `NG`, `DRP` (case-insensitive, trimmed) or a legacy token that `valueMaps.mark` translates to one of them | `mark` |
| Blank / whitespace | **No record.** A blank is never converted to `INC`, `NG` or 0. Counted per column in the batch summary. |
| Anything else (`W`, `N/A`, letters, `85%`, `1,5`, `1.0`-style scales outside 0–100, thousands separators, negative, > 100, > 2 decimals) | Cell error → the whole row is `invalid` |

### Unsupported until a real file is seen (detected, never guessed)

- **Repeated header blocks** (a data row equal to the header, or a title row per class): `REPEATED_HEADER` / `UNSUPPORTED_LAYOUT`, 422.
- **Two-row header bands** (subject row with merged cells, period row below): described as layout `two_row_band` (`headerRow` + `periodHeaderRow`, blank subject cells fill right) but **not implemented in phase 2** unless the real file needs it.
- **Multiple sheets:** CSV has none. For `.xlsx` only one named sheet (mapping setting `sheet`) would ever be read; other sheets produce a batch-level warning.

### CSV first; `.xlsx`

Support **CSV only** in ICI2-38. Registrars can export a sheet to CSV, the parser stays deterministic, and no new dependency is introduced (the project has none for spreadsheets today). Excel adds date/number serials, formulas, merged cells, hidden sheets and zip-bomb/XXE exposure. If the real file turns out to be `.xlsx` only, add it later behind the same "grid of strings" interface; the least risky library is **fastexcel-reader** (read-only, streaming, small). Apache POI (`poi-ooxml`, event/SAX API with `ZipSecureFile` limits) is the heavier alternative. Pin a current release and check advisories at that time.

### Limits (planned; `ici.import.grades.*`, in addition to the shared `ici.import.*`)

| Limit | Default | Violation |
| --- | --- | --- |
| File size | 5,000,000 bytes (shared) | `FILE_TOO_LARGE` → 413, nothing persisted |
| Data rows | 20,000 (shared) | `TOO_MANY_ROWS` → 413, nothing persisted |
| Grade cells with a value or mark | 250,000 | `TOO_MANY_CELLS` → 413, nothing persisted |
| Columns | 300 (the student import's 64 is too small for subjects × periods) | `TOO_MANY_COLUMNS` → 422, failed batch recorded |
| Characters per cell | 1,000 (shared) | `CELL_TOO_LONG` → 422 |

## Student matching

Rows link only to **existing** students; nothing is created. Candidates are limited to students of the batch's campus. A student at another campus is reported as not found (no disclosure), exactly as ICI2-37 hides other-campus evidence.

1. `studentNumber` (exact after trim/NFC) → `students.student_number`.
2. else `lrn` (12 digits) → `students.lrn`.
3. If both are present they must point to the same student; the student's stored LRN, when set, must equal the file's LRN.
4. If `lastName`/`firstName` are mapped, the match is cross-checked with the ICI2-13 `IdentityMatcher` name rules (accent-insensitive, similarity ≥ 0.8).

| Outcome | Row status | Code |
| --- | --- | --- |
| One consistent student | `valid`, `normalized_values.studentId` set | — |
| No student at this campus | `invalid` | `STUDENT_NOT_FOUND` (import the student first, then revalidate) |
| Number and LRN point to different students, LRN differs from the stored one, or the name does not match | `duplicate_flagged` (reused as "identity needs review"; `duplicate_evidence` lists `{rule, studentId}` candidates, rule one of `STUDENT_NUMBER`, `LRN`, `CONFLICT`, `NAME_MISMATCH`) | `AMBIGUOUS_STUDENT` / `NAME_MISMATCH` |
| Neither identifier on the row | `invalid` | `REQUIRED_MISSING` |

Review reuses ICI2-37: `same_person` (with `studentId` from the evidence, a reason is mandatory) links the row to that student; `skip` drops it. `distinct` is refused (409, and `import_rows` forbids it for this source) because a grade row can never create a student.

**Duplicates within a batch:** the dedupe key is the resolved cell `(student, term, subject, record kind, period)`. If two rows resolve to the same student *and* any cell key collides, the later row is `invalid` with `DUPLICATE_IN_BATCH`; both rows are never imported. Different terms for the same student on different rows are fine. **A row is the unit:** one bad cell makes the whole row `invalid`; partial rows are never imported.

## Subject and term matching

All matching is exact, through the stored mapping, and uses **existing rows only**:

- **Subject:** the part of the header before the separator, normalized like ICI2-37 value maps (NFKC, lower-case, trim, collapse spaces), is looked up in `valueMaps.subject` (legacy label → `subjects.code`); a label equal to a `subjects.code` (case-insensitive) maps to itself. The subject must exist and be active. Anything else is a *column*-level problem found once per file: `UNKNOWN_SUBJECT`.
- **Grading period:** the part after the separator → `valueMaps.period` or an equal `grading_periods.code` within the resolved term. Unknown → `UNKNOWN_PERIOD`.
- **School year / term:** as in the context table → `academic_years.label` / `academic_terms.code`. Unknown → `UNKNOWN_TERM`. The year/term/period rows must be created first through the existing setup (Administrator); the import **never** creates subjects, years, terms or periods, silently or otherwise.
- Because header-derived problems affect every row, a file with any `UNKNOWN_SUBJECT`, `UNKNOWN_PERIOD` or `UNKNOWN_TERM` header fails at the file level (422, failed batch recorded, all offending headers listed). Row-derived problems (a `term` or `schoolYear` *cell* that does not resolve) are row errors.
- Subjects are stored by id; the legacy label is preserved in the row's `raw_values` and in `source_column`.

## How historical grades are stored

**Recommendation: dedicated table `legacy_grade_records`; do not write to `grades`.**

### Constraints that make reusing `grades` hard

| Constraint | Why it blocks reuse |
| --- | --- |
| `grades` → `class_memberships(class_id, enrollment_id, term_id)` → `class_offerings` + `enrollments` | A legacy grade would need a fabricated `class_offering` (section, teacher) and `enrollment` per student-term. |
| `class_offerings.teacher_id NOT NULL → users`, `section_id NOT NULL → sections` | Legacy teachers are unknown; sections need `program_id`, `capacity > 0`, a term and a campus. Inventing them pollutes rosters and teacher views (`GradeService.students` lists memberships). |
| `enrollments.application_id NOT NULL UNIQUE` (+ composite FK to `applications`), `UNIQUE(student_id, term_id)` | Requires an application per legacy term; the `enrollment_guard` trigger demands orientation attendance and all mandatory credentials for `enrolled`. |
| Section capacity guard, enrollment status counts | Fake `enrolled`/`completed` enrollments distort capacity, cashier rosters and reports. |
| `grade_incomplete_case` trigger (V14) | Any submitted/finalized `INC` auto-opens an `incomplete_cases` row, which blocks document issuance and feeds guidance — legacy INC would pollute INC logic. |
| `grades` states and windows (`draft/submitted/finalized`, `grading_windows`, `section_grading_windows`, `GradingWindowService`) | Legacy grades have no teacher submission, locks or deadlines; they would appear in finalization, correction (`GradeResolutionService`) and teacher gradebooks. |
| Honor roll and guidance read `grades` through `class_memberships` / `enrollment_id` | Legacy rows would enter honor and failing-grade signals unless every query is changed. |
| `AcademicHistoryService` and the document renderer read grades via `enrollments` | They would show or block on fake enrollments, e.g. `MISSING_CREDENTIALS`. |
| `audit_change` on `grades` stores `value`/`mark` in `audit_logs` | Requirement: keep grade values out of the audit trail for imports. |

What *would* be easy: `grades` already has the value/mark vocabulary (`numeric(5,2)` 0–100, `INC/NG/DRP`), `UNIQUE(class, enrollment, period)` and a state column. `legacy_grade_records` copies that vocabulary exactly so readers treat values identically, without inheriting the class/enrollment structure.

**Trade-off:** dedicated storage means ICI2-35 and ICI2-40 must add a second read path (below) instead of getting legacy grades for free. That is a bounded, explicit cost versus fabricating enrollments, applications and teachers that would silently distort every existing report.

### `legacy_grade_records` (V21)

| Column | Notes |
| --- | --- |
| `id` | identity |
| `student_id` → `students` | NOT NULL |
| `campus_id` → `campuses` | NOT NULL; the campus whose legacy database supplied it |
| `term_id` → `academic_terms` | NOT NULL; the academic year is derived from the term |
| `grading_period_id` | composite FK `(grading_period_id, term_id)` → `grading_periods`; null only for `reported_final` |
| `subject_id` → `subjects` | NOT NULL |
| `record_kind` | `period` (default) or `reported_final`; a final rating the **file contains**, stored as reported and never computed; `period` requires a period, `reported_final` forbids one |
| `program_id` → `programs` | optional strand at the time (a student's current program may differ) |
| `grade_level`, `section_name` | recorded text; `grade_level` required |
| `value numeric(5,2)` 0–100, `mark` `INC/NG/DRP` | exactly one (`num_nonnulls = 1`) |
| `import_row_id`, `import_source_type`, `source_column` | provenance: matrix row, constant `legacy_grade_matrix_csv`, exact source header |
| `voided_at`, `voided_by`, `void_reason` | all-or-none; the only permitted change |
| `created_at/updated_at/created_by/updated_by` | standard metadata via `maintain_record_metadata()` |

**Provenance:** `legacy_grade_records.import_row_id → import_rows.batch_id → import_batches.source_name/checksum/mapping_snapshot/upload_context`, with `import_rows.row_number` and `raw_values`. A composite FK `(import_row_id, import_source_type, student_id, campus_id)` → `import_rows(id, source_type, student_id, campus_id)` makes the database refuse a record whose row belongs to a different student, campus or import source (including student-import rows) and refuse a record that points at a row that has not been committed.

**Integrity:** one live record per `(student, term, subject, period)` (`legacy_grade_period_once`) and per `(student, term, subject)` for reported finals (`legacy_grade_final_once`), both ignoring voided records. A different value never overwrites; it conflicts. Committed records cannot be edited, deleted or truncated (`guard_legacy_grade_record`); the only change is a one-time void with actor and reason, after which the cell may be recorded again.

**Audit:** `legacy_grade_record_audit()` writes `audit_logs` rows **without `value` and `mark`** (same idea as `import_row_audit`). Raw and normalized cells stay in `import_rows`, whose audit already omits them.

### Changes to ICI2-37 tables (V21)

- `import_batches`: `source_type` may be `legacy_grade_matrix_csv`; new `upload_context jsonb`; `UNIQUE(id, source_type)`.
- `import_column_mappings`: the same new source type; one seeded, assumed, unvalidated default (`legacy_grade_matrix_csv`, `is_default`, `real_file_validated = false`, empty value maps).
- `import_rows`: new `source_type` (default `legacy_student_csv`, FK to the batch's type); check that a grade row can never carry the `distinct` (create) decision; `import_rows_student_created_once` now applies only to student-import rows (grade rows set `student_id` to the student the grades belong to, and many rows may share one student); `UNIQUE(id, source_type, student_id, campus_id)` for provenance.
- `import_exceptions.field_name` widened from 40 to 200 characters (grade headers).
- Existing ICI2-37 services already filter by `source_type`; phase 2 must add `b.source_type` to the cross-campus candidate query in `ImportValidationService` so grade rows are never offered as student duplicates.

### How the rest of the system sees legacy grades

- **Current grades are untouched.** Honor roll, guidance, INC cases, grading windows, corrections and teacher views read `grades` only, so legacy data cannot affect them.
- **ICI2-35 renderer contract.** The renderer currently selects terms from `enrollments`. Phase-3-or-later integration (ICI2-35 owner) adds a student-keyed section from this read, with a stable shape matching today's grade rows plus provenance:

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

  Legacy rows have no `state`; the renderer treats them as already-final history **with `source = legacy_import` shown**, and must apply its existing rule that a non-numeric mark (INC/NG/DRP) is not certifiable. Whether a student with both live and legacy grades for the same term is a blocker is a renderer policy to decide with the registrar; phase 2 only warns (`OVERLAPS_LIVE_ENROLLMENT`) at staging. A view was deliberately not added: `FoundationAuthenticationTest` requires every public table/view to carry audit metadata columns.
- **ICI2-40 reconciliation** can join on `import_row_id`/`batch_id` and compare `value`/`mark` per cell; reported finals are stored as reported, so a recomputation check is possible without having been baked in.

## Permissions

| Action | Permission | Roles seeded |
| --- | --- | --- |
| Read batches, rows, errors, mappings | `imports.read` (existing) | Admin, Registrar |
| Upload, revalidate, review, commit, void | **`imports.grades.manage`** (new, V21) for the batch's campus | Admin, Registrar |
| Create/edit mappings | `imports.mappings.manage` (existing) | Admin |

`imports.manage` is **not enough** for writes: it authorizes creating student identities, whereas this writes academic records printed on official documents. Reusing it would make the two powers inseparable, and ICI2-37 already set the precedent of splitting off risk-different actions (`imports.mappings.manage`, `documents.manage`). A new permission lets the school later narrow one without the other without a migration, and its seeding today equals the existing audience, so no one gains a surprise capability. **Reads** reuse `imports.read`: the audience is identical (Registrar/Admin already read grades through `grades.review`/history and issue documents), and the new routes are a separate controller filtered by `source_type`, so grade batches never appear in the student-import listing. Commit does not need `enrollment.manage` (no student is created).

## Planned API contract (design only)

Base `/api/legacy-grade-imports`, existing Bearer authentication; actor and campus always from the authenticated grant (`AccessPolicy`). Errors are RFC 9457 problem details with `code`/`batchId` as in ICI2-37.

| Method and path | Permission | Purpose |
| --- | --- | --- |
| `GET /mappings`, `GET /mappings/{id}` | `imports.read` | Mappings of this source type |
| `POST /mappings`, `PUT /mappings/{id}` | `imports.mappings.manage` | Same rules as ICI2-37 (version, unique name, default, validation listing every problem) |
| `POST /batches` | `imports.grades.manage` for `campusId` | Upload; **always a dry run** (201). Form fields: `campusId`, optional `mappingId`, `academicYearId`, `termId`, `gradeLevel`, `section`, and `file` (`.csv`) |
| `GET /batches`, `GET /batches/{id}` | `imports.read` | History; counts, mapping, upload context, batch exceptions, error summary, per-column record and blank counts |
| `GET /batches/{id}/rows`, `/errors` | `imports.read` | Staged rows (with `cells`), problems |
| `POST /batches/{id}/revalidate` | `imports.grades.manage` | `{ "version" }`; re-run identity/setup lookups for `invalid` and flagged rows of a `validated` batch (e.g. after importing missing students); never re-reads the file |
| `POST /batches/{id}/rows/{rowId}/review` | `imports.grades.manage` | `{ version, decision: same_person \| skip, reason, studentId }` |
| `POST /batches/{id}/commit` | `imports.grades.manage` | `{ "version", "expectedValidCount", "expectedRecordCount" }` |
| `POST /batches/{id}/void` | `imports.grades.manage` | `{ "version", "reason" }`; voids every live record of the batch in one transaction |

**Dry run vs commit.** `POST /batches` writes only `import_batches`, `import_rows` (raw and normalized cells in `normalized_values.cells`) and `import_exceptions`; nothing is written to `legacy_grade_records`. Commit is the only write to it.

**Idempotency and conflicts.**
- Identical bytes already staged or imported → `409 DUPLICATE_FILE` (shared checksum index, per source type); a `failed` batch does not block a re-upload.
- Commit runs in one transaction under `FOR UPDATE NOWAIT` on the batch and refuses (409) unless the batch is `validated`, `version` matches, no row is still `duplicate_flagged`, and `expectedValidCount`/`expectedRecordCount` equal the current counts. Re-check before inserting: the student is still active at the campus, the subject/term/period still exist, and no *different* live record now exists for a cell (rows demoted to `invalid`, new exceptions, 409, nothing imported).
- A cell already recorded with the **same** value or mark is a warning (`ALREADY_RECORDED`) and is skipped; a **different** value is an error (`CONFLICTS_WITH_RECORDED`; void the older record first). Nothing is overwritten.
- Committing an imported batch again returns 200 with the same counts and `"alreadyImported": true`. A concurrent commit gets 409 `Another commit of this batch is in progress`.
- Commit inserts with JDBC batches and sets each row `imported` with `student_id`; the batch becomes `imported` with `imported_count` = rows imported.

**Status codes (as ICI2-37):** 200 reads/review/commit/void/revalidate (including idempotent repeats); 201 batch staged, mapping created; 400 bad parameters; 401; 403 missing permission or campus; 404 not found or not visible; 409 stale version, duplicate file, flagged rows remain, count mismatch, concurrent commit, conflicts with recorded grades; 413 `FILE_TOO_LARGE`, `TOO_MANY_ROWS`, `TOO_MANY_CELLS` (nothing persisted); 415 not `.csv`; 422 `EMPTY_FILE`, `INVALID_ENCODING`, `MALFORMED_CSV`, `TOO_MANY_COLUMNS`, `CELL_TOO_LONG`, `MISSING_COLUMN`, `AMBIGUOUS_HEADER`, `UNMAPPED_COLUMN`, `UNKNOWN_SUBJECT`, `UNKNOWN_PERIOD`, `UNKNOWN_TERM`, `REPEATED_HEADER`/`UNSUPPORTED_LAYOUT` (failed batch recorded, id returned), or unknown/inactive mapping (no batch). Messages never echo grades, LRNs or birth dates; only subject/period labels and codes.

## Validation rules

| Rule | Code | Level |
| --- | --- | --- |
| Number outside 0–100, negative, or not a number | `INVALID_VALUE` / `VALUE_OUT_OF_RANGE` | cell → row `invalid` |
| More than two decimals (never rounded; matches `GradeService`) | `TOO_MANY_DECIMALS` | cell |
| Unrecognized mark token (not INC/NG/DRP, not translated by `valueMaps.mark`) | `UNMAPPED_MARK` | cell |
| Blank | no record, never INC | counted |
| Same subject + period twice for a student in a batch | `DUPLICATE_IN_BATCH` | later row `invalid` |
| Identical cell already recorded / different value recorded | warning `ALREADY_RECORDED` / error `CONFLICTS_WITH_RECORDED` | cell |
| Row with no grade cells | warning `NO_GRADES_ON_ROW` (row stays `valid`, 0 records) | row |
| Student has a live enrollment in that term | warning `OVERLAPS_LIVE_ENROLLMENT` | row |
| Context disagreement | `CONTEXT_MISMATCH` | row |
| Any cell error | the **whole row** is `invalid`; partial rows are never imported | row |
| Totals / averages | **No mismatch check.** Reported finals are stored as reported; the rounding, weighting and transmutation that would be needed to recompute them are school policy and unknown (see questions). | — |

`import_rows.status` values and review semantics are those of ICI2-37; `normalized_values` is stored only for rows that are not `invalid`.

## Phase plan

Phase 1 (this): design, V21, tests for the migration. Phase 2: header/layout resolution, cell parsing, student/subject/term matching, staging service, synthetic fixtures. Phase 3: controller, review/revalidate/commit/void, API tests. Phase 2 also decides one design detail: whether voiding a whole batch should free its checksum for re-upload (today it stays `imported`, so identical bytes would still return `DUPLICATE_FILE`).

## Open questions for the registrar — these block the parser

1. A real (even anonymized, few-row) grade export, and which tool produced it: the **header layout** (one combined header like `MATH 7 - Q1`, or subject row over period row), and whether there are **repeated blocks per class/section/year** or several sheets.
2. **Encoding, delimiter and file type** (CSV or `.xlsx` only).
3. **Which identifier** each row carries (student number, LRN, or only a name) and whether the legacy student numbers equal the ones imported in ICI2-37.
4. **Where school year, term, grade level and section appear** (in the file, or only in the file name or a title row), and how years/terms are written (`2018-2019`, `SY 18-19`, `1st Sem`).
5. **Mark vocabulary and scale**: are grades 0–100 with decimals, or another scale (1.0–5.0, letters)? What do `INC`, `NG`, `DRP`, `W`, `FA`, `P`, blank mean? (Storage currently supports only 0–100 and `INC/NG/DRP`.)
6. Does the file contain **final or average columns**; should they be stored as reported, and does the registrar want a recomputation check (needs the approved rounding/weighting)?

Not blocking: the subject list and labels (to fill the value maps), whether legacy and live grades for one term may coexist on a document, how a legacy `INC` is resolved (void and re-import vs a later supersede flow), retention of raw grade values in `import_rows`, and the exact teacher/section data (not stored).
