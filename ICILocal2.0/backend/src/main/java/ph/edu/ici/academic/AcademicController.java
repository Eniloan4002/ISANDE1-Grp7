package ph.edu.ici.academic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class AcademicController {
    private final AcademicService service;
    public AcademicController(AcademicService service){this.service=service;}

    public record CreateClassOffering(@NotNull @Positive Long academicYearId,
            @NotNull @Positive Long termId,@NotNull @Positive Long sectionId,
            @NotNull @Positive Long subjectId,@NotNull @Positive Long teacherId) {}
    public record TeacherAssignment(@NotNull @Positive Long academicYearId,
            @NotNull @Positive Long termId,@NotNull @Positive Long teacherId,
            @NotNull @PositiveOrZero Integer version) {}
    public record AdviserAssignment(@NotNull @Positive Long academicYearId,
            @NotNull @Positive Long termId,@Positive Long adviserId,
            @NotNull @PositiveOrZero Integer version) {}
    public record WithdrawalDecision(@NotBlank @Pattern(regexp="cleared|rejected") String decision,
            @Size(max=2000) String notes,@NotNull @PositiveOrZero Integer version) {}

    @GetMapping("/api/academic/assignments")
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
    public Map<String,Object> assignments(@RequestParam long academicYearId,
            @RequestParam long termId,@RequestParam long campusId){
        return service.assignments(academicYearId,termId,campusId);
    }

    @PostMapping("/api/academic/class-offerings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
    public Map<String,Object> createClassOffering(@Valid @RequestBody CreateClassOffering input){
        return service.createClassOffering(input);
    }

    @PutMapping("/api/academic/class-offerings/{id}/teacher")
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
    public Map<String,Object> assignTeacher(@PathVariable long id,@Valid @RequestBody TeacherAssignment input){
        return service.assignTeacher(id,input);
    }

    @PutMapping("/api/academic/sections/{id}/adviser")
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
    public Map<String,Object> assignAdviser(@PathVariable long id,@Valid @RequestBody AdviserAssignment input){
        return service.assignAdviser(id,input);
    }

    @GetMapping("/api/academic/classes")
    @PreAuthorize("@accessPolicy.hasPermission('grades.encode')")
    public Map<String,Object> classes(@RequestParam long academicYearId,@RequestParam long termId){
        return service.teacherClasses(academicYearId,termId);
    }

    @GetMapping("/api/academic/classes/{id}")
    @PreAuthorize("@accessPolicy.hasPermission('grades.encode')")
    public Map<String,Object> classRoster(@PathVariable long id,@RequestParam long academicYearId,
            @RequestParam long termId){
        return service.teacherClassRoster(id,academicYearId,termId);
    }

    @GetMapping("/api/academic/advisory/rosters")
    @PreAuthorize("@accessPolicy.hasPermission('advisory.read')")
    public Map<String,Object> advisoryRosters(@RequestParam long academicYearId,@RequestParam long termId){
        return service.advisoryRosters(academicYearId,termId);
    }

    @GetMapping("/api/academic/withdrawal-endorsements")
    @PreAuthorize("@accessPolicy.hasPermission('advisory.read')")
    public Map<String,Object> withdrawalEndorsements(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size){
        return service.withdrawalEndorsements(page,size);
    }

    @PutMapping("/api/academic/withdrawal-endorsements/{withdrawalId}")
    @PreAuthorize("@accessPolicy.hasPermission('advisory.read')")
    public Map<String,Object> decideWithdrawal(@PathVariable long withdrawalId,
            @Valid @RequestBody WithdrawalDecision input){
        return service.decideWithdrawal(withdrawalId,input);
    }
}
