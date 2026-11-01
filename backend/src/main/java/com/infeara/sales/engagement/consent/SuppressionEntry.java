package com.infeara.sales.engagement.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Workspace-wide suppression list entry (FR-SUPPRESSION-001, BR-EMAIL-006). */
@Entity
@Table(name = "suppression_entries")
@Getter
@Setter
@NoArgsConstructor
public class SuppressionEntry {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "email_normalized", length = 254, nullable = false)
    private String emailNormalized;

    @Column(name = "reason", length = 40, nullable = false)
    private String reason;

    @Column(name = "source", length = 60)
    private String source;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
