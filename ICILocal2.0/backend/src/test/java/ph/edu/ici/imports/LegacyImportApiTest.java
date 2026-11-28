package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
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

/** Real local HTTP + embedded PostgreSQL. All data is synthetic (see src/test/resources/import/README-SYNTHETIC.md). */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyImportApiTest {
    static final String BASE="/api/legacy-student-imports",PASSWORD="Import-Test-Password-2026!";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;
    long iligan,bugo,registrar,bugoRegistrar,admin;String registrarToken,bugoToken,adminToken,teacherToken,principalToken;

    long staff(String role,Long campus){
        String name="imp-"+UUID.randomUUID();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid","Synthetic "+role,encoder.encode(PASSWORD));
        if(campus!=null)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);else db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    String login(long id){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id),"password",PASSWORD),Map.class).get("accessToken");}
    @BeforeEach void setup(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=staff("ROLE_REGISTRAR",iligan);registrarToken=login(registrar);bugoRegistrar=staff("ROLE_REGISTRAR",bugo);bugoToken=login(bugoRegistrar);
        admin=staff("ROLE_ADMIN",null);adminToken=login(admin);teacherToken=login(staff("ROLE_TEACHER",iligan));principalToken=login(staff("ROLE_PRINCIPAL",iligan));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(BASE+path,method,new HttpEntity<>(body,h),Map.class);}
    ResponseEntity<Map> get(String path,String token){return call(HttpMethod.GET,path,null,token);}
    ResponseEntity<Map> upload(String token,long campus,Long mapping,String filename,byte[] bytes){
        var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var form=new LinkedMultiValueMap<String,Object>();form.add("campusId",Long.toString(campus));if(mapping!=null)form.add("mappingId",Long.toString(mapping));
        form.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return filename;}});
        return rest.exchange(BASE+"/batches",HttpMethod.POST,new HttpEntity<>(form,h),Map.class);
    }
    long id(Map body){return ((Number)body.get("id")).longValue();}
    long stage(String name,String n){var r=upload(registrarToken,iligan,null,name,fixture(name,n));assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.CREATED);return id(r.getBody());}
    String number(String n,String id){return "SYN-"+n+"-"+id;}
    long rowId(long batch,String studentNumber){return db.queryForObject("SELECT id FROM import_rows WHERE batch_id=? AND raw_values->>'Student No'=?",Long.class,batch,studentNumber);}
    int rowVersion(long rowId){return db.queryForObject("SELECT version FROM import_rows WHERE id=?",Integer.class,rowId);}
    int batchVersion(long batch){return db.queryForObject("SELECT version FROM import_batches WHERE id=?",Integer.class,batch);}
    String status(long batch,String num){return db.queryForObject("SELECT status FROM import_rows WHERE batch_id=? AND raw_values->>'Student No'=?",String.class,batch,num);}
    long students(){return db.queryForObject("SELECT count(*) FROM students",Long.class);}
    long student(String number,String lrn,String first,String last,String birth,long campus){return db.queryForObject("INSERT INTO students(student_number,lrn,first_name,last_name,birth_date,campus_id) VALUES (?,?,?,?,?::date,?) RETURNING id",Long.class,number,lrn,first,last,birth,campus);}
    ResponseEntity<Map> review(String token,long batch,long row,String decision,String reason,Long studentId){
        Map<String,Object> body=new HashMap<>();body.put("version",rowVersion(row));body.put("decision",decision);body.put("reason",reason);if(studentId!=null)body.put("studentId",studentId);
        return call(HttpMethod.POST,"/batches/"+batch+"/rows/"+row+"/review",body,token);
    }
    ResponseEntity<Map> commit(String token,long batch,int expected){return call(HttpMethod.POST,"/batches/"+batch+"/commit",Map.of("version",batchVersion(batch),"expectedValidCount",expected),token);}
    /** The basic fixture with row 01 matching an existing student by LRN and row 03 by name/birth date only. */
    record Scenario(String n,long batch,long lrnStudent,long nameStudent){}
    Scenario scenario(){
        String n=n();long a=student("OLD-"+n+"-A","99"+n+"01","Different","Person","2001-01-01",iligan);
        long b=student("OLD-"+n+"-B",null,"Carla","Gammathree"+letters(n),"2009-07-30",iligan);
        return new Scenario(n,stage("synthetic-basic.csv",n),a,b);
    }
    Scenario resolved(){
        var s=scenario();
        assertThat(review(registrarToken,s.batch(),rowId(s.batch(),number(s.n(),"01")),"same_person","Same learner, existing record",s.lrnStudent()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review(registrarToken,s.batch(),rowId(s.batch(),number(s.n(),"03")),"distinct","Different learner; confirmed with registrar",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        return s;
    }

    // ---- permissions -------------------------------------------------------------------------------------------------
    @Test void permissionMatrixCoversAdminRegistrarOtherRolesWrongCampusAndNoToken(){
        long batch=stage("synthetic-basic.csv",n());
        for(String path:List.of("/mappings","/batches","/batches/"+batch,"/batches/"+batch+"/rows","/batches/"+batch+"/errors")){
            assertThat(get(path,adminToken).getStatusCode()).as("admin "+path).isEqualTo(HttpStatus.OK);assertThat(get(path,registrarToken).getStatusCode()).as("registrar "+path).isEqualTo(HttpStatus.OK);
            assertThat(get(path,teacherToken).getStatusCode()).as("teacher "+path).isEqualTo(HttpStatus.FORBIDDEN);assertThat(get(path,principalToken).getStatusCode()).as("principal "+path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(path,null).getStatusCode()).as("anonymous "+path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        for(String path:List.of("/batches/"+batch,"/batches/"+batch+"/rows"))assertThat(get(path,bugoToken).getStatusCode()).as("other campus "+path).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/batches?campusId="+iligan,bugoToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(registrarToken,bugo,null,"x.csv",fixture("synthetic-basic.csv",n())).getStatusCode()).as("registrar uploading for another campus").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(teacherToken,iligan,null,"x.csv",fixture("synthetic-basic.csv",n())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(bugoToken,iligan,null,"x.csv",fixture("synthetic-basic.csv",n())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(adminToken,iligan,null,"x.csv",fixture("synthetic-basic.csv",n())).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(commit(teacherToken,batch,3).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(commit(bugoToken,batch,3).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        long row=db.queryForObject("SELECT id FROM import_rows WHERE batch_id=? LIMIT 1",Long.class,batch);
        assertThat(review(teacherToken,batch,row,"skip","No access",null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(review(bugoToken,batch,row,"skip","Wrong campus",null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var body=Map.of("name","m-"+n(),"mapping",defaultMapping(),"settings",defaultSettings());
        assertThat(call(HttpMethod.POST,"/mappings",body,registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(call(HttpMethod.POST,"/mappings",body,principalToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/mappings",body,adminToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/batches?campusId="+iligan,adminToken).getBody().get("items")).asList().isNotEmpty();
        assertThat(((List)get("/batches",bugoToken).getBody().get("items"))).extracting(i->((Map)i).get("id")).doesNotContain((int)batch);
    }

    // ---- upload / dry run --------------------------------------------------------------------------------------------
    @Test void uploadIsADryRunThatReturnsTheStagedBatchAndWritesNoStudents(){
        String n=n();long before=students();var r=upload(registrarToken,iligan,null,"C:\\exports\\legacy "+n+".csv",fixture("synthetic-mixed.csv",n));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);var b=r.getBody();
        assertThat(students()).isEqualTo(before);
        assertThat(b).containsEntry("status","validated").containsEntry("sourceName","legacy "+n+".csv").containsEntry("campusId",(int)iligan).containsEntry("sourceCount",17).containsEntry("validCount",3).containsEntry("invalidCount",14).containsEntry("flaggedCount",0).containsEntry("importedCount",0).containsEntry("version",0);
        assertThat((Map)b.get("mapping")).containsEntry("realFileValidated",false);
        assertThat(((List<Map>)b.get("errorSummary"))).extracting(m->m.get("code")).contains("INVALID_DATE","INVALID_LRN","REQUIRED_MISSING");
        var rows=get("/batches/"+b.get("id")+"/rows?status=invalid&size=5",registrarToken).getBody();
        assertThat(rows).containsEntry("total",14).containsEntry("size",5);assertThat((List)rows.get("items")).hasSize(5);
        var first=(Map)((List)rows.get("items")).getFirst();assertThat(first).containsKeys("id","rowNumber","status","version","rawValues","messages");assertThat((List)first.get("messages")).isNotEmpty();
        var errors=get("/batches/"+b.get("id")+"/errors?severity=error&rowNumber="+first.get("rowNumber"),registrarToken).getBody();assertThat((List)errors.get("items")).isNotEmpty();
        assertThat(get("/batches/"+b.get("id")+"/rows?size=101",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/batches/"+b.get("id")+"/rows?status=bogus",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/batches/"+b.get("id")+"/rows?page=-1",registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/batches/999999999",registrarToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((List)get("/batches?status=validated&size=2",registrarToken).getBody().get("items")).size()).isLessThanOrEqualTo(2);
    }
    @Test void duplicateFileOversizedAndMalformedUploadsAnswer409413And422WithProblemDetails(){
        String n=n();var bytes=fixture("synthetic-basic.csv",n);var first=upload(registrarToken,iligan,null,"a.csv",bytes);long batch=id(first.getBody());
        var again=upload(registrarToken,iligan,null,"b.csv",bytes);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(again.getBody()).containsEntry("code","DUPLICATE_FILE").containsEntry("batchId",(int)batch).containsEntry("batchStatus","validated");
        var big=upload(registrarToken,iligan,null,"big.csv",new byte[5_000_001]);assertThat(big.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);assertThat(big.getBody()).containsEntry("code","FILE_TOO_LARGE");
        var rows=new StringBuilder("Student No\n");for(int i=0;i<20_001;i++)rows.append("x\n");
        var many=upload(registrarToken,iligan,null,"many.csv",rows.toString().getBytes(StandardCharsets.UTF_8));assertThat(many.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);assertThat(many.getBody()).containsEntry("code","TOO_MANY_ROWS");assertThat(many.getBody()).doesNotContainKey("batchId");
        var missing=upload(registrarToken,iligan,null,"m.csv",fixture("synthetic-missing-header.csv",n));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);assertThat(missing.getBody()).containsEntry("code","MISSING_COLUMN").containsKey("batchId");
        assertThat(get("/batches/"+missing.getBody().get("batchId"),registrarToken).getBody()).containsEntry("status","failed");
        assertThat(upload(registrarToken,iligan,null,"a.csv",fixture("synthetic-ambiguous-header.csv",n)).getBody()).containsEntry("code","AMBIGUOUS_HEADER");
        var cp=upload(registrarToken,iligan,null,"c.csv",fixture("synthetic-windows-1252.csv",n,java.nio.charset.Charset.forName("windows-1252")));assertThat(cp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);assertThat(cp.getBody()).containsEntry("code","INVALID_ENCODING");
        assertThat(upload(registrarToken,iligan,null,"notes.txt",bytes).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(upload(registrarToken,iligan,-5L,"neg.csv",fixture("synthetic-basic.csv",n())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(registrarToken,iligan,987654321L,"nomap.csv",fixture("synthetic-basic.csv",n())).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---- review ------------------------------------------------------------------------------------------------------
    @Test void reviewDecisionsRequireReasonVersionAndAValidTarget(){
        var s=scenario();long b=s.batch();long r1=rowId(b,number(s.n(),"01")),r3=rowId(b,number(s.n(),"03")),r2=rowId(b,number(s.n(),"02"));
        assertThat(status(b,number(s.n(),"01"))).isEqualTo("duplicate_flagged");assertThat(status(b,number(s.n(),"03"))).isEqualTo("duplicate_flagged");
        var detail=get("/batches/"+b+"/rows?status=duplicate_flagged",registrarToken).getBody();assertThat((List)detail.get("items")).hasSize(2);
        assertThat(review(registrarToken,b,r1,"same_person","",s.lrnStudent()).getStatusCode()).as("reason required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(registrarToken,b,r1,"merge","Because",null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(registrarToken,b,r1,"same_person","Same learner",null).getStatusCode()).as("same_person needs a student").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(registrarToken,b,r1,"same_person","Same learner",s.nameStudent()).getStatusCode()).as("not one of the shown matches").isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,b,r1,"distinct","Different learner",null).getStatusCode()).as("distinct people cannot share an LRN").isEqualTo(HttpStatus.CONFLICT);
        var stale=call(HttpMethod.POST,"/batches/"+b+"/rows/"+r1+"/review",Map.of("version",9,"decision","skip","reason","Stale"),registrarToken);assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,b,r2,"same_person","Not flagged",s.lrnStudent()).getStatusCode()).as("unflagged rows have nothing to link").isEqualTo(HttpStatus.CONFLICT);
        int version=batchVersion(b);
        var ok=review(registrarToken,b,r1,"same_person","Same learner, existing record",s.lrnStudent());assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map)ok.getBody().get("row")).containsEntry("status","valid");assertThat((Map)((Map)ok.getBody().get("row")).get("review")).containsEntry("decision","same_person").containsEntry("matchedStudentId",(int)s.lrnStudent());
        assertThat(batchVersion(b)).as("batch version moves so a stale commit is refused").isGreaterThan(version);
        assertThat(review(registrarToken,b,r3,"distinct","Different learner",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review(registrarToken,b,r2,"skip","Withdrawn before enrollment",null).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(status(b,number(s.n(),"02"))).isEqualTo("skipped");
        assertThat(db.queryForMap("SELECT valid_count,flagged_count,skipped_count,source_count FROM import_batches WHERE id=?",b)).containsEntry("valid_count",2).containsEntry("flagged_count",0).containsEntry("skipped_count",1);
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND reviewed_by=?",Integer.class,b,registrar)).isEqualTo(3);
        assertThat(review(registrarToken,b,r2,"distinct","Back again",null).getStatusCode()).as("never flagged, so no distinct decision").isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void aStudentNumberOwnedByAnotherStudentBlocksADistinctDecision(){
        String n=n();student(number(n,"03"),null,"Carla","Gammathree"+letters(n),"2009-07-30",iligan);   // same person AND same number: flagged, not invalid
        long b=stage("synthetic-basic.csv",n);long row=rowId(b,number(n,"03"));
        assertThat(status(b,number(n,"03"))).isEqualTo("duplicate_flagged");assertThat(review(registrarToken,b,row,"distinct","Different learner",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- commit ------------------------------------------------------------------------------------------------------
    @Test void commitCreatesStudentsLinksSamePersonRowsAndAttributesEverythingToTheCommittingUser(){
        var s=resolved();long b=s.batch();long before=students();
        var r=commit(registrarToken,b,3);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status","imported").containsEntry("created",2).containsEntry("linked",1).containsEntry("skipped",0).containsEntry("importedCount",3).containsEntry("alreadyImported",false);
        assertThat(students()).isEqualTo(before+2);
        var created=db.queryForMap("SELECT * FROM students WHERE student_number=?",number(s.n(),"02"));
        assertThat(created).containsEntry("lrn","99"+s.n()+"02").containsEntry("voucher_code","ESC").containsEntry("track","ACADEMIC").containsEntry("campus_id",iligan).containsEntry("active",true).containsEntry("first_name","Ben").containsEntry("created_by",registrar);
        assertThat(db.queryForObject("SELECT code FROM programs WHERE id=?",String.class,created.get("program_id"))).isEqualTo("ABM");
        assertThat(db.queryForMap("SELECT student_number,voucher_code,track FROM students WHERE student_number=?",number(s.n(),"03"))).containsEntry("voucher_code","REGULAR").containsEntry("track","TVL");
        assertThat(db.queryForObject("SELECT count(*) FROM students WHERE student_number=?",Integer.class,number(s.n(),"01"))).as("linked row creates nothing").isZero();
        assertThat(db.queryForObject("SELECT student_id FROM import_rows WHERE id=?",Long.class,rowId(b,number(s.n(),"01")))).isEqualTo(s.lrnStudent());
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND status='imported' AND student_id IS NOT NULL",Integer.class,b)).isEqualTo(3);
        assertThat(db.queryForMap("SELECT status,imported_count,valid_count,imported_by FROM import_batches WHERE id=?",b)).containsEntry("status","imported").containsEntry("imported_count",3).containsEntry("valid_count",0).containsEntry("imported_by",registrar);
        assertThat(db.queryForObject("SELECT imported_at IS NOT NULL FROM import_batches WHERE id=?",Boolean.class,b)).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='students' AND operation='INSERT' AND actor_id=? AND new_data->>'student_number' IN (?,?)",Integer.class,registrar,number(s.n(),"02"),number(s.n(),"03"))).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM students WHERE id=? AND updated_by IS NOT DISTINCT FROM (SELECT updated_by FROM students WHERE id=?)",Integer.class,s.lrnStudent(),s.lrnStudent())).isEqualTo(1);
        assertThat(get("/batches/"+b,registrarToken).getBody()).containsEntry("status","imported").containsEntry("importedCount",3);
        assertThat(review(registrarToken,b,rowId(b,number(s.n(),"02")),"skip","Too late",null).getStatusCode()).as("an imported batch is closed").isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void commitIsIdempotentAndNeverCreatesStudentsTwice(){
        var s=resolved();long b=s.batch();var first=commit(registrarToken,b,3);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);long afterFirst=students();
        var staleVersion=call(HttpMethod.POST,"/batches/"+b+"/commit",Map.of("version",0,"expectedValidCount",3),registrarToken);
        assertThat(staleVersion.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(staleVersion.getBody()).containsEntry("alreadyImported",true).containsEntry("created",2).containsEntry("linked",1).containsEntry("importedCount",3);
        var third=commit(registrarToken,b,3);assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(students()).isEqualTo(afterFirst);
        assertThat(db.queryForObject("SELECT count(*) FROM students WHERE student_number IN (?,?)",Integer.class,number(s.n(),"02"),number(s.n(),"03"))).isEqualTo(2);
        assertThat(commit(adminToken,b,0).getStatusCode()).as("admin sees the same result").isEqualTo(HttpStatus.OK);
    }
    @Test void commitIsRefusedForUnresolvedFlagsStaleVersionsWrongCountsAndFailedBatches(){
        var s=scenario();long b=s.batch();
        assertThat(commit(registrarToken,b,1).getStatusCode()).as("flagged rows block commit").isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,b,rowId(b,number(s.n(),"01")),"skip","Not importing",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(review(registrarToken,b,rowId(b,number(s.n(),"03")),"skip","Not importing",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        int version=batchVersion(b);
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/commit",Map.of("version",version-1,"expectedValidCount",1),registrarToken).getStatusCode()).as("stale version").isEqualTo(HttpStatus.CONFLICT);
        assertThat(commit(registrarToken,b,2).getStatusCode()).as("expectedValidCount mismatch").isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,"/batches/"+b+"/commit",Map.of("version",version),registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(students()).isEqualTo(db.queryForObject("SELECT count(*) FROM students",Long.class));
        assertThat(db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,b)).isEqualTo("validated");
        long failed=((Number)upload(registrarToken,iligan,null,"m.csv",fixture("synthetic-missing-header.csv",n())).getBody().get("batchId")).longValue();
        assertThat(commit(registrarToken,failed,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(commit(registrarToken,b,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        long empty=stage("synthetic-duplicates.csv",n());for(var r:db.queryForList("SELECT id FROM import_rows WHERE batch_id=? AND status<>'invalid'",Long.class,empty))assertThat(review(registrarToken,empty,r,"skip","Nothing to import",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(commit(registrarToken,empty,0).getStatusCode()).as("nothing importable").isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void aFailingStudentRollsTheWholeCommitBack(){
        String n=n();long b=stage("synthetic-basic.csv",n);
        db.execute("CREATE OR REPLACE FUNCTION fail_import_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.student_number LIKE 'SYN-"+n+"-03' THEN RAISE EXCEPTION 'synthetic failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER fail_import_test BEFORE INSERT ON students FOR EACH ROW EXECUTE FUNCTION fail_import_test()");
        try{
            long before=students();var r=commit(registrarToken,b,3);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(r.getBody().get("detail").toString()).contains("Nothing was imported");
            assertThat(students()).as("rows 1 and 2 were rolled back too").isEqualTo(before);
            assertThat(db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,b)).isEqualTo("validated");
            assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND status='valid'",Integer.class,b)).isEqualTo(3);
        }finally{db.execute("DROP TRIGGER fail_import_test ON students");db.execute("DROP FUNCTION fail_import_test()");}
        assertThat(commit(registrarToken,b,3).getStatusCode()).as("the same batch commits once the cause is gone").isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT count(*) FROM students WHERE student_number LIKE ?",Integer.class,"SYN-"+n+"-%")).isEqualTo(3);
    }
    @Test void concurrentCommitsCreateEachStudentOnceAndRefuseTheLoser()throws Exception{
        String n=n();var csv=new StringBuilder("Student No,LRN,Last Name,First Name,Birth Date,Voucher,Strand\r\n");var rnd=new Random(11);
        for(int i=0;i<150;i++)csv.append("SYN-").append(n).append('-').append(i).append(",,").append(word(rnd,12)).append(',').append(word(rnd,9)).append(",2009-").append(String.format("%02d-%02d",1+rnd.nextInt(12),1+rnd.nextInt(28))).append(",QVR,STEM\r\n");
        var up=upload(registrarToken,iligan,null,"big.csv",csv.toString().getBytes(StandardCharsets.UTF_8));long b=id(up.getBody());
        assertThat(up.getBody()).containsEntry("validCount",150);int version=batchVersion(b);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);List<Future<ResponseEntity<Map>>> results=new ArrayList<>();
        for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return call(HttpMethod.POST,"/batches/"+b+"/commit",Map.of("version",version,"expectedValidCount",150),registrarToken);}));
        start.countDown();List<Integer> codes=new ArrayList<>();for(var f:results)codes.add(f.get(60,TimeUnit.SECONDS).getStatusCode().value());pool.shutdown();
        assertThat(codes).as(codes.toString()).allMatch(c->c==200||c==409).contains(200);
        assertThat(db.queryForObject("SELECT count(*) FROM students WHERE student_number LIKE ?",Integer.class,"SYN-"+n+"-%")).isEqualTo(150);
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND status='imported'",Integer.class,b)).isEqualTo(150);
        assertThat(commit(registrarToken,b,150).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(db.queryForObject("SELECT count(*) FROM students WHERE student_number LIKE ?",Integer.class,"SYN-"+n+"-%")).isEqualTo(150);
    }
    String word(Random rnd,int length){StringBuilder s=new StringBuilder();for(int i=0;i<length;i++)s.append((char)('a'+rnd.nextInt(26)));return s.toString();}
    @Test void recordsCreatedAfterStagingAreRecheckedAndDemoteRowsInsteadOfCreatingDuplicates(){
        String n=n();long b=stage("synthetic-basic.csv",n);
        student("OTHER-"+n,"99"+n+"02","Someone","Else","1999-01-01",iligan);          // LRN of row 02 appears after staging
        student(number(n,"01"),null,"Number","Taker","1998-01-01",iligan);               // student number of row 01 taken after staging
        long before=students();var r=commit(registrarToken,b,3);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(r.getBody().get("detail").toString()).contains("changed since this batch was staged");
        assertThat(students()).isEqualTo(before);
        assertThat(status(b,number(n,"02"))).isEqualTo("duplicate_flagged");assertThat(status(b,number(n,"01"))).isEqualTo("invalid");assertThat(status(b,number(n,"03"))).isEqualTo("valid");
        assertThat(db.queryForList("SELECT error_code FROM import_exceptions WHERE batch_id=? AND row_number=2",String.class,b)).contains("STUDENT_NUMBER_CONFLICT");
        assertThat(db.queryForMap("SELECT valid_count,flagged_count,invalid_count FROM import_batches WHERE id=?",b)).containsEntry("valid_count",1).containsEntry("flagged_count",1).containsEntry("invalid_count",1);
        assertThat(db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,b)).isEqualTo("validated");
        assertThat(commit(registrarToken,b,1).getStatusCode()).as("the flagged row still blocks").isEqualTo(HttpStatus.CONFLICT);
        assertThat(review(registrarToken,b,rowId(b,number(n,"02")),"skip","Matches a student created meanwhile",null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(commit(registrarToken,b,1).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(students()).isEqualTo(before+1);
    }

    // ---- mappings ---------------------------------------------------------------------------------------------------
    JsonNode defaultBody(){return json.valueToTree(get("/mappings",adminToken).getBody()).get("items").get(0);}
    Object defaultMapping(){return json.convertValue(defaultBody().get("mapping"),Object.class);}
    Object defaultSettings(){return json.convertValue(defaultBody().get("settings"),Object.class);}
    Map<String,Object> mappingBody(String name,Object mapping,Object settings,Integer version){Map<String,Object> m=new HashMap<>();m.put("name",name);m.put("mapping",mapping);m.put("settings",settings);if(version!=null)m.put("version",version);return m;}
    @Test void mappingRulesAreAdminOnlyVersionedAndGuardTheDefaultAndInUseMappings(){
        var d=defaultBody();assertThat(d.get("isDefault").asBoolean()).isTrue();assertThat(d.get("realFileValidated").asBoolean()).isFalse();assertThat(d.at("/settings/normalizeNameCase").asBoolean()).as("name casing is off by default").isFalse();
        assertThat(get("/mappings/"+d.get("id").asLong(),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(get("/mappings/99999999",registrarToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String name="http-"+n();var created=call(HttpMethod.POST,"/mappings",mappingBody(name,defaultMapping(),defaultSettings(),null),adminToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);long id=id(created.getBody());assertThat(created.getBody()).containsEntry("version",0).containsEntry("isDefault",false).containsEntry("active",true);
        assertThat(call(HttpMethod.POST,"/mappings",mappingBody(name.toUpperCase(),defaultMapping(),defaultSettings(),null),adminToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var bad=(ObjectNode)json.valueToTree(defaultMapping());((ObjectNode)bad.get("fields")).putObject("nickname");
        var invalid=call(HttpMethod.POST,"/mappings",mappingBody("bad-"+n(),json.convertValue(bad,Object.class),defaultSettings(),null),adminToken);assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(invalid.getBody().get("detail").toString()).contains("Unknown field");
        assertThat(call(HttpMethod.POST,"/mappings",Map.of("mapping",defaultMapping(),"settings",defaultSettings()),adminToken).getStatusCode()).as("name required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT,"/mappings/"+id,mappingBody(name,defaultMapping(),defaultSettings(),null),adminToken).getStatusCode()).as("version required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT,"/mappings/"+id,mappingBody(name+"-b",defaultMapping(),defaultSettings(),0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var updated=call(HttpMethod.PUT,"/mappings/"+id,mappingBody(name+"-b",defaultMapping(),defaultSettings(),0),adminToken);assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(updated.getBody()).containsEntry("version",1).containsEntry("name",name+"-b");
        assertThat(call(HttpMethod.PUT,"/mappings/"+id,mappingBody(name,defaultMapping(),defaultSettings(),0),adminToken).getStatusCode()).as("stale version").isEqualTo(HttpStatus.CONFLICT);
        var deactivateDefault=mappingBody(d.get("name").asText(),defaultMapping(),defaultSettings(),d.get("version").asInt());deactivateDefault.put("isDefault",true);deactivateDefault.put("active",false);
        assertThat(call(HttpMethod.PUT,"/mappings/"+d.get("id").asLong(),deactivateDefault,adminToken).getStatusCode()).as("must keep an active default").isEqualTo(HttpStatus.CONFLICT);
        var unsetDefault=mappingBody(d.get("name").asText(),defaultMapping(),defaultSettings(),d.get("version").asInt());unsetDefault.put("isDefault",false);
        assertThat(call(HttpMethod.PUT,"/mappings/"+d.get("id").asLong(),unsetDefault,adminToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        long inUse=stageWithMapping(id);var deactivate=mappingBody(name+"-b",defaultMapping(),defaultSettings(),1);deactivate.put("active",false);
        assertThat(call(HttpMethod.PUT,"/mappings/"+id,deactivate,adminToken).getStatusCode()).as("used by an uncommitted batch").isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE import_batches SET status='failed' WHERE id=?",inUse);   // simulate the batch no longer awaiting commit
        assertThat(call(HttpMethod.PUT,"/mappings/"+id,deactivate,adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upload(registrarToken,iligan,id,"inactive.csv",fixture("synthetic-basic.csv",n())).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    long stageWithMapping(long mapping){var r=upload(registrarToken,iligan,mapping,"mapped.csv",fixture("synthetic-basic.csv",n()));assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);return id(r.getBody());}
    @Test void nameCasingStaysAsWrittenUnlessAMappingTurnsItOn(){
        String n=n();var csv=("Student No,Last Name,First Name,Birth Date,Voucher,Strand\r\nSYN-"+n+"-01,DELA CRUZ"+letters(n).toUpperCase()+",JUAN,2009-01-02,QVR,STEM\r\n").getBytes(StandardCharsets.UTF_8);
        long plain=id(upload(registrarToken,iligan,null,"plain.csv",csv).getBody());
        assertThat(db.queryForObject("SELECT normalized_values->>'firstName' FROM import_rows WHERE batch_id=?",String.class,plain)).isEqualTo("JUAN");
        var settings=(ObjectNode)json.valueToTree(defaultSettings());settings.put("normalizeNameCase",true);
        long mapping=id(call(HttpMethod.POST,"/mappings",mappingBody("casing-"+n,defaultMapping(),json.convertValue(settings,Object.class),null),adminToken).getBody());
        long cased=id(upload(registrarToken,iligan,mapping,"cased.csv",(new String(csv,StandardCharsets.UTF_8)+"\r\n").getBytes(StandardCharsets.UTF_8)).getBody());
        assertThat(db.queryForObject("SELECT normalized_values->>'firstName' FROM import_rows WHERE batch_id=?",String.class,cased)).isEqualTo("Juan");
    }

    // ---- discard (ICI2-37 review finding: a wrongly staged batch used to be stuck forever) ---------------------------------
    @Test void discardingAWronglyStagedBatchFreesTheFileAndStopsFalseDuplicateFlagsInOtherUploads(){
        String n=n();var bytes=fixture("synthetic-basic.csv",n);
        // The registrar of Bugo uploads a file meant for Iligan under the wrong campus.
        var wrong=upload(bugoToken,bugo,null,"iligan-export.csv",bytes);assertThat(wrong.getStatusCode()).as(wrong.toString()).isEqualTo(HttpStatus.CREATED);long wrongBatch=id(wrong.getBody());
        assertThat(upload(registrarToken,iligan,null,"iligan-export.csv",bytes).getStatusCode()).as("same bytes cannot be staged again while the wrong batch is live").isEqualTo(HttpStatus.CONFLICT);

        assertThat(call(HttpMethod.POST,"/batches/"+wrongBatch+"/discard",Map.of("version",batchVersion(wrongBatch),"reason","Wrong campus"),teacherToken).getStatusCode()).as("teachers cannot discard").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/batches/"+wrongBatch+"/discard",Map.of("version",batchVersion(wrongBatch),"reason","Not mine"),registrarToken).getStatusCode()).as("another campus's batch is invisible").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST,"/batches/"+wrongBatch+"/discard",Map.of("version",batchVersion(wrongBatch),"reason"," "),bugoToken).getStatusCode()).as("a reason is required").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,"/batches/"+wrongBatch+"/discard",Map.of("version",batchVersion(wrongBatch)+1,"reason","Wrong campus"),bugoToken).getStatusCode()).as("stale version").isEqualTo(HttpStatus.CONFLICT);

        int studentsBefore=(int)students();
        var discarded=call(HttpMethod.POST,"/batches/"+wrongBatch+"/discard",Map.of("version",batchVersion(wrongBatch),"reason","Uploaded under the wrong campus"),bugoToken);
        assertThat(discarded.getStatusCode()).as(discarded.toString()).isEqualTo(HttpStatus.OK);
        assertThat(discarded.getBody()).containsEntry("discarded",true).containsEntry("status","failed").containsEntry("alreadyDiscarded",false);
        var detail=get("/batches/"+wrongBatch,bugoToken).getBody();
        assertThat(detail).containsEntry("discarded",true).containsEntry("discardReason","Uploaded under the wrong campus").containsEntry("status","failed").containsKey("discardedAt");
        assertThat(students()).as("discarding never creates students").isEqualTo(studentsBefore);
        assertThat(call(HttpMethod.POST,"/batches/"+wrongBatch+"/discard",Map.of("version",0,"reason","again"),bugoToken).getBody()).as("repeat is a no-op").containsEntry("alreadyDiscarded",true);
        assertThat(commit(bugoToken,wrongBatch,3).getStatusCode()).as("a discarded batch cannot be committed").isEqualTo(HttpStatus.CONFLICT);

        // The same file can now be staged under the correct campus, and the discarded rows no longer flag its learners.
        var right=upload(registrarToken,iligan,null,"iligan-export.csv",bytes);
        assertThat(right.getStatusCode()).as("re-upload after discard: "+right).isEqualTo(HttpStatus.CREATED);
        long rightBatch=id(right.getBody());
        assertThat(db.queryForObject("SELECT count(*) FROM import_rows WHERE batch_id=? AND status='duplicate_flagged'",Integer.class,rightBatch))
                .as("rows flagged as duplicates of the discarded batch").isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_exceptions' AND operation='INSERT' AND actor_id=?",Integer.class,bugoRegistrar)).isGreaterThan(0);
    }

    @Test void aCommittedBatchCannotBeDiscarded(){
        var s=resolved();assertThat(commit(registrarToken,s.batch(),3).getStatusCode()).isEqualTo(HttpStatus.OK);
        var r=call(HttpMethod.POST,"/batches/"+s.batch()+"/discard",Map.of("version",batchVersion(s.batch()),"reason","Changed my mind"),registrarToken);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,s.batch())).isEqualTo("imported");
    }

    // ---- privacy ----------------------------------------------------------------------------------------------------
    @Test void personalValuesNeverAppearInImportAuditsOrErrorMessages(){
        var s=resolved();commit(registrarToken,s.batch(),3);String token=s.n();
        for(String table:List.of("import_batches","import_rows","import_exceptions","import_column_mappings"))
            assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name=? AND (new_data::text LIKE ? OR old_data::text LIKE ? OR new_data::text LIKE '%Gammathree%' OR new_data::text LIKE '%raw_values%' OR new_data::text LIKE '%normalized_values%')",Integer.class,table,"%"+token+"%","%"+token+"%")).as(table).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='import_rows' AND operation='UPDATE' AND actor_id=?",Integer.class,registrar)).isGreaterThan(0);
        long mixed=stage("synthetic-mixed.csv",n());
        assertThat(db.queryForList("SELECT detail FROM import_exceptions WHERE batch_id=?",String.class,mixed)).noneMatch(d->d.contains("2009")||d.contains("99"));
        var errors=get("/batches/"+mixed+"/errors?size=100",registrarToken).getBody();assertThat(errors.toString()).doesNotContain("40251");
    }
}
