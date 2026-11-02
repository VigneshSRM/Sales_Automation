package com.infeara.sales.sales.pipeline;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.automation.outbox.OutboxPublisher;
import com.infeara.sales.common.api.ApiError;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.paging.PageQuery;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.crm.account.Account;
import com.infeara.sales.crm.account.AccountRepository;
import com.infeara.sales.crm.activity.ActivityRecorder;
import com.infeara.sales.crm.catalog.PipelineStage;
import com.infeara.sales.crm.catalog.PipelineStageRepository;
import com.infeara.sales.crm.catalog.LossReason;
import com.infeara.sales.crm.catalog.LossReasonRepository;
import com.infeara.sales.crm.catalog.ServiceOffering;
import com.infeara.sales.crm.catalog.ServiceOfferingRepository;
import com.infeara.sales.crm.contact.Contact;
import com.infeara.sales.crm.contact.ContactRepository;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.sales.dto.OpportunityDtos;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opportunity lifecycle, pipeline board and forecast (FR-OPP-001..007, BR-OPP-001..007).
 *
 * Stage gates are evaluated here, server-side, and every transition writes an immutable
 * {@link StageHistory} row carrying the policy version that decided it, so a closed deal can
 * always be explained after the fact.
 */
@Service
public class OpportunityService {

    /** Policy version recorded on every stage-history row (BR-OPP-002). */
    public static final String RULE_VERSION = "pipeline-policy-v1";

    private static final Map<String, Integer> PROBABILITY_BY_STAGE = Map.of(
            "NEW", 10,
            "QUALIFICATION", 20,
            "DISCOVERY", 35,
            "TECHNICAL_ASSESSMENT", 50,
            "PROPOSAL_PREPARATION", 60,
            "PROPOSAL_SENT", 70,
            "NEGOTIATION", 85,
            "CLOSED_WON", 100,
            "CLOSED_LOST", 0);

    private static final Set<String> SORTABLE = Set.of("name", "amount", "expectedCloseDate", "updatedAt", "stage");

