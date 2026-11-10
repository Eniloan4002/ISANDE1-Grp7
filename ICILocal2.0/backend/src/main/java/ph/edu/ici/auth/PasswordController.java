package ph.edu.ici.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PasswordController {
    private final NamedParameterJdbcTemplate db;private final PasswordEncoder passwords;
    public PasswordController(NamedParameterJdbcTemplate db,PasswordEncoder passwords){this.db=db;this.passwords=passwords;}
    public record Change(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Size(min=12,max=72) String newPassword) {}
    @PostMapping("/api/auth/change-password") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional
    public void change(@AuthenticationPrincipal StaffIdentity identity,@Valid @RequestBody Change input){
        if(input.newPassword().getBytes(StandardCharsets.UTF_8).length>72)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must be at most 72 UTF-8 bytes.");
        var row=db.queryForMap("SELECT password_hash,active FROM users WHERE id=:id FOR UPDATE",Map.of("id",identity.userId()));
        if(!Boolean.TRUE.equals(row.get("active"))||!passwords.matches(input.currentPassword(),row.get("password_hash").toString()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Current password is incorrect.");
        if(passwords.matches(input.newPassword(),row.get("password_hash").toString()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a different new password.");
        db.queryForObject("SELECT set_config('app.actor_id',:actor,true)",Map.of("actor",Long.toString(identity.userId())),String.class);
        db.update("UPDATE users SET password_hash=:hash,password_change_required=false,password_reset_reason=null,token_version=token_version+1 WHERE id=:id",Map.of("hash",passwords.encode(input.newPassword()),"id",identity.userId()));
    }
}
