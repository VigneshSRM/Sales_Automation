package com.infeara.sales.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard response envelope required by the API contract standard (spec §12):
 * {@code {success, data | error, meta.requestId}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ApiError error, Meta meta) {

    public record Meta(String requestId, String generatedAt, String timezone) {
    }

    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(true, data, null, new Meta(requestId, null, null));
    }

    public static <T> ApiResponse<T> ok(T data, String requestId, String generatedAt, String timezone) {
        return new ApiResponse<>(true, data, null, new Meta(requestId, generatedAt, timezone));
    }

    public static <T> ApiResponse<T> failure(ApiError error, String requestId) {
        return new ApiResponse<>(false, null, error, new Meta(requestId, null, null));
    }
}
