-- Extend the existing enrollment model; preserve released migrations and data.
ALTER TABLE applications ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);
ALTER TABLE orientation_attendance ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);
ALTER TABLE sections ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);
ALTER TABLE enrollments ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);
CREATE INDEX attendance_present_application ON orientation_attendance(application_id) WHERE status='present';
CREATE INDEX enrollment_workspace ON enrollments(campus_id,term_id,program_id,status,id);
