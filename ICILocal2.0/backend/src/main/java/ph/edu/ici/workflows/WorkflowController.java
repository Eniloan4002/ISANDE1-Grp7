package ph.edu.ici.workflows;

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
public class WorkflowController {
    public record WithdrawalInput(@NotNull @Positive Long enrollmentId,@NotBlank @Size(max=2000) String reason) {}
    public record ClearanceInput(@NotBlank String status,@Size(max=2000) String notes) {}
    public record DocumentInput(@NotNull @Positive Long studentId,@NotBlank String documentType) {}
    public record DocumentIssue(@NotBlank @Size(max=1000) String storageKey,String specialOrderNumber) {}
    private final WorkflowService service;
    public WorkflowController(WorkflowService service){this.service=service;}

    @GetMapping("/api/withdrawals") @PreAuthorize("hasAnyAuthority('enrollment.manage','ROLE_PRINCIPAL','cashier.read')")
    public List<Map<String,Object>> withdrawals(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) String status){return service.withdrawals(actor,status);}
    @GetMapping("/api/cashiering/roster") @PreAuthorize("hasAuthority('cashier.read')")
    public List<Map<String,Object>> cashierRoster(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) @jakarta.validation.constraints.Positive Long termId){return service.cashierRoster(actor,termId);}
    @PostMapping("/api/withdrawals") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('enrollment.manage')")
    public Map<String,Object> requestWithdrawal(@AuthenticationPrincipal StaffIdentity actor,@Valid @RequestBody WithdrawalInput input){return service.createWithdrawal(actor,input);}
    @PutMapping("/api/withdrawals/{id}/clearances/{office}") @PreAuthorize("hasAnyAuthority('enrollment.manage','ROLE_PRINCIPAL')")
    public Map<String,Object> clearance(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@PathVariable String office,@Valid @RequestBody ClearanceInput input){return service.clearance(actor,id,office,input);}

    @GetMapping("/api/documents") @PreAuthorize("hasAuthority('enrollment.manage')")
    public List<Map<String,Object>> documents(@AuthenticationPrincipal StaffIdentity actor,@RequestParam(required=false) String status){return service.documents(actor,status);}
    @PostMapping("/api/documents") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('enrollment.manage')")
    public Map<String,Object> requestDocument(@AuthenticationPrincipal StaffIdentity actor,@Valid @RequestBody DocumentInput input){return service.createDocument(actor,input);}
    @PostMapping("/api/documents/{id}/approve") @PreAuthorize("hasAuthority('enrollment.manage')")
    public Map<String,Object> approveDocument(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id){return service.approveDocument(actor,id);}
    @PostMapping("/api/documents/{id}/issue") @PreAuthorize("hasAuthority('enrollment.manage')")
    public Map<String,Object> issueDocument(@AuthenticationPrincipal StaffIdentity actor,@PathVariable long id,@Valid @RequestBody DocumentIssue input){return service.issueDocument(actor,id,input);}
}
