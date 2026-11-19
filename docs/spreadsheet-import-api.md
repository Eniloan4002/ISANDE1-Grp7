# Spreadsheet data cleaning & import utility — ICI2-39 (design draft)

**Status: design only (phase 1, amended by phase 1b).** Nothing in this document is implemented. No production code, migration or commit accompanies it. Phase 1 scoped a generic `.xlsx` reader for the ICI2-37/38 imports; **phase 1b re-targets the ticket to its real goal, two new import types: (A) section allocations, §12, and (B) document tracking logs, §13**, with shared design in §14 and a summary of what stayed and what was reversed in §15. Everything builds on ICI2-37 (`legacy-student-import-api.md`) and ICI2-38 (`legacy-grade-matrix-import-api.md`) and must not change their behavior unless a mapping opts in.

As in ICI2-37/38, **no real legacy file has been received**. Every value, alias, format or limit below is an engineering default or an open question, never school policy. Anything the registrar must decide is a stored-mapping setting that defaults to "off" or "refuse".

## 1. Goal

Jira ICI2-39: *"Build parsers to ingest external Excel files for sectioning allocations and document tracking logs into PostgreSQL."* The deliverable is two **new import types** inside the ICI2-37/38 framework (batch, row, exception, stored mapping, review, commit), plus the shared foundation they need:

- **Type A, section allocation (§12):** place already-enrolled students into existing sections for a term.
- **Type B, document tracking log (§13):** record document (requirement) states against existing applications.
- **Shared (§§4–7, 14):** the grid interface, the cleaning engine and a JDK-only `.xlsx` reader.

Decisions already made by the product owner: **Excel `.xlsx` only** for A and B (no CSV, `.xls` or `.ods`), **one sheet per file**, **no new libraries** (JDK-only reader, built in a later phase), **hidden rows or columns fail the file**, **formula cells are accepted with a warning** (cached value, flagged), **trivial cleaning is counted** in `cleaningSummary` and **non-trivial changes are logged** in `import_rows.cleaning`.

Principles:

1. **Writes go through the existing services.** A uses `EnrollmentService.assign`, B uses `RequirementService.update`. Campus scope, optimistic versions, capacity, the confirmed-enrollment gate, section compatibility, audit attribution and completeness recalculation are therefore the existing ones, not copies.
2. **Imports never create** sections, applications, requirement types, enrollments or students, and never confirm an enrollment.
3. **Exact or configured keys only.** No fuzzy, partial or positional matching of students, sections or documents; no guessing of states.
4. **No school policy is presumed.** No real Excel layout has been seen; everything the registrar must decide is a stored-mapping setting or an open question (§10).
5. The ICI2-37/38 legacy imports keep their behavior; they are untouched unless a mapping opts in (§5).

Out of scope: creating or editing sections, applications, enrollments or requirement types; confirming enrollment; voiding an applied batch (§14); formula evaluation; multi-sheet workbooks; any screen.

## 2. What ICI2-37/38 already give us

| Need | Already present | Where |
| --- | --- | --- |
| One batch per file, status, counts, checksum, per-campus scoping, optimistic versions | `import_batches` (+ V19/V21 columns) | V1, V19, V21 |
| One staged row per record with raw and normalized JSON | `import_rows.raw_values`, `normalized_values` | V19 |
| Problems per row/batch, error vs warning, bounded detail | `import_exceptions` (`severity`, `field_name`, `import_row_id`) | V19, V21 |
| Stored, editable, snapshot-per-batch mapping with strict save validation | `import_column_mappings` + `ImportMappingService`/`GradeMatrixMappingService` | V19, V21 |
| Review (`same_person`/`distinct`/`skip`), atomic commit, idempotency, void/discard | `ImportBatchService`, `ImportCommitService`, `GradeMatrix*Service` | ICI2-37/38 |
| Permissions and campus scope | `imports.read`, `imports.manage`, `imports.grades.manage`, `imports.mappings.manage` via `AccessPolicy` | V19, V21 |
| Privacy of personal data in audit | `import_row_audit()` omits `raw_values`/`normalized_values`; messages never echo values | V19 |
| Limits as application config | `ImportLimits`, `GradeImportLimits` (`ici.import.*`) | code |
| A grid abstraction in practice | `CsvParser.Parsed(headers, rows[Record(number, cells)])`, 1-based record numbers, blank records skipped but counted | `CsvParser` |
| Text hygiene | `ImportText.clean` (NFC, trim, `\s+` collapse), `RowNormalizer` (dates, LRN, names, value maps), grade cell parsing | code |

### What is missing

- **No spreadsheet reader**, and `CsvParser` is constructed directly in `ImportValidationService.stage` and `GradeMatrixValidationService.stageGradeMatrix` (no interface to swap).
- **The grid carries strings only.** A spreadsheet cell has a type (text, number, date, boolean, error, formula) that the CSV grid never had. Today a native date would arrive as a serial number and hit `INVALID_DATE` ("spreadsheet serial numbers are not accepted"); a percent-formatted `0.85` would be read as the grade 0.85.
- **Cleaning is implicit and unlogged.** The rule that changed a value is not recorded; only raw and final values exist. Intermediate steps are lost.
- **Verified gap (jshell on this JDK): non-breaking spaces and zero-width characters survive today's cleaning.** `\s+` does not match U+00A0, `String.trim()` keeps it, NFC keeps it, and zero-width characters (U+200B…) are neither stripped nor rejected by the control-character check. They are common in cells pasted from the web or Word, and break exact matching on student numbers and names. Headers are safe (NFKC maps U+00A0 to a space); values are not.
- **No place to record cleaning, no source format on a batch**, and mappings reject unknown settings keys on save (`SETTING_KEYS`), so new settings need code to be accepted.
- **No placeholder handling** (`N/A`, `-`, `none`, …): a placeholder in an optional field is stored as a value; in a grade cell it is a cell error.
- **Dates:** only `yyyy`/`MM`/`dd` with two-digit month/day and `-`, `/`, `.`. Single-digit months (`3/4/2009`) and month names are `INVALID_DATE`.

## 3. Phase plan (revised in 1b)

Each phase is mergeable on its own, ends with all existing tests green, and adds its own tests. Migration numbers are the next free ones today (V21 is the last); re-check against other open branches before writing each.

| Phase | Delivers | Migration | Tests added |
| --- | --- | --- | --- |
| **2. Shared foundation** | `GridReader` interface (CSV reader implements it, output identical) and a `Grid` record with per-cell metadata; `CellCleaner`, `CleaningProfile`, trivial/non-trivial split (§5); new mapping settings validated on save in both existing mapping services; **behavior-identical extractions**: the campus-scoped student resolver from `GradeMatrixValidationService`, and generic batch reads (list, detail, rows, errors) parameterized by source type, so A and B do not become third and fourth copies of 38's services; `cleaning` and `cleaningSummary` exposed on the existing read routes. No spreadsheet code. | **V22** (§14) | **Golden regression**: every existing synthetic fixture with default settings yields byte-identical `normalized_values` and exceptions; per-rule cleaning tests (NBSP, zero-width, placeholders, dates, name case); mapping-save validation; audit-privacy test; the extractions covered by the existing 84 tests |
| **3. Type A, section allocation** | Source type `section_allocation`: mapping service, staging, batch reads/review/commit/discard, controller (everything except the upload route). **Small, behavior-preserving refactor in the enrollment package**: split `EnrollmentService.assign` into a read-only `assignmentBlockers(enrollmentId, sectionId)` and the existing write, so staging reports the service's own refusals instead of re-implementing them. Staging takes a `Grid`; tests build grids in code (and from CSV fixtures through the CSV reader as a test-only stand-in). | **V23** | Staging matrix for every row outcome in §12.4; capacity simulation incl. swaps; commit all-or-nothing, rollback on a forced service refusal, stale-version demotion, idempotency, concurrency with a manual assignment; permission/campus matrix; privacy; **existing enrollment tests unchanged and green** |
| **4. Type B, document tracking log** | Source type `document_log`: same set of pieces for B, committing through `RequirementService.update`; commit reports applications whose checklist became complete. | **V24** | Row outcomes in §13.4; regression/conflict review; state and document value maps; notes provenance; stale-version demotion; completeness effect; audit attribution (`/api/applications/{id}/requirements/audit` shows the committing user); existing requirement tests green |
| **5. JDK-only `.xlsx` reader** | `XlsxGridReader` and its pre-scan (§6, §7). No endpoint yet. | none | Adversarial fixtures generated in test code (no binary files, no test-scope POI): the full list from §6, plus 1900/1904 dates, serial 60, shared and inline strings, rich text, formula with and without cached value, error cells, sparse cell at column XFD, lying `<dimension>` |
| **6. Upload endpoints** | The upload route for A and B (`.xlsx` by magic bytes, 415 otherwise), `source_format = 'xlsx'`, `sheet` setting active, error mapping in `LegacyImportErrors`, docs. Whether the ICI2-37/38 upload routes also accept `.xlsx` is an open question (Q18); if yes it is a few lines here, not a phase. | none | API tests mirroring ICI2-38's: permission and campus matrix, 413/415/422 paths, duplicate file, end-to-end stage, review, commit and discard for both types from real `.xlsx` bytes, privacy |
| **7. Optional** | `POST …/inspect` (sheet names, first rows) so an Administrator can author a mapping for a real file. | none | only if wanted |

