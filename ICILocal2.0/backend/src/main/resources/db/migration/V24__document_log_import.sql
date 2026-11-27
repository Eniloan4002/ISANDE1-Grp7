-- ICI2-39 phase 4: import type B, document tracking log (source type document_log).
-- Reuses the ICI2-37/38 batch, row, exception and mapping tables and the V23 columns (applied, review decision 'apply', whose scope
-- check already names document_log). The import never writes application_requirements itself: commit calls RequirementService.update,
-- so campus scope, the item version, verifier attribution, the application lock shared with enrollment confirmation and the
-- requirement audit trail are the checklist workflow's own. Writing document states needs imports.enrollment.manage (V23), the same
-- permission as section allocations, and the called service demands enrollment.manage again at the same campus.
-- The real document log has not been received; the seeded mapping is an ASSUMED default (real_file_validated=false) whose value maps
-- are empty: no legacy document or state label is translated until an administrator maps it.

ALTER TABLE import_column_mappings DROP CONSTRAINT import_column_mappings_source_type_check;
ALTER TABLE import_column_mappings ADD CONSTRAINT import_column_mappings_source_type_check
 CHECK(source_type IN ('legacy_student_csv','legacy_grade_matrix_csv','section_allocation','document_log'));
ALTER TABLE import_batches DROP CONSTRAINT import_batches_source_type_check;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_source_type_check
 CHECK(source_type IN ('legacy_student_csv','legacy_grade_matrix_csv','section_allocation','document_log'));

-- Assumed default layout: one student and one document per row (layout long). Aliases are guesses.
INSERT INTO import_column_mappings(source_type,name,is_default,real_file_validated,mapping,settings) VALUES (
 'document_log','Assumed document log default (unvalidated)',true,false,
 '{"fields":{
   "studentNumber":{"required":false,"aliases":["student_number","student_no","studentno","student_id","id_number","id_no"]},
   "lrn":{"required":false,"aliases":["lrn","legacy_lrn","learner_reference_number"]},
   "applicationNumber":{"required":false,"aliases":["application_number","application_no","app_no"]},
   "lastName":{"required":false,"aliases":["last_name","lastname","surname","family_name"]},
   "firstName":{"required":false,"aliases":["first_name","firstname","given_name"]},
   "document":{"required":true,"aliases":["document","requirement","document_type"]},
   "status":{"required":true,"aliases":["status","document_status"]},
   "date":{"required":false,"aliases":["date","date_received","date_verified","log_date"]},
   "note":{"required":false,"aliases":["note","notes","remarks"]},
   "term":{"required":false,"aliases":["term","semester","sem"]},
   "schoolYear":{"required":false,"aliases":["school_year","sy","academic_year"]}
  },
  "valueMaps":{"document":{},"status":{},"term":{},"schoolYear":{}}}',
 '{"encoding":"UTF-8","delimiter":",","headerRow":1,"layout":"long","repeatedEntries":"reject","dateFormats":["yyyy-MM-dd"],"ignoredColumns":[]}');
