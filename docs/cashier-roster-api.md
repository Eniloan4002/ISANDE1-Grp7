# ICI2-36 — Cashiering reference roster API

Implements an **on-demand** export; no scheduler or payment/billing system is introduced. Existing UI/screens are unchanged.

## Authorization and scope

Both endpoints require an authenticated bearer token and `cashier.read`. The permission and campus must be supplied by the same current role grant. `ROLE_CASHIER_REF` already has this permission; being an administrator, registrar or principal alone does not confer cashier access. Give appropriate staff an explicit cashier role grant at each authorized campus. Unauthenticated requests return 401; missing permission or an explicitly foreign campus filter returns 403. A foreign/unknown section returns 404.

## Preview

`GET /api/cashiering/rosters`

Optional query parameters: `campusId`, `academicYearId`, `termId`, `programId`, `sectionId`, `gradeLevel`, `voucher`, `page` (default 0), `size` (default 25, maximum 100). Identifiers must be positive. Unknown years, terms/programs, incompatible term/year or section/filter combinations, and invalid voucher codes return 400. Voucher is the exact configured code (QVR, ESC, ALS, REGULAR initially), or `UNCLASSIFIED` for absent classification.

Returns:

- `items`: the requested page, ordered by QVR, ESC, ALS, OTHER, UNCLASSIFIED, then voucher code, campus, term, student number and enrollment ID.
- `total`: all matching confirmed enrollments, unaffected by page size.
- `voucherGroups`: totals for all five groups, including zeros.
- `vouchers`: totals by exact classification, preserving configured values.
- `asOf`: database transaction timestamp in UTC; each preview uses one consistent database snapshot.
- `purpose`: `cashiering_reference`, plus page and size.

Each item contains enrollment ID, student number/name, campus, year, term, program, grade level, section, exact voucher classification, group and confirmation timestamp. Contact information, birth dates, grades, admission documents and confidential counseling notes are excluded.

Only current `enrolled` records with confirmation and attendance-verification attribution are included. Provisional, withdrawn and completed enrollment records are excluded; active student status alone is not enrollment confirmation. Enrollment is unique per student/term. No joins to one-to-many grades/requirements are made, preventing duplicate roster entries. Null section is retained as an empty section label.

Application voucher classification takes precedence over the student's current voucher default, preserving the enrollment application's categorization. QVR, ESC and ALS retain their exact categories. Other configured classifications (including REGULAR) are explicitly grouped under OTHER with the original code retained. Missing classifications appear under UNCLASSIFIED rather than being omitted.

## CSV export

`GET /api/cashiering/rosters/export`

Accepts the same data filters; pagination parameters do not limit the export. Returns every matching enrollment in UTF-8 CSV, with quoted/escaped cells, CRLF row separators, preserved Unicode/multiline names and spreadsheet formula-prefix protection. Download filename is `ici-cashiering-roster.csv`, with attachment, `no-store` and `nosniff` response headers.

Columns: Voucher group, Voucher classification, Student number, Name, Campus, Academic year, Term, Program, Grade level, Section, Confirmed at.

The complete operation uses one consistent snapshot. A maximum of **5,000** matching enrollments is supported; above that, the endpoint returns **413** with a reason to narrow filters, never a silently truncated file. Empty results produce the header row only. A later preview/export is a fresh snapshot and can reflect intervening enrollment changes.

Successful exports write append-only audit provenance: server-derived staff ID and timestamp, authorized campus scope, filters, row count and SHA-256 digest of the exact CSV. Audit metadata does not duplicate student names or private student records. Failed/oversized exports do not create success audit entries.

## Verification

`CashierRosterTest` covers grouping/pagination/deduplication; provisional/withdrawn/completed exclusions; application-voucher precedence; permission-bearing campus isolation with mixed roles; combined filters and invalid combinations; protected CSV/Unicode and audit digest; and rejection of an actual 5,005-row export without truncating preview counts. Tests use disposable PostgreSQL and do not access the shared school database.
