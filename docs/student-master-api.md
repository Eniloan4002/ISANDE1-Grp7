# ICI2-12 — Student master record API

## Scope delivered

Persistent student identities with LRN, names, date of birth, sex, contact information and structured address; previous-school name/ID/address; household income bracket; normalized parent/guardian records; campus, program/strand, track and QVR/ESC/ALS/REGULAR voucher classification. Includes paginated search, optimistic updates, soft archiving, metadata and transactional audit history.

Students represent permanent identities. The program, campus and voucher on the master profile are current profile/intake preferences; term-specific enrollment and historical assignments remain in applications/enrollments. Creating a student does not confirm enrollment, create an application, reserve a section or issue academic records.

## Endpoints

| Method | Path | Result |
| --- | --- | --- |
| POST | /api/students | Create profile and guardians atomically; 201 with Location header |
| GET | /api/students/{id} | Profile with address object and guardians; 200 |
| GET | /api/students | Paginated directory; 200 |
| PUT | /api/students/{id} | Full profile replacement using current version; 200 |
| DELETE | /api/students/{id}?version=N | Archive by setting active=false; identity/history retained; 204 |
| GET | /api/students/reference-data | Authorized campuses, program catalog and voucher catalog |

All requests require the Bearer token from /api/auth/login. Missing/invalid authentication returns 401. Access requires a role with `enrollment.manage` **at the requested student's campus**. The seeded Administrator and Registrar roles have that permission. Grants are read from the verified current identity, not a caller-provided actor header. Out-of-scope record IDs return 404; an explicit unauthorized campus filter or create returns 403. The shared `AccessPolicy` checks current role-permission mappings and campus grants.

## Create request example

```json
{
  "campusId": 1,
  "programId": 1,
  "voucherCode": "QVR",
  "track": "ACADEMIC",
  "lrn": "123456789012",
  "firstName": "Ana",
  "middleName": "Reyes",
  "lastName": "Santos",
  "birthDate": "2009-01-01",
  "phone": "09171234567",
  "email": "ana@example.invalid",
  "address": {"street":"Purok 1","barangay":"Tibanga","city":"Iligan","province":"Lanao del Norte","postalCode":"9200"},
  "previousSchoolName": "Example Junior High School",
  "previousSchoolId": "EXAMPLE-001",
  "previousSchoolAddress": "Iligan City",
  "householdIncomeBracket": "Institution-defined bracket",
  "guardians": [{"fullName":"Maria Santos","relationship":"mother","phone":"09171234568","primaryContact":true}],
  "active": true
}
```

Use IDs from reference-data rather than assuming seed IDs. A nullable LRN must be omitted or null, never an empty string; when present it must have exactly 12 digits. It is unique across permanent student identities. Exact LRN conflicts return a generic 409 without exposing another campus's record. Fuzzy matching, duplicate queues and merging belong to ICI2-13.

Names, birthDate, campusId, programId, voucherCode, track, address, guardians and active are required. Names cannot be blank; birthDate must be in the past; email must be valid when supplied. Optional blank text is normalized to null. Track must match the catalog: academic SHS codes use ACADEMIC, TVL_ codes use TVL, and TVET programs use TVET. Catalog modeling can be extended with the program-configuration task.

Guardian relationship records represent mother, father or other guardian contacts; up to 10 contacts and at most one primary contact are permitted. An empty guardian list is accepted for incomplete intake. Household income is a descriptive school-defined bracket, not an invented set of monetary thresholds. Optional previous-school and family data can be completed later.

Student numbers are generated immutable STU-UUID values. Updates cannot change identity IDs/numbers or move a student to another campus; campus transfer is a separate controlled workflow. PUT requires the current version and all required fields. Guardians are replaced as a complete set in the same transaction: their row IDs are internal and may change on replacement. Omitted optional fields clear those values. A stale update/archive returns 409 without modifying profile, guardians or audit records. Unrecognized IDs return 404. Referenced catalog/validation failures return 400.

## Directory and response contract

GET /api/students?search=Santos&campusId=1&programId=1&voucherCode=QVR&active=true&page=0&size=25

Search matches full name, LRN or generated student number, with SQL wildcard characters treated literally. Filters are optional; omitted active includes archived identities. Pagination is zero-based, size 1–100 (default 25), with stable last-name/first-name/ID sorting. Response: items, total, page, size. The directory deliberately omits guardian, birth-date and address data; request authorized detail when needed.

Responses use database field names (first_name, campus_id, previous_school_name, household_income_bracket, is_primary), consistent with existing setup APIs. POST/PUT inputs use camelCase. Detail includes the current version and created_at/created_by/updated_at/updated_by; all writes and guardian replacements are attributed to the authenticated staff member in append-only audits.

## Migration and integration handoff

V5 adds master-profile fields, version/active state, name validation and directory indexes without rewriting V1–V4. Existing student identities remain intact. Historical rows with no verified campus are deliberately hidden from this API until their campus is assigned through a controlled migration; the system does not guess ownership. No archive/demo/frontend files are changed.

- Future role-specific workflows must apply their own resource and assignment checks; do not broaden student reads to every authenticated role.
- ICI2-13: duplicate detection and resolution must operate on applicant records before issuing/merging a permanent identity. Do not weaken the permanent-student LRN uniqueness constraint.
- ICI2-14: connect intake/directory UI to this contract; use reference-data and versions, and map camelCase requests to snake_case responses.

The backend suite runs against PostgreSQL 17.6: 5 student tests plus 12 foundation/authentication regressions passed. No full enrollment intake UI or enrollment confirmation is claimed by this task.
