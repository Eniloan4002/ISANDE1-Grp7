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
    @Test void publishedUpstreamV7UpgradesToV13WithoutLosingExistingAccounts(){
        var ds=database();Flyway.configure().dataSource(ds).target("7").load().migrate();var db=new JdbcTemplate(ds);
        db.update("INSERT INTO users(username,email,full_name,password_hash,token_version) VALUES('existing','existing@example.invalid','Existing account','retained-hash',4)");
        var latest=Flyway.configure().dataSource(ds).load();LocalMigrationBridge.reconcile(latest,false);assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);latest.validate();
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
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
        assertThat(db.queryForMap("SELECT deadline,locked_at,locked_by FROM grading_windows WHERE class_id=?",offering)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM section_grading_windows",Integer.class)).isZero();
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
        LocalMigrationBridge.reconcile(latest,true);assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("13");
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
