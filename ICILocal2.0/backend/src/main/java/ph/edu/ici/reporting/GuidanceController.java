package ph.edu.ici.reporting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class GuidanceController {
    private final GuidanceService guidance;public GuidanceController(GuidanceService guidance){this.guidance=guidance;}
    public record PolicyInput(@DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal failingBelow,
            @Min(1) @Max(366) Integer consecutiveAbsences,LocalDate credentialsDueOn,
            @NotBlank @Size(max=2000) String reason,@NotNull @PositiveOrZero Integer version){}
    public record CalendarDay(@NotNull LocalDate date,@NotNull Boolean schoolDay,@NotNull @PositiveOrZero Integer version){}
    public record CalendarInput(@NotEmpty @Size(max=366) List<@NotNull @Valid CalendarDay> days,@NotBlank @Size(max=2000) String reason){}
    public record AttendanceEntry(@NotNull @Positive Long enrollmentId,@NotNull LocalDate date,
            @NotBlank @Pattern(regexp="present|absent|excused") String status,@Size(max=2000) String note,@NotNull @PositiveOrZero Integer version){}
    public record AttendanceImport(@NotEmpty @Size(max=500) List<@NotNull @Valid AttendanceEntry> entries){}
    public record CaseInput(@NotNull @Positive Long enrollmentId,@NotBlank @Size(max=2000) String reason){}
    public record CaseChange(@NotBlank @Pattern(regexp="open|monitoring|closed") String status,@NotBlank @Size(max=2000) String reason,@NotNull @PositiveOrZero Integer version){}
    public record NoteInput(@NotBlank @Size(max=2000) String note,@NotNull @PositiveOrZero Integer version){}

    @GetMapping("/guidance/policies/{campusId}/{termId}")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage') or @accessPolicy.hasPermission('guidance.configure')")
    public Map<String,Object> policy(@PathVariable long campusId,@PathVariable long termId){return guidance.policy(campusId,termId);}
    @PutMapping("/guidance/policies/{campusId}/{termId}")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.configure')")
    public Map<String,Object> policy(@PathVariable long campusId,@PathVariable long termId,@Valid @RequestBody PolicyInput input){return guidance.policy(campusId,termId,input);}
    @GetMapping("/academic/school-days/{campusId}/{termId}")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage') or @accessPolicy.hasPermission('guidance.configure') or @accessPolicy.hasPermission('enrollment.manage') or @accessPolicy.hasPermission('grades.encode')")
    public Map<String,Object> calendar(@PathVariable long campusId,@PathVariable long termId){return guidance.calendar(campusId,termId);}
    @PutMapping("/academic/school-days/{campusId}/{termId}")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.configure')")
    public Map<String,Object> calendar(@PathVariable long campusId,@PathVariable long termId,@Valid @RequestBody CalendarInput input){return guidance.calendar(campusId,termId,input);}
    @GetMapping("/academic/daily-attendance/{enrollmentId}")
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage') or @accessPolicy.hasPermission('grades.encode') or @accessPolicy.hasPermission('guidance.manage')")
    public Map<String,Object> attendance(@PathVariable long enrollmentId){return guidance.attendance(enrollmentId);}
    @PostMapping("/academic/daily-attendance/import")
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage') or @accessPolicy.hasPermission('grades.encode')")
    public Map<String,Object> attendance(@Valid @RequestBody AttendanceImport input){return guidance.attendance(input);}
    @GetMapping("/guidance/risks")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage') or @accessPolicy.hasPermission('advisory.read')")
    public Map<String,Object> risks(@ModelAttribute ReportFilter filter,@RequestParam(required=false) LocalDate asOf,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return guidance.risks(filter,asOf,page,size);}
    @PostMapping("/guidance/referrals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@accessPolicy.hasPermission('advisory.read')")
    public Map<String,Object> refer(@Valid @RequestBody CaseInput input){return guidance.refer(input);}
    @PostMapping("/guidance/cases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage')")
    public Map<String,Object> createCase(@Valid @RequestBody CaseInput input){return guidance.createCase(input);}
    @GetMapping("/guidance/cases")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage')")
    public Map<String,Object> cases(@RequestParam(required=false) String status,@RequestParam(required=false) Long studentId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return guidance.cases(status,studentId,page,size);}
    @GetMapping("/guidance/cases/{id}")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage')")
    public Map<String,Object> caseDetail(@PathVariable long id){return guidance.caseDetail(id);}
    @PutMapping("/guidance/cases/{id}")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage')")
    public Map<String,Object> changeCase(@PathVariable long id,@Valid @RequestBody CaseChange input){return guidance.changeCase(id,input);}
    @PostMapping("/guidance/cases/{id}/notes")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage')")
    public Map<String,Object> note(@PathVariable long id,@Valid @RequestBody NoteInput input){return guidance.note(id,input);}
    @GetMapping("/guidance/cases/{id}/export")
    @PreAuthorize("@accessPolicy.hasPermission('guidance.manage')")
    public ResponseEntity<byte[]> export(@PathVariable long id,@RequestParam(defaultValue="pdf") String format){return ReportController.download(guidance.exportCase(id,format),format,"ici-guidance-case-"+id);}
}
