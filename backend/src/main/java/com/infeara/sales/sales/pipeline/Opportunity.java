package com.infeara.sales.sales.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "opportunities")
@Getter
@Setter
@NoArgsConstructor
public class Opportunity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "account_id", length = 36, nullable = false)
    private String accountId;

    @Column(name = "primary_contact_id", length = 36)
    private String primaryContactId;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "service_id", length = 36)
    private String serviceId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "stage_id", length = 36, nullable = false)
    private String stageId;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "probability", nullable = false)
    private int probability;

    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    @Column(name = "close_date")
    private LocalDate closeDate;

    @Column(name = "final_amount", precision = 19, scale = 4)
    private BigDecimal finalAmount;

    @Column(name = "final_currency", length = 3)
    private String finalCurrency;

    @Column(name = "loss_reason_id", length = 36)
    private String lossReasonId;

    @Column(name = "loss_notes", length = 2000)
    private String lossNotes;

    @Column(name = "competitor", length = 200)
    private String competitor;

    @Column(name = "qualification_assessment", columnDefinition = "json")
    private String qualificationAssessment;

    @Column(name = "decision_contact_id", length = 36)
    private String decisionContactId;

    @Column(name = "technical_owner_id", length = 36)
    private String technicalOwnerId;

    @Column(name = "next_step", length = 500)
    private String nextStep;

    @Column(name = "open_objections", columnDefinition = "json")
    private String openObjections;

    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    /** weighted_value = amount × probability / 100 (BR-OPP-010). */
    public BigDecimal weightedValue() {
        return amount == null ? BigDecimal.ZERO
                : amount.multiply(BigDecimal.valueOf(probability)).divide(BigDecimal.valueOf(100),
                        4, java.math.RoundingMode.HALF_UP);
    }
}