Why this order: the cleaning engine and both new types can be built and tested at service level with in-code grids, so the riskiest, hand-written part (the zip and XML reader) lands once the consumers exist and its fixtures can be exercised end to end, and nothing is reachable over HTTP until the reader is hardened.

## 4. Storage (shared foundation, V22)

Next free Flyway version is **V22** (V21 is the last); types A and B add V23 and V24 (§14.2). Published migrations are not edited.

- `import_batches.source_format varchar(10) NOT NULL DEFAULT 'csv' CHECK (source_format IN ('csv','xlsx'))`, plus `sheet_name varchar(255)` (nullable) and `formula_cell_count integer` (nullable) for review display.
- `import_batches.cleaning_summary jsonb`: rule counts for the whole batch, **including trivial rules** (§14.3), which are not written per cell.
- `import_rows.cleaning jsonb CHECK (cleaning IS NULL OR jsonb_typeof(cleaning)='array')`: one entry per **non-trivial** applied change (shape below). `import_rows.applied jsonb`: what a commit wrote (types A and B, §12.5 and §13.5).
- `CREATE OR REPLACE FUNCTION import_row_audit()` to also omit `cleaning` and `applied` (personal values, exactly like `raw_values`). Existing trigger keeps pointing at the function.
- **No new `source_type` for xlsx versions of ICI2-37/38 (types A and B do get their own, §14.1).** Keeping `legacy_student_csv` / `legacy_grade_matrix_csv` (the names are now historical) avoids touching ~15 queries and constraints that filter on them: the unique checksum index, `import_rows` FK `(batch_id, source_type)`, V21's `legacy_grade_records.import_source_type` check and provenance FK, mapping lookups, and the cross-campus duplicate query `stagedSql` (which filters `source_type='legacy_student_csv'`; a new type would silently drop xlsx-staged rows from cross-campus duplicate detection). One mapping then serves a CSV and an xlsx export of the same legacy table; format-specific settings are ignored for the other format.
- Why a column and not `normalized_values._cleaning`: `normalized_values` is stored only for non-invalid rows, so an invalid row would lose the explanation of what was changed; a dedicated column also keeps the commit services' view of `normalized_values` unchanged.

### Cleaning log entry

```json
{ "field": "lastName", "column": "Last Name", "rule": "unicode_spaces", "before": "DELA CRUZ", "after": "DELA CRUZ" }
```

One entry per **non-trivial** rule per cell that actually changed the value (trivial rules are only counted, §14.3), in application order, so multi-step cleaning keeps every intermediate value (`before` of the first entry is the raw cell, `after` of the last is the cleaned value). `before`/`after` are capped at 200 characters with a `truncated: true` flag; per-row entries are capped at 50 (then a `MORE_CHANGES` entry) so a pathological row cannot bloat the table. `raw_values` still keeps the unmodified cell. Batch detail gets `cleaningSummary: [{rule, count}]` from `import_batches.cleaning_summary` (trivial and non-trivial rules). Cleaning never writes to `import_exceptions` messages (those never echo values); an optional per-row warning `VALUE_CLEANED` (no value) is raised only for rules marked "review" below.

## 5. Cleaning rules and mapping configuration

### Pipeline (applies to every cell, in this order)

1. **Type conversion (xlsx only)** → string, with a cell flag. Raw `<v>` text, never the display text (display depends on locale and number format).
2. **Text hygiene** (all formats).
3. **Placeholder → blank** (per mapping).
4. **Field parsing** by the existing code: LRN, dates, names, value maps, grade numbers and marks, now fed cleaned text and cell flags.

Headers are not rewritten: header matching keeps its existing NFKC/underscore normalization.

### Rules

| Rule id | What it does | Applies to | Default | Recorded as (§14.3) |
| --- | --- | --- | --- | --- |
| `unicode_nfc` | NFC | all text | **on (today)** | trivial: counted |
| `trim`, `collapse_spaces` | trim and collapse ASCII whitespace runs | all text | **on (today)** | trivial: counted |
| `unicode_spaces` | U+00A0, U+2000–200A, U+202F, U+205F, U+3000 → one space | all text | **off** (opt-in; recommended on) | trivial: counted |
| `strip_invisible` | remove U+200B–200D, U+2060, U+FEFF (format characters) | all text | **off** (opt-in; recommended on) | trivial: counted |
| `placeholder_to_blank` | a value equal (after `ImportText.value` normalization) to a configured placeholder becomes blank | per field, or all fields | **off, empty list** | logged, plus warning `VALUE_CLEANED` |
| `name_case` | existing behavior: all-upper or all-lower names title-cased; mixed case untouched | `lastName`, `firstName`, `middleName` | **off** (`normalizeNameCase`, kept as is) | logged |
| `name_particles` | with `name_case` on, listed particles (e.g. `de`, `dela`, `van`) stay lower-case | same | empty list = none | logged |
| `date_formats_extended` | `dateFormats` additionally accept `M`/`d` (single digit) and `MMM`/`MMMM` (English month names), under the existing ambiguity rules | birth date | formats as configured; new tokens only if listed | logged (`date_parsed:<format>`) |
| `xlsx_native_date` | a date-typed numeric cell becomes ISO `yyyy-MM-dd`; bypasses `dateFormats` because there is no day/month ambiguity | date fields | `accept` for xlsx (see Q17) | logged |
| `xlsx_number_to_text` | an integral numeric cell in a text/identifier field becomes plain digits (no `1.2E+11`) | text and ID fields | on for xlsx | logged |
| `pad_numeric_id` | left-pad a **numeric-typed** identifier to a configured width (Excel drops leading zeros) | per field | **off** (needs registrar answer) | logged, plus warning |
| `lrn_strip_separators` | remove spaces/dashes inside an LRN | `lrn` | **off** (ICI2-37 chose "no stripping") | logged |
| `formula_cached` | read the cached value of a formula cell | any | `cached_flag` | warning `FORMULA_CACHED` (cell flagged, formula text never stored) |

Not cleaned, always an error (never guessed): two-digit years, impossible dates, a percent-formatted or date-formatted numeric cell in a grade column (`PERCENT_FORMAT`, `DATE_IN_GRADE_CELL`; otherwise `0.85` formatted `85%` would be stored as the grade 0.85), grades with more than two decimals (never rounded; cached formula averages such as 87.333333 will hit this, see Q8), unmapped marks, error cells (`#N/A`, `#DIV/0!` → `ERROR_CELL`), a formula with no cached value (`FORMULA_NO_CACHED_VALUE`).

