package com.infeara.sales.crm.scoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Versioned scoring weights and bands (BR-SCORE-002). Weights must sum to 100;
 * an invalid model is rejected at save time and never becomes active.
 */
@Entity
@Table(name = "scoring_models")
@Getter
@Setter
@NoArgsConstructor
public class ScoringModel {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    /** Array of {code, weight, description} — see docs/database/data-dictionary.md. */
    @Column(name = "factor_json", columnDefinition = "json", nullable = false)
    private String factorJson;

    /** {highPriority:80, qualifiedCandidate:60, nurture:40}. */
    @Column(name = "bands_json", columnDefinition = "json", nullable = false)
    private String bandsJson;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
