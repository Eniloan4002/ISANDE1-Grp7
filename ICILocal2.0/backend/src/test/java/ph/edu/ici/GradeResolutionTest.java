package ph.edu.ici;

import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
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
class GradeResolutionTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }

    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    final String password="Grade-Test-Password-2026!";
    long campus,program,year,term,otherTerm,section,subject,period1,period2,classId;
    long teacher,otherTeacher,registrar,e1,e2,e3;
    String teacherToken,otherTeacherToken,registrarToken;

    String suffix(){return UUID.randomUUID().toString().replace("-","").substring(0,12);}
    long staff(String role){
        String name="grade-"+suffix();
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES(?,?,?,?) RETURNING id",Long.class,
                name,name+"@example.invalid",name,encoder.encode(password));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES(?,?,?)",id,role,campus);
        return id;
    }
    String login(long id){
        String name=db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id);
        return (String)rest.postForObject("/api/auth/login",Map.of("username",name,"password",password),Map.class).get("accessToken");
    }
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){
        var headers=new HttpHeaders();if(token!=null)headers.setBearerAuth(token);headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path,method,new HttpEntity<>(body,headers),Map.class);
    }
    long enrollment(String name){
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES (?,?,'Student','2009-01-01') RETURNING id",Long.class,"STU-"+suffix(),name);
        long application=db.queryForObject("""
            INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date)
            VALUES (?,?,?,?,?,'new',?,'Student','2009-01-01') RETURNING id
            """,Long.class,"APP-"+suffix(),campus,term,program,student,name);
        long enrollment=db.queryForObject("""
            INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status)
            VALUES (?,?,?,?,?,?,'provisional') RETURNING id
            """,Long.class,student,application,campus,term,program,section);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",classId,enrollment,section,term);
        return enrollment;
    }

    @BeforeEach void setup(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);
        program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        String key=suffix();
        year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-06-01','2027-04-30') RETURNING id",Long.class,"AY"+key);
        term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,?,'First Term','2026-06-01','2026-10-31') RETURNING id",Long.class,year,"T"+key);
        otherTerm=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,?,'Second Term','2026-11-01','2027-04-30') RETURNING id",Long.class,year,"U"+key);
        period1=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence,starts_on,ends_on) VALUES (?,'Q1',1,'2026-06-01','2026-08-15') RETURNING id",Long.class,term);
        period2=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence,starts_on,ends_on) VALUES (?,'Q2',2,'2026-08-16','2026-10-31') RETURNING id",Long.class,term);
        section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'Grade 11',30) RETURNING id",Long.class,campus,term,program,"Section "+key);
        subject=db.queryForObject("INSERT INTO subjects(code,name,units) VALUES (?,?,1) RETURNING id",Long.class,"SUB"+key,"Test Subject");
        teacher=staff("ROLE_TEACHER");otherTeacher=staff("ROLE_TEACHER");registrar=staff("ROLE_REGISTRAR");
        teacherToken=login(teacher);otherTeacherToken=login(otherTeacher);registrarToken=login(registrar);
        classId=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject,teacher);
        e1=enrollment("Ana");e2=enrollment("Ben");e3=enrollment("Cy");
    }

    String base(){return "/api/academic/classes/"+classId;}
    String gradesPath(long period){return base()+"/grades?academicYearId="+year+"&termId="+term+"&gradingPeriodId="+period;}
    Map<String,Object> entry(long enrollment,Object value,String mark,Integer version){
        var entry=new HashMap<String,Object>();entry.put("enrollmentId",enrollment);entry.put("value",value);entry.put("mark",mark);entry.put("version",version);return entry;
    }
    Map<String,Object> draft(long period,Map<String,Object>... entries){
        return Map.of("academicYearId",year,"termId",term,"gradingPeriodId",period,"entries",List.of(entries));
    }
    Map<String,Object> submitBody(long period){return Map.of("academicYearId",year,"termId",term,"gradingPeriodId",period);}
    Map<String,Object> student(Map body,long enrollment){
        return ((List<Map<String,Object>>)body.get("students")).stream().filter(row->((Number)row.get("enrollmentId")).longValue()==enrollment).findFirst().orElseThrow();
    }


    long inc(boolean finalized){return db.queryForObject("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,mark,state) VALUES (?,?,?,?,'INC',?) RETURNING id",Long.class,classId,e1,term,period1,finalized?"finalized":"submitted");}
    long caseId(long grade){return db.queryForObject("SELECT id FROM incomplete_cases WHERE grade_id=? AND status<>'resolved'",Long.class,grade);}
    String path(long id){return "/api/academic/incomplete-cases/"+id;}
    void lock(){assertThat(call(HttpMethod.POST,"/api/academic/sections/"+section+"/grading-periods/"+period1+"/lock",Map.of("version",0,"reason","Fictional QA lock"),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);}
    Map<String,Object> completion(int version,int gradeVersion,Object grade){return Map.of("version",version,"gradeVersion",gradeVersion,"grade",grade,"note","Verified fictional completion");}
    ResponseEntity<Map> request(long grade,int version,String filename,String media,byte[] bytes){
        var parts=new LinkedMultiValueMap<String,Object>();var jsonHeaders=new HttpHeaders();jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        parts.add("request",new HttpEntity<>(Map.of("gradeId",grade,"gradeVersion",version,"proposedValue",91.25,"justification","Fictional assessment correction"),jsonHeaders));
        var fileHeaders=new HttpHeaders();fileHeaders.setContentType(MediaType.parseMediaType(media));
        parts.add("evidence",new HttpEntity<>(new ByteArrayResource(bytes){@Override public String getFilename(){return filename;}},fileHeaders));
        var headers=new HttpHeaders();headers.setBearerAuth(teacherToken);headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange("/api/academic/grade-corrections",HttpMethod.POST,new HttpEntity<>(parts,headers),Map.class);
    }
    ResponseEntity<Map> request(long grade){return request(grade,0,"assessment.pdf","application/pdf","%PDF-1.4\nFictional evidence".getBytes(StandardCharsets.US_ASCII));}

    @Test void incompleteCasesPersistFollowupsProposalAndOpenPeriodCompletion(){
        long grade=inc(false),id=caseId(grade);
        var initial=call(HttpMethod.GET,path(id),null,registrarToken);
        assertThat(initial.getStatusCode()).as(initial.toString()).isEqualTo(HttpStatus.OK);
        assertThat(initial.getBody()).containsEntry("status","pending_teacher_submission").containsEntry("version",0);
        assertThat(call(HttpMethod.GET,path(id),null,otherTeacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var follow=call(HttpMethod.POST,path(id)+"/follow-ups",Map.of("version",0,"note","Contacted assigned teacher","method","Phone","actor","spoofed principal"),registrarToken);
        assertThat(follow.getStatusCode()).as(follow.toString()).isEqualTo(HttpStatus.OK);
        assertThat(follow.getBody()).containsEntry("version",1);
        assertThat((List)follow.getBody().get("followUps")).hasSize(1);
        var proposed=call(HttpMethod.POST,path(id)+"/proposals",completion(1,0,88.5),teacherToken);
        assertThat(proposed.getStatusCode()).as(proposed.toString()).isEqualTo(HttpStatus.OK);
        assertThat(proposed.getBody()).containsEntry("status","registrar_review").containsEntry("version",2);
        assertThat(call(HttpMethod.GET,path(id),null,registrarToken).getBody()).containsEntry("proposedGrade",88.5);
        assertThat(call(HttpMethod.POST,path(id)+"/complete",completion(1,0,88.5),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var completed=call(HttpMethod.POST,path(id)+"/complete",completion(2,0,88.5),registrarToken);
        assertThat(completed.getStatusCode()).as(completed.toString()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,path(id),null,teacherToken).getBody()).containsEntry("status","completed");
        assertThat(db.queryForObject("SELECT value FROM grades WHERE id=?",Double.class,grade)).isEqualTo(88.5);
        assertThat(db.queryForObject("SELECT state FROM grades WHERE id=?",String.class,grade)).isEqualTo("submitted");
        assertThat(db.queryForObject("SELECT resolved_by FROM incomplete_cases WHERE id=?",Long.class,id)).isEqualTo(registrar);
        assertThat(db.queryForObject("SELECT author_id FROM incomplete_follow_ups WHERE case_id=? ORDER BY id LIMIT 1",Long.class,id)).isEqualTo(registrar);
        assertThat(call(HttpMethod.POST,path(id)+"/follow-ups",Map.of("version",3,"note","Late","method","Phone"),teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void lockedIncompleteRequiresEvidenceAndAtomicRegistrarApproval(){
        long grade=inc(true),id=caseId(grade);
        var proposal=call(HttpMethod.POST,path(id)+"/proposals",completion(0,0,91.25),teacherToken);
        assertThat(proposal.getBody()).containsEntry("status","grade_correction");
        assertThat(call(HttpMethod.POST,path(id)+"/complete",completion(1,0,91.25),registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var created=request(grade);assertThat(created.getStatusCode()).as(created.toString()).isEqualTo(HttpStatus.CREATED);
        long correction=((Number)created.getBody().get("id")).longValue();String url="/api/academic/grade-corrections/"+correction;
        assertThat(request(grade).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.GET,url,null,otherTeacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var headers=new HttpHeaders();headers.setBearerAuth(registrarToken);
        var evidence=rest.exchange(url+"/evidence",HttpMethod.GET,new HttpEntity<>(headers),byte[].class);
        assertThat(evidence.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(evidence.getBody(),StandardCharsets.US_ASCII)).startsWith("%PDF-");
        assertThat(evidence.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        var approved=call(HttpMethod.POST,url+"/review",Map.of("version",0,"gradeVersion",0,"decision","approve","reason","Evidence verified by Registrar"),registrarToken);
        assertThat(approved.getStatusCode()).as(approved.toString()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()).containsEntry("status","applied").containsEntry("originalMark","INC");
        assertThat(db.queryForObject("SELECT state FROM grades WHERE id=?",String.class,grade)).isEqualTo("finalized");
        assertThat(db.queryForObject("SELECT value FROM grades WHERE id=?",Double.class,grade)).isEqualTo(91.25);
        assertThat(call(HttpMethod.GET,path(id),null,registrarToken).getBody()).containsEntry("status","completed");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='grades' AND actor_id=? AND old_data->>'mark'='INC' AND new_data->>'id'=?",Integer.class,registrar,Long.toString(grade))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='grade_correction_evidence' AND new_data ? 'content'",Integer.class)).isZero();
    }

    @Test void wrongRoleCampusStaleRequestsAndInvalidEvidenceAreRejected(){
        long grade=inc(false);lock();long id=caseId(grade);
        assertThat(request(grade,0,"assessment.pdf","application/pdf","<html>bad</html>".getBytes()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(request(grade,0,"../assessment.pdf","application/pdf","%PDF-1.4".getBytes()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(request(grade,0,"assessment.pdf","image/jpeg","%PDF-1.4".getBytes()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(request(grade,1,"assessment.pdf","application/pdf","%PDF-1.4".getBytes()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT count(*) FROM grade_corrections WHERE grade_id=?",Integer.class,grade)).isZero();
        long old=campus;campus=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);long other=staff("ROLE_REGISTRAR");String foreign=login(other);campus=old;
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_TEACHER',?)",other,campus);
        assertThat(call(HttpMethod.GET,path(id),null,foreign).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var created=request(grade);long correction=((Number)created.getBody().get("id")).longValue();String url="/api/academic/grade-corrections/"+correction;
        assertThat(call(HttpMethod.POST,url+"/review",Map.of("version",0,"gradeVersion",0,"decision","approve","reason","No access"),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,url+"/review",Map.of("version",0,"gradeVersion",0,"decision","approve","reason","No access"),foreign).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        db.update("UPDATE grades SET version=version+1 WHERE id=?",grade);
        assertThat(call(HttpMethod.POST,url+"/review",Map.of("version",0,"gradeVersion",1,"decision","approve","reason","Stale original"),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,url+"/review",Map.of("version",0,"gradeVersion",1,"decision","reject","reason","Original mark changed; resubmit evidence"),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT mark FROM grades WHERE id=?",String.class,grade)).isEqualTo("INC");
    }

    @Test void oversizedEvidenceAndSelfApprovalAreRejected(){
        long grade=inc(true);
        assertThat(request(grade,0,"assessment.pdf","application/pdf",new byte[1_000_001]).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        var created=request(grade);long correction=((Number)created.getBody().get("id")).longValue();
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_REGISTRAR',?)",teacher,campus);
        String dualRole=login(teacher);
        assertThat(call(HttpMethod.POST,"/api/academic/grade-corrections/"+correction+"/review",Map.of("version",0,"gradeVersion",0,"decision","approve","reason","Own request"),dualRole).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(db.queryForObject("SELECT version FROM grades WHERE id=?",Integer.class,grade)).isZero();
    }

    @Test void concurrentApprovalAppliesOnlyOnce()throws Exception{
        long grade=inc(true);var created=request(grade);long correction=((Number)created.getBody().get("id")).longValue();
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<ResponseEntity<Map>> task=()->{start.await();return call(HttpMethod.POST,"/api/academic/grade-corrections/"+correction+"/review",Map.of("version",0,"gradeVersion",0,"decision","approve","reason","Verified correction"),registrarToken);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS).getStatusCode(),b.get(15,TimeUnit.SECONDS).getStatusCode())).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
            assertThat(db.queryForObject("SELECT version FROM grades WHERE id=?",Integer.class,grade)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    @Test void incompleteListHasLiveFilterOptionsAndDraftIncHasNoCase(){
        long grade=inc(false);
        var list=call(HttpMethod.GET,"/api/academic/incomplete-cases?search=Ana",null,teacherToken);
        assertThat(list.getStatusCode()).as(list.toString()).isEqualTo(HttpStatus.OK);
        assertThat((List)list.getBody().get("items")).hasSize(1);
        assertThat((Map)list.getBody().get("filters")).containsKey("gradingPeriod");
        assertThat(call(HttpMethod.GET,"/api/academic/incomplete-cases?size=101",null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long draft=db.queryForObject("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,mark) VALUES (?,?,?,?,'INC') RETURNING id",Long.class,classId,e2,term,period1);
        assertThat(db.queryForObject("SELECT count(*) FROM incomplete_cases WHERE grade_id=?",Integer.class,draft)).isZero();
    }

    long foreignCase(){
        long bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);String key=suffix();
        long foreignSection=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'Grade 11',30) RETURNING id",Long.class,bugo,term,program,"Foreign "+key);
        long foreignClass=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,foreignSection,term,subject,teacher);
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES (?,'Foreign','Student','2009-01-01') RETURNING id",Long.class,"STU-"+key);
        long application=db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date) VALUES (?,?,?,?,?,'new','Foreign','Student','2009-01-01') RETURNING id",Long.class,"APP-"+key,bugo,term,program,student);
        long enrollment=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) VALUES (?,?,?,?,?,?,'provisional') RETURNING id",Long.class,student,application,bugo,term,program,foreignSection);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",foreignClass,enrollment,foreignSection,term);
        long grade=db.queryForObject("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,mark,state) VALUES (?,?,?,?,'INC','submitted') RETURNING id",Long.class,foreignClass,enrollment,term,period1);
        return caseId(grade);
    }
    List<Map<String,Object>> items(String query,String token){
        var response=call(HttpMethod.GET,"/api/academic/incomplete-cases"+query,null,token);
        assertThat(response.getStatusCode()).as(response.toString()).isEqualTo(HttpStatus.OK);
        return (List<Map<String,Object>>)response.getBody().get("items");
    }

    @Test void incompleteFiltersNarrowTotalsAndPagingWithinCampusScope(){
        long q1=inc(false);
        long q2=db.queryForObject("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,mark,state) VALUES (?,?,?,?,'INC','submitted') RETURNING id",Long.class,classId,e2,term,period2);
        long first=caseId(q1),second=caseId(q2);
        assertThat(items("?academicYearId="+year,registrarToken)).hasSize(2);
        var byPeriod=call(HttpMethod.GET,"/api/academic/incomplete-cases?academicYearId="+year+"&gradingPeriodId="+period2,null,registrarToken).getBody();
        assertThat(((Number)byPeriod.get("total")).intValue()).isEqualTo(1);
        assertThat(((List<Map>)byPeriod.get("items")).getFirst().get("id")).isEqualTo(String.valueOf(second));
        var paged=call(HttpMethod.GET,"/api/academic/incomplete-cases?academicYearId="+year+"&sectionId="+section+"&subjectId="+subject+"&teacherId="+teacher+"&size=1&page=1",null,registrarToken).getBody();
        assertThat(((Number)paged.get("total")).intValue()).isEqualTo(2);
        assertThat((List)paged.get("items")).hasSize(1);
        assertThat(items("?gradingPeriodId="+period1+"&teacherId="+otherTeacher,registrarToken)).isEmpty();
        assertThat(items("?academicYearId=-1",registrarToken)).isEmpty();
        assertThat(call(HttpMethod.GET,"/api/academic/incomplete-cases?subjectId=abc",null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void incompleteFiltersNeverExposeAnotherCampusCases(){
        long own=caseId(inc(false)),foreign=foreignCase();
        assertThat(items("?academicYearId="+year,registrarToken)).extracting(r->r.get("id")).containsExactly(String.valueOf(own));
        String query="?academicYearId="+year+"&gradingPeriodId="+period1+"&subjectId="+subject+"&teacherId="+teacher;
        assertThat(items(query,registrarToken)).extracting(r->r.get("id")).containsExactly(String.valueOf(own));
        long foreignSection=db.queryForObject("SELECT s.id FROM sections s JOIN class_offerings co ON co.section_id=s.id JOIN incomplete_cases ic ON ic.id=? JOIN grades g ON g.id=ic.grade_id AND g.class_id=co.id",Long.class,foreign);
        var direct=call(HttpMethod.GET,"/api/academic/incomplete-cases?sectionId="+foreignSection,null,registrarToken);
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List)direct.getBody().get("items")).isEmpty();
        assertThat(((Number)direct.getBody().get("total")).intValue()).isZero();
    }
}
