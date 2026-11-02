package com.infeara.sales.common.error;

import com.infeara.sales.common.api.ApiError;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central exception translation (spec §10/§12). Every response uses the standard
 * envelope and the correlation id; internal details and secrets are never leaked.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex, HttpServletRequest request) {
        if (ex.errorCode().status().is5xxServerError()) {
            log.error("api_exception code={} path={}", ex.errorCode(), request.getRequestURI(), ex);
        } else {
            log.debug("business_rejection code={} path={} message={}", ex.errorCode(), request.getRequestURI(),
                    ex.getMessage());
        }
        return build(ex.errorCode(), ex.getMessage(), ex.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        List<ApiError.FieldErrorItem> errors = ex.getConstraintViolations().stream()
                .map(this::toFieldError)
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", errors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception ex) {
        log.debug("malformed_request {}", ex.getMessage());
        return build(ErrorCode.MALFORMED_REQUEST, "Malformed or unreadable request", List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleStale() {
        return build(ErrorCode.STALE_VERSION,
                "This record was modified by someone else. Reload and re-apply your changes.", List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("data_integrity_violation {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONFLICT, "The request conflicts with existing data", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, "You are not permitted to perform this action", List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException ex) {
        return build(ErrorCode.UNAUTHENTICATED, "Authentication required", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.NOT_FOUND, "Resource not found", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.UNSUPPORTED_OPERATION, "HTTP method not supported for this resource", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled_exception path={} type={}", request.getRequestURI(), ex.getClass().getName(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred. Quote the request id to support.",
                List.of());
    }

    private ApiError.FieldErrorItem toFieldError(FieldError fe) {
        String code = fe.getCode() == null ? "INVALID" : fe.getCode().toUpperCase();
        String message = fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage();
        return new ApiError.FieldErrorItem(fe.getField(), code, message);
    }

    private ApiError.FieldErrorItem toFieldError(ConstraintViolation<?> violation) {
        return new ApiError.FieldErrorItem(violation.getPropertyPath().toString(), "INVALID",
                violation.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message,
                                                    List<ApiError.FieldErrorItem> fieldErrors) {
        String requestId = CorrelationIdFilter.currentRequestId();
        HttpStatus status = code.status();
        ApiError error = ApiError.of(code.name(), message, fieldErrors, requestId);
        return ResponseEntity.status(status)
                .body(ApiResponse.failure(error, requestId));
    }
}
