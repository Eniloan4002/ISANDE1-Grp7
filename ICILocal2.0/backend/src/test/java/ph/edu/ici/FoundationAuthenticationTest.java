package ph.edu.ici;

import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationAuthenticationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",()->PostgresSupport.DATABASE.getJdbcUrl("postgres","postgres"));
        registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"");
        registry.add("ici.auth.secret",()->PostgresSupport.SECRET);registry.add("ici.bootstrap.enabled",()->false);
    }
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired Flyway flyway;
    @Autowired ph.edu.ici.auth.JwtAuthenticationFilter jwt;
    String username,password="Valid-Test-Password-123!",token;
    long userId;

    @BeforeEach void staff(){
        username="test-"+UUID.randomUUID();
        userId=jdbc.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,
            username,username+"@example.invalid","Test Registrar",passwords.encode(password));
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code,campus_id) SELECT ?,'ROLE_REGISTRAR',id FROM campuses WHERE code='ILIGAN'",userId);
        var response=login(username,password);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        token=(String)response.getBody().get("accessToken");
    }
    ResponseEntity<Map> login(String name,String password){return rest.postForEntity("/api/auth/login",Map.of("username",name,"password",password),Map.class);}
    ResponseEntity<Map> call(HttpMethod method,String path,Object body,String bearer){
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(bearer!=null)headers.setBearerAuth(bearer);
        return rest.exchange(path,method,new HttpEntity<>(body,headers),Map.class);
    }
    HttpStatusCode status(HttpMethod method,String path,Object body,String bearer){
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(bearer!=null)headers.setBearerAuth(bearer);
        return rest.exchange(path,method,new HttpEntity<>(body,headers),String.class).getStatusCode();
    }
    Map create(String path,Map input){var response=call(HttpMethod.POST,"/api/setup/"+path,input,token);assertThat(response.getStatusCode()).describedAs(response.toString()).isEqualTo(HttpStatus.CREATED);return response.getBody();}
    Map year(){return create("academic-years",Map.of("label",UUID.randomUUID().toString().substring(0,20),"startsOn","2026-06-01","endsOn","2027-04-30"));}
    Map term(Map year){return create("terms",Map.of("academicYearId",year.get("id"),"code","S1","name","First semester","startsOn","2026-06-01","endsOn","2026-10-31"));}
    Map subject(){return create("subjects",Map.of("code","SUB-"+UUID.randomUUID().toString().substring(0,8),"name","Mathematics","units",1,"active",true));}

    @Test void flywayRunsAndValidatesAllVersionsOnRealPostgres(){
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT version()",String.class)).contains("PostgreSQL 17");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables t WHERE t.table_schema='public' AND t.table_name NOT IN ('audit_logs','revoked_tokens','flyway_schema_history') AND NOT EXISTS(SELECT 1 FROM information_schema.columns c WHERE c.table_schema=t.table_schema AND c.table_name=t.table_name AND c.column_name='created_by')",Integer.class)).isZero();
    }
    @Test void passwordIsBcryptAndClaimsContainIdentityRolesAndCampus(){
        String hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,userId);
        assertThat(hash).startsWith("$2a$12$").isNotEqualTo(password);assertThat(passwords.matches(password,hash)).isTrue();
        var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(PostgresSupport.SECRET))).build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo(Long.toString(userId));assertThat(claims.get("roles",List.class)).contains("ROLE_REGISTRAR");
        assertThat(claims.get("campusScope",List.class)).hasSize(1);
        assertThat(claims.getExpiration().toInstant()).isAfter(Instant.now());
        var response=call(HttpMethod.GET,"/api/auth/me",null,token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(response.getBody()).containsEntry("username",username);
        assertThat(response.getHeaders().get("Set-Cookie")).isNull();
    }
    @Test void invalidCredentialsAndDisabledAccountsCannotSignIn(){
        assertThat(login(username,"wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login("absent-"+username,password).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE users SET active=false WHERE id=?",userId);
        assertThat(login(username,password).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void logoutRevokesTokenDurablyAndOtherSessionsRemainValid(){
        String second=(String)login(username,password).getBody().get("accessToken");
        assertThat(call(HttpMethod.POST,"/api/auth/logout",null,token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,second).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM revoked_tokens WHERE user_id=?",Integer.class,userId)).isEqualTo(1);
    }
    @Test void malformedExpiredForgedAndWrongIssuerTokensAreRejected(){
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,"not-a-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var key=Keys.hmacShaKeyFor(Decoders.BASE64.decode(PostgresSupport.SECRET));
        String expired=Jwts.builder().issuer("ici-local").subject(Long.toString(userId)).id(UUID.randomUUID().toString()).issuedAt(Date.from(Instant.now().minusSeconds(120))).expiration(Date.from(Instant.now().minusSeconds(60))).signWith(key).compact();
        String otherIssuer=Jwts.builder().issuer("other").subject(Long.toString(userId)).id(UUID.randomUUID().toString()).issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        String forged=Jwts.builder().subject(Long.toString(userId)).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(Jwts.SIG.HS256.key().build()).compact();
        for(String value:List.of(expired,otherIssuer,forged))assertThat(call(HttpMethod.GET,"/api/auth/me",null,value).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void protectedOperationsRejectMissingAuthentication(){
        assertThat(status(HttpMethod.GET,"/api/auth/me",null,null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.POST,"/api/auth/logout",null,null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.GET,"/api/setup/subjects",null,null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.POST,"/api/setup/subjects",Map.of("code","UNAUTH","name","Denied","units",1,"active",true),null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.GET,"/api/students",null,null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(status(HttpMethod.POST,"/api/students",Map.of(),null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void currentRolesAreRecheckedAndSetupRequiresRegistrarOrAdmin(){
        jdbc.update("DELETE FROM user_role_grants WHERE user_id=?",userId);
        assertThat(call(HttpMethod.GET,"/api/setup/subjects",null,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void setupAllowsCurrentRegistrarOrAdminGrantButDeniesOtherRoles(){
        String path="/api/setup/subjects";
        Map<String,Object> input=Map.of("code","RBAC-"+UUID.randomUUID().toString().substring(0,8),"name","Access test","units",1,"active",true);
        var created=call(HttpMethod.POST,path,input,token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String item=path+"/"+created.getBody().get("id");
        assertThat(call(HttpMethod.GET,item,null,token).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE user_role_grants SET role_code='ROLE_TEACHER' WHERE user_id=?",userId);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET,item,null,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,path,input,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE user_role_grants SET role_code='ROLE_ADMIN' WHERE user_id=?",userId);
        assertThat(call(HttpMethod.GET,item,null,token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST,path,Map.of("code","RBAC-"+UUID.randomUUID().toString().substring(0,8),"name","Admin access test","units",1,"active",true),token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
    @ParameterizedTest
    @ValueSource(strings={"ROLE_ADMIN","ROLE_REGISTRAR","ROLE_PRINCIPAL","ROLE_ADVISER","ROLE_TEACHER","ROLE_GUIDANCE","ROLE_CASHIER_REF"})
    void everySeededRoleCanManageOnlyItsOwnSessionAndAuthorizedSetup(String role){
        Map subject=subject();
        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM roles WHERE code=?)",Boolean.class,role)).isTrue();
        jdbc.update("UPDATE user_role_grants SET role_code=? WHERE user_id=?",role,userId);
        String current=(String)login(username,password).getBody().get("accessToken");
        var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(PostgresSupport.SECRET))).build().parseSignedClaims(current).getPayload();
        assertThat(claims.get("roles",List.class)).containsExactly(role);
        var request=new MockHttpServletRequest();request.setServletPath("/api/auth/me");request.addHeader("Authorization","Bearer "+current);
        var response=new MockHttpServletResponse();
        AtomicInteger reached=new AtomicInteger();
        try{
            jwt.doFilter(request,response,(servletRequest,servletResponse)->{
                var authentication=SecurityContextHolder.getContext().getAuthentication();
                assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly(role);
                reached.incrementAndGet();
            });
        }catch(Exception exception){throw new AssertionError("JWT authority conversion failed for "+role,exception);}
        finally{SecurityContextHolder.clearContext();}
        assertThat(reached.get()).isEqualTo(1);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,current).getStatusCode()).isEqualTo(HttpStatus.OK);
        boolean setupRole=List.of("ROLE_ADMIN","ROLE_REGISTRAR").contains(role);
        HttpStatusCode read=setupRole?HttpStatus.OK:HttpStatus.FORBIDDEN;
        for(String resource:List.of("subjects","academic-years","terms","grading-periods"))
            assertThat(status(HttpMethod.GET,"/api/setup/"+resource,null,current)).describedAs(role+" reading "+resource).isEqualTo(read);
        String item="/api/setup/subjects/"+subject.get("id");
        Map<String,Object> input=Map.of("code",subject.get("code"),"name","Role matrix","units",1,"active",true,"version",0);
        assertThat(call(HttpMethod.GET,item,null,current).getStatusCode()).isEqualTo(read);
        assertThat(call(HttpMethod.POST,"/api/setup/subjects",Map.of("code","MATRIX-"+UUID.randomUUID().toString().substring(0,8),"name","Role matrix","units",1,"active",true),current).getStatusCode())
            .isEqualTo(setupRole?HttpStatus.CREATED:HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT,item,input,current).getStatusCode()).isEqualTo(read);
        assertThat(call(HttpMethod.DELETE,item+"?version="+(setupRole?1:0),null,current).getStatusCode())
            .isEqualTo(setupRole?HttpStatus.NO_CONTENT:HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST,"/api/auth/logout",null,current).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET,"/api/auth/me",null,current).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void setupCrudDateValidationAndDuplicateProtection(){
        Map year=year(),term=term(year),subject=subject();
        Map period=create("grading-periods",Map.of("termId",term.get("id"),"code","Q1","sequence",1,"startsOn","2026-06-01","endsOn","2026-08-31"));
        assertThat(period.get("starts_on")).isEqualTo("2026-06-01");
        assertThat(call(HttpMethod.POST,"/api/setup/subjects",Map.of("code",subject.get("code").toString().toLowerCase(),"name","Duplicate","units",1,"active",true),token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.POST,"/api/setup/terms",Map.of("academicYearId",year.get("id"),"code","S2","name","Invalid","startsOn","2027-05-01","endsOn","2027-06-01"),token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,"/api/setup/grading-periods",Map.of("termId",term.get("id"),"code","Q2","sequence",2,"startsOn","2026-09-01","endsOn","2026-11-30"),token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST,"/api/setup/subjects",Map.of("code"," ","name","","units",0,"active",true),token).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.DELETE,"/api/setup/academic-years/"+year.get("id")+"?version=0",null,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.DELETE,"/api/setup/terms/"+term.get("id")+"?version=0",null,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void setupUsesOptimisticVersionsAndAttributedAudits(){
        Map subject=subject();String path="/api/setup/subjects/"+subject.get("id");
        Map input=Map.of("code",subject.get("code"),"name","Updated","units",2,"active",true,"version",0);
        assertThat(call(HttpMethod.PUT,path,input,token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PUT,path,input,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.DELETE,path+"?version=0",null,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map audit=jdbc.queryForMap("SELECT actor_id,old_data->>'name' old_name,new_data->>'name' new_name FROM audit_logs WHERE table_name='subjects' AND operation='UPDATE' AND actor_id=? ORDER BY id DESC LIMIT 1",userId);
        assertThat(((Number)audit.get("actor_id")).longValue()).isEqualTo(userId);assertThat(audit.get("new_name")).isEqualTo("Updated");
        assertThat(jdbc.queryForObject("SELECT created_by FROM subjects WHERE id=?",Long.class,subject.get("id"))).isEqualTo(userId);
        assertThat(call(HttpMethod.DELETE,path+"?version=1",null,token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.GET,path,null,token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void assignmentsProtectSubjectsAndGradingWindowsProtectPeriods(){
        Map year=year(),term=term(year),subject=subject();
        Map period=create("grading-periods",Map.of("termId",term.get("id"),"code","Q1","sequence",1,"startsOn","2026-06-01","endsOn","2026-08-31"));
        long section=jdbc.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) SELECT c.id,?,p.id,'Protection test','11',10 FROM campuses c CROSS JOIN programs p WHERE c.code='ILIGAN' AND p.code='STEM' RETURNING id",Long.class,term.get("id"));
        long offering=jdbc.queryForObject("INSERT INTO class_offerings(section_id,term_id,subject_id,teacher_id) VALUES (?,?,?,?) RETURNING id",Long.class,section,term.get("id"),subject.get("id"),userId);
        jdbc.update("INSERT INTO grading_windows(class_id,grading_period_id) VALUES (?,?)",offering,period.get("id"));
        assertThat(call(HttpMethod.DELETE,"/api/setup/subjects/"+subject.get("id")+"?version=0",null,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(HttpMethod.DELETE,"/api/setup/grading-periods/"+period.get("id")+"?version=0",null,token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(()->jdbc.update("UPDATE academic_years SET ends_on='2026-07-01' WHERE id=?",year.get("id"))).hasMessageContaining("contain all terms");
        assertThatThrownBy(()->jdbc.update("UPDATE academic_terms SET ends_on='2026-07-01' WHERE id=?",term.get("id"))).hasMessageContaining("contain all grading periods");
    }
    @Test void auditIsAppendOnlyAndPasswordsNeverEnterSnapshots(){
        assertThatThrownBy(()->jdbc.update("UPDATE audit_logs SET operation='EDIT'")).hasMessageContaining("append-only");
        assertThatThrownBy(()->jdbc.update("DELETE FROM audit_logs")).hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE table_name='users' AND (old_data ? 'password_hash' OR new_data ? 'password_hash')",Integer.class)).isZero();
    }
    @Test void existingV2DatabaseUpgradesWithoutLosingConfiguration() throws Exception {
        String database="upgrade_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+database);
        String url=PostgresSupport.DATABASE.getJdbcUrl("postgres",database);
        Flyway baseline=Flyway.configure().dataSource(url,"postgres","").target("2").load();baseline.migrate();
        try(var connection=java.sql.DriverManager.getConnection(url,"postgres","");var statement=connection.createStatement()){
            statement.execute("INSERT INTO subjects(code,name) VALUES ('LEGACY','Existing subject')");
            statement.execute("INSERT INTO academic_years(label,starts_on,ends_on) VALUES ('2026-2027','2026-06-01','2027-04-30')");
            statement.execute("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (1,'S1','Existing term','2026-06-01','2026-10-31')");
            statement.execute("INSERT INTO grading_periods(term_id,code,sequence) VALUES (1,'Q1',1)");
        }
        Flyway latest=Flyway.configure().dataSource(url,"postgres","").load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);latest.validate();
        try(var connection=java.sql.DriverManager.getConnection(url,"postgres","");var statement=connection.createStatement()){
            try(var rows=statement.executeQuery("SELECT name,version FROM subjects WHERE code='LEGACY'")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("Existing subject");assertThat(rows.getInt(2)).isZero();}
            try(var rows=statement.executeQuery("SELECT starts_on,ends_on FROM grading_periods WHERE code='Q1'")){assertThat(rows.next()).isTrue();assertThat(rows.getDate(1).toString()).isEqualTo("2026-06-01");assertThat(rows.getDate(2).toString()).isEqualTo("2026-10-31");}
        }
    }
    @Test void concurrentSectionReservationsCannotOverbook() throws Exception {
        long year=((Number)year().get("id")).longValue();
        long term=jdbc.queryForObject("INSERT INTO academic_terms(academic_year_id,code,name,starts_on,ends_on) VALUES (?,'CAP','Capacity test','2026-06-01','2026-10-31') RETURNING id",Long.class,year);
        long campus=jdbc.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);
        long program=jdbc.queryForObject("SELECT id FROM programs WHERE code='STEM'",Long.class);
        long section=jdbc.queryForObject("INSERT INTO sections(campus_id,term_id,program_id,name,grade_level,capacity) VALUES (?,?,?,'Capacity','11',1) RETURNING id",Long.class,campus,term,program);
        List<long[]> candidates=new ArrayList<>();
        for(int i=0;i<2;i++){
            long student=jdbc.queryForObject("INSERT INTO students(student_number,first_name,last_name,birth_date) VALUES (?,'Test','Student','2009-01-01') RETURNING id",Long.class,UUID.randomUUID().toString());
            long app=jdbc.queryForObject("INSERT INTO applications(application_number,campus_id,term_id,program_id,student_id,category,first_name,last_name,birth_date) VALUES (?,?,?,?,?,'new','Test','Student','2009-01-01') RETURNING id",Long.class,UUID.randomUUID().toString(),campus,term,program,student);
            candidates.add(new long[]{student,app});
        }
        CountDownLatch start=new CountDownLatch(1);AtomicInteger successes=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(2)){
            List<Future<?>> results=new ArrayList<>();
            for(var candidate:candidates)results.add(executor.submit(()->{
                try(Connection connection=jdbc.getDataSource().getConnection()){
                    connection.setAutoCommit(false);start.await();
                    try(var statement=connection.prepareStatement("INSERT INTO enrollments(student_id,application_id,campus_id,term_id,program_id,section_id) VALUES (?,?,?,?,?,?)")){
                        long[] args={candidate[0],candidate[1],campus,term,program,section};for(int i=0;i<args.length;i++)statement.setLong(i+1,args[i]);
                        statement.executeUpdate();connection.commit();successes.incrementAndGet();
                    }catch(java.sql.SQLException expected){connection.rollback();if(!expected.getMessage().contains("capacity"))throw new RuntimeException(expected);}
                }catch(Exception exception){throw new RuntimeException(exception);}
            }));
            start.countDown();for(var result:results)result.get(15,TimeUnit.SECONDS);
        }
        assertThat(successes.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE section_id=?",Integer.class,section)).isEqualTo(1);
    }
}
