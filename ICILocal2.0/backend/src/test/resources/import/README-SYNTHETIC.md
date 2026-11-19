# Synthetic import fixtures (ICI2-37)

Every file here is **synthetic**: invented names, student numbers and LRNs. None comes from the real legacy
ICI Local system, which has not been supplied. The column layout is the *assumed* format in
docs/legacy-student-import-api.md.

Placeholders are replaced per test so repeated runs never collide in the shared test database:
`%N%` = 8 random digits, `%L%` = the same token as lower-case letters, `%U%` = upper-case letters.
