package com.infeara.sales.crm.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Immutable record of a merge decision (BR-DUP-005). */
@Entity
@Table(name = "merge_audits")
@Getter
@Setter
@NoArgsConstructor
public class MergeAudit {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "entity_type", length = 30, nullable = false)
    private String entityType;

    @Column(name = "target_id", length = 36, nullable = false)
    private String targetId;

    @Column(name = "source_ids_json", columnDefinition = "json", nullable = false)
    private String sourceIdsJson;

    @Column(name = "field_decisions_json", columnDefinition = "json", nullable = false)
    private String fieldDecisionsJson;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "actor_user_id", length = 36)
    private String actorUserId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
