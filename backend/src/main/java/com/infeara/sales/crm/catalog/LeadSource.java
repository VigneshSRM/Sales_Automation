package com.infeara.sales.crm.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "lead_sources")
@Getter
@Setter
@NoArgsConstructor
public class LeadSource {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "code", length = 40, nullable = false)
    private String code;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    /** WEBSITE, MANUAL, IMPORT, REFERRAL, EMAIL_ENQUIRY, CALENDLY, API. */
    @Column(name = "source_type", length = 40, nullable = false)
    private String sourceType;

    /** WARN, REJECT_ON_DUPLICATE or ALLOW (BR-DUP-004). */
    @Column(name = "duplicate_policy", length = 32, nullable = false)
    private String duplicatePolicy = "WARN";

    @Column(name = "webhook_secret_ciphertext", length = 512)
    private String webhookSecretCiphertext;

    @Column(name = "webhook_secret_key_id", length = 64)
    private String webhookSecretKeyId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;
}
