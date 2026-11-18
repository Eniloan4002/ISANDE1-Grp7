-- No institutional thresholds are seeded: the school must approve each policy.
INSERT INTO permissions(code,description) VALUES
 ('honors.configure','Configure draft honor eligibility rules'),
 ('honors.approve','Attest school approval of honor eligibility rules');
INSERT INTO role_permissions(role_code,permission_code) VALUES
 ('ROLE_ADMIN','honors.configure'),('ROLE_REGISTRAR','honors.configure'),
 ('ROLE_ADMIN','honors.approve'),('ROLE_PRINCIPAL','honors.approve');
CREATE TABLE honor_policies (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 campus_id bigint NOT NULL REFERENCES campuses(id), program_id bigint NOT NULL REFERENCES programs(id),
 grade_level varchar(30) NOT NULL CHECK(length(btrim(grade_level))>0),
 grading_period_id bigint NOT NULL REFERENCES grading_periods(id),
 rules jsonb NOT NULL, status varchar(15) NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','approved','superseded')),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 approval_reference text, approved_by bigint REFERENCES users(id), approved_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT current_timestamp, updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id),updated_by bigint REFERENCES users(id),
 CHECK(status='draft' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND length(btrim(approval_reference)) BETWEEN 1 AND 2000))
);
CREATE UNIQUE INDEX honor_one_approved_scope ON honor_policies(campus_id,program_id,grade_level,grading_period_id) WHERE status='approved';
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON honor_policies FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON honor_policies FOR EACH ROW EXECUTE FUNCTION audit_change();
CREATE FUNCTION guard_approved_honor_policy() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF OLD.status<>'draft' AND (NEW.rules IS DISTINCT FROM OLD.rules OR NEW.campus_id<>OLD.campus_id OR NEW.program_id<>OLD.program_id OR NEW.grade_level<>OLD.grade_level OR NEW.grading_period_id<>OLD.grading_period_id OR NEW.approval_reference IS DISTINCT FROM OLD.approval_reference OR NEW.approved_by IS DISTINCT FROM OLD.approved_by OR NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.status<>'superseded') THEN
  RAISE EXCEPTION 'Approved honor policy contents cannot be changed' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER honor_policy_immutable BEFORE UPDATE ON honor_policies FOR EACH ROW EXECUTE FUNCTION guard_approved_honor_policy();
