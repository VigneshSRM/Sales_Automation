package com.infeara.sales.security;

import com.infeara.sales.common.api.ApiError;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.json.JsonCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonCodec jsonCodec;

    public RestAccessDeniedHandler(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String requestId = com.infeara.sales.common.web.CorrelationIdFilter.currentRequestId();
        response.setStatus(ErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(jsonCodec.write(ApiResponse.failure(
                ApiError.of(ErrorCode.FORBIDDEN.name(), "You are not permitted to perform this action", requestId),
                requestId)));
    }
}
