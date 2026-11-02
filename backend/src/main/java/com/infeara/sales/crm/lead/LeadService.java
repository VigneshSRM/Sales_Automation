package com.infeara.sales.crm.lead;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.automation.outbox.OutboxPublisher;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.paging.PageQuery;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.crm.activity.ActivityRecorder;
import com.infeara.sales.crm.catalog.Industry;
import com.infeara.sales.crm.catalog.IndustryRepository;
import com.infeara.sales.crm.catalog.LeadSource;
import com.infeara.sales.crm.catalog.LeadSourceRepository;
import com.infeara.sales.crm.catalog.ServiceOffering;
import com.infeara.sales.crm.catalog.ServiceOfferingRepository;
import com.infeara.sales.crm.dedupe.DuplicateService;
import com.infeara.sales.crm.dto.LeadDtos;
import com.infeara.sales.crm.scoring.ScoringService;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.ScopeGuard;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lead use cases: capture, edit, lifecycle, qualification, assignment, merge and bulk
 * operations. All rules are enforced here — the SPA only mirrors presentation state
 * (BR-LEAD-*, BR-SCORE-*, BR-DUP-*).
 */
@Service
public class LeadService {

    private static final Map<String, String> SORTABLE = Map.of(
            "updatedAt", "updatedAt",
            "createdAt", "createdAt",
            "companyName", "companyName",
            "status", "status",
            "score", "score");

    private static final Set<String> ALLOWED_FILTERS = Set.of("status", "owner", "q", "source", "service",
            "scoreBand", "unassigned", "industry", "minScore");

    private final LeadRepository leads;
    private final LeadScoreHistoryRepository scoreHistory;
    private final LeadDuplicateRepository duplicateRepository;
    private final MergeAuditRepository mergeAudits;
    private final LeadSourceRepository sources;
    private final ServiceOfferingRepository services;
    private final IndustryRepository industries;
    private final UserRepository users;
    private final DuplicateService duplicates;
    private final ScoringService scoring;
    private final ActivityRecorder activities;
    private final CapabilityEvaluator capabilities;
    private final ScopeGuard scopeGuard;
    private final AuditRecorder audit;
    private final OutboxPublisher outbox;
    private final JsonCodec jsonCodec;
    private final com.infeara.sales.common.persistence.IdempotencyService idempotency;
    private final Clock clock;

