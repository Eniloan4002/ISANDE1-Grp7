package ph.edu.ici;

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
class AdminApiTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;@Autowired JdbcTemplate db;@Autowired PasswordEncoder passwords;
    String username,password,token;long user,campus;
    @BeforeEach void init(){
        campus=db.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);username="admin-"+UUID.randomUUID();password="Admin-password-123!";
        user=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,username,username+"@example.invalid","Test Admin",passwords.encode(password));
        db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id,granted_by) VALUES (?,'ROLE_ADMIN',?,?)",user,campus,user);
        token=login(username,password);
    }
    private String login(String name,String secret){return (String)rest.postForObject("/api/auth/login",Map.of("username",name,"password",secret),Map.class).get("accessToken");}
    private ResponseEntity<Map> call(HttpMethod method,String path,Object body,String bearer){HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)headers.setBearerAuth(bearer);return rest.exchange(path,method,new HttpEntity<>(body,headers),Map.class);}
    private ResponseEntity<List> callList(HttpMethod method,String path,Object body,String bearer){HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(bearer!=null)headers.setBearerAuth(bearer);return rest.exchange(path,method,new HttpEntity<>(body,headers),List.class);}
    @Test void administratorCanCreateScopedAccountsWithoutExposingPasswordHashes(){
        assertThat(call(HttpMethod.GET,"/api/admin/users",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders roleHeaders=new HttpHeaders();roleHeaders.setBearerAuth(token);
        assertThat(rest.exchange("/api/admin/roles",HttpMethod.GET,new HttpEntity<>(roleHeaders),String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        long otherCampus=db.queryForObject("SELECT id FROM campuses WHERE code='KAPATAGAN'",Long.class);String outside="outside-"+UUID.randomUUID();
        var denied=call(HttpMethod.POST,"/api/admin/users",Map.of("username",outside,"email",outside+"@example.invalid","fullName","Outside Scope","password","Teacher-password-123!","grants",List.of(Map.of("roleCode","ROLE_TEACHER","campusId",otherCampus))),token);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(db.queryForObject("SELECT count(*) FROM users WHERE username=?",Integer.class,outside)).isZero();
        String staff="teacher-"+UUID.randomUUID();Map<String,Object> grant=Map.of("roleCode","ROLE_TEACHER","campusId",campus);
        var response=call(HttpMethod.POST,"/api/admin/users",Map.of("username",staff,"email",staff+"@example.invalid","fullName","Test Teacher","password","Teacher-password-123!","grants",List.of(grant)),token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);assertThat(response.getBody()).doesNotContainKey("password_hash");
        assertThat((List)response.getBody().get("grants")).hasSize(1);
        String createdToken=login(staff,"Teacher-password-123!");assertThat(createdToken).isNotBlank();
        assertThat(call(HttpMethod.GET,"/api/admin/users",null,createdToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/admin/users",null,token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void rolePermissionChangesApplyWithoutIssuingAnotherToken(){
        String staff="guidance-"+UUID.randomUUID();var create=call(HttpMethod.POST,"/api/admin/users",Map.of(
            "username",staff,"email",staff+"@example.invalid","fullName","Test Guidance","password","Guidance-password-123!",
            "grants",List.of(Map.of("roleCode","ROLE_GUIDANCE","campusId",campus))),token);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);String staffToken=login(staff,"Guidance-password-123!");
        assertThat(call(HttpMethod.GET,"/api/reports/enrollment",null,staffToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        List<String> original=db.queryForList("SELECT permission_code FROM role_permissions WHERE role_code='ROLE_GUIDANCE' ORDER BY permission_code",String.class);
        var expanded=new ArrayList<>(original);expanded.add("reports.read");
        try{
            assertThat(call(HttpMethod.PUT,"/api/admin/roles/ROLE_GUIDANCE/permissions",Map.of("permissionCodes",expanded),token).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(callList(HttpMethod.GET,"/api/reports/enrollment",null,staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(db.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='reports' AND record_id='enrollment' AND operation='GENERATE' AND actor_id=?",Integer.class,db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,staff))).isEqualTo(1);
        }finally{
            assertThat(call(HttpMethod.PUT,"/api/admin/roles/ROLE_GUIDANCE/permissions",Map.of("permissionCodes",original),token).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
    @Test void finalActiveAdministratorCannotBeDisabled(){
        List<Long> other=db.queryForList("SELECT DISTINCT u.id FROM users u JOIN user_role_grants g ON g.user_id=u.id WHERE u.active AND g.role_code='ROLE_ADMIN' AND u.id<>?",Long.class,user);
        for(Long id:other)db.update("UPDATE users SET active=false WHERE id=?",id);
        try{
            var response=call(HttpMethod.PUT,"/api/admin/users/"+user,Map.of("email",username+"@example.invalid","fullName","Test Admin","active",false),token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(jdbcActive()).isTrue();
        }finally{for(Long id:other)db.update("UPDATE users SET active=true WHERE id=?",id);}
    }
    private boolean jdbcActive(){return Boolean.TRUE.equals(db.queryForObject("SELECT active FROM users WHERE id=?",Boolean.class,user));}
}
