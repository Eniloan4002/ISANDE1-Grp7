package ph.edu.ici;

import java.util.*;
import java.time.OffsetDateTime;
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
class GradingWindowTest {
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
        section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'11',30) RETURNING id",Long.class,campus,term,program,"Section "+key);
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


    String windowPath(){return "/api/academic/sections/"+section+"/grading-periods/"+period1;}
    Map<String,Object> change(int version){return Map.of("version",version,"reason","Fictional grading-control integration test");}
    Map<String,Object> deadline(int version){return Map.of("version",version,"reason","Fictional submission cutoff","deadline",OffsetDateTime.now().plusHours(1).toString());}
    Map<String,Object> window(ResponseEntity<Map> response){return (Map<String,Object>)response.getBody().get("window");}
    void submitAll(){
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,85,null,null),entry(e2,null,"INC",null),entry(e3,90,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void registrarSetsDeadlineLocksAndRetainsAttributedState(){
        var original=call(HttpMethod.GET,windowPath()+"/window",null,registrarToken);
        assertThat(original.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(window(original)).containsEntry("version",0).containsEntry("status","open");
        var scheduled=call(HttpMethod.PUT,windowPath()+"/deadline",deadline(0),registrarToken);
        assertThat(scheduled.getStatusCode()).as(scheduled.toString()).isEqualTo(HttpStatus.OK);
        assertThat(window(scheduled)).containsEntry("version",1).containsEntry("status","open");
        assertThat(call(HttpMethod.POST,windowPath()+"/lock",change(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var locked=call(HttpMethod.POST,windowPath()+"/lock",change(1),registrarToken);
        assertThat(locked.getStatusCode()).as(locked.toString()).isEqualTo(HttpStatus.OK);
        assertThat(window(locked)).containsEntry("version",2).containsEntry("status","locked");
        assertThat(((Number)window(locked).get("lockedBy")).longValue()).isEqualTo(registrar);
        assertThat(window(locked).get("lockedAt")).isNotNull();
        assertThat(window(call(HttpMethod.GET,windowPath()+"/window",null,registrarToken))).containsEntry("version",2).containsEntry("status","locked");
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,80,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period2,entry(e1,80,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,windowPath()+"/deadline",deadline(2),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='section_grading_windows' AND actor_id=? AND new_data->>'section_id'=?",Integer.class,registrar,Long.toString(section))).isEqualTo(2);
    }

    @Test void elapsedDeadlinesAutomaticallyBlockTeacherWritesAndCannotBeExtended(){
        assertThat(call(HttpMethod.PUT,windowPath()+"/deadline",deadline(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        db.update("UPDATE section_grading_windows SET deadline=clock_timestamp()-interval '1 second' WHERE section_id=?",section);
        assertThat(window(call(HttpMethod.GET,windowPath()+"/window",null,registrarToken))).containsEntry("status","locked");
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,81,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT,windowPath()+"/deadline",deadline(1),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Existing class-specific deadline consumers must enforce cutoff too.
        db.update("INSERT INTO grading_windows(class_id,grading_period_id,term_id,deadline) VALUES (?,?,?,clock_timestamp()-interval '1 second')",classId,period2,term);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period2,entry(e1,81,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void finalizedGradesAreAtomicImmutableAndAudited(){
        assertThat(call(HttpMethod.POST,windowPath()+"/finalize",change(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT count(*) FROM section_grading_windows WHERE section_id=?",Integer.class,section)).isZero();
        submitAll();
        var result=call(HttpMethod.POST,windowPath()+"/finalize",change(0),registrarToken);
        assertThat(result.getStatusCode()).as(result.toString()).isEqualTo(HttpStatus.OK);
        assertThat(window(result)).containsEntry("status","finalized").containsEntry("version",1);
        assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=? AND state='finalized' AND version=2",Integer.class,classId)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='grades' AND actor_id=? AND new_data->>'class_id'=? AND new_data->>'state'='finalized'",Integer.class,registrar,Long.toString(classId))).isEqualTo(3);
        var reloaded=call(HttpMethod.GET,gradesPath(period1),null,teacherToken);
        assertThat(reloaded.getBody()).containsEntry("state","finalized");
        assertThat(((Map)reloaded.getBody().get("period"))).containsEntry("status","locked");
        assertThat(call(HttpMethod.POST,windowPath()+"/finalize",change(1),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,10,null,2)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        long newSubject=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Late Subject') RETURNING id",Long.class,"LATE"+suffix());
        assertThat(call(HttpMethod.POST,"/api/academic/class-offerings",Map.of("academicYearId",year,"termId",term,
                "sectionId",section,"subjectId",newSubject,"teacherId",teacher),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void oneIncompleteClassPreventsPartialSectionFinalization(){
        submitAll();
        long subject2=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Other Subject') RETURNING id",Long.class,"OTHER"+suffix());
        long class2=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject2,teacher);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",class2,e1,section,term);
        assertThat(call(HttpMethod.POST,windowPath()+"/finalize",change(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=? AND state='submitted'",Integer.class,classId)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM section_grading_windows WHERE section_id=?",Integer.class,section)).isZero();
    }

    @Test void pastDeadlinesAreRejectedWithoutChangingState(){
        var body=new HashMap<String,Object>(deadline(0));
        body.put("deadline",OffsetDateTime.now().minusMinutes(1).toString());
        var response=call(HttpMethod.PUT,windowPath()+"/deadline",body,registrarToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody())).contains("Deadline must be in the future");
        assertThat(db.queryForObject("SELECT count(*) FROM section_grading_windows WHERE section_id=?",Integer.class,section)).isZero();
        assertThat(window(call(HttpMethod.GET,windowPath()+"/window",null,registrarToken))).containsEntry("status","open").containsEntry("version",0);
        // Elapsed future deadline reports locked without inventing an actor.
        assertThat(call(HttpMethod.PUT,windowPath()+"/deadline",deadline(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        db.update("UPDATE section_grading_windows SET deadline=clock_timestamp()-interval '1 second' WHERE section_id=?",section);
        var elapsed=window(call(HttpMethod.GET,windowPath()+"/window",null,registrarToken));
        assertThat(elapsed).containsEntry("status","locked").containsEntry("lockedBy",null).containsEntry("lockedAt",null);
    }

    @Test void finalizationSkipsAndReportsOfferingsWithoutActiveMembers(){
        submitAll();
        long subject2=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Empty Elective') RETURNING id",Long.class,"EMPTY"+suffix());
        long empty=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject2,teacher);
        long withdrawn=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,
                db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'Withdrawn Elective') RETURNING id",Long.class,"WD"+suffix()),teacher);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",withdrawn,e1,section,term);
        db.update("UPDATE enrollments SET status='withdrawn' WHERE id=?",e1);
        var result=call(HttpMethod.POST,windowPath()+"/finalize",change(0),registrarToken);
        assertThat(result.getStatusCode()).as(result.toString()).isEqualTo(HttpStatus.OK);
        assertThat(window(result)).containsEntry("status","finalized");
        var skipped=(List<Map<String,Object>>)result.getBody().get("skippedClasses");
        assertThat(skipped).extracting(row->((Number)row.get("classId")).longValue()).contains(empty);
    }

    @Test void classesAddedAfterLockInheritIt(){
        assertThat(call(HttpMethod.POST,windowPath()+"/lock",change(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        long subject2=db.queryForObject("INSERT INTO subjects(code,name) VALUES (?,'New Subject') RETURNING id",Long.class,"NEW"+suffix());
        classId=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject2,teacher);
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",classId,e1,section,term);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,81,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void permissionsScopesVersionsAndPeriodsAreValidated(){
        assertThat(call(HttpMethod.GET,windowPath()+"/window",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST,windowPath()+"/lock",change(0),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        long originalCampus=campus;
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        long foreignRegistrar=staff("ROLE_REGISTRAR");String foreignToken=login(foreignRegistrar);campus=originalCampus;
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_TEACHER',?)",foreignRegistrar,campus);
        assertThat(call(HttpMethod.GET,windowPath()+"/window",null,foreignToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST,windowPath()+"/lock",change(0),foreignToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST,windowPath()+"/lock",Map.of("reason","no version"),registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,windowPath()+"/lock",Map.of("reason"," ","version",0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long badPeriod=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence) VALUES (?,'OTHER',1) RETURNING id",Long.class,otherTerm);
        assertThat(call(HttpMethod.POST,"/api/academic/sections/"+section+"/grading-periods/"+badPeriod+"/lock",change(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var listed=call(HttpMethod.GET,"/api/academic/grading-windows?sectionId="+section+"&termId="+term+"&gradeLevel=11",null,registrarToken);
        assertThat(listed.getStatusCode()).as(listed.toString()).isEqualTo(HttpStatus.OK);
        assertThat(((Number)listed.getBody().get("total")).longValue()).isEqualTo(2);
        assertThat(call(HttpMethod.GET,"/api/academic/grading-windows?size=101",null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void concurrentRegistrarLocksHaveOneWinner()throws Exception{
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<ResponseEntity<Map>> task=()->{start.await();return call(HttpMethod.POST,windowPath()+"/lock",change(0),registrarToken);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS).getStatusCode(),b.get(15,TimeUnit.SECONDS).getStatusCode()))
                    .containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
            assertThat(db.queryForObject("SELECT version FROM section_grading_windows WHERE section_id=?",Integer.class,section)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    @Test void writerWaitingOnOfferingCannotPassAnExpiredDeadline()throws Exception{
        assertThat(call(HttpMethod.PUT,windowPath()+"/deadline",deadline(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        db.update("UPDATE section_grading_windows SET deadline=clock_timestamp()+interval '1 second' WHERE section_id=?",section);
        var pool=Executors.newSingleThreadExecutor();
        try(var connection=db.getDataSource().getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM class_offerings WHERE id=? FOR UPDATE")){
                statement.setLong(1,classId);statement.executeQuery();
            }
            var pending=pool.submit(()->call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,81,null,null)),teacherToken));
            // Keep the offering locked until the database's actual cutoff passes.
            db.execute("SELECT pg_sleep(1.2)");connection.commit();
            assertThat(pending.get(10,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=?",Integer.class,classId)).isZero();
        }finally{pool.shutdownNow();}
    }
    @Test void teacherBatchAndManualLockSerializeWithoutPartialWrites()throws Exception{
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var writer=pool.submit(()->{start.await();return call(HttpMethod.PUT,base()+"/grades",
                    draft(period1,entry(e1,85,null,null),entry(e2,86,null,null),entry(e3,87,null,null)),teacherToken);});
            var locker=pool.submit(()->{start.await();return call(HttpMethod.POST,windowPath()+"/lock",change(0),registrarToken);});
            start.countDown();
            assertThat(locker.get(15,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            var writerStatus=writer.get(15,TimeUnit.SECONDS).getStatusCode();
            assertThat(writerStatus).isIn(HttpStatus.OK,HttpStatus.FORBIDDEN);
            assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=?",Integer.class,classId))
                    .isEqualTo(writerStatus==HttpStatus.OK?3:0);
            assertThat(window(call(HttpMethod.GET,windowPath()+"/window",null,registrarToken))).containsEntry("status","locked");
            assertThat(call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }finally{pool.shutdownNow();}
    }

    @Test void draftThatCrossesDeadlineRollsBackItsChanges()throws Exception{
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,85,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,windowPath()+"/deadline",deadline(0),registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        var pool=Executors.newSingleThreadExecutor();
        try(var connection=db.getDataSource().getConnection()){
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM grades WHERE class_id=? AND enrollment_id=? FOR UPDATE")){
                statement.setLong(1,classId);statement.setLong(2,e1);statement.executeQuery();
            }
            db.update("UPDATE section_grading_windows SET deadline=clock_timestamp()+interval '1 second' WHERE section_id=?",section);
            var pending=pool.submit(()->call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,90,null,0)),teacherToken));
            db.execute("SELECT pg_sleep(1.2)");connection.commit();
            assertThat(pending.get(10,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(db.queryForObject("SELECT value FROM grades WHERE class_id=? AND enrollment_id=?",Integer.class,classId,e1)).isEqualTo(85);
            assertThat(db.queryForObject("SELECT version FROM grades WHERE class_id=? AND enrollment_id=?",Integer.class,classId,e1)).isZero();
        }finally{pool.shutdownNow();}
    }

    @Test void concurrentFinalizationsOnlyApplyOnce()throws Exception{
        submitAll();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            Callable<ResponseEntity<Map>> task=()->{start.await();return call(HttpMethod.POST,windowPath()+"/finalize",change(0),registrarToken);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS).getStatusCode(),b.get(15,TimeUnit.SECONDS).getStatusCode()))
                    .containsExactlyInAnyOrder(HttpStatus.OK,HttpStatus.CONFLICT);
            assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=? AND state='finalized' AND version=2",Integer.class,classId)).isEqualTo(3);
        }finally{pool.shutdownNow();}
    }

}
