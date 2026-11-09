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
class StudentMasterTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder encoder;
    String token,otherToken;long user,campus,otherCampus,program;
    @BeforeEach void init(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);otherCampus=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);program=db.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        String name="staff-"+UUID.randomUUID();
        user=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid","Registrar",encoder.encode("Test-password-123!"));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_REGISTRAR',?)",user,campus);
        token=(String)rest.postForObject("/api/auth/login",Map.of("username",name,"password","Test-password-123!"),Map.class).get("accessToken");
    }
    Map<String,Object> input(){
        Map<String,Object> p=new HashMap<>();p.put("campusId",campus);p.put("programId",program);p.put("voucherCode","QVR");p.put("track","ACADEMIC");p.put("firstName","Ana");p.put("lastName","Student-"+UUID.randomUUID());p.put("birthDate","2009-01-01");p.put("email","ana@example.invalid");p.put("phone","09171234567");p.put("address",Map.of("city","Iligan","province","Lanao del Norte"));p.put("previousSchoolName","Test Junior High");p.put("householdIncomeBracket","School-defined bracket");p.put("guardians",List.of(Map.of("fullName","Parent Student","relationship","mother","primaryContact",true)));p.put("active",true);return p;
    }
    ResponseEntity<Map> call(HttpMethod method,String path,Object body){HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(path,method,new HttpEntity<>(body,h),Map.class);}
    HttpStatusCode setupStatus(){HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);return rest.exchange("/api/setup/subjects",HttpMethod.GET,new HttpEntity<>(h),Object.class).getStatusCode();}
    Map create(Map p){var r=call(HttpMethod.POST,"/api/students",p);assertThat(r.getStatusCode()).describedAs(r.toString()).isEqualTo(HttpStatus.CREATED);return r.getBody();}
    @Test void createsCompleteProfileAndReadsPersistedGuardians(){
        var p=input();p.put("lrn","123456789012");var student=create(p);String path="/api/students/"+student.get("id");
        var read=call(HttpMethod.GET,path,null);assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(read.getBody()).containsEntry("previous_school_name","Test Junior High").containsEntry("household_income_bracket","School-defined bracket").containsEntry("voucher_code","QVR");
        assertThat((List)read.getBody().get("guardians")).hasSize(1);assertThat((Map)read.getBody().get("address")).containsEntry("city","Iligan");
        assertThat(((Number)student.get("created_by")).longValue()).isEqualTo(user);
        var second=input();second.put("lrn","123456789012");assertThat(call(HttpMethod.POST,"/api/students",second).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void validationAndBadGuardiansDoNotLeavePartialProfiles(){
        for(var bad:List.of(Map.of("lrn","bad"),Map.of("birthDate","2099-01-01"),Map.of("firstName"," "),Map.of("email","bad"),Map.of("track","TVET"))){var p=input();p.putAll(bad);assertThat(call(HttpMethod.POST,"/api/students",p).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);}
        var p=input();p.put("guardians",List.of(Map.of("fullName","One","relationship","mother","primaryContact",true),Map.of("fullName","Two","relationship","father","primaryContact",true)));
        long before=db.queryForObject("SELECT count(*) FROM students",Long.class);assertThat(call(HttpMethod.POST,"/api/students",p).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(db.queryForObject("SELECT count(*) FROM students",Long.class)).isEqualTo(before);
    }
    @Test void updatesAreVersionedAndArchivingPreservesIdentity(){
        var p=input();var student=create(p);String path="/api/students/"+student.get("id");p.put("version",0);p.put("phone","09999999999");p.put("guardians",List.of(Map.of("fullName","New Guardian","relationship","father","primaryContact",true)));
        assertThat(call(HttpMethod.PUT,path,p).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(call(HttpMethod.PUT,path,p).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var read=call(HttpMethod.GET,path,null).getBody();assertThat(read.get("phone")).isEqualTo("09999999999");assertThat(((Map)((List)read.get("guardians")).getFirst()).get("full_name")).isEqualTo("New Guardian");
        assertThat(call(HttpMethod.DELETE,path+"?version=0",null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(call(HttpMethod.DELETE,path+"?version=1",null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);assertThat(call(HttpMethod.GET,path,null).getBody().get("active")).isEqualTo(false);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='students' AND operation='UPDATE' AND actor_id=?",Integer.class,user)).isEqualTo(2);
    }
    @Test void directoryFiltersPaginationAndCampusIsolation(){
        var p=input();var student=create(p);String name=(String)p.get("lastName");
        var list=call(HttpMethod.GET,"/api/students?search="+name+"&size=1&voucherCode=QVR&campusId="+campus,null);assertThat(((Number)list.getBody().get("total")).intValue()).isEqualTo(1);assertThat((List)list.getBody().get("items")).hasSize(1);
        assertThat(call(HttpMethod.GET,"/api/students?size=101",null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET,"/api/students?campusId="+otherCampus,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var other=input();other.put("campusId",otherCampus);assertThat(call(HttpMethod.POST,"/api/students",other).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        db.update("UPDATE students SET campus_id=? WHERE id=?",otherCampus,student.get("id"));
        assertThat(call(HttpMethod.GET,"/api/students/"+student.get("id"),null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET,"/api/students?search="+name,null).getBody().get("total")).isEqualTo(0);
    }
    @Test void referenceDataAndRoleRestrictions(){
        var refs=call(HttpMethod.GET,"/api/students/reference-data",null);assertThat((List)refs.getBody().get("campuses")).hasSize(1);assertThat((List)refs.getBody().get("vouchers")).hasSize(4);
        db.update("DELETE FROM user_role_grants WHERE user_id=?",user);db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_TEACHER',?)",user,campus);
        assertThat(call(HttpMethod.POST,"/api/students",input()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void allSeededRolesRespectStudentAndSetupPermissions(){
        for(String role:List.of("ROLE_ADMIN","ROLE_REGISTRAR","ROLE_PRINCIPAL","ROLE_ADVISER","ROLE_TEACHER","ROLE_GUIDANCE","ROLE_CASHIER_REF")){
            db.update("DELETE FROM user_role_grants WHERE user_id=?",user);
            db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",user,role,campus);
            HttpStatus expected=List.of("ROLE_ADMIN","ROLE_REGISTRAR").contains(role)?HttpStatus.OK:HttpStatus.FORBIDDEN;
            assertThat(call(HttpMethod.GET,"/api/students",null).getStatusCode()).as(role+" students").isEqualTo(expected);
            assertThat(setupStatus()).as(role+" setup").isEqualTo(expected);
        }
    }
    @Test void permissionAndCampusMustComeFromSameGrant(){
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_TEACHER',?)",user,otherCampus);
        assertThat(call(HttpMethod.GET,"/api/students?campusId="+campus,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,"/api/students?campusId="+otherCampus,null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/api/students",input()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        db.update("DELETE FROM user_role_grants WHERE user_id=? AND role_code='ROLE_REGISTRAR'",user);
        assertThat(call(HttpMethod.GET,"/api/students",null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void permissionMappingChangesApplyToExistingToken(){
        try {
            db.update("DELETE FROM role_permissions WHERE role_code='ROLE_REGISTRAR' AND permission_code='enrollment.manage'");
            assertThat(call(HttpMethod.GET,"/api/students",null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(setupStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            db.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('ROLE_REGISTRAR','enrollment.manage') ON CONFLICT DO NOTHING");
        }
        assertThat(call(HttpMethod.GET,"/api/students",null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

}
