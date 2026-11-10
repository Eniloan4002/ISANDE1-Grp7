# ICI2-13–19 demo setup and manual pass

Use the integrated version from GitHub main. For the shared Supabase database, follow [shared startup](run-with-supabase.md) and reuse the existing SPRINT DEMO 65633D term; the temporary setup below is a separate disposable option. Use test data and keep both app terminals running.

## Start without Docker

Backend terminal:

```sh
cd /Users/ladi/Desktop/ISANDE1-Grp7/ICILocal2.0/backend
python3 run-review.py
```

Choose and confirm a password of at least 12 characters. Wait for startup with no subsequent Application run failed message. Frontend terminal:

```sh
cd /Users/ladi/Desktop/ISANDE1-Grp7/ICILocal2.0/frontend
npm ci
npm run dev -- --port 5173 --strictPort
```

Open http://127.0.0.1:5173/sign-in. Administrator: `linus_admin`, password: the one you chose. A temporary database loses its accounts/records when stopped. For persistence, use the [complete startup guide](run-complete-app.md).

## Prepare demo records

In a third terminal, from the repository root:

```sh
python3 scripts/prepare-demo.py
```

The tool prompts privately for your administrator password and a new demo Registrar password. It creates a separate demo academic year/term, two Grade 11 STEM sections (capacities 1 and 2), a verified Registrar login, and one existing student to use for duplicate/returnee demonstrations. It prints their identifiers, not passwords. Nothing is deleted or overwritten. All records are created through real APIs on localhost. Use the printed campus, academic year/term and section names consistently. Re-running creates another isolated demo set, so normally run it once per temporary session. A failed preparation can leave completed creates; review the message before retrying.

Sign out of the administrator and sign in as the printed demo Registrar. Alternatively, create the academic year/term and staff account manually in Academic setup/User Accounts. Every intake needs a valid term; an empty fresh database has no pre-created term.

## Manual pass before Done

| Ticket | Demonstrate | Pass condition |
| --- | --- | --- |
| ICI2-14 | Capture a new walk-in applicant in the printed campus/term, Academic/STEM/Grade 11. Include guardian details, submit, find in queue, open details and Review and pre-enlist. Reload. | Fields and guardian data persist; status is Pre-enlisted. |
| ICI2-13 | Capture a continuing/returnee applicant using the printed existing student's exact name/DOB/LRN. Open Duplicate Records, compare highlighted fields, choose Same person, enter a verification reason and confirm. Reload. | The case/history persists, the existing student identity is reused, and explicit pre-enlistment succeeds. |
| ICI2-13 | For a separate fuzzy-name/DOB match with no shared permanent LRN, choose Distinct people with a reason. Separately capture an exact duplicate and reject the incoming application. | Distinct clears that suspected match; rejecting preserves the canonical record and rejected application's history. An exact shared LRN cannot be declared distinct. |
| ICI2-14 | Open the linked returning applicant and verify its student number. Use Link existing student for an older/student case that was not automatically matched. | Original master number remains; no second master is manufactured. Wrong identity or same-term duplicate enrollment is blocked. |
| ICI2-15 | In Requirements, change a required document's status and record a note; reload and inspect history. | State/note persist with staff attribution/time. A stale second-tab save returns a conflict. |
| ICI2-16 | Update each mandatory checklist item to Submitted and observe its summary. | Summary becomes Complete only when all mandatory items are submitted, and remains correct after reload. |
| ICI2-17 | Try confirmation with missing documents or no Present attendance. For the demo, configure clearly labelled fictional orientation/first-week ranges for the selected campus/term; save Present within a range, then confirm. | Missing prerequisites and out-of-range Present dates are blocked. Eligible enrollment confirms and remains confirmed after reload. |
| ICI2-18 | Inspect the prepared section capacities. Assign one eligible student to section A, then try assigning another confirmed student to A. | A shows 1/1 and rejects the second assignment. |
| ICI2-19 | Move the first confirmed student from A to B. Reload. Then assign the second student to A. | Both assignments persist and counts update; unconfirmed, incompatible or full-section assignments are blocked. |

For the dates, an illustrative **demo-only** 2026 schedule is orientation July 20 and first-week August 3–7. Label the approval reason as fictional test data; these are not approved ICI dates. July 25 falls in the gap and should be rejected. Actual campus dates must be supplied by staff. Confirmation currently requires at least one qualifying Present record, not all five days; agree on that criterion before the demo.

After each save, reload the page. Use another-campus account for a protected read/write attempt: it must not expose or alter the record. Save pass/fail, the tested revision, and a screenshot/short recording in the Jira ticket. Mark a ticket Done after its acceptance criteria and manual checks pass on the revision your team will actually present. ICI2-49 additionally needs each teammate's own role-account login verification.
