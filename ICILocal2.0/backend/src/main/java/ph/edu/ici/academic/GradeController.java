package ph.edu.ici.academic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("@accessPolicy.hasPermission('grades.encode')")
public class GradeController {
    private final GradeService service;
    public GradeController(GradeService service){this.service=service;}

    public record GradeEntry(@NotNull @Positive Long enrollmentId,
            BigDecimal value,@Size(max=12) String mark,@PositiveOrZero Integer version) {}
    public record DraftGrades(@NotNull @Positive Long academicYearId,@NotNull @Positive Long termId,
            @NotNull @Positive Long gradingPeriodId,
            @NotNull @Size(min=1,max=500) List<@NotNull @Valid GradeEntry> entries) {}
    public record SubmitGrades(@NotNull @Positive Long academicYearId,@NotNull @Positive Long termId,
            @NotNull @Positive Long gradingPeriodId) {}

    @GetMapping("/api/academic/classes/{id}/grading-periods")
    public Map<String,Object> gradingPeriods(@PathVariable long id,@RequestParam long academicYearId,
            @RequestParam long termId){
        return service.gradingPeriods(id,academicYearId,termId);
    }

    @GetMapping("/api/academic/classes/{id}/grades")
    public Map<String,Object> gradebook(@PathVariable long id,@RequestParam long academicYearId,
            @RequestParam long termId,@RequestParam long gradingPeriodId){
        return service.gradebook(id,academicYearId,termId,gradingPeriodId);
    }

    @PutMapping("/api/academic/classes/{id}/grades")
    public Map<String,Object> saveDraft(@PathVariable long id,@Valid @RequestBody DraftGrades input){
        return service.saveDraft(id,input);
    }

    @PostMapping("/api/academic/classes/{id}/grades/submit")
    public Map<String,Object> submit(@PathVariable long id,@Valid @RequestBody SubmitGrades input){
        return service.submit(id,input);
    }
}
