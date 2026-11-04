ALTER TABLE students ADD COLUMN campus_id bigint REFERENCES campuses(id);
ALTER TABLE students ADD COLUMN program_id bigint REFERENCES programs(id);
ALTER TABLE students ADD COLUMN voucher_code varchar(20) REFERENCES vouchers(code);
ALTER TABLE students ADD COLUMN track varchar(20) CHECK(track IN ('ACADEMIC','TVL','TVET'));
ALTER TABLE students ADD COLUMN previous_school_name text;
ALTER TABLE students ADD COLUMN previous_school_id varchar(50);
ALTER TABLE students ADD COLUMN previous_school_address text;
ALTER TABLE students ADD COLUMN household_income_bracket varchar(100);
ALTER TABLE students ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK(version>=0);
ALTER TABLE students ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE students ADD CONSTRAINT student_names_nonblank CHECK(btrim(first_name)<>'' AND btrim(last_name)<>'');
CREATE INDEX students_campus_directory ON students(campus_id,last_name,first_name,id);
CREATE INDEX students_program ON students(program_id);
-- Historical identities remain intact; assign their verified campus/program
-- explicitly before exposing them through the new campus-scoped API.
