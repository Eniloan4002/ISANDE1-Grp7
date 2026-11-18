package ph.edu.ici.documents;

import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/official-documents")
@PreAuthorize("@accessPolicy.hasPermission('documents.manage')")
public class OfficialDocumentController {
    private final OfficialDocumentService service;
    public OfficialDocumentController(OfficialDocumentService service){this.service=service;}
    public record PolicyInput(Long campusId,Long programId,String documentType,String institutionName,
            String institutionAddress,String signatories,String templateReference,Boolean soRequired,String soPrefix,Integer soMaxLength,Integer version){}
    public record Approval(Integer version,String approvalReference){}
    public record SpecialOrder(Integer version,String number,java.time.LocalDate issuedOn,String issuingAuthority){}
    public record Selection(Long studentId,Long policyId,Integer policyVersion,List<Long> enrollmentIds,UUID requestKey){}
    @GetMapping("/policies") public Object policies(){return service.policies();}
    @PostMapping("/policies") @ResponseStatus(HttpStatus.CREATED) public Object create(@RequestBody PolicyInput input){return service.savePolicy(null,input);}
    @PutMapping("/policies/{id}") public Object update(@PathVariable long id,@RequestBody PolicyInput input){return service.savePolicy(id,input);}
    @PostMapping("/policies/{id}/approve") public Object approve(@PathVariable long id,@RequestBody Approval input){return service.approve(id,input);}
    @GetMapping("/special-orders/{enrollmentId}") public Object so(@PathVariable long enrollmentId){return service.specialOrder(enrollmentId);}
    @PutMapping("/special-orders/{enrollmentId}") public Object so(@PathVariable long enrollmentId,@RequestBody SpecialOrder input){return service.saveSpecialOrder(enrollmentId,input);}
    @PostMapping("/verify") public Object verify(@RequestBody Selection input){return service.verify(input);}
    @PostMapping("/preview") public ResponseEntity<byte[]> preview(@RequestBody Selection input){return pdf(service.preview(input),"DRAFT-academic-record.pdf");}
    @PostMapping("/issue") @ResponseStatus(HttpStatus.CREATED) public Object issue(@RequestBody Selection input){return service.issue(input);}
    @GetMapping("/generations/{id}") public Object generation(@PathVariable UUID id){return service.generation(id);}
    @GetMapping("/generations/{id}/pdf") public ResponseEntity<byte[]> download(@PathVariable UUID id){return pdf(service.download(id),"academic-record-"+id+".pdf");}
    private ResponseEntity<byte[]> pdf(byte[] bytes,String name){return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"").body(bytes);}
}
