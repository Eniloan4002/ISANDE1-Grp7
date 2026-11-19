INSERT INTO permissions(code,description) VALUES ('documents.manage','Configure and issue approved academic documents');
INSERT INTO role_permissions(role_code,permission_code) VALUES ('ROLE_ADMIN','documents.manage'),('ROLE_REGISTRAR','documents.manage');

-- No presumed national template, Special Order format, or school approval is seeded.
CREATE TABLE official_document_policies (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 campus_id bigint NOT NULL REFERENCES campuses(id), program_id bigint NOT NULL REFERENCES programs(id),
 document_type varchar(15) NOT NULL CHECK(document_type IN ('FORM137','TOR')),
 institution_name text NOT NULL, institution_address text NOT NULL, signatories text NOT NULL,
 template_reference text NOT NULL, so_required boolean NOT NULL,
 so_prefix varchar(60) NOT NULL DEFAULT '', so_max_length integer NOT NULL CHECK(so_max_length BETWEEN 1 AND 120),
 version integer NOT NULL DEFAULT 0 CHECK(version>=0),
 approval_reference text, approved_by bigint REFERENCES users(id), approved_at timestamptz,
 created_by bigint NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT current_timestamp,
 updated_by bigint NOT NULL REFERENCES users(id), updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 UNIQUE(campus_id,program_id,document_type),
 CHECK(num_nonnulls(approval_reference,approved_by,approved_at) IN (0,3))
);
CREATE TABLE student_special_orders (
 enrollment_id bigint PRIMARY KEY REFERENCES enrollments(id),
 number varchar(120) NOT NULL CHECK(length(btrim(number))>0), issued_on date NOT NULL, issuing_authority text NOT NULL,
 version integer NOT NULL DEFAULT 0 CHECK(version>=0), updated_by bigint NOT NULL REFERENCES users(id),
 updated_at timestamptz NOT NULL DEFAULT current_timestamp,created_by bigint NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE TRIGGER official_document_policy_audit AFTER INSERT OR UPDATE OR DELETE ON official_document_policies FOR EACH ROW EXECUTE FUNCTION audit_change();
CREATE TRIGGER student_special_order_audit AFTER INSERT OR UPDATE OR DELETE ON student_special_orders FOR EACH ROW EXECUTE FUNCTION audit_change();

CREATE TABLE official_document_generations (
 id uuid PRIMARY KEY, request_key uuid NOT NULL UNIQUE, document_request_id bigint NOT NULL UNIQUE REFERENCES document_requests(id),
 student_id bigint NOT NULL REFERENCES students(id), campus_id bigint NOT NULL REFERENCES campuses(id), program_id bigint NOT NULL REFERENCES programs(id),
 document_type varchar(15) NOT NULL CHECK(document_type IN ('FORM137','TOR')),
 policy_id bigint NOT NULL REFERENCES official_document_policies(id), policy_version integer NOT NULL,
 snapshot jsonb NOT NULL, pdf bytea NOT NULL CHECK(octet_length(pdf) BETWEEN 1 AND 5000000),
 sha256 varchar(64) NOT NULL, generated_by bigint NOT NULL REFERENCES users(id), generated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint NOT NULL REFERENCES users(id),created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX official_documents_student ON official_document_generations(student_id,generated_at,id);
CREATE FUNCTION official_document_generation_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO audit_logs(table_name,record_id,operation,new_data,actor_id)
 VALUES(TG_TABLE_NAME,NEW.id::text,TG_OP,to_jsonb(NEW)-'pdf'-'snapshot',NEW.generated_by);
 RETURN NEW;
END $$;
CREATE TRIGGER official_document_generation_audit AFTER INSERT ON official_document_generations FOR EACH ROW EXECUTE FUNCTION official_document_generation_audit();
CREATE TRIGGER official_document_generation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON official_document_generations FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_mutation();
