# Supabase demo seed and navigation performance

November 10, 2026. Local code remains unpublished. The requested fictional demo set was created through authenticated application APIs on the running Supabase-backed backend. The existing runtime bootstrap credentials were used privately; they were not printed, saved into scripts, or placed into CLI arguments. The tool's login session was logged out afterward. Existing accounts and records were preserved.

## Seed outcome

Nine applicants, two sections, two existing identity fixtures and a dedicated demo year/term were created. Term: **SPRINT DEMO 65633D**, Iligan, STEM, Grade 11. The helper was first exercised against disposable PostgreSQL, including a rerun that preserves progress. Cloud writes and final read-back checks completed successfully. [Demo sequence and scenarios](../../quick-sprint-demo.md) lists the names/actions. The local Git-ignored `.demo-sprint-seed.json` contains IDs and deep links, not credentials.

## Changes

- Session authentication combines current account, revocation and active-campus grants into one SQL round trip; no account/grant cache spans requests.
- Permission/campus results are reused only within the current HTTP request. Account writes explicitly refresh after acquiring their transaction lock, so an earlier authorization cannot bypass the post-lock check.
- Intake reference loading retrieves vouchers directly instead of fetching and discarding a second campus/program catalog.
- Frontend reference catalogs are deduplicated/cached for 30 seconds, keyed to the signed-in token. Successful writes/account changes invalidate them, explicit reload bypasses them, and failures are not retained. Live applicant records/decisions and authorization responses are not cached.

## Verification and measured results

68 backend tests, 78 frontend tests, all seven live-browser journeys and production build passed. New tests verify next-request permission revocation, post-lock reauthorization, catalog deduplication, write/account-change invalidation, explicit refresh and failed-request recovery. Existing recovery, role/campus, concurrent last-admin, migration and enrollment tests remain green.

After QA, the backend was restarted with the verified jar, existing private Supabase configuration and unchanged JWT signing key. All nine demo records were checked before and after; the same issued session remained valid across restart. No migrations/data resets or account password changes were performed. The new backend runs in the background; its log is `/tmp/ici-cloud-optimized-backend.log` and its PID is stored in Git-ignored `.env.supabase.pid`. The original terminal may return to its prompt after the old backend shuts down.

| API | Before | After |
| --- | --- | --- |
| Session profile | 0.91 s | 0.23 s |
| Intake reference | 4.20 s | 2.33 s |
| Queue for demo term | 3.14 s | 2.19 s |
| Applicant detail | 5.02 s | 3.58 s |

These are individual observations over the owner's network, not a load test or guaranteed response-time target. The frontend catalog cache additionally avoids repeat reference requests within its window. Laptop-to-Sydney database round trips remain a limit; a backend deployed near the database would remove much of that network distance. Some detailed screens still execute several queries and can show loading briefly.

[Structured results](results.json) and [tested application source hashes](source-sha256.json) contain no passwords/tokens. The Java/backend API sources and frontend sources in that manifest match the final passing tests; the launcher stop-control change is a separate operational helper.
