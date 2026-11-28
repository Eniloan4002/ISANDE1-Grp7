package ph.edu.ici;

import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ph.edu.ici.migrations.LocalMigrationBridge;
import static org.assertj.core.api.Assertions.*;

class MigrationReconciliationTest {
    DriverManagerDataSource database(){
        String name="migration_"+UUID.randomUUID().toString().replace("-","");
        var root=new JdbcTemplate(new DriverManagerDataSource(PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"),"postgres",""));root.execute("CREATE DATABASE "+name);
        return new DriverManagerDataSource(PostgresSupport.DATABASE.getJdbcUrl("postgres",name),"postgres","");
    }
    @Test void publishedUpstreamV7UpgradesToV21WithoutLosingExistingAccounts(){
        var ds=database();Flyway.configure().dataSource(ds).target("7").load().migrate();var db=new JdbcTemplate(ds);
        db.update("INSERT INTO users(username,email,full_name,password_hash,token_version) VALUES('existing','existing@example.invalid','Existing account','retained-hash',4)");
        var latest=Flyway.configure().dataSource(ds).load();LocalMigrationBridge.reconcile(latest,false);assertThat(latest.migrate().migrationsExecuted).isEqualTo(18);latest.validate();
        assertThat(db.queryForObject("SELECT password_hash FROM users WHERE username='existing'",String.class)).isEqualTo("retained-hash");
        assertThat(db.queryForObject("SELECT token_version FROM users WHERE username='existing'",Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT count(*) FROM enrollment_attendance_windows",Integer.class)).isZero();
    }
    @Test void publishedV12PreservesExistingClassLockAndConfiguration(){
        var ds=database();Flyway.configure().dataSource(ds).target("12").load().migrate();var db=new JdbcTemplate(ds);
        long staff=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES ('existing','existing@example.invalid','Existing reviewer','retained-hash') RETURNING id",Long.class);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES ('Existing year','2026-06-01','2027-05-31') RETURNING id",Long.class);
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'T1','Existing term','2026-06-01','2026-12-31') RETURNING id",Long.class,year);
        long period=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1) RETURNING id",Long.class,term);
        long section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) SELECT c.id,?,p.id,'Existing section','11',30 FROM campuses c,programs p WHERE c.code='ILIGAN' AND p.code='STEM' RETURNING sections.id",Long.class,term);
        long subject=db.queryForObject("INSERT INTO subjects(code,name) VALUES ('EXISTING','Existing subject') RETURNING id",Long.class);
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject,staff);
        db.update("INSERT INTO grading_windows(class_id,grading_period_id,term_id,deadline,locked_at,locked_by) VALUES (?,?,?,now(),now(),?)",offering,period,term,staff);
        var before=db.queryForMap("SELECT deadline,locked_at,locked_by FROM grading_windows WHERE class_id=?",offering);
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(13);latest.validate();
        assertThat(db.queryForMap("SELECT deadline,locked_at,locked_by FROM grading_windows WHERE class_id=?",offering)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM section_grading_windows",Integer.class)).isZero();
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    @Test void publishedV13PreservesSubmittedIncAndBackfillsItsCase(){
        var ds=database();Flyway.configure().dataSource(ds).target("13").load().migrate();var db=new JdbcTemplate(ds);
        long staff=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES ('existing','existing@example.invalid','Existing reviewer','retained-hash') RETURNING id",Long.class);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES ('Existing year','2026-06-01','2027-05-31') RETURNING id",Long.class);
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'T1','Existing term','2026-06-01','2026-12-31') RETURNING id",Long.class,year);
        long period=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1) RETURNING id",Long.class,term);
        long section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) SELECT c.id,?,p.id,'Existing section','11',30 FROM campuses c,programs p WHERE c.code='ILIGAN' AND p.code='STEM' RETURNING sections.id",Long.class,term);
        long subject=db.queryForObject("INSERT INTO subjects(code,name) VALUES ('EXISTING','Existing subject') RETURNING id",Long.class);
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject,staff);
        db.update("INSERT INTO grading_windows(class_id,grading_period_id,term_id,deadline,locked_at,locked_by) VALUES (?,?,?,now(),now(),?)",offering,period,term,staff);
        var before=db.queryForMap("SELECT deadline,locked_at,locked_by FROM grading_windows WHERE class_id=?",offering);
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES ('OLD-INC','Existing','Student','2009-01-01') RETURNING id",Long.class);
        long application=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date) SELECT 'OLD-APP',campus_id,term_id,program_id,?,'new','Existing','Student','2009-01-01' FROM sections WHERE id=? RETURNING id",Long.class,student,section);
        long enrollment=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) SELECT ?,?,campus_id,term_id,program_id,id,'provisional' FROM sections WHERE id=? RETURNING id",Long.class,student,application,section);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",offering,enrollment,section,term);
        long grade=db.queryForObject("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,mark,state,version) VALUES (?,?,?,?,'INC','submitted',3) RETURNING id",Long.class,offering,enrollment,term,period);
        long correction=db.queryForObject("INSERT INTO grade_corrections(grade_id,original_mark,proposed_value,justification,evidence_storage_key,requested_by) VALUES (?,'INC',85,'Existing justification','legacy-evidence',?) RETURNING id",Long.class,grade,staff);
        var gradeBefore=db.queryForMap("SELECT * FROM grades WHERE id=?",grade);
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(12);latest.validate();
        assertThat(db.queryForMap("SELECT deadline,locked_at,locked_by FROM grading_windows WHERE class_id=?",offering)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM section_grading_windows",Integer.class)).isZero();
        assertThat(db.queryForMap("SELECT * FROM grades WHERE id=?",grade)).isEqualTo(gradeBefore);
        assertThat(db.queryForObject("SELECT count(*) FROM incomplete_cases WHERE grade_id=? AND status='open' AND version=0",Integer.class,grade)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT grade_version FROM grade_corrections WHERE id=?",Integer.class,correction)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT evidence_storage_key FROM grade_corrections WHERE id=?",String.class,correction)).isEqualTo("legacy-evidence");
        assertThat(db.queryForObject("SELECT count(*) FROM guidance_cases",Integer.class)).isZero();
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    @Test void publishedV16PreservesAcademicWithdrawalAndConfidentialGuidanceData(){
        var ds=database();Flyway.configure().dataSource(ds).target("16").load().migrate();var db=new JdbcTemplate(ds);
        long staff=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES ('prior','prior@example.invalid','Prior staff','retained-hash') RETURNING id",Long.class);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES ('Prior year','2026-06-01','2027-05-31') RETURNING id",Long.class);
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'T1','Prior term','2026-06-01','2026-12-31') RETURNING id",Long.class,year);
        long period=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1) RETURNING id",Long.class,term);
        long section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) SELECT c.id,?,p.id,'Prior section','11',30 FROM campuses c,programs p WHERE c.code='ILIGAN' AND p.code='STEM' RETURNING sections.id",Long.class,term);
        long subject=db.queryForObject("INSERT INTO subjects(code,name,units) VALUES ('PRIOR','Prior subject',2) RETURNING id",Long.class);
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject,staff);
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES ('PRIOR-STUDENT','Prior','Student','2009-01-01') RETURNING id",Long.class);
        long application=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date) SELECT 'PRIOR-APP',campus_id,term_id,program_id,?,'new','Prior','Student','2009-01-01' FROM sections WHERE id=? RETURNING id",Long.class,student,section);
        long enrollment=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) SELECT ?,?,campus_id,term_id,program_id,id,'provisional' FROM sections WHERE id=? RETURNING id",Long.class,student,application,section);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",offering,enrollment,section,term);
        long grade=db.queryForObject("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,mark,state,version) VALUES (?,?,?,?,'INC','submitted',3) RETURNING id",Long.class,offering,enrollment,term,period);
        long withdrawal=db.queryForObject("INSERT INTO withdrawals(enrollment_id,reason) VALUES (?,'Retained pending withdrawal') RETURNING id",Long.class,enrollment);
        long guidance=db.queryForObject("INSERT INTO guidance_cases(enrollment_id,reason) VALUES (?,'Retained confidential support case') RETURNING id",Long.class,enrollment);
        db.update("INSERT INTO guidance_case_notes(case_id,body,author_id) VALUES (?,'Retained confidential note',?)",guidance,staff);
        db.update("INSERT INTO guidance_policies(campus_id,term_id,failing_below,reason) SELECT campus_id,?,75,'Prior approved threshold reference' FROM sections WHERE id=?",term,section);
        db.update("INSERT INTO school_calendar_days(campus_id,term_id,school_date,is_school_day,reason) SELECT campus_id,?,'2026-06-02',true,'Prior school day' FROM sections WHERE id=?",term,section);
        db.update("INSERT INTO daily_academic_attendance(enrollment_id,attendance_date,status,note) VALUES (?,'2026-06-02','absent','Retained attendance note')",enrollment);
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("users","grades","incomplete_cases","withdrawals","guidance_cases","guidance_case_notes","guidance_policies","school_calendar_days","daily_academic_attendance","audit_logs"))before.put(table,db.queryForList("SELECT * FROM "+table));
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(9);latest.validate();
        for(var entry:before.entrySet()){
            var after=db.queryForList("SELECT * FROM "+entry.getKey());
            if(entry.getKey().equals("audit_logs"))assertThat(after).as("Existing audits remain append-only").containsAll(entry.getValue());
            else assertThat(after).as(entry.getKey()+" preserved through V16 upgrade").containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
        assertThat(db.queryForObject("SELECT count(*) FROM honor_policies",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM official_document_policies",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM official_document_generations",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT status FROM withdrawals WHERE id=?",String.class,withdrawal)).isEqualTo("pending");
        assertThat(db.queryForObject("SELECT value FROM grades WHERE id=?",java.math.BigDecimal.class,grade)).isNull();
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    @Test void publishedV18PreservesStudentsAndAddsLegacyImportStagingAndPermissions(){
        var ds=database();Flyway.configure().dataSource(ds).target("18").load().migrate();var db=new JdbcTemplate(ds);
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES ('PRE-V19','Prior','Student','2009-01-01') RETURNING id",Long.class);
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,source_count) VALUES ('prior.csv',repeat('a',64),3) RETURNING id",Long.class);
        db.update("INSERT INTO import_exceptions(batch_id,row_number,error_code,detail) VALUES (?,2,'PRIOR','Retained exception')",batch);
        var before=db.queryForList("SELECT * FROM students");
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(7);latest.validate();
        assertThat(db.queryForList("SELECT * FROM students")).containsExactlyInAnyOrderElementsOf(before);
        assertThat(db.queryForObject("SELECT source_type FROM import_batches WHERE id=?",String.class,batch)).isEqualTo("legacy_student_csv");
        assertThat(db.queryForObject("SELECT severity FROM import_exceptions WHERE batch_id=?",String.class,batch)).isEqualTo("error");
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows",Integer.class)).isZero();
        assertThat(db.queryForList("SELECT role_code||':'||permission_code FROM role_permissions WHERE permission_code LIKE 'imports.%'",String.class))
            .containsExactlyInAnyOrder("ROLE_ADMIN:imports.read","ROLE_ADMIN:imports.manage","ROLE_ADMIN:imports.mappings.manage","ROLE_REGISTRAR:imports.read","ROLE_REGISTRAR:imports.manage","ROLE_ADMIN:imports.grades.manage","ROLE_REGISTRAR:imports.grades.manage","ROLE_ADMIN:imports.enrollment.manage","ROLE_REGISTRAR:imports.enrollment.manage");
        assertThat(db.queryForMap("SELECT is_default,real_file_validated FROM import_column_mappings WHERE source_type='legacy_student_csv'")).containsEntry("is_default",true).containsEntry("real_file_validated",false);
        assertThat(db.queryForObject("SELECT mapping->'fields'->'campus'->>'required' FROM import_column_mappings WHERE is_default AND source_type='legacy_student_csv'",String.class)).isEqualTo("false");
        assertThat(db.queryForObject("SELECT campus_id FROM import_batches WHERE id=?",Long.class,batch)).as("pre-existing batches are not rewritten").isNull();
        assertThat(student).isPositive();assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    @Test void importStagingConstraintsRejectInconsistentRowsAndDuplicateFiles(){
        var ds=database();Flyway.configure().dataSource(ds).load().migrate();var db=new JdbcTemplate(ds);
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,source_count,status,valid_count,campus_id) SELECT 'a.csv',repeat('b',64),1,'validated',1,min(id) FROM campuses RETURNING id",Long.class);
        assertThatThrownBy(()->db.update("INSERT INTO import_batches(source_name,checksum,source_count,campus_id) SELECT 'copy.csv',repeat('b',64),1,min(id) FROM campuses")).hasMessageContaining("import_batch_checksum_once");
        assertThatThrownBy(()->db.update("INSERT INTO import_batches(source_name,checksum,source_count,status,valid_count,campus_id) SELECT 'c.csv',repeat('c',64),2,'validated',1,min(id) FROM campuses")).hasMessageContaining("import_batch_counts_balance");
        db.update("INSERT INTO import_rows(batch_id,row_number,status,normalized_values,raw_values) VALUES (?,2,'valid','{}','{\"lrn\":\"secret-raw\"}')",batch);
        assertThatThrownBy(()->db.update("INSERT INTO import_rows(batch_id,row_number,status,normalized_values) VALUES (?,2,'valid','{}')",batch)).hasMessageContaining("import_rows_batch_id_row_number_key");
        assertThatThrownBy(()->db.update("INSERT INTO import_rows(batch_id,row_number,status) VALUES (?,3,'imported')",batch)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        long reviewer=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES ('reviewer','reviewer@example.invalid','Synthetic reviewer','x') RETURNING id",Long.class);
        for(String reason:new String[]{null,"   "})
            assertThatThrownBy(()->db.update("INSERT INTO import_rows(batch_id,row_number,status,normalized_values,review_decision,reviewed_by,reviewed_at,review_reason) VALUES (?,9,'valid','{}','distinct',?,now(),?)",batch,reviewer,reason)).as("review decision needs a reason").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.update("INSERT INTO import_rows(batch_id,row_number,status,normalized_values,review_decision,reviewed_by,reviewed_at,review_reason) VALUES (?,9,'valid','{}','distinct',?,now(),'Different person')",batch,reviewer)).isEqualTo(1);
        long existing=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES ('V20-EXISTING','Linked','Student','2009-01-01') RETURNING id",Long.class);
        String base="INSERT INTO import_rows(batch_id,row_number,status,normalized_values,review_decision,reviewed_by,reviewed_at,review_reason,matched_student_id) VALUES (?,%d,%s,'{}',%s,?,%s,'Reason',%s)";
        assertThatThrownBy(()->db.update(base.formatted(20,"'skipped'","NULL","NULL","NULL"),batch,reviewer)).as("skipped needs the skip decision").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(base.formatted(21,"'valid'","'same_person'","now()","NULL"),batch,reviewer)).as("same_person needs a linked student").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(base.formatted(22,"'valid'","'distinct'","now()",Long.toString(existing)),batch,reviewer)).as("only same_person carries a link").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(base.formatted(23,"'invalid'","'skip'","now()","NULL"),batch,reviewer)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.update(base.formatted(24,"'valid'","'same_person'","now()",Long.toString(existing)),batch,reviewer)).isEqualTo(1);
        assertThat(db.update("INSERT INTO import_rows(batch_id,row_number,status,normalized_values,student_id) VALUES (?,25,'imported','{}',?)",batch,existing)).as("linked and created rows may share an existing student once only for created rows").isEqualTo(1);
        assertThatThrownBy(()->db.update("INSERT INTO import_rows(batch_id,row_number,status,normalized_values,student_id) VALUES (?,26,'imported','{}',?)",batch,existing)).as("a student is created by at most one row").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("INSERT INTO import_batches(source_name,checksum,source_count) VALUES ('nocampus.csv',repeat('d',64),1)")).hasMessageContaining("import_batch_campus_required");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND (new_data ? 'raw_values' OR new_data::text LIKE '%secret-raw%')",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND operation='INSERT'",Integer.class)).as("each inserted row is audited without personal values").isEqualTo(4);
    }
    @Test void publishedV20ImportRowsSurviveV21AndLegacyGradeStorageEnforcesProvenanceAndHidesValuesFromAudit(){
        var ds=database();Flyway.configure().dataSource(ds).target("20").load().migrate();var db=new JdbcTemplate(ds);
        long campus=db.queryForObject("SELECT min(id) FROM campuses",Long.class);
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES ('V21-STUDENT','Created','Student','2009-01-01') RETURNING id",Long.class);
        long oldBatch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,source_count,status,valid_count,campus_id) VALUES ('v20.csv',repeat('a',64),1,'validated',1,?) RETURNING id",Long.class,campus);
        db.update("INSERT INTO import_rows(batch_id,row_number,status,campus_id,normalized_values) VALUES (?,2,'valid',?,'{}')",oldBatch,campus);
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(5);latest.validate();
        assertThat(db.queryForObject("SELECT source_type FROM import_rows WHERE batch_id=?",String.class,oldBatch)).isEqualTo("legacy_student_csv");
        assertThat(db.queryForMap("SELECT is_default,real_file_validated FROM import_column_mappings WHERE source_type='legacy_grade_matrix_csv'")).containsEntry("is_default",true).containsEntry("real_file_validated",false);
        assertThat(db.queryForObject("SELECT is_default FROM import_column_mappings WHERE source_type='legacy_student_csv'",Boolean.class)).as("one default per source type").isTrue();
        assertThat(db.queryForList("SELECT role_code FROM role_permissions WHERE permission_code='imports.grades.manage'",String.class)).containsExactlyInAnyOrder("ROLE_ADMIN","ROLE_REGISTRAR");
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES ('2018-2019','2018-06-01','2019-03-31') RETURNING id",Long.class);
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','First term','2018-06-01','2018-10-31') RETURNING id",Long.class,year);
        long otherTerm=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S2','Second term','2018-11-01','2019-03-31') RETURNING id",Long.class,year);
        long q1=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1) RETURNING id",Long.class,term);
        long subject=db.queryForObject("INSERT INTO subjects(code,name) VALUES ('V21-MATH','Mathematics') RETURNING id",Long.class);
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,source_count,status,valid_count,campus_id,source_type) VALUES ('grades.csv',repeat('b',64),1,'validated',1,?,'legacy_grade_matrix_csv') RETURNING id",Long.class,campus);
        long gradeRow=db.queryForObject("INSERT INTO import_rows(batch_id,row_number,status,campus_id,source_type,normalized_values) VALUES (?,2,'valid',?,'legacy_grade_matrix_csv','{}') RETURNING id",Long.class,batch,campus);
        String insert="INSERT INTO legacy_grade_records(student_id,campus_id,term_id,grading_period_id,subject_id,record_kind,grade_level,value,mark,import_row_id,source_column) VALUES (?,?,?,?,?,?,'Grade 8',?,?,?,'MATH - Q1')";
        // A row only gains a student when it is committed; records cannot point at an uncommitted row.
        assertThatThrownBy(()->db.update(insert,student,campus,term,q1,subject,"period",new java.math.BigDecimal("88.50"),null,gradeRow)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        db.update("UPDATE import_rows SET status='imported',student_id=? WHERE id=?",student,gradeRow);
        assertThat(db.update(insert,student,campus,term,q1,subject,"period",new java.math.BigDecimal("88.50"),null,gradeRow)).isEqualTo(1);
        long second=db.queryForObject("INSERT INTO import_rows(batch_id,row_number,status,campus_id,source_type,normalized_values,student_id) VALUES (?,3,'imported',?,'legacy_grade_matrix_csv','{}',?) RETURNING id",Long.class,batch,campus,student);
        assertThat(second).as("many grade rows may belong to one student").isPositive();
        assertThat(db.update("INSERT INTO import_rows(batch_id,row_number,status,campus_id,normalized_values,student_id) VALUES (?,5,'imported',?,'{}',?)",oldBatch,campus,student)).as("the first student-import row may create the student").isEqualTo(1);
        assertThatThrownBy(()->db.update("INSERT INTO import_rows(batch_id,row_number,status,campus_id,normalized_values,student_id) VALUES (?,6,'imported',?,'{}',?)",oldBatch,campus,student)).as("a second student-import row cannot create the same student").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(insert,student,campus,term,q1,subject,"period",new java.math.BigDecimal("90"),null,gradeRow)).as("no second live record for the same cell").hasMessageContaining("legacy_grade_period_once");
        assertThatThrownBy(()->db.update(insert,student,campus,term,q1,subject,"period",new java.math.BigDecimal("101"),null,second)).as("scale 0-100").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(insert,student,campus,term,q1,subject,"period",null,null,second)).as("value or mark required").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(insert,student,campus,term,q1,subject,"period",new java.math.BigDecimal("80"),"INC",second)).as("not both").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(insert,student,campus,term,q1,subject,"period",null,"W",second)).as("marks limited to INC/NG/DRP").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(insert,student,campus,otherTerm,q1,subject,"period",null,"INC",second)).as("period must belong to the term").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(insert,student,campus,term,null,subject,"period",null,"INC",second)).as("period kind needs a period").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update(insert,student,campus,term,q1,subject,"reported_final",null,"INC",second)).as("reported final has no period").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.update(insert,student,campus,term,null,subject,"reported_final",null,"INC",second)).isEqualTo(1);
        assertThatThrownBy(()->db.update(insert,student,campus,term,null,subject,"reported_final",new java.math.BigDecimal("85"),null,second)).hasMessageContaining("legacy_grade_final_once");
        assertThatThrownBy(()->db.update("INSERT INTO legacy_grade_records(student_id,campus_id,term_id,subject_id,grade_level,mark,import_row_id,source_column,import_source_type) VALUES (?,?,?,?,'Grade 8','INC',?,'x','legacy_student_csv')",student,campus,term,subject,second)).as("records only point at grade matrix rows").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        long otherStudent=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES ('V21-OTHER','Other','Student','2009-01-01') RETURNING id",Long.class);
        assertThatThrownBy(()->db.update(insert,otherStudent,campus,term,q1,subject,"period",new java.math.BigDecimal("70"),null,second)).as("record student must be the row's student").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("UPDATE import_rows SET review_decision='distinct',reviewed_by=NULL WHERE id=?",gradeRow)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // Committed history cannot be edited or deleted; voiding is the only change, and it is final.
        assertThatThrownBy(()->db.update("UPDATE legacy_grade_records SET value=99 WHERE import_row_id=? AND record_kind='period'",gradeRow)).hasMessageContaining("cannot be edited");
        assertThatThrownBy(()->db.update("DELETE FROM legacy_grade_records WHERE import_row_id=?",gradeRow)).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(()->db.execute("TRUNCATE legacy_grade_records")).hasMessageContaining("cannot be truncated");
        long actor=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES ('voider','voider@example.invalid','Synthetic voider','x') RETURNING id",Long.class);
        assertThatThrownBy(()->db.update("UPDATE legacy_grade_records SET voided_at=now() WHERE import_row_id=?",gradeRow)).as("void needs actor and reason").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.update("UPDATE legacy_grade_records SET voided_at=now(),voided_by=?,void_reason='Wrong file' WHERE import_row_id=? AND record_kind='period'",actor,gradeRow)).isEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE legacy_grade_records SET void_reason='Changed' WHERE import_row_id=? AND record_kind='period'",gradeRow)).hasMessageContaining("voided");
        assertThat(db.update(insert,student,campus,term,q1,subject,"period",new java.math.BigDecimal("90"),null,gradeRow)).as("a voided cell can be recorded again").isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='legacy_grade_records'",Integer.class)).isPositive();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='legacy_grade_records' AND (new_data ? 'value' OR new_data ? 'mark' OR old_data ? 'value' OR old_data ? 'mark' OR new_data::text LIKE '%88.5%')",Integer.class)).as("no grade values or marks in the audit trail").isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM legacy_grade_records WHERE created_by IS NULL AND created_at IS NULL",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT settings->>'ignoredColumns' FROM import_column_mappings WHERE source_type='legacy_grade_matrix_csv'",String.class)).as("explicit ignoredColumns list in the seeded settings").isEqualTo("[]");
        assertThat(db.queryForObject("SELECT mapping->'grid' ? 'ignoreColumns' FROM import_column_mappings WHERE source_type='legacy_grade_matrix_csv'",Boolean.class)).isFalse();
        // Only a committed grade batch can be voided, and a void needs actor and reason.
        assertThatThrownBy(()->db.update("UPDATE import_batches SET voided_at=now(),voided_by=?,void_reason='x' WHERE id=?",actor,oldBatch)).as("student import batches are never voided").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("UPDATE import_batches SET voided_at=now() WHERE id=?",batch)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // A live grade batch blocks an identical checksum; a failed or voided one does not.
        assertThatThrownBy(()->db.update("INSERT INTO import_batches(source_name,checksum,source_count,campus_id,source_type) SELECT 'again.csv',checksum,0,campus_id,source_type FROM import_batches WHERE id=?",batch)).hasMessageContaining("import_batch_checksum_once");
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    private Flyway legacy(DriverManagerDataSource ds)throws Exception{
        var folder=Files.createTempDirectory("ici-legacy-fixture");
        var names=List.of("V1__institutional_foundation.sql","V2__reference_data_and_integrity.sql","V3__audit_metadata_and_academic_setup.sql","V4__authentication_revocations.sql","V5__student_master_profiles.sql","V6__requirement_verification_metadata.sql","V7__enrollment_workflow_versions.sql","V8__plain_catalog_labels.sql","V9__identity_resolution_and_account_security.sql");
        for(int i=0;i<names.size();i++){var resource=new ClassPathResource("db/"+(i<5?"migration":"legacy-local")+"/"+names.get(i));Files.copy(resource.getInputStream(),folder.resolve(names.get(i)));}
        var f=Flyway.configure().dataSource(ds).locations("filesystem:"+folder).load();f.migrate();return f;
    }
    @Test void exactUnpublishedLocalHistoryRequiresOptInThenPreservesDataAndAudits()throws Exception{
        var ds=database();legacy(ds);var db=new JdbcTemplate(ds);
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash,auth_version) VALUES('legacy','legacy@example.invalid','Legacy account','unchanged-hash',7) RETURNING id",Long.class);
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,'ROLE_ADMIN',id FROM campuses",id);
        int audits=db.queryForObject("SELECT count(*) FROM audit_logs",Integer.class);
        var latest=Flyway.configure().dataSource(ds).load();
        assertThatThrownBy(()->LocalMigrationBridge.reconcile(latest,false)).hasMessageContaining("explicitly set");
        assertThat(db.queryForObject("SELECT script FROM flyway_schema_history WHERE version='6'",String.class)).isEqualTo("V6__requirement_verification_metadata.sql");
        LocalMigrationBridge.reconcile(latest,true);assertThat(latest.migrate().migrationsExecuted).isEqualTo(14);latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("26");
        assertThat(db.queryForObject("SELECT token_version FROM users WHERE id=?",Integer.class,id)).isEqualTo(8);
        assertThat(db.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,id)).isEqualTo("unchanged-hash");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs",Integer.class)).isGreaterThanOrEqualTo(audits);
        assertThat(db.queryForObject("SELECT count(*) FROM user_role_grants WHERE user_id=?",Integer.class,id)).isEqualTo(3);
        LocalMigrationBridge.reconcile(latest,true);latest.validate();
    }
    @ParameterizedTest @ValueSource(ints={1,8}) void alteredLegacyChecksumRefusesBridgeWithoutPartialHistoryChanges(int version)throws Exception{
        var ds=database();legacy(ds);var db=new JdbcTemplate(ds);db.update("UPDATE flyway_schema_history SET checksum=123 WHERE version=?",Integer.toString(version));
        assertThatThrownBy(()->LocalMigrationBridge.reconcile(Flyway.configure().dataSource(ds).load(),true)).hasMessageContaining("differs");
        assertThat(db.queryForObject("SELECT script FROM flyway_schema_history WHERE version='6'",String.class)).isEqualTo("V6__requirement_verification_metadata.sql");
        assertThat(db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='users' AND column_name='token_version'",Integer.class)).isZero();
    }
}
