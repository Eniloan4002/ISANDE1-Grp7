package ph.edu.ici;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class WorkflowApiTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder passwords;
    long campus,program,term,period,subject,registrar,teacher,otherTeacher,adviser,principal,guidance,cashier;
    String registrarToken,teacherToken,otherTeacherToken,adviserToken,principalToken,guidanceToken,cashierToken;
    @BeforeEach void init(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,?,?) RETURNING id",Long.class,"T-"+UUID.randomUUID().toString().substring(0,12),LocalDate.now().minusMonths(1),LocalDate.now().plusMonths(9));
        term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,?,?,?,?) RETURNING id",Long.class,year,"T1","Test term",LocalDate.now().minusMonths(1),LocalDate.now().plusMonths(9));
        period=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence,starts_on,ends_on) VALUES (?,?,?,?,?) RETURNING id",Long.class,term,"P1",1,LocalDate.now().minusMonths(1),LocalDate.now().plusMonths(9));
        subject=db.queryForObject("INSERT INTO subjects(code,name,units) VALUES (?,?,1) RETURNING id",Long.class,"SUB-"+UUID.randomUUID().toString().substring(0,8),"Test subject");
        registrar=staff("ROLE_REGISTRAR");teacher=staff("ROLE_TEACHER");otherTeacher=staff("ROLE_TEACHER");adviser=staff("ROLE_ADVISER");principal=staff("ROLE_PRINCIPAL");guidance=staff("ROLE_GUIDANCE");cashier=staff("ROLE_CASHIER_REF");
        registrarToken=token(registrar);teacherToken=token(teacher);otherTeacherToken=token(otherTeacher);adviserToken=token(adviser);principalToken=token(principal);guidanceToken=token(guidance);cashierToken=token(cashier);
    }
    private long staff(String role){String name="staff-"+UUID.randomUUID();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid",role,passwords.encode("Test-password-123!"));db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,campus);return id;}
    private String token(long id){String name=db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id);return (String)rest.postForObject("/api/auth/login",Map.of("username",name,"password","Test-password-123!"),Map.class).get("accessToken");}
    private ResponseEntity<Map> call(HttpMethod method,String path,Object body,String bearer){HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)h.setBearerAuth(bearer);return rest.exchange(path,method,new HttpEntity<>(body,h),Map.class);}
    private ResponseEntity<List> callList(HttpMethod method,String path,Object body,String bearer){HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)h.setBearerAuth(bearer);return rest.exchange(path,method,new HttpEntity<>(body,h),List.class);}
    private long student(String suffix){return db.queryForObject("INSERT INTO students(student_number,campus_id,program_id,voucher_code,track,first_name,last_name,birth_date,address) VALUES (?,?,?,?,?,?,?,?, '{}'::jsonb) RETURNING id",Long.class,"STU-"+UUID.randomUUID(),campus,program,"QVR","ACADEMIC","Test",suffix,LocalDate.of(2009,1,1));}
    private long application(long student){
        var response=call(HttpMethod.POST,"/api/enrollment/applications",Map.of("studentId",student,"campusId",campus,"termId",term,"programId",program,"category","new"),registrarToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);return ((Number)response.getBody().get("id")).longValue();
    }
    private void prepareForConfirmation(long app){
        call(HttpMethod.PUT,"/api/enrollment/applications/"+app+"/status",Map.of("status","under_review"),registrarToken);
        call(HttpMethod.PUT,"/api/enrollment/applications/"+app+"/status",Map.of("status","pre_enlisted"),registrarToken);
        for(String code:db.queryForList("SELECT code FROM requirement_types WHERE mandatory",String.class))
            assertThat(call(HttpMethod.PUT,"/api/enrollment/applications/"+app+"/requirements/"+code,Map.of("status","submitted"),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        db.update("INSERT INTO orientation_attendance(application_id,attendance_date,status,recorded_by) VALUES (?,?, 'present',?)",app,LocalDate.now(),registrar);
    }
    private long confirmAndAssign(long app,long student){
        prepareForConfirmation(app);var confirmed=call(HttpMethod.POST,"/api/enrollment/applications/"+app+"/confirm",null,registrarToken);assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        long enrollment=((Number)confirmed.getBody().get("id")).longValue();
        var section=call(HttpMethod.POST,"/api/enrollment/sections",Map.of("campusId",campus,"termId",term,"programId",program,"name","Section-"+UUID.randomUUID(),"gradeLevel","11","capacity",20,"adviserId",adviser),registrarToken);
        assertThat(section.getStatusCode()).isEqualTo(HttpStatus.CREATED);long sectionId=((Number)section.getBody().get("id")).longValue();
        assertThat(call(HttpMethod.PUT,"/api/enrollment/enrollments/"+enrollment+"/section",Map.of("sectionId",sectionId),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        return enrollment;
    }
    @Test void unauthenticatedAndUnassignedCampusRequestsAreDeniedWithoutWrites(){
        assertThat(call(HttpMethod.GET,"/api/enrollment/applications",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST,"/api/academics/classes",Map.of(),null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        long student=student("Campus scope");long otherCampus=db.queryForObject("SELECT id FROM campuses WHERE code='KAPATAGAN'",Long.class);
        int before=db.queryForObject("SELECT count(*) FROM applications",Integer.class);
        var denied=call(HttpMethod.POST,"/api/enrollment/applications",Map.of("studentId",student,"campusId",otherCampus,"termId",term,"programId",program,"category","new"),registrarToken);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(db.queryForObject("SELECT count(*) FROM applications",Integer.class)).isEqualTo(before);
    }
    @Test void missingRequirementReportIncludesPreEnrollmentApplicationsAndLogsActor(){
        long student=student("Requirements");long app=application(student);
        var report=callList(HttpMethod.GET,"/api/reports/missing-requirements?campusId="+campus+"&termId="+term,null,registrarToken);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(report.getBody()).hasSize(4);
        assertThat(report.getBody()).allSatisfy(item->{assertThat(((Number)((Map)item).get("application_id")).longValue()).isEqualTo(app);assertThat(((Map)item).get("status")).isEqualTo("missing");});
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='reports' AND record_id='missing-requirements' AND actor_id=?",Integer.class,registrar)).isEqualTo(1);
    }
    @Test void registrarIntakeAndTeacherGradeEntryAreScopedToAssignedClassAndOpenWindow(){
        long student=student("One"),app=application(student);long enrollment=confirmAndAssign(app,student);
        var made=call(HttpMethod.POST,"/api/academics/classes",Map.of("sectionId",db.queryForObject("SELECT section_id FROM enrollments WHERE id=?",Long.class,enrollment),"subjectId",subject,"teacherId",teacher),registrarToken);
        assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);long classId=((Number)made.getBody().get("id")).longValue();
        db.update("INSERT INTO grading_windows(class_id,grading_period_id,deadline) VALUES (?,?,?)",classId,period,OffsetDateTime.now().plusDays(1));
        Map<String,Object> grade=new HashMap<>();grade.put("enrollmentId",enrollment);grade.put("gradingPeriodId",period);grade.put("value",new BigDecimal("87.50"));grade.put("state","submitted");
        var submitted=call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grades",grade,teacherToken);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(submitted.getBody()).containsEntry("state","submitted");
        var edit=call(HttpMethod.PUT,"/api/academics/grades/"+submitted.getBody().get("id"),Map.of("version",submitted.getBody().get("version"),"value",new BigDecimal("88.00"),"state","submitted"),teacherToken);
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(new BigDecimal(edit.getBody().get("value").toString())).isEqualByComparingTo("88.00");
        assertThat(callList(HttpMethod.GET,"/api/academics/classes/"+classId+"/roster",null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,"/api/academics/classes/"+classId+"/roster",null,otherTeacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grades",grade,otherTeacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=? AND enrollment_id=?",Integer.class,classId,enrollment)).isEqualTo(1);
        assertThat(call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grading-periods/"+period+"/finalize",null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grades",grade,teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var lockedEdit=call(HttpMethod.PUT,"/api/academics/grades/"+submitted.getBody().get("id"),Map.of("version",edit.getBody().get("version"),"value",new BigDecimal("90.00"),"state","submitted"),teacherToken);
        assertThat(lockedEdit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT state FROM grades WHERE class_id=? AND enrollment_id=?",String.class,classId,enrollment)).isEqualTo("finalized");
        assertThat(db.queryForObject("SELECT value FROM grades WHERE class_id=? AND enrollment_id=?",BigDecimal.class,classId,enrollment)).isEqualByComparingTo("88.00");
    }
    @Test void noGradingWindowAndExpiredWindowRejectTeacherWrites(){
        long student=student("Two"),app=application(student),enrollment=confirmAndAssign(app,student);long section=db.queryForObject("SELECT section_id FROM enrollments WHERE id=?",Long.class,enrollment);
        long classId=((Number)call(HttpMethod.POST,"/api/academics/classes",Map.of("sectionId",section,"subjectId",subject,"teacherId",teacher),registrarToken).getBody().get("id")).longValue();
        Map<String,Object> grade=new HashMap<>();grade.put("enrollmentId",enrollment);grade.put("gradingPeriodId",period);grade.put("value",80);grade.put("state","submitted");
        assertThat(call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grades",grade,teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("INSERT INTO grading_windows(class_id,grading_period_id,deadline) VALUES (?,?,NULL)",classId,period);
        assertThat(call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grades",grade,teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE grading_windows SET deadline=? WHERE class_id=? AND grading_period_id=?",OffsetDateTime.now().minusDays(1),classId,period);
        assertThat(call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grades",grade,teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE grading_windows SET deadline=?,locked_at=current_timestamp WHERE class_id=? AND grading_period_id=?",OffsetDateTime.now().plusDays(1),classId,period);
        assertThat(call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grades",grade,teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=? AND enrollment_id=?",Integer.class,classId,enrollment)).isZero();
    }
    @Test void adviserAndCashierAreReadOnlyAndGuidanceReportUsesOnlyExistingRecords(){
        long student=student("Three"),app=application(student),enrollment=confirmAndAssign(app,student);
        var sectionId=db.queryForObject("SELECT section_id FROM enrollments WHERE id=?",Long.class,enrollment);
        var classResponse=call(HttpMethod.POST,"/api/academics/classes",Map.of("sectionId",sectionId,"subjectId",subject,"teacherId",teacher),registrarToken);long classId=((Number)classResponse.getBody().get("id")).longValue();
        db.update("INSERT INTO grading_windows(class_id,grading_period_id,deadline) VALUES (?,?,?)",classId,period,OffsetDateTime.now().plusDays(1));
        Map<String,Object> grade=new HashMap<>();grade.put("enrollmentId",enrollment);grade.put("gradingPeriodId",period);grade.put("mark","INC");grade.put("state","submitted");
        assertThat(call(HttpMethod.POST,"/api/academics/classes/"+classId+"/grades",grade,teacherToken).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        db.update("UPDATE orientation_attendance SET status='absent',attendance_date=? WHERE application_id=?",LocalDate.now(),app);
        var advisory=callList(HttpMethod.GET,"/api/academics/advisory?termId="+term,null,adviserToken);assertThat(advisory.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(advisory.getBody()).hasSize(1);
        assertThat(((Number)((Map)advisory.getBody().getFirst()).get("section_id")).longValue()).isEqualTo(sectionId);
        assertThat(call(HttpMethod.PUT,"/api/withdrawals/999/clearances/adviser",Map.of("status","cleared"),adviserToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(callList(HttpMethod.GET,"/api/cashiering/roster?termId="+term,null,cashierToken).getBody()).hasSize(1);
        var risk=callList(HttpMethod.GET,"/api/reports/at-risk?termId="+term,null,guidanceToken);assertThat(risk.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(risk.getBody()).hasSize(1);
        assertThat((Map)((List)risk.getBody()).getFirst()).containsEntry("academic_risk",true).containsEntry("orientation_absent",true).doesNotContainKey("absence_count");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='reports' AND record_id='at-risk' AND actor_id=?",Integer.class,guidance)).isEqualTo(1);
    }
    @Test void principalApprovesWithdrawalsAndRegistrarRecordsExternalClearances(){
        long student=student("Four"),app=application(student),enrollment=confirmAndAssign(app,student);
        long withdrawal=((Number)call(HttpMethod.POST,"/api/withdrawals",Map.of("enrollmentId",enrollment,"reason","Test request"),registrarToken).getBody().get("id")).longValue();
        assertThat(call(HttpMethod.PUT,"/api/withdrawals/"+withdrawal+"/clearances/cashier",Map.of("status","cleared"),cashierToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT,"/api/withdrawals/"+withdrawal+"/clearances/principal",Map.of("status","cleared"),principalToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,"/api/withdrawals/"+withdrawal+"/clearances/adviser",Map.of("status","cleared"),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        var completed=call(HttpMethod.PUT,"/api/withdrawals/"+withdrawal+"/clearances/cashier",Map.of("status","cleared"),registrarToken);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(completed.getBody()).containsEntry("status","completed");
        assertThat(db.queryForObject("SELECT status FROM enrollments WHERE id=?",String.class,enrollment)).isEqualTo("withdrawn");
    }
}
