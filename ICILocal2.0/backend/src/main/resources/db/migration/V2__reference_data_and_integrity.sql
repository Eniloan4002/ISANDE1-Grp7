INSERT INTO campuses(code,name) VALUES ('ILIGAN','Iligan Main'),('KAPATAGAN','Kapatagan'),('BUGO','Bugo — Cagayan de Oro');
INSERT INTO roles(code,name) VALUES
 ('ROLE_ADMIN','System Administrator'),('ROLE_REGISTRAR','Registrar'),
 ('ROLE_PRINCIPAL','Principal'),('ROLE_ADVISER','Teacher Adviser'),
 ('ROLE_TEACHER','Subject Teacher'),('ROLE_GUIDANCE','Guidance'),('ROLE_CASHIER_REF','Cashiering Reference');
INSERT INTO permissions(code,description) VALUES
 ('users.manage','Manage staff accounts'),('enrollment.manage','Manage enrollment'),
 ('grades.encode','Encode assigned subject grades'),('grades.review','Review and finalize grades'),
 ('advisory.read','Read assigned advisory rosters'),('reports.read','View operational reports'),
 ('guidance.manage','Manage guidance interventions'),('cashier.read','Read cashiering reference rosters');
INSERT INTO role_permissions VALUES
 ('ROLE_ADMIN','users.manage'),('ROLE_REGISTRAR','enrollment.manage'),('ROLE_REGISTRAR','grades.review'),
 ('ROLE_REGISTRAR','reports.read'),('ROLE_PRINCIPAL','reports.read'),('ROLE_ADVISER','advisory.read'),
 ('ROLE_TEACHER','grades.encode'),('ROLE_GUIDANCE','guidance.manage'),('ROLE_CASHIER_REF','cashier.read');
INSERT INTO vouchers VALUES ('QVR','Qualified Voucher Recipient'),('ESC','Education Service Contracting'),('ALS','Alternative Learning System'),('REGULAR','Regular / Paying');
INSERT INTO requirement_types VALUES ('PSA','PSA Birth Certificate',true),('FORM137','Form 137 / Report Card',true),('COMPLETION','Certificate of Completion',true),('GOOD_MORAL','Certificate of Good Moral Character',true);
INSERT INTO programs(code,name,category) VALUES ('STEM','Science, Technology, Engineering and Mathematics','SHS'),('ABM','Accountancy, Business and Management','SHS'),('HUMSS','Humanities and Social Sciences','SHS'),('GAS','General Academic Strand','SHS'),('TVL_COOKERY','TVL — Cookery','SHS'),('TVL_ICT','TVL — Information and Communications Technology','SHS');

CREATE FUNCTION touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at := current_timestamp; RETURN NEW; END $$;
CREATE TRIGGER users_touch BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER students_touch BEFORE UPDATE ON students FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER applications_touch BEFORE UPDATE ON applications FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- The application sets SET LOCAL app.actor_id inside every mutating transaction.
CREATE FUNCTION audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint; before_row jsonb; after_row jsonb;
BEGIN
 actor := nullif(current_setting('app.actor_id',true),'')::bigint;
 IF TG_OP <> 'INSERT' THEN before_row := to_jsonb(OLD); END IF;
 IF TG_OP <> 'DELETE' THEN after_row := to_jsonb(NEW); END IF;
 INSERT INTO audit_logs(table_name,record_id,operation,old_data,new_data,actor_id)
 VALUES(TG_TABLE_NAME,coalesce(after_row->>'id',before_row->>'id',after_row->>'application_id',before_row->>'application_id'),TG_OP,before_row,after_row,actor);
 RETURN NULL;
END $$;
CREATE TRIGGER requirements_audit AFTER INSERT OR UPDATE OR DELETE ON application_requirements FOR EACH ROW EXECUTE FUNCTION audit_change();
CREATE TRIGGER grades_audit AFTER INSERT OR UPDATE OR DELETE ON grades FOR EACH ROW EXECUTE FUNCTION audit_change();
CREATE TRIGGER corrections_audit AFTER INSERT OR UPDATE OR DELETE ON grade_corrections FOR EACH ROW EXECUTE FUNCTION audit_change();
CREATE TRIGGER enrollments_audit AFTER INSERT OR UPDATE OR DELETE ON enrollments FOR EACH ROW EXECUTE FUNCTION audit_change();
CREATE FUNCTION reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Audit records are append-only'; END $$;
CREATE TRIGGER immutable_audits BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_logs FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();

-- Serialize capacity decisions on the parent section row to avoid overbooking.
CREATE FUNCTION guard_enrollment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE section_capacity integer; occupied integer; application_student bigint;
BEGIN
 SELECT student_id INTO application_student FROM applications WHERE id = NEW.application_id FOR UPDATE;
 IF application_student IS DISTINCT FROM NEW.student_id THEN
   RAISE EXCEPTION 'Application must be linked to the enrollment student';
 END IF;
 IF NEW.section_id IS NOT NULL AND NEW.status IN ('provisional','enrolled') THEN
   SELECT capacity INTO section_capacity FROM sections WHERE id = NEW.section_id FOR UPDATE;
   SELECT count(*) INTO occupied FROM enrollments WHERE section_id = NEW.section_id
     AND status IN ('provisional','enrolled') AND id IS DISTINCT FROM NEW.id;
   IF occupied >= section_capacity THEN RAISE EXCEPTION 'Section capacity exceeded'; END IF;
 END IF;
 IF NEW.status = 'enrolled' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'enrolled') THEN
   IF NOT EXISTS (SELECT 1 FROM orientation_attendance WHERE application_id = NEW.application_id AND status = 'present') THEN
     RAISE EXCEPTION 'Enrollment requires recorded orientation attendance';
   END IF;
   IF EXISTS (SELECT 1 FROM requirement_types t LEFT JOIN application_requirements r
     ON r.requirement_code = t.code AND r.application_id = NEW.application_id
     WHERE t.mandatory AND (r.status IS NULL OR r.status <> 'submitted')) THEN
     RAISE EXCEPTION 'Enrollment requires all mandatory credentials';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER enrollment_guard BEFORE INSERT OR UPDATE ON enrollments FOR EACH ROW EXECUTE FUNCTION guard_enrollment();
CREATE FUNCTION guard_section() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.capacity < (SELECT count(*) FROM enrollments WHERE section_id = NEW.id AND status IN ('provisional','enrolled')) THEN
   RAISE EXCEPTION 'Capacity cannot be below occupied seats';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER section_guard BEFORE UPDATE ON sections FOR EACH ROW EXECUTE FUNCTION guard_section();
CREATE FUNCTION guard_class_term() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM sections WHERE id = NEW.section_id AND term_id = NEW.term_id) THEN
   RAISE EXCEPTION 'Class term must match section term';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER class_term_guard BEFORE INSERT OR UPDATE ON class_offerings FOR EACH ROW EXECUTE FUNCTION guard_class_term();
CREATE FUNCTION guard_window_term() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 SELECT term_id INTO NEW.term_id FROM class_offerings WHERE id = NEW.class_id;
 IF NOT EXISTS(SELECT 1 FROM class_offerings c JOIN grading_periods p ON c.term_id = p.term_id WHERE c.id = NEW.class_id AND p.id = NEW.grading_period_id) THEN
   RAISE EXCEPTION 'Grading window period must match class term';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER window_term_guard BEFORE INSERT OR UPDATE ON grading_windows FOR EACH ROW EXECUTE FUNCTION guard_window_term();
