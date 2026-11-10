package ph.edu.ici.requirements;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
public class RequirementController {
    private final RequirementService service;
    public RequirementController(RequirementService service){this.service=service;}

    @GetMapping("/api/requirement-types")
    public List<Map<String,Object>> types(){return service.types();}

    @GetMapping("/api/applications/{applicationId}/requirements")
    public RequirementService.Checklist checklist(@PathVariable long applicationId){return service.checklist(applicationId);}

    @PutMapping("/api/applications/{applicationId}/requirements/{code}")
    public RequirementService.Checklist update(@PathVariable long applicationId,@PathVariable String code,
            @Valid @RequestBody RequirementUpdate input){return service.update(applicationId,code,input);}

    @GetMapping("/api/applications/{applicationId}/requirements/audit")
    public Map<String,Object> audit(@PathVariable long applicationId,
            @RequestParam(required=false) String code,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size){return service.audit(applicationId,code,page,size);}
}
