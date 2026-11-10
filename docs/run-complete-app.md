# Run the ICI Local 2.0 web app

For the persistent shared Supabase database, use the [Supabase startup guide](run-with-supabase.md) and `python3 run-supabase.py` from the backend directory. The Docker steps below are the separate local database option.

The development app has three running parts: PostgreSQL, the Spring Boot backend, and the React frontend. Open the frontend in your browser. It forwards `/api` requests to the backend automatically.

| Part | Address |
| --- | --- |
| React app | http://localhost:5173/sign-in |
| Backend REST API | http://127.0.0.1:8081/api |
| PostgreSQL | localhost:5432 |

Current reconciliation: published GitHub V1–V7 are retained and local additions are V8–V11. Fresh databases and published upstream databases upgrade normally. If an existing database reports the older unpublished local history, follow the [migration guide](migration-reconciliation.md) after backing it up; do not delete records or run Flyway repair.

## Before starting

Install Git, Java JDK 21 or later, Node.js 22.12 or later (Node 24 recommended), and Docker Desktop with Compose. Start Docker Desktop. Check `java -version`, `node --version`, and `docker compose version` in a terminal. Maven is supplied by the repository wrapper.

Clone the repository if you do not already have it:

```sh
git clone https://github.com/Eniloan4002/ISANDE1-Grp7.git
cd ISANDE1-Grp7
```

For an existing clean clone, use `git pull --ff-only` to get the team's published version. Save and review local changes before updating a working checkout. Local, unpublished features will not appear in a fresh clone.

Copy `.env.example` to `.env` in the repository root (`cp .env.example .env` on macOS/Linux/Git Bash; `Copy-Item .env.example .env` in PowerShell). Edit `.env`:

- Replace `DB_PASSWORD` with your own local database password.
- Replace `JWT_SECRET` with a Base64-encoded random key of at least 32 bytes. Generate one with `node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"`.
- Leave the default `DB_USER` and `DB_URL` for the supplied Docker database.
- For the first startup, set `BOOTSTRAP_ADMIN_ENABLED=true` and choose `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_EMAIL`, and `BOOTSTRAP_ADMIN_PASSWORD`. The password must contain at least 12 characters and at most 72 UTF-8 bytes.

Keep `.env` private. There is no shared default login. Creating a bootstrap administrator does not reset an existing account.

## Terminal 1: database and backend

Start in the repository root. Keep this terminal open while using the app.

### macOS, Linux or Git Bash

Keep `.env` valid shell syntax; single-quote values containing spaces or shell punctuation.

```sh
docker compose up -d --wait database
set -a
. ./.env
set +a
cd ICILocal2.0/backend
./mvnw clean spring-boot:run
```

### Windows PowerShell

Use plain `KEY=value` lines in `.env`; this loader also accepts matching quotes around values. It skips comments and empty lines.

```powershell
docker compose up -d --wait database
Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $key, $value = $line -split '=', 2
        if ($null -eq $value) { throw "Invalid .env line" }
        $value = $value.Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($key.Trim(), $value, 'Process')
    }
}
cd ICILocal2.0/backend
.\mvnw.cmd clean spring-boot:run
```

Wait for Spring Boot's `Started IciLocalApplication` message. Flyway creates or upgrades the database automatically. Initial dependency downloads may take several minutes. Docker loads `.env` itself; exporting it above also makes it available to Java.

After the first successful administrator provisioning, stop the backend with Ctrl+C, set `BOOTSTRAP_ADMIN_ENABLED=false` in `.env`, clear `BOOTSTRAP_ADMIN_PASSWORD`, and repeat the environment-loading/start commands. Sign in with the administrator credentials you chose.

## Create the other staff logins

The bootstrap creates **only the administrator**. Registrar, Principal, Teacher Adviser, Subject Teacher, Guidance and Cashiering Reference accounts must be created separately on each database. The old `*_demo` preview accounts do not exist automatically on another machine.

