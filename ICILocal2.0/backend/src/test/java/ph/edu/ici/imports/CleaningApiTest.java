package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import ph.edu.ici.PostgresSupport;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.imports.GradeMatrixValidationService.UploadContext;
import static org.assertj.core.api.Assertions.*;

/** ICI2-39 phase 2 over real local HTTP: the new batch fields (sourceFormat, cleaningSummary), the new row field (cleaning) and mapping-save validation of the new settings. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CleaningApiTest {
    static final String STUDENTS="/api/legacy-student-imports",GRADES="/api/legacy-grade-imports",PASSWORD="Import-Test-Password-2026!";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;
    @Autowired GradeMatrixValidationService grades;
    long iligan,bugo,registrar;String registrarToken,bugoToken,adminToken,teacherToken;

    long staff(String role,Long campus){
        String name="clean-api-"+UUID.randomUUID();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid","Synthetic "+role,encoder.encode(PASSWORD));
        if(campus!=null)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);else db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,?,id FROM campuses",id,role);
        return id;
    }
    String login(long id){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id),"password",PASSWORD),Map.class).get("accessToken");}
    @BeforeEach void setup(){
        iligan=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        registrar=staff("ROLE_REGISTRAR",iligan);registrarToken=login(registrar);bugoToken=login(staff("ROLE_REGISTRAR",bugo));adminToken=login(staff("ROLE_ADMIN",null));teacherToken=login(staff("ROLE_TEACHER",iligan));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void as(long id){var identity=new StaffIdentity(id,"u"+id,"Synthetic staff",List.of(),UUID.randomUUID(),Instant.now().plusSeconds(600),false);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));}

    ResponseEntity<Map> call(String base,HttpMethod method,String path,Object body,String token){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(base+path,method,new HttpEntity<>(body,h),Map.class);}
    ResponseEntity<Map> upload(String token,Long mapping,byte[] bytes){
        var h=new HttpHeaders();h.setBearerAuth(token);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var form=new LinkedMultiValueMap<String,Object>();form.add("campusId",Long.toString(iligan));if(mapping!=null)form.add("mappingId",Long.toString(mapping));
        form.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return "cleaning.csv";}});
        return rest.exchange(STUDENTS+"/batches",HttpMethod.POST,new HttpEntity<>(form,h),Map.class);
    }
    Map<String,Object> defaultBody(String base){return (Map<String,Object>)((List<Map<String,Object>>)call(base,HttpMethod.GET,"/mappings",null,adminToken).getBody().get("items")).getFirst();}
    Map<String,Object> mappingBody(String base,String name,java.util.function.Consumer<ObjectNode> settingsEdit){
        var d=defaultBody(base);ObjectNode settings=json.valueToTree(d.get("settings"));settingsEdit.accept(settings);
        Map<String,Object> body=new HashMap<>();body.put("name",name);body.put("mapping",d.get("mapping"));body.put("settings",json.convertValue(settings,Object.class));return body;
    }
    static void cleaning(ObjectNode s){
        s.putArray("dateFormats").add("yyyy-MM-dd").add("M/d/yyyy");
        var c=s.putObject("cleaning");c.put("unicodeSpaces",true).put("extendedDateFormats",true).putArray("blankValues").add("n/a");
    }

    @Test void mappingSaveAcceptsTheNewSettingsAndRejectsUnknownOnesOverHttp(){
        for(String base:List.of(STUDENTS,GRADES)){
            var ok=call(base,HttpMethod.POST,"/mappings",mappingBody(base,"keys-"+UUID.randomUUID(),s->{s.put("nativeDates","accept");s.putObject("cleaning").put("unicodeSpaces",true);}),adminToken);
            assertThat(ok.getStatusCode()).as(base+" "+ok.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(((Map<String,Object>)((Map<String,Object>)ok.getBody().get("settings")).get("cleaning")).get("unicodeSpaces")).isEqualTo(true);
            var unknownCleaning=call(base,HttpMethod.POST,"/mappings",mappingBody(base,"bad-"+UUID.randomUUID(),s->s.putObject("cleaning").put("surprise",true)),adminToken);
            assertThat(unknownCleaning.getStatusCode()).as(base).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(String.valueOf(unknownCleaning.getBody().get("detail"))).contains("Unknown cleaning key 'surprise'");
            var unknownSetting=call(base,HttpMethod.POST,"/mappings",mappingBody(base,"bad2-"+UUID.randomUUID(),s->s.put("surprise",1)),adminToken);
            assertThat(unknownSetting.getStatusCode()).as(base).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(String.valueOf(unknownSetting.getBody().get("detail"))).contains("Unknown setting 'surprise'");
            assertThat(call(base,HttpMethod.POST,"/mappings",mappingBody(base,"reg-"+UUID.randomUUID(),s->{}),registrarToken).getStatusCode()).as("mapping writes stay Administrator-only").isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test void studentBatchAndRowViewsCarryTheSourceFormatCleaningSummaryAndRowLog()throws Exception{
        String n=ImportFixtures.n(),l=ImportFixtures.letters(n);
        long mapping=((Number)call(STUDENTS,HttpMethod.POST,"/mappings",mappingBody(STUDENTS,"api-"+UUID.randomUUID(),CleaningApiTest::cleaning),adminToken).getBody().get("id")).longValue();
        String csv="Student No,LRN,Last Name,First Name,Middle Name,Birth Date,Sex,Previous School,Voucher,Strand\n"
            +"SYN-"+n+"-01,99"+n+"01,Alphaone"+l+",Ana,,3/14/2009,,n/a,QVR,STEM\nSYN-"+n+"-02,99"+n+"02,Betatwo "+l+",Ben,,2009-11-02,,Synthetic Junior High,ESC,ABM\n";
        var staged=upload(registrarToken,mapping,csv.getBytes(StandardCharsets.UTF_8));
        assertThat(staged.getStatusCode()).as(String.valueOf(staged.getBody())).isEqualTo(HttpStatus.CREATED);
        var batch=staged.getBody();long id=((Number)batch.get("id")).longValue();
        assertThat(batch).containsEntry("sourceFormat","csv");
        var counts=(List<Map<String,Object>>)batch.get("cleaningSummary");
        assertThat(counts).extracting(c->c.get("rule")).contains("placeholder_to_blank","date_parsed:M/d/yyyy","unicode_spaces");
        assertThat(counts).filteredOn(c->"placeholder_to_blank".equals(c.get("rule"))).singleElement().satisfies(c->assertThat(c).containsEntry("count",1).containsEntry("trivial",false));
        assertThat(counts).filteredOn(c->"unicode_spaces".equals(c.get("rule"))).singleElement().satisfies(c->assertThat(c).containsEntry("count",1).containsEntry("trivial",true));
        var detail=call(STUDENTS,HttpMethod.GET,"/batches/"+id,null,registrarToken);assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(detail.getBody()).containsEntry("sourceFormat","csv");assertThat(detail.getBody().get("cleaningSummary")).isEqualTo(counts);
        var rows=(List<Map<String,Object>>)call(STUDENTS,HttpMethod.GET,"/batches/"+id+"/rows",null,registrarToken).getBody().get("items");
        var log=(List<Map<String,Object>>)rows.get(0).get("cleaning");
        assertThat(log).extracting(e->e.get("rule")).containsExactly("date_parsed:M/d/yyyy","placeholder_to_blank");
        assertThat(log.get(0)).containsEntry("field","birthDate").containsEntry("column","Birth Date").containsEntry("before","3/14/2009").containsEntry("after","2009-03-14");
        assertThat(log.get(1)).containsEntry("before","n/a").containsEntry("after",null);
        assertThat(rows.get(0)).containsKey("rawValues");assertThat(((Map<String,Object>)rows.get(0).get("rawValues")).get("Birth Date")).isEqualTo("3/14/2009");
        assertThat(rows.get(1)).as("only a trivial change, so nothing to show").containsEntry("cleaning",null);
        assertThat(((List<Map<String,Object>>)rows.get(0).get("messages")).stream().map(m->m.get("code"))).containsExactly("VALUE_CLEANED");
        var listed=(List<Map<String,Object>>)call(STUDENTS,HttpMethod.GET,"/batches?campusId="+iligan,null,registrarToken).getBody().get("items");
        assertThat(listed).anySatisfy(b->assertThat(b).containsEntry("sourceFormat","csv").containsKey("cleaningSummary"));
        assertThat(call(STUDENTS,HttpMethod.GET,"/batches/"+id+"/rows",null,bugoToken).getStatusCode()).as("another campus").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(STUDENTS,HttpMethod.GET,"/batches/"+id+"/rows",null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(STUDENTS,HttpMethod.GET,"/batches/"+id+"/rows",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test void gradeBatchAndRowViewsCarryTheSameNewFields()throws Exception{
        String n=ImportFixtures.n();
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2018-06-01','2019-03-31') RETURNING id",Long.class,"SY"+n);
        long term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','S1','2018-06-01','2018-10-31') RETURNING id",Long.class,year);
        db.update("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1)",term);db.update("INSERT INTO subjects(code,name) VALUES (?,'Synthetic mathematics')","SYNMATH"+n);
        db.update("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id) VALUES (?,?,?,'2009-01-01',?)","SYN-"+n+"-01","Stu","Syn"+ImportFixtures.letters(n),iligan);
        long mapping=((Number)call(GRADES,HttpMethod.POST,"/mappings",mappingBody(GRADES,"gapi-"+UUID.randomUUID(),s->s.putObject("cleaning").putArray("blankValues").add("n/a")),adminToken).getBody().get("id")).longValue();
        as(registrar);
        long id=grades.stageGradeMatrix(iligan,new UploadContext(null,term,"Grade 7",null),"g.csv",("Student No,Grade Level,SYNMATH"+n+" - Q1\nSYN-"+n+"-01,Grade 7, N/A \n").getBytes(StandardCharsets.UTF_8),mapping).batchId();
        SecurityContextHolder.clearContext();
        var batch=call(GRADES,HttpMethod.GET,"/batches/"+id,null,registrarToken).getBody();
        assertThat(batch).containsEntry("sourceFormat","csv");
        assertThat((List<Map<String,Object>>)batch.get("cleaningSummary")).extracting(c->c.get("rule")).contains("placeholder_to_blank","trim");
        var row=((List<Map<String,Object>>)call(GRADES,HttpMethod.GET,"/batches/"+id+"/rows",null,registrarToken).getBody().get("items")).getFirst();
        assertThat((List<Map<String,Object>>)row.get("cleaning")).singleElement().satisfies(e->assertThat(e).containsEntry("rule","placeholder_to_blank").containsEntry("before","N/A").containsEntry("after",null).containsEntry("column","SYNMATH"+n+" - Q1"));
        assertThat(call(GRADES,HttpMethod.GET,"/batches/"+id+"/rows",null,bugoToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