    private final OpportunityRepository opportunities;
    private final StageHistoryRepository stageHistory;
    private final PipelineStageRepository stages;
    private final AccountRepository accounts;
    private final ContactRepository contacts;
    private final UserRepository users;
    private final LossReasonRepository lossReasons;
    private final ServiceOfferingRepository services;
    private final OpportunityGateQueries gateQueries;
    private final CapabilityEvaluator capabilities;
    private final ScopeGuard scopeGuard;
    private final AuditRecorder audit;
    private final ActivityRecorder activityRecorder;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public OpportunityService(OpportunityRepository opportunities, StageHistoryRepository stageHistory,
                              PipelineStageRepository stages, AccountRepository accounts,
                              ContactRepository contacts, UserRepository users, LossReasonRepository lossReasons,
                              ServiceOfferingRepository services, OpportunityGateQueries gateQueries,
                              CapabilityEvaluator capabilities, ScopeGuard scopeGuard, AuditRecorder audit,
                              ActivityRecorder activityRecorder, OutboxPublisher outbox, Clock clock) {
        this.opportunities = opportunities;
        this.stageHistory = stageHistory;
        this.stages = stages;
        this.accounts = accounts;
        this.contacts = contacts;
        this.users = users;
        this.lossReasons = lossReasons;
        this.services = services;
        this.gateQueries = gateQueries;
        this.capabilities = capabilities;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.activityRecorder = activityRecorder;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public PageResponse<OpportunityDtos.OpportunitySummary> list(CurrentPrincipal principal, PageQuery query) {
        capabilities.require(principal, "opportunities.read");
        Map<String, String> filters = query.filters();
        Specification<Opportunity> spec = (root, cq, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspaceId"), principal.workspaceId()));
            predicates.add(cb.isNull(root.get("deletedAt")));
            applyFilter(filters, "stageId", cb, root, "stageId", predicates);
            applyFilter(filters, "accountId", cb, root, "accountId", predicates);
            applyFilter(filters, "ownerUserId", cb, root, "ownerUserId", predicates);
            applyFilter(filters, "currency", cb, root, "currency", predicates);
            String term = filters.get("q");
            if (term != null && !term.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + term.toLowerCase(Locale.ROOT) + "%"));
            }
            if (!"all".equalsIgnoreCase(principal.scopeType())) {
                predicates.add(cb.equal(root.get("ownerUserId"), principal.userId()));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Sort sort = Sort.by(query.descending() ? Sort.Direction.DESC : Sort.Direction.ASC,
                resolveSort(query.sortBy()));
        Page<Opportunity> page = opportunities.findAll(spec,
                PageRequest.of(query.page(), query.size(), sort));
        List<OpportunityDtos.OpportunitySummary> items = page.getContent().stream()
                .map(this::toSummary)
                .toList();
        return PageResponse.of(items, query.page(), query.size(), page.getTotalElements(),
                resolveSort(query.sortBy()) + (query.descending() ? ":desc" : ":asc"), query.appliedFilters());
    }

    /** Slice returned by the legacy controller signature (kept for the list endpoint). */
    public record PageSlice(List<OpportunityDtos.OpportunitySummary> items, long total) {
    }

    /**
     * Convenience entry point for the REST controller: maps individual query parameters onto
     * {@link PageQuery} so paging, sorting and filter allow-listing stay in one place.
     */
    @Transactional(readOnly = true)
    public PageSlice list(CurrentPrincipal principal, String stageId, String accountId, String ownerUserId,
                          String term, int page, int size, String sort, String direction) {
        Map<String, String> filters = new HashMap<>();
        if (stageId != null && !stageId.isBlank()) {
            filters.put("stageId", stageId);
        }
        if (accountId != null && !accountId.isBlank()) {
            filters.put("accountId", accountId);
        }
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            filters.put("ownerUserId", ownerUserId);
        }
        if (term != null && !term.isBlank()) {
            filters.put("q", term);
        }
        PageQuery query = PageQuery.of(page, size, sort, direction, filters,
                Set.of("stageId", "accountId", "ownerUserId", "q", "currency"));
        PageResponse<OpportunityDtos.OpportunitySummary> result = list(principal, query);
        return new PageSlice(result.items(), result.totalElements());
    }

    private String resolveSort(String requested) {
        if (requested == null) {
            return "updatedAt";
        }
        String field = requested.split(",")[0].trim();
        if (!SORTABLE.contains(field)) {
            throw new ApiException(ErrorCode.INVALID_SORT_FIELD, "Unsupported sort field: " + field);
        }
        return "name".equals(field) ? "name" : field;
    }

    private void applyFilter(Map<String, String> filters, String key, jakarta.persistence.criteria.CriteriaBuilder cb,
                             jakarta.persistence.criteria.Root<Opportunity> root, String property,
                             List<jakarta.persistence.criteria.Predicate> predicates) {
        String value = filters.get(key);
        if (value != null && !value.isBlank()) {
            predicates.add(cb.equal(root.get(property), value));
        }
    }

    @Transactional(readOnly = true)
    public OpportunityDtos.OpportunityDetail get(CurrentPrincipal principal, UUID opportunityId) {
        capabilities.require(principal, "opportunities.read");
        Opportunity opportunity = require(principal, Ids.of(opportunityId));
        return toDetail(principal, opportunity);
    }

    @Transactional(readOnly = true)
    public OpportunityDtos.BoardView board(CurrentPrincipal principal) {
        capabilities.require(principal, "opportunities.read");
        List<PipelineStage> allStages = stages.findByWorkspaceIdAndActiveTrueOrderByOrderNoAsc(principal.workspaceId());
        List<Opportunity> all = opportunities.findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                principal.workspaceId());
        Map<String, List<Opportunity>> byStage = all.stream()
                .collect(Collectors.groupingBy(Opportunity::getStageId, LinkedHashMap::new, Collectors.toList()));

        List<OpportunityDtos.BoardColumn> columns = new ArrayList<>();
        long openCount = 0;
        BigDecimal openAmount = BigDecimal.ZERO;
        BigDecimal weightedAmount = BigDecimal.ZERO;
        Map<String, BigDecimal> openByCurrency = new LinkedHashMap<>();
        Map<String, Long> countByCurrency = new LinkedHashMap<>();