Placeholders never become `INC`, `NG`, `DRP` or 0: in a grade cell a placeholder is blank ("no record"), as an empty cell is today. A token cannot be both a placeholder and a key of `valueMaps.mark`; saving such a mapping is a 400.

### Stored configuration (inside `settings`, no change to `mapping`)

All keys optional; **absent means today's behavior**, so every stored mapping and both seeded defaults keep working without an edit.

```json
"settings": {
  "encoding": "UTF-8", "delimiter": ",", "headerRow": 1, "dateFormats": ["yyyy-MM-dd","MM/dd/yyyy"], "normalizeNameCase": false,
  "sheet": "Students",
  "nativeDates": "accept",
  "cleaning": {
    "unicodeSpaces": true,
    "stripInvisible": true,
    "blankValues": ["n/a", "-"],
    "fieldBlankValues": { "lrn": ["000000000000"] },
    "nameParticles": [],
    "padNumeric": { "studentNumber": 8 },
    "lrnStripSeparators": false
  }
}
```

- `sheet`: exact sheet name (not index, which changes when someone reorders tabs). Absent = the workbook must contain exactly one sheet. xlsx only; ignored for CSV (`encoding`/`delimiter` are ignored for xlsx). `headerRow` means the sheet row number.
- `nativeDates`: `accept` (default, Q17) or `reject`. Decided, so **not settings**: formula cells are always read as cached values with warning `FORMULA_CACHED`; any hidden row or column fails the file (`HIDDEN_CONTENT`).
- `cleaning.*`: lists capped (50 entries of 100 characters), keys limited to known fields, **save-time validation in both mapping services** (the same `SETTING_KEYS` allow-list approach, every problem listed, 400). Token/value-map collisions rejected. Grade mappings use the same `cleaning` block; `decimalSeparator` stays where it is.
- Per ICI2-37, "a mapping cannot make the six mandatory student fields optional": a placeholder that turns a mandatory field blank therefore produces `REQUIRED_MISSING` on that row, with the cleaning entry explaining why.
- The mapping snapshot already copies `settings`, so a staged batch records exactly which cleaning ran.

## 6. Safe spreadsheet parsing

A mandatory **pre-scan with the JDK only** (`java.util.zip.ZipInputStream` over the in-memory bytes; nothing is extracted to disk, so zip-slip does not apply). It runs before any library sees the file and enforces, by counting **actually inflated bytes**, never trusting declared sizes:

| Guard | Default (`ici.import.xlsx.*`) | Violation |
| --- | --- | --- |
| Compressed file size | shared `ici.import.max-file-bytes` (5,000,000) | `FILE_TOO_LARGE` 413 |
| Entries in the archive | 200 | `XLSX_LIMIT_EXCEEDED` 413, nothing persisted |
| Inflated bytes, per entry / total | 40 MB / 80 MB | same |
| Compression ratio per entry | 100:1 (above a small absolute floor) | same |
| Data rows / columns / cell characters | shared `max-rows`, `max-columns` (grades: 300), `max-cell-chars` | existing codes |
| **Non-empty cells in the sheet** | 250,000 (`ici.import.xlsx.max-cells`) | `TOO_MANY_CELLS` 413 |
| Duplicate entry names, absolute or `..` names, encrypted entries, nested archives read | rejected | `MALFORMED_XLSX` 422 |

Columns are enforced from the **cell reference** (`XFD1` alone must not create a 16,384-wide row), and `<dimension>` is never trusted (counts are of cells actually read). The cell cap matters because the existing row × column limits alone allow a very large in-memory grid.

**Fail closed (422, failed batch recorded, with a specific code):**

| Condition | Code |
| --- | --- |
| Macro-enabled workbook (`vbaProject.bin`, macro content type, `.xlsm`) | `MACRO_ENABLED` |
| External links, data connections, query tables, embedded objects/OLE, ActiveX (`xl/externalLinks/*`, `connections.xml`, `queryTables/*`, `embeddings/*`, `activeX/*`) | `EXTERNAL_CONTENT` |
| Password-protected or legacy `.xls` (OLE2 header `D0 CF 11 E0`) | `UNSUPPORTED_FORMAT` |
| Merged cells (`<mergeCells>` in the selected sheet) | `MERGED_CELLS` |
| More than one sheet and no `settings.sheet`, or the named sheet absent | `MULTIPLE_SHEETS`, `SHEET_NOT_FOUND` (names listed) |
| Any hidden row or column (decided); a hidden sheet counts as a sheet | `HIDDEN_CONTENT` |
| `<!DOCTYPE>` or entity declarations anywhere in the XML parts; malformed XML or zip; truncated archive | `MALFORMED_XLSX` |

Format is decided from **magic bytes**, never the extension or the client's content type (`PK\x03\x04` → xlsx; `D0CF11E0` → refused; the CSV decoder keeps handling everything else). XML is parsed with DTD and external entities disabled. **Formulas are never evaluated**: only the cached `<v>` is read, the cell is flagged, and the formula text is discarded (so a `=cmd|…` string is never stored; if raw values are ever exported to a spreadsheet in future, values starting with `=`, `+`, `-`, `@` need escaping — no export exists today). Hyperlinks are never followed; images and charts are ignored.

Grid conventions: row numbers are the **sheet's row numbers** (what the registrar sees in Excel), blank rows skipped but counted, as with CSV. Rows are padded to the header width; a non-blank cell beyond the header width is `COLUMN_COUNT_MISMATCH`, as for CSV. Booleans read as `TRUE`/`FALSE`; shared strings concatenate rich-text runs and skip phonetic runs; the 1904 date system and the 1900 leap-year quirk are handled (serials below 61 are `INVALID_DATE`, not guessed).

## 7. `.xlsx` reader: JDK-only (decision)

**Decision (product owner): no new library.** This reverses the phase 1 recommendation of fastexcel-reader. For the record, the phase 1 comparison found that neither fastexcel-reader nor Apache POI SAX covers the fail-closed list on its own (merged cells, package parts such as macros and external links, hidden rows and columns, cell caps all need our own scan), so most of the safety layer was ours either way; the JDK-only route removes the dependency, its transitive libraries and its advisory surface, at the cost of owning the spreadsheet-format details.

Design (phase 5), over the same in-memory `byte[]` (already capped by `ici.import.max-file-bytes`), so there is no check-then-read window:

