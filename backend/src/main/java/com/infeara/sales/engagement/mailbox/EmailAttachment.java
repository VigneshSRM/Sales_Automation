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
 * Attachment metadata only (addendum 3.2: attachment bytes belong in secure object storage, never in
 * an ordinary CRM row). {@code scanStatus} is explicit so an unscanned file can never be mistaken for
 * a clean one.
 */
@Entity
@Table(name = "email_attachments")
@Getter
@Setter
@NoArgsConstructor
public class EmailAttachment {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "message_id", length = 36, nullable = false)
    private String messageId;

    @Column(name = "filename", length = 255, nullable = false)
    private String filename;

    @Column(name = "media_type", length = 160, nullable = false)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_ref", length = 500)
    private String storageRef;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "scan_status", length = 20, nullable = false)
    private String scanStatus = "PENDING";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
