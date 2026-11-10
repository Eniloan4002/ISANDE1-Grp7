# PR #5 integration review: 2026-11-03

Reviewed collaborator antqnluis's commit 778b048fed79c25b1342c3f5292be4a4cf03785e against main 4b8bf3b201b84359575c5dd55d1e0d32ffa6549c and our uncommitted implementation. All 69 changed paths inspected: 19 new application files, README change and 49 byte-preserving prototype renames. The original PR's 33 tests and production build passed in isolation.

## Conflicts and resolutions

| Finding | Evidence/trigger | Resolution |
| --- | --- | --- |
| Git content conflict | Trial merge with our snapshot produced one conflict: root README.md | Combined the complete implementation/setup guide with the collaborator's workspace layout |
| Two application workspaces | PR uses ICILocal2.0/, local work used root frontend/ and backend/ | One application under ICILocal2.0/; no parallel root applications remain |
| JWT role mismatch | PR adapter returned [] for backend ROLE_ADMIN/ROLE_REGISTRAR claims | Explicit alias mapping, with unknown roles still rejected; verified current staff profile drives authenticated routes |
| Local-only logout | PR AuthContext cleared browser state without invalidating the JWT | Sign-out calls POST /api/auth/logout; browser session clears only on confirmed invalidation or 401; token reuse tested |
| Placeholder sign-in / memory-only session | PR deliberately supplied scaffolding pending the authentication task | Integrated real login, session restoration via /api/auth/me, sessionStorage, expiry timer and protected setup |
| Development API transport | PR default /api had no Vite proxy to the Spring Boot port | Same-origin /api proxy with ICI_API_URL override; one shared Axios client |
| Prototype relocation breaks archive regression | Our old checksum test read original files at repository root | Test reads prototype/ and verifies the unchanged frozen archive independently |
| Mobile aria-expanded | PR computed mobileOpen OR !desktopCollapsed, announcing expanded on closed mobile navigation | Use viewport-specific expanded state; unit and browser regression assertions added |
| Active nav group could not collapse | Active route forced its group open on every render | Group state can be toggled while retaining the overall navigation and selected route |

The collaborator's AppShell, route registry, Axios instance, theme and existing clean route paths were retained. Academic setup and component examples were added as shared authorized routes. The original prototype remains byte-for-byte in prototype/; the separate immutable snapshot and manifest remain in archive/high-fidelity-2026-11-02/.

## Final verification

- 35 frontend unit tests (including the collaborator's route and Axios coverage, updated for verified sessions).
- 15 archive/PGlite regression checks; all 50 frozen source checksums preserved.
- 17 backend HTTP/PostgreSQL integration tests, including all five Flyway migrations, legacy upgrade, authentication, revocation, student APIs and capacity concurrency.
- 5 live Chromium tests against the merged frontend and PostgreSQL-backed API: sign-in, session reload, durable logout, setup CRUD, reference protection, reserved routes, dialogs, mobile navigation and 404.
- Frontend production build and backend package/verify passed. Desktop and mobile layouts inspected.

No remaining Git conflict is intentionally deferred. Principal/Cashier module permissions remain ICI2-7, account screens remain ICI2-9, and other module screens remain placeholders rather than being claimed as completed functionality. The remaining security/task boundaries are described in authentication.md and implementation-status.md.

No PR review comments or messages were posted on behalf of the user. The collaborator's original commit is retained as a merge parent; integration changes are separate from their authored work.
