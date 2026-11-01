package com.infeara.sales.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Authentication request/response contracts. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String password,
            @Size(max = 80) String workspaceSlug) {
    }

    public record MfaVerifyRequest(
            @NotBlank String challengeToken,
            @NotBlank @Size(min = 6, max = 6) String code) {
    }

    public record MfaEnrollStartRequest(@NotBlank String enrolmentToken) {
    }

    public record MfaEnrollConfirmRequest(
            @NotBlank String enrolmentToken,
            @NotBlank @Size(min = 6, max = 6) String code) {
    }

    public record PasswordChangeRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 12, max = 200) String newPassword) {
    }

    public enum LoginStatus {
        AUTHENTICATED,
        MFA_REQUIRED,
        MFA_ENROLMENT_REQUIRED
    }

    /** {@code sessionToken} is only present when the caller is fully authenticated. */
    public record LoginResponse(
            LoginStatus status,
            String sessionToken,
            String challengeToken,
            String enrolmentToken,
            UserDtos.Profile profile,
            String message) {
    }

    public record SessionInfo(
            String userId,
            String workspaceId,
            String displayName,
            String email,
            String scopeType,
            String timezone,
            boolean mfaSatisfied,
            List<String> capabilities,
            String sessionExpiresAt) {
    }
}
