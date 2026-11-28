package ph.edu.ici.imports;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import ph.edu.ici.PostgresSupport;
import static ph.edu.ici.imports.ImportFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** ICI2-40 cutover reconciliation. Synthetic data only; the embedded database is shared with other test classes, so assertions target this test's own batches. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationApiTest {
    static final String PASSWORD="Reconcile-Test-Password-2026!";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    long iligan,bugo,registrar;String registrarToken,bugoToken,teacherToken,principalToken;

    long staff(String role,long campus){
        String name="rec-"+UUID.randomUUID();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid","Synthetic "+role,encoder.encode(PASSWORD));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);return id;
    }
    String login(long id){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id),"password",PASSWORD),Map.class).get("accessToken");}
    @BeforeEach void setup(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=staff("ROLE_REGISTRAR",iligan);registrarToken=login(registrar);bugoToken=login(staff("ROLE_REGISTRAR",bugo));
        teacherToken=login(staff("ROLE_TEACHER",iligan));principalToken=login(staff("ROLE_PRINCIPAL",iligan));
    }

    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){
        var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(path,method,new HttpEntity<>(body,h),Map.class);
    }
    long stage(String fixture,String n){
        var h=new HttpHeaders();h.setBearerAuth(registrarToken);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var form=new LinkedMultiValueMap<String,Object>();form.add("campusId",Long.toString(iligan));
        form.add("file",new ByteArrayResource(fixture(fixture,n)){@Override public String getFilename(){return fixture+"-"+n+".csv";}});
        var r=rest.exchange("/api/legacy-student-imports/batches",HttpMethod.POST,new HttpEntity<>(form,h),Map.class);
        assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.CREATED);return ((Number)r.getBody().get("id")).longValue();
    }
    int version(long batch){return db.queryForObject("SELECT version FROM import_batches WHERE id=?",Integer.class,batch);}
    void commit(long batch,int expected){
        var r=call(HttpMethod.POST,"/api/legacy-student-imports/batches/"+batch+"/commit",Map.of("version",version(batch),"expectedValidCount",expected),registrarToken);
        assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);
    }
    /** Mixed fixture: 17 rows, 3 valid, 14 invalid. Committing leaves the 14 invalid rows unimported. */
    long committedMixed(){long b=stage("synthetic-mixed.csv",n());commit(b,3);return b;}
    /** Basic fixture where every row is resolved and imported, so source count equals imported count. */
    long committedBasic(){
        String n=n();
        long a=db.queryForObject("INSERT INTO students(student_number,lrn,first_name,last_name,birth_date,campus_id) VALUES (?,?,?,?,?::date,?) RETURNING id",Long.class,"OLD-"+n+"-A","99"+n+"01","Different","Person","2001-01-01",iligan);
        db.update("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id) VALUES (?,?,?,?::date,?)","OLD-"+n+"-B","Carla","Gammathree"+letters(n),"2009-07-30",iligan);
        long batch=stage("synthetic-basic.csv",n);
        for(var row:db.queryForList("SELECT id,status FROM import_rows WHERE batch_id=? AND status='duplicate_flagged' ORDER BY row_number",batch)){
            long rowId=((Number)row.get("id")).longValue();int rowVersion=db.queryForObject("SELECT version FROM import_rows WHERE id=?",Integer.class,rowId);
            boolean byLrn=db.queryForObject("SELECT raw_values->>'Student No' FROM import_rows WHERE id=?",String.class,rowId).endsWith("-01");
            Map<String,Object> body=new HashMap<>();body.put("version",rowVersion);body.put("decision",byLrn?"same_person":"distinct");body.put("reason","Reviewed against the registrar's records");
            if(byLrn)body.put("studentId",a);
            assertThat(call(HttpMethod.POST,"/api/legacy-student-imports/batches/"+batch+"/rows/"+rowId+"/review",body,registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        commit(batch,3);return batch;
    }
    Map<String,Object> entry(Map summary,long batch){
        return ((List<Map<String,Object>>)summary.get("batches")).stream().filter(b->((Number)b.get("id")).longValue()==batch).findFirst().orElseThrow(()->new AssertionError("batch "+batch+" missing from summary"));
    }
    ResponseEntity<Map> signOff(String token,long batch,Integer version,Integer expected,String note){
        Map<String,Object> body=new HashMap<>();body.put("version",version);body.put("expectedImportedCount",expected);if(note!=null)body.put("note",note);
        return call(HttpMethod.POST,"/api/legacy-imports/reconciliation/batches/"+batch+"/sign-off",body,token);
    }
    long importedCount(long batch){return db.queryForObject("SELECT imported_count FROM import_batches WHERE id=?",Long.class,batch);}

    @Test void anUncommittedBatchIsReportedAsNotCommittedAndBlocksDecommissioning(){
        long batch=stage("synthetic-basic.csv",n());
        var summary=call(HttpMethod.GET,"/api/legacy-imports/reconciliation?campusId="+iligan,null,registrarToken);
        assertThat(summary.getStatusCode()).as(summary.toString()).isEqualTo(HttpStatus.OK);
        var e=entry(summary.getBody(),batch);
        assertThat(e).containsEntry("status","validated").containsEntry("canSignOff",false);
        assertThat((List<Map>)e.get("discrepancies")).extracting(d->d.get("code")).containsExactly("NOT_COMMITTED");
        assertThat(summary.getBody()).containsEntry("readyToDecommission",false);
        assertThat(signOff(registrarToken,batch,version(batch),0,null).getStatusCode()).as("cannot sign off what was never committed").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void aBatchWithUnimportedRowsShowsTheGapAndCannotBeSignedOff(){
        long batch=committedMixed();
        var e=entry(call(HttpMethod.GET,"/api/legacy-imports/reconciliation?campusId="+iligan,null,registrarToken).getBody(),batch);
        assertThat(e).containsEntry("status","imported").containsEntry("sourceCount",17).containsEntry("importedCount",3).containsEntry("unimportedCount",14).containsEntry("canSignOff",false);
        var problems=(List<Map>)e.get("discrepancies");
        assertThat(problems).extracting(d->d.get("code")).containsExactly("UNIMPORTED_ROWS");
        assertThat((String)problems.getFirst().get("detail")).contains("14 of 17").contains("invalid 14");
        var refused=signOff(registrarToken,batch,version(batch),3,"Accepting the gap");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,batch)).isEqualTo("imported");
    }

    @Test void aFullyImportedBatchCanBeSignedOffOnceWithAnAuditTrail(){
        long batch=committedBasic();
        long reconciledBefore=((Number)((Map)call(HttpMethod.GET,"/api/legacy-imports/reconciliation",null,registrarToken).getBody().get("totals")).get("reconciledBatches")).longValue();
        var before=entry(call(HttpMethod.GET,"/api/legacy-imports/reconciliation?campusId="+iligan,null,registrarToken).getBody(),batch);
        assertThat(before).containsEntry("canSignOff",true).containsEntry("unimportedCount",0);assertThat((List)before.get("discrepancies")).isEmpty();
        long imported=importedCount(batch);

        assertThat(signOff(registrarToken,batch,version(batch)+1,(int)imported,null).getStatusCode()).as("stale version").isEqualTo(HttpStatus.CONFLICT);
        assertThat(signOff(registrarToken,batch,version(batch),(int)imported+1,null).getStatusCode()).as("count the registrar saw does not match").isEqualTo(HttpStatus.CONFLICT);
        assertThat(signOff(registrarToken,batch,null,(int)imported,null).getStatusCode()).as("version is required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(signOff(registrarToken,batch,version(batch),(int)imported,"x".repeat(1001)).getStatusCode()).as("note too long").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(signOff(teacherToken,batch,version(batch),(int)imported,null).getStatusCode()).as("teachers have no import access").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(signOff(bugoToken,batch,version(batch),(int)imported,null).getStatusCode()).as("another campus cannot see the batch").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,batch)).as("refused attempts change nothing").isEqualTo("imported");

        var ok=signOff(registrarToken,batch,version(batch),(int)imported,"Counts match the legacy totals");
        assertThat(ok.getStatusCode()).as(ok.toString()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status","reconciled").containsEntry("alreadySignedOff",false).containsEntry("signedOffBy",(int)registrar).containsKey("signedOffAt");
        assertThat(db.queryForMap("SELECT status,signed_off_by,source_count,imported_count FROM import_batches WHERE id=?",batch))
                .containsEntry("status","reconciled").containsEntry("signed_off_by",registrar);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_batches' AND operation='SIGNOFF' AND actor_id=? AND record_id=?::jsonb::text",Integer.class,registrar,"{\"id\": "+batch+"}"))
                .as("sign-off audit row").isEqualTo(1);
        assertThat(db.queryForObject("SELECT new_data->>'note' FROM audit_logs WHERE table_name='import_batches' AND operation='SIGNOFF' AND record_id=?::jsonb::text",String.class,"{\"id\": "+batch+"}")).isEqualTo("Counts match the legacy totals");

        var again=signOff(registrarToken,batch,0,0,null);
        assertThat(again.getBody()).as("repeat is a no-op").containsEntry("alreadySignedOff",true).containsEntry("status","reconciled");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_batches' AND operation='SIGNOFF' AND record_id=?::jsonb::text",Integer.class,"{\"id\": "+batch+"}")).as("no second audit row").isEqualTo(1);

        var after=call(HttpMethod.GET,"/api/legacy-imports/reconciliation",null,registrarToken).getBody();
        assertThat(((Number)((Map)after.get("totals")).get("reconciledBatches")).longValue()).isEqualTo(reconciledBefore+1);
        assertThat(entry(call(HttpMethod.GET,"/api/legacy-imports/reconciliation?campusId="+iligan,null,registrarToken).getBody(),batch)).containsEntry("status","reconciled").containsEntry("canSignOff",false);
    }

    @Test void discardedBatchesAreExcludedFromTheSummary(){
        long batch=stage("synthetic-basic.csv",n());
        assertThat(call(HttpMethod.POST,"/api/legacy-student-imports/batches/"+batch+"/discard",Map.of("version",version(batch),"reason","Uploaded by mistake"),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        var summary=call(HttpMethod.GET,"/api/legacy-imports/reconciliation?campusId="+iligan,null,registrarToken).getBody();
        assertThat(((List<Map>)summary.get("batches")).stream().map(b->((Number)b.get("id")).longValue())).doesNotContain(batch);
        assertThat(((Number)summary.get("excludedBatches")).longValue()).isGreaterThanOrEqualTo(1);
    }

    @Test void readsAreScopedToTheCallersCampusesAndPermissions(){
        long batch=stage("synthetic-basic.csv",n());
        var bugoView=call(HttpMethod.GET,"/api/legacy-imports/reconciliation",null,bugoToken).getBody();
        assertThat(((List<Map>)bugoView.get("batches")).stream().map(b->((Number)b.get("id")).longValue())).as("Bugo does not see Iligan's batch").doesNotContain(batch);
        assertThat(call(HttpMethod.GET,"/api/legacy-imports/reconciliation?campusId="+iligan,null,bugoToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/legacy-imports/reconciliation",null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/legacy-imports/reconciliation",null,principalToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/legacy-imports/reconciliation",null,null).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/api/legacy-imports/reconciliation/batches/999999999/sign-off",Map.of("version",0,"expectedImportedCount",0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void gradeBatchesNeedTheStricterGradeImportPermission(){
        assertThat(ReconciliationService.writePermission("legacy_grade_matrix_csv")).isEqualTo("imports.grades.manage");
        assertThat(ReconciliationService.writePermission("legacy_student_csv")).isEqualTo("imports.manage");
    }
}
