package ph.edu.ici.documents;

import org.springframework.dao.*;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=OfficialDocumentController.class)
public class OfficialDocumentErrors {
    @ExceptionHandler({CannotAcquireLockException.class,PessimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> busy(Exception e){return problem(HttpStatus.CONFLICT,"Academic records are being updated. Retry document issuance after the update finishes.");}
    @ExceptionHandler(UncategorizedSQLException.class)
    public ResponseEntity<ProblemDetail> sql(UncategorizedSQLException e){
        if("55P03".equals(e.getSQLException().getSQLState()))return busy(e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,"The document operation could not be completed.");
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrity(Exception e){return problem(HttpStatus.CONFLICT,"The document configuration, Special Order or request conflicts with an existing or referenced record. Reload and check the supplied values.");}
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String detail){return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,detail));}
}
