package ph.edu.ici.academic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@PreAuthorize("@accessPolicy.hasPermission('grades.review') or @accessPolicy.hasPermission('grades.encode')")
public class GradeResolutionController {
    private final GradeResolutionService service;
    public GradeResolutionController(GradeResolutionService service){this.service=service;}
    public record CorrectionInput(@NotNull @Positive Long gradeId,@NotNull @PositiveOrZero Integer gradeVersion,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal proposedValue,
        @NotBlank @Size(max=2000) String justification) {}
    public record ReviewInput(@NotNull @PositiveOrZero Integer version,@NotNull @PositiveOrZero Integer gradeVersion,
        @NotBlank @Pattern(regexp="approve|reject") String decision,@NotBlank @Size(max=2000) String reason) {}
    public record FollowUpInput(@NotNull @PositiveOrZero Integer version,@NotBlank @Size(max=2000) String note,
        @NotBlank @Pattern(regexp="Email|Phone|In person|System note") String method) {}
    public record CompletionInput(@NotNull @PositiveOrZero Integer version,@NotNull @PositiveOrZero Integer gradeVersion,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal grade,@NotBlank @Size(max=2000) String note) {}

    @GetMapping("/api/academic/grade-corrections")
    public Map<String,Object> corrections(@RequestParam(defaultValue="pending") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return service.corrections(status,page,size);}
    @PostMapping(value="/api/academic/grade-corrections",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@accessPolicy.hasPermission('grades.encode')")
    public Map<String,Object> create(@Valid @RequestPart("request") CorrectionInput input,@RequestPart("evidence") MultipartFile evidence){return service.createCorrection(input,evidence);}
    @GetMapping("/api/academic/grade-corrections/{id}") public Map<String,Object> correction(@PathVariable long id){return service.correction(id);}
    @PostMapping("/api/academic/grade-corrections/{id}/review")
    @PreAuthorize("@accessPolicy.hasPermission('grades.review')")
    public Map<String,Object> review(@PathVariable long id,@Valid @RequestBody ReviewInput input){return service.reviewCorrection(id,input);}
    @GetMapping("/api/academic/grade-corrections/{id}/evidence")
    public ResponseEntity<byte[]> evidence(@PathVariable long id){
        var file=service.evidence(id);
        return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename((String)file.get("filename"),StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType((String)file.get("media_type"))).body((byte[])file.get("content"));
    }
    @GetMapping("/api/academic/incomplete-cases")
    public Map<String,Object> cases(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size,
        @RequestParam(required=false) String search,@RequestParam(required=false) String status,
        @RequestParam(required=false) Long academicYearId,@RequestParam(required=false) Long gradingPeriodId,@RequestParam(required=false) Long sectionId,
        @RequestParam(required=false) Long subjectId,@RequestParam(required=false) Long teacherId){return service.cases(page,size,search,status,academicYearId,gradingPeriodId,sectionId,subjectId,teacherId);}
    @GetMapping("/api/academic/incomplete-cases/{id}") public Map<String,Object> incomplete(@PathVariable long id){return service.incomplete(id);}
    @PostMapping("/api/academic/incomplete-cases/{id}/follow-ups")
    public Map<String,Object> follow(@PathVariable long id,@Valid @RequestBody FollowUpInput input){return service.follow(id,input);}
    @PostMapping("/api/academic/incomplete-cases/{id}/proposals")
    @PreAuthorize("@accessPolicy.hasPermission('grades.encode')")
    public Map<String,Object> propose(@PathVariable long id,@Valid @RequestBody CompletionInput input){return service.propose(id,input);}
    @PostMapping("/api/academic/incomplete-cases/{id}/complete")
    @PreAuthorize("@accessPolicy.hasPermission('grades.review')")
    public Map<String,Object> complete(@PathVariable long id,@Valid @RequestBody CompletionInput input){return service.complete(id,input);}
}
