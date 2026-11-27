package ph.edu.ici.imports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** ICI2-40: cutover reconciliation across every legacy import source (students, grades, and later sources). */
@RestController
@RequestMapping("/api/legacy-imports/reconciliation")
public class ReconciliationController {
    private final ReconciliationService service;
    public ReconciliationController(ReconciliationService service){this.service=service;}

    public record SignOffBody(@NotNull @PositiveOrZero Integer version,@NotNull @PositiveOrZero Integer expectedImportedCount,@Size(max=1000) String note) {}

    @GetMapping @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> summary(@RequestParam(required=false) @Positive Long campusId){return service.summary(campusId);}

    // The service then requires the permission that matches the batch's source (imports.manage or imports.grades.manage) for its campus.
    @PostMapping("/batches/{id}/sign-off")
    @PreAuthorize("@accessPolicy.hasPermission('imports.manage') or @accessPolicy.hasPermission('imports.grades.manage')")
    public Map<String,Object> signOff(@PathVariable long id,@Valid @RequestBody SignOffBody body){
        return service.signOff(id,body.version(),body.expectedImportedCount(),body.note());
    }
}
