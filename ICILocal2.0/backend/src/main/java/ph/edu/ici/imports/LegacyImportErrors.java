package ph.edu.ici.imports;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=LegacyImportController.class)
public class LegacyImportErrors {
    @ExceptionHandler(ImportFileException.class)
    public ResponseEntity<ProblemDetail> file(ImportFileException e){
        var problem=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.error().status),e.getMessage());
        problem.setProperty("code",e.error().name());if(e.batchId()!=null)problem.setProperty("batchId",e.batchId());
        return ResponseEntity.status(e.error().status).body(problem);
    }
    @ExceptionHandler(ImportConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(ImportConflictException e){
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,e.getMessage());problem.setProperty("code","DUPLICATE_FILE");problem.setProperty("batchId",e.batchId());problem.setProperty("batchStatus",e.status());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e){
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,"The upload is larger than the allowed size.");problem.setProperty("code","FILE_TOO_LARGE");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e){return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"The request conflicts with existing data. Reload and try again."));}
}
