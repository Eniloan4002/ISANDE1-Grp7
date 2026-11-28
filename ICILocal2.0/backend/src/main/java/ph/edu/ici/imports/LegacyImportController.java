package ph.edu.ici.imports;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/legacy-student-imports")
public class LegacyImportController {
    private final ImportMappingService mappings;private final ImportValidationService validation;private final ImportBatchService batches;private final ImportCommitService commits;
    public LegacyImportController(ImportMappingService mappings,ImportValidationService validation,ImportBatchService batches,ImportCommitService commits){this.mappings=mappings;this.validation=validation;this.batches=batches;this.commits=commits;}

    public record MappingBody(@NotBlank @Size(max=120) String name,@NotNull JsonNode mapping,@NotNull JsonNode settings,Boolean isDefault,Boolean active,Boolean realFileValidated,@PositiveOrZero Integer version) {
        ImportMappingService.MappingInput input(){return new ImportMappingService.MappingInput(name,mapping,settings,Boolean.TRUE.equals(isDefault),active==null||active,Boolean.TRUE.equals(realFileValidated));}
    }
    public record ReviewBody(@NotNull @PositiveOrZero Integer version,@NotBlank @Pattern(regexp="same_person|distinct|skip") String decision,@NotBlank @Size(max=2000) String reason,@Positive Long studentId) {}
    public record DiscardBody(@NotNull @PositiveOrZero Integer version,@NotBlank @Size(max=1000) String reason) {}
    public record CommitBody(@NotNull @PositiveOrZero Integer version,@NotNull @PositiveOrZero Integer expectedValidCount) {}

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
    @PostMapping(value="/batches",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("@accessPolicy.hasPermission('imports.manage')")
    public Map<String,Object> upload(@RequestParam @Positive long campusId,@RequestParam(required=false) @Positive Long mappingId,@RequestPart("file") MultipartFile file){
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("");String type=Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        if(!name.toLowerCase(Locale.ROOT).endsWith(".csv")&&!type.startsWith("text/csv"))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Upload a .csv file.");
        byte[] bytes;try{bytes=file.getBytes();}catch(IOException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The file could not be read.");}
        return batches.detail(validation.stage(campusId,name,bytes,mappingId).batchId());
    }
    @GetMapping("/batches") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> batches(@RequestParam(required=false) Long campusId,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.list(campusId,status,page,size);}
    @GetMapping("/batches/{id}") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> batch(@PathVariable long id){return batches.detail(id);}
    @GetMapping("/batches/{id}/rows") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> rows(@PathVariable long id,@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.rows(id,status,page,size);}
    @GetMapping("/batches/{id}/errors") @PreAuthorize("@accessPolicy.hasPermission('imports.read')")
    public Map<String,Object> errors(@PathVariable long id,@RequestParam(required=false) String severity,@RequestParam(required=false) Integer rowNumber,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return batches.errors(id,severity,rowNumber,page,size);}
    @PostMapping("/batches/{id}/rows/{rowId}/review") @PreAuthorize("@accessPolicy.hasPermission('imports.manage')")
    public Map<String,Object> review(@PathVariable long id,@PathVariable long rowId,@Valid @RequestBody ReviewBody body){return batches.review(id,rowId,body.version(),body.decision(),body.reason(),body.studentId());}
    @PostMapping("/batches/{id}/discard") @PreAuthorize("@accessPolicy.hasPermission('imports.manage')")
    public Map<String,Object> discard(@PathVariable long id,@Valid @RequestBody DiscardBody body){return batches.discard(id,body.version(),body.reason());}
    @PostMapping("/batches/{id}/commit") @PreAuthorize("@accessPolicy.hasPermission('imports.manage')")
    public Map<String,Object> commit(@PathVariable long id,@Valid @RequestBody CommitBody body){return commits.commit(id,body.version(),body.expectedValidCount());}
}
