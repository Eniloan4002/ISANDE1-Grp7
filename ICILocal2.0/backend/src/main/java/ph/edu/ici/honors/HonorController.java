package ph.edu.ici.honors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/honors")
public class HonorController {
    private final HonorService service;
    public HonorController(HonorService service){this.service=service;}
    public record SubjectWeight(@Positive long subjectId,@NotNull @DecimalMin(value="0",inclusive=false) @DecimalMax("1000") @Digits(integer=4,fraction=4) BigDecimal weight){}
    public record Category(@NotBlank @Size(max=100) String name,@NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal minimumAverage){}
    public record Example(@NotBlank @Size(max=200) String name,@NotEmpty @Size(max=100) List<@NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal> values,@NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal expectedAverage,@Size(max=100) String expectedCategory){}
    public record Rules(@NotEmpty @Size(max=100) List<@NotNull @Valid SubjectWeight> subjects,
            @Min(0) @Max(4) int decimalPlaces,@NotBlank @Pattern(regexp="HALF_UP|HALF_EVEN|DOWN") String rounding,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal minimumSubjectGrade,
            @NotEmpty @Size(max=20) List<@NotNull @Valid Category> categories,
            @NotBlank @Pattern(regexp="competition_include_all") String tiePolicy,
            @NotEmpty @Size(max=50) List<@NotNull @Valid Example> examples){}
    public record PolicyInput(@Positive long campusId,@Positive long programId,@NotBlank @Size(max=30) String gradeLevel,@Positive long gradingPeriodId,@NotNull @Valid Rules rules){}
    public record DraftUpdate(@Positive int version,@NotNull @Valid Rules rules){}
    public record Approval(@Positive int version,@NotBlank @Size(max=2000) String reference){}
    @PostMapping("/policies") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@accessPolicy.hasPermission('honors.configure')")
    public Map<String,Object> create(@Valid @RequestBody PolicyInput input){return service.create(input);}
    @GetMapping("/policies")
    @PreAuthorize("@accessPolicy.hasPermission('honors.configure') or @accessPolicy.hasPermission('honors.approve') or @accessPolicy.hasPermission('reports.read')")
    public Map<String,Object> list(@RequestParam(required=false) Long campusId,@RequestParam(required=false) Long programId,@RequestParam(required=false) String gradeLevel,
            @RequestParam(required=false) Long gradingPeriodId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
        return service.list(campusId,programId,gradeLevel,gradingPeriodId,status,page,size);
    }
    @GetMapping("/policies/{id}")
    @PreAuthorize("@accessPolicy.hasPermission('honors.configure') or @accessPolicy.hasPermission('honors.approve') or @accessPolicy.hasPermission('reports.read')")
    public Map<String,Object> get(@PathVariable long id){return service.get(id);}
    @PutMapping("/policies/{id}")
    @PreAuthorize("@accessPolicy.hasPermission('honors.configure')")
    public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody DraftUpdate input){return service.update(id,input);}
    @PostMapping("/policies/{id}/approve")
    @PreAuthorize("@accessPolicy.hasPermission('honors.approve')")
    public Map<String,Object> approve(@PathVariable long id,@Valid @RequestBody Approval input){return service.approve(id,input);}
    @GetMapping("/roll") @PreAuthorize("@accessPolicy.hasPermission('reports.read')")
    public Map<String,Object> roll(@RequestParam long campusId,@RequestParam long programId,@RequestParam String gradeLevel,@RequestParam long gradingPeriodId){return service.roll(campusId,programId,gradeLevel,gradingPeriodId);}
}
