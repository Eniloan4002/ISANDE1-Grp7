# Verification: 2026-11-03

## Executed checks

- Backend: Spring Boot application starts against native PostgreSQL 17.6 and Flyway applies V1-V5. Backend HTTP integration tests exercise real sockets and SQL, not an H2/mock database.
- Backend integration: 12 tests passed with zero failures/errors/skips. Coverage includes BCrypt hashes, JWT identity/roles/campus claims, inactive users, bad credentials, malformed/forged/expired/wrong-issuer tokens, durable logout and unaffected independent sessions, current-role checks, setup CRUD/validation, protected deletes, stale edits and authenticated audit attribution.
- Database: fresh initialization, seeded reference records, complete business-table audit metadata, append-only audit logs with password hashes excluded, safe V2-to-V5 upgrade retaining configuration, migration validation and no-op rerun, and concurrent last-seat reservations (one success, one rejection).
- Frontend: 15 archive/PGlite tests passed, including all 50 archive checksums and original integrity gates. React production build passed.
- Chromium: 5 browser tests passed against the live Spring Boot/PostgreSQL fixture: original shell checks plus sign-in error/success, session restoration, confirmed logout/token-reuse rejection, academic calendar/subject creation/editing, referenced-parent delete rejection, persisted reload, and mobile layout.
- Desktop and mobile setup screenshots visually inspected. Original archive and root demo source remain unchanged.

## Environment and limits

Docker returned an internal daemon error. Real PostgreSQL testing instead used native PostgreSQL 17.6 via a disposable test fixture. No existing school database or Docker volume was modified. Backend compilation/testing used Java 25 targeting Java 21 and Maven 3.9.16. PostgreSQL and Java fixture test processes are temporary; Maven wrapper is included for repeatability.

These checks complete the requested database/authentication foundation, not production deployment or the remaining business modules. Authentication/RBAC boundaries and bootstrap instructions are in authentication.md.

## ICI2-12 verification

17 backend tests passed on PostgreSQL 17.6: the 12 foundation/authentication regressions and 5 student master tests. Flyway fresh startup and V2-to-V5 upgrade passed. The packaged backend builds successfully. New tests cover full-profile persistence, guardians/address, LRN uniqueness, rejected invalid inputs, no partial records on failure, versions, archive retention, audit actor, search/filter/pagination, role restrictions and campus isolation. No frontend code was changed for this API task.

## PR #5 integration

Unified the application under ICILocal2.0/ while preserving the collaborator's shell/routes. Final merged checks: 35 frontend unit tests, 15 archive/schema checks, 17 backend integration tests and 5 real-browser tests, all passing. See pr-5-integration-review.md for conflicts and resolutions.

## ICI2-15 verification: 2026-11-04

All 26 backend tests passed on real PostgreSQL 17.6, including 9 new requirement-tracking tests and 17 existing foundation/auth/student regressions. Backend verify/package passed with Flyway V1-V6; V2-to-V6 upgrade retained existing data. The 15 archive/PGlite checks passed. No checklist UI is claimed: ICI2-16 remains separate. The archive check was decoupled from changing prototype source while retaining all frozen checksum checks.

## ICI2-16-19 verification: 2026-11-05

- 40 backend HTTP/PostgreSQL 17.6 integration tests passed, including 14 enrollment workflow tests. Flyway V1-V7 validated; V2-to-V7 upgrade retained prior data. Backend verify/package passed.
- 57 frontend unit tests passed, covering role routes, document edits/conflicts and preserved drafts, attributed history, follow-up contacts, attendance/confirmation, section creation and assignment restrictions.
- 15 archive/PGlite checks passed with the new migration; released migration files and the frozen prototype remained unchanged.
- The opt-in real Chromium journey passed against a disposable PostgreSQL backend: sign in through a specific application link, verify all credentials, record attendance, explicitly confirm, create a section, allocate, reload and verify persisted assignment and occupied-seat counts. Desktop/mobile captures were inspected; 390px viewport checks include closed navigation and no document overflow.
- Fixed applicant query-string loss during sign-in and a stale capacity display found by browser verification. External font requests were disabled in the browser test to remove network timing from local workflow checks.

The browser applicant and administrator existed only in a disposable test database. No fixture records, credentials or message-sending functionality were added to production migrations. ICI2-14 intake and ICI2-13 duplicate matching remain separate work; see enrollment-workflows.md for their field/locking contract. These changes are local, awaiting team review and publication.

## Punctuation sweep: 2026-11-05

Removed middle-dot separators and en/em dashes from active React and prototype code and accompanying documentation. Labels now use colons, metadata uses slashes or commas, numeric ranges use plain punctuation or words, and absent values use “Not recorded.” The frozen archive and V1-V7 migration files remain unchanged.

All 57 frontend unit tests, 15 archive/schema checks, 40 backend PostgreSQL integration tests and the production build passed. Prototype JavaScript syntax checks and fresh/legacy storage checks confirmed that applicant status, attendance eligibility, grades and user notes are preserved. Legacy metadata and audit timestamp parsing remain compatible. V8 catalog checks confirmed renamed defaults and preserved custom names.


