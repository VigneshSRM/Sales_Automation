package com.infeara.sales.engagement.sequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A reusable follow-up cadence (FR-SEQUENCE-001). */
@Entity
@Table(name = "email_sequences")
@Getter
@Setter
@NoArgsConstructor
public class EmailSequence {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "name", length = 160, nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "DRAFT";

    @Column(name = "active_version")
    private Integer activeVersion;

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
