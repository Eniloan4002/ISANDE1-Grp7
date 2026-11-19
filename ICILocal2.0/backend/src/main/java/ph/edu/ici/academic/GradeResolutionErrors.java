package ph.edu.ici.academic;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=GradeResolutionController.class)
public class GradeResolutionErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException exception){
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"The grade or request changed, or another pending request exists. Reload before saving."));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> oversized(MaxUploadSizeExceededException exception){
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,"Evidence must be at most 1,000,000 bytes; total request size must be at most 2 MB."));
    }
}
