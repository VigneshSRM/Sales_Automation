package com.infeara.sales.crm.scoring;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.crm.activity.ActivityRepository;
import com.infeara.sales.crm.lead.Lead;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Qualification scoring (BR-SCORE-001..007). Pure calculation: the same inputs always
 * produce the same score and factor breakdown. Weights and band thresholds come from
 * the versioned {@code scoring_models} row, never from code.
 *
 * <p>Factor fit is derived from recorded CRM evidence only. A human attestation
 * (decision-maker confirmation) is required for the authority factor; AI suggestions
 * may not set it (BR-AI-007).
 */
@Service
public class ScoringService {

    public record FactorResult(String code, int weight, double fit, int points, String evidence) {
    }

    public record ScoreResult(int total, String band, int modelVersion, List<FactorResult> factors) {
    }

    private final ScoringModelRepository models;
    private final ActivityRepository activities;
    private final JsonCodec jsonCodec;
    private final Clock clock;

    public ScoringService(ScoringModelRepository models, ActivityRepository activities, JsonCodec jsonCodec,
                          Clock clock) {
        this.models = models;
        this.activities = activities;
        this.jsonCodec = jsonCodec;
        this.clock = clock;
    }

    public ScoringModel activeModel(String workspaceId) {
        return models.findFirstByWorkspaceIdAndActiveTrueOrderByVersionDesc(workspaceId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_SCORE_MODEL,
                        "No active scoring model is configured for this workspace"));
    }

    public ScoreResult calculate(String workspaceId, Lead lead, boolean decisionMakerConfirmed) {
        ScoringModel model = activeModel(workspaceId);
        List<Map<String, Object>> configured = jsonCodec.readListOfMaps(model.getFactorJson());
        if (configured.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_SCORE_MODEL, "The active scoring model has no factors");
        }
        int weightSum = configured.stream()
                .mapToInt(entry -> ((Number) entry.getOrDefault("weight", 0)).intValue())
                .sum();
        if (weightSum != 100) {
            throw new ApiException(ErrorCode.INVALID_SCORE_MODEL,
                    "Scoring weights must sum to 100 (found " + weightSum + ")");
        }

        long engagementCount = activities.countByWorkspaceIdAndRelatedTypeAndRelatedId(workspaceId, "LEAD",
                lead.getId());
        List<FactorResult> results = new ArrayList<>();
        for (Map<String, Object> factor : configured) {
            String code = String.valueOf(factor.get("code"));
            int weight = ((Number) factor.getOrDefault("weight", 0)).intValue();
            Fit fit = fitFor(code, lead, engagementCount, decisionMakerConfirmed);
            int points = (int) Math.round(weight * fit.value());
            results.add(new FactorResult(code, weight, fit.value(), points, fit.evidence()));
        }
        int total = results.stream().mapToInt(FactorResult::points).sum();
        total = Math.max(0, Math.min(100, total));
        return new ScoreResult(total, bandFor(model, total), model.getVersion(), results);
    }

    public String bandFor(ScoringModel model, int total) {
        Map<String, Object> bands = jsonCodec.readMap(model.getBandsJson());
        int high = intValue(bands, "highPriority", 80);
        int qualified = intValue(bands, "qualifiedCandidate", 60);
        int nurture = intValue(bands, "nurture", 40);
        if (total >= high) {
            return "HIGH_PRIORITY";
        }
        if (total >= qualified) {
            return "QUALIFIED_CANDIDATE";
        }
        if (total >= nurture) {
            return "NURTURE";
        }
        return "LOW_PRIORITY";
    }

    private Fit fitFor(String code, Lead lead, long engagementCount, boolean decisionMakerConfirmed) {
        return switch (code) {
            case "INDUSTRY_SERVICE_FIT" -> {
                boolean industry = lead.getIndustryId() != null;
                boolean service = lead.getPrimaryServiceId() != null;
                double fit = industry && service ? 1.0 : industry || service ? 0.6 : 0.0;
                yield new Fit(fit, "industrySet=" + industry + ", serviceSet=" + service);
            }
            case "COMPANY_SIZE_FIT" -> {
                String band = lead.getEmployeeBand();
                if (band == null) {
                    yield new Fit(0.0, "employeeBand not recorded");
                }
                yield switch (band) {
                    case "201-1000", "1001-5000", "5000+" -> new Fit(1.0, "employeeBand=" + band);
                    case "51-200" -> new Fit(0.6, "employeeBand=" + band);
                    default -> new Fit(0.2, "employeeBand=" + band);
                };
            }
            case "REQUIREMENT_FIT" -> {
                String requirement = lead.getRequirement();
                if (requirement == null || requirement.isBlank()) {
                    yield new Fit(0.0, "requirement not recorded");
                }
                double fit = requirement.length() >= 40 ? 1.0 : requirement.length() >= 10 ? 0.6 : 0.2;
                yield new Fit(fit, "requirementLength=" + requirement.length());
            }
            case "BUDGET_COMPATIBILITY" -> {
                if (lead.getBudgetAmount() == null || lead.getBudgetCurrency() == null) {
                    yield new Fit(0.2, "budget not recorded");
                }
                yield new Fit(1.0, "budget=" + lead.getBudgetAmount().stripTrailingZeros().toPlainString()
                        + " " + lead.getBudgetCurrency());
            }
            case "PROJECT_TIMELINE" -> {
                String timeline = lead.getTimelineCode();
                if (timeline == null) {
                    yield new Fit(0.2, "timeline not recorded");
                }
                yield switch (timeline) {
                    case "IMMEDIATE", "WITHIN_1_MONTH", "WITHIN_3_MONTHS" -> new Fit(1.0, "timeline=" + timeline);
                    case "WITHIN_6_MONTHS" -> new Fit(0.6, "timeline=" + timeline);
                    default -> new Fit(0.2, "timeline=" + timeline);
                };
            }
            case "DECISION_MAKER_ROLE" ->
                    new Fit(decisionMakerConfirmed ? 1.0 : 0.3,
                            "humanAttestation=" + decisionMakerConfirmed);
            case "MEANINGFUL_ENGAGEMENT" -> {
                double fit = engagementCount >= 3 ? 1.0 : engagementCount == 2 ? 0.8
                        : engagementCount == 1 ? 0.5 : 0.1;
                yield new Fit(fit, "recordedActivities=" + engagementCount);
            }
            default -> new Fit(0.0, "unknownFactor:" + code);
        };
    }

    public ScoringModel createModel(String workspaceId, String name, List<Map<String, Object>> factors,
                                    Map<String, Integer> bands, String createdBy) {
        int weightSum = factors.stream()
                .mapToInt(entry -> ((Number) entry.getOrDefault("weight", 0)).intValue())
                .sum();
        if (weightSum != 100) {
            throw new ApiException(ErrorCode.INVALID_SCORE_MODEL,
                    "Scoring weights must sum to 100 (found " + weightSum + ")");
        }
        int nextVersion = models.findFirstByWorkspaceIdOrderByVersionDesc(workspaceId)
                .map(model -> model.getVersion() + 1).orElse(1);
        models.findFirstByWorkspaceIdAndActiveTrueOrderByVersionDesc(workspaceId).ifPresent(previous -> {
            previous.setActive(false);
            models.save(previous);
        });
        ScoringModel model = new ScoringModel();
        model.setId(com.infeara.sales.common.util.Ids.newId());
        model.setWorkspaceId(workspaceId);
        model.setVersion(nextVersion);
        model.setName(name);
        model.setFactorJson(jsonCodec.write(factors));
        model.setBandsJson(jsonCodec.write(bands == null ? Map.of("highPriority", 80,
                "qualifiedCandidate", 60, "nurture", 40) : new LinkedHashMap<>(bands)));
        model.setActive(true);
        model.setCreatedBy(createdBy);
        return models.save(model);
    }

    public String breakdownJson(ScoreResult result) {
        return jsonCodec.write(result.factors().stream()
                .map(factor -> Map.of("code", factor.code(), "weight", factor.weight(), "fit", factor.fit(),
                        "points", factor.points(), "evidence", factor.evidence()))
                .toList());
    }

    public String evidenceJson(ScoreResult result) {
        return jsonCodec.write(result.factors().stream()
                .map(factor -> Map.of("code", factor.code(), "evidence", factor.evidence()))
                .toList());
    }

    public BigDecimal weightedTotal(int total) {
        return BigDecimal.valueOf(total).setScale(4, RoundingMode.HALF_UP);
    }

    public ScoreResult recalculate(String workspaceId, Lead lead, boolean decisionMakerConfirmed) {
        return calculate(workspaceId, lead, decisionMakerConfirmed);
    }

    private int intValue(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private record Fit(double value, String evidence) {
    }
}
