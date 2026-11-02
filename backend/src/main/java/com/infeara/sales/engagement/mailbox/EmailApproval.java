package com.infeara.sales.engagement.mailbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Approval queue entry for an AI-drafted or policy-gated reply (addendum 3.4 and 4).
 *
 * <p>{@code payloadHash} binds the approval to the exact draft that was reviewed: if the draft body
 * changes afterwards the approval is invalid and the send is refused.
 */
@Entity
@Table(name = "email_approvals")
@Getter
@Setter
@NoArgsConstructor
public class EmailApproval {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "message_id", length = 36, nullable = false)
    private String messageId;

    @Column(name = "conversation_id", length = 36)
    private String conversationId;

    @Column(name = "analysis_id", length = 36)
    private String analysisId;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING";

    @Column(name = "reason_code", length = 60)
    private String reasonCode;

    @Column(name = "requested_by", length = 36)
    private String requestedBy;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "payload_hash", length = 64, nullable = false)
    private String payloadHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
