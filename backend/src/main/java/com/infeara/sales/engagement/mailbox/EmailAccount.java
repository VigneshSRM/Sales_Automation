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
 * A real mailbox connection (addendum 3.1). Inbound access is deliberately separate from the
 * outbound SMTP profile: SMTP credentials do not grant mailbox reading.
 */
@Entity
@Table(name = "email_accounts")
@Getter
@Setter
@NoArgsConstructor
public class EmailAccount {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "provider", length = 20, nullable = false)
    private String provider = "IMAP";

    @Column(name = "mailbox_address", length = 254, nullable = false)
    private String mailboxAddress;

    @Column(name = "from_name", length = 120)
    private String fromName;

    @Column(name = "inbound_enabled", nullable = false)
    private boolean inboundEnabled = true;

    @Column(name = "outbound_enabled", nullable = false)
    private boolean outboundEnabled = true;

    @Column(name = "smtp_profile_id", length = 36)
    private String smtpProfileId;

    @Column(name = "host", length = 253)
    private String host;

    @Column(name = "port")
    private Integer port;

    @Column(name = "tls_mode", length = 20)
    private String tlsMode;

    @Column(name = "auth_method", length = 20, nullable = false)
    private String authMethod = "PASSWORD";

    @Column(name = "folder", length = 120, nullable = false)
    private String folder = "INBOX";

    @Column(name = "username", length = 254)
    private String username;

    @Column(name = "secret_ciphertext", length = 2048)
    private String secretCiphertext;

    @Column(name = "secret_key_version", nullable = false)
    private int secretKeyVersion = 1;

    @Column(name = "oauth_refresh_ciphertext", length = 4096)
    private String oauthRefreshCiphertext;

    @Column(name = "oauth_scopes", length = 500)
    private String oauthScopes;

    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Column(name = "team_id", length = 36)
    private String teamId;

    @Column(name = "queue_key", length = 60)
    private String queueKey;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING_TEST";

    @Column(name = "poll_interval_seconds", nullable = false)
    private int pollIntervalSeconds = 300;

    @Column(name = "last_health_at")
    private Instant lastHealthAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

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
}
