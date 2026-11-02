package com.infeara.sales.security;

import com.infeara.sales.common.api.ApiError;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limiting for authentication, API and webhook traffic
 * (SEC-004). Buckets are per instance and in memory by design: no Redis is
 * permitted, and the database is not a sensible store for request counters.
 * Horizontal deployments therefore enforce limits per node — documented in
 * SECURITY-PLAN.md §Rate limiting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties properties;
    private final JsonCodec jsonCodec;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties properties, JsonCodec jsonCodec) {
        this.properties = properties;
        this.jsonCodec = jsonCodec;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AppProperties.RateLimit limits = properties.getSecurity().getRateLimit();
        if (!limits.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        String bucketName;
        int limit;
        if (path.startsWith("/api/v1/auth/")) {
            bucketName = "auth:" + clientKey(request);
            limit = limits.getAuthPerMinute();
        } else if (path.startsWith("/api/v1/webhooks/")) {
            bucketName = "webhook:" + clientKey(request);
            limit = limits.getWebhookPerMinute();
        } else if (path.startsWith("/api/")) {
            bucketName = "api:" + clientKey(request);
            limit = limits.getApiPerMinute();
        } else {
            chain.doFilter(request, response);
            return;
        }
        if (!buckets.computeIfAbsent(bucketName, key -> new Bucket()).tryConsume(limit)) {
            String requestId = CorrelationIdFilter.currentRequestId();
            response.setStatus(ErrorCode.RATE_LIMITED.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            response.getWriter().write(jsonCodec.write(ApiResponse.failure(
                    ApiError.of(ErrorCode.RATE_LIMITED.name(), "Too many requests. Try again shortly.", requestId),
                    requestId)));
            return;
        }
        if (buckets.size() > 20_000) {
            buckets.clear();
        }
        chain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        Object principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() == null ? null
                : org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication().getPrincipal();
        if (principal instanceof CurrentPrincipal current) {
            return current.userId();
        }
        return ip == null ? "unknown" : ip;
    }

    private static final class Bucket {
        private final AtomicInteger count = new AtomicInteger();
        private volatile long windowStart = System.currentTimeMillis();

        boolean tryConsume(int limit) {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000L) {
                synchronized (this) {
                    if (now - windowStart > 60_000L) {
                        windowStart = now;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet() <= limit;
        }
    }
}
