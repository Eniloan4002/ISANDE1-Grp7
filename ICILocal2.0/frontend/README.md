# ICI Local 2.0 frontend

The single frontend retains PR #5's AppShell, prototype route registry, Axios client and responsive navigation. It now connects to the Spring Boot backend, verifies staff sessions, supports ROLE_* JWT claims, restores tab sessions and calls the logout invalidation endpoint before clearing authentication.

Run npm ci, then npm run dev in ICILocal2.0/frontend. Vite proxies /api to localhost:8081; set ICI_API_URL to override. /sign-in is the staff login page; /login is an alias. /setup provides the working academic configuration UI. Other reserved module routes remain placeholders. The backend enforces actual authorization.

npm test runs Vitest and archive/PostgreSQL schema checks. npm run build creates dist. npx playwright test runs browser tests; set ICI_LIVE_API=1 with the backend fixture running to enable the authenticated end-to-end tests. See the root README for bootstrap and test credentials configuration.

Prototype-role aliases are confined to src/auth/jwt.js. Do not use browser-decoded claims as server authorization. Backend roles and per-campus grants are revalidated server-side on every request. Full module permission mapping for Principal and Cashier Reference remains ICI2-7.

The local November 9 integration connects Pauline’s ICI2-14 queue/intake and ICI2-16 requirements directory/dialog to authenticated application services. See ../../docs/pauline-enrollment-integration.md for scope and pending duplicate-resolution/existing-student workflows.
