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
class AccountManagementTest {
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
        r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired TestRestTemplate rest;
    long campus,other,admin;String token;final String password="Secure-Password-123!";
    @BeforeEach void init() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        campus=jdbc.queryForObject("INSERT INTO campuses(code,name) VALUES (?,?) RETURNING id",Long.class,"A"+suffix,"Campus A");
        other=jdbc.queryForObject("INSERT INTO campuses(code,name) VALUES (?,?) RETURNING id",Long.class,"B"+suffix,"Campus B");
        admin=staff("admin"+suffix,"ROLE_ADMIN",campus);
        token=login("admin"+suffix,password);
    }
    long staff(String name,String role,long location) {
        long id=jdbc.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid",name,passwords.encode(password));
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,?,?)",id,role,location);return id;
    }
    String login(String username,String secret) {return (String)rest.postForEntity("/api/auth/login",Map.of("username",username,"password",secret),Map.class).getBody().get("accessToken");}
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String bearer) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)headers.setBearerAuth(bearer);
        return rest.exchange(path,method,new HttpEntity<>(body,headers),Map.class);
    }
    Map<String,Object> input(String name,long location,String role) {return Map.of("username",name,"email",name+"@example.invalid","fullName","Staff Name","password",password,"grants",List.of(Map.of("campusId",location,"role",role)));}
    @Test void createEditAndScopeEnforcement() {
        String name="staff"+UUID.randomUUID().toString().substring(0,8);
        assertThat(call(HttpMethod.GET,"/api/admin/users",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var created=call(HttpMethod.POST,"/api/admin/users",input(name,campus,"ROLE_TEACHER"),token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(created.getBody()).doesNotContainKeys("password","password_hash");
        long id=((Number)created.getBody().get("id")).longValue();
        assertThat(call(HttpMethod.POST,"/api/admin/users",input(name.toUpperCase(),campus,"ROLE_TEACHER"),token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,"/api/admin/users",input("outside",other,"ROLE_TEACHER"),token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/api/admin/users",input("invalid",campus,"ROLE_UNKNOWN"),token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var duplicateEmail=new HashMap<>(input("distinct"+name,campus,"ROLE_TEACHER"));duplicateEmail.put("email",name.toUpperCase()+"@EXAMPLE.INVALID");
        assertThat(call(HttpMethod.POST,"/api/admin/users",duplicateEmail,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var noGrants=new HashMap<>(input("empty"+name,campus,"ROLE_TEACHER"));noGrants.put("grants",List.of());
        assertThat(call(HttpMethod.POST,"/api/admin/users",noGrants,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var badPassword=new HashMap<>(input("invalidpass"+name,campus,"ROLE_TEACHER"));badPassword.put("password","漢".repeat(25));
        assertThat(call(HttpMethod.POST,"/api/admin/users",badPassword,token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var changed=new HashMap<>(input(name,campus,"ROLE_REGISTRAR"));changed.put("fullName","Updated Name");
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+id,changed,token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE id=?",String.class,id)).isEqualTo("Updated Name");
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES (?,'ROLE_TEACHER',?)",id,other);
        assertThat(call(HttpMethod.GET,"/api/admin/users/"+id,null,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders listHeaders=new HttpHeaders();listHeaders.setBearerAuth(token);
        var list=rest.exchange("/api/admin/users",HttpMethod.GET,new HttpEntity<>(listHeaders),List.class);
        assertThat(list.getBody().toString()).doesNotContain(name);
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+id,changed,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String teacher=login(name,password);
        assertThat(call(HttpMethod.GET,"/api/admin/users",null,teacher).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void deactivationAndPasswordResetPermanentlyInvalidateTokens() {
        String name="user"+UUID.randomUUID().toString().substring(0,8);long id=staff(name,"ROLE_TEACHER",campus);String original=login(name,password);
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+id+"/status",Map.of("active",false),token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.postForEntity("/api/auth/login",Map.of("username",name,"password",password),Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,original).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+id+"/status",Map.of("active",true),token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,original).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String renewed=login(name,password);
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+id+"/password",Map.of("password","short"),token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+id+"/password",Map.of("password","New-Secure-Password-456!"),token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,renewed).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(name,"New-Secure-Password-456!")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='users' AND (old_data ? 'password_hash' OR new_data ? 'password_hash')",Integer.class)).isZero();
    }
    @Test void cannotDeactivateSelfOrRemoveOwnAdminGrant() {
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+admin+"/status",Map.of("active",false),token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String name=jdbc.queryForObject("SELECT username FROM users WHERE id=?",String.class,admin);
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+admin,input(name,campus,"ROLE_TEACHER"),token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void concurrentChangesPreserveLastAdmin() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8),role="ROLE_M"+suffix;
        jdbc.update("INSERT INTO roles(code,name) VALUES (?,?)",role,"Account Manager");jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES (?,'users.manage')",role);
        String managerName="manager"+suffix;staff(managerName,role,campus);String managerToken=login(managerName,password);
        long second=staff("admin2"+suffix,"ROLE_ADMIN",campus);
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->{start.await();return call(HttpMethod.PUT,"/api/admin/users/"+admin+"/status",Map.of("active",false),managerToken).getStatusCode().value();});
            var two=executor.submit(()->{start.await();return call(HttpMethod.PUT,"/api/admin/users/"+second+"/status",Map.of("active",false),managerToken).getStatusCode().value();});
            start.countDown();assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            long remaining=Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM users WHERE id=?",Boolean.class,admin))?admin:second;
            String remainingName=jdbc.queryForObject("SELECT username FROM users WHERE id=?",String.class,remaining);
            assertThat(call(HttpMethod.PUT,"/api/admin/users/"+remaining,input(remainingName,campus,"ROLE_TEACHER"),managerToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }
}
