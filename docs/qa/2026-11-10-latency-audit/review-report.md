# Latency audit — 10 November 2026

## Outcome

Current delays are dominated by repeated remote database round trips, compounded by frontend request waterfalls and unnecessary requests. SQL execution is already in milliseconds on the small demo dataset. A faster index alone cannot remove the measured network/driver/pooler latency. Code batching plus a backend close to the database is the strongest route to consistently sub-second fresh reads. Single-digit end-to-end milliseconds is not a defensible target across the current laptop-to-Sydney connection.

Audited main: `c6612c8d03e382824496cdaf952165aeec48a48f`. GitHub main was fetched and unchanged at audit start; prior backend/frontend CI succeeded. Source review covered authentication, scoped authorization, enrollment/intake, requirements/audit, duplicate resolution, staff accounts, academic setup, frontend fetch lifecycle and launch/deployment settings. This is a performance audit, not a claim that every repository file or every business workflow was exercised.

No business records, accounts, schema, deployment or running application configuration were changed. Audit tooling and this report are local additions. No new application performance fix has been shipped in this audit.

## Live measurements

Five sequential authenticated GETs per endpoint, local running Spring Boot on port 8081, current Sydney Supabase session pooler. Values include HTTP/authentication/backend/database work, exclude browser rendering. These are diagnostic samples, not a load-test p95 or SLA. Cold/JIT effects are retained, including the first auth sample. Every sampled GET returned 200.

| Endpoint | Median ms | Min–max ms | Result |
| --- | ---: | ---: | --- |
| `/auth/me` | 249 | 240–771 | 5/5 HTTP 200 |
| `/intake/reference-data` | 2095 | 2042–2099 | 5/5 HTTP 200 |
| `/enrollment/applications?termId=1` | 1944 | 1917–2171 | 5/5 HTTP 200 |
| `/intake/applications/1` | 2999 | 2983–3523 | 5/5 HTTP 200 |
| `/applications/1/requirements` | 1852 | 1842–1863 | 5/5 HTTP 200 |
| `/admin/accounts` | 3460 | 3443–3507 | 5/5 HTTP 200 |

A reused read-only JDBC connection measured ten `SELECT 1` exchanges at **225.54–300.76 ms**, median **281.05 ms**. PostgreSQL reported **0.029 ms execution** for `EXPLAIN ANALYZE SELECT 1`. This difference includes network, pooler, protocol and driver costs; it is not a pure ICMP RTT measurement. Initial new connection setup was **2,027 ms**; pooled HTTP requests do not necessarily pay it each time.

Queue count and page SQL executed in PostgreSQL in **0.316 ms** and **1.473 ms**, respectively; planning was **0.681 ms** and **5.153 ms**. The database currently has nine applications, eight students, ten accounts and 106 audit events. These plans do not predict large-dataset performance.

## Query-count profile

Actual current service/controller classes, read-only transaction, fresh request attributes for each scenario; includes current actor session and an explicit permission scope check. These timings exclude HTTP/JWT verification, connection acquisition and Spring transaction setup/commit. Do not equate them with full API latency: transaction boundaries, driver fetch behavior and scheduling add costs in real HTTP. Row-dependent counts can change as demo data changes.

| Scenario | SQL executions | Direct-profile ms |
| --- | ---: | ---: |
| auth/me (actor/grants only) | 1 | 288 |
| intake/reference-data | 7 | 1787 |
| enrollment/reference-data | 6 | 1580 |
| enrollment/applications | 4 | 1052 |
| intake/statistics | 3 | 796 |
| intake/applications/detail | 12 | 3681 |
| applications/requirements | 4 | 1041 |
| applications/requirements/audit | 5 | 1275 |
| applications/enrollment | 11 | 2913 |
| sections | 4 | 1046 |
| duplicate-cases | 4 | 1047 |
| duplicate-cases/detail | 10 | 2626 |
| admin/accounts (admin, up to 25 rows) | 14 | 3658 |

## Prioritized findings and fixes

### 1. Place Spring Boot close to PostgreSQL — highest structural impact

The configured pooler is `aws-1-ap-southeast-2.pooler.supabase.com`, Sydney. Each teammate currently runs Spring Boot on their own laptop. That makes a screen with multiple SQL exchanges repeatedly cross the remote path. Host one backend in/near the database region, keep the database persistent, and let frontend clients make one regional HTTPS API call per resource. This avoids repeated long-distance database exchanges inside one request. Region proximity is a deployment hypothesis to measure, not an already verified improvement.

Start by benchmarking an isolated read-only backend near the existing Sydney database. A future Singapore database/backend pair may suit Philippine users, but migrating persistent data and changing regions should be a separate reviewed operation. Do not move the database merely because its SQL engine is suspected to be slow. Do not purchase IPv4 or switch to transaction pooling as an assumed fix. Validate connectivity, session/advisory-lock semantics and actual timings first.