In the integrated local version, sign in as administrator, open **User Accounts**, enter the person's username, full name, email and chosen password, select their staff role and one or more campuses, and click **Create staff account**. Share credentials privately. Sign out and sign in using the newly created account. An inactive account can be activated in the same screen. There are no universal role passwords.

The integrated account-management screen is included in this shared version. Principal/teacher/adviser/guidance/cashier modules may still show a development placeholder after a successful sign-in. That is distinct from a login failure.

For repeatable role-account setup on each teammate's own database, use the [reviewer provisioning instructions](gap-closure-handoff.md#per-teammate-reviewer-accounts). They cover the optional Python tool, private password prompts, campus selection and verification-only mode. Existing staff can change passwords in Account Security; administrators can edit access and recover accounts with mandatory temporary-password replacement.

## Terminal 2: frontend

Open another terminal in the repository root:

```sh
cd ICILocal2.0/frontend
npm ci
npm run dev -- --port 5173 --strictPort
```

Open **http://localhost:5173/sign-in**. Use your bootstrap administrator account. `/setup` provides a database-backed workflow for checking that the frontend and backend communicate. Create a test subject and reload to confirm persistence.

The backend has no standalone homepage. Opening port 8081 in a browser is not how you open the app. The dependency-board website is a separate planning tool.

## Which features will I see?

The integrated main version connects Pauline’s queue/intake and requirements screens to persistent backend workflows. It includes duplicate resolution, verified returning-student linking, attendance/confirmation, section configuration/allocation, versioned account management and configurable campus attendance dates. See [shared Supabase startup](run-with-supabase.md), [migration notes](migration-reconciliation.md), and [manual demo](quick-sprint-demo.md).

The duplicate route always uses the real backend; no `VITE_DUPLICATES_LIVE` flag is required. Some academic/guidance/reporting role screens remain placeholders and are separate from a successful login.

## Common startup problems

| Symptom | Check |
| --- | --- |
| Docker cannot connect | Start Docker Desktop, then rerun the database command |
| Port 5432 is occupied | Another PostgreSQL instance may be running; resolve the conflict or deliberately configure another host port and matching `DB_URL` |
| Backend cannot connect to PostgreSQL | Run `docker compose ps` from the repository root; confirm a healthy database and matching `DB_PASSWORD` |
| Database rejects a changed password | Editing `.env` does not change the password of an already initialized PostgreSQL volume; restore the original password or deliberately update the database account |
| Missing `DB_PASSWORD` or invalid JWT key | Reload `.env` in the backend terminal; replace the example values |
| Login fails | Verify bootstrap ran successfully and use your own chosen credentials |
| Frontend reports a proxy/network error | Confirm the backend is running on 8081; Vite targets that port by default |
| Java version error | Ensure this terminal uses JDK 21 or later |
| Port 5173 or 8081 is occupied | Stop the previous app process before starting another instance |

## Stop and restart

Use Ctrl+C in both app terminals, then run `docker compose stop database` from the repository root. Docker's named volume keeps the records. To restart, repeat the two terminal sections; `npm ci` is only needed after dependency changes or on first setup. Reload `.env` each time you open a new backend terminal.

For a temporary demo without Docker, the root README documents `./run-local-postgres.sh`. That alternative discards its database when stopped and is not the persistent setup above.

## Temporary reviewer setup without Docker

From `ICILocal2.0/backend`, run `python3 run-review.py`. It prompts privately for a new password and confirmation, validates its length, then starts the disposable database/backend with administrator `linus_admin`. Wait for startup with no subsequent application failure, then sign in using that password. The launcher does not print or save the password to a file. Keep the frontend running separately. Stopping the launcher discards accounts and records; use the persistent setup above when data must survive restarts.

For an ICI2-13–19 presentation, see [demo setup and manual pass](demo-13-19-checklist.md). The optional `python3 scripts/prepare-demo.py` tool creates separate demo catalogs, two sections, an existing returning student and a verified Registrar login on localhost. It never deletes or overwrites existing records.
