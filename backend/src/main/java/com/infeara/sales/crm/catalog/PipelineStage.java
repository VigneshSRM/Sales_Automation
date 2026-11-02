package com.infeara.sales.crm.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Configurable pipeline stage (BR-OPP-001). Terminal semantics remain explicit:
 * {@code terminalType} is NONE, WON or LOST and cannot be removed from the pipeline.
 */
@Entity
@Table(name = "pipeline_stages")
@Getter
@Setter
@NoArgsConstructor
public class PipelineStage {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "code", length = 40, nullable = false)
    private String code;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "order_no", nullable = false)
    private int orderNo;

    @Column(name = "terminal_type", length = 8, nullable = false)
    private String terminalType = "NONE";

    @Column(name = "allow_skip", nullable = false)
    private boolean allowSkip;

    /** Gate requirements evaluated by the transition policy. */
    @Column(name = "required_fields", columnDefinition = "json")
    private String requiredFields;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public boolean isTerminal() {
        return !"NONE".equals(terminalType);
    }

    public boolean isWon() {
        return "WON".equals(terminalType);
    }

    public boolean isLost() {
        return "LOST".equals(terminalType);
    }
}
