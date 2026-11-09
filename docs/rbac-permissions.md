# Role and campus authorization

The backend reloads active staff grants on each authenticated request. `AccessPolicy` reads the current `role_permissions` mappings and returns only active campuses where the same role grant carries the requested permission. The API remains authoritative; frontend route gates are navigation aids. Disabling an account, disabling a campus, removing a grant, or removing a role permission takes effect on the next request.

| Role | Seeded permissions | Existing API access |
| --- | --- | --- |
| Administrator | users.manage, enrollment.manage | Global academic setup and student management in granted campuses |
| Registrar | enrollment.manage, grades.review, reports.read | Global academic setup and student management in granted campuses |
| Principal | reports.read | No role-specific production API yet |
| Adviser | advisory.read | No role-specific production API yet |
| Teacher | grades.encode | No role-specific production API yet |
| Guidance | guidance.manage | No role-specific production API yet |
| Cashiering reference | cashier.read | No role-specific production API yet |

V6 adds `enrollment.manage` to administrators to preserve their existing access. Other permission mappings were already seeded in V2. Setup data is global in the current schema, so anyone with `enrollment.manage` at any active campus can manage it globally. Student reads and writes are restricted to campuses where the same grant provides `enrollment.manage`. An unrelated role at another campus cannot expand this scope.

Permissions are a prerequisite for future workflows, not a substitute for checking the resource itself. Adviser access must also check the assigned section; teacher access must check subject assignment, membership, and grading window. Those APIs, other role-specific workflows, and account administration are not implemented by this change. The Principal and Cashiering navigation entries are placeholders. Client role information is not a security boundary and may remain stale until the session profile is refreshed.
