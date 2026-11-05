package ph.edu.ici.reports;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ph.edu.ici.auth.StaffIdentity;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService service;
    public ReportController(ReportService service){this.service=service;}
    @GetMapping("/enrollment") @PreAuthorize("hasAuthority('reports.read')")
    public List<Map<String,Object>> enrollment(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) @Positive Long campusId,@RequestParam(required=false) @Positive Long termId){return service.enrollment(actor,campusId,termId);}
    @GetMapping("/student-records") @PreAuthorize("hasAuthority('reports.read')")
    public List<Map<String,Object>> studentRecords(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) @Positive Long campusId,@RequestParam(required=false) @Positive Long termId){return service.studentRecords(actor,campusId,termId);}
    @GetMapping("/missing-requirements") @PreAuthorize("hasAuthority('reports.read')")
    public List<Map<String,Object>> missingRequirements(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) @Positive Long campusId,@RequestParam(required=false) @Positive Long termId){return service.missingRequirements(actor,campusId,termId);}
    @GetMapping("/incomplete-grades") @PreAuthorize("hasAuthority('reports.read')")
    public List<Map<String,Object>> incompleteGrades(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) @Positive Long campusId,@RequestParam(required=false) @Positive Long termId){return service.incompleteGrades(actor,campusId,termId);}
    @GetMapping("/document-requests") @PreAuthorize("hasAuthority('reports.read')")
    public List<Map<String,Object>> documentRequests(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) @Positive Long campusId,@RequestParam(required=false) @Positive Long termId){return service.documentRequests(actor,campusId,termId);}
    @GetMapping("/at-risk") @PreAuthorize("hasAuthority('guidance.manage')")
    public List<Map<String,Object>> atRisk(@AuthenticationPrincipal StaffIdentity actor,@RequestParam @Positive Long termId,
        @RequestParam(required=false) @Positive Long gradingPeriodId,@RequestParam(required=false) @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal gradeBelow,
        @RequestParam(required=false) @Positive Long campusId){return service.atRisk(actor,termId,gradingPeriodId,gradeBelow,campusId);}
}
