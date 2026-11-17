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
    @Test void publishedUpstreamV7UpgradesToV12WithoutLosingExistingAccounts(){
        var ds=database();Flyway.configure().dataSource(ds).target("7").load().migrate();var db=new JdbcTemplate(ds);
        db.update("INSERT INTO users(username,email,full_name,password_hash,token_version) VALUES('existing','existing@example.invalid','Existing account','retained-hash',4)");
        var latest=Flyway.configure().dataSource(ds).load();LocalMigrationBridge.reconcile(latest,false);assertThat(latest.migrate().migrationsExecuted).isEqualTo(5);latest.validate();
        assertThat(db.queryForObject("SELECT password_hash FROM users WHERE username='existing'",String.class)).isEqualTo("retained-hash");
        assertThat(db.queryForObject("SELECT token_version FROM users WHERE username='existing'",Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT count(*) FROM enrollment_attendance_windows",Integer.class)).isZero();
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
        LocalMigrationBridge.reconcile(latest,true);assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("12");
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
