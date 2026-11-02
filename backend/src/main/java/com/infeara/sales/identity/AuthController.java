package com.infeara.sales.identity;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.config.AppProperties;
import com.infeara.sales.identity.dto.AuthDtos;
import com.infeara.sales.identity.dto.UserDtos;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.CurrentPrincipalProvider;
import com.infeara.sales.security.SessionAuthenticationFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. The session token is delivered as an HttpOnly cookie and
 * is never echoed in a JSON body; the CSRF token is a readable cookie echoed by the SPA.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login, MFA, session and password management")
public class AuthController {

    private final AuthService authService;
    private final SessionService sessionService;
    private final CurrentPrincipalProvider principalProvider;
    private final AppProperties properties;

    public AuthController(AuthService authService, SessionService sessionService,
                          CurrentPrincipalProvider principalProvider, AppProperties properties) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.principalProvider = principalProvider;
        this.properties = properties;
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email and password")
    public ApiResponse<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse httpResponse) {
        var response = authService.login(request, clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        if (response.status() == AuthDtos.LoginStatus.AUTHENTICATED) {
            setSessionCookie(httpRequest, httpResponse, response.sessionToken());
            return ApiResponse.ok(sanitised(response), CorrelationIdFilter.currentRequestId());
        }
        return ApiResponse.ok(response, CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/mfa/verify")
    @Operation(summary = "Complete login with a TOTP code")
    public ApiResponse<AuthDtos.LoginResponse> verifyMfa(@Valid @RequestBody AuthDtos.MfaVerifyRequest request,
                                                         HttpServletRequest httpRequest,
                                                         HttpServletResponse httpResponse) {
        var response = authService.verifyMfa(request, clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        setSessionCookie(httpRequest, httpResponse, response.sessionToken());
        return ApiResponse.ok(sanitised(response), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/mfa/enrol/start")
    @Operation(summary = "Start MFA enrolment (returns the provisioning URI once)")
    public ApiResponse<MfaEnrolmentResponse> startEnrolment(@Valid @RequestBody AuthDtos.MfaEnrollStartRequest request) {
        var enrolment = authService.startMfaEnrolment(request.enrolmentToken());
        return ApiResponse.ok(new MfaEnrolmentResponse(enrolment.provisioningUri(), enrolment.secret()),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/mfa/enrol/confirm")
    @Operation(summary = "Confirm MFA enrolment with a TOTP code")
    public ApiResponse<AuthDtos.LoginResponse> confirmEnrolment(
            @Valid @RequestBody AuthDtos.MfaEnrollConfirmRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        var response = authService.confirmMfaEnrolment(request, clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
        setSessionCookie(httpRequest, httpResponse, response.sessionToken());
        return ApiResponse.ok(sanitised(response), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session")
    public ApiResponse<java.util.Map<String, String>> logout(HttpServletRequest httpRequest,
                                                             HttpServletResponse httpResponse) {
        CurrentPrincipal principal = principalProvider.principalOrNull();
        String token = readSessionCookie(httpRequest);
        authService.logout(token, principal == null ? null : principal.userId());
        clearSessionCookie(httpRequest, httpResponse);
        return ApiResponse.ok(java.util.Map.of("status", "SIGNED_OUT"), CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/me")
    @Operation(summary = "Current user profile, capabilities and session expiry")
    public ApiResponse<AuthDtos.SessionInfo> me(HttpServletRequest httpRequest) {
        CurrentPrincipal principal = principalProvider.principal();
        Instant expiry = sessionService.expiryFor(readSessionCookie(httpRequest));
        UserDtos.Profile profile = authService.profileFor(principal.userId());
        return ApiResponse.ok(new AuthDtos.SessionInfo(principal.userId(), principal.workspaceId(),
                principal.displayName(), principal.email(), principal.scopeType(), principal.timezone(),
                principal.mfaSatisfied(),
                profile == null ? List.copyOf(principal.capabilities()) : profile.capabilities(),
                expiry == null ? null : expiry.toString()), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/password")
    @Operation(summary = "Change my password (revokes other sessions)")
    public ApiResponse<java.util.Map<String, String>> changePassword(
            @Valid @RequestBody AuthDtos.PasswordChangeRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        authService.changePassword(authService.requireUser(principal.userId()),
                request.currentPassword(), request.newPassword());
        return ApiResponse.ok(java.util.Map.of("status", "PASSWORD_CHANGED"),
                CorrelationIdFilter.currentRequestId());
    }

    private AuthDtos.LoginResponse sanitised(AuthDtos.LoginResponse response) {
        return new AuthDtos.LoginResponse(response.status(), null, null, null, response.profile(),
                response.message());
    }

    private void setSessionCookie(HttpServletRequest request, HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(SessionAuthenticationFilter.SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookie(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMinutes(properties.getSecurity().getSessionTtlMinutes()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearSessionCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(SessionAuthenticationFilter.SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookie(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * A Secure cookie is only ever sent over TLS. Marking it Secure on a plain-HTTP connector (a
     * local run, or Tomcat behind a TLS-terminating proxy that is not configured to forward the
     * scheme) makes every subsequent request unauthenticated — the failure looks like a broken
     * session, not a cookie problem.
     */
    private boolean secureCookie(HttpServletRequest request) {
        String mode = properties.getSecurity().getCookieSecure();
        if ("always".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("never".equalsIgnoreCase(mode)) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null && forwardedProto.toLowerCase(java.util.Locale.ROOT).startsWith("https");
    }

    private String readSessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (SessionAuthenticationFilter.SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }

    public record MfaEnrolmentResponse(String provisioningUri, String secret) {
    }
}
