import java.lang.reflect.*;
import java.sql.*;
import java.time.Instant;
import java.time.Duration;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.context.request.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ph.edu.ici.auth.*;
import ph.edu.ici.enrollment.*;
import ph.edu.ici.requirements.*;
import ph.edu.ici.students.*;

/** Audit-only harness. Uses an enforced READ ONLY transaction; no data is written. Optional live GET sampling uses short-lived audit tokens. */
public class ReadOnlyProfile {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final List<Capture> captures=new ArrayList<>();
    static class Capture {
        final String sql;long nanos;int executions;
        final Map<Integer,Setter> setters=new TreeMap<>();
        Capture(String sql){this.sql=sql;}
        Map<String,Object> publicView(){return Map.of("sql",sql.replaceAll("\\s+"," ").trim(),"executions",executions,"milliseconds",round(nanos/1e6));}
    }
    record Setter(Method method,Object[] args){}
    static double round(double v){return Math.round(v*100.0)/100.0;}
    static Object invoke(Method method,Object target,Object[] args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
    static Connection observed(Connection raw){
        return (Connection)Proxy.newProxyInstance(ReadOnlyProfile.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("close"))return null;
            if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql){
                if(!sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT"))throw new SQLException("Audit refused non-SELECT service statement");
                PreparedStatement statement=(PreparedStatement)invoke(method,raw,args);Capture c=new Capture(sql);captures.add(c);
                return Proxy.newProxyInstance(ReadOnlyProfile.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                    if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer index)c.setters.put(index,new Setter(m,a.clone()));
                    if(m.getName().startsWith("execute")){long start=System.nanoTime();try{return invoke(m,statement,a);}finally{c.nanos+=System.nanoTime()-start;c.executions++;}}
                    return invoke(m,statement,a);
                });
            }
            return invoke(method,raw,args);
        });
    }
    static Map<String,Object> step(String name,StaffRepository staff,AccessPolicy policy,long actor,String permission,Supplier<Object> work){
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));captures.clear();long start=System.nanoTime();
        var session=staff.session(actor,UUID.randomUUID()).orElseThrow();var a=session.account();
        if(!a.active()||session.revoked())throw new IllegalStateException("Audit actor is unavailable");
        var identity=new StaffIdentity(a.id(),a.username(),a.fullName(),session.grants(),UUID.randomUUID(),Instant.now().plusSeconds(300),a.passwordChangeRequired());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));
        if(permission!=null&&!policy.hasPermission(permission))throw new IllegalStateException("Audit actor lacks requested permission");
        Object value=work.get();long elapsed=System.nanoTime()-start;
        System.err.println("Profiled "+name+" in "+round(elapsed/1e6)+" ms");
        List<Map<String,Object>> sql=captures.stream().map(Capture::publicView).toList();
        int executions=captures.stream().mapToInt(c->c.executions).sum();
        return Map.of("route",name,"elapsedMs",round(elapsed/1e6),"sqlExecutions",executions,"sqlWireMs",round(captures.stream().mapToLong(c->c.nanos).sum()/1e6),"queries",sql);
    }
    public static void main(String[] args)throws Exception{
        String url=System.getenv("AUDIT_DB_URL"),user=System.getenv("AUDIT_DB_USER"),password=System.getenv("AUDIT_DB_PASSWORD");
        if(url==null||!url.contains("aws-1-ap-southeast-2.pooler.supabase.com:5432/postgres"))throw new IllegalStateException("Unexpected audit database target");
        Map<String,Object> output=new LinkedHashMap<>();
        StaffRepository.Session adminSession=null,registrarSession=null;
        long connected=System.nanoTime();
        try(Connection raw=DriverManager.getConnection(url,user,password)){
            output.put("connectMs",round((System.nanoTime()-connected)/1e6));
            raw.setReadOnly(true);raw.setAutoCommit(false);
            try{
                Connection proxy=observed(raw);
                DataSource ds=new AbstractDataSource(){public Connection getConnection(){return proxy;}public Connection getConnection(String u,String p){return proxy;}};
                JdbcTemplate jdbc=new JdbcTemplate(ds);NamedParameterJdbcTemplate db=new NamedParameterJdbcTemplate(ds);
                List<Double> pings=new ArrayList<>();
                for(int i=0;i<10;i++){long start=System.nanoTime();jdbc.queryForObject("SELECT 1",Integer.class);pings.add(round((System.nanoTime()-start)/1e6));}
                output.put("selectOneWireMs",pings);
                output.put("selectOnePlan",jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT 1",String.class));
                long actor=jdbc.queryForObject("SELECT id FROM users WHERE username='linus_admin' AND active",Long.class);
                long registrar=jdbc.queryForObject("SELECT id FROM users WHERE username='registrar_demo' AND active",Long.class);
                long campus=jdbc.queryForObject("SELECT id FROM campuses WHERE code='ILIGAN'",Long.class);
                long term=Long.parseLong(System.getenv().getOrDefault("AUDIT_TERM_ID","1"));
                long app=Long.parseLong(System.getenv().getOrDefault("AUDIT_APPLICATION_ID","1"));
                var policy=new AccessPolicy(jdbc);var staff=new StaffRepository(jdbc);var students=new StudentService(db,JSON,policy);var requirements=new RequirementService(db,policy);var enrollment=new EnrollmentService(db,requirements,policy);var duplicates=new DuplicateService(db,JSON,policy);var intake=new IntakeService(db,JSON,students,enrollment,duplicates,policy);var accounts=new StaffAccountController(db,new BCryptPasswordEncoder(12),policy);
                adminSession=staff.session(actor,UUID.randomUUID()).orElseThrow();registrarSession=staff.session(registrar,UUID.randomUUID()).orElseThrow();
                List<Map<String,Object>> steps=new ArrayList<>();
                steps.add(step("auth/me (actor/grants only)",staff,policy,registrar,null,()->Map.of("ok",true)));
                steps.add(step("intake/reference-data",staff,policy,registrar,"enrollment.manage",intake::reference));
                steps.add(step("enrollment/reference-data",staff,policy,registrar,"enrollment.manage",enrollment::reference));
                steps.add(step("enrollment/applications",staff,policy,registrar,"enrollment.manage",()->enrollment.applications("",campus,term,null,"","","","",0,25)));
                var queueSql=new ArrayList<>(captures);
                steps.add(step("intake/statistics",staff,policy,registrar,"enrollment.manage",intake::statistics));
                steps.add(step("intake/applications/detail",staff,policy,registrar,"enrollment.manage",()->intake.detail(app)));
                steps.add(step("applications/requirements",staff,policy,registrar,"enrollment.manage",()->requirements.checklist(app)));
                steps.add(step("applications/requirements/audit",staff,policy,registrar,"enrollment.manage",()->requirements.audit(app,null,0,25)));
                steps.add(step("applications/enrollment",staff,policy,registrar,"enrollment.manage",()->enrollment.detail(app)));
                steps.add(step("sections",staff,policy,registrar,"enrollment.manage",()->enrollment.sections(campus,term,null,0,25)));
                steps.add(step("duplicate-cases",staff,policy,registrar,"enrollment.manage",()->duplicates.list("open",null,0)));
                Long duplicate=jdbc.queryForObject("SELECT min(id) FROM duplicate_cases WHERE status='open'",Long.class);
                if(duplicate!=null)steps.add(step("duplicate-cases/detail",staff,policy,registrar,"enrollment.manage",()->duplicates.detail(duplicate)));
                steps.add(step("admin/accounts (admin, up to 25 rows)",staff,policy,actor,"users.manage",()->accounts.list("",0)));
                output.put("steps",steps);
                List<Map<String,Object>> plans=new ArrayList<>();
                for(Capture c:queueSql)if(c.sql.contains("FROM applications a JOIN campuses")){
                    try(PreparedStatement explain=raw.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+c.sql)){
                        for(Setter setter:c.setters.values())setter.method.invoke(explain,setter.args);
                        try(ResultSet rows=explain.executeQuery()){rows.next();plans.add(Map.of("sql",c.sql.replaceAll("\\s+"," ").trim(),"plan",JSON.readTree(rows.getString(1))));}
                    }
                }
                output.put("queuePlans",plans);
                output.put("indexes",jdbc.queryForList("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname"));
                output.put("rowCounts",jdbc.queryForMap("SELECT (SELECT count(*) FROM applications) applications,(SELECT count(*) FROM students) students,(SELECT count(*) FROM users) accounts,(SELECT count(*) FROM audit_logs) audit_events"));
                output.put("methodology","Read-only connection; direct service calls with current DB actor grants and explicit permission check. Request attributes reproduce per-request permission reuse. Includes JDBC wire time, excludes HTTP transport, Spring transaction/pool management, JWT crypto and rendering. Direct-service phase issues no tokens or writes; see liveApiMethod for optional HTTP sampling.");
            }finally{raw.rollback();SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
        }
        if(System.getenv("AUDIT_JWT_SECRET")!=null){
            // Trusted audit harness issues short-lived tokens for existing DB identities only.
            // Tokens are held only in memory and every GET still passes the normal live API authentication checks.
            var tokens=new TokenService(System.getenv("AUDIT_JWT_SECRET"),"ici-local",3);
            String registrarToken=tokens.issue(registrarSession.account().id(),registrarSession.grants(),registrarSession.account().tokenVersion()).accessToken();
            String adminToken=tokens.issue(adminSession.account().id(),adminSession.grants(),adminSession.account().tokenVersion()).accessToken();
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            List<Map<String,Object>> apis=new ArrayList<>();
            for(String path:List.of("/auth/me","/intake/reference-data","/enrollment/applications?termId=1","/intake/applications/1","/applications/1/requirements","/admin/accounts")){
                List<Double> samples=new ArrayList<>();List<Integer> statuses=new ArrayList<>();List<Integer> sizes=new ArrayList<>();
                for(int i=0;i<5;i++){
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081/api"+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+(path.equals("/admin/accounts")?adminToken:registrarToken)).GET().build();
                    long start=System.nanoTime();var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());samples.add(round((System.nanoTime()-start)/1e6));statuses.add(response.statusCode());sizes.add(response.body().length);
                }
                System.err.println("Measured API "+path+" "+samples);
                apis.add(Map.of("path",path,"samplesMs",samples,"statuses",statuses,"bodyBytes",sizes));
            }
            output.put("liveApiSamples",apis);
            output.put("liveApiMethod","Five sequential GETs per endpoint against running backend; existing registrar/admin identities, current grants/version and private local signing key; three-minute audit JWTs never printed/saved. Normal authentication/policy filters remain enabled. No logout/revocation write was performed; tokens expire naturally.");
        }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }
}
