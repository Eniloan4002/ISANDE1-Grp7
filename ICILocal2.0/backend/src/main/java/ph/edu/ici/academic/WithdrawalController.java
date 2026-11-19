package ph.edu.ici.academic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/academic/withdrawals")
public class WithdrawalController {
    private final WithdrawalService service;
    public WithdrawalController(WithdrawalService service){this.service=service;}
    public record CreateInput(@NotNull @Positive Long enrollmentId,@NotNull @PositiveOrZero Integer enrollmentVersion,
            @NotBlank @Size(max=2000) String reason) {}
    public record DecisionInput(@NotNull @PositiveOrZero Integer version,
            @NotNull @PositiveOrZero Integer withdrawalVersion,
            @NotBlank @Pattern(regexp="cleared|rejected") String decision,@NotBlank @Size(max=2000) String notes) {}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage')")
    public Map<String,Object> create(@Valid @RequestBody CreateInput input){return service.create(input);}

    @GetMapping
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage') or @accessPolicy.hasPermission('advisory.read') or @accessPolicy.hasPermission('withdrawals.approve') or @accessPolicy.hasPermission('withdrawals.clear')")
    public Map<String,Object> list(@RequestParam(required=false) Long campusId,@RequestParam(required=false) Long termId,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
        return service.list(campusId,termId,status,page,size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage') or @accessPolicy.hasPermission('advisory.read') or @accessPolicy.hasPermission('withdrawals.approve') or @accessPolicy.hasPermission('withdrawals.clear')")
    public Map<String,Object> detail(@PathVariable long id){return service.detail(id);}

    @GetMapping("/{id}/release-eligibility")
    @PreAuthorize("@accessPolicy.hasPermission('enrollment.manage') or @accessPolicy.hasPermission('withdrawals.approve') or @accessPolicy.hasPermission('withdrawals.clear')")
    public Map<String,Object> release(@PathVariable long id){return service.release(id);}

    @PutMapping("/{id}/clearances/{office}")
    @PreAuthorize("@accessPolicy.hasPermission('advisory.read') or @accessPolicy.hasPermission('withdrawals.approve') or @accessPolicy.hasPermission('withdrawals.clear')")
    public Map<String,Object> decide(@PathVariable long id,@PathVariable String office,@Valid @RequestBody DecisionInput input){
        return service.decide(id,office,input);
    }
}
