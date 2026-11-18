# Official academic documents API — ICI2-35

Backend implementation for registrar review. No screens or styles are changed. It uses the existing permanent student, enrollment, credential, grade, correction, withdrawal and document-request records.

## Institutional approval boundary

No national Form 137/TOR layout, signature, seal, SO format, or registrar approval is invented or seeded. The renderer produces a structured printable academic record containing the recorded subjects and period marks. The school must approve that layout and its applicability before official use; this implementation does **not** establish DepEd compliance by itself. Policies contain an externally verifiable template reference and approval reference. Recording those references is an authenticated registrar action, not automatic proof of external approval. A new policy or any edit remains unapproved until that action. Preview is always marked **DRAFT - NOT FOR OFFICIAL RELEASE**.

The UI and school acceptance of the actual document layout remain separate. A fixed national-form background, certified final-rating computation, digital signatures/seals, and legacy grade import are not implemented here. No unapproved final average is calculated. Output explicitly lists the selected enrollment terms, so a partial selection never claims to be the entire student record.

## Authorization

Every endpoint requires `documents.manage`, granted narrowly to Registrar and Administrator. The permission and campus must belong to the same current grant. A registrar can only use enrollment records and policies at that campus; every selected enrollment must match the selected student and policy program. A generation retains its original campus and download authorization uses the caller's current campus grants. Teacher, cashier and general report access do not grant academic document issuance or download rights.

Base: `/api/official-documents`, existing Bearer authentication.

## Configuration and Special Orders

- `GET /policies`: authorized policies.
- `POST /policies`: create a draft policy, returns 201.
- `PUT /policies/{id}`: update draft/configuration, requiring `version`; invalidates prior approval.
- `POST /policies/{id}/approve`: `{ "version": 0, "approvalReference": "Registrar sign-off record/reference" }`. Records authenticated actor and time and increments version. Reapproval without an edit is rejected.

Policy body:

```json
{
  "campusId": 1,
  "programId": 1,
  "documentType": "FORM137",
  "institutionName": "Institution-provided name",
  "institutionAddress": "Institution-provided address",
  "signatories": "Approved names and titles, one per line",
  "templateReference": "Registrar-reviewed layout/version reference",
  "soRequired": true,
  "soPrefix": "",
  "soMaxLength": 120
}
```

Type is `FORM137` or `TOR`. One policy per campus/program/type; scope cannot be changed in place. The registrar must confirm applicability before approval. SO prefix may be empty, and maximum length must be 1–120. These are configurable approved format constraints; no national SO-number regular expression is assumed. Policy fields are bounded printable text; template reference is not fetched as a URL.

- `GET /special-orders/{enrollmentId}`: returns stored SO or `{enrollment_id, version:-1}` when absent.
- `PUT /special-orders/{enrollmentId}`: `{ "version": -1, "number": "actual recorded number", "issuedOn": "2026-01-01", "issuingAuthority": "Issuing authority" }`. Version -1 creates; subsequent updates require the returned nonnegative version. The date cannot be in the future (UTC). SO data is explicitly stored against the student/program/term enrollment record. The registrar must confirm this granularity is correct for the institution before acceptance; unsupported group-level SO assignment is not presumed.

Policy and SO edits are audited with server-derived actor and timestamp. Stale edits return 409. Required SO information is checked against the approved prefix/length at verification and issuance. Documents for a policy with `soRequired:false` are not blocked merely because SO information is absent.

## Verification, draft and issuance

`POST /verify`, `POST /preview`, and `POST /issue` accept:

```json
{
  "studentId": 123,
  "policyId": 4,
  "policyVersion": 1,
  "enrollmentIds": [101, 102],
  "requestKey": "5eb1a35b-2fca-4706-a1c8-88c2bbcc6e14"
}
```

Select 1–50 distinct enrollments. At most 1,000 grade cells per term and 2,000 across the selection; larger selections return 413 rather than truncate. `requestKey` is required only for issuance. It is an idempotency key: repeating the same request returns the same generation; reuse for another selection returns 409.

Verification returns the student, selected terms, recorded marks, policy, SO information, `eligible`, and understandable `reasons`. It is a consistent read-only snapshot, not a reservation. Preview downloads a draft PDF, including blockers. It never inserts an issuance record, even if the data is eligible.

Official issuance fails closed (409) for:

- Unapproved or stale template/SO policy.
- Provisional enrollment; absent class membership or configured grading periods.
- Any missing, draft, submitted/unfinalized or nonnumeric grade (including INC/NG/DRP). All selected grades must be finalized numeric marks; treatment of other terminal marks requires additional registrar-approved policy.
- Open INC cases or pending/legacy-approved-but-not-applied corrections. Applied corrections are allowed.
- Any unverified mandatory credential, including a missing checklist row.
- Missing/mismatched SO information where required.
- Any unresolved student-wide withdrawal, even outside the selected terms. Other-campus clearance details are not disclosed.

Successful issuance inserts the existing `document_requests` row as issued and an immutable `official_document_generations` row containing the complete data/policy snapshot, PDF, SHA-256, actor/time and UUID. Later grade/configuration edits do not rewrite that issued artifact. The generation audit excludes PDF and snapshot bodies to avoid duplicating sensitive content. Audit and generation rows cannot be mutated/deleted by ordinary application SQL.

- `GET /generations/{uuid}`: issuance metadata and SHA-256.
- `GET /generations/{uuid}/pdf`: exact original bytes, `application/pdf`, `Cache-Control:no-store`, `X-Content-Type-Options:nosniff`.

The 5 MB PDF and 250,000 source-character limits are enforced without truncation. Draft/title and page numbers repeat on every PDF page. Unsupported font glyphs or nonprintable source characters return 422 instead of silently changing an official name or subject. The bundled Liberation Sans font supports the tested Filipino/Latin names; other writing systems require an approved font/layout extension. No PDF/signature content is downloaded from remote services.

## Concurrency and deployment

Issuance uses a short READ COMMITTED transaction and takes a deterministic set of PostgreSQL `EXCLUSIVE MODE NOWAIT` source-table locks **before** reading academic data. This conflicts with existing writes and `SELECT FOR UPDATE` transactions, yielding an immediate retryable 409 instead of waiting in a row/table lock cycle. New writes wait while the bounded PDF/snapshot is created. Ordinary SELECT readers continue. These conservative locks serialize source writes across campuses during issuance, a deliberate correctness/throughput tradeoff for this initial backend. They are not intended for high-volume bulk printing; per-student write coordination can replace them later with dedicated concurrency review. No external I/O occurs while holding locks.

The existing withdrawal-release trigger remains an independent database boundary. The service rechecks the full eligibility snapshot during issuance; a prior successful `/verify` is never trusted. Migrations are additive (V18); no published migrations or shared school database were modified during implementation.
