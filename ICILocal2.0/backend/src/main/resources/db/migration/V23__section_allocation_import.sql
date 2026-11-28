-- ICI2-39 phase 3: import type A, section allocation (source type section_allocation).
-- Reuses the ICI2-37/38 batch, row, exception and mapping tables. The import never writes enrollments itself: commit calls
-- EnrollmentService.assign, so campus scope, versions, capacity and audit are the enrollment workflow's own. What a commit changed
-- is recorded per row in import_rows.applied (ids and versions only, no personal data in this column's keys, but kept out of
-- audit_logs like raw and normalized values).
-- The real section allocation workbook has not been received; the seeded mapping is an ASSUMED default (real_file_validated=false)
-- with empty value maps.

-- Writing section placements is the enrollment manager's power, not the student-identity or grade import powers, so it is its own
-- permission. The called enrollment service still demands enrollment.manage at the same campus on every write.
INSERT INTO permissions(code,description) VALUES
 ('imports.enrollment.manage','Upload, review and commit section allocation imports using an existing mapping');
INSERT INTO role_permissions(role_code,permission_code) VALUES
 ('ROLE_ADMIN','imports.enrollment.manage'),('ROLE_REGISTRAR','imports.enrollment.manage');

-- A third import source shares the V19 batch and mapping tables.
ALTER TABLE import_column_mappings DROP CONSTRAINT import_column_mappings_source_type_check;
ALTER TABLE import_column_mappings ADD CONSTRAINT import_column_mappings_source_type_check
 CHECK(source_type IN ('legacy_student_csv','legacy_grade_matrix_csv','section_allocation'));
ALTER TABLE import_batches DROP CONSTRAINT import_batches_source_type_check;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_source_type_check
 CHECK(source_type IN ('legacy_student_csv','legacy_grade_matrix_csv','section_allocation'));

-- What a committed row changed: {enrollmentId, fromSectionId, toSectionId, versionBefore, versionAfter} (or {enrollmentId, noop:true}).
ALTER TABLE import_rows ADD COLUMN applied jsonb
 CONSTRAINT import_rows_applied_check CHECK(applied IS NULL OR jsonb_typeof(applied)='object');
-- Only imported rows have an applied record.
ALTER TABLE import_rows ADD CONSTRAINT import_row_applied_only_when_imported CHECK(applied IS NULL OR status='imported');

-- 'apply' accepts a reassignment (or, for document logs, an overwrite) and never creates or links a student.
ALTER TABLE import_rows DROP CONSTRAINT import_rows_review_decision_check;
ALTER TABLE import_rows ADD CONSTRAINT import_rows_review_decision_check CHECK(review_decision IN ('same_person','distinct','skip','apply'));
ALTER TABLE import_rows ADD CONSTRAINT import_row_apply_scope
 CHECK(review_decision IS DISTINCT FROM 'apply' OR source_type IN ('section_allocation','document_log'));

-- Same function as V22 plus the new column; the existing record_audit trigger keeps pointing at it.
CREATE OR REPLACE FUNCTION import_row_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint := nullif(current_setting('app.actor_id',true),'')::bigint; before_row jsonb; after_row jsonb;
BEGIN
 IF TG_OP<>'INSERT' THEN before_row:=to_jsonb(OLD)-'raw_values'-'normalized_values'-'cleaning'-'applied'; END IF;
 IF TG_OP<>'DELETE' THEN after_row:=to_jsonb(NEW)-'raw_values'-'normalized_values'-'cleaning'-'applied'; END IF;
 INSERT INTO audit_logs(table_name,record_id,operation,old_data,new_data,actor_id)
 VALUES(TG_TABLE_NAME,jsonb_build_object('id',CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END)::text,TG_OP,before_row,after_row,actor);
 RETURN NULL;
END $$;

-- Assumed default layout: one student per row (layout student_list). Aliases are guesses; every value map is empty so only exact
-- section names, program codes, term codes and academic year labels translate. A source value maps to a section NAME (names are
-- per campus and term, so targets are checked at staging, not here).
INSERT INTO import_column_mappings(source_type,name,is_default,real_file_validated,mapping,settings) VALUES (
 'section_allocation','Assumed section allocation default (unvalidated)',true,false,
 '{"fields":{
   "studentNumber":{"required":false,"aliases":["student_number","student_no","studentno","student_id","id_number","id_no"]},
   "lrn":{"required":false,"aliases":["lrn","legacy_lrn","learner_reference_number"]},
   "lastName":{"required":false,"aliases":["last_name","lastname","surname","family_name"]},
   "firstName":{"required":false,"aliases":["first_name","firstname","given_name"]},
   "section":{"required":true,"aliases":["section","section_name"]},
   "gradeLevel":{"required":false,"aliases":["grade_level","grade","year_level"]},
   "program":{"required":false,"aliases":["program","strand","track"]},
   "term":{"required":false,"aliases":["term","semester","sem"]},
   "schoolYear":{"required":false,"aliases":["school_year","sy","academic_year"]}
  },
  "valueMaps":{"section":{},"program":{},"term":{},"schoolYear":{}}}',
 '{"encoding":"UTF-8","delimiter":",","headerRow":1,"layout":"student_list","ignoredColumns":[]}');
