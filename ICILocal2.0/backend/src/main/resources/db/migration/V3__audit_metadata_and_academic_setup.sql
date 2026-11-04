-- Complete common metadata without rewriting an already shared baseline.
CREATE FUNCTION maintain_record_metadata() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint := nullif(current_setting('app.actor_id',true),'')::bigint;
BEGIN
 IF TG_OP = 'INSERT' THEN
   NEW.created_at := current_timestamp;
   NEW.created_by := actor;
 ELSE
   NEW.created_at := OLD.created_at;
   NEW.created_by := OLD.created_by;
 END IF;
 NEW.updated_at := current_timestamp;
 NEW.updated_by := actor;
 RETURN NEW;
END $$;

-- Logs identify composite-key rows too, and never retain credential hashes.
CREATE OR REPLACE FUNCTION audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint; before_row jsonb; after_row jsonb; identity_data jsonb;
BEGIN
 actor := nullif(current_setting('app.actor_id',true),'')::bigint;
 IF TG_OP <> 'INSERT' THEN before_row := to_jsonb(OLD) - 'password_hash'; END IF;
 IF TG_OP <> 'DELETE' THEN after_row := to_jsonb(NEW) - 'password_hash'; END IF;
 SELECT jsonb_object_agg(a.attname,coalesce(after_row,before_row)->a.attname)
 INTO identity_data FROM pg_index i
 JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=ANY(i.indkey)
 WHERE i.indrelid=TG_RELID AND i.indisprimary;
 INSERT INTO audit_logs(table_name,record_id,operation,old_data,new_data,actor_id)
 VALUES(TG_TABLE_NAME,identity_data::text,TG_OP,before_row,after_row,actor);
 RETURN NULL;
END $$;

DO $$ DECLARE table_record record;
BEGIN
 FOR table_record IN SELECT tablename FROM pg_tables WHERE schemaname='public'
   AND tablename <> 'audit_logs' AND tablename <> 'flyway_schema_history'
 LOOP
   EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT current_timestamp',table_record.tablename);
   EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT current_timestamp',table_record.tablename);
   EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS created_by bigint REFERENCES users(id)',table_record.tablename);
   EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS updated_by bigint REFERENCES users(id)',table_record.tablename);
   EXECUTE format('CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata()',table_record.tablename);
   -- V2 already installed these four audit hooks; keep exactly one per table.
   IF table_record.tablename NOT IN ('application_requirements','grades','grade_corrections','enrollments') THEN
     EXECUTE format('CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION audit_change()',table_record.tablename);
   END IF;
 END LOOP;
END $$;

ALTER TABLE subjects ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE subjects ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);
ALTER TABLE academic_years ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);
ALTER TABLE academic_terms ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);
ALTER TABLE grading_periods ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);
ALTER TABLE grading_periods ADD COLUMN starts_on date;
ALTER TABLE grading_periods ADD COLUMN ends_on date;
UPDATE grading_periods p SET starts_on=t.starts_on,ends_on=t.ends_on FROM academic_terms t WHERE p.term_id=t.id;
ALTER TABLE grading_periods ALTER COLUMN starts_on SET NOT NULL;
ALTER TABLE grading_periods ALTER COLUMN ends_on SET NOT NULL;
ALTER TABLE grading_periods ADD CONSTRAINT period_date_order CHECK(ends_on >= starts_on);
ALTER TABLE subjects ADD CONSTRAINT subjects_nonblank CHECK(btrim(code)<>'' AND btrim(name)<>'');
ALTER TABLE academic_years ADD CONSTRAINT years_nonblank CHECK(btrim(label)<>'');
ALTER TABLE academic_terms ADD CONSTRAINT terms_nonblank CHECK(btrim(code)<>'' AND btrim(name)<>'');
ALTER TABLE grading_periods ADD CONSTRAINT periods_nonblank CHECK(btrim(code)<>'');
CREATE UNIQUE INDEX subjects_code_casefold ON subjects(lower(btrim(code)));
CREATE UNIQUE INDEX academic_year_label_casefold ON academic_years(lower(btrim(label)));
CREATE UNIQUE INDEX term_code_casefold ON academic_terms(academic_year_id,lower(btrim(code)));
CREATE UNIQUE INDEX period_code_casefold ON grading_periods(term_id,lower(btrim(code)));

CREATE FUNCTION guard_academic_dates() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_start date; parent_end date;
BEGIN
 IF TG_TABLE_NAME='academic_terms' THEN
   SELECT starts_on,ends_on INTO parent_start,parent_end FROM academic_years WHERE id=NEW.academic_year_id FOR UPDATE;
   IF NEW.starts_on < parent_start OR NEW.ends_on > parent_end THEN
     RAISE EXCEPTION 'Term dates must be within the academic year' USING ERRCODE='23514';
   END IF;
   IF EXISTS(SELECT 1 FROM grading_periods WHERE term_id=NEW.id AND (starts_on<NEW.starts_on OR ends_on>NEW.ends_on)) THEN
     RAISE EXCEPTION 'Term dates must contain all grading periods' USING ERRCODE='23514';
   END IF;
 ELSIF TG_TABLE_NAME='grading_periods' THEN
   SELECT starts_on,ends_on INTO parent_start,parent_end FROM academic_terms WHERE id=NEW.term_id FOR UPDATE;
   -- Existing direct-SQL consumers can omit dates and inherit their term bounds.
   NEW.starts_on := coalesce(NEW.starts_on,parent_start);
   NEW.ends_on := coalesce(NEW.ends_on,parent_end);
   IF NEW.starts_on < parent_start OR NEW.ends_on > parent_end THEN
     RAISE EXCEPTION 'Grading period dates must be within its term' USING ERRCODE='23514';
   END IF;
 ELSIF EXISTS(SELECT 1 FROM academic_terms WHERE academic_year_id=NEW.id AND (starts_on<NEW.starts_on OR ends_on>NEW.ends_on)) THEN
   RAISE EXCEPTION 'Academic year dates must contain all terms' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER academic_year_date_guard BEFORE UPDATE ON academic_years FOR EACH ROW EXECUTE FUNCTION guard_academic_dates();
CREATE TRIGGER academic_term_date_guard BEFORE INSERT OR UPDATE ON academic_terms FOR EACH ROW EXECUTE FUNCTION guard_academic_dates();
CREATE TRIGGER grading_period_date_guard BEFORE INSERT OR UPDATE ON grading_periods FOR EACH ROW EXECUTE FUNCTION guard_academic_dates();

-- Published or assigned configuration can be renamed, but cannot be moved to a
-- different parent; immutable keys preserve historical scope.
CREATE FUNCTION guard_setup_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='academic_terms' THEN
   IF NEW.academic_year_id<>OLD.academic_year_id THEN
     RAISE EXCEPTION 'A term cannot be moved to another academic year' USING ERRCODE='23514';
   END IF;
 ELSIF TG_TABLE_NAME='grading_periods' THEN
   IF NEW.term_id<>OLD.term_id THEN
     RAISE EXCEPTION 'A grading period cannot be moved to another term' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER term_parent_guard BEFORE UPDATE ON academic_terms FOR EACH ROW EXECUTE FUNCTION guard_setup_parent();
CREATE TRIGGER period_parent_guard BEFORE UPDATE ON grading_periods FOR EACH ROW EXECUTE FUNCTION guard_setup_parent();
