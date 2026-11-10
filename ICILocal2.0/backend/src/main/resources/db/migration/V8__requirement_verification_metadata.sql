ALTER TABLE application_requirements
 ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version >= 0),
 ADD COLUMN notes text CHECK(notes IS NULL OR char_length(notes) <= 2000),
 ADD COLUMN verified_at timestamptz;

-- Existing V3 metadata and V2 audit triggers already cover these new fields.
-- Do not seed checklist rows on reads or manufacture historical verifiers.
CREATE INDEX requirement_audit_application ON audit_logs
 ((coalesce(new_data->>'application_id',old_data->>'application_id')),id DESC)
 WHERE table_name='application_requirements';
