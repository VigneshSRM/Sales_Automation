package com.infeara.sales.sales.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Opportunity, pipeline board and forecast contracts (FR-OPP-001..007). */
public final class OpportunityDtos {

    private OpportunityDtos() {
    }

    public record CreateOpportunityRequest(
            UUID accountId,
            UUID leadId,
            UUID primaryContactId,
            UUID serviceId,
            @NotBlank @Size(max = 200) String name,
            @DecimalMin("0") BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            LocalDate expectedCloseDate,
            @Size(max = 500) String nextStep,
            Long expectedRowVersion) {
    }

    public record PatchOpportunityRequest(
            @Size(max = 200) String name,
            @DecimalMin("0") BigDecimal amount,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            LocalDate expectedCloseDate,
            @Size(max = 500) String nextStep,
            @Size(max = 200) String competitor,
            UUID primaryContactId,
            UUID decisionContactId,
            UUID technicalOwnerId,
            UUID ownerUserId,
            Long expectedRowVersion) {
    }

    public record TransitionRequest(
            @NotBlank String targetStageId,
            @Size(max = 1000) String reason,
            Long expectedRowVersion,
            LocalDate closeDate,
            @DecimalMin("0") BigDecimal finalAmount,
            @Pattern(regexp = "[A-Z]{3}") String finalCurrency,
            UUID decisionContactId,
            UUID lossReasonId,
            @Size(max = 2000) String lossNotes) {
    }

    public record StageRef(UUID id, String code, String name, int orderNo, String terminalType) {
    }

    public record OpportunitySummary(UUID id, String name, UUID accountId, String accountName,
                                     StageRef stage, BigDecimal amount, String currency,
                                     BigDecimal weightedValue, int probability, LocalDate expectedCloseDate,
                                     LocalDate closeDate, String ownerName, Instant updatedAt, long rowVersion) {
    }

    public record StageHistoryEntry(String fromStageName, String toStageName, String actorName, String reason,
                                    String ruleVersion, Instant occurredAt) {
    }

    public record OpportunityDetail(OpportunitySummary summary, UUID leadId, UUID primaryContactId,
                                    String primaryContactName, UUID serviceId, String serviceName,
                                    BigDecimal finalAmount, String finalCurrency, String lossReasonName,
                                    String lossNotes, String competitor, String nextStep, UUID ownerUserId,
                                    UUID decisionContactId, List<StageRef> availableStages,
                                    List<StageHistoryEntry> history, String qualificationAssessment,
                                    Instant createdAt) {
    }

    public record BoardColumn(String stageId, String stageCode, String stageName, int orderNo, String terminalType,
                              BigDecimal totalAmount, BigDecimal weightedAmount, String currency,
                              long count, List<OpportunitySummary> opportunities,
                              List<CurrencyTotal> totalsByCurrency) {
    }

    public record CurrencyTotal(String currency, BigDecimal amount, long count) {
    }

    public record BoardView(List<BoardColumn> columns, Instant generatedAt, long openCount,
                            BigDecimal openAmount, BigDecimal weightedAmount, List<CurrencyTotal> byCurrency,
                            List<String> limitations) {
    }

    public record ForecastView(String periodStart, String periodEnd, List<CurrencyTotal> openByCurrency,
                               List<CurrencyTotal> weightedByCurrency, List<CurrencyTotal> wonByCurrency,
                               long wonCount, long lostCount, BigDecimal winRatePercent,
                               BigDecimal averageWonValue, String wonCurrency,
                               List<String> definitions, Instant generatedAt) {
    }
}
