# ICI Local 2.0

Student information and enrollment management for Iligan Computer Institute: Iligan Main, Kapatagan and Bugo — CDO.

## Implemented

- ICI2-5: PostgreSQL schema, Flyway V1–V7, common audit metadata/history, reference data and tested relational integrity.
- ICI2-6: staff login, BCrypt passwords, signed JWTs with campus/role claims, protected requests, durable logout revocation and real React sign-in/sign-out.
- ICI2-9: campus-scoped personnel account management, role assignments, activation and password resets.
- ICI2-12: student master API with guardian data, scoped directory search, validation, audits and safe archiving; see docs/student-master-api.md.
- NEW SETUP: subject, academic-year, term and grading-period management APIs and React forms, validation, safe deletion and optimistic updates.
- React shell/design system and the checksum-verified 50-file high-fidelity archive.

PR #5’s shell and route/navigation structure are retained as the single frontend.

Full module workflows and honor policies remain separate tasks; campus-specific RBAC (ICI2-7) is partially implemented. No production data or default passwords are included.

## Repository map

| Location | Purpose |
| --- | --- |
| archive/high-fidelity-2026-11-02/ | Frozen runnable mockup, demo guide and checksums |
| ICILocal2.0/frontend/ | React/Vite shell, staff authentication and academic setup |
| ICILocal2.0/backend/ | Spring Boot, security, setup APIs, migrations and tests |
| docs/database-schema.md | Schema and remaining business-service boundaries |
| docs/authentication.md | Token lifecycle, configuration and initial administrator |
| docs/setup-api.md | Academic setup endpoint contract |
| docs/verification.md | Test evidence and task acceptance mapping |
| docs/reference/implementation-plan.md | User-supplied planning context |

## Persistent local database and backend

Requires Java 21+, Docker Compose and Node 22.12+ (Node 24 recommended). Maven is provided by ICILocal2.0/backend/mvnw (Windows: mvnw.cmd).

1. Copy .env.example to .env. Choose DB_PASSWORD and replace JWT_SECRET with output from openssl rand -base64 32. Keep .env out of Git.
2. In the root, run docker compose up -d database.
3. Export DB_PASSWORD and JWT_SECRET in the shell that runs Java. Docker Compose reads .env automatically; Spring Boot does not. Optional DB_URL/DB_USER default to localhost:5432/ici_local and ici_local.
4. To create the first administrator, also export BOOTSTRAP_ADMIN_ENABLED=true, BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD. Choose a password of at least 12 characters, at most 72 UTF-8 bytes. Existing usernames are not reset.
5. Start the backend:

```sh
cd ICILocal2.0/backend
./mvnw spring-boot:run
```

Flyway applies migrations automatically. The backend binds to localhost:8081. After initial provisioning, disable bootstrap and remove its password from the environment. The persistent database retains accounts and setup records. Stop it using docker compose stop database; removing the volume discards its contents.

## Start the frontend

In another terminal:

```sh
cd ICILocal2.0/frontend
npm ci
npm run dev
```

Open http://localhost:5173/sign-in. Sign in with your provisioned account. Academic setup is at /setup. Vite proxies /api to localhost:8081; ICI_API_URL overrides that target. For deployment, configure /api to the backend on the same origin and serve index.html for client-side frontend routes.

## Disposable PostgreSQL alternative

If Docker is unavailable, ICILocal2.0/backend/run-local-postgres.sh starts a real PostgreSQL 17 database and backend using test-only infrastructure. Export your chosen BOOTSTRAP_ADMIN_* values as above, then:

```sh
cd ICILocal2.0/backend
./run-local-postgres.sh
```

This database is temporary and disappears on shutdown. The launcher uses a test signing key and is for local development only. Test classes are excluded from the packaged application. Persistent school records belong in the configured PostgreSQL service.

## Verification

```sh
cd ICILocal2.0/backend
./mvnw verify
```

Backend tests run against a temporary native PostgreSQL 17 server, including migration upgrade, HTTP authentication, logout and multi-connection capacity checks. No Docker daemon is required. Native binaries cover Linux/Windows x86-64 and macOS x86-64/Apple Silicon; other architectures need matching test-binary dependencies. Run as a normal user, not root.

```sh
cd ICILocal2.0/frontend
npm test
npm run build
npx playwright test
```

First-time browser setup: npx playwright install chromium. To include live API browser tests, start the disposable backend with your test administrator and run ICI_LIVE_API=1 ICI_TEST_USERNAME=your-name ICI_TEST_PASSWORD=your-test-password npx playwright test. Set ICI_API_URL if the backend is on a non-default port. Without ICI_LIVE_API, the two live API tests explicitly skip.

## Archived prototype

At the repository root run python3 -m http.server 8080 and open http://localhost:8080/archive/high-fidelity-2026-11-02/. Follow DEMO_SCENARIO.md. This archived demo uses seeded localStorage and an unauthenticated role selector; it is not the new authenticated application.

## Migration rules

Never edit applied migrations or manually execute SQL against a Flyway-managed database. Add a new V6__description.sql and later versions. Use separate migration-owner and restricted runtime database credentials for deployment; the Docker owner account is local-development configuration.

Technical sources: [Spring Security](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html), [JJWT](https://github.com/jwtk/jjwt), [Flyway](https://documentation.red-gate.com/flyway), [PostgreSQL](https://www.postgresql.org/docs/current/ddl-constraints.html).
