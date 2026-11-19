package ph.edu.ici.academic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("@accessPolicy.hasPermission('grades.review')")
public class GradingWindowController {
    private final GradingWindowService service;
    public GradingWindowController(GradingWindowService service){this.service=service;}

    public record DeadlineInput(@NotNull OffsetDateTime deadline,
            @NotNull @PositiveOrZero Integer version,@NotBlank @Size(max=2000) String reason) {}
    public record ChangeInput(@NotNull @PositiveOrZero Integer version,
            @NotBlank @Size(max=2000) String reason) {}

    @GetMapping("/api/academic/grading-windows")
    public Map<String,Object> list(@RequestParam(required=false) Long campusId,
            @RequestParam(required=false) Long termId,@RequestParam(required=false) Long sectionId,
            @RequestParam(required=false) Long gradingPeriodId,@RequestParam(required=false) String gradeLevel,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
        return service.list(campusId,termId,sectionId,gradingPeriodId,gradeLevel,page,size);
    }

    @GetMapping("/api/academic/sections/{sectionId}/grading-periods/{periodId}/window")
    public Map<String,Object> detail(@PathVariable long sectionId,@PathVariable long periodId){
        return service.detail(sectionId,periodId);
    }

    @PutMapping("/api/academic/sections/{sectionId}/grading-periods/{periodId}/deadline")
    public Map<String,Object> deadline(@PathVariable long sectionId,@PathVariable long periodId,
            @Valid @RequestBody DeadlineInput input){
        return service.deadline(sectionId,periodId,input);
    }

    @PostMapping("/api/academic/sections/{sectionId}/grading-periods/{periodId}/lock")
    public Map<String,Object> lock(@PathVariable long sectionId,@PathVariable long periodId,
            @Valid @RequestBody ChangeInput input){
        return service.change(sectionId,periodId,input,false);
    }

    @PostMapping("/api/academic/sections/{sectionId}/grading-periods/{periodId}/finalize")
    public Map<String,Object> finalizeGrades(@PathVariable long sectionId,@PathVariable long periodId,
            @Valid @RequestBody ChangeInput input){
        return service.change(sectionId,periodId,input,true);
    }
}
