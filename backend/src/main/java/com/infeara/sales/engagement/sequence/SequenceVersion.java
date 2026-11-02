package com.infeara.sales.engagement.sequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable snapshot of a cadence. Enrollments bind to a version, so editing a
 * sequence never changes the cadence of an in-flight enrollment (BR-EMAIL-003).
 */
@Entity
@Table(name = "sequence_versions")
@Getter
@Setter
@NoArgsConstructor
public class SequenceVersion {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "sequence_id", length = 36, nullable = false)
    private String sequenceId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "DRAFT";

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
