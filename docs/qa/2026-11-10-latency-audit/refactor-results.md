# Implemented latency refactor — 10 November 2026

This supersedes the initial audit's statement that no application fixes had yet been made. The implementation and QA evidence are included together for publication.

## Changes

- Staff account pages load grants for all visible IDs in one batch. The visible-campus exclusion rule and version/response formatting remain in place. SQL executions including session/scope fell from 14 to 5 on ten accounts; a full page has the same bounded grant-query count.
- Enrollment/intake reference catalogs use a single UNION query, preserving scoped active campuses/advisers and existing response fields. Intake reference SQL executions fell from 7 to 3 including session/scope. No cross-request backend permission cache was added.
- Intake/enrollment/checklist assembly reuses an already scoped application row, with scope rechecks on internal composition. Read-side attendance, student-active and open-duplicate flags share one query. Intake detail executions fell from 12 to 8. Confirmation still performs authoritative checks inside its existing locks/transaction.
- Queue carries its filtered total with the page via count(*) OVER(), removing the usual separate count exchange. Empty first page returns zero; an out-of-range later page uses an exact-count fallback. Internal total columns never enter API responses. Query-budget and existing filter/pagination tests cover this behavior.
- Cold queue now starts its directory/statistics independently of catalogs; unavailable catalog options do not block record fetching. Catalog failures retain the retry UI.
- Queue, requirements, staff and existing-student searches debounce for 300 ms. Inputs update immediately.
- Identical authenticated GETs share only in-flight work, with per-subscriber cancellation leases. Unmounting one consumer does not abort another consumer's request. Final abandonment aborts transport after a deferred tick so development StrictMode remounts can reuse it. Abort errors are not displayed as service failures.
- Completed applicant/authorization reads are not cached. Only reference catalogs retain the previous 30-second client cache. Token changes and writes invalidate catalogs; generation keys prevent a late pre-write response from repopulating a fresh cache.

## Real read-only HTTP comparison

Five alternating authenticated GET pairs against old demo backend 8081 and temporary refactored backend 8082 on the same laptop/database. Refactored 8082 had migrations/bootstrap disabled and a read-only connection pool. All 40 GETs returned 200 and all paired JSON payloads were equal. Medians below are diagnostic, not guaranteed latency or load-test p95; the read-only pool setting and network variability may affect comparison. The original 8081 process predates minor publication compatibility changes unrelated to these endpoints.

| Endpoint | Before ms | Refactored ms | Reduction |
| --- | ---: | ---: | ---: |
| `/intake/reference-data` | 2387 | 1281 | 46% |
| `/intake/applications/1` | 3670 | 2621 | 29% |
| `/admin/accounts` | 4191 | 1868 | 55% |
| `/enrollment/applications?termId=1` | 2506 | 2182 | 13% |

The backend still talks to Sydney over a remote path. This refactor reduces waste; it does not make fresh remote reads single-digit milliseconds. Queue perceived cold loading also benefits from removing the separate catalog waterfall, beyond its server timing improvement.

## Browser traffic verification

Isolated Chromium, all actual /api/** traffic mocked with fixed 300 ms responses. No real authentication/database requests. Cold queue GETs reduced **7 to 4**, development duplicate auth/queue/statistics calls disappeared, and queue starts alongside catalogs. Typing Carlo at 60 ms per key reduced searches **5 to 1**. No browser page errors. This is request-shape evidence, not a real-network browser speed claim.

## Validation

- 78 backend tests across the workflow/auth/migration suites and query-budget regressions: zero failures/errors. Query-budget regressions explicitly cover a 25-account page, queue combined total, and empty deep-page count fallback.
- 83 frontend unit/component/API tests passed, including shared-read deduplication, individual cancellation, abandoned transport, late pre-write catalog response and search debounce.
- 15 schema/archive checks passed; no schema migration was added or modified.
- Frontend production build and backend executable jar packaging passed.
- Browser acceptance: **13 passed, 1 fixture-dependent legacy skip** on the final code (1.4 minutes). Tests use a separate disposable PostgreSQL database, never shared demo data. The legacy enrollment test requires a supplied pre-enlisted application fixture; its full workflow is covered by the other acceptance tests.
- git diff --check clean for application changes. Unrelated existing prototype work was preserved.

Evidence: refactor-http-comparison.json, refactor-query-profile.json, refactor-browser-request-shape.json and refactor-source-sha256.json. SQL texts and timings contain no returned personal data, bearer tokens or private configuration values. The comparison uses short-lived audit tokens held only in memory for current existing identities; normal API authorization remains enabled.

## Running app

The normal local Supabase backend was rebuilt and restarted on `127.0.0.1:8081` using the existing private configuration and stable signing key. Flyway validated all 11 migrations and reported the schema up to date; no migration was necessary. Bootstrap remained disabled. The frontend remains on `http://127.0.0.1:5173`. Temporary read-only comparison and disposable acceptance backends were stopped. No cloud hosting service was created.

Post-restart smoke: **15 authenticated GETs returned 200**, with nine demo applications and ten accounts retained. The internal queue count field is absent from returned items. See `refactor-running-app-smoke.json`; three sequential samples per endpoint are diagnostic only.
