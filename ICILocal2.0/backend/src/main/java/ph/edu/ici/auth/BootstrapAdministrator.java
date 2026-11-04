package ph.edu.ici.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit one-time bootstrap; never resets an existing account. */
@Component
@ConditionalOnProperty(name="ici.bootstrap.enabled",havingValue="true")
public class BootstrapAdministrator implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String username,password,email;
    public BootstrapAdministrator(JdbcTemplate jdbc,PasswordEncoder encoder,
        @Value("${ici.bootstrap.username}") String username,@Value("${ici.bootstrap.password}") String password,
        @Value("${ici.bootstrap.email}") String email) {
        this.jdbc=jdbc;this.encoder=encoder;this.username=username;this.password=password;this.email=email;
    }
    @Override @Transactional public void run(ApplicationArguments arguments) {
        if(username.isBlank()||username.length()>100||!email.contains("@")||password.length()<12||password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("Bootstrap requires a username, valid email and password of at least 12 characters and at most 72 UTF-8 bytes.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE lower(username)=lower(?))",Boolean.class,username.trim())))return;
        long id=jdbc.queryForObject("INSERT INTO users(username,email,full_name,password_hash) VALUES (?,?,?,?) RETURNING id",Long.class,
            username.trim(),email.trim(),"System Administrator",encoder.encode(password));
        jdbc.queryForObject("SELECT set_config('app.actor_id',?,true)",String.class,Long.toString(id));
        jdbc.update("UPDATE users SET full_name=full_name WHERE id=?",id);
        jdbc.update("INSERT INTO user_role_grants(user_id,role_code,campus_id,granted_by) SELECT ?,'ROLE_ADMIN',id,? FROM campuses WHERE active",id,id);
    }
}
