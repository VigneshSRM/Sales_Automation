package com.infeara.sales.common.error;

import com.infeara.sales.common.api.ApiError;
import java.util.List;

/**
 * Business/application exception carrying a stable error code. The message is safe
 * for client display (no stack traces, no secrets, no internal identifiers).
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ApiError.FieldErrorItem> fieldErrors;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<ApiError.FieldErrorItem> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException business(String message) {
        return new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public static ApiException field(String field, String code, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                List.of(new ApiError.FieldErrorItem(field, code, message)));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ApiError.FieldErrorItem> fieldErrors() {
        return fieldErrors;
    }
}
