package com.infeara.sales.automation.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Durable job row. Claiming is a single conditional UPDATE (compare-and-set) so it
 * is atomic without an external broker and safe with several Tomcat instances.
 */
@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class Job {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "job_type", length = 80, nullable = false)
    private String jobType;

    @Column(name = "payload_json", columnDefinition = "json", nullable = false)
    private String payloadJson;

    @Column(name = "status", length = 16, nullable = false)
    private String status = "PENDING";

    @Column(name = "priority", nullable = false)
    private int priority = 100;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 8;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt = Instant.now();

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "locked_by", length = 80)
    private String lockedBy;

    @Column(name = "idempotency_key", length = 160, unique = true)
    private String idempotencyKey;

    @Column(name = "dedupe_scope", length = 80)
    private String dedupeScope;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;
}
