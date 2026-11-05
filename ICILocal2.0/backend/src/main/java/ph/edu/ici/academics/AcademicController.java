package ph.edu.ici.academics;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ph.edu.ici.auth.StaffIdentity;

@RestController
@RequestMapping("/api/academics")
public class AcademicController {
    public record ClassInput(@NotNull @Positive Long sectionId,@NotNull @Positive Long subjectId,@NotNull @Positive Long teacherId) {}
    public record GradeInput(@NotNull @Positive Long enrollmentId,@NotNull @Positive Long gradingPeriodId,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal value,String mark,@NotBlank String state) {}
    public record GradeUpdate(@NotNull @PositiveOrZero Long version,@DecimalMin("0.00") @DecimalMax("100.00") BigDecimal value,String mark,@NotBlank String state) {}
    public record CorrectionInput(@NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal proposedValue,
        @NotBlank @Size(max=4000) String justification,@NotBlank @Size(max=1000) String evidenceStorageKey) {}
    public record CorrectionDecision(@NotBlank String status,@Size(max=2000) String reason) {}
    public record IncompleteInput(@NotNull @Positive Long gradeId,@NotBlank @Size(max=2000) String description) {}
    public record IncompleteUpdate(@NotBlank String status) {}
    private final AcademicService service;
    public AcademicController(AcademicService service){this.service=service;}

    @GetMapping("/classes") @PreAuthorize("hasAnyAuthority('grades.encode','grades.review')")
    public List<Map<String,Object>> classes(@AuthenticationPrincipal StaffIdentity actor){return service.classes(actor);}
    @GetMapping("/advisory") @PreAuthorize("hasAuthority('advisory.read')")
    public List<Map<String,Object>> advisory(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) @Positive Long termId){return service.advisory(actor,termId);}
    @GetMapping("/classes/{id}/roster") @PreAuthorize("hasAnyAuthority('grades.encode','grades.review')")
    public List<Map<String,Object>> roster(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id){return service.roster(actor,id);}
    @PostMapping("/classes") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('enrollment.manage')")
    public Map<String,Object> createClass(@AuthenticationPrincipal StaffIdentity actor,@Valid @RequestBody ClassInput input){return service.createClass(actor,input);}
    @PutMapping("/classes/{id}/teacher") @PreAuthorize("hasAuthority('enrollment.manage')")
    public Map<String,Object> assignTeacher(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@RequestParam @Positive long teacherId){return service.assignTeacher(actor,id,teacherId);}
    @GetMapping("/classes/{id}/grades") @PreAuthorize("hasAnyAuthority('grades.encode','grades.review')")
    public List<Map<String,Object>> grades(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@RequestParam @Positive long gradingPeriodId){return service.grades(actor,id,gradingPeriodId);}
    @PostMapping("/classes/{id}/grades") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('grades.encode')")
    public Map<String,Object> addGrade(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody GradeInput input){return service.addGrade(actor,id,input);}
    @PutMapping("/grades/{id}") @PreAuthorize("hasAuthority('grades.encode')")
    public Map<String,Object> updateGrade(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody GradeUpdate input){return service.updateGrade(actor,id,input);}
    @PostMapping("/grades/{id}/corrections") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('grades.encode')")
    public Map<String,Object> correction(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody CorrectionInput input){return service.correction(actor,id,input);}
    @GetMapping("/grade-corrections") @PreAuthorize("hasAnyAuthority('grades.encode','grades.review')")
    public List<Map<String,Object>> corrections(@AuthenticationPrincipal StaffIdentity actor){return service.corrections(actor);}
    @PutMapping("/grade-corrections/{id}") @PreAuthorize("hasAuthority('grades.review')")
    public Map<String,Object> decideCorrection(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody CorrectionDecision input){return service.decideCorrection(actor,id,input);}
    @PostMapping("/classes/{classId}/grading-periods/{periodId}/finalize") @PreAuthorize("hasAuthority('grades.review')")
    public Map<String,Object> finalizeGrades(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long classId,@PathVariable long periodId){return service.finalizeGrades(actor,classId,periodId);}
    @GetMapping("/incomplete-cases") @PreAuthorize("hasAuthority('grades.review')")
    public List<Map<String,Object>> incomplete(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) String status){return service.incomplete(actor,status);}
    @PostMapping("/incomplete-cases") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('grades.review')")
    public Map<String,Object> addIncomplete(@AuthenticationPrincipal StaffIdentity actor,@Valid @RequestBody IncompleteInput input){return service.addIncomplete(actor,input);}
    @PutMapping("/incomplete-cases/{id}") @PreAuthorize("hasAuthority('grades.review')")
    public Map<String,Object> updateIncomplete(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody IncompleteUpdate input){return service.updateIncomplete(actor,id,input);}
}
