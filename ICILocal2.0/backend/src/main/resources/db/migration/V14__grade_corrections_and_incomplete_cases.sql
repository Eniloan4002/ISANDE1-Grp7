ALTER TABLE grade_corrections ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version>=0);
ALTER TABLE grade_corrections ADD COLUMN grade_version integer;
UPDATE grade_corrections c SET grade_version=g.version FROM grades g WHERE g.id=c.grade_id;
ALTER TABLE grade_corrections ALTER COLUMN grade_version SET NOT NULL;
CREATE UNIQUE INDEX correction_one_pending ON grade_corrections(grade_id) WHERE status='pending';
CREATE TABLE grade_correction_evidence (
 correction_id bigint PRIMARY KEY REFERENCES grade_corrections(id),
 storage_key text NOT NULL UNIQUE, filename text NOT NULL, media_type text NOT NULL,
 content bytea NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 1000000),
 sha256 varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT current_timestamp,updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id),updated_by bigint REFERENCES users(id)
);
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON grade_correction_evidence FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();
CREATE FUNCTION audit_correction_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE before_row jsonb; after_row jsonb;
BEGIN
 IF TG_OP<>'INSERT' THEN before_row:=to_jsonb(OLD)-'content'; END IF;
 IF TG_OP<>'DELETE' THEN after_row:=to_jsonb(NEW)-'content'; END IF;
 INSERT INTO audit_logs(table_name,record_id,operation,old_data,new_data,actor_id)
 VALUES(TG_TABLE_NAME,coalesce(after_row,before_row)->>'correction_id',TG_OP,before_row,after_row,nullif(current_setting('app.actor_id',true),'')::bigint);
 RETURN NULL;
END $$;
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON grade_correction_evidence FOR EACH ROW EXECUTE FUNCTION audit_correction_evidence();
ALTER TABLE incomplete_cases ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version>=0);
ALTER TABLE incomplete_cases ADD COLUMN proposed_value numeric(5,2) CHECK(proposed_value BETWEEN 0 AND 100);
ALTER TABLE incomplete_cases ADD COLUMN proposed_by bigint REFERENCES users(id);
ALTER TABLE incomplete_cases ADD COLUMN proposed_at timestamptz;
ALTER TABLE incomplete_cases ADD COLUMN proposed_grade_version integer;
CREATE TABLE incomplete_follow_ups (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,case_id bigint NOT NULL REFERENCES incomplete_cases(id),
 note text NOT NULL CHECK(length(btrim(note)) BETWEEN 1 AND 2000),
 method varchar(20) NOT NULL CHECK(method IN ('Email','Phone','In person','System note')),
 author_id bigint NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id),updated_by bigint REFERENCES users(id)
);
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON incomplete_follow_ups FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON incomplete_follow_ups FOR EACH ROW EXECUTE FUNCTION audit_change();
CREATE FUNCTION maintain_incomplete_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.mark='INC' AND NEW.state IN ('submitted','finalized') THEN
  INSERT INTO incomplete_cases(grade_id,description) VALUES(NEW.id,'Incomplete grade awaiting verified completion')
  ON CONFLICT(grade_id) WHERE status<>'resolved' DO NOTHING;
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER grade_incomplete_case AFTER INSERT OR UPDATE ON grades FOR EACH ROW EXECUTE FUNCTION maintain_incomplete_case();
INSERT INTO incomplete_cases(grade_id,description)
 SELECT g.id,'Incomplete grade awaiting verified completion' FROM grades g
 WHERE g.mark='INC' AND g.state IN ('submitted','finalized')
 ON CONFLICT(grade_id) WHERE status<>'resolved' DO NOTHING;
