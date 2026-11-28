-- ICI2-91: academic setup edits global data (subjects, academic years, terms, grading periods), so it gets its own
-- permission instead of the campus-scoped enrollment.manage. Default holder is administrators only; a school can grant
-- setup.manage to another role through role_permissions. Reading setup data stays on enrollment.manage.
INSERT INTO permissions(code,description) VALUES ('setup.manage','Edit global academic setup (subjects, years, terms, grading periods)')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions(role_code,permission_code) VALUES ('ROLE_ADMIN','setup.manage')
ON CONFLICT DO NOTHING;
