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
    @Test void publishedUpstreamV7UpgradesToV19WithoutLosingExistingAccounts(){
        var ds=database();Flyway.configure().dataSource(ds).target("7").load().migrate();var db=new JdbcTemplate(ds);
        db.update("INSERT INTO users(username,email,full_name,password_hash,token_version) VALUES('existing','existing@example.invalid','Existing account','retained-hash',4)");
        var latest=Flyway.configure().dataSource(ds).load();LocalMigrationBridge.reconcile(latest,false);assertThat(latest.migrate().migrationsExecuted).isEqualTo(12);latest.validate();
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
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(7);latest.validate();
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
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);latest.validate();
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
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);latest.validate();
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
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
        assertThat(db.queryForList("SELECT * FROM students")).containsExactlyInAnyOrderElementsOf(before);
        assertThat(db.queryForObject("SELECT source_type FROM import_batches WHERE id=?",String.class,batch)).isEqualTo("legacy_student_csv");
        assertThat(db.queryForObject("SELECT severity FROM import_exceptions WHERE batch_id=?",String.class,batch)).isEqualTo("error");
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows",Integer.class)).isZero();
        assertThat(db.queryForList("SELECT role_code||':'||permission_code FROM role_permissions WHERE permission_code LIKE 'imports.%'",String.class))
            .containsExactlyInAnyOrder("ROLE_ADMIN:imports.read","ROLE_ADMIN:imports.manage","ROLE_REGISTRAR:imports.read","ROLE_REGISTRAR:imports.manage");
        assertThat(db.queryForMap("SELECT is_default,real_file_validated FROM import_column_mappings WHERE source_type='legacy_student_csv'")).containsEntry("is_default",true).containsEntry("real_file_validated",false);
        assertThat(student).isPositive();assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    @Test void importStagingConstraintsRejectInconsistentRowsAndDuplicateFiles(){
        var ds=database();Flyway.configure().dataSource(ds).load().migrate();var db=new JdbcTemplate(ds);
        long batch=db.queryForObject("INSERT INTO import_batches(source_name,checksum,source_count,status,valid_count) VALUES ('a.csv',repeat('b',64),1,'validated',1) RETURNING id",Long.class);
        assertThatThrownBy(()->db.update("INSERT INTO import_batches(source_name,checksum,source_count) VALUES ('copy.csv',repeat('b',64),1)")).hasMessageContaining("import_batch_checksum_once");
        assertThatThrownBy(()->db.update("INSERT INTO import_batches(source_name,checksum,source_count,status,valid_count) VALUES ('c.csv',repeat('c',64),2,'validated',1)")).hasMessageContaining("import_batch_counts_balance");
        db.update("INSERT INTO import_rows(batch_id,row_number,status,normalized_values,raw_values) VALUES (?,2,'valid','{}','{\"lrn\":\"secret-raw\"}')",batch);
        assertThatThrownBy(()->db.update("INSERT INTO import_rows(batch_id,row_number,status,normalized_values) VALUES (?,2,'valid','{}')",batch)).hasMessageContaining("import_rows_batch_id_row_number_key");
        assertThatThrownBy(()->db.update("INSERT INTO import_rows(batch_id,row_number,status) VALUES (?,3,'imported')",batch)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND (new_data ? 'raw_values' OR new_data::text LIKE '%secret-raw%')",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND operation='INSERT'",Integer.class)).isEqualTo(1);
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
        LocalMigrationBridge.reconcile(latest,true);assertThat(latest.migrate().migrationsExecuted).isEqualTo(8);latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("19");
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
