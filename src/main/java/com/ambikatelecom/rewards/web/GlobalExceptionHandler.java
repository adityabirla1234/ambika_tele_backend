package com.ambikatelecom.rewards.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves the API in the same safe shape: a stable code, a fixed message and the correlation id.
 * Nothing from an exception message, stack trace, SQL or request value reaches the caller or the log, because
 * those can carry names, phone numbers or device numbers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static String cid() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    private static ResponseEntity<ApiError> simple(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(code, message, cid()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldIssue> issues = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldIssue(f.getField(), f.getDefaultMessage())).toList();
        ApiError body = new ApiError("VALIDATION_FAILED", "Please check the highlighted fields.",
                cid(), java.time.Instant.now(), issues);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof RequestSizeLimitFilter.PayloadTooLargeException) {
                return simple(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "That request is too large.");
            }
            if (t.getCause() == t) break;
        }
        return simple(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The request could not be read.");
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> badRequest(Exception ex) {
        return simple(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The request could not be read.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return simple(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "That action is not allowed here.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMedia(HttpMediaTypeNotSupportedException ex) {
        return simple(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send the request as JSON.");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiError> notFound(Exception ex) {
        return simple(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found.");
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex) {
        List<ApiError.FieldIssue> fields = ex.getField() == null
                ? List.of() : List.of(new ApiError.FieldIssue(ex.getField(), ex.getMessage()));
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiError(ex.getCode(), ex.getMessage(), cid(), java.time.Instant.now(), fields));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex) {
        // The message of this exception names the duplicate value, so it is never logged.
        log.warn("Data integrity conflict");
        return simple(HttpStatus.CONFLICT, "CONFLICT", "That request clashed with another one. Please try again.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        // Log the exception types and the first line of our own code only. Exception messages and the stack
        // trace header can carry personal data, so the exception object itself is deliberately not passed to the logger.
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        log.error("Unhandled error type={} root={} at={}", ex.getClass().getName(), root.getClass().getName(), whereInOurCode(root));
        return simple(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Please try again.");
    }

    private static String whereInOurCode(Throwable t) {
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith("com.ambikatelecom.")) {
                return e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1) + "." + e.getMethodName() + ":" + e.getLineNumber();
            }
        }
        return "unknown";
    }
}
