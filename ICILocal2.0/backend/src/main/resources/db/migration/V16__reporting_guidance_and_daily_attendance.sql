-- Configured policies/calendar are explicit, never inferred from orientation attendance.
INSERT INTO permissions(code,description) VALUES ('guidance.configure','Configure institution-approved guidance thresholds and school days');
INSERT INTO role_permissions(role_code,permission_code) VALUES ('ROLE_REGISTRAR','guidance.configure'),('ROLE_ADMIN','guidance.configure');
CREATE TABLE guidance_policies (
 campus_id bigint NOT NULL REFERENCES campuses(id),term_id bigint NOT NULL REFERENCES academic_terms(id),
 failing_below numeric(5,2) CHECK(failing_below BETWEEN 0 AND 100),
 consecutive_absences integer CHECK(consecutive_absences BETWEEN 1 AND 366),credentials_due_on date,
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 2000),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 PRIMARY KEY(campus_id,term_id)
);
CREATE TABLE school_calendar_days (
 campus_id bigint NOT NULL REFERENCES campuses(id),term_id bigint NOT NULL REFERENCES academic_terms(id),
 school_date date NOT NULL,is_school_day boolean NOT NULL,
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 2000),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),PRIMARY KEY(campus_id,term_id,school_date)
);
CREATE TABLE daily_academic_attendance (
 enrollment_id bigint NOT NULL REFERENCES enrollments(id),attendance_date date NOT NULL,
 status varchar(12) NOT NULL CHECK(status IN ('present','absent','excused')),
 note text CHECK(length(note)<=2000),version integer NOT NULL DEFAULT 1 CHECK(version>0),
 PRIMARY KEY(enrollment_id,attendance_date)
);
CREATE TABLE guidance_cases (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,enrollment_id bigint NOT NULL UNIQUE REFERENCES enrollments(id),
 status varchar(20) NOT NULL DEFAULT 'open' CHECK(status IN ('open','monitoring','closed')),
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 2000),version integer NOT NULL DEFAULT 1 CHECK(version>0)
);
CREATE TABLE guidance_case_notes (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,case_id bigint NOT NULL REFERENCES guidance_cases(id),
 body text NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 2000),author_id bigint NOT NULL REFERENCES users(id)
);
DO $$ DECLARE name text; BEGIN
 FOREACH name IN ARRAY ARRAY['guidance_policies','school_calendar_days','daily_academic_attendance','guidance_cases','guidance_case_notes'] LOOP
  EXECUTE format('ALTER TABLE %I ADD COLUMN created_at timestamptz NOT NULL DEFAULT current_timestamp, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT current_timestamp, ADD COLUMN created_by bigint REFERENCES users(id), ADD COLUMN updated_by bigint REFERENCES users(id)',name);
  EXECUTE format('CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata()',name);
  EXECUTE format('CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION audit_change()',name);
 END LOOP;
END $$;
CREATE INDEX daily_academic_attendance_date ON daily_academic_attendance(attendance_date,enrollment_id);
CREATE INDEX guidance_case_notes_case ON guidance_case_notes(case_id,id);
CREATE FUNCTION guard_school_calendar_day() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM academic_terms WHERE id=NEW.term_id AND NEW.school_date BETWEEN starts_on AND ends_on) THEN
  RAISE EXCEPTION 'School day must be within its academic term' USING ERRCODE='23514';
 END IF;
 IF NOT NEW.is_school_day AND EXISTS(SELECT 1 FROM daily_academic_attendance a JOIN enrollments e ON e.id=a.enrollment_id WHERE e.campus_id=NEW.campus_id AND e.term_id=NEW.term_id AND a.attendance_date=NEW.school_date) THEN
  RAISE EXCEPTION 'A school day with recorded attendance cannot be removed' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER school_calendar_guard BEFORE INSERT OR UPDATE ON school_calendar_days FOR EACH ROW EXECUTE FUNCTION guard_school_calendar_day();
CREATE FUNCTION guard_daily_academic_attendance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM enrollments e JOIN school_calendar_days d ON d.campus_id=e.campus_id AND d.term_id=e.term_id WHERE e.id=NEW.enrollment_id AND d.school_date=NEW.attendance_date AND d.is_school_day) THEN
  RAISE EXCEPTION 'Attendance requires an explicit school day for this enrollment' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER daily_attendance_guard BEFORE INSERT OR UPDATE ON daily_academic_attendance FOR EACH ROW EXECUTE FUNCTION guard_daily_academic_attendance();