    public LeadService(LeadRepository leads, LeadScoreHistoryRepository scoreHistory,
                       LeadDuplicateRepository duplicateRepository, MergeAuditRepository mergeAudits,
                       LeadSourceRepository sources, ServiceOfferingRepository services,
                       IndustryRepository industries, UserRepository users, DuplicateService duplicates,
                       ScoringService scoring, ActivityRecorder activities, CapabilityEvaluator capabilities,
                       ScopeGuard scopeGuard, AuditRecorder audit, OutboxPublisher outbox, JsonCodec jsonCodec,
                       com.infeara.sales.common.persistence.IdempotencyService idempotency, Clock clock) {
        this.leads = leads;
        this.scoreHistory = scoreHistory;
        this.duplicateRepository = duplicateRepository;
        this.mergeAudits = mergeAudits;
        this.sources = sources;
        this.services = services;
        this.industries = industries;
        this.users = users;
        this.duplicates = duplicates;
        this.scoring = scoring;
        this.activities = activities;
        this.capabilities = capabilities;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.outbox = outbox;
        this.jsonCodec = jsonCodec;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create
    @Transactional
    public LeadDetailView create(CurrentPrincipal principal, LeadDtos.CreateLeadRequest request,
                                 String idempotencyKey) {
        capabilities.require(principal, "leads.create");
        String workspaceId = principal.workspaceId();
        String requestHash = idempotency.hashOf(request);
        var replayed = idempotency.replay(workspaceId, "leads.create", idempotencyKey, requestHash);
        if (replayed.isPresent()) {
            LeadDtos.LeadDetail stored = jsonCodec.read(replayed.get(), LeadDtos.LeadDetail.class);
            List<LeadDtos.DuplicateCandidate> candidates = stored == null ? List.of() : stored.duplicates();
            return new LeadDetailView(stored, candidates);
        }
        String email = Normalizer.email(request.email());
        String emailForComparison = Normalizer.emailForComparison(request.email());
        if (request.email() != null && !request.email().isBlank() && emailForComparison == null) {
            throw ApiException.field("email", "INVALID_EMAIL", "A valid business email address is required");
        }
        LeadSource source = sources.findByWorkspaceIdAndCode(workspaceId, request.sourceCode())
                .filter(LeadSource::isActive)
                .orElseThrow(() -> ApiException.field("sourceCode", "UNKNOWN_SOURCE",
                        "Unknown or inactive lead source"));
        List<ServiceOffering> requestedServices = resolveServices(workspaceId, request.serviceIds());
        Industry industry = null;
        if (request.industryCode() != null && !request.industryCode().isBlank()) {
            industry = industries.findByWorkspaceIdAndCode(workspaceId, request.industryCode())
                    .orElseThrow(() -> ApiException.field("industryCode", "UNKNOWN_INDUSTRY",
                            "Unknown industry code"));
        }
        User owner = resolveOwner(principal, request.assignedUserId());

        List<LeadDtos.DuplicateCandidate> candidates = duplicates.candidates(workspaceId, request.email(),
                request.phone(), Normalizer.websiteDomain(request.website()), request.companyName());
        boolean strongEmailDuplicate = emailForComparison != null
                && duplicates.hasStrongEmailDuplicate(workspaceId, emailForComparison);
        if (strongEmailDuplicate && "REJECT_ON_DUPLICATE".equals(source.getDuplicatePolicy())) {
            throw new ApiException(ErrorCode.DUPLICATE_LEAD,
                    "A lead with this email already exists and this source rejects duplicates",
                    List.of(new com.infeara.sales.common.api.ApiError.FieldErrorItem("email",
                            "DUPLICATE_LEAD", "Existing lead uses this email address")));
        }
        if (request.budgetAmount() != null && request.currency() == null) {
            throw ApiException.field("currency", "CURRENCY_REQUIRED",
                    "A currency is required when a budget amount is provided");
        }

        Lead lead = new Lead();
        lead.setId(Ids.newId());
        lead.setWorkspaceId(workspaceId);
        lead.setFirstName(request.firstName().trim());
        lead.setLastName(trimOrNull(request.lastName()));
        lead.setEmail(email);
        lead.setEmailNormalized(emailForComparison);
        lead.setPhone(trimOrNull(request.phone()));
        lead.setPhoneE164(Normalizer.phoneE164(request.phone(), "91"));
        lead.setWhatsapp(trimOrNull(request.whatsapp()));
        lead.setCompanyName(request.companyName().trim());
        lead.setCompanyNameNormalized(Normalizer.companyName(request.companyName()));
        lead.setDomainNormalized(Normalizer.websiteDomain(request.website()));
        lead.setIndustryId(industry == null ? null : industry.getId());
        lead.setRequirement(trimOrNull(request.requirement()));
        lead.setBudgetAmount(request.budgetAmount());
        lead.setBudgetCurrency(request.budgetAmount() == null ? null : request.currency());
        lead.setTimelineCode(trimOrNull(request.timelineCode()));
        lead.setLocation(trimOrNull(request.location()));
        lead.setEmployeeBand(trimOrNull(request.employeeBand()));
        lead.setSourceId(source.getId());
        lead.setSourceEvidenceRef(trimOrNull(request.sourceEvidenceRef()));
        lead.setPrimaryServiceId(requestedServices.isEmpty() ? null : requestedServices.get(0).getId());
        lead.setOwnerUserId(owner == null ? null : owner.getId());
        lead.setStatus(LeadStatus.NEW);
        lead.setConsentReference(trimOrNull(request.consentReference()));
        lead.setCreatedBy(principal.userId());
        leads.save(lead);

        for (LeadDtos.DuplicateCandidate candidate : candidates) {
            LeadDuplicate duplicate = new LeadDuplicate();
            duplicate.setId(Ids.newId());
            duplicate.setWorkspaceId(workspaceId);
            duplicate.setLeadId(lead.getId());
            duplicate.setCandidateType(candidate.candidateType());
            duplicate.setCandidateId(candidate.candidateId().toString());
            duplicate.setMatchType(candidate.matchType());
            duplicate.setConfidence(java.math.BigDecimal.valueOf(candidate.confidence()));
            duplicateRepository.save(duplicate);
        }

        activities.record(principal, "LEAD", lead.getId(), "SYSTEM_EVENT", "Lead captured",
                "Source: " + source.getName(), Map.of("sourceCode", source.getCode(),
                        "duplicateCandidates", candidates.size()));
        audit.record(principal, "LEAD_CREATED", "LEAD", lead.getId(), null,
                Map.of("companyName", lead.getCompanyName(), "status", lead.getStatus().name(),
                        "source", source.getCode(), "owner", lead.getOwnerUserId() == null ? "" :
                                lead.getOwnerUserId()),
                Map.of("duplicateCandidates", candidates.size()));
        outbox.publish(workspaceId, "LEAD", lead.getId(), "LEAD_CREATED", Map.of(
                "companyName", lead.getCompanyName(),
                "notifyUserId", lead.getOwnerUserId() == null ? principal.userId() : lead.getOwnerUserId(),
                "summary", "Captured from " + source.getName()));

        LeadDetailView created = toDetail(principal, lead);
        idempotency.remember(workspaceId, "leads.create", idempotencyKey, requestHash, 201,
                created.detail());
        return created;
    }

    // -------------------------------------------------------------------- read
    @Transactional(readOnly = true)
    public PageResponse<LeadDtos.LeadSummary> list(CurrentPrincipal principal, PageQuery query) {
        capabilities.require(principal, "leads.read");
        Specification<Lead> specification = buildSpecification(principal, query);
        Sort sort = Sort.by(query.descending() ? Sort.Direction.DESC : Sort.Direction.ASC,
                query.resolveSort(SORTABLE, "updatedAt"));
        PageRequest pageable = PageRequest.of(query.page(), query.size(), sort);
        Page<Lead> page = leads.findAll(specification, pageable);
        List<LeadDtos.LeadSummary> items = page.getContent().stream()
                .map(lead -> toSummary(principal, lead))
                .toList();
        return PageResponse.of(items, query.page(), query.size(), page.getTotalElements(),
                query.sortBy() + ":" + (query.descending() ? "desc" : "asc"), query.appliedFilters());
    }

    @Transactional(readOnly = true)
    public LeadDtos.LeadDetail get(CurrentPrincipal principal, UUID leadId) {
        capabilities.require(principal, "leads.read");
        Lead lead = requireLead(principal, leadId);
        return toDetail(principal, lead).detail();
    }

    // ------------------------------------------------------------------ update
    @Transactional
    public LeadDetailView update(CurrentPrincipal principal, UUID leadId, LeadDtos.PatchLeadRequest request) {
        capabilities.require(principal, "leads.update");
        Lead lead = requireLead(principal, leadId);
        checkVersion(lead, request.rowVersion());
        Map<String, Object> before = snapshot(lead);

        if (request.firstName() != null) {
            lead.setFirstName(request.firstName().trim());
        }
        if (request.lastName() != null) {
            lead.setLastName(trimOrNull(request.lastName()));
        }
        if (request.email() != null) {
            String normalized = Normalizer.emailForComparison(request.email());
            if (normalized == null) {
                throw ApiException.field("email", "INVALID_EMAIL", "A valid email address is required");
            }
            lead.setEmail(Normalizer.email(request.email()));
            lead.setEmailNormalized(normalized);
        }
        if (request.phone() != null) {
            lead.setPhone(trimOrNull(request.phone()));
            lead.setPhoneE164(Normalizer.phoneE164(request.phone(), "91"));
        }
        if (request.whatsapp() != null) {
            lead.setWhatsapp(trimOrNull(request.whatsapp()));
        }
        if (request.companyName() != null) {
            lead.setCompanyName(request.companyName().trim());
            lead.setCompanyNameNormalized(Normalizer.companyName(request.companyName()));
        }
        if (request.website() != null) {
            lead.setDomainNormalized(Normalizer.websiteDomain(request.website()));
        }
        if (request.industryCode() != null) {
            lead.setIndustryId(request.industryCode().isBlank() ? null
                    : industries.findByWorkspaceIdAndCode(principal.workspaceId(), request.industryCode())
                            .orElseThrow(() -> ApiException.field("industryCode", "UNKNOWN_INDUSTRY",
                                    "Unknown industry code")).getId());
        }
        if (request.serviceIds() != null) {
            List<ServiceOffering> resolved = resolveServices(principal.workspaceId(), request.serviceIds());
            lead.setPrimaryServiceId(resolved.isEmpty() ? null : resolved.get(0).getId());
        }
        if (request.requirement() != null) {
            lead.setRequirement(trimOrNull(request.requirement()));
        }
        if (request.budgetAmount() != null) {
            lead.setBudgetAmount(request.budgetAmount());
        }
        if (request.currency() != null) {
            lead.setBudgetCurrency(request.currency());
        }
        if (request.timelineCode() != null) {
            lead.setTimelineCode(trimOrNull(request.timelineCode()));
        }
        if (request.location() != null) {
            lead.setLocation(trimOrNull(request.location()));
        }
        if (request.employeeBand() != null) {
            lead.setEmployeeBand(trimOrNull(request.employeeBand()));
        }
        if (request.consentReference() != null) {
            lead.setConsentReference(trimOrNull(request.consentReference()));
        }
        if (lead.getBudgetAmount() == null) {
            lead.setBudgetCurrency(null);
        } else if (lead.getBudgetCurrency() == null) {
            throw ApiException.field("currency", "CURRENCY_REQUIRED",
                    "A currency is required when a budget amount is provided");
        }
        lead.setUpdatedBy(principal.userId());
        leads.save(lead);
        audit.record(principal, "LEAD_UPDATED", "LEAD", lead.getId(), before, snapshot(lead), Map.of());
        return toDetail(principal, lead);
    }

    // -------------------------------------------------------------- lifecycle
    @Transactional
    public LeadDetailView transition(CurrentPrincipal principal, UUID leadId, LeadDtos.TransitionRequest request) {
        capabilities.require(principal, "leads.update");
        Lead lead = requireLead(principal, leadId);
        if (request.rowVersion() != null) {
            checkVersion(lead, request.rowVersion());
        }
        LeadStatus target;
        try {
            target = LeadStatus.valueOf(request.targetStatus());
        } catch (IllegalArgumentException ex) {
            throw ApiException.field("targetStatus", "UNKNOWN_STATUS", "Unknown lead status");
        }
        if (lead.getStatus() == target) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The lead is already in status " + target);
        }
        if (!lead.getStatus().canTransitionTo(target)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Transition " + lead.getStatus() + " → " + target + " is not permitted",
                    List.of(new com.infeara.sales.common.api.ApiError.FieldErrorItem("targetStatus",
                            "INVALID_TRANSITION", "Allowed: " + lead.getStatus().allowedTargets())));
        }
        if (target == LeadStatus.REOPENED) {
            capabilities.require(principal, "leads.reopen");
        }
        if (target == LeadStatus.DISQUALIFIED && (request.reasonCode() == null || request.reasonCode().isBlank())) {
            throw ApiException.field("reasonCode", "REASON_REQUIRED",
                    "A disqualification reason code is required");
        }
        if (target == LeadStatus.CONVERTED) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Use the conversion command to convert a lead: it links an account, contact and "
                            + "opportunity and preserves references (BR-LEAD-002/003)");
        }
        LeadStatus previous = lead.getStatus();
        lead.setStatus(target);
        lead.setUpdatedBy(principal.userId());
        leads.save(lead);
        activities.record(principal, "LEAD", lead.getId(), "LEAD_STATUS", "Status " + previous + " → " + target,
                request.reason(), Map.of("from", previous.name(), "to", target.name(),
                        "reasonCode", request.reasonCode() == null ? "" : request.reasonCode()));
        audit.record(principal, "LEAD_STATUS_CHANGED", "LEAD", lead.getId(),
                Map.of("status", previous.name()), Map.of("status", target.name()),
                Map.of("reason", request.reason() == null ? "" : request.reason()));
        if (target == LeadStatus.QUALIFIED) {
            outbox.publish(principal.workspaceId(), "LEAD", lead.getId(), "LEAD_QUALIFIED",
                    Map.of("companyName", lead.getCompanyName(),
                            "notifyUserId", lead.getOwnerUserId() == null ? principal.userId()
                                    : lead.getOwnerUserId()));
        }
        return toDetail(principal, lead);
    }

    @Transactional
    public LeadDetailView assign(CurrentPrincipal principal, UUID leadId, LeadDtos.AssignRequest request) {
        capabilities.require(principal, "leads.assign");
        Lead lead = requireLead(principal, leadId);
        if (request.rowVersion() != null) {
            checkVersion(lead, request.rowVersion());
        }
        User owner = users.findByIdAndWorkspaceIdAndDeletedAtIsNull(request.ownerUserId().toString(),
                        principal.workspaceId())
                .orElseThrow(() -> ApiException.field("ownerUserId", "UNKNOWN_OWNER",
                        "Owner must be an active user in this workspace"));
        String previousOwner = lead.getOwnerUserId();
        lead.setOwnerUserId(owner.getId());
        lead.setUpdatedBy(principal.userId());
        leads.save(lead);
        activities.record(principal, "LEAD", lead.getId(), "SYSTEM_EVENT", "Owner changed",
                request.reason(), Map.of("from", previousOwner == null ? "" : previousOwner,
                        "to", owner.getId()));
        audit.record(principal, "LEAD_ASSIGNED", "LEAD", lead.getId(),
                Map.of("ownerUserId", previousOwner == null ? "" : previousOwner),
                Map.of("ownerUserId", owner.getId()),
                Map.of("reason", request.reason() == null ? "" : request.reason()));
        outbox.publish(principal.workspaceId(), "LEAD", lead.getId(), "LEAD_ASSIGNED",
                Map.of("notifyUserId", owner.getId(), "summary", "Assigned by " + principal.displayName()));
        return toDetail(principal, lead);
    }

    @Transactional
    public LeadDtos.LeadDetail qualify(CurrentPrincipal principal, UUID leadId, LeadDtos.QualifyRequest request) {
        capabilities.require(principal, "leads.qualify");
        Lead lead = requireLead(principal, leadId);
        if (request.rowVersion() != null) {
            checkVersion(lead, request.rowVersion());
        }
        ScoringService.ScoreResult result = scoring.calculate(principal.workspaceId(), lead,
                request.decisionMakerConfirmed());
        lead.setScore(result.total());
        lead.setScoreBand(result.band());
        lead.setScoringModelVersion(result.modelVersion());
        lead.setQualificationAssessment(jsonCodec.write(Map.of(
                "decisionMakerConfirmed", request.decisionMakerConfirmed(),
                "notes", request.evidenceNotes() == null ? "" : request.evidenceNotes(),
                "assessedBy", principal.userId(),
                "assessedAt", clock.instant().toString())));
        if (request.markQualified() && lead.getStatus().canTransitionTo(LeadStatus.QUALIFIED)) {
            lead.setStatus(LeadStatus.QUALIFIED);
        }
        lead.setUpdatedBy(principal.userId());
        leads.save(lead);
        persistScoreHistory(principal, lead, result, "SYSTEM", null, null);
        activities.record(principal, "LEAD", lead.getId(), "AI_ACTION", "Qualification scoring",
                "Score " + result.total() + " (" + result.band() + ")",
                Map.of("score", result.total(), "band", result.band(), "modelVersion", result.modelVersion()));
        audit.record(principal, "LEAD_QUALIFIED_SCORED", "LEAD", lead.getId(),
                Map.of("score", lead.getScore() == null ? 0 : lead.getScore()),
                Map.of("score", result.total(), "band", result.band(),
                        "modelVersion", result.modelVersion()), Map.of());
        return toDetail(principal, lead).detail();
    }

    @Transactional
    public LeadDtos.LeadDetail overrideScore(CurrentPrincipal principal, UUID leadId,
                                             LeadDtos.OverrideScoreRequest request) {
        capabilities.require(principal, "leads.qualify");
        Lead lead = requireLead(principal, leadId);
        ScoringService.ScoreResult model = scoring.calculate(principal.workspaceId(), lead, false);
        String band = scoring.bandFor(scoring.activeModel(principal.workspaceId()), request.score());
        Integer previous = lead.getScore();
        lead.setScore(request.score());
        lead.setScoreBand(band);
        leads.save(lead);
        persistScoreHistory(principal, lead, model, "HUMAN_OVERRIDE", request.reason(), request.score());
        audit.record(principal, "LEAD_SCORE_OVERRIDDEN", "LEAD", lead.getId(),
                Map.of("score", previous == null ? 0 : previous), Map.of("score", request.score()),
                Map.of("reason", request.reason()));
        return toDetail(principal, lead).detail();
    }

    @Transactional
    public LeadDtos.BulkAssignResult bulkAssign(CurrentPrincipal principal, LeadDtos.BulkAssignRequest request) {
        capabilities.require(principal, "leads.assign");
        User owner = users.findByIdAndWorkspaceIdAndDeletedAtIsNull(request.ownerUserId().toString(),
                        principal.workspaceId())
                .orElseThrow(() -> ApiException.field("ownerUserId", "UNKNOWN_OWNER", "Unknown owner"));
        int updated = 0;
        List<LeadDtos.BulkFailure> failures = new ArrayList<>();
        for (UUID leadId : request.leadIds()) {
            try {
                Lead lead = leads.findByIdAndWorkspaceIdAndDeletedAtIsNull(leadId.toString(),
                                principal.workspaceId())
                        .orElseThrow(() -> new ApiException(ErrorCode.LEAD_NOT_FOUND, "Lead not found"));
                scopeGuard.requireAccess(principal, lead.getOwnerUserId(), "lead");
                lead.setOwnerUserId(owner.getId());
                lead.setUpdatedBy(principal.userId());
                leads.save(lead);
                updated++;
            } catch (ApiException ex) {
                failures.add(new LeadDtos.BulkFailure(leadId, ex.errorCode().name(), ex.getMessage()));
            }
        }
        audit.record(principal, "LEAD_BULK_ASSIGNED", "LEAD", null, null,
                Map.of("ownerUserId", owner.getId(), "updated", updated, "failed", failures.size()),
                Map.of("reason", request.reason() == null ? "" : request.reason()));
        return new LeadDtos.BulkAssignResult(updated, failures);
    }

    @Transactional
    public LeadDetailView merge(CurrentPrincipal principal, LeadDtos.MergeRequest request) {
        capabilities.require(principal, "leads.merge");
        if (request.sourceLeadId().equals(request.targetLeadId())) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "A lead cannot be merged into itself");
        }
        Lead target = requireLead(principal, request.targetLeadId());
        Lead source = requireLead(principal, request.sourceLeadId());
        if (source.getMergedIntoId() != null) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "The source lead was already merged");
        }
        Map<String, Object> before = snapshot(target);
        source.setMergedIntoId(target.getId());
        source.setStatus(LeadStatus.ARCHIVED);
        source.setDeletedAt(clock.instant());
        source.setUpdatedBy(principal.userId());
        leads.save(source);

        MergeAudit mergeAudit = new MergeAudit();
        mergeAudit.setId(Ids.newId());
        mergeAudit.setWorkspaceId(principal.workspaceId());
        mergeAudit.setEntityType("LEAD");
        mergeAudit.setTargetId(target.getId());
        mergeAudit.setSourceIdsJson(jsonCodec.write(List.of(source.getId())));
        mergeAudit.setFieldDecisionsJson(jsonCodec.write(request.fieldDecisions() == null
                ? Map.of() : request.fieldDecisions()));
        mergeAudit.setReason(request.reason());
        mergeAudit.setActorUserId(principal.userId());
        mergeAudits.save(mergeAudit);

        duplicateRepository.findByLeadId(source.getId()).forEach(duplicate -> {
            duplicate.setResolution("MERGED");
            duplicate.setResolvedBy(principal.userId());
            duplicate.setResolvedAt(clock.instant());
            duplicateRepository.save(duplicate);
        });
        activities.record(principal, "LEAD", target.getId(), "SYSTEM_EVENT", "Duplicate merged",
                "Source lead " + source.getId() + " merged", Map.of("sourceLeadId", source.getId()));
        audit.record(principal, "LEAD_MERGED", "LEAD", target.getId(), before, snapshot(target),
                Map.of("sourceLeadId", source.getId(), "reason", request.reason()));
        outbox.publish(principal.workspaceId(), "LEAD", target.getId(), "LEAD_MERGED",
                Map.of("targetCompany", target.getCompanyName(),
                        "notifyUserId", target.getOwnerUserId() == null ? principal.userId()
                                : target.getOwnerUserId()));
        return toDetail(principal, target);
    }

    @Transactional
    public void archive(CurrentPrincipal principal, UUID leadId, String reason) {
        capabilities.require(principal, "leads.update");
        Lead lead = requireLead(principal, leadId);
        if (!lead.getStatus().canTransitionTo(LeadStatus.ARCHIVED) && lead.getStatus() != LeadStatus.ARCHIVED) {
            lead.setStatus(LeadStatus.ARCHIVED);
        }
        lead.setDeletedAt(clock.instant());
        lead.setUpdatedBy(principal.userId());
        leads.save(lead);
        audit.record(principal, "LEAD_ARCHIVED", "LEAD", lead.getId(), null, Map.of("archived", true),
                Map.of("reason", reason == null ? "" : reason));
    }

    // ----------------------------------------------------------------- helpers
    private Lead requireLead(CurrentPrincipal principal, UUID leadId) {
        Lead lead = leads.findByIdAndWorkspaceIdAndDeletedAtIsNull(leadId.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.LEAD_NOT_FOUND, "Lead not found"));
        scopeGuard.requireAccess(principal, lead.getOwnerUserId(), "lead");
        return lead;
    }

    private void checkVersion(Lead lead, Long expected) {
        if (expected != null && expected != lead.getRowVersion()) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "This lead was updated by someone else (expected version " + expected
                            + ", current " + lead.getRowVersion() + "). Reload and retry.");
        }
    }

    private User resolveOwner(CurrentPrincipal principal, UUID ownerUserId) {
        if (ownerUserId == null) {
            return null;
        }
        return users.findByIdAndWorkspaceIdAndDeletedAtIsNull(ownerUserId.toString(), principal.workspaceId())
                .orElseThrow(() -> ApiException.field("assignedUserId", "UNKNOWN_OWNER",
                        "The assigned user must be active in this workspace"));
    }

    private List<ServiceOffering> resolveServices(String workspaceId, List<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return List.of();
        }
        List<ServiceOffering> resolved = new ArrayList<>();
        for (UUID serviceId : serviceIds) {
            ServiceOffering service = services.findById(serviceId.toString())
                    .filter(offering -> offering.getWorkspaceId().equals(workspaceId))
                    .filter(ServiceOffering::isActive)
                    .orElseThrow(() -> ApiException.field("serviceIds", "UNKNOWN_SERVICE",
                            "Service " + serviceId + " is not an active service in this workspace"));
            resolved.add(service);
        }
        return resolved;
    }

    private Specification<Lead> buildSpecification(CurrentPrincipal principal, PageQuery query) {
        Map<String, String> filters = query.filters();
        return (root, criteriaQuery, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("workspaceId"), principal.workspaceId()));
            predicates.add(builder.isNull(root.get("deletedAt")));
            String status = filters.get("status");
            if (status != null && !status.isBlank()) {
                predicates.add(builder.equal(root.get("status"), LeadStatus.valueOf(status)));
            }
            String owner = filters.get("owner");
            if ("me".equals(owner)) {
                predicates.add(builder.equal(root.get("ownerUserId"), principal.userId()));
            } else if (owner != null && !owner.isBlank() && !"all".equals(owner)) {
                predicates.add(builder.equal(root.get("ownerUserId"), owner));
            }
            if ("true".equals(filters.get("unassigned"))) {
                predicates.add(builder.isNull(root.get("ownerUserId")));
            }
            String source = filters.get("source");
            if (source != null && !source.isBlank()) {
                predicates.add(builder.equal(root.get("sourceId"), source));
            }
            String service = filters.get("service");
            if (service != null && !service.isBlank()) {
                predicates.add(builder.equal(root.get("primaryServiceId"), service));
            }
            String scoreBand = filters.get("scoreBand");
            if (scoreBand != null && !scoreBand.isBlank()) {
                predicates.add(builder.equal(root.get("scoreBand"), scoreBand));
            }
            String minScore = filters.get("minScore");
            if (minScore != null && !minScore.isBlank()) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("score"), Integer.parseInt(minScore)));
            }
            String term = filters.get("q");
            if (term != null && !term.isBlank()) {
                String like = "%" + term.trim().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("companyName")), like),
                        builder.like(root.get("emailNormalized"), like),
                        builder.like(builder.lower(root.get("firstName")), like),
                        builder.like(builder.lower(builder.coalesce(root.get("lastName"), "")), like)));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private void persistScoreHistory(CurrentPrincipal principal, Lead lead, ScoringService.ScoreResult result,
                                     String source, String overrideReason, Integer explicitTotal) {
        LeadScoreHistory history = new LeadScoreHistory();
        history.setId(Ids.newId());
        history.setWorkspaceId(principal.workspaceId());
        history.setLeadId(lead.getId());
        history.setModelVersion(result.modelVersion());
        history.setTotal(explicitTotal == null ? result.total() : explicitTotal);
        history.setBand(explicitTotal == null ? result.band()
                : scoring.bandFor(scoring.activeModel(principal.workspaceId()), explicitTotal));
        history.setFactorJson(scoring.breakdownJson(result));
        history.setEvidenceJson(scoring.evidenceJson(result));
        history.setCalculationSource(source);
        if (explicitTotal != null) {
            history.setOverrideReason(overrideReason);
            history.setOverriddenBy(principal.userId());
        }
        scoreHistory.save(history);
    }

    private Map<String, Object> snapshot(Lead lead) {
        return Map.of(
                "firstName", lead.getFirstName(),
                "lastName", lead.getLastName() == null ? "" : lead.getLastName(),
                "companyName", lead.getCompanyName(),
                "email", lead.getEmail() == null ? "" : lead.getEmail(),
                "status", lead.getStatus().name(),
                "score", lead.getScore() == null ? 0 : lead.getScore(),
                "ownerUserId", lead.getOwnerUserId() == null ? "" : lead.getOwnerUserId(),
                "budgetAmount", lead.getBudgetAmount() == null ? "" : lead.getBudgetAmount().toPlainString(),
                "currency", lead.getBudgetCurrency() == null ? "" : lead.getBudgetCurrency());
    }

    private LeadDtos.LeadSummary toSummary(CurrentPrincipal principal, Lead lead) {
        String ownerName = lead.getOwnerUserId() == null ? null
                : users.findById(lead.getOwnerUserId()).map(User::getDisplayName).orElse(null);
        String sourceName = lead.getSourceId() == null ? null
                : sources.findById(lead.getSourceId()).map(LeadSource::getName).orElse(null);
        String serviceName = lead.getPrimaryServiceId() == null ? null
                : services.findById(lead.getPrimaryServiceId()).map(ServiceOffering::getName).orElse(null);
        return new LeadDtos.LeadSummary(Ids.parse(lead.getId()), lead.fullName(), lead.getCompanyName(),
                lead.getEmail(), lead.getPhone(), lead.getStatus().name(), lead.getScore(), lead.getScoreBand(),
                ownerName, lead.getOwnerUserId() == null ? null : Ids.parse(lead.getOwnerUserId()), sourceName,
                serviceName, lead.getUpdatedAt(), lead.getRowVersion());
    }

    private LeadDetailView toDetail(CurrentPrincipal principal, Lead lead) {
        String ownerName = lead.getOwnerUserId() == null ? null
                : users.findById(lead.getOwnerUserId()).map(User::getDisplayName).orElse(null);
        String sourceCode = sources.findById(lead.getSourceId()).map(LeadSource::getCode).orElse(null);
        String industryCode = lead.getIndustryId() == null ? null
                : industries.findById(lead.getIndustryId()).map(Industry::getCode).orElse(null);
        List<UUID> serviceIds = lead.getPrimaryServiceId() == null ? List.of()
                : List.of(Ids.parse(lead.getPrimaryServiceId()));

        ScoringService.ScoreResult latest = scoring.calculate(principal.workspaceId(), lead, false);
        LeadDtos.ScoreView scoreView = new LeadDtos.ScoreView(latest.total(), latest.band(),
                latest.modelVersion(),
                latest.factors().stream()
                        .map(factor -> new LeadDtos.ScoreFactor(factor.code(), factor.weight(), factor.fit(),
                                factor.points(), factor.evidence()))
                        .toList(),
                lead.getUpdatedAt());

        List<LeadDtos.DuplicateCandidate> candidates = duplicates.forLead(principal.workspaceId(), lead);
        List<String> transitions = lead.getStatus().allowedTargets().stream().map(Enum::name).sorted().toList();

        LeadDtos.LeadDetail detail = new LeadDtos.LeadDetail(Ids.parse(lead.getId()), lead.getFirstName(),
                lead.getLastName(), lead.getEmail(), lead.getPhone(), lead.getWhatsapp(), lead.getCompanyName(),
                lead.getDomainNormalized() == null ? null : "https://" + lead.getDomainNormalized(),
                lead.getDomainNormalized(), industryCode, serviceIds, sourceCode, lead.getSourceEvidenceRef(),
                lead.getRequirement(), lead.getBudgetAmount(), lead.getBudgetCurrency(), lead.getTimelineCode(),
                lead.getLocation(), lead.getEmployeeBand(), lead.getStatus().name(), lead.getScore(),
                lead.getScoreBand(), scoreView,
                lead.getOwnerUserId() == null ? null : Ids.parse(lead.getOwnerUserId()), ownerName,
                lead.getConsentReference(), transitions, candidates, lead.getCreatedAt(), lead.getUpdatedAt(),
                lead.getRowVersion());
        return new LeadDetailView(detail, candidates);
    }

    private static String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Internal carrier so that create can return duplicate candidates without a second query. */
    public record LeadDetailView(LeadDtos.LeadDetail detail, List<LeadDtos.DuplicateCandidate> duplicates) {
    }

    /** Convenience for other modules that need an authorised lead. */
    @Transactional(readOnly = true)
    public Lead requireAccessibleLead(CurrentPrincipal principal, String leadId) {
        return requireLead(principal, Ids.parse(leadId));
    }

    public Instant now() {
        return clock.instant();
    }
}
