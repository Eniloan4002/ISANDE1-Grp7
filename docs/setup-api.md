# Academic configuration (NEW SETUP)

The /setup React route manages four persisted resources. Access requires an authenticated admin or registrar. Vite proxies /api to localhost:8081; set ICI_API_URL to override during development. Deployed hosting should route /api to the backend on the same origin.

| Resource | Collection path | Required create fields |
| --- | --- | --- |
| Subjects | /api/setup/subjects | code, name, units, active |
| Academic years | /api/setup/academic-years | label, startsOn, endsOn |
| Terms | /api/setup/terms | academicYearId, code, name, startsOn, endsOn |
| Grading periods | /api/setup/grading-periods | termId, code, sequence, startsOn, endsOn |

Each resource supports GET collection, GET /{id}, POST collection, PUT /{id} and DELETE /{id}?version=N. POST returns 201, update 200, delete 204, unknown ID 404. Terms accept an optional academicYearId query filter; periods accept termId.

Use YYYY-MM-DD dates. Response fields follow database naming (starts_on, academic_year_id) and include version plus audit metadata. Updates require the current version. A stale update or deletion returns 409 and never overwrites another staff member's work.

Subject codes, year labels and scoped term/period codes reject case-insensitive/trimmed duplicates. Period sequence is unique per term. Required fields, numeric limits and dates are checked on the server and in PostgreSQL. Terms must fit their academic year; periods must fit their term. Shrinking a parent's dates cannot strand children. Terms/periods cannot be moved to another parent. Referenced configuration cannot be deleted: foreign keys preserve assignments, windows and grades. Dates may overlap: ICI's policy on overlapping terms/periods is not yet specified, so this is intentionally not forbidden.

Setup screens include create/edit forms, parent selectors, tables, confirmation dialogs, loading/empty/error states and success notifications. Records persist across page refreshes in PostgreSQL. The subject active flag allows retaining historical catalog entries.

No student/grade CRUD, honor eligibility computation or teacher assignment API is included in this setup task.
