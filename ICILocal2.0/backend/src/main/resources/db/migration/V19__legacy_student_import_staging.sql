-- ICI2-37 legacy student CSV import: staging, provenance and stored column mapping.
-- Extends the V1 import_batches/import_exceptions tables; no parallel batch or error tables.
-- The real legacy export has not been supplied. The seeded mapping is an ASSUMED default
-- (real_file_validated=false) and contains no invented legacy value translations.
INSERT INTO permissions(code,description) VALUES
 ('imports.read','View legacy import batches, staged rows, mappings and validation results'),
 ('imports.manage','Upload, validate and commit legacy student imports using an existing mapping'),
 ('imports.mappings.manage','Create, edit and activate legacy import column mappings');
-- Mapping changes alter how every later file is read, so they are Administrator-only.
INSERT INTO role_permissions(role_code,permission_code) VALUES
 ('ROLE_ADMIN','imports.read'),('ROLE_REGISTRAR','imports.read'),
 ('ROLE_ADMIN','imports.manage'),('ROLE_REGISTRAR','imports.manage'),
 ('ROLE_ADMIN','imports.mappings.manage');

CREATE TABLE import_column_mappings (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 source_type varchar(40) NOT NULL CHECK(source_type IN ('legacy_student_csv')),
 name varchar(120) NOT NULL CHECK(length(btrim(name))>0),
 mapping jsonb NOT NULL CHECK(jsonb_typeof(mapping)='object'),
 settings jsonb NOT NULL CHECK(jsonb_typeof(settings)='object'),
 is_default boolean NOT NULL DEFAULT false,
 active boolean NOT NULL DEFAULT true,
 real_file_validated boolean NOT NULL DEFAULT false,
 version integer NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz NOT NULL DEFAULT current_timestamp, updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id),
 CHECK(NOT is_default OR active)
);
CREATE UNIQUE INDEX import_mapping_name_casefold ON import_column_mappings(source_type,lower(btrim(name)));
CREATE UNIQUE INDEX import_mapping_one_default ON import_column_mappings(source_type) WHERE is_default;
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON import_column_mappings FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON import_column_mappings FOR EACH ROW EXECUTE FUNCTION audit_change();

INSERT INTO import_column_mappings(source_type,name,is_default,real_file_validated,mapping,settings) VALUES (
 'legacy_student_csv','Assumed default (unvalidated)',true,false,
 '{"fields":{
   "studentNumber":{"required":true,"aliases":["student_number","student_no","studentno","student_id","id_number","id_no"]},
   "lrn":{"required":false,"aliases":["lrn","legacy_lrn","learner_reference_number"]},
   "lastName":{"required":true,"aliases":["last_name","lastname","surname","family_name"]},
   "firstName":{"required":true,"aliases":["first_name","firstname","given_name"]},
   "middleName":{"required":false,"aliases":["middle_name","middlename"]},
   "birthDate":{"required":true,"aliases":["birth_date","birthdate","date_of_birth","dob","birthday"]},
   "sex":{"required":false,"aliases":["sex","gender"]},
   "previousSchool":{"required":false,"aliases":["previous_school","last_school_attended","school_last_attended"]},
   "voucher":{"required":true,"aliases":["voucher","voucher_type","voucher_classification","voucher_status"]},
   "specialization":{"required":true,"aliases":["specialization","strand","program"]},
   "campus":{"required":false,"aliases":["campus","campus_code","branch"]}
  },
  "valueMaps":{"voucher":{},"specialization":{},"campus":{},"sex":{}}}',
 '{"encoding":"UTF-8","delimiter":",","headerRow":1,"dateFormats":["yyyy-MM-dd","MM/dd/yyyy"],"normalizeNameCase":false}');

ALTER TABLE import_batches ADD COLUMN source_type varchar(40) NOT NULL DEFAULT 'legacy_student_csv' CHECK(source_type IN ('legacy_student_csv'));
-- Each campus has its own legacy database, so an upload is for exactly one campus.
ALTER TABLE import_batches ADD COLUMN campus_id bigint REFERENCES campuses(id);
ALTER TABLE import_batches ADD CONSTRAINT import_batch_campus_required CHECK(campus_id IS NOT NULL) NOT VALID;
ALTER TABLE import_batches ADD COLUMN mapping_id bigint REFERENCES import_column_mappings(id);
ALTER TABLE import_batches ADD COLUMN mapping_snapshot jsonb CHECK(mapping_snapshot IS NULL OR jsonb_typeof(mapping_snapshot)='object');
ALTER TABLE import_batches ADD COLUMN file_size_bytes bigint CHECK(file_size_bytes IS NULL OR file_size_bytes>=0);
ALTER TABLE import_batches ADD COLUMN valid_count integer NOT NULL DEFAULT 0 CHECK(valid_count>=0);
ALTER TABLE import_batches ADD COLUMN invalid_count integer NOT NULL DEFAULT 0 CHECK(invalid_count>=0);
ALTER TABLE import_batches ADD COLUMN flagged_count integer NOT NULL DEFAULT 0 CHECK(flagged_count>=0);
ALTER TABLE import_batches ADD COLUMN skipped_count integer NOT NULL DEFAULT 0 CHECK(skipped_count>=0);
ALTER TABLE import_batches ADD COLUMN imported_by bigint REFERENCES users(id);
ALTER TABLE import_batches ADD COLUMN imported_at timestamptz;
ALTER TABLE import_batches ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version>=0);
-- NOT VALID: constrain new/changed rows without failing on any pre-existing V1 batch rows.
ALTER TABLE import_batches ADD CONSTRAINT import_batch_counts_balance
 CHECK(status IN ('staged','failed') OR valid_count+invalid_count+flagged_count+skipped_count+imported_count=source_count) NOT VALID;
