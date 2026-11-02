package com.infeara.sales.engagement.mailbox;

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
 * A thread in the unified inbox (addendum 3.2 and 4).
 *
 * <p>{@code threadKey} is derived from the RFC threading identifiers when they exist and from
 * mailbox + normalized subject otherwise. An ambiguous match leaves {@code leadId} null rather than
 * attaching the conversation to a guessed customer.
 */
@Entity
@Table(name = "email_conversations")
@Getter
@Setter
@NoArgsConstructor
public class EmailConversation {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "account_id", length = 36)
    private String accountId;

    @Column(name = "mailbox_address", length = 254, nullable = false)
    private String mailboxAddress;

    @Column(name = "thread_key", length = 300, nullable = false)
    private String threadKey;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "normalized_subject", length = 300)
    private String normalizedSubject;

    @Column(name = "participant_emails_json")
    private String participantEmailsJson;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "contact_id", length = 36)
    private String contactId;

    @Column(name = "crm_account_id", length = 36)
    private String crmAccountId;

    @Column(name = "opportunity_id", length = 36)
    private String opportunityId;

    @Column(name = "assigned_user_id", length = 36)
    private String assignedUserId;

    @Column(name = "queue_key", length = 60)
    private String queueKey;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "OPEN";

    @Column(name = "priority", length = 20, nullable = false)
    private String priority = "NORMAL";

    @Column(name = "unread", nullable = false)
    private boolean unread = true;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
