package ph.edu.ici;

import java.util.*;
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
class HonorRollTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);}
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    long campus,other,program,term,period,section,teacher,subject1,subject2,class1,class2;
    String registrar,principal,foreign,teacherToken;
    String key(){return UUID.randomUUID().toString().replace("-","").substring(0,12);}
    long staff(String role,long c){String name="honor-"+key();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES(?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid",name,encoder.encode("Honor-Test-Password-2026!"));db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES(?,?,?)",id,role,c);return id;}
    String login(long id){return (String)rest.postForObject("/api/auth/login",Map.of("username",db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id),"password","Honor-Test-Password-2026!"),Map.class).get("accessToken");}
    @BeforeEach void setup(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);other=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES(?,'2026-01-01','2026-12-31') RETURNING id",Long.class,"HY"+key());
        term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES(?,'T1','Honor term','2026-01-01','2026-12-31') RETURNING id",Long.class,year);
        period=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES(?,'Q1',1) RETURNING id",Long.class,term);
        section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES(?,?,?,?,'11',30) RETURNING id",Long.class,campus,term,program,"HS"+key());
        teacher=staff("ROLE_TEACHER",campus);teacherToken=login(teacher);registrar=login(staff("ROLE_REGISTRAR",campus));principal=login(staff("ROLE_PRINCIPAL",campus));foreign=login(staff("ROLE_PRINCIPAL",other));
        subject1=db.queryForObject("INSERT INTO subjects(code,name) VALUES(?,'Math') RETURNING id",Long.class,"H"+key());subject2=db.queryForObject("INSERT INTO subjects(code,name) VALUES(?,'Science') RETURNING id",Long.class,"H"+key());
        class1=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES(?,?,?,?) RETURNING id",Long.class,section,term,subject1,teacher);class2=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES(?,?,?,?) RETURNING id",Long.class,section,term,subject2,teacher);
    }
    long student(String first){
        long st=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,program_id) VALUES(?,?,'Honorstudent','2009-01-01',?,?) RETURNING id",Long.class,"HST"+key(),first,campus,program);
        long app=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date) VALUES(?,?,?,?,?,'new',?,'Honorstudent','2009-01-01') RETURNING id",Long.class,"HA"+key(),campus,term,program,st,first);
        long e=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) VALUES(?,?,?,?,?,?,'completed') RETURNING id",Long.class,st,app,campus,term,program,section);
        for(long c:List.of(class1,class2))db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES(?,?,?,?)",c,e,section,term);return e;
    }
    void marks(long e,Object first,Object second,String state){for(var entry:List.of(new Object[]{class1,first},new Object[]{class2,second})){Object mark=entry[1] instanceof String?entry[1]:null,value=entry[1] instanceof String?null:entry[1];db.update("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,mark,state) VALUES(?,?,?,?,?,?,?)",entry[0],e,term,period,value,mark,state);}}
    Map<String,Object> rules(){return new LinkedHashMap<>(Map.of("subjects",List.of(Map.of("subjectId",subject1,"weight",1),Map.of("subjectId",subject2,"weight",3)),"decimalPlaces",2,"rounding","HALF_UP","minimumSubjectGrade",75,"categories",List.of(Map.of("name","Fictional distinction","minimumAverage",90)),"tiePolicy","competition_include_all","examples",List.of(Map.of("name","Fictional school example","values",List.of(88,92),"expectedAverage",91,"expectedCategory","Fictional distinction"))));}
    Map<String,Object> input(Map<String,Object> rules){return Map.of("campusId",campus,"programId",program,"gradeLevel","11","gradingPeriodId",period,"rules",rules);}
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);return rest.exchange(path,method,new HttpEntity<>(body,h),Map.class);}
    long create(){var r=call(HttpMethod.POST,"/api/honors/policies",input(rules()),registrar);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.CREATED);return ((Number)r.getBody().get("id")).longValue();}
    void approve(long id,int version){var r=call(HttpMethod.POST,"/api/honors/policies/"+id+"/approve",Map.of("version",version,"reference","Fictional authorized school test approval"),principal);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);}
    String roll(){return "/api/honors/roll?campusId="+campus+"&programId="+program+"&gradeLevel=11&gradingPeriodId="+period;}
    @Test void approvedRulesComputeWeightedFinalizedGradesAndTies(){
        long a=student("Alpha"),b=student("Beta"),c=student("Gamma");marks(a,88,92,"finalized");marks(b,91,91,"finalized");marks(c,90,90,"finalized");long id=create();approve(id,1);
        var r=call(HttpMethod.GET,roll(),null,principal);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);var items=(List<Map>)r.getBody().get("items");assertThat(items).hasSize(3);assertThat(items.get(0)).containsEntry("average",91.0).containsEntry("rank",1).containsEntry("eligible",true);assertThat(items.get(1)).containsEntry("rank",1);assertThat(items.get(2)).containsEntry("rank",3);
        assertThat(((Number)r.getBody().get("policyId")).longValue()).isEqualTo(id);
    }
    @Test void unapprovedPolicyAndInvalidExamplesCannotProduceOfficialRoll(){
        assertThat(call(HttpMethod.GET,roll(),null,principal).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);long id=create();assertThat(call(HttpMethod.GET,roll(),null,principal).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var bad=rules();bad.put("examples",List.of(Map.of("name","Incorrect","values",List.of(88,92),"expectedAverage",99,"expectedCategory","Fictional distinction")));assertThat(call(HttpMethod.POST,"/api/honors/policies",input(bad),registrar).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,"/api/honors/policies/"+id+"/approve",Map.of("version",1,"reference",""),principal).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void missingNonNumericDraftAndBelowMinimumGradesAreExplicitlyIneligible(){
        long inc=student("Incomplete"),missing=student("Missing"),draft=student("Draft"),low=student("Low");marks(inc,"INC",99,"finalized");marks(draft,99,99,"draft");marks(low,74,100,"finalized");
        long id=create();approve(id,1);var r=call(HttpMethod.GET,roll(),null,principal);assertThat(r.getStatusCode()).as(r.toString()).isEqualTo(HttpStatus.OK);var items=(List<Map>)r.getBody().get("items");assertThat(items).allSatisfy(i->assertThat(i).containsEntry("eligible",false).containsEntry("rank",null));
        assertThat((List)items.stream().filter(i->((Number)i.get("enrollmentId")).longValue()==inc).findFirst().orElseThrow().get("reasons")).contains("non_numeric_mark_INC");assertThat((List)items.stream().filter(i->((Number)i.get("enrollmentId")).longValue()==missing).findFirst().orElseThrow().get("reasons")).contains("missing_grade");assertThat((List)items.stream().filter(i->((Number)i.get("enrollmentId")).longValue()==draft).findFirst().orElseThrow().get("reasons")).contains("unfinalized_or_missing_grade");assertThat((List)items.stream().filter(i->((Number)i.get("enrollmentId")).longValue()==low).findFirst().orElseThrow().get("reasons")).contains("below_subject_minimum");
    }
    @Test void authorizationVersioningSupersessionAndAuditAreEnforced(){
        assertThat(call(HttpMethod.POST,"/api/honors/policies",input(rules()),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(call(HttpMethod.GET,roll(),null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(call(HttpMethod.GET,roll(),null,foreign).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        long id=create();String path="/api/honors/policies/"+id;assertThat(call(HttpMethod.GET,path,null,foreign).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);assertThat(call(HttpMethod.POST,path+"/approve",Map.of("version",1,"reference","Registrar cannot approve"),registrar).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT,path,Map.of("version",1,"rules",rules()),registrar).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(call(HttpMethod.POST,path+"/approve",Map.of("version",1,"reference","Stale"),principal).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);approve(id,2);
        assertThat(call(HttpMethod.PUT,path,Map.of("version",3,"rules",rules()),registrar).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);long replacement=create();approve(replacement,1);assertThat(call(HttpMethod.GET,path,null,principal).getBody()).containsEntry("status","superseded");assertThat(((Number)call(HttpMethod.GET,roll(),null,principal).getBody().get("policyId")).longValue()).isEqualTo(replacement);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='honor_policies' AND new_data->>'id'=? AND actor_id IS NOT NULL",Integer.class,Long.toString(id))).isGreaterThanOrEqualTo(4);
    }
    @Test void duplicateWeightsThresholdsAndUnfinalizedMembershipCannotSlipThrough(){
        var duplicate=rules();duplicate.put("subjects",List.of(Map.of("subjectId",subject1,"weight",1),Map.of("subjectId",subject1,"weight",3)));assertThat(call(HttpMethod.POST,"/api/honors/policies",input(duplicate),registrar).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var zero=rules();zero.put("subjects",List.of(Map.of("subjectId",subject1,"weight",0)));assertThat(call(HttpMethod.POST,"/api/honors/policies",input(zero),registrar).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var duplicateBand=rules();duplicateBand.put("categories",List.of(Map.of("name","A","minimumAverage",90),Map.of("name","B","minimumAverage",90)));assertThat(call(HttpMethod.POST,"/api/honors/policies",input(duplicateBand),registrar).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void roundingBoundaryUsesApprovedPolicyAndExcludesProvisionalOrWithdrawnStudents(){
        long e=student("Boundary"),provisional=student("Provisional"),withdrawn=student("Withdrawn");marks(e,90,90.06,"finalized");marks(provisional,100,100,"finalized");marks(withdrawn,100,100,"finalized");db.update("UPDATE enrollments SET status='provisional' WHERE id=?",provisional);db.update("UPDATE enrollments SET status='withdrawn' WHERE id=?",withdrawn);
        var up=rules();up.put("categories",List.of(Map.of("name","Boundary award","minimumAverage",90.05)));up.put("examples",List.of(Map.of("name","Half cent boundary","values",List.of(90,90.06),"expectedAverage",90.05,"expectedCategory","Boundary award")));
        var created=call(HttpMethod.POST,"/api/honors/policies",input(up),registrar);assertThat(created.getStatusCode()).as(created.toString()).isEqualTo(HttpStatus.CREATED);approve(((Number)created.getBody().get("id")).longValue(),1);
        var items=(List<Map>)call(HttpMethod.GET,roll(),null,principal).getBody().get("items");assertThat(items).hasSize(1);assertThat(items.getFirst()).containsEntry("average",90.05).containsEntry("eligible",true);
        up.put("rounding","HALF_EVEN");var example=new LinkedHashMap<String,Object>();example.put("name","Even rounding boundary");example.put("values",List.of(90,90.06));example.put("expectedAverage",90.04);example.put("expectedCategory",null);up.put("examples",List.of(example));
        var second=call(HttpMethod.POST,"/api/honors/policies",input(up),registrar);assertThat(second.getStatusCode()).as(second.toString()).isEqualTo(HttpStatus.CREATED);approve(((Number)second.getBody().get("id")).longValue(),1);
        var rounded=(List<Map>)call(HttpMethod.GET,roll(),null,principal).getBody().get("items");assertThat(rounded.getFirst()).containsEntry("average",90.04).containsEntry("eligible",false);
    }

    @Test void pendingCorrectionMakesAnOtherwiseQualifiedFinalizedGradeIneligible(){
        long e=student("Disputed");marks(e,99,99,"finalized");long grade=db.queryForObject("SELECT id FROM grades WHERE enrollment_id=? AND class_id=?",Long.class,e,class1);
        db.update("INSERT INTO grade_corrections(grade_id,original_value,proposed_value,justification,evidence_storage_key,requested_by,grade_version) VALUES(?,99,98,'Fictional disputed mark','fictional-reference',?,0)",grade,teacher);
        long id=create();approve(id,1);var items=(List<Map>)call(HttpMethod.GET,roll(),null,principal).getBody().get("items");assertThat(items).hasSize(1);assertThat(items.getFirst()).containsEntry("eligible",false);assertThat((List)items.getFirst().get("reasons")).contains("pending_grade_correction");
    }

    @Test void legacyApprovedCorrectionStaysIneligibleUntilAppliedOrRejected(){
        long e=student("Legacy disputed");marks(e,99,99,"finalized");long grade=db.queryForObject("SELECT id FROM grades WHERE enrollment_id=? AND class_id=?",Long.class,e,class1);
        long correction=db.queryForObject("INSERT INTO grade_corrections(grade_id,original_value,proposed_value,justification,evidence_storage_key,requested_by,grade_version,status,reviewed_by,reviewed_at) VALUES(?,99,98,'Fictional legacy approval','fictional-reference',?,0,'approved',?,current_timestamp) RETURNING id",Long.class,grade,teacher,teacher);
        long id=create();approve(id,1);
        var approved=(List<Map>)call(HttpMethod.GET,roll(),null,principal).getBody().get("items");assertThat(approved).hasSize(1);assertThat(approved.getFirst()).containsEntry("eligible",false);assertThat((List)approved.getFirst().get("reasons")).contains("pending_grade_correction");
        db.update("UPDATE grades SET value=98,version=version+1 WHERE id=?",grade);db.update("UPDATE grade_corrections SET status='applied' WHERE id=?",correction);
        var applied=(List<Map>)call(HttpMethod.GET,roll(),null,principal).getBody().get("items");assertThat(applied.getFirst()).containsEntry("eligible",true).containsEntry("average",98.75);
        db.update("UPDATE grade_corrections SET status='rejected' WHERE id=?",correction);
        var rejected=(List<Map>)call(HttpMethod.GET,roll(),null,principal).getBody().get("items");assertThat(rejected.getFirst()).containsEntry("eligible",true);assertThat((List)rejected.getFirst().get("reasons")).doesNotContain("pending_grade_correction");
    }


}
