-- ICI2-39 phase 2: shared foundation for spreadsheet import and deterministic cleaning.
-- Reuses the ICI2-37/38 batch and row tables; adds only what cleaning and a second source format need.
-- Cleaning of trivial (lossless whitespace/Unicode) changes is only COUNTED per batch; every other change is LOGGED per row
-- with the raw and cleaned value and the rule applied, so review can show it. The log holds personal values, so it is kept
-- out of audit_logs exactly like raw_values and normalized_values.

-- csv for every batch staged before and after this migration until a spreadsheet reader exists.
ALTER TABLE import_batches ADD COLUMN source_format varchar(10) NOT NULL DEFAULT 'csv'
 CONSTRAINT import_batches_source_format_check CHECK(source_format IN ('csv','xlsx'));
-- Rule id to number of cells it changed, for the whole batch (trivial and non-trivial rules). Counts only, no values.
ALTER TABLE import_batches ADD COLUMN cleaning_summary jsonb
 CONSTRAINT import_batches_cleaning_summary_check CHECK(cleaning_summary IS NULL OR jsonb_typeof(cleaning_summary)='object');
-- Non-trivial cleaning changes of one row: [{field, column, rule, before, after}], capped per row by the application.
ALTER TABLE import_rows ADD COLUMN cleaning jsonb
 CONSTRAINT import_rows_cleaning_check CHECK(cleaning IS NULL OR jsonb_typeof(cleaning)='array');

-- Same function as V19 plus the new column; the existing record_audit trigger keeps pointing at it.
CREATE OR REPLACE FUNCTION import_row_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint := nullif(current_setting('app.actor_id',true),'')::bigint; before_row jsonb; after_row jsonb;
BEGIN
 IF TG_OP<>'INSERT' THEN before_row:=to_jsonb(OLD)-'raw_values'-'normalized_values'-'cleaning'; END IF;
 IF TG_OP<>'DELETE' THEN after_row:=to_jsonb(NEW)-'raw_values'-'normalized_values'-'cleaning'; END IF;
 INSERT INTO audit_logs(table_name,record_id,operation,old_data,new_data,actor_id)
 VALUES(TG_TABLE_NAME,jsonb_build_object('id',CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END)::text,TG_OP,before_row,after_row,actor);
 RETURN NULL;
END $$;
