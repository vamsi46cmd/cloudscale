package io.cloudscale;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** One error schema for every endpoint. Internal details are never returned to the client. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(Instant timestamp, int status, String error, String message, String path, List<String> details) {
        public static ApiError of(HttpStatus s, String message, String path, List<String> details) {
            return new ApiError(Instant.now(), s.value(), s.getReasonPhrase(), message, path, details);
        }
    }

    private static ResponseEntity<ApiError> resp(HttpStatus s, String m, HttpServletRequest r, List<String> d) {
        return ResponseEntity.status(s).body(ApiError.of(s, m, r.getRequestURI(), d));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e, HttpServletRequest r) {
        return resp(e.status, e.getMessage(), r, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest r) {
        List<String> d = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).toList();
        return resp(HttpStatus.BAD_REQUEST, "Validation failed", r, d);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> unreadable(Exception e, HttpServletRequest r) {
        return resp(HttpStatus.BAD_REQUEST, "Malformed request", r, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> optimistic(OptimisticLockingFailureException e, HttpServletRequest r) {
        return resp(HttpStatus.CONFLICT, "The resource was modified concurrently; reload and retry", r, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e, HttpServletRequest r) {
        return resp(HttpStatus.CONFLICT, "Request conflicts with existing data", r, null);
    }

    /** Database unreachable or timing out: tell clients to retry (503) rather than reporting a server bug (500). */
    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.dao.QueryTimeoutException.class})
    ResponseEntity<ApiError> databaseUnavailable(Exception e, HttpServletRequest r) {
        log.warn("Database unavailable on {} {}: {}", r.getMethod(), r.getRequestURI(), e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(ApiError.of(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable; retry shortly", r.getRequestURI(), null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e, HttpServletRequest r) {
        log.error("Unhandled error on {} {}", r.getMethod(), r.getRequestURI(), e);
        return resp(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", r, null);
    }
}
