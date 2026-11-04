package ph.edu.ici.students;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=StudentController.class)
public class StudentErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException error){
        Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
        String state=cause instanceof java.sql.SQLException sql?sql.getSQLState():"";
        HttpStatus status="23505".equals(state)?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;
        String message=status==HttpStatus.CONFLICT?"A student identifier is already in use. Review the record before retrying.":"Check the student values and referenced campus, program or voucher.";
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,message));
    }
}
