package ph.edu.ici.enrollment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
public class EnrollmentController {
    private final EnrollmentService service;
    public EnrollmentController(EnrollmentService service){this.service=service;}
    public record AttendanceInput(@NotBlank @Pattern(regexp="present|absent|excused|pending") String status,
                                  @NotNull @PositiveOrZero Integer version) {}
    public record VersionInput(@NotNull @PositiveOrZero Integer version) {}
    public record SectionInput(@NotNull @Positive Long campusId,@NotNull @Positive Long termId,
        @NotNull @Positive Long programId,@NotBlank @Size(max=200) String name,
        @NotBlank @Size(max=30) String gradeLevel,@Size(max=200) String room,
        @NotNull @Min(1) @Max(10000) Integer capacity,@Positive Long adviserId,
        @PositiveOrZero Integer version) {}
    public record AssignmentInput(@Positive Long sectionId,@NotNull @PositiveOrZero Integer version) {}

    public record WindowInput(@NotNull LocalDate startsOn,@NotNull LocalDate endsOn,@NotBlank @Size(max=2000) String approvalNote,@NotNull @PositiveOrZero Integer version,LocalDate orientationStartsOn,LocalDate orientationEndsOn) {}
    @GetMapping("/api/enrollment/attendance-window") public Map<String,Object> window(@RequestParam long campusId,@RequestParam long termId){return service.window(campusId,termId);}
    @PutMapping("/api/enrollment/attendance-window") public Map<String,Object> window(@RequestParam long campusId,@RequestParam long termId,@Valid @RequestBody WindowInput input){return service.saveWindow(campusId,termId,input);}
    @GetMapping("/api/enrollment/reference-data") public Map<String,Object> reference(){return service.reference();}
    @GetMapping("/api/enrollment/applications") public Map<String,Object> applications(
        @RequestParam(defaultValue="") String search,@RequestParam(required=false) Long campusId,
        @RequestParam(required=false) Long termId,@RequestParam(required=false) Long programId,
        @RequestParam(defaultValue="") String requirementsStatus,@RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="") String district,@RequestParam(defaultValue="") String transportRoute,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
        return service.applications(search,campusId,termId,programId,requirementsStatus,status,district,transportRoute,page,size);
    }
    @GetMapping("/api/applications/{id}/enrollment") public Map<String,Object> detail(@PathVariable long id){return service.detail(id);}
    @PutMapping("/api/applications/{id}/attendance/{date}") public Map<String,Object> attendance(
        @PathVariable long id,@PathVariable LocalDate date,@Valid @RequestBody AttendanceInput input){return service.attendance(id,date,input);}
    @PostMapping("/api/applications/{id}/confirm-enrollment") public Map<String,Object> confirm(
        @PathVariable long id,@Valid @RequestBody VersionInput input){return service.confirm(id,input.version());}
    @GetMapping("/api/sections") public Map<String,Object> sections(@RequestParam(required=false) Long campusId,
        @RequestParam(required=false) Long termId,@RequestParam(required=false) Long programId,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.sections(campusId,termId,programId,page,size);}
    @GetMapping("/api/sections/{id}") public Map<String,Object> section(@PathVariable long id){return service.section(id);}
    @PostMapping("/api/sections") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createSection(@Valid @RequestBody SectionInput input){return service.createSection(input);}
    @PutMapping("/api/sections/{id}") public Map<String,Object> updateSection(@PathVariable long id,@Valid @RequestBody SectionInput input){return service.updateSection(id,input);}
    @DeleteMapping("/api/sections/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSection(@PathVariable long id,@RequestParam int version){service.deleteSection(id,version);}
    @PutMapping("/api/enrollments/{id}/section") public Map<String,Object> assign(@PathVariable long id,
        @Valid @RequestBody AssignmentInput input){return service.assign(id,input);}
}
