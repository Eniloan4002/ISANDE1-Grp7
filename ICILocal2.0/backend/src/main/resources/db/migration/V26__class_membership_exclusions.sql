-- ICI2-94: per-subject class membership (electives / cross-section students).
-- A class roster row no longer has to share the student's home section: the membership still belongs to the
-- class's section (FK to class_offerings) but only has to match the enrollment's term.
DO $$ DECLARE fk text;
BEGIN
 SELECT c.conname INTO fk FROM pg_constraint c
 WHERE c.conrelid='class_memberships'::regclass AND c.contype='f' AND c.confrelid='enrollments'::regclass;
 IF fk IS NOT NULL THEN EXECUTE format('ALTER TABLE class_memberships DROP CONSTRAINT %I',fk); END IF;
END $$;
ALTER TABLE enrollments ADD CONSTRAINT enrollments_id_term_key UNIQUE(id, term_id);
ALTER TABLE class_memberships ADD CONSTRAINT class_memberships_enrollment_term_fk
 FOREIGN KEY(enrollment_id, term_id) REFERENCES enrollments(id, term_id);

-- Students deliberately removed from a class of their own section. addSectionMembers (create/sync) skips these
-- so a later sync does not silently put them back; adding the student again clears the exclusion.
CREATE TABLE class_membership_exclusions (
 class_id bigint NOT NULL REFERENCES class_offerings(id),
 enrollment_id bigint NOT NULL REFERENCES enrollments(id),
 created_at timestamptz NOT NULL DEFAULT current_timestamp, updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id),
 PRIMARY KEY(class_id, enrollment_id)
);
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON class_membership_exclusions FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON class_membership_exclusions FOR EACH ROW EXECUTE FUNCTION audit_change();
