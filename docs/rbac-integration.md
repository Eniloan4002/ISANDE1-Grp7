# RBAC integration for future modules

ICI2-7 secures the APIs that exist today and supplies the pattern for future controllers. The database is the source of role codes: `roles` and `user_role_grants` are created in V1 and the seven role rows are seeded in V2. Do not create a second backend role registry.

## Current request flow

1. `POST /api/auth/login` verifies credentials and issues a signed JWT. It is public; it must remain outside method-level authentication rules.
2. The JWT filter verifies each protected request, rejects inactive accounts and revoked tokens, reloads current campus-role grants from the database, and creates Spring authorities from their `role_code` values. A token's old role claims do not grant access after a database grant changes.
3. Spring Security requires authentication for all requests except login and `/error`. `/api/auth/me` and `/api/auth/logout` explicitly require an authenticated caller. Setup and Student controllers currently require `hasAnyRole('ADMIN','REGISTRAR')`.
4. Student service queries additionally limit data and writes to campuses on the caller's Admin or Registrar grants. A role check alone cannot enforce campus, adviser-section, class, or student ownership.

The V2 `role_permissions` rows are seed metadata, not active Spring authorities. The filter currently grants only `ROLE_*` authorities from live user grants. Do not switch endpoints to `hasAuthority('grades.encode')`, for example, until the team has approved that permission's exact actions and scope. See [ici2-7-decisions.md](ici2-7-decisions.md).

## Adding a module

- Put a role rule on every new business controller or method. For a controller whose operations all have the same approved role access, follow the existing `@PreAuthorize("hasAnyRole('ADMIN','REGISTRAR')")` pattern. `hasRole('TEACHER')` matches the database code `ROLE_TEACHER`; pass the name without `ROLE_` to `hasRole`.
- Keep a role rule separate from resource checks. In the service, use the authenticated `StaffIdentity.userId()` and live `grants()` to constrain queries by campus and, where the approved workflow requires it, assigned section or class. Include those constraints in the final SQL write, inside the transaction. Never use an actor or campus ID supplied by the client as proof of authorization.
- Define the resource rule with the team before implementing it. The existing schema has `sections.adviser_id`, `class_offerings.teacher_id`, and `grading_windows`, but their presence does not settle withdrawal endorsement, grade-window semantics, or access to sensitive student fields.
- Add a React route only when its page exists. Register its `allowedRoles` in `routeConfig.js`; `RoleGate` controls navigation and direct URL rendering. The authenticated profile supplies `ROLE_*` grant codes, which `rolesFromClaims` maps to UI labels. The backend must independently authorize every API call. A role with no page can be recognized without receiving a placeholder or unrelated route.
- Test each endpoint with no token (401), an allowed role, and a valid token with a denied role (403). For resource-scoped APIs, test another campus or assignment and verify that denied writes leave data unchanged. Also test that a grant change takes effect with an already-issued JWT. Keep the seven-role matrix tests for existing Setup and Student APIs as examples.

Do not grant access to a new module merely because V2 contains a role or permission row. The missing module APIs and unresolved policy choices remain outside this foundation.
