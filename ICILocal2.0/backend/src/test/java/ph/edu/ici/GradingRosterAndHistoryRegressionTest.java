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

/**
 * Regression tests for review findings on ICI2-25/29/41/43:
 * class rosters are populated from the section, submit ignores withdrawn students,
 * and a transferee's current campus can open their academic history.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GradingRosterAndHistoryRegressionTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }

    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    final String password="Regression-Test-Password-2026!";
    long campus,program,year,term,section,subject,period1,teacher,registrar;
    String teacherToken,registrarToken;

    String suffix(){return UUID.randomUUID().toString().replace("-","").substring(0,12);}
    long staff(String role,long campusId){
        String name="regress-"+suffix();
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES(?,?,?,?) RETURNING id",Long.class,
                name,name+"@example.invalid",name,encoder.encode(password));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES(?,?,?)",id,role,campusId);
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
    /** Enrolls a student in the section. Adds a class membership directly only when classId is given. */
    long enrollment(String name,Long classId){
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES (?,?,'Student','2009-01-01') RETURNING id",Long.class,"STU-"+suffix(),name);
        long application=db.queryForObject("""
            INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date)
            VALUES (?,?,?,?,?,'new',?,'Student','2009-01-01') RETURNING id
            """,Long.class,"APP-"+suffix(),campus,term,program,student,name);
        long enrollment=db.queryForObject("""
            INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status)
            VALUES (?,?,?,?,?,?,'provisional') RETURNING id
            """,Long.class,student,application,campus,term,program,section);
        if(classId!=null)db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",classId,enrollment,section,term);
        return enrollment;
    }
    Map<String,Object> entry(long enrollment,Object value,String mark,Integer version){
        var entry=new HashMap<String,Object>();entry.put("enrollmentId",enrollment);entry.put("value",value);entry.put("mark",mark);entry.put("version",version);return entry;
    }
    long createClassThroughApi(){
        var created=call(HttpMethod.POST,"/api/academic/class-offerings",
                Map.of("academicYearId",year,"termId",term,"sectionId",section,"subjectId",subject,"teacherId",teacher),registrarToken);
        assertThat(created.getStatusCode()).as(created.toString()).isEqualTo(HttpStatus.CREATED);
        return ((Number)created.getBody().get("classId")).longValue();
    }
    List<?> gradebook(long classId){
        var grades=call(HttpMethod.GET,"/api/academic/classes/"+classId+"/grades?academicYearId="+year+"&termId="+term+"&gradingPeriodId="+period1,null,teacherToken);
        assertThat(grades.getStatusCode()).as(grades.toString()).isEqualTo(HttpStatus.OK);
        return (List<?>)grades.getBody().get("students");
    }

    @BeforeEach void setup(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);
        program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        String key=suffix();
        year=db.queryForObject("INSERT INTO academic_years(label,starts_on,ends_on) VALUES (?,'2026-06-01','2027-04-30') RETURNING id",Long.class,"AY"+key);
        term=db.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,?,'First Term','2026-06-01','2026-10-31') RETURNING id",Long.class,year,"T"+key);
        period1=db.queryForObject("INSERT INTO grading_periods(term_id,code,sequence,starts_on,ends_on) VALUES (?,'Q1',1,'2026-06-01','2026-08-15') RETURNING id",Long.class,term);
        section=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'Grade 11',30) RETURNING id",Long.class,campus,term,program,"Section "+key);
        subject=db.queryForObject("INSERT INTO subjects(code,name,units) VALUES (?,?,1) RETURNING id",Long.class,"SUB"+key,"Regression Subject");
        teacher=staff("ROLE_TEACHER",campus);registrar=staff("ROLE_REGISTRAR",campus);
        teacherToken=login(teacher);registrarToken=login(registrar);
    }

    @Test void classCreatedThroughApiContainsTheSectionsActiveStudents(){
        enrollment("Ana",null);enrollment("Ben",null);
        long withdrawn=enrollment("Cy",null);
        db.update("UPDATE enrollments SET status='withdrawn' WHERE id=?",withdrawn);
        long classId=createClassThroughApi();
        assertThat(gradebook(classId)).as("students on the gradebook for a section with 2 active students").hasSize(2);
        assertThat(db.queryForObject("SELECT count(*) FROM class_memberships WHERE class_id=?",Integer.class,classId)).isEqualTo(2);
    }

    @Test void syncAddsStudentsWhoJoinedTheSectionAfterTheClassWasCreated(){
        enrollment("Ana",null);
        long classId=createClassThroughApi();
        enrollment("Late",null);
        String sync="/api/academic/class-offerings/"+classId+"/members/sync?academicYearId="+year+"&termId="+term;
        var synced=call(HttpMethod.POST,sync,null,registrarToken);
        assertThat(synced.getStatusCode()).as(synced.toString()).isEqualTo(HttpStatus.OK);
        assertThat(synced.getBody()).containsEntry("addedMembers",1);
        assertThat(gradebook(classId)).hasSize(2);
        assertThat(call(HttpMethod.POST,sync,null,registrarToken).getBody()).as("repeat sync is a no-op").containsEntry("addedMembers",0);
        assertThat(call(HttpMethod.POST,sync,null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        long otherCampus=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        assertThat(call(HttpMethod.POST,sync,null,login(staff("ROLE_REGISTRAR",otherCampus))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void withdrawnStudentsClearedDraftDoesNotBlockSubmit(){
        long classId=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject,teacher);
        long e1=enrollment("Ana",classId),e2=enrollment("Ben",classId),e3=enrollment("Cy",classId);
        String base="/api/academic/classes/"+classId;
        var saved=call(HttpMethod.PUT,base+"/grades",Map.of("academicYearId",year,"termId",term,"gradingPeriodId",period1,
                "entries",List.of(entry(e1,85,null,null),entry(e2,90,null,null),entry(e3,70,null,null))),teacherToken);
        assertThat(saved.getStatusCode()).as(saved.toString()).isEqualTo(HttpStatus.OK);
        var cleared=call(HttpMethod.PUT,base+"/grades",Map.of("academicYearId",year,"termId",term,"gradingPeriodId",period1,
                "entries",List.of(entry(e3,null,null,0))),teacherToken);
        assertThat(cleared.getStatusCode()).as("teacher clears Cy's draft: "+cleared).isEqualTo(HttpStatus.OK);
        db.update("UPDATE enrollments SET status='withdrawn' WHERE id=?",e3);
        var submitted=call(HttpMethod.POST,base+"/grades/submit",Map.of("academicYearId",year,"termId",term,"gradingPeriodId",period1),teacherToken);
        assertThat(submitted.getStatusCode()).as("submit after Cy withdrew: "+submitted).isEqualTo(HttpStatus.OK);
    }

    @Test void withdrawnStudentsDraftIsNotSubmitted(){
        long classId=db.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term,subject,teacher);
        long e1=enrollment("Ana",classId),e2=enrollment("Ben",classId);
        String base="/api/academic/classes/"+classId;
        call(HttpMethod.PUT,base+"/grades",Map.of("academicYearId",year,"termId",term,"gradingPeriodId",period1,
                "entries",List.of(entry(e1,85,null,null),entry(e2,null,"INC",null))),teacherToken);
        db.update("UPDATE enrollments SET status='withdrawn' WHERE id=?",e2);
        var submitted=call(HttpMethod.POST,base+"/grades/submit",Map.of("academicYearId",year,"termId",term,"gradingPeriodId",period1),teacherToken);
        assertThat(submitted.getStatusCode()).as(submitted.toString()).isEqualTo(HttpStatus.OK);
        assertThat(db.queryForObject("SELECT state FROM grades WHERE class_id=? AND enrollment_id=?",String.class,classId,e1)).isEqualTo("submitted");
        assertThat(db.queryForObject("SELECT state FROM grades WHERE class_id=? AND enrollment_id=?",String.class,classId,e2))
                .as("withdrawn student's INC draft state").isEqualTo("draft");
        assertThat(db.queryForObject("SELECT count(*) FROM incomplete_cases ic JOIN grades g ON g.id=ic.grade_id WHERE g.enrollment_id=?",Integer.class,e2))
                .as("no incomplete case opened for the withdrawn student").isZero();
    }

    @Test void transfereesCurrentCampusCanSeeTheirAcademicHistory(){
        long kapatagan=db.queryForObject("SELECT id FROM campuses WHERE code='KAPATAGAN'",Long.class);
        long bugo=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);
        long kSection=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'Grade 11',30) RETURNING id",Long.class,kapatagan,term,program,"K-Section "+suffix());
        String last="Transferee"+suffix();
        long student=db.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date,campus_id,program_id) VALUES (?,'Carlo',?,'2009-01-01',?,?) RETURNING id",Long.class,"STU-"+suffix(),last,campus,program);
        long application=db.queryForObject("""
            INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date)
            VALUES (?,?,?,?,?,'transferee','Carlo',?,'2009-01-01') RETURNING id
            """,Long.class,"APP-"+suffix(),kapatagan,term,program,student,last);
        db.update("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id,status) VALUES (?,?,?,?,?,?,'provisional')",
                student,application,kapatagan,term,program,kSection);

        String kPrincipal=login(staff("ROLE_PRINCIPAL",kapatagan));
        var history=call(HttpMethod.GET,"/api/academic/history/students/"+student,null,kPrincipal);
        assertThat(history.getStatusCode()).as("Kapatagan principal opening a student enrolled at Kapatagan: "+history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>)history.getBody().get("terms")).hasSize(1);
        var directory=call(HttpMethod.GET,"/api/academic/history/students?search="+last,null,kPrincipal);
        assertThat((List<Map>)directory.getBody().get("items")).extracting(row->((Number)row.get("id")).longValue()).containsExactly(student);

        String bPrincipal=login(staff("ROLE_PRINCIPAL",bugo));
        assertThat(call(HttpMethod.GET,"/api/academic/history/students/"+student,null,bPrincipal).getStatusCode())
                .as("a campus with neither the home record nor an enrollment still gets 404").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