ALTER TABLE import_batches ADD CONSTRAINT import_batch_commit_actor
 CHECK(status NOT IN ('imported','reconciled') OR (imported_by IS NOT NULL AND imported_at IS NOT NULL)) NOT VALID;
-- The same file content cannot be staged twice unless the earlier attempt failed.
CREATE UNIQUE INDEX import_batch_checksum_once ON import_batches(source_type,checksum) WHERE status<>'failed';
CREATE INDEX import_batches_history ON import_batches(source_type,campus_id,started_at DESC,id DESC);

CREATE TABLE import_rows (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 batch_id bigint NOT NULL REFERENCES import_batches(id),
 row_number integer NOT NULL CHECK(row_number>0),
 status varchar(20) NOT NULL CHECK(status IN ('valid','invalid','duplicate_flagged','skipped','imported')),
 campus_id bigint REFERENCES campuses(id),
 raw_values jsonb NOT NULL DEFAULT '{}' CHECK(jsonb_typeof(raw_values)='object'),
 normalized_values jsonb CHECK(normalized_values IS NULL OR jsonb_typeof(normalized_values)='object'),
 duplicate_evidence jsonb CHECK(duplicate_evidence IS NULL OR jsonb_typeof(duplicate_evidence)='array'),
 review_decision varchar(15) CHECK(review_decision IN ('same_person','distinct')),
 review_reason text, reviewed_by bigint REFERENCES users(id), reviewed_at timestamptz,
 student_id bigint REFERENCES students(id),
 version integer NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz NOT NULL DEFAULT current_timestamp, updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id),
 UNIQUE(batch_id,row_number),
 CHECK((status='imported')=(student_id IS NOT NULL)),
 CHECK(num_nonnulls(review_decision,reviewed_by,reviewed_at)IN(0,3)),
 CHECK(review_decision IS NULL OR (review_reason IS NOT NULL AND length(btrim(review_reason)) BETWEEN 1 AND 2000)),
 CHECK(status NOT IN ('valid','duplicate_flagged','imported') OR normalized_values IS NOT NULL),
 CHECK(status<>'duplicate_flagged' OR duplicate_evidence IS NOT NULL)
);
CREATE INDEX import_rows_batch_status ON import_rows(batch_id,status,row_number);
CREATE UNIQUE INDEX import_rows_student_once ON import_rows(student_id) WHERE student_id IS NOT NULL;
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON import_rows FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();
-- Raw and normalized source values contain personal data; keep them out of audit_logs.
CREATE FUNCTION import_row_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint := nullif(current_setting('app.actor_id',true),'')::bigint; before_row jsonb; after_row jsonb;
BEGIN
 IF TG_OP<>'INSERT' THEN before_row:=to_jsonb(OLD)-'raw_values'-'normalized_values'; END IF;
 IF TG_OP<>'DELETE' THEN after_row:=to_jsonb(NEW)-'raw_values'-'normalized_values'; END IF;
 INSERT INTO audit_logs(table_name,record_id,operation,old_data,new_data,actor_id)
 VALUES(TG_TABLE_NAME,jsonb_build_object('id',CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END)::text,TG_OP,before_row,after_row,actor);
 RETURN NULL;
END $$;
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON import_rows FOR EACH ROW EXECUTE FUNCTION import_row_audit();

-- Per-row (and batch-level) messages reuse import_exceptions: one record per problem.
ALTER TABLE import_exceptions ADD COLUMN import_row_id bigint REFERENCES import_rows(id);
ALTER TABLE import_exceptions ADD COLUMN field_name varchar(40);
ALTER TABLE import_exceptions ADD COLUMN severity varchar(10) NOT NULL DEFAULT 'error' CHECK(severity IN ('error','warning'));
ALTER TABLE import_exceptions ADD CONSTRAINT import_exception_detail_bounded CHECK(length(btrim(detail)) BETWEEN 1 AND 1000) NOT VALID;
CREATE INDEX import_exceptions_row ON import_exceptions(import_row_id) WHERE import_row_id IS NOT NULL;
CREATE INDEX import_exceptions_batch ON import_exceptions(batch_id,row_number);
