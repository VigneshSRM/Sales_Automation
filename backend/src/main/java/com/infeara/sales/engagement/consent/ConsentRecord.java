package com.infeara.sales.engagement.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Immutable consent capture record (FR-CONSENT-001). */
@Entity
@Table(name = "consent_records")
@Getter
@Setter
@NoArgsConstructor
public class ConsentRecord {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "email_normalized", length = 254, nullable = false)
    private String emailNormalized;

    @Column(name = "contact_id", length = 36)
    private String contactId;

    @Column(name = "lead_id", length = 36)
    private String leadId;

    @Column(name = "consent_status", length = 30, nullable = false)
    private String consentStatus;

    @Column(name = "lawful_basis", length = 60)
    private String lawfulBasis;

    @Column(name = "reference", length = 300)
    private String reference;

    @Column(name = "captured_at", nullable = false, insertable = false, updatable = false)
    private Instant capturedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "captured_by", length = 36)
    private String capturedBy;
}
