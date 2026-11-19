# Synthetic import fixtures (ICI2-37)

Every file here is **synthetic**: invented names, student numbers and LRNs. None comes from the real legacy
ICI Local system, which has not been supplied. The column layout is the *assumed* format in
docs/legacy-student-import-api.md.

Placeholders are replaced per test so repeated runs never collide in the shared test database:
`%N%` = 8 random digits, `%L%` = the same token as lower-case letters, `%U%` = upper-case letters.

## Grade matrix fixtures (ICI2-38)

`synthetic-grades-*.csv` are **synthetic** grade matrices: invented student numbers, subject codes (`SYNMATH%N%`, `SYNSCI%N%`), names and
marks. No real legacy grade file has been supplied; the layout (one row per student, `Subject - Period` columns) is the *assumed* format in
docs/legacy-grade-matrix-import-api.md, so real-file validation is still pending. The tests create the matching students, subjects, academic
years, terms and grading periods themselves, using the same `%N%`/`%L%` placeholders.
