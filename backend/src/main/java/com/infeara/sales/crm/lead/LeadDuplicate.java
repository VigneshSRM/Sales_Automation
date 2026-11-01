package com.infeara.sales.crm.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "lead_duplicates")
@Getter
@Setter
@NoArgsConstructor
public class LeadDuplicate {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "lead_id", length = 36, nullable = false)
    private String leadId;

    @Column(name = "candidate_type", length = 20, nullable = false)
    private String candidateType;

    @Column(name = "candidate_id", length = 36, nullable = false)
    private String candidateId;

    @Column(name = "match_type", length = 40, nullable = false)
    private String matchType;

    @Column(name = "confidence", precision = 5, scale = 4, nullable = false)
    private BigDecimal confidence;

    @Column(name = "resolution", length = 24, nullable = false)
    private String resolution = "PENDING";

    @Column(name = "resolved_by", length = 36)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
