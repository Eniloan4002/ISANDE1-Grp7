# Reconciled implementation QA: November 10, 2026

Local implementation reconciled against GitHub main **a62a677d746e4ed4ae02a4d36d15d422e9168065**, rechecked as unchanged during final QA. The active checkout's Git HEAD remains 2a82b07; this is a file-level reconciliation of unpublished work with the upstream implementations, not a recorded Git merge or publication. No commits, pushes, Jira changes or upgrades of existing user databases were performed. Prior working files are preserved in `/tmp/ici-before-reconciliation-20261110.tar.gz`.

## Final results

| Verification | Outcome |
| --- | --- |
| Maven verify / real PostgreSQL | **66 passed**, zero failures/errors/skips |
| React unit/component tests | **75 passed** |
| Schema/frozen-archive checks | **15 passed** |
| Production frontend build | Passed |
| Fresh live browser journeys | **7 passed**, zero unexpected failures/skips/flaky results |
| Demo setup helper | Real API creates plus Registrar sign-in passed on disposable V11 backend |
| Reviewer launcher validation | Rejects short/mismatched passwords; passes confirmed bootstrap values without printing secrets |
| Published migration verification | Upstream V1–V7 remain byte-for-byte identical |
| git diff --check | Passed |

[Structured results](results.json), [backend summary](backend-summary.txt), and [tested source hashes](source-sha256.json) preserve evidence without raw test environment properties/passwords. Browser tests ran against a separate disposable PostgreSQL/backend at 18081 and Vite at 5174, leaving the user's 5173 frontend untouched. The backend/API source used for browser verification matches the accepted API code; the final bridge-baseline checksum hardening was separately exercised by the final full backend suite. Test-only backend was stopped afterward.

## Audit findings resolved

- **Migration collision:** retain published V1–V7; move unpublished local additions to V8–V11. Fresh databases and upstream V7 upgrade paths pass. Exact old local V9 history requires an explicit backup/opt-in bridge, with checksum verification across baseline and local migrations, atomic rollback, preserved accounts/grants/audits, session invalidation and idempotent Flyway validation. Altered checksums in either baseline V1 or local V8 are rejected before changes.
- **Stale compiled migrations:** native startup uses a clean compilation so renamed files cannot remain in target and cause duplicate-version failures. Persistent startup guide also uses clean.
- **RBAC:** upstream live permission/campus checks now cover all implemented enrollment services. Tests demonstrate removal takes effect on the next request and unrelated campus grants cannot expand enrollment scope. Directory requests outside scope safely return empty lists; protected record operations are denied.
- **Account reconciliation:** one implementation serves both `/admin/accounts` and `/admin/users`, supports legacy single-role or upstream multi-grant creation, retains username editing, requires versions/reasons, invalidates sessions and forces recovered passwords to be changed. The transaction lock/last-campus-admin guard protects concurrent deactivation. An older edit now returns conflict without overwriting the newer name/username.
- **Attendance reconciliation:** PR #14 component routes into persisted attendance/confirmation. No mock state or fixed August dates authorize enrollment. Approved campus/term orientation and first-week ranges are configurable; gap/out-of-range dates are blocked. The existing at-least-one-qualifying-Present rule remains explicit; an all-five-days policy has not been invented.
- **Duplicate reconciliation:** real persisted cases/three decision meanings, canonical identity reuse, reasons, audit, campus restrictions and optimistic versions remain intact; the upstream mock cannot silently replace them.
- **Archive:** frozen checksum/length tests remain intact, without asserting that the evolving prototype equals the archive. All 15 checks pass.

The parallel upstream account implementation is deliberately consolidated rather than added as a second unversioned write path. Its alias uses the documented paginated/versioned contract; old custom clients expecting an unversioned full-array response need to adopt that contract. Principal/teacher/guidance/reporting business modules remain outside this enrollment demo.

## ICI2-13–19 demo coverage

| Ticket | Verified behavior |
| --- | --- |
| ICI2-13 | Registrar comparison/highlights/filtering; canonical same-person link; distinct-person adjudication; reject incoming duplicate; persisted decision reason/history; exact-LRN and scope/version safety |
| ICI2-14 | Registrar walk-in intake/guardian fields/queue/detail/review; continuing/returnee links preserve existing master |
| ICI2-15 | Document state/notes/audit persist; simultaneous/stale updates conflict |
| ICI2-16 | Pauline's checklist controls/summary use persistent requirement service; reload and conflict behavior verified |
| ICI2-17 | Missing-document/attendance gates; separate configured date ranges; rejected gap dates; eligible persisted confirmation |
| ICI2-18 | Section create/edit/delete guards, capacity changes, simultaneous last-seat protection |
| ICI2-19 | Confirmed allocation/reload, section counts, incompatible/full sections and assignment conflicts |

The full intake-to-confirmation-to-allocation browser journey and duplicate identity journeys execute with newly provisioned **Registrar** credentials. Account administration/recovery and scope tests separately exercise their appropriate actors. All seven defined browser journeys ran; none were skipped.

Screenshots: [requirements](integrated-requirements-desktop.png), [allocation](integrated-allocation-desktop.png), [returning identity](gap-returnee-linked.png), [duplicate rejection](gap-duplicate-rejection.png), [full section](qa-19-full-section.png), [mobile intake](integrated-intake-mobile.png), [account recovery](gap-account-recovery.png).

## Execution notes

The first run exposed obsolete renamed migrations in Maven target output; clean builds fixed that startup issue. A new scope test initially expected a denial response where the existing safe directory contract returns 200 with an empty list; the assertion now verifies empty items/zero total and separately verifies protected operations. The first expanded browser run passed six journeys but navigated immediately after clicking Sign out in the Registrar fixture, before asynchronous logout finished. Tests now wait for the sign-in screen, and all seven passed on the full rerun. A restricted-sandbox test launch could not create native PostgreSQL sockets; final PostgreSQL runs used the required local-process access. These intermediate results are not presented as final passes.

## Remaining human acceptance

Follow [demo setup and manual pass](../../demo-13-19-checklist.md) on the actual revision used for the presentation. Exact institution-approved campus dates and confirmation policy remain staff decisions. Demo helper dates/data are fictional and clearly labelled. A passing automated fixture does not certify teammates' own persistent reviewer accounts; ICI2-49 still needs their verification.

ICI2-13–19 are ready for manual acceptance of the implemented scope. Mark Done only after that ticket's original criteria pass on the shared/presentation revision. Broader module permissions and academic/reporting services are not certified by this enrollment QA. Changes remain local and unpublished.
