package ph.edu.ici.setup;

import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class SetupErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException exception) {
        Throwable cause=exception;
        while(cause.getCause()!=null) cause=cause.getCause();
        String state=cause instanceof SQLException sql ? sql.getSQLState() : "";
        HttpStatus status="23514".equals(state)||"23502".equals(state)?HttpStatus.BAD_REQUEST:HttpStatus.CONFLICT;
        String message=switch(state==null?"":state) {
            case "23505" -> "That code, label or sequence is already in use. Choose a different value.";
            case "23503" -> "This record is referenced by other records, or its parent no longer exists. Keep referenced configuration and edit its details instead.";
            case "23514" -> "Check the date range, parent dates and field values. A year must contain its terms, and each term must contain its grading periods. Existing parent assignments cannot be changed.";
            case "23502" -> "Complete all required fields.";
            default -> "The change violates a database integrity rule.";
        };
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,message));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception) {
        ProblemDetail detail=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Check the highlighted fields.");
        detail.setProperty("errors",exception.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(error->error.getField(),error->error.getDefaultMessage(),(a,b)->a)));
        return ResponseEntity.badRequest().body(detail);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> malformed(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Use valid JSON values and dates in YYYY-MM-DD format."));
    }
}
