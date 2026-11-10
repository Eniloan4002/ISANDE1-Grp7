# Nearby backend investigation — 10 November 2026

Owner is open to investigating a hosted backend. No service, account, billing resource, secret upload or deployment was created.

Recommended first experiment: one always-running Java 21 Spring Boot instance on Fly.io in Sydney (`syd`), retaining the existing Sydney Supabase database. Same-city hosting reduces expected database transport cost but is not proof of shared datacenter/private networking or a latency guarantee. Measure the actual public TLS database path before cutover.

Provider comparison (official documentation checked today):

| Candidate | Location relevant to this project | Assessment |
| --- | --- | --- |
| Fly.io | Sydney `syd` available | Best first locality experiment for the existing database. Docker/Java packaging required. Start with one shared CPU and 1 GB RAM as a sizing hypothesis; observe heap/RSS before resizing. |
| Render | Singapore, no Sydney listed | Convenient Docker hosting, but still a Singapore–Sydney database path. Free service sleeps after 15 minutes and wake-up takes about one minute, unsuitable for predictable demo loading. |
| Railway | Singapore, no Sydney listed | Convenient alternative; retains cross-region SQL path to the current database. |

Fly pricing is region-dependent and usage-based. The current canonical pricing page lists a 1 GB shared-CPU machine at USD 6.70 per 30 days in the lowest-priced US regions; this is **not a Sydney quote**. Verify Sydney's actual compute quote and transfer charges in the provider before authorizing deployment. No new PostgreSQL service is required. One always-on machine avoids cold-start latency but has continuous compute cost; a trial or auto-stop deployment is not evidence of stable warm latency.

Current code readiness findings:

- `application.yml` binds `127.0.0.1:8081`. Hosted container must override `SERVER_ADDRESS=0.0.0.0` and configure its internal port to match `SERVER_PORT`.
- No Dockerfile or hosting manifest was found. Use a multi-stage Java 21 image and package only the app; never copy private `.env.supabase.json` or demo credential files into an image.
- Normal hosted startup is `java -jar ...`, not the interactive laptop `run-supabase.py` launcher. Therefore explicitly configure pool limits; the launcher's three-connection override is not automatically inherited by direct Java startup.
- Supply DB_URL, DB_USER, DB_PASSWORD and a stable server-only JWT_SECRET using the hosting secret store. Bootstrap remains disabled. Retain TLS and Flyway validation; back up and review migration history before the first shared-database startup. Deployment startup can run migrations, so this is a mutation requiring a deliberate deployment step, not a read-only benchmark.
- Current security permits login/error only without authentication, and no dedicated public health route exists. Use a TCP service check initially or implement a minimal readiness endpoint deliberately; don't treat an unauthenticated 401 as a failed app.
- Keep the local frontend using its existing same-origin Vite `/api` proxy, changing ICI_API_URL to the hosted HTTPS backend and verifying Host/origin behavior. This avoids requiring browser CORS for local development. For a public frontend, configure a same-origin reverse proxy or explicit restricted CORS; changing VITE_API_BASE_URL alone does not add backend CORS support.
- The current database accounts and records are retained. No seed/provisioning rerun is needed. Existing bearer tokens may require fresh login if the signing key changes.

Experiment and cutover:

1. Prepare container/config locally, preserving laptop startup. Build/test without embedding secrets. Fix query batching/frontend request waste independently.
2. Owner selects hosting/billing account and approves exact resource cost/configuration. Create a single Sydney instance, store secrets privately, and verify migrations against the existing history before first startup.
3. Measure the same endpoints with the same identities, data and filters: warm/cold, sequential then bounded concurrency, query count, connection acquisition, p50/p95 and errors. Compare against measurements.json; do not claim improvement until observed.
4. Point a separate test frontend at the new backend. Verify login, logout/revocation, scoped roles and ICI2-13–19 against disposable mutation fixtures. Preserve manual demo records.
5. Cut teammates over only after verification. Laptop backend remains a fallback; avoid simultaneously increasing every developer's DB pool.

Primary sources:

- Fly regions: https://docs.fly.io/reference/regions
- Fly current pricing and regional caveat: https://docs.fly.io/about/pricing
- Render regions: https://render.com/docs/regions
- Render Docker: https://render.com/docs/docker
- Render free sleep behavior: https://render.com/docs/free
- Railway regions: https://docs.railway.com/deployments/regions