### 2. Staff account N+1 queries — immediate code opportunity

`ICILocal2.0/backend/src/main/java/ph/edu/ici/auth/StaffAccountController.java:52–67`: list selects a page, then `view()` fetches grants separately for each account. The ten-account page required **14 SQL executions** including session/scope. A full 25-account page implies 29 under the same profile model.

Fetch grants once for all returned account IDs, group in memory, and preserve the existing scoped visibility condition, ordering, version formatting and no-secret response. Target **5 or fewer** profiled executions independent of page length. At today's observed exchange cost, removing nine serial exchanges offers seconds of potential savings, but only a before/after HTTP benchmark can establish the real improvement.

### 3. Applicant detail repeats reads and over-assembles — immediate code opportunity

`enrollment/IntakeService.java:50`, `EnrollmentService.java:116–128`, `requirements/RequirementService.java:75`: intake loads the application, enrollment loads it again, checklist loads its identity again. Additional statements fetch attendance, qualifying attendance, enrollment, student active flag, duplicate state and attendance windows. Measured **12** total executions.

Build a scoped read model once and reuse the application identity internally. Batch one-to-one flags using EXISTS/joins and aggregate child arrays without creating Cartesian duplicates. Keep application/campus checks, current permission checks, optimistic versions, duplicate blocking and authoritative write validation. Target roughly **4–6** total statements as a design budget, not a claim already achieved.

The requirements dialog (`frontend/src/enrollment/RequirementsScreen.jsx:9`) fetches the whole intake detail even though a lighter checklist endpoint exists. Use the checklist endpoint plus only the contact summary actually needed, or define a compact dialog endpoint. History should remain paged and can load on demand. The live checklist endpoint was 1.85 seconds versus 3.00 seconds for full intake detail.

### 4. Catalog waterfall delays cold queue — immediate frontend opportunity

`frontend/src/enrollment/EnrollmentScreens.jsx:306–318`: parent requests intake catalogs and returns a loading screen until they arrive. Only then does the queue mount and request directory and statistics. Catalog retrieval median is 2.095 seconds; queue median is 1.944 seconds, so this serial arrangement can visibly add their waits. This is a structural estimate, not a measured browser completion time.

Mount the queue data requests independently of catalogs. Show usable rows while filter options finish. Batch the small reference catalog SELECTs into one read response/query where safe, or cache stable catalogs with bounded scope-aware invalidation. Existing client catalog cache lasts 30 seconds and deduplicates catalog requests only. Preserve explicit refresh and clearing on account changes/writes.

### 5. Search creates obsolete work — immediate frontend opportunity

Queue, requirements, staff directory and existing-student lookup bind each keystroke straight to the request URL. `frontend/src/EnrollmentWorkspace.jsx:7–14` cleanup ignores old responses but does not cancel transport; `frontend/src/api.js` does not forward an AbortSignal. Typing five characters can launch five API searches. Slow responses continue occupying backend/database resources after becoming irrelevant.

Debounce searches around 250–350 ms; update input immediately, reset pagination when the effective search changes, and forward AbortSignal through Axios. Add in-flight deduplication for identical scoped GETs and prevent canceled requests from surfacing as user errors. Browser cancellation does not guarantee PostgreSQL work already executing will stop, so debounce/dedup are important even with cancellation.

React StrictMode intentionally reruns effects in development. The current hook can issue duplicate non-catalog requests on mount; catalog calls already deduplicate. Fix fetch lifecycle rather than removing StrictMode. Compare production build separately before estimating customer traffic.

