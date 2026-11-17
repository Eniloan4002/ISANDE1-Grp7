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
class GradeSubmissionTest {
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

    @Test void teacherListsPeriodsDraftsAndSubmitsTheirClass(){
        var periods=call(HttpMethod.GET,base()+"/grading-periods?academicYearId="+year+"&termId="+term,null,teacherToken);
        assertThat(periods.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>)periods.getBody().get("periods")).extracting(row->row.get("code")).containsExactly("Q1","Q2");
        assertThat((List<Map>)periods.getBody().get("periods")).allSatisfy(row->assertThat(row).containsEntry("status","open"));

        var empty=call(HttpMethod.GET,gradesPath(period1),null,teacherToken);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List)empty.getBody().get("students")).hasSize(3);
        assertThat(empty.getBody()).containsEntry("state","draft");

        var saved=call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,87.5,null,null),entry(e2,null,"inc",null),entry(e3,null,null,null)),teacherToken);
        assertThat(saved.getStatusCode()).as(saved.toString()).isEqualTo(HttpStatus.OK);
        assertThat(((Number)student(saved.getBody(),e1).get("value")).doubleValue()).isEqualTo(87.5);
        assertThat(student(saved.getBody(),e1)).containsEntry("version",0).containsEntry("state","draft");
        assertThat(student(saved.getBody(),e2)).containsEntry("mark","INC");
        assertThat(student(saved.getBody(),e3).get("version")).isNull();

        var changed=call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,90,null,0),entry(e2,null,"INC",0)),teacherToken);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(student(changed.getBody(),e1)).containsEntry("version",1);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,91,null,0)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e3,70,null,0)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var incomplete=call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),teacherToken);
        assertThat(incomplete.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=? AND state='draft'",Integer.class,classId)).isEqualTo(2);

        call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e3,76.25,null,null)),teacherToken);
        var submitted=call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),teacherToken);
        assertThat(submitted.getStatusCode()).as(submitted.toString()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody()).containsEntry("state","submitted");
        assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=? AND state='submitted'",Integer.class,classId)).isEqualTo(3);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,50,null,2)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var otherPeriod=call(HttpMethod.GET,gradesPath(period2),null,teacherToken);
        assertThat(otherPeriod.getBody()).containsEntry("state","draft");
        assertThat(student(otherPeriod.getBody(),e1).get("value")).isNull();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='grades' AND actor_id=?",Integer.class,teacher)).isGreaterThan(0);
    }

    @Test void gradeValuesAndStudentsAreValidated(){
        for(var bad:List.<Map<String,Object>>of(entry(e1,100.01,null,null),entry(e1,-1,null,null),entry(e1,85.555,null,null),
                entry(e1,80,"INC",null),entry(e1,null,"XYZ",null)))
            assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,bad),teacherToken).getStatusCode()).as(bad.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,80,null,null),entry(e1,81,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1+100000,80,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,100,null,null),entry(e2,0,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT count(*) FROM grades WHERE class_id=?",Integer.class,classId)).isEqualTo(2);
    }

    @Test void onlyTheAssignedTeacherCanUseAnOpenPeriodOfTheirClass(){
        assertThat(call(HttpMethod.GET,gradesPath(period1),null,otherTeacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,80,null,null)),otherTeacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),otherTeacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET,gradesPath(period1),null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,gradesPath(period1),null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET,base()+"/grades?academicYearId="+year+"&termId="+otherTerm+"&gradingPeriodId="+period1,null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        long foreignPeriod=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence,starts_on,ends_on) VALUES (?,'Q1',1,'2026-11-01','2027-01-15') RETURNING id",Long.class,otherTerm);
        assertThat(call(HttpMethod.GET,gradesPath(foreignPeriod),null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void lockedGradingWindowRejectsDraftsAndSubmission(){
        db.update("INSERT INTO grading_windows(class_id,grading_period_id,term_id,deadline,locked_at,locked_by) VALUES (?,?,?,now(),now(),?)",classId,period1,term,registrar);
        var periods=call(HttpMethod.GET,base()+"/grading-periods?academicYearId="+year+"&termId="+term,null,teacherToken);
        assertThat((List<Map>)periods.getBody().get("periods")).extracting(row->row.get("status")).containsExactly("locked","open");
        assertThat(call(HttpMethod.GET,gradesPath(period1),null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period1,entry(e1,80,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,base()+"/grades/submit",submitBody(period1),teacherToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,base()+"/grades",draft(period2,entry(e1,80,null,null)),teacherToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
