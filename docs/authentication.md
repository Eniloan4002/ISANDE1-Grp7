# Authentication and JWT (ICI2-6)

## Delivered behavior

- POST /api/auth/login validates a username/password against BCrypt hashes (cost 12). Invalid credentials and inactive accounts receive the same 401 response.
- Successful login returns accessToken, tokenType (Bearer), expiresAt and the staff profile. Signed HS256 JWTs include sub/userId, unique jti, issuer, issued/expiry times, roles and explicit per-campus role grants.
- GET /api/auth/me verifies the token and returns the current staff profile. All business APIs require a valid token; academic setup currently permits admin/registrar roles.
- POST /api/auth/logout durably revokes the current token by jti in PostgreSQL. Revocation survives application restarts when the database is retained. Other independent sign-ins stay valid.
- Every protected request verifies the signature, issuer and expiry, rejects revoked tokens and outdated account token versions, checks the account is still active and reloads current campus-role grants. Deactivating an account or resetting its password invalidates all previously issued tokens permanently, including after reactivation. Removed permissions do not stay effective until token expiry.
- No server login sessions or authentication cookies are created. Bearer headers are explicit, so CSRF cookie authentication is not used. Login/token responses are not cached.
- React provides a real staff sign-in form, invalid-credential feedback, protected setup route, session restoration, expired-session handling and sign-out. The token is kept in sessionStorage for that browser tab and removed on confirmed logout or a 401. Passwords are never stored in browser storage.

## Configuration

JWT_SECRET is required for the packaged backend: generate at least 32 random bytes using openssl rand -base64 32. No production default is supplied. JWT_TTL_MINUTES defaults to 15 and accepts 1–60. All instances must use the same signing key and PostgreSQL revocation data. Keep keys in deployment secrets; use HTTPS for remote deployments.

The initial administrator is opt-in: set BOOTSTRAP_ADMIN_ENABLED=true and provide BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD. Passwords must be at least 12 characters and at most 72 UTF-8 bytes. Startup hashes the password and grants ROLE_ADMIN across active campuses. Existing usernames are never reset. Disable bootstrap and remove the bootstrap password from the environment after first provisioning. There is no default account or public registration endpoint.

The disposable native-PostgreSQL developer launcher lives on the test classpath and uses a test signing key. Its database is erased on shutdown; use it only for development/testing. It is excluded from the production JAR.

## API examples

Login JSON: {"username":"your-staff-name","password":"your-password"}

For subsequent calls, send Authorization: Bearer <accessToken>. Logout is POST with that same header and returns 204; token reuse then returns 401.

A missing/invalid/expired token returns 401. A signed-in user without the required setup role receives 403. Validation returns 400 with field errors. The API does not trust caller-supplied actor IDs: setup audit actors come from the verified staff identity in the same database transaction.

## Task boundary

This completes authentication/token issuance and invalidation. The existing setup and student APIs use the role-permission and campus policy described in rbac-permissions.md. User-account management is provided by the administrator panel (ICI2-9). Authorization for future module workflows, self-service password recovery, refresh tokens and rate-limit infrastructure remain separate work. Existing static mockups retain their original demo-only role selector inside the archive.

Sources: [Spring Security stateless sessions](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html), [JJWT signing and verification](https://github.com/jwtk/jjwt).

## Administrator account management (ICI2-9)

`/administration/users` manages personnel accounts. The API uses `GET /api/admin/users` and `/options`, `GET /api/admin/users/{id}`, `POST /api/admin/users`, `PUT /api/admin/users/{id}`, `PUT /api/admin/users/{id}/status`, and `PUT /api/admin/users/{id}/password`. Account details contain username, email, fullName and role/campus grants; create also requires an initial password. Status uses `active`, and password reset uses `password`. Password hashes are never returned or recorded in audit entries.

All existing and requested account grants must be within the caller's active `users.manage` campus scope. Accounts with no grants or grants outside that scope are omitted from lists and cannot be managed. Inactive campus grants remain outside the scope until the campus is active. Changes take a transaction lock, recheck authorization, and preserve an active administrator at each affected campus. Administrators cannot deactivate themselves or remove their own administrator grants. Initial and reset passwords must be communicated outside the application.
