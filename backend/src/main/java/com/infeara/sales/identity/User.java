package com.infeara.sales.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Workspace user account. Email is unique per workspace (FR-WS-002). */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "email", length = 254, nullable = false)
    private String email;

    @Column(name = "email_normalized", length = 254, nullable = false)
    private String emailNormalized;

    @Column(name = "display_name", length = 160, nullable = false)
    private String displayName;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "status", length = 24, nullable = false)
    private String status = "PENDING";

    @Column(name = "timezone", length = 64, nullable = false)
    private String timezone = "Asia/Kolkata";

    @Column(name = "locale", length = 16, nullable = false)
    private String locale = "en-IN";

    /** Record visibility scope: OWN, TEAM or WORKSPACE (FR-RBAC-002). */
    @Column(name = "scope_type", length = 16, nullable = false)
    private String scopeType = "OWN";

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "mfa_secret_ciphertext", length = 512)
    private String mfaSecretCiphertext;

    @Column(name = "mfa_secret_key_id", length = 64)
    private String mfaSecretKeyId;

    @Column(name = "mfa_enrolled_at")
    private Instant mfaEnrolledAt;

    @Column(name = "mfa_last_used_step")
    private Long mfaLastUsedStep;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public boolean isActive() {
        return "ACTIVE".equals(status) && deletedAt == null;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