        for (PipelineStage stage : allStages) {
            List<Opportunity> stageDeals = byStage.getOrDefault(stage.getId(), List.of());
            Map<String, BigDecimal> totalsByCurrency = new LinkedHashMap<>();
            Map<String, Long> countsByCurrency = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal weighted = BigDecimal.ZERO;
            List<OpportunityDtos.OpportunitySummary> summaries = new ArrayList<>();
            for (Opportunity deal : stageDeals) {
                summaries.add(toSummary(deal));
                BigDecimal amount = Optional.ofNullable(deal.getAmount()).orElse(BigDecimal.ZERO);
                BigDecimal dealWeighted = weightedValue(deal);
                total = total.add(amount);
                weighted = weighted.add(dealWeighted);
                totalsByCurrency.merge(deal.getCurrency(), amount, BigDecimal::add);
                countsByCurrency.merge(deal.getCurrency(), 1L, Long::sum);
                if (deal.getCloseDate() == null) {
                    openCount++;
                    openAmount = openAmount.add(amount);
                    weightedAmount = weightedAmount.add(dealWeighted);
                    openByCurrency.merge(deal.getCurrency(), amount, BigDecimal::add);
                    countByCurrency.merge(deal.getCurrency(), 1L, Long::sum);
                }
            }
            String columnCurrency = totalsByCurrency.size() == 1 ? totalsByCurrency.keySet().iterator().next()
                    : (totalsByCurrency.isEmpty() ? "INR" : "MULTI");
            List<OpportunityDtos.CurrencyTotal> currencyTotals = totalsByCurrency.entrySet().stream()
                    .map(entry -> new OpportunityDtos.CurrencyTotal(entry.getKey(), entry.getValue(),
                            countsByCurrency.getOrDefault(entry.getKey(), 0L)))
                    .toList();
            columns.add(new OpportunityDtos.BoardColumn(stage.getId(), stage.getCode(), stage.getName(),
                    stage.getOrderNo(), stage.getTerminalType(), total, weighted, columnCurrency,
                    stageDeals.size(), summaries, currencyTotals));
        }

        List<OpportunityDtos.CurrencyTotal> byCurrency = openByCurrency.entrySet().stream()
                .map(entry -> new OpportunityDtos.CurrencyTotal(entry.getKey(), entry.getValue(),
                        countByCurrency.getOrDefault(entry.getKey(), 0L)))
                .toList();

