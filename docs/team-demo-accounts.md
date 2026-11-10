# Persistent team/demo accounts

Run this once after the Supabase-backed backend has started. It creates missing accounts and verifies their actual login and grants. Existing passwords/grants are never overwritten.

From the repository root:

```sh
python3 scripts/provision-review-accounts.py --base-url http://127.0.0.1:8081 --named-team --shared-new-password
```

Enter existing administrator username `linus_admin` and its current APP password, then privately choose and confirm a password for missing demo accounts. Use at least 12 characters and at most 72 UTF-8 bytes; the nine-character password in the historical screenshot does not meet current requirements. Nothing is printed/saved for passwords. A shared password is an explicit demo convenience; real personnel accounts should use individual passwords.

| Username | Role | Campus scope |
| --- | --- | --- |
| linus_admin | Administrator | Iligan, Kapatagan, Bugo |
| anton_admin | Administrator | Iligan, Kapatagan, Bugo |
| pau_admin | Administrator | Iligan, Kapatagan, Bugo |
| eli_admin | Administrator | Iligan, Kapatagan, Bugo |
| registrar_demo | Registrar | Iligan |
| principal_demo | Principal | Iligan, Kapatagan, Bugo |
| adviser_demo | Teacher Adviser | Iligan |
| teacher_demo | Subject Teacher | Iligan |
| guidance_demo | Guidance Staff | Iligan |
| cashier_demo | Cashiering Reference | Iligan |

Scopes preserve the earlier documented preview setup. An administrator must already control all three campuses to provision this complete set. Existing `linus_admin` retains its current password; choose the same valid password for missing accounts only if you intend that demo convention. Existing other accounts prompt for their current password to verify them. No account is silently reset.

When the script prints all ten verified, accounts are stored in the database behind the current backend. With `run-supabase.py`, they persist in Supabase across restarts. The script logs out its verification sessions without ending unrelated browser sessions. On a partial failure, review the message and rerun; completed accounts are preserved. Incorrect/inactive/out-of-scope grants require administrator review in User Accounts.

To only verify an existing set:

```sh
python3 scripts/provision-review-accounts.py --base-url http://127.0.0.1:8081 --named-team --verify-only
```

Successful sign-in does not implement unfinished academic/guidance/reporting modules. Registrar enrollment workflows ICI2-13–19 have their separate [demo/manual checklist](demo-13-19-checklist.md).
