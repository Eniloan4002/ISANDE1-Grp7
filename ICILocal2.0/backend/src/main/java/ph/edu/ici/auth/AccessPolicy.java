package ph.edu.ici.auth;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Permission and campus must come from the same current role grant. */
@Component("accessPolicy")
public class AccessPolicy {
    private final JdbcTemplate jdbc;
    public AccessPolicy(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String CACHE=AccessPolicy.class.getName()+".requestScopes";
    public void refresh(){var attributes=RequestContextHolder.getRequestAttributes();if(attributes instanceof ServletRequestAttributes request)request.getRequest().removeAttribute(CACHE);}
    @SuppressWarnings("unchecked")
    public List<Long> campuses(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffIdentity identity)) return List.of();
        Map<String,List<Long>> scopes=null;
        var attributes=RequestContextHolder.getRequestAttributes();
        if(attributes instanceof ServletRequestAttributes request){
            scopes=(Map<String,List<Long>>)request.getRequest().getAttribute(CACHE);
            if(scopes==null){scopes=new HashMap<>();request.getRequest().setAttribute(CACHE,scopes);}
        }
        String key=identity.userId()+":"+permission;
        if(scopes!=null&&scopes.containsKey(key))return scopes.get(key);
        var result=List.copyOf(jdbc.queryForList("SELECT DISTINCT g.campus_id FROM user_role_grants g " +
            "JOIN users u ON u.id=g.user_id AND u.active " +
            "JOIN campuses c ON c.id=g.campus_id AND c.active " +
            "JOIN role_permissions rp ON rp.role_code=g.role_code " +
            "WHERE g.user_id=? AND rp.permission_code=? ORDER BY g.campus_id",
            Long.class, identity.userId(), permission));
        if(scopes!=null)scopes.put(key,result);
        return result;
    }

    public boolean hasPermission(String permission) { return !campuses(permission).isEmpty(); }
}
