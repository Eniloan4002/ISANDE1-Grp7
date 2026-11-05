package ph.edu.ici.auth;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Shared campus and audit helpers. Role checks alone never grant access to a resource. */
@Component
public class AccessScope {
    private final JdbcTemplate jdbc;
    public AccessScope(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public List<Long> campuses(StaffIdentity identity,String permission) {
        return jdbc.queryForList("SELECT DISTINCT g.campus_id FROM user_role_grants g " +
            "JOIN campuses c ON c.id=g.campus_id AND c.active " +
            "JOIN role_permissions rp ON rp.role_code=g.role_code " +
            "WHERE g.user_id=? AND rp.permission_code=? ORDER BY g.campus_id",Long.class,identity.userId(),permission);
    }

    public List<Long> campusesForRole(StaffIdentity identity,String role) {
        return jdbc.queryForList("SELECT DISTINCT g.campus_id FROM user_role_grants g " +
            "JOIN campuses c ON c.id=g.campus_id AND c.active " +
            "WHERE g.user_id=? AND g.role_code=? ORDER BY g.campus_id",Long.class,identity.userId(),role);
    }

    public void requireCampus(StaffIdentity identity,String permission,long campusId) {
        if(!campuses(identity,permission).contains(campusId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot access this campus.");
    }

    public void requireRoleCampus(StaffIdentity identity,String role,long campusId) {
        if(!campusesForRole(identity,role).contains(campusId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Your account cannot access this campus.");
    }

    public void actor(StaffIdentity identity) {
        jdbc.queryForObject("SELECT set_config('app.actor_id',?,true)",String.class,Long.toString(identity.userId()));
    }

    public boolean hasRole(StaffIdentity identity,String role) {
        return identity.grants().stream().anyMatch(grant->role.equals(grant.role()));
    }
}
