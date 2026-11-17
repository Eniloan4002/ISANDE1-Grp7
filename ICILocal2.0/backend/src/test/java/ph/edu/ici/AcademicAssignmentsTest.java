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
class AcademicAssignmentsTest {
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

    @Test void registrarCanCreateAndReassignClassOfferingsWithCampusRoleValidation(){
        var created=createOffering(teacher);long id=((Number)created.get("classId")).longValue();
        assertThat(((Number)created.get("teacherId")).longValue()).isEqualTo(teacher);assertThat(created).containsEntry("version",0);
        String assignments="/api/academic/assignments?academicYearId="+year+"&termId="+term+"&campusId="+campus;
        var list=call(HttpMethod.GET,assignments,null,registrarToken);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List)list.getBody().get("classes")).hasSize(1);
        assertThat((List<Map>)list.getBody().get("teachers")).extracting(row->((Number)row.get("id")).longValue()).contains(teacher,otherTeacher);

        var reassignment=Map.of("academicYearId",year,"termId",term,"teacherId",otherTeacher,"version",0);
        var saved=call(HttpMethod.PUT,"/api/academic/class-offerings/"+id+"/teacher",reassignment,registrarToken);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(((Number)saved.getBody().get("teacherId")).longValue()).isEqualTo(otherTeacher);assertThat(saved.getBody()).containsEntry("version",1);
        assertThat(call(HttpMethod.PUT,"/api/academic/class-offerings/"+id+"/teacher",reassignment,registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var crossCampus=new HashMap<String,Object>(reassignment);crossCampus.put("teacherId",otherCampusTeacher);crossCampus.put("version",1);
        assertThat(call(HttpMethod.PUT,"/api/academic/class-offerings/"+id+"/teacher",crossCampus,registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var wrongYear=new HashMap<String,Object>(reassignment);wrongYear.put("academicYearId",year+100000);wrongYear.put("version",1);
        assertThat(call(HttpMethod.PUT,"/api/academic/class-offerings/"+id+"/teacher",wrongYear,registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET,"/api/academic/assignments?academicYearId="+year+"&termId="+term+"&campusId="+otherCampus,null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/api/academic/class-offerings",offeringInput(teacher),teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='class_offerings' AND operation='UPDATE' AND actor_id=?",Integer.class,registrar)).isEqualTo(1);
    }

    @Test void teachersReadOnlyTheirAssignedClassesAndStudents(){
        long assigned=((Number)createOffering(teacher).get("classId")).longValue();
        String key=suffix();
        long secondSection=db.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,?,'Grade 11',30) RETURNING id",Long.class,campus,term,program,"Section "+key);
        long secondSubject=db.queryForObject("INSERT INTO subjects(code,name,units) VALUES (?,?,1) RETURNING id",Long.class,"SUB"+key,"Second Subject");
        long other=((Number)createOffering(secondSection,secondSubject,otherTeacher).get("classId")).longValue();
        long enrollment=studentEnrollment("Ari");
        db.update("INSERT INTO class_memberships(class_id,enrollment_id,section_id,term_id) VALUES (?,?,?,?)",assigned,enrollment,section,term);

        String listPath="/api/academic/classes?academicYearId="+year+"&termId="+term;
        var own=call(HttpMethod.GET,listPath,null,teacherToken);assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>)own.getBody().get("items")).extracting(row->((Number)row.get("classId")).longValue()).containsExactly(assigned);
        var detail=call(HttpMethod.GET,"/api/academic/classes/"+assigned+"?academicYearId="+year+"&termId="+term,null,teacherToken);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>)detail.getBody().get("students")).singleElement().satisfies(row->assertThat(row).containsEntry("name","Ari Student").containsEntry("requirementsStatus","Incomplete"));
        assertThat(call(HttpMethod.GET,"/api/academic/classes/"+other+"?academicYearId="+year+"&termId="+term,null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET,listPath,null,registrarToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/academic/classes/"+assigned+"?academicYearId="+year+"&termId="+(term+1),null,teacherToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void advisoryRostersAndWithdrawalDecisionsFollowTheAssignedSection(){
        long enrollment=studentEnrollment("Mina");
        var assignAdviser=Map.of("academicYearId",year,"termId",term,"adviserId",adviser,"version",0);
        var assigned=call(HttpMethod.PUT,"/api/academic/sections/"+section+"/adviser",assignAdviser,registrarToken);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(((Number)assigned.getBody().get("adviserId")).longValue()).isEqualTo(adviser);assertThat(assigned.getBody()).containsEntry("version",1);

        String rosterPath="/api/academic/advisory/rosters?academicYearId="+year+"&termId="+term;
        var roster=call(HttpMethod.GET,rosterPath,null,adviserToken);assertThat(roster.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> sections=(List<Map>)roster.getBody().get("sections");assertThat(sections).hasSize(1).singleElement().satisfies(row->{
            assertThat(((Number)row.get("id")).longValue()).isEqualTo(section);assertThat(row).containsEntry("name",db.queryForObject("SELECT name FROM sections WHERE id=?",String.class,section));
            assertThat((List<Map>)row.get("students")).singleElement().satisfies(student->assertThat(student).containsEntry("name","Mina Student").containsEntry("enrollmentStatus","Pre-enlisted").containsEntry("requirementsStatus","Incomplete"));
        });
        assertThat(((List)call(HttpMethod.GET,rosterPath,null,otherAdviserToken).getBody().get("sections"))).isEmpty();

        long first=withdrawal(enrollment,"Family relocation");
        var queue=call(HttpMethod.GET,"/api/academic/withdrawal-endorsements",null,adviserToken);
        assertThat((List<Map>)queue.getBody().get("items")).extracting(row->((Number)row.get("withdrawalId")).longValue()).contains(first);
        assertThat(((List)call(HttpMethod.GET,"/api/academic/withdrawal-endorsements",null,otherAdviserToken).getBody().get("items"))).isEmpty();
        var decision=Map.of("decision","cleared","notes","Adviser reviewed the request","version",0);
        assertThat(call(HttpMethod.PUT,"/api/academic/withdrawal-endorsements/"+first,decision,otherAdviserToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var cleared=call(HttpMethod.PUT,"/api/academic/withdrawal-endorsements/"+first,decision,adviserToken);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(cleared.getBody()).containsEntry("endorsementStatus","cleared").containsEntry("withdrawalStatus","pending").containsEntry("version",1);
        assertThat(db.queryForObject("SELECT status FROM withdrawals WHERE id=?",String.class,first)).isEqualTo("pending");
        assertThat(db.queryForObject("SELECT acted_by FROM withdrawal_clearances WHERE withdrawal_id=? AND office='adviser'",Long.class,first)).isEqualTo(adviser);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='withdrawal_clearances' AND operation='UPDATE' AND actor_id=?",Integer.class,adviser)).isEqualTo(1);
        assertThat(call(HttpMethod.PUT,"/api/academic/withdrawal-endorsements/"+first,decision,adviserToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        long rejected=withdrawal(studentEnrollment("Nico"),"Moving schools");
        var reject=Map.of("decision","rejected","version",0);
        var result=call(HttpMethod.PUT,"/api/academic/withdrawal-endorsements/"+rejected,reject,adviserToken);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(result.getBody()).containsEntry("withdrawalStatus","rejected");
        assertThat(db.queryForObject("SELECT status FROM withdrawals WHERE id=?",String.class,rejected)).isEqualTo("rejected");
    }

    @Test void sectionAdviserAssignmentRejectsWrongRoleCampusAndStaleVersions(){
        var body=Map.of("academicYearId",year,"termId",term,"adviserId",otherCampusTeacher,"version",0);
        assertThat(call(HttpMethod.PUT,"/api/academic/sections/"+section+"/adviser",body,registrarToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var assigned=Map.of("academicYearId",year,"termId",term,"adviserId",adviser,"version",0);
        assertThat(call(HttpMethod.PUT,"/api/academic/sections/"+section+"/adviser",assigned,registrarToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,"/api/academic/sections/"+section+"/adviser",assigned,registrarToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.PUT,"/api/academic/sections/"+section+"/adviser",assigned,adviserToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
