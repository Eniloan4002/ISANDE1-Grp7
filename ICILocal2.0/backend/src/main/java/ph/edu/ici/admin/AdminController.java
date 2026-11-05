package ph.edu.ici.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ph.edu.ici.auth.StaffIdentity;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('users.manage')")
public class AdminController {
    public record GrantInput(@NotBlank String roleCode,@NotNull @Positive Long campusId) {}
    public record CreateUser(@NotBlank @Size(max=100) String username,@NotBlank @Email @Size(max=320) String email,
        @NotBlank @Size(max=200) String fullName,@NotBlank String password,@NotEmpty List<@Valid GrantInput> grants) {}
    public record UserUpdate(@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(max=200) String fullName,
        @NotNull Boolean active) {}
    public record PasswordUpdate(@NotBlank String password) {}
    public record GrantUpdate(@NotEmpty List<@Valid GrantInput> grants) {}
    public record PermissionUpdate(@NotNull List<@NotBlank String> permissionCodes) {}
    private final AdminService service;
    public AdminController(AdminService service){this.service=service;}

    @GetMapping("/users") public Map<String,Object> users(@AuthenticationPrincipal StaffIdentity identity,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.list(identity,page,size);}
    @GetMapping("/users/{id}") public Map<String,Object> user(@AuthenticationPrincipal StaffIdentity identity,@PathVariable long id){return service.get(identity,id);}
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@AuthenticationPrincipal StaffIdentity identity,@Valid @RequestBody CreateUser input){return service.create(identity,input);}
    @PutMapping("/users/{id}") public Map<String,Object> update(@AuthenticationPrincipal StaffIdentity identity,@PathVariable long id,@Valid @RequestBody UserUpdate input){return service.update(identity,id,input);}
    @PostMapping("/users/{id}/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal StaffIdentity identity,@PathVariable long id,@Valid @RequestBody PasswordUpdate input){service.password(identity,id,input);}
    @PutMapping("/users/{id}/grants") public Map<String,Object> grants(@AuthenticationPrincipal StaffIdentity identity,@PathVariable long id,@Valid @RequestBody GrantUpdate input){return service.grants(identity,id,input);}
    @GetMapping("/roles") public List<Map<String,Object>> roles(){return service.roles();}
    @PutMapping("/roles/{roleCode}/permissions") public Map<String,Object> permissions(@AuthenticationPrincipal StaffIdentity identity,
        @PathVariable String roleCode,@Valid @RequestBody PermissionUpdate input){return service.permissions(identity,roleCode,input);}
}
