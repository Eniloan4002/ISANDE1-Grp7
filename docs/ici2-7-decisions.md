# ICI2-7 authorization decisions pending

The RBAC foundation keeps the existing Auth, Setup, and Student API roles: Setup and Student actions require `ROLE_ADMIN` or `ROLE_REGISTRAR`; Student data is limited to campuses granted for that role. Authentication and logout remain available to any authenticated staff account. These rules do not define permissions for unbuilt workflows.

The team still needs to decide:

- Whether Admin has institution-wide access to operational data or only access at explicitly granted campuses. The current student API uses explicit campus grants; setup records have no campus field.
- Whether academic setup should be limited to particular staff or campuses when institution-wide subjects and calendar entries are edited.
- Which advisory data an Adviser may read, and whether withdrawal endorsement is an explicit exception to the stated read-only Adviser role.
- Which student fields, notes, reports, and exports Guidance may access, and whether those records require campus or assigned-case limits.
- Whether Cashiering may record withdrawal clearance despite its read-only reference role, and which fields its planned roster export may include.
- How a missing grading window, null deadline, period dates, and manual lock determine whether teachers may submit grades. Assignment and window enforcement belongs with the future grade APIs.

No new API or React route access should be inferred from a seeded role or permission row before these decisions are made.
