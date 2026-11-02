package com.infeara.sales.engagement.sequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Execution record for one step of one enrollment (unique per enrollment + step). */
@Entity
@Table(name = "sequence_step_runs")
@Getter
@Setter
@NoArgsConstructor
public class SequenceStepRun {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "enrollment_id", length = 36, nullable = false)
    private String enrollmentId;

    @Column(name = "step_no", nullable = false)
    private int stepNo;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "email_message_id", length = 36)
    private String emailMessageId;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "executed_at", nullable = false, insertable = false, updatable = false)
    private Instant executedAt;
}
