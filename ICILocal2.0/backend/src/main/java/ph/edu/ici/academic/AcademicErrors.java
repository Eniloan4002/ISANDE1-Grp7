package ph.edu.ici.academic;

import java.sql.SQLException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=AcademicController.class)
public class AcademicErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException exception){
        Throwable cause=exception;while(cause.getCause()!=null)cause=cause.getCause();
        String state=cause instanceof SQLException sql?sql.getSQLState():"";
        HttpStatus status="23502".equals(state)||"23514".equals(state)||"23503".equals(state)
                ?HttpStatus.BAD_REQUEST:HttpStatus.CONFLICT;
        String detail="23505".equals(state)
                ?"This subject is already offered for the selected section and term."
                :"The change violates an academic assignment constraint. Check the selected section, term, subject and staff account.";
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,detail));
    }
}
