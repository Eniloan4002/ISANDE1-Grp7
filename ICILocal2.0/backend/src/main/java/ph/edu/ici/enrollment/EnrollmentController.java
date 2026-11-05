package ph.edu.ici.enrollment;

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
@RequestMapping("/api/enrollment")
@PreAuthorize("hasAuthority('enrollment.manage')")
public class EnrollmentController {
    public record ApplicationInput(@NotNull @Positive Long studentId,@NotNull @Positive Long campusId,
        @NotNull @Positive Long termId,@NotNull @Positive Long programId,@NotBlank String category) {}
    public record ApplicationStatusInput(@NotBlank String status) {}
    public record RequirementInput(@NotBlank String status,String storageKey,String originalFilename) {}
    public record DuplicateInput(Long matchedApplicationId,Long matchedStudentId,Map<String,Object> evidence) {}
    public record DuplicateResolution(@NotBlank String status,@NotBlank String reason) {}
    public record SectionInput(@NotNull @Positive Long campusId,@NotNull @Positive Long termId,@NotNull @Positive Long programId,
        @NotBlank String name,@NotBlank String gradeLevel,@NotNull @Positive Integer capacity,String room,Long adviserId) {}
    public record SectionUpdate(@NotNull @Positive Integer capacity,String room,Long adviserId) {}
    public record SectionAssignment(@NotNull @Positive Long sectionId) {}
    private final EnrollmentService service;
    public EnrollmentController(EnrollmentService service){this.service=service;}

    @GetMapping("/applications") public Map<String,Object> applications(@AuthenticationPrincipal StaffIdentity actor,
        @RequestParam(required=false) Long campusId,@RequestParam(required=false) String status,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.applications(actor,campusId,status,page,size);}
    @PostMapping("/applications") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createApplication(@AuthenticationPrincipal StaffIdentity actor,@Valid @RequestBody ApplicationInput input){return service.createApplication(actor,input);}
    @GetMapping("/applications/{id}") public Map<String,Object> application(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id){return service.application(actor,id);}
    @PutMapping("/applications/{id}/status") public Map<String,Object> status(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody ApplicationStatusInput input){return service.status(actor,id,input);}
    @GetMapping("/duplicates") public List<Map<String,Object>> duplicates(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) Long campusId){return service.duplicates(actor,campusId);}
    @PostMapping("/applications/{id}/duplicates") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> duplicate(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody DuplicateInput input){return service.duplicate(actor,id,input);}
    @PutMapping("/duplicates/{id}") public Map<String,Object> resolveDuplicate(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody DuplicateResolution input){return service.resolveDuplicate(actor,id,input);}
    @PutMapping("/applications/{id}/requirements/{code}") public Map<String,Object> requirement(@AuthenticationPrincipal StaffIdentity actor,
        @PathVariable long id,@PathVariable String code,@Valid @RequestBody RequirementInput input){return service.requirement(actor,id,code,input);}
    @PostMapping("/applications/{id}/confirm") public Map<String,Object> confirm(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id){return service.confirm(actor,id);}
    @GetMapping("/sections") public List<Map<String,Object>> sections(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) Long campusId,
        @RequestParam(required=false) Long termId,@RequestParam(required=false) Long programId){return service.sections(actor,campusId,termId,programId);}
    @PostMapping("/sections") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createSection(@AuthenticationPrincipal StaffIdentity actor,@Valid @RequestBody SectionInput input){return service.createSection(actor,input);}
    @PutMapping("/sections/{id}") public Map<String,Object> updateSection(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody SectionUpdate input){return service.updateSection(actor,id,input);}
    @PutMapping("/enrollments/{id}/section") public Map<String,Object> assignSection(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody SectionAssignment input){return service.assignSection(actor,id,input);}
}
