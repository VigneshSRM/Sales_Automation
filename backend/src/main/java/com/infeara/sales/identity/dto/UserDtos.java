package com.infeara.sales.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** User and role administration contracts. */
public final class UserDtos {

    private UserDtos() {
    }

    public record Profile(
            UUID id,
            String displayName,
            String email,
            String status,
            String scopeType,
            String timezone,
            boolean mfaEnabled,
            List<String> roles,
            List<String> capabilities,
            long rowVersion) {
    }

    public record UserSummary(
            UUID id,
            String displayName,
            String email,
            String status,
            String scopeType,
            boolean mfaEnabled,
            List<String> roles,
            Instant lastLoginAt,
            long rowVersion) {
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(min = 12, max = 200) String initialPassword,
            @NotEmpty List<String> roleCodes,
            @Pattern(regexp = "OWN|TEAM|WORKSPACE") String scopeType,
            @Size(max = 64) String timezone) {
    }

    public record UpdateUserRequest(
            @Size(max = 160) String displayName,
            @Pattern(regexp = "OWN|TEAM|WORKSPACE") String scopeType,
            @Size(max = 64) String timezone,
            @Pattern(regexp = "ACTIVE|INACTIVE|LOCKED") String status,
            List<String> roleCodes) {
    }

    public record RoleSummary(String code, String name, String description, List<String> capabilities) {
    }

    public record CapabilitySummary(String code, String module, String description, boolean privileged) {
    }
}
