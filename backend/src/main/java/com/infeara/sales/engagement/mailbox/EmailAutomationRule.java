package com.infeara.sales.engagement.mailbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response policy (addendum 3.4).
 *
 * <p>Rules are evaluated highest priority first; the first enabled rule whose intent and confidence
 * constraints match decides the mode. When nothing matches the engine falls back to
 * {@code HUMAN}: an unmatched message is never answered automatically.
 */
@Entity
@Table(name = "email_automation_rules")
@Getter
@Setter
@NoArgsConstructor
public class EmailAutomationRule {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "name", length = 160, nullable = false)
    private String name;

    @Column(name = "priority", nullable = false)
    private int priority = 100;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "mode", length = 20, nullable = false)
    private String mode;

    @Column(name = "allowed_intents_json")
    private String allowedIntentsJson;

    @Column(name = "blocked_intents_json")
    private String blockedIntentsJson;

    @Column(name = "min_confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal minConfidence = new BigDecimal("0.850");

    @Column(name = "business_hours_json")
    private String businessHoursJson;

    @Column(name = "delay_seconds", nullable = false)
    private int delaySeconds;

    @Column(name = "account_id", length = 36)
    private String accountId;

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
