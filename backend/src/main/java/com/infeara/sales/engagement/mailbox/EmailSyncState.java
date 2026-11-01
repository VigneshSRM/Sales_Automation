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
 * Per-mailbox sync checkpoint (addendum 3.1 and 6). The IMAP connector resumes from
 * {@code uidValidity + lastUid}; a UIDVALIDITY change forces a reconciliation pass instead of
 * silently skipping messages that were renumbered by the server.
 */
@Entity
@Table(name = "email_sync_state")
@Getter
@Setter
@NoArgsConstructor
public class EmailSyncState {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "account_id", length = 36, nullable = false)
    private String accountId;

    @Column(name = "folder", length = 120, nullable = false)
    private String folder = "INBOX";

    @Column(name = "uid_validity")
    private Long uidValidity;

    @Column(name = "last_uid", nullable = false)
    private long lastUid;

    @Column(name = "provider_cursor", length = 500)
    private String providerCursor;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "reconciliation_status", length = 20, nullable = false)
    private String reconciliationStatus = "IDLE";

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