1. **Pass 1, guard.** `java.util.zip.ZipInputStream`; nothing is extracted to disk. Every entry is inflated through a counting stream with the limits of §6 (actual bytes, not declared sizes). Denylisted parts and duplicate, absolute or `..` entry names are rejected here. Small parts are kept in memory: `[Content_Types].xml`, `xl/workbook.xml` (sheet list, `state`, `date1904`), `xl/_rels/workbook.xml.rels`, `xl/styles.xml` (number-format class per style: date, percent, other number), `xl/sharedStrings.xml` (with a string-count and character cap).
2. **Pass 2, the one selected sheet.** Opened again from the same bytes and streamed through StAX with DTD and external entities disabled: `<cols hidden>`, `<row hidden>`, `<mergeCells>`, `<c r t s>`, `<f>`, `<v>`, `<is>`. `<dimension>` is ignored; columns are limited from the cell reference, cells and rows are counted as read.
3. **Output.** The same `Grid` the CSV reader produces (headers, rows with the sheet's row numbers, cells as strings) plus `CellMeta` for cells that were not plain text (native date, formula, number-format class, error), which the cleaner and the field parsers consult.

Estimated size: about 500 lines plus 25 to 30 fixtures built in test code. No POI or other library, not even test scope.

Risks to accept and test: custom number formats that mean "date" (rule: a numeric cell with an unclassifiable custom format in a date or grade-like field is an error, never a guess), `_xHHHH_` escapes, rich-text and phonetic runs, inline strings, shared formulas, the 1900 leap-year quirk and the 1904 system. Maintenance of this reader is ours; it sits behind `GridReader`, so it can be replaced later without touching A or B.

## 8. API changes

New controllers for the two new types (same conventions as `/api/legacy-grade-imports`: Bearer JWT, RFC 9457 problem details, actor and campus from the authenticated grant, `LegacyImportErrors` advice extended to both):

| Base | Type |
| --- | --- |
| `/api/section-allocation-imports` | A |
| `/api/document-log-imports` | B |

Both expose the same routes as ICI2-38 minus void: `GET/POST/PUT /mappings`, `POST /batches` (upload, `.xlsx` only; added in phase 6), `GET /batches`, `GET /batches/{id}`, `GET /batches/{id}/rows`, `GET /batches/{id}/errors`, `POST /batches/{id}/rows/{rowId}/review`, `POST /batches/{id}/commit`, `POST /batches/{id}/discard`. Upload form fields: `campusId`, optional `mappingId`, `termId` (and optional `academicYearId`), `file`; the sheet name comes from the mapping, never the request. Batch detail adds `sourceFormat`, `sheetName`, `formulaCellCount`, `cleaningSummary`; rows add `cleaning` (non-trivial changes only), `applied` (after commit), and the typed `cells`/outcome fields of §12 and §13. Review decisions: `same_person` and `skip` as in ICI2-38, plus `apply` (§14); `distinct` is a 409.

Status codes follow ICI2-38: 413 `FILE_TOO_LARGE`, `TOO_MANY_ROWS`, `TOO_MANY_CELLS`, `XLSX_LIMIT_EXCEEDED` (nothing persisted); 415 not an `.xlsx`; 422 file errors with a recorded failed batch (`MACRO_ENABLED`, `EXTERNAL_CONTENT`, `MERGED_CELLS`, `MULTIPLE_SHEETS`, `SHEET_NOT_FOUND`, `HIDDEN_CONTENT`, `UNSUPPORTED_FORMAT`, `MALFORMED_XLSX`, plus `MISSING_COLUMN`, `AMBIGUOUS_HEADER`, `UNMAPPED_COLUMN` as in 38); 409 duplicate file, stale version, rows needing review, rows changed since staging, concurrent commit. Row-level codes are in §12.4 and §13.4; xlsx cell codes (`ERROR_CELL`, `FORMULA_NO_CACHED_VALUE`, warning `FORMULA_CACHED`) as in §5 and §6.

`ici.import.xlsx.*` limits and all existing `ici.import.*` limits apply; `spring.servlet.multipart` limits are unchanged.

**ICI2-37/38 routes:** unchanged. Whether their upload routes also accept `.xlsx` is open (Q18). If yes, the behavior in the phase 1 draft applies (`source_format`, magic-byte sniffing, existing `notes.txt` 415 tests keep passing); the new source types are not needed for that.

## 9. Risks and compatibility

**Would break existing behavior if done carelessly (all avoided by the design above):**

- Turning `unicode_spaces`/`strip_invisible` **on by default** would change CSV outputs for any value containing those characters. They are opt-in; existing tests have no such fixtures (searched), but real data might, so the golden regression test is mandatory.
- `ImportMappingService.validate` and the grade equivalent reject unknown settings keys; the new keys must be added in **both** or Administrators cannot save them. Existing mappings without them must parse unchanged.
- The `.csv`-only checks in both controllers and the 415 tests: the existing 415 tests upload `notes.txt` (`LegacyImportApiTest:137`, `GradeMatrixApiTest:186`), which stays 415, so they should pass unmodified.
- Adding `source_type` values would silently exclude xlsx rows from cross-campus duplicate detection (`stagedSql`) and touch V21's provenance FK; hence none is added.
- `CsvParser` signatures are used directly by tests (`new CsvParser(limits).parse(bytes, settings)`, `CsvParser.Record`, `CsvParser.Parsed`). Keep `CsvParser` and its records source-compatible; add the interface beside it.
- `normalized_values` shape is read by the commit services; cleaning data goes in a separate column, so commit code is untouched.
- Embedded-Postgres/test classpath: adding `commons-compress` 1.28.0 may change what the zonky test dependency resolves.

**New risks:**

- **Zip bombs and memory:** all protection is ours (§6); the cell cap is the main memory bound. Needs adversarial tests, not just happy paths.
- **Silent misreads that only spreadsheets cause:** percent or date formats on grade cells, leading zeros dropped from numeric IDs, formula-cached averages with many decimals, hidden rows that registrars consider "deleted", 1900/1904 date systems, a typed-locale date already converted to a serial (day/month swap cannot be detected). Each is an error, warning or a mapping option, never a guess.
- **Cleaning log volume and privacy:** per-row cap, personal values only in `import_rows.cleaning` (excluded from audit), never in messages.
- **Pre-1.0, single-maintainer dependency:** pinned and wrapped.
- **Two-phase read** (our pre-scan, then the library) must operate on the same in-memory bytes to avoid a swap between check and read; it does, files are already ≤5 MB byte arrays.
- Stored mapping is shared across CSV and xlsx; an Administrator editing it for one format affects the other. The snapshot keeps staged batches safe.

**Added in 1b (types A and B):**

- **Rules duplicated between staging and the services.** Capacity, grade matching and the confirmed-enrollment gate live in `EnrollmentService.assign`. Re-implementing them in staging would drift. Mitigation: extract `assignmentBlockers` and call it from both; this touches the enrollment package (other tickets' code), so it must be behavior-preserving, reviewed by its owners and covered by the existing enrollment tests.
- **One stale row blocks the whole batch.** All-or-nothing commit plus optimistic versions means a registrar editing one enrollment or checklist item during a commit window makes the commit answer 409. Mitigation: recheck demotes only the affected rows and says which; the rest of the batch survives.
- **Service refusals are rollback-only.** A service exception inside the commit transaction cannot be caught and continued; the design relies on rollback, and on the recheck to make that rare.
- **Lock ordering.** A commit touches many enrollments, sections or application rows in one transaction. Rows are ordered (enrollment id, then `(section id, row number)`; application id then document code) so two concurrent import commits cannot deadlock each other; a clash with a manual edit surfaces as a retryable 409.
- **Importing documents can make applications "complete"**, an input to enrollment confirmation. It never confirms anything; the commit result reports `applicationsNowComplete` so it is visible.
- **Attribution is the importer and import time**, not the legacy verifier or date (§13.3). A reviewer reading the requirement audit sees the registrar who ran the import, with the legacy details in the note.
- **No void and no automatic revert.** A wrong sectioning import is corrected through the existing section and checklist workflows; `applied` shows what changed (Q13).
- **Layouts unseen.** Section lists as blocks or per-section sheets, wide document matrices and multi-event logs are refused, not guessed; if the real files are like that, a design amendment is needed before phase 3 or 4 is built.
- **Own xlsx parser.** The JDK-only reader owns spreadsheet-format edge cases (§7); mitigated by adversarial fixtures and by keeping it behind `GridReader`.
- **Migration collisions.** Three migrations (V22 to V24) increase the chance another branch claims a number first (Q21).
- **Student matching refactor.** Extracting the resolver and the batch reads touches ICI2-38 code; the existing 84 import tests plus the golden regression are the guard.

## 10. Open questions

Resolved by the product owner (no longer open): `.xlsx` only; one sheet per file; JDK-only reader; hidden rows and columns fail the file; formula cells accepted with a warning; trivial cleaning counted, non-trivial logged. (Phase 1 questions Q10, Q11, Q12, Q13 and Q16 are closed by these; the rest are folded in below, and the numbering here is new.)

### Real Excel layouts (registrar). These block validating anything against real data

**Section allocation (A)**

- **Q1.** What does the registrar's section list look like? One sheet with a student per row and a section column (assumed); a block per section (title row, then students); one sheet per section; or sections as columns? Which tool or template produces it, and is there a title or header band above the table?
- **Q2.** Which columns exist: student number, LRN, names, grade level, program or strand, term or school year, section, adviser, room, remarks? Is the section written exactly like the names configured in the system (`STEM A`) or differently (`11-STEM-A`, `Section 1`)?
- **Q3.** Does one file cover one campus and one term (assumed)? Are listed students always already confirmed as enrolled, or does the list include applicants still being processed, withdrawn or transferred learners?

**Document tracking log (B)**

- **Q4.** Is the log long (one row per student and document, with a status and a date) or wide (one column per document)? Is there one row per event, so one document can appear several times (received, then verified)?
- **Q5.** Which documents appear, do all exist in the requirement catalog (`PSA`, `FORM137`, `COMPLETION`, `GOOD_MORAL`, others), and what are their legacy labels?
- **Q6.** Which words express state, and what does each mean in school terms (`Received`, `Verified`, `Complete`, `Returned`, `For compliance`, blank)? Is "received but not yet verified" `pending`, and does the system's `submitted` (which stamps a verifier) correspond to "verified"? What do the registrar's `missing` and `invalid` mean?
- **Q7.** Which identifier does each row carry (student number, LRN, application number, or only a name)? Are there verifier and date columns, and in which date format?

### Product and policy decisions

- **Q8.** Is the legacy log date and verifier acceptable only inside the note (my design, because `RequirementService` stamps the importer and the import time), or must `verified_at`/`verified_by` carry them? The latter needs a change to the requirement service and its audit semantics, which is another ticket's code.
- **Q9.** If a log has several events per document, should the latest by date win (a future setting) or should such files be refused (my default)?
- **Q10.** Applications in `rejected` or `withdrawn` are refused by the import (my default, stricter than the service); regressions on an already `enrolled` application only warn. Agreed?
- **Q11.** When a section would overflow, rows are admitted in file order and the rest refused. Is file order acceptable, or should any overflow refuse the whole batch?
- **Q12.** Reassigning an already-placed student needs an explicit review decision and is applied as unassign then assign (two audit events) so swaps between full sections work. Acceptable, or refuse reassignments outright?
- **Q13.** Should an applied batch be revertible (replay `applied` backwards where versions allow), or are the existing section and checklist screens enough?
- **Q14.** One new permission `imports.enrollment.manage` for both A and B (my recommendation), or two?
- **Q15.** May a file ever supply an applicant's grade level when intake left it empty, or is a missing `profile.gradeLevel` always fixed through intake (my design)?
- **Q16.** Unconfirmed (provisional) students are refused as `NOT_CONFIRMED`, because the service forbids allocating them. Do registrars expect the import to confirm them? This design does not.
- **Q17.** Is a native Excel date cell accepted without a configured text format (my default), or must every date follow a configured format?
- **Q18.** Should the ICI2-37/38 upload routes also accept `.xlsx` once the reader exists?
- **Q19.** Is the optional inspect endpoint (phase 7) wanted?
- **Q20.** Jira acceptance criteria: I only have the one-line description. Please paste the criteria (the Atlassian connector needs authentication, which I did not trigger).
- **Q21.** Migration numbering: V22 to V24 are the next free numbers today. Is another branch about to claim one?

## 11. Implementation map (proposed)

Phase 2 (`ph.edu.ici.imports`): `GridReader` and `Grid` (CSV reader implements it), `GridReaders` (format selection), `CellCleaner`, `CleaningProfile`, `CleaningLog`; `StudentResolver` (extracted from `GradeMatrixValidationService`); generic batch reads parameterized by source type (replacing the copy-pasted list/detail/rows/errors in `ImportBatchService` and `GradeMatrixBatchService`); shared mapping-settings validation used by the student and grade mapping services.

Phase 3: `SectionAllocationMapping*`, `SectionAllocationValidationService` (staging), `SectionAllocationCommitService`, `SectionAllocationImportController`; in `ph.edu.ici.enrollment`: `EnrollmentService.assignmentBlockers` extracted from `assign`.

Phase 4: `DocumentLogMapping*`, `DocumentLogValidationService`, `DocumentLogCommitService`, `DocumentLogImportController`; a read-only checklist lookup on `RequirementService` if the existing `checklist` method is not enough.

Phase 5: `XlsxGridReader`, `XlsxPreScan`, `XlsxLimits`. Phase 6: the two upload routes and the `LegacyImportErrors` additions. Tests: `ImportFixtures` extended with an in-test xlsx builder and grid builders.

## 12. Import type A: section allocation (`section_allocation`)

### 12.1 What a row means

One row = **"this student sits in this section for this term"**. Layout `student_list` only: header row, then one student per row. Section lists written as repeated blocks (a section title row followed by its students), one sheet per section, or a matrix with sections as columns are **not supported** and fail closed (`MULTIPLE_SHEETS`, `REPEATED_HEADER`, `UNMAPPED_COLUMN`); see Q1.

| Field | Required | Meaning |
| --- | --- | --- |
| `studentNumber`, `lrn` | at least one | Student identity, resolved exactly as in ICI2-38 |
| `lastName`, `firstName` | optional | Cross-check only; a clear mismatch is flagged, never used to find a student |
| `section` | **required** | The legacy section label |
| `term` / upload `termId` | one of them | Term of the enrollment; same rules as ICI2-38 (upload and column must agree; term implies academic year) |
| `gradeLevel`, `program` | optional | Cross-checks against the enrollment's own grade level and program; never written |
| `schoolYear` | optional | As in ICI2-38 |

Not columns: adviser, room, capacity, program of the section. Sections are configured only through `/api/sections`; a row cannot create or edit one.

### 12.2 Matching: exact or configured only

- **Student:** the campus-scoped resolver extracted from ICI2-38 (phase 2). Student number exact, else LRN exact; both present must agree; a name cross-check below the existing `IdentityMatcher` threshold is flagged. A student at another campus is "not found" without disclosure. Unlike ICI2-38, an **inactive student is an error** (`STUDENT_INACTIVE`), because an archived student cannot hold a seat.
- **Enrollment:** the unique `(student, term)` enrollment at the batch's campus. None: `ENROLLMENT_NOT_FOUND`.
- **Section:** within `(campus, term)` only. The label is looked up in `valueMaps.section` (legacy label to the section's **name**), else compared with section names after the same normalization as value maps (`ImportText.value`). Exactly one match is accepted; none is `UNKNOWN_SECTION`; more than one (names are unique per campus and term only case-sensitively) is `AMBIGUOUS_SECTION`. Never by contains, prefix, similarity, grade-level inference or position. Value-map targets cannot be checked at save time (sections belong to terms), so they are checked at staging.

### 12.3 Which service performs the assignment

`EnrollmentService.assign(enrollmentId, new AssignmentInput(sectionId, enrollmentVersion))`, the method behind `PUT /api/enrollments/{id}/section`. It already enforces: the caller's `enrollment.manage` grant for the enrollment's campus (otherwise 404); locks on the application, the enrollment and both sections in stable id order; the **enrollment version** (409 if stale); enrollment status `enrolled` (the **confirmed-enrollment gate**); section campus, term and program equal to the enrollment's (**section compatibility**); applicant `profile.gradeLevel` against the section's grade level (`11` equals `Grade 11`; missing grade level refuses); **capacity** counting provisional and enrolled seats; refusal when academic class memberships exist; a no-op when the section is unchanged. The import never writes `enrollments.section_id`.

**Staging must not re-implement these rules.** Phase 3 therefore splits `assign` into a read-only `assignmentBlockers(enrollmentId, sectionId)`, which `assign` itself calls, and the unchanged write. Staging and the commit-time recheck call the former; the existing enrollment tests are the regression for the refactor. What staging adds on its own is only what the service cannot know: the same student twice in the file, and capacity across the rows of the batch (§12.4).

### 12.4 Row outcomes

| Situation | Row status | Code | How it ends |
| --- | --- | --- | --- |
| Enrolled, unassigned, section matches and fits | `valid` | none | assigned at commit |
| Already in the named section | `valid` | warning `ALREADY_ASSIGNED` | no write; counted as `alreadyAssigned` |
| **Already assigned to a different section** (reassignment) | `duplicate_flagged` | warning `REASSIGNMENT` with evidence `{fromSectionId, toSectionId}` (ids shown only where the reviewer has the campus) | needs review: `apply` with a reason, or `skip` |
| **Unconfirmed**: enrollment `provisional`, or application not yet confirmed | `invalid` | `NOT_CONFIRMED` | the import never confirms; confirm through the enrollment workflow, then discard and re-upload |
| Enrollment `withdrawn` or `completed` | `invalid` | `ENROLLMENT_CLOSED` | none |
| No enrollment for the student and term | `invalid` | `ENROLLMENT_NOT_FOUND` | none |
| **Incompatible**: section campus, term or program differs from the enrollment | `invalid` | `SECTION_INCOMPATIBLE` | none |
| Applicant grade level differs from the section's, or is missing | `invalid` | `GRADE_MISMATCH`, `GRADE_LEVEL_MISSING` | grade level is recorded through intake, never from this file |
| Academic class memberships exist | `invalid` | `ACADEMIC_HISTORY_EXISTS` | academic transfer workflow, not this import |
| **Over capacity** | `invalid` | `SECTION_FULL` | see below |
| Section label unknown or ambiguous | `invalid` | `UNKNOWN_SECTION`, `AMBIGUOUS_SECTION` | fix the mapping or the section setup |
| Student not found, ambiguous identity, name mismatch | `invalid` / `duplicate_flagged` | as ICI2-38 | `same_person` or `skip` for flagged |
| Same student on more than one row | later row `invalid` | `DUPLICATE_IN_BATCH` | none |
| File `gradeLevel`, `program` or term disagrees with the enrollment | `invalid` | `CONTEXT_MISMATCH` | none |

**Capacity across the batch.** For each target section, staging computes `occupied now, minus students in this batch leaving it, plus students in this batch arriving`. Rows are admitted in **file order** until the section is full; the later rows get `SECTION_FULL`, whose message gives only numbers (capacity, seats occupied), never names. Nothing raises a capacity or picks who "deserves" a seat; the file order rule is a deterministic default and an open question (Q11). Because the service rechecks capacity on every write under its section locks, a seat taken by a concurrent manual assignment fails the commit rather than overfilling.

### 12.5 Commit

One transaction under `FOR UPDATE NOWAIT` on the batch, all or nothing (§14). After the usual gates (batch `validated`, version, no row still flagged, `expectedValidCount`), the recheck calls `assignmentBlockers` again and compares each enrollment's current version with the staged one; any difference demotes the row to `duplicate_flagged` with `CHANGED_SINCE_STAGING`, nothing is applied and the answer is 409. Then, with the committing user as the actor:

1. every **reassignment** row is first unassigned (`sectionId: null` through `assign`), ordered by enrollment id, so seats are freed before they are taken and a swap between two full sections is feasible;
2. every assignment, including the second half of reassignments, is applied through `assign`, ordered by `(section id, row number)` so concurrent import commits take section locks in the same order;
3. rows become `imported` with `student_id` and `applied = {enrollmentId, fromSectionId, toSectionId, versionBefore, versionAfter}`.

If any service call refuses (a race that the recheck could not see), the exception propagates and **the whole transaction rolls back**: nothing is imported, the batch stays `validated`, and the 409/422 names the row number and the service's reason. A reassignment therefore leaves two audit events on the enrollment (to unassigned, then to the new section); this is the price of making swaps possible (Q12).

```json
200 { "batchId": 90, "status": "imported", "assigned": 27, "reassigned": 2, "alreadyAssigned": 1,
      "skipped": 1, "invalid": 0, "importedCount": 30, "version": 4, "alreadyImported": false }
```

### 12.6 Mapping (source type `section_allocation`)

```json
{ "fields": { "studentNumber": { "aliases": ["student_number", "student_no"] }, "lrn": {}, "lastName": {}, "firstName": {},
              "section": { "required": true, "aliases": ["section", "section_name"] },
              "gradeLevel": {}, "program": {}, "term": {}, "schoolYear": {} },
  "valueMaps": { "section": { "<legacy label>": "<sections.name>" }, "program": { "<legacy>": "<programs.code>" }, "term": {}, "schoolYear": {} } }
```
Settings: the ICI2-39 settings of §5 plus `layout: "student_list"`, `ignoredColumns`. Save validation (400, every problem listed): known keys only; `section` required with at least one alias; `studentNumber` or `lrn` mapped; no alias shared by two fields; `program` targets must be existing `programs.code`, `term` targets existing `academic_terms.code`, `schoolYear` targets existing `academic_years.label`; section targets are non-blank names (checked per term at staging); no source value listed twice after normalization; `ignoredColumns` never a field alias. One seeded default, `real_file_validated = false`, empty value maps.

## 13. Import type B: document tracking log (`document_log`)

### 13.1 What a row means

One row = **"for this student's application in this term, this document has this state"**. Layout `long` only: one student and one document per row.

| Field | Required | Meaning |
| --- | --- | --- |
| `studentNumber`, `lrn` | at least one, or `applicationNumber` | Student identity (ICI2-38 resolver) |
| `applicationNumber` | optional | Exact match to `applications.application_number` at the campus; if given with a student identifier they must agree |
| `lastName`, `firstName` | optional | Cross-check only |
| `document` | **required** | Legacy document label |
| `status` | **required** | Legacy state label |
| `date` | optional | Legacy log date |
| `note` | optional | Legacy remark, up to the note limit below |
| `term` / upload `termId` | one of them | Selects the application, as in A |

A wide layout (one column per document, as in the grade matrix) is **not supported** until a real file shows it (Q4); it is detected and refused (`UNMAPPED_COLUMN`), not guessed.

### 13.2 Matching

- **Student:** as in A.
- **Application:** `applications` where `student_id` is the student, `term_id` the term and `campus_id` the batch's campus, or exactly the `applicationNumber`. None: `APPLICATION_NOT_FOUND`; an application with no linked student is reachable only by `applicationNumber`; more than one candidate: `AMBIGUOUS_APPLICATION` (invalid; use `applicationNumber`). Applications are never created or linked by the import. Applications in status `rejected` or `withdrawn` are `APPLICATION_CLOSED` (invalid; a stricter default than the service, which does not check status; Q10).
- **Document:** `valueMaps.document` (legacy label to `requirement_types.code`, exact case, as the API takes codes) or a label equal to a code. Unknown is `UNMAPPED_VALUE`; document types are never created.
- **State:** `valueMaps.status` maps legacy labels to the **four existing states** `submitted`, `missing`, `pending`, `invalid`; canonical words are accepted as they are; anything else is `UNMAPPED_VALUE`. No default translation is shipped (`Received`, `Verified`, `Returned`, `Complete`, `Incomplete` and `For compliance` could each mean different states; Q6). A **blank state cell is no record**, never `missing`.

### 13.3 Which service applies it

`RequirementService.update(applicationId, code, new RequirementUpdate(status, version, notes))`, the method behind `PUT /api/applications/{id}/requirements/{code}`. It already provides: the caller's `enrollment.manage` campus scope (404 outside it); a lock on the parent application row, the same lock enrollment confirmation takes; the required item **version** (409 if stale; an unrecorded item is version 0); verifier attribution from the authenticated user (`verified_by`/`verified_at` set when the new state is `submitted`, cleared when it leaves `submitted`); no-op for an exact repeat; the append-only audit trigger on `application_requirements` with old and new status and notes; and the checklist summary, which is **computed on every read** from the stored items (no completeness column exists to recalculate, so the import cannot leave it stale).

Consequences the design accepts and states:

- **Attribution is the importing registrar and the import time.** The service gives no way to set the verifier or the verification time, so the legacy date and the legacy verifier are *not* stored as `verified_at`/`verified_by`. They are carried in the **note**, which the import composes: `Legacy document log (batch <id>, row <n>): status "<legacy label>", dated <yyyy-MM-dd>. <legacy note>`. If that exceeds 2,000 characters the row is `TOO_LONG` (never truncated). Changing the service to accept a legacy time is a separate decision (Q8).
- **Notes are never silently overwritten.** The service clears notes when none are sent, so the import always sends the composed note, and a row whose target item has different existing notes is a conflict (below).
- **Document verification does not enroll anyone**, but marking the last mandatory document `submitted` can make an application `credentialsComplete`, which is one input of enrollment confirmation. The commit result therefore reports `applicationsNowComplete` (checklist summary before and after, read through the same service) so the registrar sees the effect.

### 13.4 Row outcomes and conflicts with current state

Current state is read at staging through the service's own checklist (status, version, notes, `recorded`).

| Current state of that application and document | Import says | Row status | Code | How it ends |
| --- | --- | --- | --- | --- |
| Not recorded (virtual `pending`, version 0) | any state except `pending` | `valid` | none | recorded at commit |
| Not recorded | `pending` | `valid`, no write | warning `NOTHING_TO_RECORD` | the item is already `pending` by definition |
| Recorded, same state, notes equal to the composed note or both empty | same | `valid`, no write | warning `ALREADY_RECORDED` | counted, no audit event |
| Recorded, same state, other notes | same | `duplicate_flagged` | `NOTES_WOULD_CHANGE` | `apply` or `skip` |
| Recorded, **different state** | other | `duplicate_flagged` | `CONFLICTS_WITH_RECORDED`; evidence `{currentStatus, importedStatus, regression}` | `apply` with a reason, or `skip` |
| Recorded `submitted`, import says any other state (a **regression** that removes a verified document) | other | `duplicate_flagged` | `CONFLICTS_WITH_RECORDED`, `regression: true` | as above; additionally warning `APPLICATION_ENROLLED` if the application is already enrolled |
| Application `rejected` or `withdrawn` | any | `invalid` | `APPLICATION_CLOSED` | none |
| Application or student not found, ambiguous identity or application | | `invalid` / `duplicate_flagged` | as 13.2 | |
| Same `(application, document)` twice in the file | later row | `invalid` | `DUPLICATE_IN_BATCH` | see below |
| `date` in the future, unparseable or ambiguous | | `invalid` | `FUTURE_DATE`, `INVALID_DATE`, `AMBIGUOUS_DATE` | none |

**Nothing differing from recorded state is applied without an explicit review decision**, so the import can never silently overwrite a registrar's verification or regress a document. `apply` requires a reason (stored with the decision, as for every ICI2-37/38 review), and the previous status and notes remain in the service's own audit history. **Several events for one document** (a real *log* may list received, then verified, then returned) are not replayed: replaying would create audit history with an invented sequence and import-time timestamps. By default a repeated `(application, document)` is `DUPLICATE_IN_BATCH`; a future setting `repeatedEntries: "latest_by_date"` is described but not built until a real file needs it (Q9).

### 13.5 Commit

Same skeleton as A (§14): one transaction, `FOR UPDATE NOWAIT`, gates, recheck (an item whose current version differs from the staged one is demoted with `CHANGED_SINCE_STAGING`, 409), then every row that is not a no-op is applied through `RequirementService.update` with the **staged item version**, ordered by application id then document code so concurrent commits lock application rows in a stable order. The service's own 409 (stale) or 404 (scope) aborts and rolls back the whole batch. `applied = {applicationId, code, before: {status, version, notes}, after: {status, version}}` is stored on the row.

```json
200 { "batchId": 91, "status": "imported", "applied": 112, "alreadyRecorded": 9, "skipped": 2, "invalid": 0,
      "applicationsNowComplete": 14, "applicationsNoLongerComplete": 0, "importedCount": 123, "version": 4, "alreadyImported": false }
```

### 13.6 Mapping (source type `document_log`)

```json
{ "fields": { "studentNumber": {}, "lrn": {}, "applicationNumber": {}, "lastName": {}, "firstName": {},
              "document": { "required": true, "aliases": ["document", "requirement"] },
              "status": { "required": true, "aliases": ["status", "remarks"] },
              "date": {}, "note": {}, "term": {}, "schoolYear": {} },
  "valueMaps": { "document": { "<legacy label>": "<requirement_types.code>" },
                 "status": { "<legacy label>": "submitted|missing|pending|invalid" }, "term": {}, "schoolYear": {} } }
```
Settings: §5 settings plus `layout: "long"`, `repeatedEntries: "reject"`, `dateFormats` (as ICI2-37), `ignoredColumns`. Save validation: `document` targets must exist in `requirement_types` (exact case); `status` targets must be one of the four states; `document` and `status` required with aliases; identity as in A; a placeholder (§5) cannot also be a `status` or `document` key. Seeded default: `real_file_validated = false`, empty value maps.

## 14. Shared design for A and B

### 14.1 Source types: yes, both need their own

| Type | `source_type` | Why a new type |
| --- | --- | --- |
| A | `section_allocation` | Own mapping vocabulary, own row meaning, own commit; the unique checksum index, the default-mapping index and every read are keyed by type, so mixing would let one workbook block or satisfy another type |
| B | `document_log` | same |

The names are deliberately **format-neutral** (no `_csv`/`_xlsx`): the format is `import_batches.source_format`. This reverses phase 1's "no new source type" only for A and B; for ICI2-37/38 files in `.xlsx` (if wanted, Q18) it still holds.

**What a new type touches, from reading the code and V19/V20/V21:**

| Item | Needs change? |
| --- | --- |
| `import_batches_source_type_check`, `import_column_mappings_source_type_check` (V21 replaced them) | **Yes**: replaced again in V23 (A) and V24 (B), adding one value each |
| `import_rows_review_decision_check` (V20) | **Yes**: V23 adds `apply`; a new check ties `apply` to the two new types only, and `import_row_review_status` (decisions only on `valid`/`skipped`/`imported`) is unchanged |
| `import_row_grade_never_creates` (V21: only `legacy_student_csv` may take `distinct`) | No. It already forbids `distinct` for every other type |
| `import_rows` check `(status='imported') = (student_id IS NOT NULL)` (V19) | No. Both types are per student, so imported rows carry the student |
| `import_rows_student_created_once` (V21, limited to student rows) | No. A and B rows link a student without creating one |
| `import_batch_void_scope` and `guard_batch_void` (V21, grade batches only) | No, and this is the point: **A and B cannot be voided at the database level** (below) |
| `import_batches_id_source_type` unique and the `(batch_id, source_type)` row FK | No. They already support any type |
| Unique checksum index `(source_type, checksum) WHERE status<>'failed' AND voided_at IS NULL` | No; per type |
| Existing queries (the ~15 occurrences of `source_type`/`SOURCE` in the three student and grade services, including `liveBatch`, batch/list reads, mapping reads and writes) | **No change**: each is pinned to its own service constant, so a new type is invisible to them |
| `ImportValidationService.stagedSql` (cross-campus duplicate candidates) | **No change, but guarded**: it already filters `source_type='legacy_student_csv'`. A and B rows will not store `lrn`/`birthDate` keys, and a test inserts an A/B row carrying an LRN and asserts it is never offered as a student duplicate (the same kind of test ICI2-38 added) |
| `LegacyImportErrors` `assignableTypes` | **Yes**: add the two controllers |
| Per-type copies of batch reads (`ImportBatchService`, `GradeMatrixBatchService`) | **Refactor in phase 2**: one generic implementation parameterized by source type and permissions, behavior-identical, so A and B do not add two more copies |
| Mapping services | A and B each get a mapping service on the shared validation helper |

### 14.2 Migration plan

| Version | Phase | Content |
| --- | --- | --- |
| **V22** | 2 | `import_batches`: `source_format varchar(10) NOT NULL DEFAULT 'csv' CHECK IN ('csv','xlsx')`, `sheet_name`, `formula_cell_count`, **`cleaning_summary jsonb`** (rule counts including trivial rules, which are not in the row log). `import_rows`: **`cleaning jsonb`** (non-trivial changes) and **`applied jsonb`** (what a commit wrote). `CREATE OR REPLACE FUNCTION import_row_audit()` to also omit `cleaning` and `applied` besides `raw_values` and `normalized_values` |
| **V23** | 3 | Source type `section_allocation` in both checks; review decision `apply` and its scope check; permission `imports.enrollment.manage` for Admin and Registrar; one seeded, unvalidated default mapping |
| **V24** | 4 | Source type `document_log` in both checks; one seeded, unvalidated default mapping |

Published migrations are not edited. If another branch claims a number first, the next free one is used and this table updated; folding V23/V24 into one migration is acceptable if both phases ship together.

### 14.3 Cleaning log: trivial counted, non-trivial logged

- **Trivial** (fixed in code, not configurable): `unicode_nfc`, `trim`, `collapse_spaces`, `unicode_spaces`, `strip_invisible`. They never change meaning. They are **counted** per rule in `import_batches.cleaning_summary` and not written per cell.
- **Non-trivial**: everything else in §5 (`placeholder_to_blank`, `name_case`, date parsing by format or native date, number-to-text, padding, `lrn_strip_separators`, formula-cached reads). Each is **logged** in `import_rows.cleaning` as `{field, column, rule, before, after}` with the caps of §4, and also counted in `cleaningSummary`.
- Label cells (section, document, state) are cleaned by the same trivial rules and then looked up through the value maps; the lookup result is visible in the typed fields of the row (`sectionId`, `requirementCode`, `status`), not logged as cleaning.

### 14.4 Permissions

| Action | Permission | Roles seeded |
| --- | --- | --- |
| Read batches, rows, errors, mappings (A and B) | `imports.read` (existing) | Admin, Registrar |
| Upload and stage, review, commit, discard (A and B) | **`imports.enrollment.manage`** (new, V23) for the batch's campus; **and** the called service demands `enrollment.manage` at the same campus on every write | Admin, Registrar |
| Create or edit mappings | `imports.mappings.manage` (existing) | Admin only |

One new permission covers both types: they share an audience (only Admin and Registrar hold `enrollment.manage`) and a risk profile (they change rosters and verification records that gate enrollment). `imports.manage` is **not** enough: it authorizes creating student identities. A and B writes also never need `imports.grades.manage`. Splitting into `imports.sections.manage` and `imports.documents.manage` is possible if the school wants different people for each (Q14). Teachers, advisers, guidance, principal and cashier roles get 403/404 as everywhere else, and a registrar of another campus cannot see or commit a batch of this campus.

### 14.5 Review and commit semantics

- **All-or-nothing**, one transaction, `FOR UPDATE NOWAIT` on the batch, exactly as ICI2-37/38. Per-row partial commits are rejected for three reasons: the services are `@Transactional` and a refusal inside a joined transaction marks it rollback-only anyway; partial rosters or partial verification states are harder to reason about and to reconcile than "nothing happened"; and the existing count balance and `imported` semantics assume the whole batch lands.
- **Gates:** status `validated`; batch `version`; no row still `duplicate_flagged`; `expectedValidCount`; at least one valid row.
- **Recheck at commit:** rows are re-evaluated with the same read-only preflight as staging and the staged item or enrollment `version` is compared; any change demotes the affected rows (to `duplicate_flagged` for state changes, `invalid` for blockers), writes new exceptions, applies nothing and answers 409 so the registrar reviews again.
- **Review decisions:** `same_person` (identity flags; `studentId` taken from the row's evidence), `apply` (accept an overwrite or reassignment, reason mandatory), `skip`. `distinct` is a 409. Reviews only while `validated`; row and batch versions bump as in ICI2-37.
- **Idempotency:** committing an imported batch returns 200 `alreadyImported`; re-uploading identical bytes is `409 DUPLICATE_FILE` while staged or imported; at row level, no-op rows (`ALREADY_ASSIGNED`, `ALREADY_RECORDED`) mean a later file repeating the same data changes nothing.
- **Discard** (before commit): as ICI2-38, the batch becomes `failed` with a `DISCARDED` exception carrying the reason, which frees the checksum for a corrected re-upload.
- **No void.** Unlike grades, nothing in A or B is written to a table the import owns: the writes land in `enrollments` and `application_requirements`, which the existing workflows edit. Undo is those workflows (assign another section or `sectionId: null`; update the requirement), each audited. `applied` records the before and after so a registrar can see what a batch changed. A guarded "revert batch" that replays `applied` backwards (skipping any item whose version moved since) is possible later (Q13); it is not part of this design.

### 14.6 Audit and privacy

- Writes to `enrollments` and `application_requirements` are audited by the **existing** triggers with the committing user as actor (the services set `app.actor_id` themselves); the requirement audit endpoint shows them with old and new status and notes.
- Provenance of a change: `import_rows.applied` and the batch (`source_name`, `checksum`, `imported_by`, `imported_at`, mapping snapshot with its cleaning settings). For B the note on the requirement also names the batch and row. For A there is no free-text field on an enrollment, so the link is `applied` plus the audit event's time and actor.
- `import_row_audit()` omits `raw_values`, `normalized_values`, `cleaning` and `applied`; the legacy values never reach `audit_logs` through `import_rows`. Row messages never echo names, student numbers, LRNs or note text; they give codes, counts and section/document codes. Hidden rows or columns, macros and external links fail the whole file before anything is staged (§6).

## 15. Phase 1 versus phase 1b: what stayed and what was reversed

**Unchanged**

- §2 baseline of ICI2-37/38 and the list of gaps (including the verified NBSP and zero-width finding).
- The cleaning pipeline, rule catalogue and stored-configuration keys of §5 (`sheet`, `nativeDates`, `cleaning.*`; the `formulas` and `hiddenContent` options of phase 1 are dropped because both behaviors are now decided), absent keys meaning today's behavior, and the golden regression requirement.
- The `GridReader` interface, the cleaning log entry shape and its caps, `import_rows.cleaning`, `source_format`, `sheet_name`, `formula_cell_count` and the audit-function change (now in V22).
- §6 safe parsing: limits, zip guards, denylist, fail-closed table, row-number and padding conventions. Two items are now **decided** rather than proposed: hidden rows or columns fail the file; formula cells are accepted with the warning `FORMULA_CACHED`.
- No new source type for ICI2-37/38; `imports.read`, `imports.mappings.manage` and the status-code conventions.

**Reversed or changed**

| Phase 1 | Phase 1b |
| --- | --- |
| Target: a generic `.xlsx` reader for the ICI2-37/38 legacy imports | Target: two **new import types** (A sections, B documents); the reader and cleaning are shared foundation; xlsx for 37/38 becomes optional (Q18) |
| Recommend **fastexcel-reader** 0.20.2, JDK-only as fallback | **JDK-only reader**, no new library (§7) |
| One migration, V22, "if needed" | **V22 (foundation), V23 (A), V24 (B)** (§14.2); V22 gains `cleaning_summary` and `applied` |
| "Log every change, including trim/NFC/space collapse" (my proposal) | **Trivial counted, non-trivial logged** (§14.3) |
| Reader built before the consumers (phase 3 of 5) | **Foundation, A, B, then the reader, then upload endpoints** (§3) |
| `.csv` and `.xlsx` accepted on upload | **`.xlsx` only** for A and B (415 otherwise); CSV stays only in the ICI2-37/38 routes and as a test stand-in |
| Reuse ICI2-37/38 controllers | Two **new controllers** for A and B; the legacy ones untouched |
| Phase 1 open questions Q9 to Q16 | Phase 1 Q10 (formulas), Q11 (hidden), Q12 (xls/multisheet), Q13 (cleaning log), Q16 (dependency) resolved by the decisions above; the rest folded into §10, with new numbering |
