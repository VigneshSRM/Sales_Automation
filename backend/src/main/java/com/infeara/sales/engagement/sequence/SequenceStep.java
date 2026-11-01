package com.infeara.sales.engagement.sequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One send within a version, offset in whole days from enrolment (day 0/2/5/8/12). */
@Entity
@Table(name = "sequence_steps")
@Getter
@Setter
@NoArgsConstructor
public class SequenceStep {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "sequence_version_id", length = 36, nullable = false)
    private String sequenceVersionId;

    @Column(name = "step_no", nullable = false)
    private int stepNo;

    @Column(name = "day_offset", nullable = false)
    private int dayOffset;

    @Column(name = "template_id", length = 36)
    private String templateId;

    @Column(name = "subject_override", length = 300)
    private String subjectOverride;

    @Column(name = "stop_on_reply", nullable = false)
    private boolean stopOnReply = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
