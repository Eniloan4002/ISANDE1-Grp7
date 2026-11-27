package ph.edu.ici.imports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.imports.LegacyImportController.MappingBody;

/**
 * Section allocation import (ICI2-39 type A). Reads need imports.read; every write needs imports.enrollment.manage for the batch's campus, and the
 * enrollment service demands enrollment.manage again on each placement. Uploads are .xlsx only, recognised by magic bytes.
 */
@RestController
@RequestMapping("/api/section-allocation-imports")
public class SectionAllocationImportController {
    private final SectionAllocationMappingService mappings;private final SectionAllocationBatchService batches;private final SectionAllocationCommitService commits;
    private final SectionAllocationValidationService validation;private final XlsxGridReader xlsx;
    public SectionAllocationImportController(SectionAllocationMappingService mappings,SectionAllocationBatchService batches,SectionAllocationCommitService commits,
                                             SectionAllocationValidationService validation,XlsxGridReader xlsx){
        this.mappings=mappings;this.batches=batches;this.commits=commits;this.validation=validation;this.xlsx=xlsx;
    }

    public record ReviewBody(@NotNull @PositiveOrZero Integer version,@NotBlank @Pattern(regexp="same_person|distinct|skip|apply") String decision,@NotBlank @Size(max=2000) String reason,@Positive Long studentId) {}
    public record CommitBody(@NotNull @PositiveOrZero Integer version,@NotNull @PositiveOrZero Integer expectedValidCount) {}
    public record ReasonBody(@NotNull @PositiveOrZero Integer version,@NotBlank @Size(max=1000) String reason) {}

    private Map<String,Object> view(ImportMappingService.StoredMapping m){
        Map<String,Object> r=new LinkedHashMap<>();r.put("id",m.id());r.put("name",m.name());r.put("version",m.version());r.put("active",m.active());r.put("isDefault",m.isDefault());r.put("realFileValidated",m.realFileValidated());r.put("mapping",m.mapping());r.put("settings",m.settings());return r;
    }

    // ---- mappings ----
    @GetMapping("/mappings") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> mappings(){return Map.of("items",mappings.list().stream().map(this::view).toList());}
    @GetMapping("/mappings/{id}") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> mapping(@PathVariable long id){return view(mappings.get(id));}
    @PostMapping("/mappings") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("@accessPolicy.hasPermission('imports.mappings.manage')")
    public Map<String,Object> createMapping(@Valid @RequestBody MappingBody body){return view(mappings.create(body.input()));}
    @PutMapping("/mappings/{id}") @PreAuthorize("@accessPolicy.hasPermission('imports.mappings.manage')")
    public Map<String,Object> updateMapping(@PathVariable long id,@Valid @RequestBody MappingBody body){
        if(body.version()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Record version is required for updates.");
        return view(mappings.update(id,body.version(),body.input()));
    }

    // ---- batches ----
    /** Stages an .xlsx section list (dry run). The sheet comes from the mapping's settings.sheet, never the request. */
    @PostMapping(value="/batches",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("@accessPolicy.hasPermission('imports.enrollment.manage')")
    public Map<String,Object> upload(@RequestParam @Positive long campusId,@RequestParam(required=false) @Positive Long mappingId,@RequestParam(required=false) @Positive Long academicYearId,
                                     @RequestParam(required=false) @Positive Long termId,@RequestPart("file") MultipartFile file){
        byte[] bytes=XlsxGridReader.upload(file);
        var staged=validation.stage(campusId,new SectionAllocationValidationService.UploadContext(academicYearId,termId),file.getOriginalFilename(),bytes,xlsx,mappingId);
        return batches.detail(staged.batchId());
    }
    @GetMapping("/batches") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> batches(@RequestParam(required=false) Long campusId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.list(campusId,status,page,size);}
    @GetMapping("/batches/{id}") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> batch(@PathVariable long id){return batches.detail(id);}
    @GetMapping("/batches/{id}/rows") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> rows(@PathVariable long id,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.rows(id,status,page,size);}
    @GetMapping("/batches/{id}/errors") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> errors(@PathVariable long id,@RequestParam(required=false) String severity,@RequestParam(required=false) Integer rowNumber,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.errors(id,severity,rowNumber,page,size);}
    @PostMapping("/batches/{id}/rows/{rowId}/review") @PreAuthorize("@accessPolicy.hasPermission('imports.enrollment.manage')")
    public Map<String,Object> review(@PathVariable long id,@PathVariable long rowId,@Valid @RequestBody ReviewBody body){return batches.review(id,rowId,body.version(),body.decision(),body.reason(),body.studentId());}
    @PostMapping("/batches/{id}/commit") @PreAuthorize("@accessPolicy.hasPermission('imports.enrollment.manage')")
    public Map<String,Object> commit(@PathVariable long id,@Valid @RequestBody CommitBody body){return commits.commit(id,body.version(),body.expectedValidCount());}
    @PostMapping("/batches/{id}/discard") @PreAuthorize("@accessPolicy.hasPermission('imports.enrollment.manage')")
    public Map<String,Object> discard(@PathVariable long id,@Valid @RequestBody ReasonBody body){return batches.discard(id,body.version(),body.reason());}
}
