package ph.edu.ici.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StaffRepository {
    public record Account(long id,String username,String fullName,String hash,boolean active,int tokenVersion) {}
    private final JdbcTemplate jdbc;
    public StaffRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Account> find(String username) {
        return jdbc.query("SELECT id,username,full_name,password_hash,active,token_version FROM users WHERE lower(username)=lower(?)",
            (rs,row)->new Account(rs.getLong("id"),rs.getString("username"),rs.getString("full_name"),rs.getString("password_hash"),rs.getBoolean("active"),rs.getInt("token_version")),username).stream().findFirst();
    }
    public Optional<Account> find(long id) {
        return jdbc.query("SELECT id,username,full_name,password_hash,active,token_version FROM users WHERE id=?",
            (rs,row)->new Account(rs.getLong("id"),rs.getString("username"),rs.getString("full_name"),rs.getString("password_hash"),rs.getBoolean("active"),rs.getInt("token_version")),id).stream().findFirst();
    }
    public List<StaffIdentity.Grant> grants(long id) {
        return jdbc.query("SELECT g.campus_id,c.code,g.role_code FROM user_role_grants g JOIN campuses c ON c.id=g.campus_id WHERE g.user_id=? AND c.active ORDER BY g.campus_id,g.role_code",
            (rs,row)->new StaffIdentity.Grant(rs.getLong("campus_id"),rs.getString("code"),rs.getString("role_code")),id);
    }
    public boolean revoked(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM revoked_tokens WHERE token_id=?)",Boolean.class,id));
    }
    public void revoke(StaffIdentity identity) {
        jdbc.update("INSERT INTO revoked_tokens(token_id,user_id,expires_at) VALUES (?,?,?) ON CONFLICT(token_id) DO NOTHING",
            identity.tokenId(),identity.userId(),java.sql.Timestamp.from(identity.expiresAt()));
    }
}
