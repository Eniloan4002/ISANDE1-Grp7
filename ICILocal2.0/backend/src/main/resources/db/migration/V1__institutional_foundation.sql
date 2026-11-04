-- PostgreSQL 17+. Run via Flyway once; migrations are append-only after release.
CREATE TABLE campuses (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 code varchar(20) NOT NULL UNIQUE, name text NOT NULL,
 active boolean NOT NULL DEFAULT true
);
CREATE TABLE users (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 username varchar(100) NOT NULL, email text NOT NULL,
 full_name text NOT NULL, password_hash text NOT NULL,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id)
);
CREATE UNIQUE INDEX users_username_normalized ON users(lower(username));
CREATE UNIQUE INDEX users_email_normalized ON users(lower(email));
CREATE TABLE roles (code varchar(30) PRIMARY KEY, name text NOT NULL);
CREATE TABLE permissions (code varchar(60) PRIMARY KEY, description text NOT NULL);
CREATE TABLE role_permissions (
 role_code varchar(30) REFERENCES roles(code),
 permission_code varchar(60) REFERENCES permissions(code),
 PRIMARY KEY(role_code, permission_code)
);
-- Campus scope is explicit. Multi-campus staff receive one grant per campus.
CREATE TABLE user_role_grants (
 user_id bigint REFERENCES users(id), role_code varchar(30) REFERENCES roles(code),
 campus_id bigint REFERENCES campuses(id),
 granted_by bigint REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(user_id, role_code, campus_id)
);
CREATE TABLE academic_years (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 label varchar(20) NOT NULL UNIQUE, starts_on date NOT NULL, ends_on date NOT NULL,
 CHECK(ends_on > starts_on)
);
CREATE TABLE academic_terms (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 academic_year_id bigint NOT NULL REFERENCES academic_years(id),
 code varchar(20) NOT NULL, name text NOT NULL,
 starts_on date NOT NULL, ends_on date NOT NULL,
 UNIQUE(academic_year_id, code), CHECK(ends_on >= starts_on)
);
CREATE TABLE grading_periods (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 term_id bigint NOT NULL REFERENCES academic_terms(id), code varchar(20) NOT NULL,
 sequence smallint NOT NULL CHECK(sequence > 0),
 UNIQUE(term_id, code), UNIQUE(term_id, sequence), UNIQUE(id, term_id)
);
CREATE TABLE programs (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 code varchar(30) NOT NULL UNIQUE, name text NOT NULL,
 category varchar(10) NOT NULL CHECK(category IN ('SHS','TVET'))
);
CREATE TABLE vouchers (code varchar(20) PRIMARY KEY, name text NOT NULL);
CREATE TABLE sections (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 campus_id bigint NOT NULL REFERENCES campuses(id),
 term_id bigint NOT NULL REFERENCES academic_terms(id),
 program_id bigint NOT NULL REFERENCES programs(id),
 name text NOT NULL, grade_level varchar(30) NOT NULL, room text,
 capacity integer NOT NULL CHECK(capacity > 0), adviser_id bigint REFERENCES users(id),
 UNIQUE(campus_id, term_id, name), UNIQUE(id, campus_id, term_id, program_id), UNIQUE(id, term_id)
);
-- A permanent student identity survives applications, transfers and academic years.
CREATE TABLE students (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 student_number varchar(40) NOT NULL UNIQUE,
 lrn varchar(12) UNIQUE CHECK(lrn IS NULL OR lrn ~ '^[0-9]{12}$'),
 first_name text NOT NULL, middle_name text, last_name text NOT NULL, suffix text,
 birth_date date NOT NULL, sex varchar(30), phone text, email text,
 address jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id)
);
CREATE INDEX students_name_birth ON students(lower(last_name), lower(first_name), birth_date);
CREATE TABLE guardians (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 student_id bigint NOT NULL REFERENCES students(id),
 full_name text NOT NULL, relationship text NOT NULL, phone text, address text,
 is_primary boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX guardians_one_primary ON guardians(student_id) WHERE is_primary;
-- Applicant LRNs are deliberately NOT unique: duplicates must enter a resolution queue.
CREATE TABLE applications (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 application_number varchar(40) NOT NULL UNIQUE,
 campus_id bigint NOT NULL REFERENCES campuses(id),
 term_id bigint NOT NULL REFERENCES academic_terms(id),
 program_id bigint NOT NULL REFERENCES programs(id),
 student_id bigint REFERENCES students(id),
 category varchar(20) NOT NULL CHECK(category IN ('new','transferee','returnee','continuing')),
 first_name text NOT NULL, middle_name text, last_name text NOT NULL,
 birth_date date NOT NULL, lrn varchar(12) CHECK(lrn IS NULL OR lrn ~ '^[0-9]{12}$'),
 contact_phone text, email text, previous_school text,
 profile jsonb NOT NULL DEFAULT '{}', voucher_code varchar(20) REFERENCES vouchers(code),
 status varchar(24) NOT NULL DEFAULT 'submitted' CHECK(status IN ('submitted','duplicate_flag','under_review','pre_enlisted','enrolled','rejected','withdrawn')),
 submitted_at timestamptz NOT NULL DEFAULT current_timestamp,
 updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 created_by bigint REFERENCES users(id), updated_by bigint REFERENCES users(id),
 UNIQUE(id, campus_id, term_id, program_id)
);
CREATE INDEX applications_queue ON applications(campus_id, term_id, status);
CREATE INDEX applications_lrn ON applications(lrn);
CREATE TABLE duplicate_cases (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 application_id bigint NOT NULL REFERENCES applications(id),
 matched_application_id bigint REFERENCES applications(id),
 matched_student_id bigint REFERENCES students(id),
 evidence jsonb NOT NULL DEFAULT '{}',
 status varchar(20) NOT NULL DEFAULT 'open' CHECK(status IN ('open','merged','distinct','rejected')),
 resolved_by bigint REFERENCES users(id), resolved_at timestamptz, reason text,
 CHECK(num_nonnulls(matched_application_id, matched_student_id) = 1),
 CHECK(matched_application_id IS NULL OR matched_application_id <> application_id),
 CHECK(status = 'open' OR (resolved_by IS NOT NULL AND resolved_at IS NOT NULL AND nullif(btrim(reason),'') IS NOT NULL))
);
CREATE TABLE requirement_types (
 code varchar(30) PRIMARY KEY, name text NOT NULL, mandatory boolean NOT NULL DEFAULT true
);
CREATE TABLE application_requirements (
 application_id bigint REFERENCES applications(id),
 requirement_code varchar(30) REFERENCES requirement_types(code),
 status varchar(20) NOT NULL DEFAULT 'missing' CHECK(status IN ('submitted','missing','pending','invalid')),
 storage_key text, original_filename text, verified_by bigint REFERENCES users(id),
 updated_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(application_id, requirement_code)
);
CREATE TABLE orientation_attendance (
 application_id bigint REFERENCES applications(id), attendance_date date NOT NULL,
 status varchar(10) NOT NULL CHECK(status IN ('present','absent','excused','pending')),
 recorded_by bigint REFERENCES users(id), recorded_at timestamptz NOT NULL DEFAULT current_timestamp,
 PRIMARY KEY(application_id, attendance_date)
);
CREATE TABLE enrollments (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 student_id bigint NOT NULL REFERENCES students(id),
 application_id bigint NOT NULL UNIQUE,
 campus_id bigint NOT NULL, term_id bigint NOT NULL, program_id bigint NOT NULL,
 section_id bigint,
 status varchar(20) NOT NULL DEFAULT 'provisional' CHECK(status IN ('provisional','enrolled','withdrawn','completed')),
 attendance_verified_at timestamptz, attendance_verified_by bigint REFERENCES users(id),
 confirmed_at timestamptz, confirmed_by bigint REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 UNIQUE(student_id, term_id), UNIQUE(id, section_id, term_id),
 FOREIGN KEY(application_id, campus_id, term_id, program_id) REFERENCES applications(id, campus_id, term_id, program_id),
 FOREIGN KEY(section_id, campus_id, term_id, program_id) REFERENCES sections(id, campus_id, term_id, program_id),
 CHECK(status <> 'enrolled' OR (confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL AND attendance_verified_at IS NOT NULL AND attendance_verified_by IS NOT NULL))
);
CREATE INDEX enrollments_section ON enrollments(section_id);
CREATE TABLE subjects (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 code varchar(40) NOT NULL UNIQUE, name text NOT NULL,
 units numeric(5,2) NOT NULL DEFAULT 1 CHECK(units > 0)
);
CREATE TABLE class_offerings (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 section_id bigint NOT NULL REFERENCES sections(id), term_id bigint NOT NULL,
 subject_id bigint NOT NULL REFERENCES subjects(id), teacher_id bigint NOT NULL REFERENCES users(id),
 UNIQUE(section_id, subject_id), UNIQUE(id, section_id, term_id), UNIQUE(id, term_id),
 FOREIGN KEY(section_id, term_id) REFERENCES sections(id, term_id)
);
-- A term consistency trigger below validates offerings against their section.
CREATE TABLE class_memberships (
 class_id bigint NOT NULL, enrollment_id bigint NOT NULL,
 section_id bigint NOT NULL, term_id bigint NOT NULL,
 PRIMARY KEY(class_id, enrollment_id), UNIQUE(class_id, enrollment_id, term_id),
 FOREIGN KEY(class_id, section_id, term_id) REFERENCES class_offerings(id, section_id, term_id),
 FOREIGN KEY(enrollment_id, section_id, term_id) REFERENCES enrollments(id, section_id, term_id)
);
CREATE TABLE grading_windows (
 class_id bigint NOT NULL REFERENCES class_offerings(id),
 grading_period_id bigint NOT NULL REFERENCES grading_periods(id),
 term_id bigint NOT NULL,
 deadline timestamptz, locked_at timestamptz, locked_by bigint REFERENCES users(id),
 PRIMARY KEY(class_id, grading_period_id),
 FOREIGN KEY(class_id, term_id) REFERENCES class_offerings(id, term_id),
 FOREIGN KEY(grading_period_id, term_id) REFERENCES grading_periods(id, term_id)
);
CREATE TABLE grades (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 class_id bigint NOT NULL, enrollment_id bigint NOT NULL,
 term_id bigint NOT NULL, grading_period_id bigint NOT NULL,
 value numeric(5,2) CHECK(value BETWEEN 0 AND 100),
 mark varchar(12) CHECK(mark IN ('INC','NG','DRP')),
 state varchar(12) NOT NULL DEFAULT 'draft' CHECK(state IN ('draft','submitted','finalized')),
 version integer NOT NULL DEFAULT 0 CHECK(version >= 0),
 updated_at timestamptz NOT NULL DEFAULT current_timestamp, updated_by bigint REFERENCES users(id),
 UNIQUE(class_id, enrollment_id, grading_period_id),
 FOREIGN KEY(class_id, enrollment_id, term_id) REFERENCES class_memberships(class_id, enrollment_id, term_id),
 FOREIGN KEY(grading_period_id, term_id) REFERENCES grading_periods(id, term_id),
 CHECK(num_nonnulls(value, mark) <= 1),
 CHECK(state = 'draft' OR num_nonnulls(value, mark) = 1)
);
CREATE TABLE grade_corrections (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 grade_id bigint NOT NULL REFERENCES grades(id),
 original_value numeric(5,2) CHECK(original_value BETWEEN 0 AND 100),
 original_mark varchar(12) CHECK(original_mark IN ('INC','NG','DRP')),
 proposed_value numeric(5,2) NOT NULL CHECK(proposed_value BETWEEN 0 AND 100),
 justification text NOT NULL CHECK(length(btrim(justification)) > 0),
 evidence_storage_key text NOT NULL,
 requested_by bigint NOT NULL REFERENCES users(id), requested_at timestamptz NOT NULL DEFAULT current_timestamp,
 status varchar(15) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected','applied')),
 reviewed_by bigint REFERENCES users(id), reviewed_at timestamptz, review_reason text,
 CHECK(num_nonnulls(original_value, original_mark) = 1),
 CHECK(status = 'pending' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE TABLE incomplete_cases (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 grade_id bigint NOT NULL REFERENCES grades(id), description text NOT NULL,
 status varchar(15) NOT NULL DEFAULT 'open' CHECK(status IN ('open','following_up','resolved')),
 resolved_at timestamptz, resolved_by bigint REFERENCES users(id),
 CHECK(status <> 'resolved' OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL))
);
CREATE UNIQUE INDEX incomplete_one_open ON incomplete_cases(grade_id) WHERE status <> 'resolved';
CREATE TABLE withdrawals (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 enrollment_id bigint NOT NULL UNIQUE REFERENCES enrollments(id), reason text NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT current_timestamp,
 status varchar(15) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected','completed'))
);
CREATE TABLE withdrawal_clearances (
 withdrawal_id bigint REFERENCES withdrawals(id),
 office varchar(15) CHECK(office IN ('adviser','principal','cashier')),
 status varchar(15) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','cleared','rejected')),
 acted_by bigint REFERENCES users(id), acted_at timestamptz, notes text,
 PRIMARY KEY(withdrawal_id, office),
 CHECK(status = 'pending' OR (acted_by IS NOT NULL AND acted_at IS NOT NULL))
);
CREATE TABLE student_notes (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 student_id bigint NOT NULL REFERENCES students(id),
 enrollment_id bigint REFERENCES enrollments(id),
 category varchar(20) NOT NULL CHECK(category IN ('adviser','guidance','incomplete')),
 body text NOT NULL CHECK(length(btrim(body)) > 0),
 author_id bigint NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE TABLE document_requests (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 student_id bigint NOT NULL REFERENCES students(id),
 document_type varchar(15) NOT NULL CHECK(document_type IN ('FORM137','FORM138','TOR')),
 status varchar(15) NOT NULL DEFAULT 'requested' CHECK(status IN ('requested','blocked','approved','issued')),
 special_order_number text, requested_at timestamptz NOT NULL DEFAULT current_timestamp,
 issued_at timestamptz, issued_by bigint REFERENCES users(id), storage_key text,
 CHECK(status <> 'issued' OR (issued_at IS NOT NULL AND issued_by IS NOT NULL AND storage_key IS NOT NULL))
);
CREATE TABLE import_batches (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 source_name text NOT NULL, checksum varchar(64) NOT NULL,
 status varchar(15) NOT NULL DEFAULT 'staged' CHECK(status IN ('staged','validated','imported','reconciled','failed')),
 source_count integer NOT NULL CHECK(source_count >= 0),
 imported_count integer NOT NULL DEFAULT 0 CHECK(imported_count >= 0),
 started_at timestamptz NOT NULL DEFAULT current_timestamp,
 signed_off_by bigint REFERENCES users(id), signed_off_at timestamptz,
 CHECK(status <> 'reconciled' OR (source_count = imported_count AND signed_off_by IS NOT NULL AND signed_off_at IS NOT NULL))
);
CREATE TABLE import_exceptions (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 batch_id bigint NOT NULL REFERENCES import_batches(id), row_number integer CHECK(row_number > 0),
 error_code text NOT NULL, detail text NOT NULL, resolved boolean NOT NULL DEFAULT false
);
CREATE TABLE audit_logs (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 table_name text NOT NULL, record_id text NOT NULL, operation varchar(10) NOT NULL,
 old_data jsonb, new_data jsonb, actor_id bigint REFERENCES users(id),
 occurred_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX audit_record_history ON audit_logs(table_name, record_id, occurred_at DESC);
CREATE INDEX audit_actor_history ON audit_logs(actor_id, occurred_at DESC);
