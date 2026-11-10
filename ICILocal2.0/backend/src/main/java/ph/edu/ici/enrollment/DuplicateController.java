package ph.edu.ici.enrollment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
public class DuplicateController {
    private final DuplicateService service;private final IntakeService intake;
    public DuplicateController(DuplicateService service,IntakeService intake){this.service=service;this.intake=intake;}
    public record Decision(@NotBlank @Pattern(regexp="same_person|distinct|reject") String action,@NotNull @PositiveOrZero Integer version,@NotNull @PositiveOrZero Integer applicationVersion,@PositiveOrZero Integer studentVersion,@NotBlank @Size(max=2000) String reason) {}
    public record Link(@NotNull @Positive Long studentId,@NotNull @PositiveOrZero Integer version,@NotNull @PositiveOrZero Integer studentVersion,@NotBlank @Size(max=2000) String reason) {}
    @GetMapping("/api/duplicate-cases") public Map<String,Object> list(@RequestParam(defaultValue="open") String status,@RequestParam(required=false) Long applicationId,@RequestParam(defaultValue="0") int page){return service.list(status,applicationId,page);}
    @GetMapping("/api/duplicate-cases/{id}") public Map<String,Object> detail(@PathVariable long id){return service.detail(id);}
    @PostMapping("/api/duplicate-cases/{id}/resolve") public Map<String,Object> resolve(@PathVariable long id,@Valid @RequestBody Decision input){return service.resolve(id,input.action(),input.version(),input.applicationVersion(),input.studentVersion(),input.reason());}
    @PostMapping("/api/intake/applications/{id}/duplicates/scan") public Map<String,Object> scan(@PathVariable long id){service.scan(id);return intake.detail(id);}
    @PostMapping("/api/intake/applications/{id}/link-student") public Map<String,Object> link(@PathVariable long id,@Valid @RequestBody Link input){service.link(id,input.studentId(),input.version(),input.studentVersion(),input.reason());return intake.detail(id);}
}
