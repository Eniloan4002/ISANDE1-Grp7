# Verification — 2026-11-03

## Executed checks

- Backend: Spring Boot application starts against native PostgreSQL 17.6 and Flyway applies V1–V5. Backend HTTP integration tests exercise real sockets and SQL, not an H2/mock database.
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
