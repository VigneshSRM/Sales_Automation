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
 * A lead's position in a cadence. active_key holds a deterministic value while the
 * enrollment is ACTIVE (lead + sequence) and is NULLed when it stops, so the unique
 * index enforces "one active enrollment per lead per sequence" in the database.
 */
@Entity
@Table(name = "sequence_enrollments")
@Getter
@Setter
@NoArgsConstructor
public class SequenceEnrollment {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "sequence_id", length = 36, nullable = false)
    private String sequenceId;

    @Column(name = "sequence_version_id", length = 36, nullable = false)
    private String sequenceVersionId;

    @Column(name = "lead_id", length = 36, nullable = false)
    private String leadId;

    @Column(name = "contact_id", length = 36)
    private String contactId;

    @Column(name = "smtp_profile_id", length = 36)
    private String smtpProfileId;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "active_key", length = 160)
    private String activeKey;

    @Column(name = "enrolled_by", length = 36)
    private String enrolledBy;

    @Column(name = "enrolled_at", nullable = false, insertable = false, updatable = false)
    private Instant enrolledAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "stop_reason", length = 60)
    private String stopReason;

    @Column(name = "next_run_at")
    private Instant nextRunAt;
}
