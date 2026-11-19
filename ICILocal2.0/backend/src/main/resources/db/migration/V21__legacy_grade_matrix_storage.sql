-- ICI2-38 legacy historical grade matrix: provenance, stored layout mapping and dedicated legacy grade storage.
-- Reuses the ICI2-37 batch/row/exception/mapping tables; historical grades are NOT written to grades/class_memberships
-- (those require applications, enrollments, sections, teachers and locks that legacy data does not have).
-- The real legacy grade file has not been supplied. The seeded mapping is an ASSUMED default (real_file_validated=false)
-- and contains no invented subject, term or mark translations.

-- Writing legacy grades into official academic records is a different power from creating student identities,
-- so it is its own permission. Reads reuse imports.read; mapping edits reuse imports.mappings.manage.
INSERT INTO permissions(code,description) VALUES
 ('imports.grades.manage','Upload, review and commit legacy historical grade matrix imports using an existing mapping');
INSERT INTO role_permissions(role_code,permission_code) VALUES
 ('ROLE_ADMIN','imports.grades.manage'),('ROLE_REGISTRAR','imports.grades.manage');

-- A second import source shares the V19 batch and mapping tables.
ALTER TABLE import_column_mappings DROP CONSTRAINT import_column_mappings_source_type_check;
ALTER TABLE import_column_mappings ADD CONSTRAINT import_column_mappings_source_type_check
 CHECK(source_type IN ('legacy_student_csv','legacy_grade_matrix_csv'));
ALTER TABLE import_batches DROP CONSTRAINT import_batches_source_type_check;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_source_type_check
 CHECK(source_type IN ('legacy_student_csv','legacy_grade_matrix_csv'));
-- What the registrar declared at upload (school year, term, grade level, section) next to the mapping snapshot.
ALTER TABLE import_batches ADD COLUMN upload_context jsonb CHECK(upload_context IS NULL OR jsonb_typeof(upload_context)='object');
ALTER TABLE import_batches ADD CONSTRAINT import_batches_id_source_type UNIQUE(id,source_type);

-- Grade headers can be longer than the 40 characters student-field names needed.
ALTER TABLE import_exceptions ALTER COLUMN field_name TYPE varchar(200);

-- Rows learn their source type so a grade row (which belongs to an EXISTING student) is never mistaken for a row that created one.
ALTER TABLE import_rows ADD COLUMN source_type varchar(40) NOT NULL DEFAULT 'legacy_student_csv';
ALTER TABLE import_rows ADD CONSTRAINT import_rows_batch_source_fk
 FOREIGN KEY(batch_id,source_type) REFERENCES import_batches(id,source_type);
-- 'distinct' means "create a student"; a grade matrix row can never do that.
ALTER TABLE import_rows ADD CONSTRAINT import_row_grade_never_creates
 CHECK(source_type='legacy_student_csv' OR review_decision IS DISTINCT FROM 'distinct');
-- Only student-import rows are unique per created student; many grade rows may belong to one student.
DROP INDEX import_rows_student_created_once;
CREATE UNIQUE INDEX import_rows_student_created_once ON import_rows(student_id)
 WHERE student_id IS NOT NULL AND review_decision IS DISTINCT FROM 'same_person' AND source_type='legacy_student_csv';
-- Target of the provenance foreign key on legacy_grade_records.
ALTER TABLE import_rows ADD CONSTRAINT import_rows_provenance_key UNIQUE(id,source_type,student_id,campus_id);

CREATE TABLE legacy_grade_records (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 student_id bigint NOT NULL REFERENCES students(id),
 campus_id bigint NOT NULL REFERENCES campuses(id),
 -- Existing setup rows only: the import never creates academic years, terms, periods or subjects.
 term_id bigint NOT NULL REFERENCES academic_terms(id),
 grading_period_id bigint,
 subject_id bigint NOT NULL REFERENCES subjects(id),
 -- 'reported_final' is a rating the legacy file already contains; it is stored as reported and never computed here.
 record_kind varchar(15) NOT NULL DEFAULT 'period' CHECK(record_kind IN ('period','reported_final')),
 program_id bigint REFERENCES programs(id),
 grade_level varchar(30) NOT NULL CHECK(length(btrim(grade_level))>0),
 section_name varchar(150) CHECK(section_name IS NULL OR length(btrim(section_name))>0),
 -- Same scale and mark vocabulary as grades, so readers treat both identically.
 value numeric(5,2) CHECK(value BETWEEN 0 AND 100),
 mark varchar(12) CHECK(mark IN ('INC','NG','DRP')),
 -- Provenance: the matrix row (and through it the batch, file name and checksum) and the exact source column.
 import_row_id bigint NOT NULL,
 import_source_type varchar(40) NOT NULL DEFAULT 'legacy_grade_matrix_csv' CHECK(import_source_type='legacy_grade_matrix_csv'),
 source_column varchar(200) NOT NULL CHECK(length(btrim(source_column))>0),
 voided_at timestamptz, voided_by bigint REFERENCES users(id), void_reason text,
 created_at timestamptz NOT NULL DEFAULT current_timestamp, updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id),
 FOREIGN KEY(grading_period_id,term_id) REFERENCES grading_periods(id,term_id),
 FOREIGN KEY(import_row_id,import_source_type,student_id,campus_id) REFERENCES import_rows(id,source_type,student_id,campus_id),
 CHECK(num_nonnulls(value,mark)=1),
 CHECK((record_kind='period')=(grading_period_id IS NOT NULL)),
 CHECK(num_nonnulls(voided_at,voided_by,void_reason) IN (0,3)),
 CHECK(void_reason IS NULL OR length(btrim(void_reason)) BETWEEN 1 AND 2000)
);
-- One live record per student, term, subject and period; a different value never overwrites, it must be voided first.
CREATE UNIQUE INDEX legacy_grade_period_once ON legacy_grade_records(student_id,term_id,subject_id,grading_period_id)
 WHERE record_kind='period' AND voided_at IS NULL;
