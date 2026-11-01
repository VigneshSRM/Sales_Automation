package com.infeara.sales.engagement.smtp;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** SMTP profile contracts. The secret is write-only: it is never present in a response. */
public final class SmtpDtos {

    private SmtpDtos() {
    }

    public record CreateSmtpProfileRequest(
            @NotBlank @Size(min = 2, max = 80) String name,
            @NotBlank @Size(max = 120) String fromName,
            @NotBlank @Email String fromEmail,
            @Email String replyToEmail,
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$",
                    message = "must be a hostname, not a URL")
            String host,
            @Min(1) @Max(65535) int port,
            @NotBlank @Pattern(regexp = "STARTTLS|IMPLICIT_TLS|NONE") String tlsMode,
            @NotBlank @Size(max = 254) String username,
            @NotBlank @Size(min = 1, max = 500) String secret,
            @Min(1) @Max(5000) Integer dailyLimit,
            @Min(1) @Max(1000) Integer hourlyLimit) {
    }

    public record UpdateSmtpProfileRequest(
            @Size(min = 2, max = 80) String name,
            @Size(max = 120) String fromName,
            @Email String fromEmail,
            @Email String replyToEmail,
            @Pattern(regexp = "^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$") String host,
            @Min(1) @Max(65535) Integer port,
            @Pattern(regexp = "STARTTLS|IMPLICIT_TLS|NONE") String tlsMode,
            @Size(max = 254) String username,
            @Size(min = 1, max = 500) String secret,
            @Min(1) @Max(5000) Integer dailyLimit,
            @Min(1) @Max(1000) Integer hourlyLimit,
            Long expectedRowVersion) {
    }

    public record TestConnectionRequest(@Email String recipient, Long expectedRowVersion) {
    }

    public record TestConnectionResult(boolean delivered, String detail, Instant testedAt, String host,
                                       int port, String tlsMode) {
    }

    public record SmtpProfileView(UUID id, String name, String fromName, String fromEmail, String replyToEmail,
                                  String host, int port, String tlsMode, String username, int dailyLimit,
                                  int hourlyLimit, String status, Instant lastTestedAt, String lastTestDetail,
                                  int sentToday, long rowVersion) {
    }

    /** Parse a TLS mode into the socket factory Spring's JavaMailSender needs. */
    public static boolean isValidTlsMode(String mode) {
        return switch (mode == null ? "" : mode) {
            case "STARTTLS", "IMPLICIT_TLS", "NONE" -> true;
            default -> false;
        };
    }
}
