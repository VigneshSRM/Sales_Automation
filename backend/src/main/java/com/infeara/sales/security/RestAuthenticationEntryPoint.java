package com.infeara.sales.security;

import com.infeara.sales.common.api.ApiError;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.json.JsonCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Returns the standard JSON envelope for unauthenticated API calls (no redirects). */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonCodec jsonCodec;

    public RestAuthenticationEntryPoint(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String requestId = com.infeara.sales.common.web.CorrelationIdFilter.currentRequestId();
        response.setStatus(ErrorCode.UNAUTHENTICATED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(jsonCodec.write(ApiResponse.failure(
                ApiError.of(ErrorCode.UNAUTHENTICATED.name(), "Authentication required", requestId), requestId)));
    }
}
