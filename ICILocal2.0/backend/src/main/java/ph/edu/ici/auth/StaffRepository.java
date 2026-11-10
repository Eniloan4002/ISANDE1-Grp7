package ph.edu.ici.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StaffRepository {
    public record Account(long id,String username,String fullName,String hash,boolean active,int tokenVersion,boolean passwordChangeRequired) {}
    private final JdbcTemplate jdbc;
    public StaffRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Account> find(String username) {
        return jdbc.query("SELECT id,username,full_name,password_hash,active,token_version,password_change_required FROM users WHERE lower(username)=lower(?)",
            (rs,row)->new Account(rs.getLong("id"),rs.getString("username"),rs.getString("full_name"),rs.getString("password_hash"),rs.getBoolean("active"),rs.getInt("token_version"),rs.getBoolean("password_change_required")),username).stream().findFirst();
    }
    public Optional<Account> find(long id) {
        return jdbc.query("SELECT id,username,full_name,password_hash,active,token_version,password_change_required FROM users WHERE id=?",
            (rs,row)->new Account(rs.getLong("id"),rs.getString("username"),rs.getString("full_name"),rs.getString("password_hash"),rs.getBoolean("active"),rs.getInt("token_version"),rs.getBoolean("password_change_required")),id).stream().findFirst();
    }
    public List<StaffIdentity.Grant> grants(long id) {
        return jdbc.query("SELECT g.campus_id,c.code,g.role_code FROM user_role_grants g JOIN campuses c ON c.id=g.campus_id WHERE g.user_id=? AND c.active ORDER BY g.campus_id,g.role_code",
            (rs,row)->new StaffIdentity.Grant(rs.getLong("campus_id"),rs.getString("code"),rs.getString("role_code")),id);
    }
    public record Session(Account account,List<StaffIdentity.Grant> grants,boolean revoked) {}
    /** One database round trip per authenticated request; credentials/grants are never cached across requests. */
    public Optional<Session> session(long id,UUID tokenId){
        return jdbc.query("""
            SELECT u.id,u.username,u.full_name,u.active,u.token_version,u.password_change_required,
              EXISTS(SELECT 1 FROM revoked_tokens r WHERE r.token_id=?) AS revoked,
              g.campus_id,g.code,g.role_code
            FROM users u LEFT JOIN (
              SELECT g.user_id,g.campus_id,c.code,g.role_code FROM user_role_grants g
              JOIN campuses c ON c.id=g.campus_id AND c.active
            ) g ON g.user_id=u.id
            WHERE u.id=? ORDER BY g.campus_id,g.role_code
            """,rs->{
                if(!rs.next())return Optional.<Session>empty();
                var account=new Account(rs.getLong("id"),rs.getString("username"),rs.getString("full_name"),null,rs.getBoolean("active"),rs.getInt("token_version"),rs.getBoolean("password_change_required"));
                boolean revoked=rs.getBoolean("revoked");var grants=new java.util.ArrayList<StaffIdentity.Grant>();
                do{if(rs.getObject("campus_id")!=null)grants.add(new StaffIdentity.Grant(rs.getLong("campus_id"),rs.getString("code"),rs.getString("role_code")));}while(rs.next());
                return Optional.of(new Session(account,List.copyOf(grants),revoked));
            },tokenId,id);
    }
    public boolean revoked(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM revoked_tokens WHERE token_id=?)",Boolean.class,id));
    }
    public void revoke(StaffIdentity identity) {
        jdbc.update("INSERT INTO revoked_tokens(token_id,user_id,expires_at) VALUES (?,?,?) ON CONFLICT(token_id) DO NOTHING",
            identity.tokenId(),identity.userId(),java.sql.Timestamp.from(identity.expiresAt()));
    }
}
