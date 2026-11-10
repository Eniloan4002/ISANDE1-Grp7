# Shared persistent Supabase startup

Use this guide after pulling the integrated version from GitHub main. Everyone connects to the same ICI PostgreSQL database: accounts, applicants, documents, attendance and section assignments are shared and survive local app restarts. Each teammate still runs their own Spring Boot backend and React frontend.

## One-time prerequisites

Install Git, JDK 21 or later, Node.js 22.12 or later (Node 24 recommended), and Python 3. Maven is included. Docker is not needed for this setup. Obtain the **existing project's database password privately from the project owner**. It is separate from staff login passwords and is never committed.

The owner has disabled the project's Supabase Data API; keep it disabled. The app uses Spring Boot for authorization and PostgreSQL connectivity, not Supabase Auth or the frontend Supabase SDK. Use the existing project; teammates do not need to create their own Supabase database.

## Terminal 1: persistent backend

From a clean existing clone:

```sh
git pull --ff-only
cd ICILocal2.0/backend
python3 run-supabase.py
```

For a new clone, first run `git clone https://github.com/Eniloan4002/ISANDE1-Grp7.git` and enter its directory. Save/review any local changes before updating; do not force-reset a teammate's work. Windows PowerShell users can run `py -3 run-supabase.py`.

On the first run, type `yes` when asked whether this project's Data API is disabled. Enter the **Supabase database password** when prompted; typing is invisible. The shared database already contains the ten team/demo accounts, so normal startup does not create a new administrator or change any password.

The launcher uses the configured session pooler on port 5432 with TLS, runs a clean build, and lets Flyway validate/apply the shared V1–V11 migration history. Wait for `Started IciLocalApplication` with no following failure. Leave the terminal open.

The Git-ignored `.env.supabase.json` stores the connection details and a generated local signing key, not the database or staff passwords. It has owner-only permissions on macOS/Linux; keep it private on every platform. Each local backend can have its own signing key; users sign in separately on each machine while records remain shared.

## Terminal 2: frontend

From the repository root in another terminal:

```sh
cd ICILocal2.0/frontend
npm ci
npm run dev -- --port 5173 --strictPort
```

Open http://127.0.0.1:5173/sign-in. Sign in with your assigned account and its existing APP password:

- Administrators: `linus_admin`, `anton_admin`, `pau_admin`, `eli_admin`.
- Demo roles: `registrar_demo`, `principal_demo`, `adviser_demo`, `teacher_demo`, `guidance_demo`, `cashier_demo`.

Get credentials privately from the owner; no password is included in this guide. Existing `linus_admin` kept its original app password; newly provisioned accounts use the demo password chosen during provisioning. Some non-enrollment role modules are still placeholders despite working login.

## Normal restart and demo

Stop the frontend/backend with Ctrl+C. Supabase records remain. To restart, run the two terminal commands again; `npm ci` is needed only after dependency changes. The backend prompts for the database password again. No accounts or demo records need recreating.

The shared demo is **SPRINT DEMO 65633D**, Iligan / STEM / Grade 11. Sign in as Registrar, filter by that term and follow the [quick demo](quick-sprint-demo.md) or [manual acceptance checklist](demo-13-19-checklist.md). Reuse these records; setup tools are not a startup requirement. Everyone sees each other's changes, so coordinate demo actions and future migrations.

Persistence was verified by seeding nine applicants, restarting the backend and reading back the same records. Original temporary-database records are not automatically copied into Supabase. Only fake/reference records supplied through the real APIs were seeded for the demo.

## Owner bootstrap/recovery only

`python3 run-supabase.py --bootstrap-admin` explicitly creates a **missing** `linus_admin` account and prompts for its password. It never resets an existing account. Teammates should use normal startup, not this flag. Password recovery for existing staff uses an authorized administrator's User Accounts controls.

For the currently Codex-managed background backend on the owner's Mac, the old terminal may already be at a prompt while the server remains running. Use `python3 run-supabase.py --stop` to request shutdown of that verified project process, then start normally in your own terminal. Normal terminal-run instances stop with Ctrl+C. The background PID and logs are local, Git-ignored/runtime files.

Supabase connection/security references: [Spring Boot connection guide](https://supabase.com/docs/guides/getting-started/quickstarts/spring-boot), [Data API security](https://supabase.com/docs/guides/api/securing-your-api). Local Docker/disposable alternatives are in [the complete startup guide](run-complete-app.md).
