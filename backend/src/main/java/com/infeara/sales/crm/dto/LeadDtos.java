package com.infeara.sales.crm.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lead request/response contracts (spec §12 example create-lead payload). */
public final class LeadDtos {

    private LeadDtos() {
    }

    public record DuplicateCandidate(String candidateType, UUID candidateId, String matchType,
                                     double confidence, String display, String detail) {
    }

    public record CreateLeadRequest(
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Email @Size(max = 254) String email,
            @Size(max = 40) String phone,
            @Size(max = 40) String whatsapp,
            @NotBlank @Size(min = 2, max = 200) String companyName,
            @Size(max = 400) String website,
            @Size(max = 40) String industryCode,
            List<UUID> serviceIds,
            @NotBlank @Size(max = 40) String sourceCode,
            @Size(max = 400) String sourceEvidenceRef,
            @Size(max = 2000) String requirement,
            @Min(0) BigDecimal budgetAmount,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 40) String timelineCode,
            @Size(max = 200) String location,
            @Size(max = 40) String employeeBand,
            UUID assignedUserId,
            @Size(max = 200) String consentReference) {
    }

    public record PatchLeadRequest(
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Email @Size(max = 254) String email,
            @Size(max = 40) String phone,
            @Size(max = 40) String whatsapp,
            @Size(min = 2, max = 200) String companyName,
            @Size(max = 400) String website,
            @Size(max = 40) String industryCode,
            List<UUID> serviceIds,
            @Size(max = 2000) String requirement,
            @Min(0) BigDecimal budgetAmount,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 40) String timelineCode,
            @Size(max = 200) String location,
            @Size(max = 40) String employeeBand,
            @Size(max = 200) String consentReference,
            @NotNull @Min(0) Long rowVersion) {
    }

    public record TransitionRequest(
            @NotBlank @Pattern(regexp = "RESEARCHING|QUALIFIED|CONTACTED|ENGAGED|MEETING_SCHEDULED|"
                    + "DISCOVERY_COMPLETED|CONVERTED|NURTURE|DISQUALIFIED|REOPENED|ARCHIVED") String targetStatus,
            @Size(max = 500) String reason,
            @Size(max = 40) String reasonCode,
            @Min(0) Long rowVersion) {
    }

    public record AssignRequest(@NotNull UUID ownerUserId, @Size(max = 500) String reason,
                                @Min(0) Long rowVersion) {
    }

    public record QualifyRequest(
            boolean decisionMakerConfirmed,
            @Size(max = 500) String evidenceNotes,
            boolean markQualified,
            @Min(0) Long rowVersion) {
    }

    public record OverrideScoreRequest(
            @NotNull @Min(0) @Max(100) Integer score,
            @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record MergeRequest(
            @NotNull UUID sourceLeadId,
            @NotNull UUID targetLeadId,
            Map<String, String> fieldDecisions,
            @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record BulkAssignRequest(@NotNull List<UUID> leadIds, @NotNull UUID ownerUserId,
                                    @Size(max = 500) String reason) {
    }

    public record BulkAssignResult(int updated, List<BulkFailure> failures) {
    }

    public record BulkFailure(UUID leadId, String code, String message) {
    }

    public record LeadSummary(
            UUID id,
            String fullName,
            String companyName,
            String email,
            String phone,
            String status,
            Integer score,
            String scoreBand,
            String ownerName,
            UUID ownerUserId,
            String sourceName,
            String primaryServiceName,
            Instant updatedAt,
            long rowVersion) {
    }

    public record ScoreFactor(String code, int weight, double fit, int points, String evidence) {
    }

    public record ScoreView(int total, String band, int modelVersion, List<ScoreFactor> factors,
                            Instant calculatedAt) {
    }

    public record LeadDetail(
            UUID id,
            String firstName,
            String lastName,
            String email,
            String phone,
            String whatsapp,
            String companyName,
            String website,
            String domain,
            String industryCode,
            List<UUID> serviceIds,
            String sourceCode,
            String sourceEvidenceRef,
            String requirement,
            BigDecimal budgetAmount,
            String currency,
            String timelineCode,
            String location,
            String employeeBand,
            String status,
            Integer score,
            String scoreBand,
            ScoreView latestScore,
            UUID ownerUserId,
            String ownerName,
            String consentReference,
            List<String> allowedTransitions,
            List<DuplicateCandidate> duplicates,
            Instant createdAt,
            Instant updatedAt,
            long rowVersion) {
    }

    public record LeadLookup(UUID id, String label) {
    }
}
