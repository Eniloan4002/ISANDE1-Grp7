-- Section policy also covers class offerings added after a lock or deadline.
CREATE TABLE section_grading_windows (
 section_id bigint NOT NULL,
 grading_period_id bigint NOT NULL,
 term_id bigint NOT NULL,
 deadline timestamptz,
 locked_at timestamptz, locked_by bigint REFERENCES users(id),
 finalized_at timestamptz, finalized_by bigint REFERENCES users(id),
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 2000),
 version integer NOT NULL DEFAULT 1 CHECK(version > 0),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id),
 PRIMARY KEY(section_id,grading_period_id),
 FOREIGN KEY(section_id,term_id) REFERENCES sections(id,term_id),
 FOREIGN KEY(grading_period_id,term_id) REFERENCES grading_periods(id,term_id),
 CHECK((locked_at IS NULL) = (locked_by IS NULL)),
 CHECK((finalized_at IS NULL) = (finalized_by IS NULL)),
 CHECK(finalized_at IS NULL OR locked_at IS NOT NULL)
);
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON section_grading_windows
 FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON section_grading_windows
 FOR EACH ROW EXECUTE FUNCTION audit_change();
