-- Version term-scoped teacher assignments and adviser withdrawal decisions.
ALTER TABLE class_offerings
  ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);

ALTER TABLE withdrawal_clearances
  ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0);

CREATE INDEX class_offerings_teacher_term ON class_offerings(teacher_id,term_id);
CREATE INDEX sections_adviser_term ON sections(adviser_id,term_id) WHERE adviser_id IS NOT NULL;
CREATE INDEX withdrawal_adviser_pending ON withdrawal_clearances(withdrawal_id)
  WHERE office='adviser' AND status='pending';
