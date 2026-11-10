package ph.edu.ici.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** PR #13 account workflows reconciled with versioned recovery and PR #12 permissions. */
@RestController
@RequestMapping({"/api/admin/accounts","/api/admin/users"})
@PreAuthorize("@accessPolicy.hasPermission('users.manage')")
public class StaffAccountController {
    private final NamedParameterJdbcTemplate db;
    private final PasswordEncoder passwords;
    private final AccessPolicy accessPolicy;
    private static final List<String> ROLES=List.of("ROLE_ADMIN","ROLE_REGISTRAR","ROLE_PRINCIPAL","ROLE_ADVISER","ROLE_TEACHER","ROLE_GUIDANCE","ROLE_CASHIER_REF");
    public StaffAccountController(NamedParameterJdbcTemplate db,PasswordEncoder passwords,AccessPolicy accessPolicy){this.db=db;this.passwords=passwords;this.accessPolicy=accessPolicy;}
    public record Create(@NotBlank @Size(max=100) @Pattern(regexp="[A-Za-z0-9._-]+") String username,
        @NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=250) String fullName,
        @NotBlank @Size(min=12,max=72) String password,String role,
        @Size(min=1,max=100) List<@NotNull @Positive Long> campusIds,
        @Size(min=1,max=100) List<@Valid Grant> grants) {}
    public record Status(@NotNull Boolean active,@NotBlank String version) {}
    private StaffIdentity identity(){return (StaffIdentity)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
    private List<Long> scope(){return accessPolicy.campuses("users.manage");}
    private void lock(){db.queryForObject("SELECT pg_advisory_xact_lock(91372009)",Map.of(),(rs,n)->true);accessPolicy.refresh();if(scope().isEmpty())throw error(HttpStatus.FORBIDDEN,"No authorized account-management campus.");}
    private void retainAdministrators(long id,boolean willBeActive,List<Grant> desired){
        var old=db.queryForList("SELECT g.campus_id FROM user_role_grants g JOIN users u ON u.id=g.user_id WHERE g.user_id=:id AND g.role_code='ROLE_ADMIN' AND u.active",Map.of("id",id));
        for(var grant:old){long campus=((Number)grant.get("campus_id")).longValue();
            boolean retained=willBeActive&&desired.stream().anyMatch(g->g.campusId()==campus&&"ROLE_ADMIN".equals(g.role()));
            if(!retained&&!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM user_role_grants g JOIN users u ON u.id=g.user_id WHERE g.campus_id=:campus AND g.role_code='ROLE_ADMIN' AND u.active AND u.id<>:id)",Map.of("campus",campus,"id",id),Boolean.class)))throw error(HttpStatus.CONFLICT,"Each campus must retain an active administrator.");
        }
    }
    private List<Grant> currentGrants(long id){return db.query("SELECT role_code,campus_id FROM user_role_grants WHERE user_id=:id",Map.of("id",id),(rs,n)->new Grant(rs.getString(1),rs.getLong(2)));}
    private void actor(){db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity().userId())),String.class);}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    @GetMapping({"/reference-data","/options"}) public Map<String,Object> reference(){return Map.of("campuses",db.queryForList("SELECT id,code,name FROM campuses WHERE active AND id IN (:scope) ORDER BY name",Map.of("scope",scope())),"roles",db.queryForList("SELECT code,name FROM roles WHERE code IN (:roles) ORDER BY name",Map.of("roles",ROLES)));}
    private static final String VISIBLE="""
        EXISTS(SELECT 1 FROM user_role_grants g WHERE g.user_id=u.id AND g.campus_id IN (:scope))
        AND NOT EXISTS(SELECT 1 FROM user_role_grants g WHERE g.user_id=u.id AND g.campus_id NOT IN (:scope))
        """;
    private Map<String,Object> view(Map<String,Object> row){
        long id=((Number)row.get("id")).longValue();row.put("version",((Timestamp)row.remove("updated_at")).toInstant().toString());
        row.put("grants",db.queryForList("SELECT g.role_code AS role,g.campus_id AS \"campusId\",c.name AS campus FROM user_role_grants g JOIN campuses c ON c.id=g.campus_id WHERE g.user_id=:id ORDER BY c.name,g.role_code",Map.of("id",id)));return row;
    }
    private Map<String,Object> account(long id,boolean lock){
        var rows=db.queryForList("SELECT u.id,u.username,u.email,u.full_name AS \"fullName\",u.active,u.password_change_required AS \"passwordChangeRequired\",u.updated_at FROM users u WHERE u.id=:id AND "+VISIBLE+(lock?" FOR UPDATE":""),Map.of("id",id,"scope",scope()));
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Account not found.");return rows.getFirst();
    }
    @GetMapping @Transactional(readOnly=true)
    public Map<String,Object> list(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page){
        if(search.length()>150||page<0||page>1000000)throw error(HttpStatus.BAD_REQUEST,"Use a valid search and page.");
        Map<String,Object> p=Map.of("scope",scope(),"search",search.trim().toLowerCase(Locale.ROOT),"offset",(long)page*25);
        String where=" WHERE "+VISIBLE+" AND strpos(lower(concat_ws(' ',u.username,u.full_name,u.email)),:search)>0";
        long total=db.queryForObject("SELECT count(*) FROM users u"+where,p,Long.class);
        var rows=db.queryForList("SELECT u.id,u.username,u.email,u.full_name AS \"fullName\",u.active,u.password_change_required AS \"passwordChangeRequired\",u.updated_at FROM users u"+where+" ORDER BY u.username LIMIT 25 OFFSET :offset",p);
        return Map.of("items",rows.stream().map(this::view).toList(),"total",total,"page",page,"size",25);
    }
    private void validateGrants(List<Grant> grants){
        if(grants==null||grants.isEmpty())throw error(HttpStatus.BAD_REQUEST,"Choose at least one role and campus.");
        var scope=scope();var unique=new HashSet<String>();
        for(var grant:grants){
            if(grant==null||!ROLES.contains(grant.role()))throw error(HttpStatus.BAD_REQUEST,"Choose a valid staff role.");
            if(!scope.contains(grant.campusId()))throw error(HttpStatus.FORBIDDEN,"Choose campuses you administer.");
            if(!unique.add(grant.role()+":"+grant.campusId()))throw error(HttpStatus.BAD_REQUEST,"Duplicate role/campus grant.");
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM campuses WHERE id=:id AND active)",Map.of("id",grant.campusId()),Boolean.class)))throw error(HttpStatus.BAD_REQUEST,"Choose active campuses.");
        }
    }
    @GetMapping("/{id}") @Transactional(readOnly=true)
    public Map<String,Object> get(@PathVariable long id){return view(account(id,false));}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Map<String,Object> create(@Valid @RequestBody Create input){
        lock();
        if(input.grants()!=null&&(input.role()!=null||input.campusIds()!=null))throw error(HttpStatus.BAD_REQUEST,"Use grants or role/campusIds, not both.");
        List<Grant> grants=input.grants();
        if(grants==null){if(input.role()==null||input.campusIds()==null||input.campusIds().isEmpty())throw error(HttpStatus.BAD_REQUEST,"Choose a role and campuses.");grants=input.campusIds().stream().distinct().map(c->new Grant(input.role(),c)).toList();}
        validateGrants(grants);
        if(input.password().getBytes(StandardCharsets.UTF_8).length>72)throw error(HttpStatus.BAD_REQUEST,"Password must be at most 72 UTF-8 bytes.");
        actor();Map<String,Object> p=Map.of("username",input.username(),"email",input.email().trim(),"name",input.fullName().trim(),"hash",passwords.encode(input.password()));
        long id=db.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES(:username,:email,:name,:hash) RETURNING id",p,Long.class);
        for(var grant:grants)db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id,granted_by) VALUES(:id,:role,:campus,:actor)",Map.of("id",id,"role",grant.role(),"campus",grant.campusId(),"actor",identity().userId()));
        return view(account(id,false));
    }
    @PutMapping("/{id}/status") @Transactional
    public Map<String,Object> status(@PathVariable long id,@Valid @RequestBody Status input){
        lock();var row=account(id,true);
        if(!((Timestamp)row.get("updated_at")).toInstant().toString().equals(input.version()))throw error(HttpStatus.CONFLICT,"Account changed. Reload before saving.");
        if(id==identity().userId()&&!input.active())throw error(HttpStatus.CONFLICT,"You cannot deactivate your own account.");
        retainAdministrators(id,input.active(),currentGrants(id));
        actor();db.update("UPDATE users SET active=:active,token_version=token_version+1 WHERE id=:id",Map.of("id",id,"active",input.active()));return view(account(id,false));
    }
    public record Edit(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=250) String fullName,
        @NotNull @Size(min=1,max=100) List<@Valid Grant> grants,@NotBlank String version,@NotBlank @Size(max=2000) String reason,
        @Size(min=1,max=100) @Pattern(regexp="[A-Za-z0-9._-]+") String username) {}
    public record Grant(@NotBlank String role,@NotNull @Positive Long campusId) {}
    public record Reset(@NotBlank @Size(min=12,max=72) String password,@NotBlank String version,@NotBlank @Size(max=2000) String reason) {}
    private void expected(Map<String,Object> row,String version){if(!((Timestamp)row.get("updated_at")).toInstant().toString().equals(version))throw error(HttpStatus.CONFLICT,"Account changed. Reload before saving.");}
    @PutMapping("/{id}") @Transactional
    public Map<String,Object> edit(@PathVariable long id,@Valid @RequestBody Edit input){
        lock();var row=account(id,true);expected(row,input.version());
        if(id==identity().userId())throw error(HttpStatus.CONFLICT,"Ask another administrator to change your own role or account details.");
        validateGrants(input.grants());
        retainAdministrators(id,(Boolean)row.get("active"),input.grants());
        actor();db.update("UPDATE users SET username=:username,email=:email,full_name=:name,access_change_reason=:reason,token_version=token_version+1 WHERE id=:id",Map.of("id",id,"username",input.username()==null?row.get("username"):input.username(),"email",input.email().trim(),"name",input.fullName().trim(),"reason",input.reason().trim()));
        db.update("DELETE FROM user_role_grants WHERE user_id=:id",Map.of("id",id));
        for(var grant:input.grants())db.update("INSERT INTO user_role_grants(user_id,role_code,campus_id,granted_by) VALUES(:id,:role,:campus,:actor)",Map.of("id",id,"role",grant.role(),"campus",grant.campusId(),"actor",identity().userId()));
        return view(account(id,false));
    }
    @PostMapping("/{id}/reset-password") @Transactional
    public Map<String,Object> reset(@PathVariable long id,@Valid @RequestBody Reset input){
        lock();var row=account(id,true);expected(row,input.version());
        if(id==identity().userId())throw error(HttpStatus.CONFLICT,"Use Account Security to change your own password.");
        if(input.password().getBytes(StandardCharsets.UTF_8).length>72)throw error(HttpStatus.BAD_REQUEST,"Password must be at most 72 UTF-8 bytes.");
        actor();db.update("UPDATE users SET password_hash=:hash,password_change_required=true,password_reset_reason=:reason,token_version=token_version+1 WHERE id=:id",Map.of("id",id,"hash",passwords.encode(input.password()),"reason",input.reason().trim()));return view(account(id,false));
    }
    @PutMapping("/{id}/password") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void password(@PathVariable long id,@Valid @RequestBody Reset input){reset(id,input);}
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(){return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Username or email is already in use, or a selected role/campus changed. No account was created."));}
}
