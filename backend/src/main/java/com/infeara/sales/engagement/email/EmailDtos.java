package com.infeara.sales.engagement.email;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EmailDtos {

    private EmailDtos() {
    }

    public record ComposeRequest(
            UUID smtpProfileId,
            UUID templateId,
            UUID leadId,
            UUID contactId,
            UUID opportunityId,
            @Email @NotBlank String toEmail,
            @Size(max = 200) String toName,
            @Size(max = 300) String subject,
            @Size(max = 50000) String bodyHtml,
            Instant scheduledAt,
            Boolean submitForApproval) {
    }

    public record ApproveRequest(@Size(max = 1000) String note) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record EmailView(UUID id, String status, String subject, String toEmail, String toName,
                            String leadId, String opportunityId, String smtpProfileName, Instant scheduledAt,
                            Instant sentAt, String sentByName, Instant approvedAt, String failureCode,
                            String failureDetail, int attemptCount, Instant createdAt, long rowVersion) {
    }

    public record EmailDetail(EmailView message, String bodyHtml, String bodyText,
                              List<EventView> events, EligibilityView eligibility) {
    }

    public record EventView(String eventType, String detail, Instant occurredAt) {
    }

    public record EligibilityView(boolean allowed, List<String> blockers, List<String> warnings,
                                  String consentStatus) {
    }

    public record DraftResult(UUID id, String status, String message, List<String> warnings) {
    }

    public record SendResult(UUID id, String status, Instant sentAt, String providerMessageId, String detail) {
    }

    public record InboundReplyRequest(
            @NotBlank @Email String fromEmail,
            @Size(max = 300) String subject,
            @Size(max = 20000) String body,
            UUID leadId,
            String providerMessageId) {
    }

    public record BounceRequest(@NotBlank @Email String toEmail, boolean hardBounce,
                                @Size(max = 500) String detail) {
    }
}
