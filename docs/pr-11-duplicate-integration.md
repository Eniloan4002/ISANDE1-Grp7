# PR #11 duplicate-screen integration

November 9, 2026. Local changes, not committed or pushed.

Elijah's merged [PR #11](https://github.com/Eniloan4002/ISANDE1-Grp7/pull/11), merge commit `36b05afda2d18f2ae03c2d7fcac22b1a9dee3720`, supplied the React duplicate-review presentation and proposed an API contract. This integration reconciles that work with the locally implemented persistent identity service.

## Delivered behavior

- The production route imports `src/DuplicateRecords.jsx`, matching PR #11's component location. The earlier `enrollment/DuplicateRecords.jsx` path re-exports this single implementation.
- Adapted the comparison highlighting and filter presentation to the backend's application/student identity fields. Highlights mean equal nonblank recorded values; they do not fabricate matching confidence. Search explicitly filters the current page, while status/application filtering and pagination use the server.
- Replaced the PR's mock data layer with a real `src/api/duplicates.js` decision/rescan module. No environment flag can silently enable demo decisions in the production screen.
- Uses `/api/duplicate-cases`, `/api/duplicate-cases/{id}` and `/api/duplicate-cases/{id}/resolve`, with the existing intake scan endpoint.
- Keeps three separate actions: `same_person` links the canonical student, `distinct` clears a false positive, and `reject` rejects the incoming application while preserving history. The PR's ambiguous uppercase `REJECT` action is not accepted.
- Every decision includes its reason and case/application/student versions. Existing campus scope, immutable history, explicit confirmation and backend checks remain in force.

The PR's mock-only statistics, fabricated confidence percentages and development preview were not carried into the authenticated production route. This is a reconciliation of its presentation and component/API locations with the tested workflow, rather than a direct pull over the dirty working copy. Other unpublished enrollment/account work is preserved. A later Git publication must reconcile the upstream `App.jsx`/CSS changes; no merge or push has been performed by this task.

## Team message

> Connected Elijah's duplicate-review screen work to the persistent backend. Matching fields are highlighted, filtering is available, and decisions save with reasons, version checks and audit history. Distinct people, canonical linking and rejecting an incoming duplicate now have separate meanings. Pauline's intake/checklist integration remains in place. This is still local; the team needs the shared revision and a short acceptance test before closing the tickets.

## Jira acceptance

ICI2-13–19 have implementation/automated-QA evidence locally. After sharing the tested revision and performing the manual acceptance checklist in the [QA report](qa/gap-closure/review-report.md), these can move to Done if the original ticket acceptance criteria match that delivered scope. Preserve collaborator assignments and record integration contributions in comments. This PR's title references ICI2-14, but duplicate detection/review belongs with ICI2-13; ICI2-14 remains the enrollment queue/intake feature.

ICI2-9 can be completed for account creation/directory/status/access editing and administrator-mediated password recovery if those satisfy its criteria. Email reset delivery and broader ICI2-7 module permissions are not supplied. ICI2-49 still requires each teammate's role-account setup/login verification; a disposable automated fixture does not prove their own persistent accounts exist.

## Verification

75 frontend tests, 15 schema/archive checks, production build and all seven fresh browser journeys passed with zero skipped, flaky or unexpected results. The duplicate browser journey verifies current-page search, equal-field highlighting, canonical linking, reload persistence and decision history; the other journeys cover distinct/reject and enrollment/account regressions. Backend source was unchanged; its preceding 59-test PostgreSQL suite remains the backend baseline. New browser execution used a fresh disposable database migrated through V9. See [structured results](qa/pr-11-integration/results.json) and [tested frontend hashes](qa/pr-11-integration/source-sha256.json).
