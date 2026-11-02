package com.infeara.sales.sales.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stage_history")
@Getter
@Setter
@NoArgsConstructor
public class StageHistory {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "opportunity_id", length = 36, nullable = false)
    private String opportunityId;

    @Column(name = "from_stage_id", length = 36)
    private String fromStageId;

    @Column(name = "to_stage_id", length = 36, nullable = false)
    private String toStageId;

    @Column(name = "actor_user_id", length = 36)
    private String actorUserId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "rule_version", length = 40, nullable = false)
    private String ruleVersion;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
