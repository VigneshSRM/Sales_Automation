package com.infeara.sales.crm.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Account and contact contracts. */
public final class PartyDtos {

    private PartyDtos() {
    }

    public record CreateAccountRequest(
            @NotBlank @Size(min = 2, max = 200) String name,
            @Size(max = 200) String legalName,
            @Size(max = 400) String website,
            @Size(max = 40) String industryCode,
            @Pattern(regexp = "1-10|11-50|51-200|201-1000|1001-5000|5000\\+") String sizeBand,
            @Size(max = 200) String headquarters,
            @Size(max = 4000) String description,
            @Pattern(regexp = "PROSPECT|CUSTOMER|PARTNER|FORMER_CUSTOMER|DO_NOT_CONTACT")
            String relationshipStatus,
            UUID ownerUserId,
            @Size(max = 40) String sourceCode) {
    }

    public record PatchAccountRequest(
            @Size(min = 2, max = 200) String name,
            @Size(max = 400) String website,
            @Size(max = 40) String industryCode,
            @Size(max = 200) String headquarters,
            @Size(max = 4000) String description,
            @Pattern(regexp = "PROSPECT|CUSTOMER|PARTNER|FORMER_CUSTOMER|DO_NOT_CONTACT")
            String relationshipStatus,
            UUID ownerUserId,
            @NotNull @Min(0) Long rowVersion) {
    }

    public record AccountSummary(
            UUID id,
            String name,
            String domain,
            String industryCode,
            String sizeBand,
            String relationshipStatus,
            String ownerName,
            int contactCount,
            int openOpportunityCount,
            Instant updatedAt,
            long rowVersion) {
    }

    public record AccountDetail(
            UUID id,
            String name,
            String legalName,
            String website,
            String domain,
            String industryCode,
            String sizeBand,
            String headquarters,
            String description,
            String relationshipStatus,
            UUID ownerUserId,
            String ownerName,
            List<ContactSummary> contacts,
            Instant createdAt,
            Instant updatedAt,
            long rowVersion) {
    }

    public record CreateContactRequest(
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 160) String title,
            @Email @Size(max = 254) String email,
            @Size(max = 40) String phone,
            UUID accountId,
            @Pattern(regexp = "EMAIL|PHONE|WHATSAPP") String preferredChannel,
            @Size(max = 64) String timezone,
            @Pattern(regexp = "DECISION_MAKER|INFLUENCER|CHAMPION|BLOCKER|USER|TECHNICAL|PROCUREMENT|UNKNOWN")
            String decisionRole,
            @Pattern(regexp = "UNKNOWN|GRANTED|WITHDRAWN|NOT_REQUIRED") String consentStatus,
            @Size(max = 200) String consentReference,
            @Size(max = 24) String consentSource) {
    }

    public record PatchContactRequest(
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 160) String title,
            @Email @Size(max = 254) String email,
            @Size(max = 40) String phone,
            @Pattern(regexp = "EMAIL|PHONE|WHATSAPP") String preferredChannel,
            @Size(max = 64) String timezone,
            @Pattern(regexp = "DECISION_MAKER|INFLUENCER|CHAMPION|BLOCKER|USER|TECHNICAL|PROCUREMENT|UNKNOWN")
            String decisionRole,
            @Pattern(regexp = "UNKNOWN|GRANTED|WITHDRAWN|NOT_REQUIRED") String consentStatus,
            @NotNull @Min(0) Long rowVersion) {
    }

    public record ContactSummary(
            UUID id,
            String fullName,
            String title,
            String email,
            String phone,
            String decisionRole,
            String consentStatus,
            String suppressionStatus,
            UUID accountId,
            String accountName,
            Instant updatedAt,
            long rowVersion) {
    }

    public record ContactDetail(
            UUID id,
            String firstName,
            String lastName,
            String title,
            String email,
            String phone,
            String preferredChannel,
            String timezone,
            String decisionRole,
            String consentStatus,
            String suppressionStatus,
            UUID accountId,
            String accountName,
            Instant createdAt,
            Instant updatedAt,
            long rowVersion) {
    }
}
