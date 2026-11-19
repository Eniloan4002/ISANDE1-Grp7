-- ICI2-37 phase 3: registrar review decisions (link to an existing student, distinct, skip) on staged import rows.
-- Builds on the unpublished V19 import_rows table.
ALTER TABLE import_rows ADD COLUMN matched_student_id bigint REFERENCES students(id);
ALTER TABLE import_rows DROP CONSTRAINT import_rows_review_decision_check;
ALTER TABLE import_rows ADD CONSTRAINT import_rows_review_decision_check CHECK(review_decision IN ('same_person','distinct','skip'));
-- same_person links the row to the chosen existing student; the other decisions never carry a link.
ALTER TABLE import_rows ADD CONSTRAINT import_row_link_matches_decision CHECK(coalesce(review_decision='same_person',false)=(matched_student_id IS NOT NULL));
-- skip is the only decision that skips; reviewed rows are never invalid or still flagged.
ALTER TABLE import_rows ADD CONSTRAINT import_row_skip_matches_decision CHECK((status='skipped')=coalesce(review_decision='skip',false));
ALTER TABLE import_rows ADD CONSTRAINT import_row_review_status CHECK(review_decision IS NULL OR status IN ('valid','skipped','imported'));
-- A linked row points at an existing student, so only rows that created a student must be unique per student.
DROP INDEX import_rows_student_once;
CREATE UNIQUE INDEX import_rows_student_created_once ON import_rows(student_id) WHERE student_id IS NOT NULL AND review_decision IS DISTINCT FROM 'same_person';
CREATE INDEX import_rows_student ON import_rows(student_id) WHERE student_id IS NOT NULL;
