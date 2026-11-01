package com.infeara.sales.crm.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "leads")
@Getter
@Setter
@NoArgsConstructor
public class Lead {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "contact_id", length = 36)
    private String contactId;

    @Column(name = "account_id", length = 36)
    private String accountId;

    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "email_normalized", length = 254)
    private String emailNormalized;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    @Column(name = "whatsapp", length = 40)
    private String whatsapp;

    @Column(name = "company_name", length = 200, nullable = false)
    private String companyName;

    @Column(name = "company_name_normalized", length = 200, nullable = false)
    private String companyNameNormalized;

    @Column(name = "domain_normalized", length = 255)
    private String domainNormalized;

    @Column(name = "industry_id", length = 36)
    private String industryId;

    @Column(name = "location", length = 200)
    private String location;

    @Column(name = "employee_band", length = 40)
    private String employeeBand;

    @Column(name = "requirement", length = 2000)
    private String requirement;

    @Column(name = "budget_amount", precision = 19, scale = 4)
    private BigDecimal budgetAmount;

    @Column(name = "budget_currency", length = 3)
    private String budgetCurrency;

    @Column(name = "timeline_code", length = 40)
    private String timelineCode;

    @Column(name = "source_id", length = 36, nullable = false)
    private String sourceId;

    @Column(name = "source_evidence_ref", length = 400)
    private String sourceEvidenceRef;

    @Column(name = "campaign_id", length = 36)
    private String campaignId;

    @Column(name = "primary_service_id", length = 36)
    private String primaryServiceId;

    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private LeadStatus status = LeadStatus.NEW;

    @Column(name = "score")
    private Integer score;

    @Column(name = "score_band", length = 24)
    private String scoreBand;

    @Column(name = "scoring_model_version")
    private Integer scoringModelVersion;

    @Column(name = "qualification_assessment", columnDefinition = "json")
    private String qualificationAssessment;

    @Column(name = "consent_reference", length = 200)
    private String consentReference;

    @Column(name = "merged_into_id", length = 36)
    private String mergedIntoId;

    @Column(name = "converted_at")
    private Instant convertedAt;

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

    public String fullName() {
        return (firstName + " " + (lastName == null ? "" : lastName)).trim();
    }
}