Browser verification: an isolated Chromium context intercepted **every actual /api/** endpoint with a fixed synthetic 300 ms response (no live login/database calls). Cold queue issued **7 GETs**: auth/me twice, reference once, queue twice, statistics twice. Queue requests began after the catalog response completed. Typing `Carlo` at 60 ms per character issued **5 queue searches**, for C/Ca/Car/Carl/Carlo; all completed. There were no page errors. This verifies request shape on the development server, not real user network latency or production duplicate counts. See `browser-request-shape.json`.

### 6. Pool contention and multi-request pages — measure after reducing waste

`backend/run-supabase.py:86` sets Hikari maximum three connections per local backend. Academic setup (`frontend/src/AcademicSetup.jsx:52–55`) starts all four catalog calls through Promise.all. Queue/statistics/search and obsolete dev requests can compete for three connections. No concurrent pool-wait benchmark was performed, so contention is a credible mechanism rather than a measured cause for each request.

Load only necessary setup data or provide one setup bootstrap endpoint. Measure active/idle/pending connections and acquisition latency before changing the pool. More teammates each running a larger pool can exhaust shared database capacity; do not blindly raise it. Keep DB password in backend configuration only.

### 7. Keep useful UI visible and cache deliberately

The hook clears data on path changes; revisiting records has no reusable record cache. A short-lived account/scope/parameter-keyed read cache with background refresh can make revisits feel immediate. Always clear protected data on logout/account changes, invalidate affected queue/detail/count/section data after mutations, and label any stale view. Do not treat cached capacity, credential status or permissions as authoritative for writes. Keep backend permission/revocation checks live.

Use lazy route imports to reduce initial JavaScript work if production browser traces show it material. It is secondary to seconds of remote SQL. No production rendering/long-task or Core Web Vitals benchmark was completed in this audit.

### 8. SQL scalability work — useful, but not today's main delay

`EnrollmentService.APPLICATION_FROM` is shared by count/page and includes catalog joins and lateral requirements aggregation. Use a minimal count query with equivalent filters; page application IDs before expanding requirement summaries where filter semantics permit. Consider keyset pagination only if deep-page scale warrants it; retain current total/filter contract or deliberately update it.

Duplicate candidates are already filtered in SQL by LRN or birth date, then matched in Java; this is **not** a blind full-table Java scan. LRN indexes exist. The existing `(lower(last_name),lower(first_name),birth_date)` index is not a straightforward leading birth-date index. Test standalone birth-date or name-search indexes with representative synthetic data before adding migrations. Candidate-loop queries and the global duplicate advisory lock can serialize writes; no live write/load benchmark was run and the lock must not be removed without preserving concurrency correctness.

Requirement audit already has the correct application-expression/id partial index in **V8**, verified on the live database. Do not add a duplicate index for this path. Existing queue, LRN, enrollment, attendance and audit indexes were inventoried in measurements.json. Substring search at future scale may benefit from pg_trgm indexes, subject to actual query/plans and controlled dataset tests.

## Practical targets and acceptance

These are proposed goals, not measured promises:

- Existing laptop + remote DB: remove redundant SQL and request waterfalls first; fresh reads still pay hundreds of ms per exchange, so do not promise every screen below 100 ms.
- Nearby hosted backend plus batched reads: aim for **p50 150–400 ms, p95 below 800 ms** for ordinary read endpoints, then revise from actual measurements and hosting limits.
- Safe cached navigation: aim for visible existing content within **100 ms**, with freshness state and background validation. This measures perceived rendering, not fresh server data.
- PostgreSQL simple statement execution: already sub-2 ms for measured queue statements on this demo database. Retest with realistic volume and concurrency.

Implementation order: (1) debounce/dedup/cancellation and batch account grants; (2) compact detail/catalog reads and remove queue waterfall; (3) measure nearby backend; (4) scale-query tuning and production browser traces. Add opt-in timing observability for HTTP duration, query counts, pool wait and frontend request stages; never log JWTs, passwords or personal query parameters. Use deterministic query-count regression tests, not a flaky public-network latency threshold in CI.

Retest all ICI2-13–19 correctness paths after changes: exact/fuzzy duplicates and decisions; queue/intake/search/page; requirement verification/history/version conflict; attendance windows; confirmation eligibility; section capacity and concurrent assignment; cross-campus RBAC and session revocation. Use a disposable database for mutations and representative load. For network performance, collect at least dozens of warm/cold observations with 1/5/10 concurrent sessions; report p50/p95, errors, pool wait and render timing separately.

## Reproducibility and limits

`measurements.json` contains full sample arrays, SQL texts (no bound personal values), SELECT query plans, live index inventory, row counts and methodology. `scripts/performance/ReadOnlyProfile.java` enforces a read-only JDBC transaction and rolls back. Its optional live GET phase issues three-minute audit JWTs using the private local signing key for existing current registrar/admin identities; requests pass the normal live filters. Tokens and credentials stay in process memory and are never included in artifacts. Direct-call counts are diagnostic; actual endpoint calls provide the separate HTTP timing evidence. The running backend predates minor publication API-compatibility changes; relevant profiled performance code matches current published classes. Do not interpret these measurements as a restart/deployment verification of every latest API behavior.

No production fixes or schema changes were made here; no write-path load tests were run on the team's shared data. Audit findings identify code opportunities and deployment experiments; they do not prove new speed gains until implemented and retested.

## Primary documentation

- PostgreSQL EXPLAIN and caveats: https://www.postgresql.org/docs/17/using-explain.html — engine execution time excludes client/network transfer; small demo plans do not establish scale performance.
- React StrictMode: https://react.dev/reference/react/StrictMode — extra effect checks are development-only.
- Supabase project regions: https://supabase.com/docs/guides/platform/regions — project region placement and available regions.
- Supabase regional invocation: https://supabase.com/docs/guides/functions/regional-invocation — documents database round-trip locality for functions; applying the locality principle to Spring Boot is an engineering inference, not a measured deployment result.
