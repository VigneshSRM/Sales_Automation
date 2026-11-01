package com.infeara.sales.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable security audit record (FR-AUD-001). Append-only: the application
 * database user has no UPDATE/DELETE grant on this table (deploy/sql/grants.sql),
 * and {@code prev_hash}/{@code record_hash} form a tamper-evident chain.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false, updatable = false)
    private String workspaceId;

    @Column(name = "actor_type", length = 16, nullable = false)
    private String actorType;

    @Column(name = "actor_user_id", length = 36)
    private String actorUserId;

    @Column(name = "action", length = 80, nullable = false)
    private String action;

    @Column(name = "object_type", length = 60, nullable = false)
    private String objectType;

    @Column(name = "object_id", length = 36)
    private String objectId;

    @Column(name = "outcome", length = 16, nullable = false)
    private String outcome = "SUCCESS";

    @Column(name = "before_json", columnDefinition = "json")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "json")
    private String afterJson;

    @Column(name = "metadata_json", columnDefinition = "json")
    private String metadataJson;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "record_hash", length = 64, nullable = false)
    private String recordHash;
}
