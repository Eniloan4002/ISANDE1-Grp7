package ph.edu.ici.enrollment;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={EnrollmentController.class,IntakeController.class,DuplicateController.class})
public class EnrollmentErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException exception){
        Throwable cause=exception;while(cause.getCause()!=null)cause=cause.getCause();
        String state=cause instanceof java.sql.SQLException sql?sql.getSQLState():"";
        HttpStatus status="23502".equals(state)||"23514".equals(state)?HttpStatus.BAD_REQUEST:HttpStatus.CONFLICT;
        String text="23505".equals(state)?"A section name or student enrollment for this term already exists.":"23503".equals(state)?"This record is referenced, or the selected campus, term or program no longer exists.":"The change violates an enrollment or capacity rule. Reload and check the record.";
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,text));
    }
}
