-- Preserve the existing administrator access to setup and student management.
INSERT INTO role_permissions(role_code, permission_code)
VALUES ('ROLE_ADMIN', 'enrollment.manage')
ON CONFLICT DO NOTHING;
