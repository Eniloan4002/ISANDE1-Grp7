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
class ReconciledAccessTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");r.add("ici.auth.secret",()->PostgresSupport.SECRET);r.add("ici.bootstrap.enabled",()->false);}
    @Autowired JdbcTemplate db;@Autowired TestRestTemplate rest;@Autowired PasswordEncoder encoder;
    String password="Private-Reconciliation-2026",adminToken;long campus,admin;
    String suffix(){return UUID.randomUUID().toString().substring(0,8);}
    long staff(String role){String name="reconcile-"+suffix();long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES(?,?,?,?) RETURNING id",Long.class,name,name+"@example.invalid",name,encoder.encode(password));db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES(?,?,?)",id,role,campus);return id;}
    String login(long id){String name=db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id);return (String)rest.postForEntity("/api/auth/login",Map.of("username",name,"password",password),Map.class).getBody().get("accessToken");}
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String token){var h=new HttpHeaders();h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);return rest.exchange(path,method,new HttpEntity<>(body,h),Map.class);}
    Map account(long id){return call(HttpMethod.GET,"/api/admin/users/"+id,null,adminToken).getBody();}
    @BeforeEach void init(){String code="R"+suffix();campus=db.queryForObject("INSERT INTO campuses(code,name) VALUES(?,?) RETURNING id",Long.class,code,"Reconciliation test campus");admin=staff("ROLE_ADMIN");adminToken=login(admin);}
    @Test void upstreamAliasSupportsMultiGrantCreationAndRejectsStaleEdits(){
        String name="multi-"+suffix();var grants=List.of(Map.of("role","ROLE_TEACHER","campusId",campus),Map.of("role","ROLE_ADVISER","campusId",campus));
        var created=call(HttpMethod.POST,"/api/admin/users",Map.of("username",name,"fullName","Original","email",name+"@example.invalid","password",password,"grants",grants),adminToken);assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id=((Number)created.getBody().get("id")).longValue();var edit=new HashMap<String,Object>(Map.of("fullName","Latest","email",name+"@example.invalid","grants",grants,"version",created.getBody().get("version"),"reason","Verified access change"));
        edit.put("username",name+"-updated");
        assertThat(call(HttpMethod.PUT,"/api/admin/users/"+id,edit,adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        edit.put("fullName","Stale");assertThat(call(HttpMethod.PUT,"/api/admin/accounts/"+id,edit,adminToken).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(account(id).get("fullName")).isEqualTo("Latest");assertThat(account(id).get("username")).isEqualTo(name+"-updated");
        assertThat(rest.postForEntity("/api/auth/login",Map.of("username",name,"password",password),Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);assertThat(login(id)).isNotBlank();
    }
    @Test void concurrentDeactivationRetainsLastCampusAdministrator()throws Exception{
        long second=staff("ROLE_ADMIN");var one=account(admin);var two=account(second);String role="ROLE_M"+suffix();
        db.update("INSERT INTO roles(code,name) VALUES(?,?)",role,"Scoped account manager");db.update("INSERT INTO role_permissions(role_code,permission_code) VALUES(?,'users.manage')",role);String manager=login(staff(role));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{start.await();return call(HttpMethod.PUT,"/api/admin/users/"+admin+"/status",Map.of("active",false,"version",one.get("version")),manager).getStatusCode().value();});
            var b=pool.submit(()->{start.await();return call(HttpMethod.PUT,"/api/admin/accounts/"+second+"/status",Map.of("active",false,"version",two.get("version")),manager).getStatusCode().value();});
            start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
    @Test void permissionsAreLiveAndAnUnrelatedCampusGrantCannotExpandEnrollmentScope(){
        String role="ROLE_E"+suffix();db.update("INSERT INTO roles(code,name) VALUES(?,?)",role,"Enrollment manager");db.update("INSERT INTO role_permissions(role_code,permission_code) VALUES(?,'enrollment.manage')",role);
        long user=staff(role);long other=db.queryForObject("SELECT id FROM campuses WHERE code='BUGO'",Long.class);db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) VALUES(?,'ROLE_TEACHER',?)",user,other);String token=login(user);
        var ref=call(HttpMethod.GET,"/api/intake/reference-data",null,token);assertThat(ref.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat((List)ref.getBody().get("campuses")).hasSize(1);
        var outside=call(HttpMethod.GET,"/api/enrollment/applications?campusId="+other,null,token);assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat((List)outside.getBody().get("items")).isEmpty();assertThat(((Number)outside.getBody().get("total")).intValue()).isZero();
        db.update("DELETE FROM role_permissions WHERE role_code=?",role);
        for(String path:List.of("/api/intake/reference-data","/api/enrollment/reference-data","/api/duplicate-cases","/api/students","/api/setup/subjects"))assertThat(call(HttpMethod.GET,path,null,token).getStatusCode()).describedAs(path).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
