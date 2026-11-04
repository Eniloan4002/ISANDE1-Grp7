package ph.edu.ici;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequirementTrackingTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    long application,otherApplication,campus,actor;String token,teacherToken;
    @BeforeEach void fixture(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);
        long other=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        long program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-06-01','2027-04-30') RETURNING id",Long.class,UUID.randomUUID().toString().substring(0,20));
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','First semester','2026-06-01','2026-10-31') RETURNING id",Long.class,year);
        application=application(campus,term,program);otherApplication=application(other,term,program);
        String username="req-"+UUID.randomUUID();
        actor=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,username,username+"@example.invalid","Test Requirements Registrar",encoder.encode("Test-password-123!"));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_REGISTRAR',?)",actor,campus);
        token=login(username);
    }
    long application(long campus,long term,long program){return db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,category,first_name,last_name,birth_date) VALUES (?,?,?,?,'new','Test','Applicant','2009-01-01') RETURNING id",Long.class,"REQ-"+UUID.randomUUID(),campus,term,program);}
    String login(String name){return (String)rest.postForObject("/api/auth/login",Map.of("username",name,"password","Test-password-123!"),Map.class).get("accessToken");}
    String path(long id){return "/api/applications/"+id+"/requirements";}
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String bearer){HttpHeaders h=new HttpHeaders();if(bearer!=null)h.setBearerAuth(bearer);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(path,method,new HttpEntity<>(body,h),Map.class);}
    ResponseEntity<Map> update(String code,String status,int version){return call(HttpMethod.PUT,path(application)+"/"+code,Map.of("status",status,"version",version),token);}
    Map checklist(){var r=call(HttpMethod.GET,path(application),null,token);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);return r.getBody();}
    Map item(Map result,String code){return (Map)((List)result.get("items")).stream().map(value->(Map)value).filter(value->code.equals(((Map)value).get("code"))).findFirst().orElseThrow();}
    String overall(Map result){return (String)((Map)result.get("summary")).get("overallStatus");}
    int auditCount(){return ((Number)call(HttpMethod.GET,path(application)+"/audit",null,token).getBody().get("total")).intValue();}

    @Test void unrecordedChecklistIsPendingAndReadsDoNotManufactureHistory(){
        Map result=checklist();assertThat((List)result.get("items")).hasSize(4);assertThat(overall(result)).isEqualTo("Pending");
        for(Object value:(List)result.get("items")){Map item=(Map)value;assertThat(item).containsEntry("status","pending").containsEntry("recorded",false).containsEntry("version",0);}
        assertThat(((Map)result.get("summary")).get("credentialsComplete")).isEqualTo(false);
        assertThat(db.queryForObject("SELECT count(*) FROM application_requirements WHERE application_id=?",Integer.class,application)).isZero();
        assertThat(auditCount()).isZero();checklist();assertThat(auditCount()).isZero();
    }
    @Test void mandatoryStatusesDriveAllThreeSummaryStates(){
        assertThat(overall(update("PSA","submitted",0).getBody())).isEqualTo("Pending");
        assertThat(overall(update("FORM137","missing",0).getBody())).isEqualTo("Incomplete");
        assertThat(overall(update("FORM137","invalid",1).getBody())).isEqualTo("Incomplete");
        assertThat(overall(update("FORM137","pending",2).getBody())).isEqualTo("Pending");
        update("FORM137","submitted",3);update("COMPLETION","submitted",0);
        Map result=update("GOOD_MORAL","submitted",0).getBody();assertThat(overall(result)).isEqualTo("Complete");
        assertThat(((Map)result.get("summary"))).containsEntry("submitted",4).containsEntry("credentialsComplete",true);
        assertThat(overall(checklist())).isEqualTo("Complete");
    }
    @Test void actualWritesAreAttributedAndHistoryPreservesPreviousValues(){
        var first=call(HttpMethod.PUT,path(application)+"/PSA",Map.of("status","submitted","version",0,"notes","Original verified"),token);
        Map saved=item(first.getBody(),"PSA");assertThat(((Number)saved.get("verifiedBy")).longValue()).isEqualTo(actor);assertThat(saved.get("verifiedAt")).isNotNull();
        call(HttpMethod.PUT,path(application)+"/PSA",Map.of("status","invalid","version",1,"notes","Unreadable certificate"),token);
        Map current=item(checklist(),"PSA");assertThat(current.get("verifiedBy")).isNull();assertThat(current.get("verifiedAt")).isNull();assertThat(current.get("version")).isEqualTo(2);
        var history=call(HttpMethod.GET,path(application)+"/audit?code=PSA&size=1",null,token).getBody();
        assertThat(history.get("total")).isEqualTo(2);assertThat((List)history.get("items")).hasSize(1);
        Map event=(Map)((List)history.get("items")).getFirst();assertThat(event).containsEntry("oldStatus","submitted").containsEntry("newStatus","invalid").containsEntry("oldNotes","Original verified").containsEntry("newNotes","Unreadable certificate");
        assertThat(((Number)event.get("actorId")).longValue()).isEqualTo(actor);
        assertThat(event.get("actorName")).isEqualTo("Test Requirements Registrar");
        assertThat(db.queryForObject("SELECT created_by FROM application_requirements WHERE application_id=? AND requirement_code='PSA'",Long.class,application)).isEqualTo(actor);
        assertThatThrownBy(()->db.update("DELETE FROM audit_logs WHERE table_name='application_requirements'")).hasMessageContaining("append-only");
    }
    @Test void noOpsAndStaleUpdatesDoNotCreateFalseAuditEntries(){
        update("PSA","submitted",0);assertThat(auditCount()).isEqualTo(1);
        Map original=item(checklist(),"PSA");
        var noop=update("PSA","submitted",1);assertThat(noop.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(item(noop.getBody(),"PSA").get("version")).isEqualTo(1);assertThat(auditCount()).isEqualTo(1);
        assertThat(update("PSA","invalid",0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(auditCount()).isEqualTo(1);
        var note=call(HttpMethod.PUT,path(application)+"/PSA",Map.of("status","submitted","version",1,"notes","Follow-up note"),token);
        assertThat(note.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(item(note.getBody(),"PSA").get("verifiedAt")).isEqualTo(original.get("verifiedAt"));assertThat(auditCount()).isEqualTo(2);
    }
    @Test void invalidInputsAndUnknownCodesLeaveNoPartialWrites(){
        for(Map body:List.of(Map.of("status","unsupported","version",0),Map.of("status","submitted"),Map.of("status","pending","version",-1),Map.of("status","missing","version",0,"notes","x".repeat(2001))))
            assertThat(call(HttpMethod.PUT,path(application)+"/PSA",body,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(update("UNKNOWN","submitted",0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET,path(application)+"/audit?size=101",null,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET,path(application)+"/audit?page=-1",null,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET,path(Long.MAX_VALUE),null,token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(auditCount()).isZero();
    }
    @Test void rolesAndCampusesProtectReadWriteAndAudit(){
        assertThat(call(HttpMethod.GET,path(application),null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for(String suffix:List.of("","/audit"))assertThat(call(HttpMethod.GET,path(otherApplication)+suffix,null,token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,path(otherApplication)+"/PSA",Map.of("status","submitted","version",0),token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        db.update("DELETE FROM user_role_grants WHERE user_id=?",actor);db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_TEACHER',?)",actor,campus);
        for(String suffix:List.of("","/audit"))assertThat(call(HttpMethod.GET,path(application)+suffix,null,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(update("PSA","submitted",0).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void optionalDocumentsDoNotBlockMandatoryCompletion(){
        String code="OPT"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        db.update("INSERT INTO requirement_types(code,name,mandatory) VALUES (?,'Optional document',false)",code);
        try{
            for(String required:List.of("PSA","FORM137","COMPLETION","GOOD_MORAL"))update(required,"submitted",0);
            var optional=update(code,"invalid",0);assertThat(optional.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(overall(optional.getBody())).isEqualTo("Complete");
            assertThat(((Map)checklist().get("summary")).get("mandatoryTotal")).isEqualTo(4);
        }finally{
            db.update("DELETE FROM application_requirements WHERE requirement_code=?",code);
            db.update("DELETE FROM requirement_types WHERE code=?",code);
        }
    }
    @Test void simultaneousFirstWritesReturnOneConflictInsteadOfOverwriting() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            List<Future<HttpStatusCode>> futures=new ArrayList<>();
            for(String status:List.of("submitted","missing"))futures.add(executor.submit(()->{start.await();return update("PSA",status,0).getStatusCode();}));
            start.countDown();List<Integer> statuses=new ArrayList<>();for(var future:futures)statuses.add(future.get(15,TimeUnit.SECONDS).value());
            assertThat(statuses).containsExactlyInAnyOrder(200,409);
        }
        assertThat(item(checklist(),"PSA").get("version")).isEqualTo(1);assertThat(auditCount()).isEqualTo(1);
    }
    @Test void unrelatedApplicationsDoNotLeakIntoAuditPages(){
        update("PSA","submitted",0);update("PSA","invalid",1);
        var first=call(HttpMethod.GET,path(application)+"/audit?size=1&page=0",null,token).getBody();
        var second=call(HttpMethod.GET,path(application)+"/audit?size=1&page=1",null,token).getBody();
        assertThat(first.get("total")).isEqualTo(2);assertThat(second.get("total")).isEqualTo(2);
        assertThat(((Map)((List)first.get("items")).getFirst()).get("newStatus")).isEqualTo("invalid");
        assertThat(((Map)((List)second.get("items")).getFirst()).get("newStatus")).isEqualTo("submitted");
        assertThat(call(HttpMethod.GET,path(otherApplication)+"/audit",null,token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
