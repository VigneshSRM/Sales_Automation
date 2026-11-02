package com.infeara.sales.engagement.smtp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An outbound mail account (FR-EMAIL-001). Credentials are stored only as AES-GCM
 * ciphertext; the plaintext secret never leaves the encryption boundary and is never
 * returned by the API, logged, or written to the audit trail.
 */
@Entity
@Table(name = "smtp_profiles")
@Getter
@Setter
@NoArgsConstructor
public class SmtpProfile {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "from_name", length = 120, nullable = false)
    private String fromName;

    @Column(name = "from_email", length = 254, nullable = false)
    private String fromEmail;

    @Column(name = "reply_to_email", length = 254)
    private String replyToEmail;

    @Column(name = "host", length = 253, nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private int port;

    @Column(name = "tls_mode", length = 20, nullable = false)
    private String tlsMode;

    @Column(name = "username", length = 254, nullable = false)
    private String username;

    @Column(name = "secret_ciphertext", length = 2048, nullable = false)
    private String secretCiphertext;

    @Column(name = "secret_key_version", nullable = false)
    private int secretKeyVersion = 1;

    @Column(name = "daily_limit", nullable = false)
    private int dailyLimit = 200;

    @Column(name = "hourly_limit", nullable = false)
    private int hourlyLimit = 50;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING_TEST";

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_test_detail", length = 500)
    private String lastTestDetail;

    @Column(name = "sent_today", nullable = false)
    private int sentToday;

    @Column(name = "sent_today_date")
    private LocalDate sentTodayDate;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public boolean isUsable() {
        return "ACTIVE".equals(status) && deletedAt == null;
    }
}
