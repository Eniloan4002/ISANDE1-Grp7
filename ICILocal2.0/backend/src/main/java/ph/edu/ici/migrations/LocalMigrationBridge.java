package ph.edu.ici.migrations;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.zip.CRC32;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Explicit, checksum-verified bridge for the unpublished November 9 local V6–V9 sequence. */
@Configuration
public class LocalMigrationBridge {
    private static final String[] OLD={"V6__requirement_verification_metadata.sql","V7__enrollment_workflow_versions.sql","V8__plain_catalog_labels.sql","V9__identity_resolution_and_account_security.sql"};
    private static final String[] NEXT={"V8__requirement_verification_metadata.sql","V9__enrollment_workflow_versions.sql","V10__plain_catalog_labels.sql","V11__identity_resolution_and_account_security.sql"};
    @Bean public FlywayMigrationStrategy migrations(@Value("${ici.migrations.reconcile-local-history:false}") boolean enabled){
        return flyway->{reconcile(flyway,enabled);flyway.migrate();};
    }
    private static String script(String folder,String name){
        try{return new ClassPathResource("db/"+folder+"/"+name).getContentAsString(StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}
    }
    // Flyway SQL checksums normalize line endings and exclude newline characters.
    private static int checksum(String sql){var crc=new CRC32();sql.replaceFirst("^\\uFEFF","").lines().forEach(line->crc.update(line.getBytes(StandardCharsets.UTF_8)));return (int)crc.getValue();}
    public static void reconcile(Flyway flyway,boolean enabled){
        try(Connection connection=flyway.getConfiguration().getDataSource().getConnection()){
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL",Boolean.class)))return;
            var six=db.queryForList("SELECT script FROM flyway_schema_history WHERE version='6' AND success");
            if(six.isEmpty()||!OLD[0].equals(six.getFirst().get("script")))return;
            if(!enabled)throw new IllegalStateException("Unpublished local V6-V9 history detected. Back up this database, then explicitly set ICI_MIGRATIONS_RECONCILE_LOCAL_HISTORY=true for the documented data-preserving bridge. Do not run Flyway repair or delete data.");
            connection.setAutoCommit(false);
            try{
                db.execute("LOCK TABLE flyway_schema_history IN EXCLUSIVE MODE");
                var baseline=db.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history WHERE version::numeric<6 ORDER BY version::numeric");
                if(baseline.size()!=5)throw new IllegalStateException("Local migration history differs from the verified baseline.");
                for(var row:baseline){String name=(String)row.get("script");if(!Boolean.TRUE.equals(row.get("success"))||((Number)row.get("checksum")).intValue()!=checksum(script("migration",name)))throw new IllegalStateException("Local migration history differs from the verified baseline; bridge refused without changing data.");}
                var history=db.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history WHERE version::numeric>=6 ORDER BY version::numeric");
                if(history.size()!=4)throw new IllegalStateException("Bridge supports only the exact unpublished local V6-V9 history; manual migration review is required.");
                for(int i=0;i<4;i++){
                    var row=history.get(i);
                    if(!Integer.toString(i+6).equals(row.get("version"))||!OLD[i].equals(row.get("script"))||!Boolean.TRUE.equals(row.get("success"))||((Number)row.get("checksum")).intValue()!=checksum(script("legacy-local",OLD[i])))throw new IllegalStateException("Local migration history differs from the verified snapshot; bridge refused without changing data.");
                }
                // Add the two published changes, without rerunning existing credential/workflow migrations.
                db.execute(script("migration","V6__admin_enrollment_permission.sql"));
                db.execute(script("migration","V7__user_token_version.sql"));
                // Invalidate legacy sessions while preserving the existing security counter.
                db.update("UPDATE users SET token_version=auth_version+1");
                db.update("UPDATE flyway_schema_history SET installed_rank=installed_rank+1000 WHERE version::numeric>=6");
                for(int i=0;i<4;i++){
                    String name=NEXT[i],description=name.substring(name.indexOf("__")+2,name.length()-4).replace('_',' ');
                    db.update("UPDATE flyway_schema_history SET installed_rank=?,version=?,description=?,script=?,checksum=? WHERE script=?",i+8,Integer.toString(i+8),description,name,checksum(script("migration",name)),OLD[i]);
                }
                String[] published={"V6__admin_enrollment_permission.sql","V7__user_token_version.sql"};
                for(int i=0;i<2;i++){
                    String name=published[i],description=name.substring(name.indexOf("__")+2,name.length()-4).replace('_',' ');
                    db.update("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(?,?,?,'SQL',?,?,current_user,0,true)",i+6,Integer.toString(i+6),description,name,checksum(script("migration",name)));
                }
                connection.commit();
            }catch(Exception e){connection.rollback();throw e;}finally{connection.setAutoCommit(true);}
        }catch(Exception e){throw new IllegalStateException("Local migration reconciliation failed: "+e.getMessage(),e);}
    }
}
