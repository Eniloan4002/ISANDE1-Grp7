package ph.edu.ici.accounts;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("@accessPolicy.hasPermission('users.manage')")
public class AccountController {
    public record Grant(Long campusId, String role) {}
    public record Details(String username, String email, String fullName, String password, List<Grant> grants) {}
    public record Status(Boolean active) {}
    public record Password(String password) {}
    private final AccountService service;
    public AccountController(AccountService service) { this.service=service; }
    @GetMapping public List<Map<String,Object>> list() { return service.list(); }
    @GetMapping("/options") public Map<String,Object> options() { return service.options(); }
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable long id) { return service.get(id); }
    @PostMapping public ResponseEntity<Map<String,Object>> create(@RequestBody Details input) {
        var created=service.create(input);
        return ResponseEntity.created(URI.create("/api/admin/users/"+created.get("id"))).body(created);
    }
    @PutMapping("/{id}") public Map<String,Object> update(@PathVariable long id,@RequestBody Details input) { return service.update(id,input); }
    @PutMapping("/{id}/status") public Map<String,Object> status(@PathVariable long id,@RequestBody Status input) { return service.status(id,input.active()); }
    @PutMapping("/{id}/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@PathVariable long id,@RequestBody Password input) { service.password(id,input.password()); }
}
