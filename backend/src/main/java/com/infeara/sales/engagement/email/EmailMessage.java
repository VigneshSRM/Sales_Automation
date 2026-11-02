package com.infeara.sales.engagement.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One outbound email and its delivery lifecycle (BR-EMAIL-002):
 * DRAFT → PENDING_APPROVAL → SCHEDULED → QUEUED → SENDING → SENT
 * (→ DELIVERED / BOUNCED / REPLIED), with CANCELLED available before hand-off.
 *
 * message_key is unique per workspace and is used as the idempotency key with the
 * mail transport, so a retried job can never deliver the same message twice.
 */
@Entity
@Table(name = "email_messages")
@Getter
@Setter
@NoArgsConstructor
public class EmailMessage {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    /** Null for INBOUND messages: nothing was sent from an SMTP account. */
    @Column(name = "smtp_profile_id", length = 36)
    private String smtpProfileId;

    @Column(name = "direction", length = 10, nullable = false)
    private String direction = "OUTBOUND";

    /** The mailbox this message was received through (inbound only); the CRM account id is below. */
    @Column(name = "mailbox_account_id", length = 36)
    private String mailboxAccountId;

    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    /** RFC 5322 Message-ID, In-Reply-To and References: the threading evidence. */
    @Column(name = "message_id_header", length = 400)
    private String messageIdHeader;

    @Column(name = "in_reply_to_header", length = 400)
    private String inReplyToHeader;

    @Column(name = "references_header", length = 2000)
    private String referencesHeader;

    @Column(name = "template_id", length = 36)
    private String templateId;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "contact_id", length = 36)
    private String contactId;

    @Column(name = "account_id", length = 36)
    private String accountId;

    @Column(name = "opportunity_id", length = 36)
    private String opportunityId;

    @Column(name = "sequence_enrollment_id", length = 36)
    private String sequenceEnrollmentId;

    @Column(name = "campaign_id", length = 36)
    private String campaignId;

    @Column(name = "to_email", length = 254, nullable = false)
    private String toEmail;

    @Column(name = "to_name", length = 200)
    private String toName;

    @Column(name = "from_email", length = 254)
    private String fromEmail;

    @Column(name = "from_name", length = 200)
    private String fromName;

    @Column(name = "cc_json")
    private String ccJson;

    @Column(name = "reply_to_email", length = 254)
    private String replyToEmail;

    @Column(name = "subject", length = 300, nullable = false)
    private String subject;

    @Column(name = "body_html", nullable = false)
    private String bodyHtml;

    @Column(name = "body_text")
    private String bodyText;

    @Column(name = "raw_headers_json")
    private String rawHeadersJson;

    @Column(name = "uid")
    private Long uid;

    @Column(name = "uid_validity")
    private Long uidValidity;

    @Column(name = "attachment_count", nullable = false)
    private int attachmentCount;

    @Column(name = "status", length = 30, nullable = false)
    private String status = "DRAFT";

    @Column(name = "ai_status", length = 20, nullable = false)
    private String aiStatus = "NONE";

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "message_key", length = 120, nullable = false)
    private String messageKey;

    /** Inbound dedupe key: provider message id, or mailbox + UIDVALIDITY + UID. */
    @Column(name = "inbound_key", length = 160)
    private String inboundKey;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_detail", length = 1000)
    private String failureDetail;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public boolean isTerminal() {
        return switch (status) {
            case "SENT", "DELIVERED", "BOUNCED", "REPLIED", "FAILED", "CANCELLED" -> true;
            default -> false;
        };
    }
}
