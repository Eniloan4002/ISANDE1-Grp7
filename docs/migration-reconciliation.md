# November 10 migration reconciliation

The reconciliation uses GitHub main a62a677 as the upstream reference. Published SQL V1–V7 is retained byte-for-byte. Unpublished local additions follow it:

| Version | Migration |
| --- | --- |
| V6 | Published administrator enrollment permission |
| V7 | Published users.token_version |
| V8 | Local requirement verification metadata, formerly local V6 |
| V9 | Local enrollment workflow versions, formerly local V7 |
| V10 | Local plain catalog labels, formerly local V8 |
| V11 | Local identity resolution/account security/attendance policy, formerly local V9 |

All authentication paths now use `users.token_version` and the `tokenVersion` JWT claim. Account edits, status changes and password changes/recovery invalidate old sessions. There is one account implementation behind `/api/admin/accounts` and `/api/admin/users`; both require expected versions for writes. The latter is an alias with the reconciled paginated response contract, not an unversioned bypass. `GET /options` aliases `/reference-data`; versioned/reasoned `PUT /{id}/password` performs the same forced-change recovery as `POST /{id}/reset-password`. New accounts accept either the original role/campusIds payload or the upstream multi-grant payload. Username edits are retained.

## Fresh or published-upstream database

Run a clean build once after changing migration filenames. The updated native launcher does this automatically. Fresh databases apply V1–V11; published upstream databases apply V8–V11 normally. There are no default passwords or students in these migrations.

For the persistent Docker setup, use `./mvnw clean spring-boot:run` (PowerShell: `.\mvnw.cmd clean spring-boot:run`) after loading `.env`. A clean build removes obsolete SQL copies from target; it does not remove PostgreSQL records.

## Existing unpublished local V6–V9 database

Do not replace its history files or use a generic Flyway repair. First take a verified PostgreSQL backup and stop other app instances. The application detects that older sequence and refuses automatic startup with an explicit migration message.

After backing up, enable the exact-history bridge in the backend environment:

```sh
export ICI_MIGRATIONS_RECONCILE_LOCAL_HISTORY=true
./mvnw clean spring-boot:run
```

For the persistent startup guide, reload the other `.env` values before starting. Windows users can set `$env:ICI_MIGRATIONS_RECONCILE_LOCAL_HISTORY='true'`. Disable this opt-in after a successful upgrade; it is not required for subsequent starts.

The bridge recognizes only the four exact historical local migration files/checksums bundled under `db/legacy-local`, which are reference files outside Flyway's normal migration location. It locks history, verifies the sequence, applies the two published additions, maps the old local entries to V8–V11, and commits atomically. Existing users, grants, workflow data and append-only audits are retained; legacy token counters are transferred and incremented to end old sessions. The unused legacy auth_version column can remain on an upgraded database without being consulted by authentication. Altered/partial histories are rejected; they need a specific reviewed upgrade rather than an automatic repair.

Only disposable databases were upgraded during verification. No existing user or school database was changed. Tests cover published V7 upgrade, explicit local V9 upgrade, idempotent validation, account/audit retention and refusal of altered history. The previous local working files were backed up at `/tmp/ici-before-reconciliation-20261110.tar.gz`; that is a code backup, not a PostgreSQL data backup.

## Collaborator reconciliation

PR #12's live permission-and-campus policy now guards setup, student, intake, duplicate, requirement, attendance, confirmation and section APIs. An unrelated role grant at another campus cannot expand scope. PR #13's account workflows share the versioned account implementation, including its last-admin transaction lock/guard and multi-grant creation. PR #14's attendance component route uses persistent APIs and configurable campus/term policy instead of fixed dates and React-only records. The local duplicate integration and Pauline's intake/checklist integration remain intact. The frozen archive checksum checks remain enabled; the changing prototype is not required to equal that snapshot.

The attendance rule remains complete mandatory requirements plus at least one Present record inside configured orientation/first-week ranges (or the explicitly shown unconfigured-calendar Present gate). The upstream mock's all-five-days rule is not silently adopted as school policy. If the institution requires all five days, that is a policy change to approve before presentation acceptance.
