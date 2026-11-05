package ph.edu.ici.admin;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.auth.AccessScope;
import ph.edu.ici.auth.StaffIdentity;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AccessScope access;
    public AdminService(JdbcTemplate jdbc,PasswordEncoder passwords,AccessScope access){this.jdbc=jdbc;this.passwords=passwords;this.access=access;}
    private List<Long> adminCampuses(StaffIdentity actor){
        List<Long> ids=access.campusesForRole(actor,"ROLE_ADMIN");
        if(ids.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator access requires an active campus grant.");
        return ids;
    }
    private void allowedGrant(StaffIdentity actor,AdminController.GrantInput grant,List<Long> campuses){
        if(!campuses.contains(grant.campusId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You cannot assign access at this campus.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM roles WHERE code=?)",Boolean.class,grant.roleCode())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an existing role.");
    }
    private void actor(StaffIdentity identity){access.actor(identity);}
    private void scopeUser(long id,List<Long> campuses){
        if(!Boolean.TRUE.equals(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users u JOIN user_role_grants g ON g.user_id=u.id WHERE u.id=:id AND g.campus_id IN (:campuses))",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("campuses",campuses).addValue("id",id),Boolean.class)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Staff account not found.");
    }
    public Map<String,Object> list(StaffIdentity actor,int page,int size){
        if(page<0||size<1||size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a nonnegative page and a size of 1–100.");
        List<Long> campuses=adminCampuses(actor);var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("campuses",campuses).addValue("limit",size).addValue("offset",(long)page*size);
        var rows=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).queryForList(
            "SELECT DISTINCT u.id,u.username,u.email,u.full_name,u.active FROM users u JOIN user_role_grants g ON g.user_id=u.id " +
            "WHERE g.campus_id IN (:campuses) ORDER BY u.username,u.id LIMIT :limit OFFSET :offset",params);
        long total=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).queryForObject("SELECT count(DISTINCT u.id) FROM users u JOIN user_role_grants g ON g.user_id=u.id WHERE g.campus_id IN (:campuses)",params,Long.class);
        rows.forEach(row->row.put("grants",visibleGrants(((Number)row.get("id")).longValue(),campuses)));
        return Map.of("items",rows,"total",total,"page",page,"size",size);
    }
    public Map<String,Object> get(StaffIdentity actor,long id){
        List<Long> campuses=adminCampuses(actor);scopeUser(id,campuses);
        var row=jdbc.queryForMap("SELECT id,username,email,full_name,active FROM users WHERE id=?",id);
        row.put("grants",visibleGrants(id,campuses));return row;
    }
    private List<Map<String,Object>> visibleGrants(long id,List<Long> campuses){
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).queryForList(
            "SELECT g.role_code,c.id AS campus_id,c.code AS campus_code FROM user_role_grants g JOIN campuses c ON c.id=g.campus_id " +
            "WHERE g.user_id=:id AND c.id IN (:campuses) ORDER BY c.id,g.role_code",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id).addValue("campuses",campuses));
    }
    @Transactional public Map<String,Object> create(StaffIdentity actor,AdminController.CreateUser input){
        List<Long> campuses=adminCampuses(actor);checkPassword(input.password());
        var grants=unique(input.grants());if(grants.isEmpty())throw bad("At least one campus role is required.");
        grants.forEach(grant->allowedGrant(actor,grant,campuses));lockAdmins();actor(actor);
        long id;
        try{id=jdbc.queryForObject("INSERT INTO users(username,email,full_name,password_hash,active) VALUES (?,?,?,?,true) RETURNING id",
            Long.class,input.username().trim(),input.email().trim(),input.fullName().trim(),passwords.encode(input.password()));}
        catch(org.springframework.dao.DuplicateKeyException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"Username or email is already in use.");}
        for(var grant:grants)jdbc.update("INSERT INTO user_role_grants(user_id,role_code,campus_id,granted_by) VALUES (?,?,?,?)",
            id,grant.roleCode(),grant.campusId(),actor.userId());
        requireAdministratorRemains();return get(actor,id);
    }
    @Transactional public Map<String,Object> update(StaffIdentity actor,long id,AdminController.UserUpdate input){
        List<Long> campuses=adminCampuses(actor);scopeUser(id,campuses);lockAdmins();actor(actor);
        try{jdbc.update("UPDATE users SET email=?,full_name=?,active=? WHERE id=?",input.email().trim(),input.fullName().trim(),input.active(),id);}
        catch(org.springframework.dao.DuplicateKeyException exception){throw new ResponseStatusException(HttpStatus.CONFLICT,"Email is already in use.");}
        requireAdministratorRemains();return get(actor,id);
    }
    @Transactional public void password(StaffIdentity actor,long id,AdminController.PasswordUpdate input){
        List<Long> campuses=adminCampuses(actor);scopeUser(id,campuses);checkPassword(input.password());actor(actor);
        jdbc.update("UPDATE users SET password_hash=? WHERE id=?",passwords.encode(input.password()),id);
    }
    @Transactional public Map<String,Object> grants(StaffIdentity actor,long id,AdminController.GrantUpdate input){
        List<Long> campuses=adminCampuses(actor);scopeUser(id,campuses);var grants=unique(input.grants());
        if(grants.isEmpty())throw bad("At least one campus role is required.");grants.forEach(grant->allowedGrant(actor,grant,campuses));
        lockAdmins();actor(actor);
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).update(
            "DELETE FROM user_role_grants WHERE user_id=:id AND campus_id IN (:campuses)",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id).addValue("campuses",campuses));
        for(var grant:grants)jdbc.update("INSERT INTO user_role_grants(user_id,role_code,campus_id,granted_by) VALUES (?,?,?,?)",
            id,grant.roleCode(),grant.campusId(),actor.userId());
        requireAdministratorRemains();return get(actor,id);
    }
    private List<AdminController.GrantInput> unique(List<AdminController.GrantInput> input){
        var result=new LinkedHashMap<String,AdminController.GrantInput>();
        for(var grant:input)result.put(grant.campusId()+":"+grant.roleCode(),grant);
        return List.copyOf(result.values());
    }
    public List<Map<String,Object>> roles(){
        return jdbc.query("SELECT code,name FROM roles ORDER BY code",(rs,row)->{
            String code=rs.getString("code");
            return Map.<String,Object>of("code",code,"name",rs.getString("name"),
                "permissions",jdbc.queryForList("SELECT permission_code FROM role_permissions WHERE role_code=? ORDER BY permission_code",String.class,code));
        });
    }
    @Transactional public Map<String,Object> permissions(StaffIdentity actor,String role,AdminController.PermissionUpdate input){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM roles WHERE code=?)",Boolean.class,role)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Role not found.");
        var codes=new LinkedHashSet<>(input.permissionCodes());
        if(codes.size()!=input.permissionCodes().size())throw bad("Permission codes must be unique.");
        for(String code:codes)if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM permissions WHERE code=?)",Boolean.class,code)))
            throw bad("Select only existing permission codes.");
        if("ROLE_ADMIN".equals(role)&&!codes.contains("users.manage"))throw bad("System administrators must retain users.manage.");
        actor(actor);
        jdbc.update("DELETE FROM role_permissions WHERE role_code=?",role);
        for(String code:codes)jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES (?,?)",role,code);
        return roles().stream().filter(row->role.equals(row.get("code"))).findFirst().orElseThrow();
    }
    private void checkPassword(String value){
        int bytes=value.getBytes(StandardCharsets.UTF_8).length;
        if(value.length()<12||bytes>72)throw bad("Passwords must be at least 12 characters and at most 72 UTF-8 bytes.");
    }
    private void lockAdmins(){
        jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
        jdbc.execute("LOCK TABLE user_role_grants IN SHARE ROW EXCLUSIVE MODE");
    }
    private void requireAdministratorRemains(){
        Long count=jdbc.queryForObject("SELECT count(DISTINCT u.id) FROM users u JOIN user_role_grants g ON g.user_id=u.id " +
            "JOIN campuses c ON c.id=g.campus_id AND c.active WHERE u.active AND g.role_code='ROLE_ADMIN'",Long.class);
        if(count==null||count<1)throw new ResponseStatusException(HttpStatus.CONFLICT,"The last active system administrator cannot be disabled or removed.");
    }
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
