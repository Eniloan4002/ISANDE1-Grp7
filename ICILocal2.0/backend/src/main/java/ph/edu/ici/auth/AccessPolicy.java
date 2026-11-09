package ph.edu.ici.auth;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Permission and campus must come from the same current role grant. */
@Component("accessPolicy")
public class AccessPolicy {
    private final JdbcTemplate jdbc;
    public AccessPolicy(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Long> campuses(String permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffIdentity identity)) return List.of();
        return jdbc.queryForList("SELECT DISTINCT g.campus_id FROM user_role_grants g " +
            "JOIN users u ON u.id=g.user_id AND u.active " +
            "JOIN campuses c ON c.id=g.campus_id AND c.active " +
            "JOIN role_permissions rp ON rp.role_code=g.role_code " +
            "WHERE g.user_id=? AND rp.permission_code=? ORDER BY g.campus_id",
            Long.class, identity.userId(), permission);
    }

    public boolean hasPermission(String permission) { return !campuses(permission).isEmpty(); }
}
