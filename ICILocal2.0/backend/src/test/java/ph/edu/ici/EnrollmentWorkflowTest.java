package ph.edu.ici;

import java.time.LocalDate;
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
class EnrollmentWorkflowTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    long campus,other,term,program,actor,app;String token;
    @BeforeEach void fixture(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);
        other=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        long year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-06-01','2027-04-30') RETURNING id",Long.class,UUID.randomUUID().toString().substring(0,20));
        term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'S1','First semester','2026-06-01','2026-10-31') RETURNING id",Long.class,year);
        String username="flow-"+UUID.randomUUID();actor=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,username,username+"@example.invalid","Workflow Registrar",encoder.encode("Test-password-123!"));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_REGISTRAR',?)",actor,campus);
        token=(String)rest.postForObject("/api/auth/login",Map.of("username",username,"password","Test-password-123!"),Map.class).get("accessToken");
        app=application(campus);
    }
    long application(long campus){
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,program_id) VALUES (?,'Workflow','Applicant','2009-01-01',?,?) RETURNING id",Long.class,"ST-"+UUID.randomUUID(),campus,program);
        return db.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date,status,profile) VALUES (?,?,?,?,?,'new','Workflow','Applicant','2009-01-01','pre_enlisted','{\"gradeLevel\":\"Grade 11\",\"district\":\"Tibanga\",\"transportRoute\":\"North\"}') RETURNING id",Long.class,"APP-"+UUID.randomUUID(),campus,term,program,student);
    }
    ResponseEntity<Map> call(HttpMethod method,String path,Object body){return call(method,path,body,token);}
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String bearer){HttpHeaders h=new HttpHeaders();if(bearer!=null)h.setBearerAuth(bearer);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(path,method,new HttpEntity<>(body,h),Map.class);}
    String path(long id){return "/api/applications/"+id;}
    Map detail(long id){var r=call(HttpMethod.GET,path(id)+"/enrollment",null);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);return r.getBody();}
    ResponseEntity<Map> confirm(long id,int version){return call(HttpMethod.POST,path(id)+"/confirm-enrollment",Map.of("version",version));}
    void ready(long id){
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status,verified_by) SELECT ?,code,'submitted',? FROM requirement_types WHERE mandatory",id,actor);
        assertThat(call(HttpMethod.PUT,path(id)+"/attendance/2026-06-01",Map.of("status","present","version",0)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    long enrolled(long id){ready(id);var r=confirm(id,0);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);return ((Number)((Map)r.getBody().get("enrollment")).get("id")).longValue();}
    Map<String,Object> sectionInput(String name,int capacity,int version){var m=new HashMap<String,Object>();m.put("name",name);m.put("capacity",capacity);m.put("version",version);m.put("campusId",campus);m.put("termId",term);m.put("programId",program);m.put("gradeLevel","11");m.put("room","Room 204");return m;}
    long section(String name,int capacity){var r=call(HttpMethod.POST,"/api/sections",sectionInput(name,capacity,0));assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);return ((Number)r.getBody().get("id")).longValue();}
    ResponseEntity<Map> assign(long id,Long section,int version){var m=new HashMap<String,Object>();m.put("sectionId",section);m.put("version",version);return call(HttpMethod.PUT,"/api/enrollments/"+id+"/section",m);}

    @Test void directoryFiltersCompletenessCampusDistrictRouteAndPagination(){
        application(other);ready(app);
        String base="/api/enrollment/applications?termId="+term;
        Map page=call(HttpMethod.GET,base+"&requirementsStatus=Complete&district=Tibanga&transportRoute=North&size=1",null).getBody();
        assertThat(page.get("total")).isEqualTo(1);assertThat((List)page.get("items")).hasSize(1);
        Map row=(Map)((List)page.get("items")).getFirst();assertThat(((Number)row.get("id")).longValue()).isEqualTo(app);assertThat(row.get("requirementsStatus")).isEqualTo("Complete");
        assertThat(call(HttpMethod.GET,base+"&campusId="+other,null).getBody().get("total")).isEqualTo(0);
        assertThat((List)call(HttpMethod.GET,base+"&size=1&page=1",null).getBody().get("items")).isEmpty();
        for(String suffix:List.of("&size=101","&page=-1","&requirementsStatus=unknown","&status=unknown"))assertThat(call(HttpMethod.GET,base+suffix,null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void confirmationChecksEveryGateAndDoesNotWriteOnFailure(){
        assertThat(detail(app).get("canConfirm")).isEqualTo(false);
        assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        call(HttpMethod.PUT,path(app)+"/attendance/2026-06-01",Map.of("status","excused","version",0));
        assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status) SELECT ?,code,'submitted' FROM requirement_types WHERE mandatory",app);
        assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        call(HttpMethod.PUT,path(app)+"/attendance/2026-06-01",Map.of("status","present","version",1));
        db.update("UPDATE applications SET status='under_review' WHERE id=?",app);assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE applications SET status='pre_enlisted' WHERE id=?",app);
        db.update("UPDATE students SET active=false WHERE id=(SELECT student_id FROM applications WHERE id=?)",app);assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE students SET active=true WHERE id=(SELECT student_id FROM applications WHERE id=?)",app);
        long matched=application(campus);db.update("INSERT INTO duplicate_cases(application_id,matched_application_id) VALUES (?,?)",matched,app);
        assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("DELETE FROM duplicate_cases WHERE matched_application_id=?",app);
        db.update("UPDATE applications SET student_id=null WHERE id=?",app);assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT count(*) FROM enrollments WHERE application_id=?",Integer.class,app)).isZero();
    }
    @Test void confirmationIsAttributedPersistedAndSafeToRetry(){
        ready(app);Map first=confirm(app,0).getBody();Map enrollment=(Map)first.get("enrollment");
        assertThat(enrollment).containsEntry("status","enrolled");assertThat(((Number)enrollment.get("confirmedBy")).longValue()).isEqualTo(actor);
        assertThat(((Map)first.get("application")).get("version")).isEqualTo(1);
        assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirm(app,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT count(*) FROM enrollments WHERE application_id=?",Integer.class,app)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT created_by FROM enrollments WHERE application_id=?",Long.class,app)).isEqualTo(actor);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='enrollments' AND new_data->>'application_id'=? AND actor_id=?",Integer.class,Long.toString(app),actor)).isEqualTo(1);
    }
    @Test void attendanceValidatesDatesVersionsAndProtectsConfirmedEvidence(){
        assertThat(call(HttpMethod.PUT,path(app)+"/attendance/"+LocalDate.now().plusDays(1),Map.of("status","present","version",0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT,path(app)+"/attendance/2026-06-01",Map.of("status","unknown","version",0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ready(app);assertThat(call(HttpMethod.PUT,path(app)+"/attendance/2026-06-01",Map.of("status","absent","version",0)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,path(app)+"/attendance/2026-06-01",Map.of("status","absent","version",1)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT recorded_by FROM orientation_attendance WHERE application_id=?",Long.class,app)).isEqualTo(actor);
    }
    @Test void campusAndRoleScopeProtectEveryWorkflow(){
        long cross=application(other);
        assertThat(call(HttpMethod.GET,path(cross)+"/enrollment",null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(confirm(cross,0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,path(cross)+"/attendance/2026-06-01",Map.of("status","present","version",0)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var crossSection=sectionInput("Other",10,0);crossSection.put("campusId",other);assertThat(call(HttpMethod.POST,"/api/sections",crossSection).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET,"/api/sections",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        db.update("UPDATE user_role_grants SET role_code='ROLE_TEACHER' WHERE user_id=?",actor);
        for(String url:List.of("/api/enrollment/reference-data","/api/enrollment/applications","/api/sections",path(app)+"/enrollment"))assertThat(call(HttpMethod.GET,url,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/api/sections",sectionInput("Blocked",10,0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void sectionCrudPreservesParentsVersionsAndOccupiedCapacity(){
        long s=section("STEM A",2);var input=sectionInput("STEM A renamed",3,0);
        assertThat(call(HttpMethod.PUT,"/api/sections/"+s,input).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,"/api/sections/"+s,input).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        input.put("version",1);input.put("termId",Long.MAX_VALUE);assertThat(call(HttpMethod.PUT,"/api/sections/"+s,input).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long e=enrolled(app);assertThat(assign(e,s,0).getStatusCode()).isEqualTo(HttpStatus.OK);
        long second=application(campus),e2=enrolled(second);assertThat(assign(e2,s,0).getStatusCode()).isEqualTo(HttpStatus.OK);
        input.put("termId",term);input.put("capacity",1);assertThat(call(HttpMethod.PUT,"/api/sections/"+s,input).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.DELETE,"/api/sections/"+s+"?version=1",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map capacity=call(HttpMethod.GET,"/api/sections/"+s,null).getBody();assertThat(capacity).containsEntry("occupied",2).containsEntry("available",1);
        long unused=section("Unused",1);assertThat(call(HttpMethod.DELETE,"/api/sections/"+unused+"?version=0",null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
    @Test void sectionInputValidatesRequiredFieldsAndAdviserCampus(){
        var input=sectionInput("Invalid",0,0);assertThat(call(HttpMethod.POST,"/api/sections",input).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        input.put("capacity",5);input.put("adviserId",actor);assertThat(call(HttpMethod.POST,"/api/sections",input).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        input.remove("adviserId");input.put("programId",Long.MAX_VALUE);assertThat(call(HttpMethod.POST,"/api/sections",input).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void assignmentChecksCompatibilityGradeVersionsAndSupportsRemoval(){
        long e=enrolled(app),s=section("STEM A",1);
        var wrong=sectionInput("Grade 12",1,0);wrong.put("gradeLevel","12");long wrongId=((Number)call(HttpMethod.POST,"/api/sections",wrong).getBody().get("id")).longValue();
        assertThat(assign(e,wrongId,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE applications SET profile='{}' WHERE id=?",app);assertThat(assign(e,s,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE applications SET profile='{\"gradeLevel\":\"Grade 11\"}' WHERE id=?",app);
        assertThat(assign(e,s,0).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(assign(e,s,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(assign(e,null,1).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(db.queryForObject("SELECT section_id FROM enrollments WHERE id=?",Long.class,e)).isNull();
        long otherProgram=db.queryForObject("SELECT id FROM programs WHERE code='ABM'",Long.class);db.update("UPDATE sections SET program_id=? WHERE id=?",otherProgram,s);
        assertThat(assign(e,s,2).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void assignedAcademicMembershipsBlockTransfersAndPreserveGrades(){
        long e=enrolled(app),s=section("STEM A",2);assign(e,s,0);
        long subject=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Mathematics') RETURNING id",Long.class,"S-"+UUID.randomUUID());
        long offering=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,s,term,subject,actor);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",offering,e,s,term);
        assertThat(assign(e,null,1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT section_id FROM enrollments WHERE id=?",Long.class,e)).isEqualTo(s);
    }
    @Test void simultaneousReservationsCannotOverbookLastSeat() throws Exception{
        long e1=enrolled(app),second=application(campus),e2=enrolled(second),s=section("Last seat",1);
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->{start.await();return assign(e1,s,0).getStatusCode().value();});
            var next=executor.submit(()->{start.await();return assign(e2,s,0).getStatusCode().value();});start.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),next.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM enrollments WHERE section_id=?",Integer.class,s)).isEqualTo(1);
    }
    @Test void simultaneousConfirmationCreatesOneEnrollment() throws Exception{
        ready(app);CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var one=executor.submit(()->{start.await();return confirm(app,0).getStatusCode().value();});
            var two=executor.submit(()->{start.await();return confirm(app,0).getStatusCode().value();});start.countDown();
            assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void provisionalEnrollmentIsPromotedAndSameTermCannotBeEnrolledTwice(){
        long student=db.queryForObject("SELECT student_id FROM applications WHERE id=?",Long.class,app);
        long provisional=db.queryForObject("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id) VALUES (?,?,?,?,?) RETURNING id",Long.class,student,app,campus,term,program);
        ready(app);Map result=confirm(app,0).getBody();Map enrollment=(Map)result.get("enrollment");
        assertThat(((Number)enrollment.get("id")).longValue()).isEqualTo(provisional);assertThat(enrollment.get("version")).isEqualTo(1);
        long second=application(campus);db.update("UPDATE applications SET student_id=? WHERE id=?",student,second);ready(second);
        assertThat(confirm(second,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT status FROM applications WHERE id=?",String.class,second)).isEqualTo("pre_enlisted");
    }
    @Test void inaccessibleSectionsAndEnrollmentIdsCannotBeReadOrMutated(){
        long foreignSection=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,'Other campus','11',2) RETURNING id",Long.class,other,term,program);
        assertThat(call(HttpMethod.GET,"/api/sections/"+foreignSection,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,"/api/sections/"+foreignSection,sectionInput("Hidden",2,0)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE,"/api/sections/"+foreignSection+"?version=0",null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        long e=enrolled(app);assertThat(assign(e,foreignSection,0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(assign(Long.MAX_VALUE,null,0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        db.update("UPDATE user_role_grants SET role_code='ROLE_PRINCIPAL' WHERE user_id=?",actor);
        assertThat(assign(e,null,0).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void capacityEditsSerializeWithReservations() throws Exception {
        long e1=enrolled(app),second=application(campus),e2=enrolled(second),section=section("Concurrent capacity",2);assign(e1,section,0);
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var allocation=executor.submit(()->{start.await();return assign(e2,section,0).getStatusCode().value();});
            var reduce=executor.submit(()->{start.await();return call(HttpMethod.PUT,"/api/sections/"+section,sectionInput("Concurrent capacity",1,0)).getStatusCode().value();});
            start.countDown();assertThat(List.of(allocation.get(15,TimeUnit.SECONDS),reduce.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        long capacity=db.queryForObject("SELECT capacity FROM sections WHERE id=?",Long.class,section);
        long occupied=db.queryForObject("SELECT count(*) FROM enrollments WHERE section_id=?",Long.class,section);
        assertThat(occupied).isLessThanOrEqualTo(capacity);
    }


    private static final java.util.concurrent.atomic.AtomicInteger intakeIdentitySequence=new java.util.concurrent.atomic.AtomicInteger();
    Map<String,Object> intakeInput(){
        Map<String,Object> input=new HashMap<>();input.put("campusId",campus);input.put("termId",term);input.put("programId",program);
        input.put("category","new");input.put("firstName","Intake"+UUID.randomUUID().toString().replace("-",""));input.put("lastName","Integrated");input.put("birthDate",LocalDate.of(2009,1,1).plusDays(intakeIdentitySequence.incrementAndGet()).toString());
        input.put("voucher","QVR");input.put("track","ACADEMIC");input.put("gradeLevel","11");input.put("district","Tibanga");input.put("transportRoute","North");
        input.put("guardians",List.of(Map.of("fullName","Integration Guardian","relationship","mother","phone","09123456789","primaryContact",true),Map.of("fullName","Second Guardian","relationship","father","primaryContact",false)));
        return input;
    }
    long intake(Map<String,Object> input){var r=call(HttpMethod.POST,"/api/intake/applications",input);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);return ((Number)r.getBody().get("id")).longValue();}
    @Test void intakePersistsGuardiansAndExplicitReviewFeedsEnrollmentGates(){
        var input=intakeInput();long id=intake(input);
        assertThat(db.queryForObject("SELECT student_id FROM applications WHERE id=?",Long.class,id)).isNull();
        var r=call(HttpMethod.GET,"/api/intake/applications/"+id,null);assertThat(r.getBody()).containsEntry("status","submitted");
        assertThat((List)((Map)r.getBody().get("profile")).get("guardians")).hasSize(2);
        assertThat(confirm(id,0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var reviewed=call(HttpMethod.POST,"/api/intake/applications/"+id+"/review",Map.of("version",0));assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewed.getBody()).containsEntry("status","pre_enlisted");long student=((Number)reviewed.getBody().get("studentId")).longValue();
        assertThat(db.queryForObject("SELECT count(*) FROM guardians WHERE student_id=?",Integer.class,student)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT created_by FROM students WHERE id=?",Long.class,student)).isEqualTo(actor);
        assertThat(call(HttpMethod.POST,"/api/intake/applications/"+id+"/review",Map.of("version",0)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        for(String code:List.of("PSA","FORM137","COMPLETION","GOOD_MORAL"))assertThat(call(HttpMethod.PUT,path(id)+"/requirements/"+code,Map.of("status","submitted","version",0,"notes","Original inspected")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,path(id)+"/attendance/2026-06-01",Map.of("status","present","version",0)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(id,1).getStatusCode()).isEqualTo(HttpStatus.OK);
        long enrollment=((Number)((Map)detail(id).get("enrollment")).get("id")).longValue();assertThat(assign(enrollment,section("Integrated-"+id,40),0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT created_by FROM applications WHERE id=?",Long.class,id)).isEqualTo(actor);
    }
    @Test void intakeValidationAndCampusScopeRejectWithoutPartialWrites(){
        var input=intakeInput();long before=db.queryForObject("SELECT count(*) FROM applications",Long.class);
        input.put("campusId",other);assertThat(call(HttpMethod.POST,"/api/intake/applications",input).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        input.put("campusId",campus);input.put("track","TVL");assertThat(call(HttpMethod.POST,"/api/intake/applications",input).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        input.put("track","ACADEMIC");input.put("guardians",List.of(Map.of("fullName","One","relationship","mother","primaryContact",true),Map.of("fullName","Two","relationship","father","primaryContact",true)));
        assertThat(call(HttpMethod.POST,"/api/intake/applications",input).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        input.put("guardians",List.of());input.put("lrn","");assertThat(call(HttpMethod.POST,"/api/intake/applications",input).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT count(*) FROM applications",Long.class)).isEqualTo(before);
        long cross=application(other);assertThat(call(HttpMethod.GET,"/api/intake/applications/"+cross,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST,"/api/intake/applications/"+cross+"/review",Map.of("version",0)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void intakeDuplicateRulesFlagCrossCampusLrnAndFuzzyNameDobWithoutNewIdentity(){
        String lrn=String.format("%012d",System.nanoTime()%1000000000000L);
        long existing=application(other);db.update("UPDATE applications SET lrn=? WHERE id=?",lrn,existing);
        var input=intakeInput();input.put("lrn",lrn);long id=intake(input);
        assertThat(db.queryForObject("SELECT status FROM applications WHERE id=?",String.class,id)).isEqualTo("duplicate_flag");
        assertThat(call(HttpMethod.POST,"/api/intake/applications/"+id+"/review",Map.of("version",1)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT student_id FROM applications WHERE id=?",Long.class,id)).isNull();
        var fuzzy=intakeInput();long first=intake(fuzzy);fuzzy.put("firstName",fuzzy.get("firstName")+"a");long second=intake(fuzzy);
        assertThat(db.queryForObject("SELECT status FROM applications WHERE id=?",String.class,second)).isEqualTo("duplicate_flag");
        // A match introduced after submission is rechecked at review time.
        assertThat(call(HttpMethod.POST,"/api/intake/applications/"+first+"/review",Map.of("version",0)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void intakeRequiresAuthenticatedRegistrarAndStatisticsStayScoped(){
        var input=intakeInput();assertThat(call(HttpMethod.POST,"/api/intake/applications",input,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        db.update("UPDATE user_role_grants SET role_code='ROLE_TEACHER' WHERE user_id=?",actor);
        assertThat(call(HttpMethod.POST,"/api/intake/applications",input).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        db.update("UPDATE user_role_grants SET role_code='ROLE_REGISTRAR' WHERE user_id=?",actor);
        long before=((Number)call(HttpMethod.GET,"/api/intake/statistics",null).getBody().get("total")).longValue();
        application(other);assertThat(((Number)call(HttpMethod.GET,"/api/intake/statistics",null).getBody().get("total")).longValue()).isEqualTo(before);
    }

    @Test void concurrentIntakeReviewCreatesOnlyOneStudentIdentity() throws Exception {
        long id=intake(intakeInput());var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->call(HttpMethod.POST,"/api/intake/applications/"+id+"/review",Map.of("version",0)).getStatusCode());
            var second=pool.submit(()->call(HttpMethod.POST,"/api/intake/applications/"+id+"/review",Map.of("version",0)).getStatusCode());
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
            long student=db.queryForObject("SELECT student_id FROM applications WHERE id=?",Long.class,id);
            assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='students' AND new_data->>'id'=?",Integer.class,Long.toString(student))).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    Map intakeDetail(long id){return call(HttpMethod.GET,"/api/intake/applications/"+id,null).getBody();}
    long firstCase(long id){Map list=call(HttpMethod.GET,"/api/duplicate-cases?applicationId="+id,null).getBody();return ((Number)((Map)((List)list.get("items")).getFirst()).get("id")).longValue();}
    Map<String,Object> resolveInput(long id,long caseId,String action){Map application=intakeDetail(id),c=call(HttpMethod.GET,"/api/duplicate-cases/"+caseId,null).getBody();var result=new HashMap<String,Object>();result.put("action",action);result.put("version",c.get("version"));result.put("applicationVersion",application.get("version"));result.put("studentVersion",c.get("studentVersion"));result.put("reason","Verified original identity evidence");return result;}
    long master(Map<String,Object> input){var payload=new HashMap<>(input);payload.remove("termId");payload.put("voucherCode",payload.remove("voucher"));payload.put("address",Map.of());payload.put("active",true);var r=call(HttpMethod.POST,"/api/students",payload);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);return ((Number)r.getBody().get("id")).longValue();}
    @Test void continuingApplicantLinksExistingIdentityResolvesCaseAndReviewsWithoutDuplicateStudent(){
        var input=intakeInput();long student=master(input);input.put("category","continuing");long id=intake(input),caseId=firstCase(id);long count=db.queryForObject("SELECT count(*) FROM students",Long.class);
        Map c=call(HttpMethod.GET,"/api/duplicate-cases/"+caseId,null).getBody();assertThat(c).containsEntry("status","open");
        var resolved=call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",resolveInput(id,caseId,"same_person"));assertThat(resolved.getStatusCode()).describedAs(resolved.toString()).isEqualTo(HttpStatus.OK);assertThat(resolved.getBody()).containsEntry("status","merged");
        Map linked=intakeDetail(id);assertThat(((Number)linked.get("studentId")).longValue()).isEqualTo(student);assertThat(linked).containsEntry("status","submitted");
        assertThat(call(HttpMethod.POST,"/api/intake/applications/"+id+"/review",Map.of("version",linked.get("version"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT count(*) FROM students",Long.class)).isEqualTo(count);
        assertThat(db.queryForObject("SELECT resolved_by FROM duplicate_cases WHERE id=?",Long.class,caseId)).isEqualTo(actor);
        assertThat((List)resolved.getBody().get("history")).isNotEmpty();
    }
    @Test void distinctDecisionIsReciprocalAndDoesNotReflagAfterCanonicalStudentCreation(){
        var input=intakeInput();long first=intake(input);input.put("firstName",input.get("firstName")+"a");long second=intake(input),caseId=firstCase(second);
        var r=call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",resolveInput(second,caseId,"distinct"));assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        for(long id:List.of(first,second)){Map current=intakeDetail(id);assertThat(current).containsEntry("status","submitted");var reviewed=call(HttpMethod.POST,"/api/intake/applications/"+id+"/review",Map.of("version",current.get("version")));assertThat(reviewed.getStatusCode()).describedAs(reviewed.toString()).isEqualTo(HttpStatus.OK);}
        assertThat(db.queryForObject("SELECT count(DISTINCT student_id) FROM applications WHERE id IN (?,?)",Integer.class,first,second)).isEqualTo(2);
        assertThat(call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",resolveInput(second,caseId,"distinct")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void exactLrnCannotBeDeclaredDistinctAndRejectRetainsCanonicalAndAudit(){
        var input=intakeInput();input.put("lrn",String.format("%012d",System.nanoTime()%1000000000000L));long canonical=intake(input),duplicate=intake(input),caseId=firstCase(duplicate);
        assertThat(call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",resolveInput(duplicate,caseId,"distinct")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var decision=resolveInput(duplicate,caseId,"reject");decision.put("reason","");assertThat(call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",decision).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        decision=resolveInput(duplicate,caseId,"reject");assertThat(call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",decision).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(intakeDetail(duplicate)).containsEntry("status","rejected");Map keep=intakeDetail(canonical);assertThat(call(HttpMethod.POST,"/api/intake/applications/"+canonical+"/review",Map.of("version",keep.get("version"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT count(*) FROM applications WHERE id IN (?,?)",Integer.class,canonical,duplicate)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='duplicate_cases' AND new_data->>'id'=?",Integer.class,Long.toString(caseId))).isGreaterThanOrEqualTo(2);
    }
    @Test void duplicateCasesHideOutOfScopeIdentityAndRejectStaleDecision(){
        long cross=application(other);String lrn=String.format("%012d",System.nanoTime()%1000000000000L);db.update("UPDATE applications SET lrn=?,student_id=null,status='submitted' WHERE id=?",lrn,cross);
        var input=intakeInput();input.put("lrn",lrn);long id=intake(input);Map list=call(HttpMethod.GET,"/api/duplicate-cases?applicationId="+id,null).getBody();Map row=(Map)((List)list.get("items")).getFirst();long caseId=((Number)row.get("id")).longValue();assertThat(row.get("canReview")).isEqualTo(false);
        assertThat(call(HttpMethod.GET,"/api/duplicate-cases/"+caseId,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_REGISTRAR',?)",actor,other);
        var decision=resolveInput(id,caseId,"reject");decision.put("version",99);assertThat(call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",decision).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE user_role_grants SET role_code='ROLE_TEACHER' WHERE user_id=?",actor);assertThat(call(HttpMethod.GET,"/api/duplicate-cases",null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void manualStudentLinkRejectsWrongIdentityAndArchivesAndPreservesOriginalMaster(){
        var input=intakeInput();long student=master(input);input.put("category","returnee");long id=intake(input);Map current=intakeDetail(id);var payload=new HashMap<String,Object>(Map.of("studentId",student,"studentVersion",0,"version",current.get("version"),"reason","Checked permanent record"));
        db.update("UPDATE students SET active=false WHERE id=?",student);assertThat(call(HttpMethod.POST,"/api/intake/applications/"+id+"/link-student",payload).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE students SET active=true,birth_date='2000-01-01' WHERE id=?",student);assertThat(call(HttpMethod.POST,"/api/intake/applications/"+id+"/link-student",payload).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("UPDATE students SET birth_date=?::date WHERE id=?",input.get("birthDate"),student);
        assertThat(call(HttpMethod.POST,"/api/intake/applications/"+id+"/link-student",payload).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number)intakeDetail(id).get("studentId")).longValue()).isEqualTo(student);assertThat(db.queryForObject("SELECT first_name FROM students WHERE id=?",String.class,student)).isEqualTo(input.get("firstName"));
        assertThat(call(HttpMethod.POST,"/api/intake/applications/"+id+"/link-student",payload).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void approvedAttendanceDatesEnforceRangeVersionsScopeAndConfirmedHistory(){
        String url="/api/enrollment/attendance-window?campusId="+campus+"&termId="+term;
        var dates=Map.of("startsOn","2026-06-01","endsOn","2026-06-07","approvalNote","Test institution-approved orientation dates","version",0);
        assertThat(call(HttpMethod.PUT,url,dates).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(call(HttpMethod.PUT,url,dates).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,path(app)+"/attendance/2026-06-08",Map.of("status","present","version",0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ready(app);assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,url,Map.of("startsOn","2026-07-01","endsOn","2026-07-07","approvalNote","Invalid historical change","version",1)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,"/api/enrollment/attendance-window?campusId="+other+"&termId="+term,dates).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void concurrentDuplicateDecisionHasOneWinnerAndKeepsTheDecisionImmutable() throws Exception {
        var input=intakeInput();input.put("lrn",String.format("%012d",System.nanoTime()%1000000000000L));intake(input);long duplicate=intake(input),caseId=firstCase(duplicate);var decision=resolveInput(duplicate,caseId,"reject");
        try(var pool=Executors.newFixedThreadPool(2)){
            CountDownLatch start=new CountDownLatch(1);var one=pool.submit(()->{start.await();return call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",decision).getStatusCode();});var two=pool.submit(()->{start.await();return call(HttpMethod.POST,"/api/duplicate-cases/"+caseId+"/resolve",decision).getStatusCode();});start.countDown();assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
        }
        assertThat(db.queryForObject("SELECT version FROM duplicate_cases WHERE id=?",Integer.class,caseId)).isEqualTo(1);
    }

    @Test void separateOrientationAndFirstWeekWindowsDoNotAcceptTheGapBetweenEvents(){
        String url="/api/enrollment/attendance-window?campusId="+campus+"&termId="+term;
        var dates=new HashMap<String,Object>(Map.of("startsOn","2026-08-03","endsOn","2026-08-07","orientationStartsOn","2026-07-20","orientationEndsOn","2026-07-20","approvalNote","Fictional campus schedule supplied for QA","version",0));
        assertThat(call(HttpMethod.PUT,url,dates).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,path(app)+"/attendance/2026-07-25",Map.of("status","present","version",0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT,path(app)+"/attendance/2026-07-20",Map.of("status","present","version",0)).getStatusCode()).isEqualTo(HttpStatus.OK);
        db.update("INSERT INTO application_requirements(application_id,requirement_code,status,verified_by) SELECT ?,code,'submitted',? FROM requirement_types WHERE mandatory",app,actor);
        assertThat(detail(app).get("attendancePresent")).isEqualTo(true);assertThat(confirm(app,0).getStatusCode()).isEqualTo(HttpStatus.OK);
        dates.put("version",1);dates.put("orientationStartsOn",null);dates.put("orientationEndsOn",null);
        assertThat(call(HttpMethod.PUT,url,dates).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_REGISTRAR',?)",actor,other);
        assertThat(call(HttpMethod.GET,"/api/enrollment/attendance-window?campusId="+other+"&termId="+term,null).getBody()).containsEntry("configured",false);
    }
}
