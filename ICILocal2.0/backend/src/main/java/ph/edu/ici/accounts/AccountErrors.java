package ph.edu.ici.accounts;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=AccountController.class)
public class AccountErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException exception) {
        Throwable cause=exception;while(cause.getCause()!=null)cause=cause.getCause();
        boolean unique=cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState());
        HttpStatus status=unique?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,unique?"That username or email is already in use.":"Check the account details and assignments."));
    }
}
