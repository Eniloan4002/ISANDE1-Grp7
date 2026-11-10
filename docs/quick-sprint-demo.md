# Quick enrollment sprint review

The demo seeder creates a separate `SPRINT DEMO <tag>` term in Iligan, STEM, Grade 11, plus two labelled sections. Records and dates are fictional. Existing records/accounts are preserved. Use `registrar_demo` and its chosen demo password.

| Applicant | Prepared state | Show the professor |
| --- | --- | --- |
| Carlo Bautista | Submitted walk-in | Queue → open details → Review and pre-enlist |
| Maria Santos | Pre-enlisted, documents pending | Requirements checklist → submit/verify a document with a note → reload/history |
| Ana Garcia | Mandatory documents complete, no attendance | Confirmation blocked until attendance is recorded |
| Miguel Dela Cruz | Documents complete and qualifying Present recorded | Attendance → Confirm enrollment → section assignment |
| Sofia Reyes | Enrolled and assigned to section A | Section A is full, 1/1 |
| Daniel Ramos | Enrolled, unassigned | Section A unavailable/full → assign to section B → reload |
| Paolo Mendoza | Continuing applicant matching an existing master | Duplicate Records → Same person → reason → confirm → reuse original student number and pre-enlist |
| Second Sofia Reyes application | Exact incoming duplicate | Reject incoming duplicate → preserve the original enrolled Sofia and audit trail |
| Elenaa Salazar | Fuzzy match against existing Elena Salazar | Compare identities → Distinct people → reason → persisted decision history |

## Five-minute path

1. Sign in as Registrar and filter Enrollment Queue by the printed demo term.
2. Open Maria and demonstrate one credential update/note and its saved history.
3. Open Miguel → Attendance and confirmation → confirm his enrollment.
4. Open Daniel in Enrolled & Assigned; show full A and assign him to B. Reload.
5. Open Paolo's duplicate case, explain canonical identity linking, then save with a reason and inspect history.

Use the second Sofia and Elenaa cases if the professor asks about duplicate rejection or false positives. Carlo is available to demonstrate initial review; a fresh walk-in can also be captured live through Intake.

The fictional date configuration is orientation July 20 and first-week August 3–7 of the demo year. Record Present on August 3; July 25 is outside both ranges and should be blocked. These are test dates, not an approved campus calendar. Current confirmation policy is at least one qualifying Present plus mandatory documents, not all five days.

The local `.demo-sprint-seed.json` contains the actual IDs/links/term for the last set (no passwords, Git-ignored). `scripts/seed-sprint-demo.py` can be run interactively if a new environment needs setup. A complete existing set is detected and left untouched on rerun; it will not undo actions performed during the demo. Partial runs preserve completed creates for review rather than deleting anything.

These are real API/database flows. Academic, grading, principal and guidance/reporting workflows outside enrollment remain separate scope.
