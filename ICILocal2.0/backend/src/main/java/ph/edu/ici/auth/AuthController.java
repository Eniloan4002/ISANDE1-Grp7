package ph.edu.ici.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Login(@NotBlank @Size(max=100) String username,@NotBlank @Size(max=72) String password) {}
    public record Profile(long userId,String username,String fullName,List<StaffIdentity.Grant> grants) {}
    public record LoginResponse(String accessToken,String tokenType,Instant expiresAt,Profile user) {}
    private final StaffRepository staff;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    public AuthController(StaffRepository staff,TokenService tokens,PasswordEncoder passwords) {
        this.staff=staff;this.tokens=tokens;this.passwords=passwords;
        this.dummyHash=passwords.encode("non-account-timing-check");
    }
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody Login input) {
        StaffRepository.Account account=staff.find(input.username().trim()).orElse(null);
        boolean match=false;
        try { match=passwords.matches(input.password(),account==null?dummyHash:account.hash()); }
        catch(IllegalArgumentException ignored) { /* Invalid credentials use the same response. */ }
        if(account==null||!match||!account.active()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid username or password.");
        List<StaffIdentity.Grant> grants=staff.grants(account.id());
        TokenService.Issued token=tokens.issue(account.id(),grants,account.tokenVersion());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new LoginResponse(token.accessToken(),"Bearer",token.expiresAt(),new Profile(account.id(),account.username(),account.fullName(),grants)));
    }
    @GetMapping("/me")
    public Profile me(@AuthenticationPrincipal StaffIdentity identity) { return profile(identity); }
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal StaffIdentity identity) { staff.revoke(identity); }
    static Profile profile(StaffIdentity identity) { return new Profile(identity.userId(),identity.username(),identity.fullName(),identity.grants()); }
}
