package ph.edu.ici.accounts;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessPolicy;
import ph.edu.ici.auth.StaffIdentity;
import ph.edu.ici.accounts.AccountController.Details;
import ph.edu.ici.accounts.AccountController.Grant;

@Service
public class AccountService {
    private static final Pattern EMAIL=Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final JdbcTemplate jdbc;
    private final AccessPolicy policy;
    private final PasswordEncoder passwords;
    public AccountService(JdbcTemplate jdbc,AccessPolicy policy,PasswordEncoder passwords) { this.jdbc=jdbc;this.policy=policy;this.passwords=passwords; }
    private ResponseStatusException error(HttpStatus status,String detail) { return new ResponseStatusException(status,detail); }
    private long actor() { return ((StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId(); }
    private Set<Long> scope() {
        Set<Long> scope=new HashSet<>(policy.campuses("users.manage"));
        if(scope.isEmpty()) throw error(HttpStatus.FORBIDDEN,"You cannot manage accounts in any active campus.");
        return scope;
    }
    private void lock() {
        // All account changes take the same transaction lock before checking current authorization.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(91372009)",(rs,n)->true);
        scope();
        jdbc.queryForObject("SELECT set_config('app.actor_id',?,true)",String.class,Long.toString(actor()));
    }
    private List<Grant> grants(long id) {
        return jdbc.query("SELECT campus_id,role_code FROM user_role_grants WHERE user_id=? ORDER BY campus_id,role_code",(rs,n)->new Grant(rs.getLong(1),rs.getString(2)),id);
    }
    private Map<String,Object> view(long id,Set<Long> scope) {
        var rows=jdbc.queryForList("SELECT id,username,email,full_name AS \"fullName\",active FROM users WHERE id=?",id);
        if(rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"Account not found.");
        var grants=grants(id);
        if(grants.isEmpty()||grants.stream().anyMatch(g->!scope.contains(g.campusId()))) throw error(HttpStatus.FORBIDDEN,"This account is outside your campus scope.");
        Map<String,Object> result=new LinkedHashMap<>(rows.get(0));result.put("grants",grants);return result;
    }
    @Transactional(readOnly=true) public List<Map<String,Object>> list() {
        var scope=scope();
        List<Map<String,Object>> result=new ArrayList<>();
        for(Long id:jdbc.queryForList("SELECT id FROM users ORDER BY lower(full_name),id",Long.class)) {
            var grants=grants(id);
            if(!grants.isEmpty()&&grants.stream().allMatch(g->scope.contains(g.campusId()))) result.add(view(id,scope));
        }
        return result;
    }
    @Transactional(readOnly=true) public Map<String,Object> get(long id) { return view(id,scope()); }
    @Transactional(readOnly=true) public Map<String,Object> options() {
        var scope=scope();
        return Map.of("roles",jdbc.queryForList("SELECT code,name FROM roles ORDER BY name"),"campuses",jdbc.queryForList("SELECT id,code,name FROM campuses WHERE active ORDER BY name").stream().filter(c->scope.contains(((Number)c.get("id")).longValue())).toList());
    }
    private void validate(Details input,boolean creating,Set<Long> scope) {
        if(input.username()==null||input.username().isBlank()||input.username().trim().length()>100||input.fullName()==null||input.fullName().isBlank()||input.fullName().trim().length()>200||input.email()==null||input.email().trim().length()>254||!EMAIL.matcher(input.email().trim()).matches())
            throw error(HttpStatus.BAD_REQUEST,"Enter a valid username, full name, and email address.");
        if(creating) validatePassword(input.password());
        if(input.grants()==null||input.grants().isEmpty()) throw error(HttpStatus.BAD_REQUEST,"Choose at least one role and campus.");
        Set<Grant> unique=new HashSet<>();
        for(Grant g:input.grants()) {
            if(g==null||g.campusId()==null||g.role()==null||!unique.add(g)) throw error(HttpStatus.BAD_REQUEST,"Each role and campus assignment must be valid and unique.");
            if(!scope.contains(g.campusId())) throw error(HttpStatus.FORBIDDEN,"A selected campus is outside your authorized scope.");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM roles WHERE code=?)",Boolean.class,g.role()))) throw error(HttpStatus.BAD_REQUEST,"Choose a valid role.");
        }
    }
    private void validatePassword(String value) {
        if(value==null||value.length()<12||value.getBytes(StandardCharsets.UTF_8).length>72) throw error(HttpStatus.BAD_REQUEST,"Password must be at least 12 characters and at most 72 UTF-8 bytes.");
    }
    private void guard(long id,List<Grant> old,List<Grant> next,boolean wasActive,boolean willBeActive) {
        for(Grant g:old) if("ROLE_ADMIN".equals(g.role())&&(!willBeActive||!next.contains(g))) {
            if(id==actor()) throw error(HttpStatus.CONFLICT,"You cannot deactivate yourself or remove your own administrator access.");
            if(wasActive) {
                Integer others=jdbc.queryForObject("SELECT count(*) FROM user_role_grants g JOIN users u ON u.id=g.user_id WHERE g.campus_id=? AND g.role_code='ROLE_ADMIN' AND u.active AND u.id<>?",Integer.class,g.campusId(),id);
                if(others==null||others==0) throw error(HttpStatus.CONFLICT,"Each campus must retain an active administrator.");
            }
        }
        if(id==actor()&&!willBeActive) throw error(HttpStatus.CONFLICT,"You cannot deactivate your own account.");
    }
    private void saveGrants(long id,List<Grant> desired) {
        var old=grants(id);
        for(var g:old) if(!desired.contains(g)) jdbc.update("DELETE FROM user_role_grants WHERE user_id=? AND campus_id=? AND role_code=?",id,g.campusId(),g.role());
        for(var g:desired) if(!old.contains(g)) jdbc.update("INSERT INTO user_role_grants(user_id,campus_id,role_code,granted_by) VALUES (?,?,?,?)",id,g.campusId(),g.role(),actor());
    }
    @Transactional public Map<String,Object> create(Details input) {
        lock();var scope=scope();validate(input,true,scope);
        long id=jdbc.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,input.username().trim(),input.email().trim(),input.fullName().trim(),passwords.encode(input.password()));
        saveGrants(id,input.grants());return view(id,scope);
    }
    @Transactional public Map<String,Object> update(long id,Details input) {
        lock();var scope=scope();var old=view(id,scope);validate(input,false,scope);
        boolean active=(Boolean)old.get("active");guard(id,grants(id),input.grants(),active,active);
        jdbc.update("UPDATE users SET username=?,email=?,full_name=? WHERE id=?",input.username().trim(),input.email().trim(),input.fullName().trim(),id);
        saveGrants(id,input.grants());return view(id,scope);
    }
    @Transactional public Map<String,Object> status(long id,Boolean active) {
        if(active==null) throw error(HttpStatus.BAD_REQUEST,"Choose an account status.");
        lock();var scope=scope();var old=view(id,scope);boolean wasActive=(Boolean)old.get("active");
        guard(id,grants(id),grants(id),wasActive,active);
        if(wasActive!=active) jdbc.update("UPDATE users SET active=?, token_version=token_version+? WHERE id=?",active,active?0:1,id);
        return view(id,scope);
    }
    @Transactional public void password(long id,String password) {
        validatePassword(password);lock();view(id,scope());
        jdbc.update("UPDATE users SET password_hash=?,token_version=token_version+1 WHERE id=?",passwords.encode(password),id);
    }
}
