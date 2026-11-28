# ICI2-26/27/28: grade corrections and incomplete cases

This adds persistent Spring Boot services to the existing teacher-grade contracts and replaces Pauline's INC mock adapter with authenticated HTTP calls. No fabricated accounts or grades are seeded.

## Permissions and concurrency

Registrar mutations require `grades.review` at the grade's campus. Teacher mutations require `grades.encode` at that same campus **and** the current class teacher assignment. A role at an unrelated campus cannot extend access. Reads allow either scoped reviewer or assigned teacher; out-of-scope identifiers return 404. Actors are always derived from the current authenticated identity. Client `actor` and `role` values are ignored.

Each mutation takes the offering lock before its grade row, then the case or correction row. This matches ICI2-25 teacher writes and avoids a case/grade lock-order inversion. Version mismatches return 409. Direct INC completion respects both legacy class windows and ICI2-25 section deadlines/manual locks; finalized grades are locked even if no manual policy exists. Formal approved corrections can amend a locked grade **without unlocking the period**.

## Formal correction requests (26/27)

- `GET /api/academic/grade-corrections?status=pending&page=0&size=25`: authorized paginated queue. Status accepts pending/applied/rejected/all, size 1–100.
- `POST /api/academic/grade-corrections`: assigned teacher multipart request, HTTP 201. Part `request` has Content-Type application/json and `{gradeId, gradeVersion, proposedValue, justification}`; part `evidence` contains the file. A submitted/finalized grade must be locked. There is at most one pending request per grade.
- `GET /api/academic/grade-corrections/{id}`: request snapshot, version, original value/mark, proposed numeric value, recorded requester/reviewer/reason, current scoped grade and protected evidence URL.
- `GET /api/academic/grade-corrections/{id}/evidence`: scoped attachment download with `nosniff` and `no-store`. No arbitrary path/key is accepted.
- `POST /api/academic/grade-corrections/{id}/review`: Registrar `{version, gradeVersion, decision: "approve"|"reject", reason}`.

Evidence is required, 1–1,000,000 bytes, with PDF/PNG/JPEG media type matching its magic signature. The original filename must be a plain filename, at most 200 characters, without path separators or control characters. Signature checks do not claim antivirus/content scanning; files are delivered only as attachments, never inline. Content is stored transactionally in PostgreSQL bytea under generated storage keys. SHA-256 and file metadata are recorded. Evidence audit rows explicitly exclude binary content. The servlet rejects files above 1,000,000 bytes and entire multipart requests above 2 MB with 413.

Approval requires a different authorized reviewer from the requesting teacher, compares the immutable original grade snapshot and version, applies the new numeric value once, preserves submitted/finalized state, increments the grade version, records review attribution/reason and closes unresolved INC cases in the **same transaction**. Terminal status is `applied`. Rejection records `rejected` and leaves the grade/case unchanged. Repeated decisions and stale approvals cannot overwrite a newer grade. Both original teacher and approving Registrar remain attributed in records/audits.

## Persistent incomplete cases (28)

V14 backfills open cases for existing submitted/finalized INC grades, and a grade trigger creates a case when a draft INC is submitted. Draft INC does not create a case. Only one unresolved case per grade exists. Existing DB case states remain open/following_up/resolved; outward UI statuses preserve Pauline's vocabulary:

- pending_teacher_submission: no completion proposal yet;
- registrar_review: proposal available in an open period;
- grade_correction: proposal available in a locked/finalized period;
- completed: resolved.

Endpoints:

- `GET /api/academic/incomplete-cases?page=0&size=100&search=...&status=...&academicYearId=&gradingPeriodId=&sectionId=&subjectId=&teacherId=`: scoped page plus total and authorized display-filter options. All filters are optional, combine with AND, and are bound SQL parameters applied on top of the campus/teacher scope, so an id from another campus simply matches nothing. `total` and paging reflect the filters; the returned `filters` option lists remain the unfiltered authorized set. Non-numeric ids return 400; `size` is still capped at 100.
- `GET /api/academic/incomplete-cases/{id}`: full case and follow-up history.
- `POST /api/academic/incomplete-cases/{id}/follow-ups`: `{version,note,method}`. Method: Email, Phone, In person, System note. This logs a contact; it sends no message.
- `POST /api/academic/incomplete-cases/{id}/proposals`: assigned teacher `{version,gradeVersion,grade,note}`. Saves a proposal without changing marks, including when a locked period requires subsequent formal correction/evidence.
- `POST /api/academic/incomplete-cases/{id}/complete`: Registrar `{version,gradeVersion,grade?,note}`. Optional grade uses the saved proposal only if its captured grade version still matches. An open period is required; pending formal corrections block direct completion. Finalized or locked periods return 403 and require formal approval instead.

Numeric values are 0–100 with at most two decimals. Notes/justification are required, nonblank and at most 2,000 characters. Follow-up mutations on completed cases return 409. The response carries `version` and `gradeVersion`, current mark, proposal, `periodLocked` and per-record `canPropose`/`canComplete` flags derived from actual campus grants. These flags support mixed-role accounts without making global role selection an authorization mechanism.

## Frontend handoff

`incompleteGradesApi.js` preserves existing function names and normalizes all case IDs to strings. It sends case/grade versions but no caller identity, fetches every authorized page rather than silently hiding results after page 1, and gives an explicit error if more than 10,000 cases would be loaded into the existing client-filter workspace. Academic year/period/grade/section/subject/teacher/status display filtering remains in the existing UI over those loaded authorized records. A server-filter/pagination UI is the next scale improvement if scopes exceed that bound.

`IncompleteGrades.jsx` retains Pauline's layout, replaces MOCK DATA labels/notices with persisted-record language, passes versions, requires resolution notes and uses per-case capability flags. Stale-save errors retain draft input for correction/reload. The separate formal correction request/review screens can consume the documented APIs; this backend task does not invent those screens.

V14 is additive; published V1–V13 are unchanged. Test accounts and grades exist only in disposable PostgreSQL integration fixtures. Shared Supabase has not been migrated/reset by this work.
