package com.infeara.sales.crm.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "lead_score_history")
@Getter
@Setter
@NoArgsConstructor
public class LeadScoreHistory {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "lead_id", length = 36, nullable = false)
    private String leadId;

    @Column(name = "model_version", nullable = false)
    private int modelVersion;

    @Column(name = "total", nullable = false)
    private int total;

    @Column(name = "band", length = 24, nullable = false)
    private String band;

    @Column(name = "factor_json", columnDefinition = "json", nullable = false)
    private String factorJson;

    @Column(name = "evidence_json", columnDefinition = "json")
    private String evidenceJson;

    @Column(name = "calculation_source", length = 24, nullable = false)
    private String calculationSource = "SYSTEM";

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "overridden_by", length = 36)
    private String overriddenBy;

    @Column(name = "calculated_at", nullable = false, insertable = false, updatable = false)
    private Instant calculatedAt;
}
