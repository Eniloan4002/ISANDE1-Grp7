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
class HistoryWithdrawalTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }

    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    final String password="Academic-Test-Password-2026!";
    long campus,otherCampus,program,year,term,section,subject;
    long registrar,teacher,otherTeacher,otherCampusTeacher,adviser,otherAdviser;
    String registrarToken,teacherToken,otherTeacherToken,adviserToken,otherAdviserToken;

    String suffix(){return UUID.randomUUID().toString().replace("-","").substring(0,12);}
    long staff(String role,long assignedCampus){
        String name="academic-"+suffix();
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES(?,?,?,?) RETURNING id",Long.class,
                name,name+"@example.invalid",name,encoder.encode(password));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES(?,?,?)",id,role,assignedCampus);
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

    @BeforeEach void setup(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);
        otherCampus=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        String key=suffix();
        year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-06-01','2027-04-30') RETURNING id",Long.class,"AY"+key);
        term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,?,'First Term','2026-06-01','2026-10-31') RETURNING id",Long.class,year,"T"+key);
        section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'Grade 11',30) RETURNING id",Long.class,campus,term,program,"Section "+key);
        subject=db.queryForObject("INSERT INTO subjects(code,name,units) VALUES (?,?,1) RETURNING id",Long.class,"SUB"+key,"Test Subject");
        registrar=staff("ROLE_REGISTRAR",campus);teacher=staff("ROLE_TEACHER",campus);otherTeacher=staff("ROLE_TEACHER",campus);
        otherCampusTeacher=staff("ROLE_TEACHER",otherCampus);adviser=staff("ROLE_ADVISER",campus);otherAdviser=staff("ROLE_ADVISER",campus);
        registrarToken=login(registrar);teacherToken=login(teacher);otherTeacherToken=login(otherTeacher);
        adviserToken=login(adviser);otherAdviserToken=login(otherAdviser);
    }

    Map<String,Object> offeringInput(long teacherId){
        return Map.of("academicYearId",year,"termId",term,"sectionId",section,"subjectId",subject,"teacherId",teacherId);
    }
    Map<String,Object> offeringInput(long sectionId,long subjectId,long teacherId){
        return Map.of("academicYearId",year,"termId",term,"sectionId",sectionId,"subjectId",subjectId,"teacherId",teacherId);
    }
    Map<String,Object> createOffering(long teacherId){
        var response=call(HttpMethod.POST,"/api/academic/class-offerings",offeringInput(teacherId),registrarToken);
        assertThat(response.getStatusCode()).as(response.toString()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
    Map<String,Object> createOffering(long sectionId,long subjectId,long teacherId){
        var response=call(HttpMethod.POST,"/api/academic/class-offerings",offeringInput(sectionId,subjectId,teacherId),registrarToken);
        assertThat(response.getStatusCode()).as(response.toString()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
    long studentEnrollment(String name){
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,phone) VALUES (?,?,'Student','2009-01-01','09171234567') RETURNING id",Long.class,"STU-"+suffix(),name);
        db.update("UPDATE students SET campus_id=?,program_id=? WHERE id=?",campus,program,student);
        long application=db.queryForObject("""
            INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date)
            VALUES (?,?,?, ?,?,'new',?,'Student','2009-01-01') RETURNING id
            """,Long.class,"APP-"+suffix(),campus,term,program,student,name);
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status) VALUES (?,'FORM137','missing')",application);
        return db.queryForObject("""
            INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status)
            VALUES (?,?,?,?,?,?,'provisional') RETURNING id
            """,Long.class,student,application,campus,term,program,section);
    }
    long withdrawal(long enrollment,String reason){
        long id=db.queryForObject("INSERT INTO withdrawals(enrollment_id,reason) VALUES (?,?) RETURNING id",Long.class,enrollment,reason);
        db.update("INSERT INTO withdrawal_clearances(withdrawal_id,office,status) VALUES (?,'adviser','pending')",id);
        return id;
    }


    String root(){return "/api/academic/withdrawals";}
    Map<String,Object> createWithdrawal(long enrollment){
        var response=call(HttpMethod.POST,root(),Map.of("enrollmentId",enrollment,"enrollmentVersion",0,"reason","Fictional family relocation"),registrarToken);
        assertThat(response.getStatusCode()).as(response.toString()).isEqualTo(HttpStatus.CREATED);return response.getBody();
    }
    Map<String,Object> decision(int aggregate,int office,String action){return Map.of("withdrawalVersion",aggregate,"version",office,"decision",action,"notes","Fictional clearance reference verified");}
    long enroll(){db.update("UPDATE sections SET adviser_id=? WHERE id=?",adviser,section);return studentEnrollment("Withdrawal");}
    String principalToken(){return login(staff("ROLE_PRINCIPAL",campus));}
    String cashierToken(){return login(staff("ROLE_CASHIER_REF",campus));}

    @Test void officeChainUpdatesOnlyTheTermStandingAndRetainsHistory(){
        long enrollment=enroll();var created=createWithdrawal(enrollment);long id=((Number)created.get("id")).longValue();
        long student=((Number)created.get("studentId")).longValue();String principal=principalToken(),cashier=cashierToken();
        assertThat(created).containsEntry("version",0).containsEntry("status","pending").containsEntry("credentialReleaseEligible",false);
        assertThat((List)created.get("clearances")).hasSize(3);
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/principal",decision(0,0,"cleared"),principal).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/cashier",decision(0,0,"cleared"),cashier).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/principal",decision(0,0,"cleared"),registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var adviserResult=call(HttpMethod.PUT,"/api/academic/withdrawal-endorsements/"+id,Map.of("version",0,"decision","cleared","notes","Fictional adviser endorsement"),adviserToken);
        assertThat(adviserResult.getStatusCode()).as(adviserResult.toString()).isEqualTo(HttpStatus.OK);
        assertThat(adviserResult.getBody()).containsEntry("version",1).containsEntry("withdrawalVersion",1);
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/principal",decision(0,0,"cleared"),principal).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var approved=call(HttpMethod.PUT,root()+"/"+id+"/clearances/principal",decision(1,0,"cleared"),principal);
        assertThat(approved.getStatusCode()).as(approved.toString()).isEqualTo(HttpStatus.OK);assertThat(approved.getBody()).containsEntry("status","approved").containsEntry("version",2);
        var complete=call(HttpMethod.PUT,root()+"/"+id+"/clearances/cashier",decision(2,0,"cleared"),cashier);
        assertThat(complete.getStatusCode()).as(complete.toString()).isEqualTo(HttpStatus.OK);
        assertThat(complete.getBody()).containsEntry("status","completed").containsEntry("version",3).containsEntry("enrollmentStatus","withdrawn").containsEntry("credentialReleaseEligible",true);
        var reloaded=call(HttpMethod.GET,root()+"/"+id,null,registrarToken);
        assertThat(reloaded.getBody()).containsEntry("status","completed").containsEntry("version",3);
        assertThat((List)reloaded.getBody().get("history")).hasSizeGreaterThanOrEqualTo(10);
        assertThat(db.queryForObject("SELECT active FROM students WHERE id=?",Boolean.class,student)).isTrue();
        assertThat(db.queryForObject("SELECT status FROM applications WHERE id=(SELECT application_id FROM enrollments WHERE id=?)",String.class,enrollment)).isEqualTo("withdrawn");
        assertThat(call(HttpMethod.GET,root()+"/"+id+"/release-eligibility",null,cashier).getBody()).containsEntry("eligible",true);
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/cashier",decision(3,1,"cleared"),cashier).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='enrollments' AND new_data->>'id'=? AND new_data->>'status'='withdrawn' AND actor_id IS NOT NULL",Integer.class,Long.toString(enrollment))).isEqualTo(1);
    }

    @Test void rejectionBlocksReleaseAndDoesNotWithdrawStudent(){
        long enrollment=enroll();var created=createWithdrawal(enrollment);long id=((Number)created.get("id")).longValue();
        long student=((Number)created.get("studentId")).longValue();
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/adviser",decision(0,0,"rejected"),adviserToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,root()+"/"+id,null,registrarToken).getBody()).containsEntry("status","rejected").containsEntry("credentialReleaseEligible",false);
        assertThat(call(HttpMethod.GET,root()+"/"+id+"/release-eligibility",null,registrarToken).getBody()).containsEntry("eligible",false);
        assertThat(db.queryForObject("SELECT status FROM enrollments WHERE id=?",String.class,enrollment)).isEqualTo("provisional");
        assertThatThrownBy(()->db.update("INSERT INTO document_requests(student_id,document_type,status) VALUES (?,'TOR','approved')",student)).hasMessageContaining("Credential release");
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/principal",decision(1,0,"cleared"),principalToken()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void wrongCampusWrongOfficeAndUnassignedAdviserAreDenied(){
        var created=createWithdrawal(enroll());long id=((Number)created.get("id")).longValue();
        String foreign=login(staff("ROLE_PRINCIPAL",otherCampus));
        assertThat(call(HttpMethod.GET,root()+"/"+id,null,foreign).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/principal",decision(0,0,"cleared"),foreign).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/adviser",decision(0,0,"cleared"),otherAdviserToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET,root()+"/"+id,null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,root()+"?campusId="+otherCampus,null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var missingVersion=Map.of("decision","cleared","notes","Missing version");
        assertThat(call(HttpMethod.PUT,root()+"/"+id+"/clearances/adviser",missingVersion,adviserToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET,root()+"?size=101",null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void competingOfficeDecisionsHaveOneWinner()throws Exception{
        long id=((Number)createWithdrawal(enroll()).get("id")).longValue();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<ResponseEntity<Map>> task=()->{start.await();return call(HttpMethod.PUT,root()+"/"+id+"/clearances/adviser",decision(0,0,"cleared"),adviserToken);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS).getStatusCode(),b.get(15,TimeUnit.SECONDS).getStatusCode())).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
            assertThat(db.queryForObject("SELECT version FROM withdrawals WHERE id=?",Integer.class,id)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT version FROM withdrawal_clearances WHERE withdrawal_id=? AND office='adviser'",Integer.class,id)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    @Test void withdrawalCreationAndDocumentApprovalSerializeOnStudent()throws Exception{
        var pool=Executors.newSingleThreadExecutor();
        try{
            for(boolean withdrawalFirst:List.of(true,false)){
                long enrollment=enroll();long student=db.queryForObject("SELECT student_id FROM enrollments WHERE id=?",Long.class,enrollment);
                long document=db.queryForObject("INSERT INTO document_requests(student_id,document_type,status) VALUES (?,'TOR','requested') RETURNING id",Long.class,student);
                try(var first=Objects.requireNonNull(db.getDataSource()).getConnection()){
                    first.setAutoCommit(false);
                    String insert="INSERT INTO withdrawals(enrollment_id,reason) VALUES ("+enrollment+",'Concurrent test')";
                    String approve="UPDATE document_requests SET status='approved' WHERE id="+document;
                    first.createStatement().executeUpdate(withdrawalFirst?insert:approve);
                    var started=new CountDownLatch(1);
                    var second=pool.submit(()->{
                        try(var connection=db.getDataSource().getConnection()){
                            connection.setAutoCommit(false);started.countDown();
                            try{connection.createStatement().executeUpdate(withdrawalFirst?approve:insert);connection.commit();return "committed";}
                            catch(java.sql.SQLException error){connection.rollback();return error.getSQLState();}
                        }
                    });
                    assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    first.commit();
                    assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo(withdrawalFirst?"23514":"committed");
                    assertThat(db.queryForObject("SELECT status FROM document_requests WHERE id=?",String.class,document)).isEqualTo(withdrawalFirst?"requested":"approved");
                    assertThat(db.queryForObject("SELECT count(*) FROM withdrawals WHERE enrollment_id=?",Integer.class,enrollment)).isEqualTo(1);
                }
            }
        }finally{pool.shutdownNow();}
    }

    @Test void historyDirectoryAndDetailAreScopedAndHideConfidentialNotes(){
        long enrollment=enroll();long student=db.queryForObject("SELECT student_id FROM enrollments WHERE id=?",Long.class,enrollment);
        long offering=((Number)createOffering(teacher).get("classId")).longValue();
        // Creating the offering adds the section's active students to the class roster.
        assertThat(db.queryForObject("SELECT count(*) FROM class_memberships WHERE class_id=? AND enrollment_id=?",Integer.class,offering,enrollment)).isEqualTo(1);
        long period=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'Q1',1) RETURNING id",Long.class,term);
        db.update("INSERT INTO grades(class_id,enrollment_id,term_id,grading_period_id,value,state) VALUES (?,?,?,?,87,'submitted')",offering,enrollment,term,period);
        db.update("INSERT INTO student_notes(student_id,enrollment_id,category,body,author_id) VALUES (?,?,'guidance','Confidential counseling note',?)",student,enrollment,registrar);
        String principal=principalToken();
        var directory=call(HttpMethod.GET,"/api/academic/history/students?search=Withdrawal",null,principal);
        assertThat(directory.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat((List<Map>)directory.getBody().get("items")).extracting(row->((Number)row.get("id")).longValue()).contains(student);
        var history=call(HttpMethod.GET,"/api/academic/history/students/"+student,null,principal);
        assertThat(history.getStatusCode()).as(history.toString()).isEqualTo(HttpStatus.OK);
        assertThat((List)history.getBody().get("terms")).hasSize(1);
        assertThat((Map)history.getBody().get("credentials")).containsEntry("overallStatus","Incomplete");
        assertThat(history.getBody().toString()).doesNotContain("Confidential counseling note");
        String foreign=login(staff("ROLE_PRINCIPAL",otherCampus));
        assertThat(call(HttpMethod.GET,"/api/academic/history/students/"+student,null,foreign).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET,"/api/academic/history/students?search=Withdrawal",null,foreign).getBody().get("items")).asList().isEmpty();
        assertThat(call(HttpMethod.GET,"/api/academic/history/students/"+student,null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