        return new OpportunityDtos.BoardView(columns, clock.instant(), openCount, openAmount, weightedAmount,
                byCurrency, List.of("Board amounts are grouped per currency; the platform performs no FX conversion.",
                        "Weighted value uses the stage probability policy " + RULE_VERSION + "."));
    }

    @Transactional(readOnly = true)
    public OpportunityDtos.ForecastView forecast(CurrentPrincipal principal, LocalDate from, LocalDate to) {
        capabilities.require(principal, "reports.read");
        LocalDate start = from == null ? LocalDate.now(clock).withDayOfMonth(1) : from;
        LocalDate end = to == null ? start.plusMonths(1).minusDays(1) : to;

        List<Object[]> openRows = opportunities.openPipelineByCurrency(principal.workspaceId());
        List<Object[]> wonRows = opportunities.wonByCurrencyInPeriod(principal.workspaceId(), start, end);
        List<Object[]> lostRows = opportunities.lostByCurrencyInPeriod(principal.workspaceId(), start, end);

        List<OpportunityDtos.CurrencyTotal> open = new ArrayList<>();
        List<OpportunityDtos.CurrencyTotal> weighted = new ArrayList<>();
        for (Object[] row : openRows) {
            open.add(new OpportunityDtos.CurrencyTotal((String) row[0], toDecimal(row[1]), toLong(row[3])));
            weighted.add(new OpportunityDtos.CurrencyTotal((String) row[0], toDecimal(row[2]), toLong(row[3])));
        }
        List<OpportunityDtos.CurrencyTotal> won = wonRows.stream()
                .map(row -> new OpportunityDtos.CurrencyTotal((String) row[0], toDecimal(row[1]), toLong(row[2])))
                .toList();
        List<OpportunityDtos.CurrencyTotal> lost = lostRows.stream()
                .map(row -> new OpportunityDtos.CurrencyTotal((String) row[0], toDecimal(row[1]), toLong(row[2])))
                .toList();

        long wonCount = won.stream().mapToLong(OpportunityDtos.CurrencyTotal::count).sum();
        long lostCount = lost.stream().mapToLong(OpportunityDtos.CurrencyTotal::count).sum();
        BigDecimal winRate = wonCount + lostCount == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(wonCount * 100L)
                        .divide(BigDecimal.valueOf(wonCount + lostCount), 1, RoundingMode.HALF_UP);
        BigDecimal avgWon = BigDecimal.ZERO;
        String wonCurrency = null;
        if (!won.isEmpty()) {
            OpportunityDtos.CurrencyTotal primary = won.get(0);
            wonCurrency = primary.currency();
            avgWon = primary.count() == 0 ? BigDecimal.ZERO
                    : primary.amount().divide(BigDecimal.valueOf(primary.count()), 2, RoundingMode.HALF_UP);
        }

        return new OpportunityDtos.ForecastView(start.toString(), end.toString(), open, weighted, won, wonCount,
                lostCount, winRate, avgWon, wonCurrency,
                List.of("Open pipeline = sum of amounts for deals without a close date.",
                        "Weighted pipeline = Σ(amount × stage probability ÷ 100).",
                        "Win rate = won ÷ (won + lost) × 100 for deals closed inside the period.",
                        "Amounts are never summed across currencies (AMB-04)."),
                clock.instant());
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public OpportunityDtos.OpportunityDetail create(CurrentPrincipal principal,
                                                    OpportunityDtos.CreateOpportunityRequest request) {
        capabilities.require(principal, "opportunities.create");
        String workspaceId = principal.workspaceId();
        PipelineStage firstStage = stages.findByWorkspaceIdAndActiveTrueOrderByOrderNoAsc(workspaceId).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "No pipeline stages are configured for this workspace"));

        String accountId = Ids.of(request.accountId());
        if (accountId != null) {
            accounts.findByIdAndWorkspaceIdAndDeletedAtIsNull(accountId, workspaceId)
                    .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
        }
        String primaryContactId = Ids.of(request.primaryContactId());
        if (primaryContactId != null) {
            contacts.findByIdAndWorkspaceIdAndDeletedAtIsNull(primaryContactId, workspaceId)
                    .orElseThrow(() -> new ApiException(ErrorCode.CONTACT_NOT_FOUND, "Contact not found"));
        }
        String serviceId = Ids.of(request.serviceId());
        if (serviceId != null) {
            services.findById(serviceId)
                    .filter(service -> service.getWorkspaceId().equals(workspaceId))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Service offering not found"));
        }

        Opportunity opportunity = new Opportunity();
        opportunity.setId(Ids.newId());
        opportunity.setWorkspaceId(workspaceId);
        opportunity.setAccountId(accountId);
        opportunity.setLeadId(Ids.of(request.leadId()));
        opportunity.setPrimaryContactId(primaryContactId);
        opportunity.setServiceId(serviceId);
        opportunity.setName(request.name().trim());
        opportunity.setStageId(firstStage.getId());
        opportunity.setAmount(request.amount() == null ? BigDecimal.ZERO : request.amount());
        opportunity.setCurrency(request.currency());
        opportunity.setProbability(probabilityFor(firstStage));
        opportunity.setExpectedCloseDate(request.expectedCloseDate());
        opportunity.setNextStep(request.nextStep());
        opportunity.setOwnerUserId(principal.userId());
        opportunity.setCreatedBy(principal.userId());
        opportunity.setUpdatedBy(principal.userId());
        opportunity.setCreatedAt(clock.instant());
        opportunity.setUpdatedAt(clock.instant());
        Opportunity saved = opportunities.save(opportunity);

        recordHistory(saved, null, firstStage, principal.userId(), "Created at " + firstStage.getName());
        audit.record(principal, "OPPORTUNITY_CREATED", "OPPORTUNITY", saved.getId(), null,
                Map.of("name", saved.getName(), "amount", saved.getAmount().toPlainString(),
                        "currency", saved.getCurrency(), "stage", firstStage.getCode()),
                Map.of("ruleVersion", RULE_VERSION));
        outbox.publish(workspaceId, "OPPORTUNITY", saved.getId(), "OPPORTUNITY_CREATED",
                Map.of("opportunityId", saved.getId(), "stageId", firstStage.getId(),
                        "accountId", accountId == null ? "" : accountId));
        return toDetail(principal, saved);
    }

    @Transactional
    public OpportunityDtos.OpportunityDetail update(CurrentPrincipal principal, UUID opportunityId,
                                                    OpportunityDtos.PatchOpportunityRequest request) {
        capabilities.require(principal, "opportunities.update");
        Opportunity opportunity = require(principal, Ids.of(opportunityId));
        checkVersion(opportunity, request.expectedRowVersion());
        Map<String, Object> before = snapshot(opportunity);

        if (request.name() != null) {
            opportunity.setName(request.name().trim());
        }
        if (request.amount() != null) {
            opportunity.setAmount(request.amount());
        }
        if (request.currency() != null) {
            opportunity.setCurrency(request.currency());
        }
        if (request.expectedCloseDate() != null) {
            opportunity.setExpectedCloseDate(request.expectedCloseDate());
        }
        if (request.nextStep() != null) {
            opportunity.setNextStep(request.nextStep());
        }
        if (request.competitor() != null) {
            opportunity.setCompetitor(request.competitor());
        }
        if (request.primaryContactId() != null) {
            String contactId = Ids.of(request.primaryContactId());
            contacts.findByIdAndWorkspaceIdAndDeletedAtIsNull(contactId, principal.workspaceId())
                    .orElseThrow(() -> new ApiException(ErrorCode.CONTACT_NOT_FOUND, "Contact not found"));
            opportunity.setPrimaryContactId(contactId);
        }
        if (request.decisionContactId() != null) {
            String contactId = Ids.of(request.decisionContactId());
            contacts.findByIdAndWorkspaceIdAndDeletedAtIsNull(contactId, principal.workspaceId())
                    .orElseThrow(() -> new ApiException(ErrorCode.CONTACT_NOT_FOUND, "Decision contact not found"));
            opportunity.setDecisionContactId(contactId);
        }
        if (request.technicalOwnerId() != null) {
            String userId = Ids.of(request.technicalOwnerId());
            requireUser(principal.workspaceId(), userId);
            opportunity.setTechnicalOwnerId(userId);
        }
        if (request.ownerUserId() != null) {
            capabilities.require(principal, "opportunities.assign");
            String userId = Ids.of(request.ownerUserId());
            requireUser(principal.workspaceId(), userId);
            opportunity.setOwnerUserId(userId);
        }
        opportunity.setUpdatedBy(principal.userId());
        opportunity.setUpdatedAt(clock.instant());
        Opportunity saved = opportunities.save(opportunity);

        audit.record(principal, "OPPORTUNITY_UPDATED", "OPPORTUNITY", saved.getId(), before, snapshot(saved), Map.of());
        return toDetail(principal, saved);
    }

    @Transactional
    public OpportunityDtos.OpportunityDetail transition(CurrentPrincipal principal, UUID opportunityId,
                                                        OpportunityDtos.TransitionRequest request) {
        capabilities.require(principal, "opportunities.transition");
        Opportunity opportunity = require(principal, Ids.of(opportunityId));
        checkVersion(opportunity, request.expectedRowVersion());

        PipelineStage target = stages.findByIdAndWorkspaceId(Ids.of(Ids.parse(request.targetStageId())),
                        principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Target stage not found"));
        PipelineStage current = stages.findByIdAndWorkspaceId(opportunity.getStageId(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Current stage is no longer configured"));

        if (target.getId().equals(current.getId())) {
            throw new ApiException(ErrorCode.INVALID_STAGE_TRANSITION, "The opportunity is already at this stage");
        }
        if (!target.isActive()) {
            throw new ApiException(ErrorCode.INVALID_STAGE_TRANSITION, "The target stage is inactive");
        }

        boolean backwards = target.getOrderNo() < current.getOrderNo();
        boolean skipping = target.getOrderNo() > current.getOrderNo() + 1;
        if (backwards && (request.reason() == null || request.reason().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A reason is required to move a deal backwards");
        }
        if (skipping && !current.isAllowSkip()) {
            throw new ApiException(ErrorCode.INVALID_STAGE_TRANSITION,
                    "Stage " + current.getName() + " does not allow skipping stages");
        }

        Map<String, Object> before = snapshot(opportunity);
        List<ApiError.FieldErrorItem> gateFailures = new ArrayList<>();

        if ("WON".equals(target.getTerminalType())) {
            capabilities.require(principal, "opportunities.close");
            if (request.closeDate() == null) {
                gateFailures.add(new ApiError.FieldErrorItem("closeDate", "REQUIRED", "A close date is required"));
            }
            if (request.finalAmount() == null || request.finalAmount().signum() < 0) {
                gateFailures.add(new ApiError.FieldErrorItem("finalAmount", "REQUIRED", "The final amount is required"));
            }
            if (request.finalCurrency() == null) {
                gateFailures.add(new ApiError.FieldErrorItem("finalCurrency", "REQUIRED", "The final currency is required"));
            }
            if (request.decisionContactId() == null && opportunity.getDecisionContactId() == null) {
                gateFailures.add(new ApiError.FieldErrorItem("decisionContactId", "REQUIRED", "A decision-maker contact is required"));
            }
            if (!gateFailures.isEmpty()) {
                throw new ApiException(ErrorCode.STAGE_GATE_FAILED,
                        "The stage gate for " + target.getName() + " is not satisfied", gateFailures);
            }
            opportunity.setCloseDate(request.closeDate());
            opportunity.setFinalAmount(request.finalAmount());
            opportunity.setFinalCurrency(request.finalCurrency());
            if (request.decisionContactId() != null) {
                opportunity.setDecisionContactId(Ids.of(request.decisionContactId()));
            }
        } else if ("LOST".equals(target.getTerminalType())) {
            capabilities.require(principal, "opportunities.close");
            if (request.lossReasonId() == null) {
                gateFailures.add(new ApiError.FieldErrorItem("lossReasonId", "REQUIRED", "A loss reason is required"));
            }
            if (!gateFailures.isEmpty()) {
                throw new ApiException(ErrorCode.STAGE_GATE_FAILED,
                        "The stage gate for " + target.getName() + " is not satisfied", gateFailures);
            }
            LossReason lossReason = lossReasons.findById(Ids.of(request.lossReasonId()))
                    .filter(reason -> reason.getWorkspaceId().equals(principal.workspaceId()))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Loss reason not found"));
            opportunity.setLossReasonId(lossReason.getId());
            opportunity.setLossNotes(request.lossNotes());
            opportunity.setCloseDate(Optional.ofNullable(request.closeDate()).orElse(LocalDate.now(clock)));
        } else {
            // Re-opening a terminal deal clears the close evidence.
            opportunity.setCloseDate(null);
            opportunity.setFinalAmount(null);
            opportunity.setFinalCurrency(null);
            if ("DISCOVERY".equals(target.getCode())
                    && !gateQueries.hasDiscoveryInteraction(opportunity.getId(), principal.workspaceId())) {
                gateFailures.add(new ApiError.FieldErrorItem("discovery", "EVIDENCE_REQUIRED", "Record a discovery meeting, call or note first"));
            }
            if ("PROPOSAL_SENT".equals(target.getCode())) {
                if (!gateQueries.hasApprovedProposal(opportunity.getId())) {
                    gateFailures.add(new ApiError.FieldErrorItem("proposal", "EVIDENCE_REQUIRED", "An approved proposal version is required"));
                } else if (!gateQueries.hasProposalSendEvidence(opportunity.getId())) {
                    gateFailures.add(new ApiError.FieldErrorItem("proposalSend", "EVIDENCE_REQUIRED", "Proposal send evidence is required"));
                }
            }
            if (!gateFailures.isEmpty()) {
                throw new ApiException(ErrorCode.STAGE_GATE_FAILED,
                        "The stage gate for " + target.getName() + " is not satisfied", gateFailures);
            }
        }

        opportunity.setStageId(target.getId());
        opportunity.setProbability(probabilityFor(target));
        opportunity.setUpdatedBy(principal.userId());
        opportunity.setUpdatedAt(clock.instant());
        Opportunity saved = opportunities.save(opportunity);

        recordHistory(saved, current, target, principal.userId(),
                request.reason() == null ? "Moved to " + target.getName() : request.reason());
        audit.record(principal, "OPPORTUNITY_STAGE_CHANGED", "OPPORTUNITY", saved.getId(), before,
                snapshot(saved), Map.of("ruleVersion", RULE_VERSION,
                        "fromStage", current.getCode(), "toStage", target.getCode()));
        outbox.publish(principal.workspaceId(), "OPPORTUNITY", saved.getId(), "OPPORTUNITY_STAGE_CHANGED",
                Map.of("opportunityId", saved.getId(), "fromStage", current.getCode(),
                        "toStage", target.getCode(), "terminalType", target.getTerminalType()));
        activityRecorder.record(principal, "OPPORTUNITY", saved.getId(), "NOTE",
                "Stage changed: " + current.getName() + " → " + target.getName(),
                request.reason(), Map.of("stageCode", target.getCode()));
        return toDetail(principal, saved);
    }

    // ------------------------------------------------------------------ helpers

    private Opportunity require(CurrentPrincipal principal, String opportunityId) {
        Opportunity opportunity = opportunities
                .findByIdAndWorkspaceIdAndDeletedAtIsNull(opportunityId, principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.OPPORTUNITY_NOT_FOUND, "Opportunity not found"));
        scopeGuard.requireAccess(principal, opportunity.getOwnerUserId(), "opportunity");
        return opportunity;
    }

    private void requireUser(String workspaceId, String userId) {
        users.findByIdAndWorkspaceIdAndDeletedAtIsNull(userId, workspaceId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found in this workspace"));
    }

    private void checkVersion(Opportunity opportunity, Long expected) {
        if (expected != null && expected != opportunity.getRowVersion()) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "This record changed since you loaded it. Refresh and try again.");
        }
    }

    private void recordHistory(Opportunity opportunity, PipelineStage from, PipelineStage to, String actorId,
                               String reason) {
        StageHistory history = new StageHistory();
        history.setId(Ids.newId());
        history.setWorkspaceId(opportunity.getWorkspaceId());
        history.setOpportunityId(opportunity.getId());
        history.setFromStageId(from == null ? null : from.getId());
        history.setToStageId(to.getId());
        history.setActorUserId(actorId);
        history.setReason(reason);
        history.setRuleVersion(RULE_VERSION);
        history.setCreatedAt(clock.instant());
        stageHistory.save(history);
    }

    private BigDecimal weightedValue(Opportunity opportunity) {
        BigDecimal amount = Optional.ofNullable(opportunity.getAmount()).orElse(BigDecimal.ZERO);
        return amount.multiply(BigDecimal.valueOf(opportunity.getProbability()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private int probabilityFor(PipelineStage stage) {
        Integer probability = PROBABILITY_BY_STAGE.get(stage.getCode());
        return probability != null ? probability : 10;
    }

    private Map<String, Object> snapshot(Opportunity opportunity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", opportunity.getName());
        map.put("stageId", opportunity.getStageId());
        map.put("amount", opportunity.getAmount() == null ? null : opportunity.getAmount().toPlainString());
        map.put("currency", opportunity.getCurrency());
        map.put("probability", opportunity.getProbability());
        map.put("closeDate", opportunity.getCloseDate() == null ? null : opportunity.getCloseDate().toString());
        map.put("finalAmount", opportunity.getFinalAmount() == null ? null : opportunity.getFinalAmount().toPlainString());
        map.put("ownerUserId", opportunity.getOwnerUserId());
        return map;
    }

    private OpportunityDtos.OpportunitySummary toSummary(Opportunity opportunity) {
        PipelineStage stage = stages.findById(opportunity.getStageId()).orElse(null);
        String accountName = opportunity.getAccountId() == null ? null
                : accounts.findById(opportunity.getAccountId()).map(Account::getName).orElse(null);
        String ownerName = opportunity.getOwnerUserId() == null ? null
                : users.findById(opportunity.getOwnerUserId()).map(User::getDisplayName).orElse(null);
        return new OpportunityDtos.OpportunitySummary(Ids.parse(opportunity.getId()), opportunity.getName(),
                Ids.parse(opportunity.getAccountId()), accountName,
                stage == null ? null : new OpportunityDtos.StageRef(Ids.parse(stage.getId()), stage.getCode(),
                        stage.getName(), stage.getOrderNo(), stage.getTerminalType()),
                opportunity.getAmount(), opportunity.getCurrency(), weightedValue(opportunity),
                opportunity.getProbability(), opportunity.getExpectedCloseDate(), opportunity.getCloseDate(),
                ownerName, opportunity.getUpdatedAt(), opportunity.getRowVersion());
    }

    private OpportunityDtos.OpportunityDetail toDetail(CurrentPrincipal principal, Opportunity opportunity) {
        OpportunityDtos.OpportunitySummary summary = toSummary(opportunity);
        List<PipelineStage> activeStages = stages.findByWorkspaceIdAndActiveTrueOrderByOrderNoAsc(
                principal.workspaceId());
        List<OpportunityDtos.StageRef> available = activeStages.stream()
                .filter(stage -> !stage.getId().equals(opportunity.getStageId()))
                .map(stage -> new OpportunityDtos.StageRef(Ids.parse(stage.getId()), stage.getCode(),
                        stage.getName(), stage.getOrderNo(), stage.getTerminalType()))
                .toList();
        Map<String, PipelineStage> byId = activeStages.stream()
                .collect(Collectors.toMap(PipelineStage::getId, stage -> stage, (a, b) -> a, HashMap::new));
        List<OpportunityDtos.StageHistoryEntry> history = stageHistory
                .findByOpportunityIdOrderByCreatedAtDesc(opportunity.getId()).stream()
                .map(entry -> new OpportunityDtos.StageHistoryEntry(
                        entry.getFromStageId() == null ? null
                                : byId.containsKey(entry.getFromStageId())
                                        ? byId.get(entry.getFromStageId()).getName() : "Previous stage",
                        byId.containsKey(entry.getToStageId()) ? byId.get(entry.getToStageId()).getName() : "Stage",
                        entry.getActorUserId() == null ? "System"
                                : users.findById(entry.getActorUserId()).map(User::getDisplayName).orElse("User"),
                        entry.getReason(), entry.getRuleVersion(), entry.getCreatedAt()))
                .toList();
        String primaryContactName = opportunity.getPrimaryContactId() == null ? null
                : contacts.findById(opportunity.getPrimaryContactId()).map(Contact::fullName).orElse(null);
        String serviceName = opportunity.getServiceId() == null ? null
                : services.findById(opportunity.getServiceId()).map(ServiceOffering::getName).orElse(null);
        String lossReasonName = opportunity.getLossReasonId() == null ? null
                : lossReasons.findById(opportunity.getLossReasonId()).map(LossReason::getName).orElse(null);
        return new OpportunityDtos.OpportunityDetail(summary, Ids.parse(opportunity.getLeadId()),
                Ids.parse(opportunity.getPrimaryContactId()), primaryContactName,
                Ids.parse(opportunity.getServiceId()), serviceName, opportunity.getFinalAmount(),
                opportunity.getFinalCurrency(), lossReasonName, opportunity.getLossNotes(),
                opportunity.getCompetitor(), opportunity.getNextStep(), Ids.parse(opportunity.getOwnerUserId()),
                Ids.parse(opportunity.getDecisionContactId()), available, history,
                opportunity.getQualificationAssessment(), opportunity.getCreatedAt());
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** Used by the forecast export endpoint. */
    public Comparator<OpportunityDtos.CurrencyTotal> byCurrency() {
        return Comparator.comparing(OpportunityDtos.CurrencyTotal::currency);
    }
}
