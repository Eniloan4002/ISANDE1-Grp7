-- Preserve all existing student/application/audit history; no destructive merges.
ALTER TABLE duplicate_cases ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version>=0);
CREATE INDEX duplicate_cases_review ON duplicate_cases(application_id,status);
ALTER TABLE users ADD COLUMN auth_version integer NOT NULL DEFAULT 0 CHECK(auth_version>=0);
ALTER TABLE users ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN access_change_reason text;
ALTER TABLE users ADD COLUMN password_reset_reason text;
-- Dates are supplied by authorized staff from institution-approved schedules.
CREATE TABLE enrollment_attendance_windows (
 campus_id bigint REFERENCES campuses(id), term_id bigint REFERENCES academic_terms(id),
 starts_on date NOT NULL, ends_on date NOT NULL CHECK(ends_on>=starts_on),
 approval_note text NOT NULL CHECK(btrim(approval_note)<>''),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT current_timestamp, updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id),
 PRIMARY KEY(campus_id,term_id)
);
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON enrollment_attendance_windows FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON enrollment_attendance_windows FOR EACH ROW EXECUTE FUNCTION audit_change();
ALTER TABLE enrollment_attendance_windows ADD COLUMN orientation_starts_on date;
ALTER TABLE enrollment_attendance_windows ADD COLUMN orientation_ends_on date;
ALTER TABLE enrollment_attendance_windows ADD CONSTRAINT paired_orientation_dates CHECK(
 (orientation_starts_on IS NULL AND orientation_ends_on IS NULL) OR
 (orientation_starts_on IS NOT NULL AND orientation_ends_on IS NOT NULL AND orientation_ends_on>=orientation_starts_on));
