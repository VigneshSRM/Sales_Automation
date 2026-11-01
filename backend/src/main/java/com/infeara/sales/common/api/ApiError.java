package com.infeara.sales.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Error payload of the standard envelope. Never contains stack traces or secrets. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<FieldErrorItem> fieldErrors, String requestId) {

    public record FieldErrorItem(String field, String code, String message) {
    }

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, null, requestId);
    }

    public static ApiError of(String code, String message, List<FieldErrorItem> fieldErrors, String requestId) {
        return new ApiError(code, message, fieldErrors, requestId);
    }
}
