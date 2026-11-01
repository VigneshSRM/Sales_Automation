package com.infeara.sales.engagement.mailbox;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contracts for the two-way email module (addendum 8). Secrets are write-only. */
public final class MailboxDtos {

    private MailboxDtos() {
    }

    public record CreateAccountRequest(
            @NotBlank @Pattern(regexp = "IMAP|GMAIL_API|MICROSOFT_GRAPH") String provider,
            @NotBlank @Size(min = 2, max = 80) String name,
            @NotBlank @Email String mailboxAddress,
            @Size(max = 120) String fromName,
            boolean inboundEnabled,
            boolean outboundEnabled,
            UUID smtpProfileId,
            @Pattern(regexp = "^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$") String host,
            @Min(1) @Max(65535) Integer port,
            @Pattern(regexp = "STARTTLS|IMPLICIT_TLS|NONE") String tlsMode,
            @Pattern(regexp = "PASSWORD|OAUTH2") String authMethod,
            @Size(max = 120) String folder,
            @Size(max = 254) String username,
            @Size(min = 1, max = 500) String secret,
            @Size(max = 4000) String oauthRefreshToken,
            UUID ownerUserId,
            @Size(max = 60) String queueKey,
            @Min(60) @Max(86400) Integer pollIntervalSeconds) {
    }

    public record UpdateAccountRequest(
            @Size(min = 2, max = 80) String name,
            @Email String mailboxAddress,
            @Size(max = 120) String fromName,
            Boolean inboundEnabled,
            Boolean outboundEnabled,
            UUID smtpProfileId,
            @Pattern(regexp = "^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$") String host,
            @Min(1) @Max(65535) Integer port,
            @Pattern(regexp = "STARTTLS|IMPLICIT_TLS|NONE") String tlsMode,
            @Size(max = 120) String folder,
            @Size(max = 254) String username,
            @Size(min = 1, max = 500) String secret,
            @Size(max = 4000) String oauthRefreshToken,
            UUID ownerUserId,
            @Size(max = 60) String queueKey,
            @Min(60) @Max(86400) Integer pollIntervalSeconds,
            Long expectedRowVersion) {
    }

    public record SyncStateView(Long uidValidity, long lastUid, Instant lastSyncAt, Instant lastSuccessAt,
                                int consecutiveFailures, String lastError, String reconciliationStatus) {
    }

    public record AccountView(UUID id, String name, String provider, String mailboxAddress, String fromName,
                              boolean inboundEnabled, boolean outboundEnabled, String smtpProfileName,
                              String host, Integer port, String tlsMode, String folder, String authMethod,
                              String username, String status, int pollIntervalSeconds, Instant lastHealthAt,
                              String lastError, boolean hasSecret, boolean hasOauthToken, String ownerName,
                              String queueKey, SyncStateView sync, long rowVersion) {
    }

    public record TestConnectionResult(boolean ok, String detail, Instant testedAt, String host, Integer port,
                                       String tlsMode, String folder, Integer messageCount) {
    }

    public record SyncResult(UUID accountId, String mailbox, int fetched, int persisted, int duplicates,
                             int failed, String detail, Instant completedAt) {
    }

    public record AttachmentView(UUID id, String filename, String mediaType, long sizeBytes, String scanStatus) {
    }

    public record MessageView(UUID id, String direction, String fromEmail, String fromName, String toEmail,
                              String subject, String bodyText, String bodyHtml, Instant receivedAt,
                              Instant sentAt, String status, String messageIdHeader, String inReplyToHeader,
                              String aiStatus, List<AttachmentView> attachments) {
    }

    public record AnalysisView(String intent, String subIntent, String sentiment, String urgency,
                               String buyingSignal, BigDecimal confidence, String recommendedAction,
                               String replyDraft, String provider, String model, String promptVersion,
                               boolean validated, Instant createdAt, List<Map<String, Object>> extracted,
                               List<String> questions, List<String> risks) {
    }

    public record ApprovalView(UUID id, UUID messageId, String status, String reasonCode, String requestedByName,
                               String decidedByName, Instant decidedAt, String rejectionReason,
                               Instant expiresAt, Instant createdAt) {
    }

    public record PolicyView(String mode, String reasonCode, String ruleName, boolean mayAutoSend,
                             int delaySeconds) {
    }

    public record ConversationSummary(UUID id, String subject, String mailboxAddress, UUID accountId,
                                      UUID leadId, String leadName, UUID contactId, String contactName,
                                      UUID assignedUserId, String assignedUserName, String queueKey,
                                      String status, String priority, boolean unread, int messageCount,
                                      Instant lastMessageAt, String matchReason, boolean awaitingApproval) {
    }

    public record ConversationDetail(ConversationSummary summary, List<MessageView> messages,
                                     AnalysisView analysis, ApprovalView approval, PolicyView policy,
                                     String aiDraft, long rowVersion) {
    }

    public record ReplyRequest(
            @NotBlank @Size(max = 20000) String body,
            @Size(max = 300) String subject,
            Boolean sendImmediately) {
    }

    public record AssignRequest(UUID userId, @Size(max = 60) String queueKey, String note) {
    }

    public record NoteRequest(@NotBlank @Size(max = 2000) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record CreateRuleRequest(
            @NotBlank @Size(max = 160) String name,
            @Min(1) @Max(1000) Integer priority,
            @NotBlank @Pattern(regexp = "AUTOMATIC|APPROVAL_REQUIRED|HUMAN|NO_RESPONSE") String mode,
            List<String> allowedIntents,
            List<String> blockedIntents,
            @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("1")
            BigDecimal minConfidence,
            List<String> businessDays,
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String businessHoursStart,
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String businessHoursEnd,
            @Min(0) @Max(86400) Integer delaySeconds,
            Boolean enabled) {
    }

    public record RuleView(UUID id, String name, int priority, boolean enabled, String mode,
                           List<String> allowedIntents, List<String> blockedIntents, BigDecimal minConfidence,
                           List<String> businessDays, String businessHoursStart, String businessHoursEnd,
                           int delaySeconds, Instant createdAt, long rowVersion) {
    }

    public record InboxStats(int totalOpen, int unread, int unassigned, int awaitingApproval,
                             int analysedToday) {
    }
}
