package ph.edu.ici.imports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.ici.imports.GradeMatrixValidationService.UploadContext;
import ph.edu.ici.imports.LegacyImportController.MappingBody;

/** Legacy historical grade matrix import (ICI2-38). Reads need imports.read; every write needs imports.grades.manage for the batch's campus. */
@RestController
@RequestMapping("/api/legacy-grade-imports")
public class GradeMatrixImportController {
    private final GradeMatrixMappingService mappings;private final GradeMatrixValidationService validation;private final GradeMatrixBatchService batches;private final GradeMatrixCommitService commits;
    public GradeMatrixImportController(GradeMatrixMappingService mappings,GradeMatrixValidationService validation,GradeMatrixBatchService batches,GradeMatrixCommitService commits){this.mappings=mappings;this.validation=validation;this.batches=batches;this.commits=commits;}

    public record ReviewBody(@NotNull @PositiveOrZero Integer version,@NotBlank @Pattern(regexp="same_person|distinct|skip") String decision,@NotBlank @Size(max=2000) String reason,@Positive Long studentId) {}
    public record CommitBody(@NotNull @PositiveOrZero Integer version,@NotNull @PositiveOrZero Integer expectedValidCount) {}
    public record ReasonBody(@NotNull @PositiveOrZero Integer version,@NotBlank @Size(max=2000) String reason) {}

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
    @PostMapping(value="/batches",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("@accessPolicy.hasPermission('imports.grades.manage')")
    public Map<String,Object> upload(@RequestParam @Positive long campusId,@RequestParam(required=false) @Positive Long mappingId,@RequestParam(required=false) @Positive Long academicYearId,
                                     @RequestParam(required=false) @Positive Long termId,@RequestParam(required=false) String gradeLevel,@RequestParam(required=false) String section,@RequestPart("file") MultipartFile file){
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("");String type=Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        if(!name.toLowerCase(Locale.ROOT).endsWith(".csv")&&!type.startsWith("text/csv"))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Upload a .csv file.");
        byte[] bytes;try{bytes=file.getBytes();}catch(IOException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The file could not be read.");}
        return batches.detail(validation.stageGradeMatrix(campusId,new UploadContext(academicYearId,termId,gradeLevel,section),name,bytes,mappingId).batchId());
    }
    @GetMapping("/batches") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> batches(@RequestParam(required=false) Long campusId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.list(campusId,status,page,size);}
    @GetMapping("/batches/{id}") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> batch(@PathVariable long id){return batches.detail(id);}
    @GetMapping("/batches/{id}/rows") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> rows(@PathVariable long id,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.rows(id,status,page,size);}
    @GetMapping("/batches/{id}/errors") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> errors(@PathVariable long id,@RequestParam(required=false) String severity,@RequestParam(required=false) Integer rowNumber,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.errors(id,severity,rowNumber,page,size);}
    @PostMapping("/batches/{id}/rows/{rowId}/review") @PreAuthorize("@accessPolicy.hasPermission('imports.grades.manage')")
    public Map<String,Object> review(@PathVariable long id,@PathVariable long rowId,@Valid @RequestBody ReviewBody body){return batches.review(id,rowId,body.version(),body.decision(),body.reason(),body.studentId());}
    @PostMapping("/batches/{id}/commit") @PreAuthorize("@accessPolicy.hasPermission('imports.grades.manage')")
    public Map<String,Object> commit(@PathVariable long id,@Valid @RequestBody CommitBody body){return commits.commit(id,body.version(),body.expectedValidCount());}
    @PostMapping("/batches/{id}/void") @PreAuthorize("@accessPolicy.hasPermission('imports.grades.manage')")
    public Map<String,Object> voidBatch(@PathVariable long id,@Valid @RequestBody ReasonBody body){return batches.voidBatch(id,body.version(),body.reason());}
    @PostMapping("/batches/{id}/discard") @PreAuthorize("@accessPolicy.hasPermission('imports.grades.manage')")
    public Map<String,Object> discard(@PathVariable long id,@Valid @RequestBody ReasonBody body){return batches.discard(id,body.version(),body.reason());}
}