## Pauline frontend integration: 2026-11-09

ICI2-14 queue/intake and ICI2-16 requirements UI were integrated from Pauline’s published PRs with the local authenticated enrollment services. Frontend verification passed 63 unit tests and 15 archive/schema checks, and the production build passed. The real Chromium journey created an application through the form, verified persistence after reload, explicitly reviewed/pre-enlisted it, saved credentials and notes, confirmed present attendance/enrollment, and allocated a section with persistence after reload. Intake and checklist screenshots were inspected at desktop and 390px mobile widths; no horizontal document overflow was found.

Backend tests cover the complete API journey, multiple guardians, validation without partial writes, role/campus scope, duplicate matching and review gating. An additional concurrent-review test passed with one successful review and one conflict, verifying a single student-identity insert. Full duplicate adjudication and existing-student linkage remain pending. See [integration handoff](pauline-enrollment-integration.md).

The browser run used a temporary administrator and disposable PostgreSQL database. No existing school records or prototype source were changed by this integration, and no changes were pushed or deployed.


## Staff-role login fix: 2026-11-09

Final combined verification passed: 49 backend tests (16 foundation/authentication/account, 9 requirements, 5 student master, 19 enrollment), 66 frontend unit tests, 15 schema/archive checks and production build. Both opt-in Chromium journeys passed against the disposable fixture: complete intake-to-allocation and staff account provisioning/sign-in/reload for all seven roles. Account-management mobile overflow was checked at 390px. The sign-in test preserves a requested admin-only URL and verifies that each non-admin reaches an allowed landing page instead.

The fixture and its temporary accounts were stopped after verification. No shared passwords, production demo accounts, or new migrations were added. The updated guide instructs administrators to create actual staff credentials per database. See [staff login fix and GC note](staff-login-fix.md). Missing academic/guidance/reporting services remain separate from successful sign-in.


## Jira acceptance QA: 2026-11-09

Executed QA for ICI2-13–19 and ICI2-49 with 48 documented acceptance cases: 45 passed, two remain blocked by missing production duplicate-resolution / existing-student-linkage scope, and one is conditional on provisioning the actual review-environment accounts. Final execution passed 49 backend tests, 66 serial React tests, 15 schema/archive checks, production build, six historical PR #6 matcher checks and four browser journeys. Intermediate execution timeouts and corrected test-fixture/locator errors are retained in the report. Application code was not changed by this QA. See [QA report and Jira recommendations](qa/2026-11-09/review-report.md) and [test-case matrix](qa/2026-11-09/test-cases.md). The disposable fixture is stopped after verification; Jira, remote publication and existing school databases are unchanged.

## Gap closure: November 9, 2026

Implemented duplicate adjudication, verified existing-student linking, staff grant editing/password security, per-teammate reviewer provisioning instructions/tool, and separate campus/term orientation and first-week attendance ranges. Final checks passed: 59 PostgreSQL backend tests, 70 frontend tests, 15 schema/archive checks, production build and seven browser journeys. Provisioning plus verify-only succeeded against a disposable database. See [current report](qa/gap-closure/review-report.md) and [team handoff](gap-closure-handoff.md). These changes are local and unpublished; exact approved campus dates must be entered by staff.

## Elijah PR #11 connection: November 9, 2026

Reconciled the merged duplicate screen with the persistent local workflow at the upstream component path, replacing mock resolution with real versioned/reasoned decisions and preserving comparison highlighting and page filtering. 75 frontend tests, 15 schema/archive checks, production build and seven fresh live-backend browser journeys passed. Backend code was unchanged from the 59-test baseline. See [integration and Jira handoff](pr-11-duplicate-integration.md). This remains local and unpublished.

## November 10 reconciled QA

Reconciled upstream a62a677 with local enrollment/account work: published V1–V7 retained, additions V8–V11, explicit legacy-history bridge, live permission/campus checks, one versioned account API with last-admin protection, and persistent attendance route. Final QA passed 66 PostgreSQL backend tests, 75 frontend tests, 15 schema/archive checks, production build and all seven browser journeys, including Registrar intake-to-allocation and duplicate decisions. The demo setup helper created and verified its Registrar on a disposable backend. See [current report](qa/2026-11-10-reconciled/review-report.md), [migration guide](migration-reconciliation.md), and [manual demo pass](demo-13-19-checklist.md). Local/unpublished; existing school databases were not changed.

## Shared Supabase publication: November 10, 2026

Publication-tree validation passed 75 backend tests (including preserved upstream account/RBAC tests), 78 frontend tests, 15 schema/archive checks, production build and 13 browser tests. One separate legacy fixture-dependent enrollment test was skipped; the full enrollment path passed in other live browser journeys. The launcher uses existing shared accounts by default; owner bootstrap is explicit. Private credentials/configuration remain outside Git. See [publication validation](qa/2026-11-10-publication/review-report.md) and [teammate startup](run-with-supabase.md). Older QA notes above are historical snapshots.
