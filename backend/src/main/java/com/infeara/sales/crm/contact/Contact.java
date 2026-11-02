package com.infeara.sales.crm.contact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "contacts")
@Getter
@Setter
@NoArgsConstructor
public class Contact {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "account_id", length = 36)
    private String accountId;

    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "full_name_normalized", length = 220, nullable = false)
    private String fullNameNormalized;

    @Column(name = "title", length = 160)
    private String title;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "email_normalized", length = 254)
    private String emailNormalized;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "preferred_channel", length = 20, nullable = false)
    private String preferredChannel = "EMAIL";

    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "decision_role", length = 40)
    private String decisionRole;

    @Column(name = "communication_preferences", columnDefinition = "json")
    private String communicationPreferences;

    @Column(name = "consent_status", length = 24, nullable = false)
    private String consentStatus = "UNKNOWN";

    @Column(name = "suppression_status", length = 24, nullable = false)
    private String suppressionStatus = "CLEAR";

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

    public String fullName() {
        return (firstName + " " + (lastName == null ? "" : lastName)).trim();
    }
}
