-- Narrow write authorities: reporting/read-only permissions do not grant clearance writes.
INSERT INTO permissions(code,description) VALUES
 ('withdrawals.approve','Approve assigned-campus withdrawal requests as Principal'),
 ('withdrawals.clear','Record assigned-campus cashier clearance references');
INSERT INTO role_permissions(role_code,permission_code) VALUES
 ('ROLE_PRINCIPAL','withdrawals.approve'),('ROLE_CASHIER_REF','withdrawals.clear');
ALTER TABLE withdrawals ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version>=0);
CREATE INDEX withdrawals_status_requested ON withdrawals(status,requested_at,id);
-- Existing requests gain the remaining offices without changing decided endorsements.
INSERT INTO withdrawal_clearances(withdrawal_id,office)
 SELECT w.id,o.office FROM withdrawals w CROSS JOIN (VALUES ('adviser'),('principal'),('cashier')) o(office)
 ON CONFLICT(withdrawal_id,office) DO NOTHING;

-- Both paths serialize on permanent identity, after withdrawal service enrollment/section locks.
-- READ COMMITTED is required so the guard reads a fresh snapshot after waiting for this lock.
CREATE FUNCTION lock_withdrawal_student() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF current_setting('transaction_isolation') <> 'read committed' THEN
   RAISE EXCEPTION 'Withdrawal creation requires READ COMMITTED isolation' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM students WHERE id=(SELECT student_id FROM enrollments WHERE id=NEW.enrollment_id) FOR UPDATE;
 RETURN NEW;
END $$;
CREATE TRIGGER withdrawal_student_lock BEFORE INSERT ON withdrawals
 FOR EACH ROW EXECUTE FUNCTION lock_withdrawal_student();

-- Enforce the credential-release boundary independently of future document formatters.
CREATE FUNCTION guard_withdrawal_credential_release() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status IN ('approved','issued') THEN
   IF current_setting('transaction_isolation') <> 'read committed' THEN
     RAISE EXCEPTION 'Credential release requires READ COMMITTED isolation' USING ERRCODE='23514';
   END IF;
   PERFORM 1 FROM students WHERE id=NEW.student_id FOR UPDATE;
   IF EXISTS (
   SELECT 1 FROM withdrawals w JOIN enrollments e ON e.id=w.enrollment_id
   WHERE e.student_id=NEW.student_id AND w.status<>'completed'
 ) THEN
   RAISE EXCEPTION 'Credential release requires every withdrawal clearance to be completed' USING ERRCODE='23514';
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER withdrawal_release_guard BEFORE INSERT OR UPDATE ON document_requests
 FOR EACH ROW EXECUTE FUNCTION guard_withdrawal_credential_release();