CREATE UNIQUE INDEX legacy_grade_final_once ON legacy_grade_records(student_id,term_id,subject_id)
 WHERE record_kind='reported_final' AND voided_at IS NULL;
CREATE INDEX legacy_grade_student_term ON legacy_grade_records(student_id,term_id) WHERE voided_at IS NULL;
CREATE INDEX legacy_grade_import_row ON legacy_grade_records(import_row_id);
CREATE TRIGGER record_metadata BEFORE INSERT OR UPDATE ON legacy_grade_records FOR EACH ROW EXECUTE FUNCTION maintain_record_metadata();

-- Committed legacy grades are history: the only permitted change is voiding a mistaken record, once, with a reason.
CREATE FUNCTION guard_legacy_grade_record() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ignored text[] := ARRAY['voided_at','voided_by','void_reason','updated_at','updated_by'];
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Legacy grade records cannot be deleted; void them instead' USING ERRCODE='23514'; END IF;
 IF OLD.voided_at IS NOT NULL THEN RAISE EXCEPTION 'A voided legacy grade record cannot be changed' USING ERRCODE='23514'; END IF;
 IF (to_jsonb(NEW)-ignored) IS DISTINCT FROM (to_jsonb(OLD)-ignored) THEN
  RAISE EXCEPTION 'Legacy grade values and provenance cannot be edited; void the record instead' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER legacy_grade_record_guard BEFORE UPDATE OR DELETE ON legacy_grade_records FOR EACH ROW EXECUTE FUNCTION guard_legacy_grade_record();
CREATE FUNCTION reject_legacy_grade_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Legacy grade records cannot be truncated' USING ERRCODE='23514'; END $$;
CREATE TRIGGER legacy_grade_record_no_truncate BEFORE TRUNCATE ON legacy_grade_records FOR EACH STATEMENT EXECUTE FUNCTION reject_legacy_grade_truncate();

-- Grade values and marks are academic personal data; keep them out of audit_logs (like import_rows raw values).
CREATE FUNCTION legacy_grade_record_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint := nullif(current_setting('app.actor_id',true),'')::bigint; before_row jsonb; after_row jsonb;
BEGIN
 IF TG_OP<>'INSERT' THEN before_row:=to_jsonb(OLD)-'value'-'mark'; END IF;
 IF TG_OP<>'DELETE' THEN after_row:=to_jsonb(NEW)-'value'-'mark'; END IF;
 INSERT INTO audit_logs(table_name,record_id,operation,old_data,new_data,actor_id)
 VALUES(TG_TABLE_NAME,jsonb_build_object('id',CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END)::text,TG_OP,before_row,after_row,actor);
 RETURN NULL;
END $$;
CREATE TRIGGER record_audit AFTER INSERT OR UPDATE OR DELETE ON legacy_grade_records FOR EACH ROW EXECUTE FUNCTION legacy_grade_record_audit();

-- Assumed default layout for the grade matrix. Aliases are guesses; every value map is empty so only canonical codes translate.
INSERT INTO import_column_mappings(source_type,name,is_default,real_file_validated,mapping,settings) VALUES (
 'legacy_grade_matrix_csv','Assumed grade matrix default (unvalidated)',true,false,
 '{"fields":{
   "studentNumber":{"required":false,"aliases":["student_number","student_no","studentno","student_id","id_number","id_no"]},
   "lrn":{"required":false,"aliases":["lrn","legacy_lrn","learner_reference_number"]},
   "lastName":{"required":false,"aliases":["last_name","lastname","surname","family_name"]},
   "firstName":{"required":false,"aliases":["first_name","firstname","given_name"]},
   "schoolYear":{"required":false,"aliases":["school_year","sy","academic_year"]},
   "term":{"required":false,"aliases":["term","semester","sem"]},
   "gradeLevel":{"required":false,"aliases":["grade_level","grade","year_level"]},
   "section":{"required":false,"aliases":["section","section_name"]}
  },
  "grid":{"layout":"subject_period_flat","separator":" - ","ignoreColumns":[],"reportedFinalLabels":[]},
  "valueMaps":{"subject":{},"period":{},"term":{},"schoolYear":{},"mark":{}}}',
 '{"encoding":"UTF-8","delimiter":",","headerRow":1,"decimalSeparator":"."}');
