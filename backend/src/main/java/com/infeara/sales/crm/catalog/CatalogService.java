package com.infeara.sales.crm.catalog;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of workspace catalogues (FR-ADMIN-001): services, lead sources,
 * pipeline stages, loss reasons and industries are editable records, never hard-coded
 * strings. Stage terminal semantics are protected from accidental removal.
 */
@Service
public class CatalogService {

    private final ServiceOfferingRepository services;
    private final LeadSourceRepository sources;
    private final PipelineStageRepository stages;
    private final LossReasonRepository lossReasons;
    private final IndustryRepository industries;
    private final CapabilityEvaluator capabilities;
    private final AuditRecorder audit;

    public CatalogService(ServiceOfferingRepository services, LeadSourceRepository sources,
                          PipelineStageRepository stages, LossReasonRepository lossReasons,
                          IndustryRepository industries, CapabilityEvaluator capabilities, AuditRecorder audit) {
        this.services = services;
        this.sources = sources;
        this.stages = stages;
        this.lossReasons = lossReasons;
        this.industries = industries;
        this.capabilities = capabilities;
        this.audit = audit;
    }

    public record ServiceView(UUID id, String code, String name, String division, String description,
                              boolean active, boolean claimsApproved, long rowVersion) {
    }

    public record SourceView(UUID id, String code, String name, String sourceType, String duplicatePolicy,
                             boolean active) {
    }

    public record StageView(UUID id, String code, String name, int orderNo, String terminalType,
                            boolean allowSkip, boolean active, long rowVersion) {
    }

    public record SimpleRef(UUID id, String code, String name) {
    }

    @Transactional(readOnly = true)
    public List<ServiceView> services(CurrentPrincipal principal) {
        return services.findByWorkspaceIdOrderByDivisionAscNameAsc(principal.workspaceId()).stream()
                .map(service -> new ServiceView(Ids.parse(service.getId()), service.getCode(), service.getName(),
                        service.getDivision(), service.getDescription(), service.isActive(),
                        claimsApproved(service.getApprovedClaims()), service.getRowVersion()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SourceView> sources(CurrentPrincipal principal) {
        return sources.findByWorkspaceIdAndActiveTrueOrderByName(principal.workspaceId()).stream()
                .map(source -> new SourceView(Ids.parse(source.getId()), source.getCode(), source.getName(),
                        source.getSourceType(), source.getDuplicatePolicy(), source.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StageView> stages(CurrentPrincipal principal) {
        return stages.findByWorkspaceIdOrderByOrderNoAsc(principal.workspaceId()).stream()
                .map(stage -> new StageView(Ids.parse(stage.getId()), stage.getCode(), stage.getName(),
                        stage.getOrderNo(), stage.getTerminalType(), stage.isAllowSkip(), stage.isActive(),
                        stage.getRowVersion()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SimpleRef> lossReasons(CurrentPrincipal principal) {
        return lossReasons.findByWorkspaceIdAndActiveTrueOrderByName(principal.workspaceId()).stream()
                .map(reason -> new SimpleRef(Ids.parse(reason.getId()), reason.getCode(), reason.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SimpleRef> industries(CurrentPrincipal principal) {
        return industries.findByWorkspaceIdAndActiveTrueOrderByName(principal.workspaceId()).stream()
                .map(industry -> new SimpleRef(Ids.parse(industry.getId()), industry.getCode(),
                        industry.getName()))
                .toList();
    }

    @Transactional
    public ServiceView updateService(CurrentPrincipal principal, UUID serviceId, String name, String division,
                                     String description, Boolean active) {
        capabilities.require(principal, "catalog.manage");
        ServiceOffering service = services.findById(serviceId.toString())
                .filter(offering -> offering.getWorkspaceId().equals(principal.workspaceId()))
                .orElseThrow(() -> ApiException.notFound("Service"));
        Map<String, Object> before = Map.of("name", service.getName(), "division", service.getDivision(),
                "active", service.isActive());
        if (name != null && !name.isBlank()) {
            service.setName(name.trim());
        }
        if (division != null && !division.isBlank()) {
            service.setDivision(division.trim());
        }
        if (description != null) {
            service.setDescription(description);
        }
        if (active != null) {
            service.setActive(active);
        }
        services.save(service);
        audit.record(principal, "SERVICE_UPDATED", "SERVICE", service.getId(), before,
                Map.of("name", service.getName(), "division", service.getDivision(),
                        "active", service.isActive()), Map.of());
        return services(principal).stream()
                .filter(view -> view.id().toString().equals(service.getId()))
                .findFirst().orElseThrow();
    }

    @Transactional
    public StageView updateStage(CurrentPrincipal principal, UUID stageId, String name, Boolean allowSkip,
                                 Boolean active) {
        capabilities.require(principal, "catalog.manage");
        PipelineStage stage = stages.findByIdAndWorkspaceId(stageId.toString(), principal.workspaceId())
                .orElseThrow(() -> ApiException.notFound("Pipeline stage"));
        if (Boolean.FALSE.equals(active) && stage.isTerminal()) {
            throw new ApiException(com.infeara.sales.common.error.ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Terminal stages (won/lost) cannot be deactivated");
        }
        Map<String, Object> before = Map.of("name", stage.getName(), "allowSkip", stage.isAllowSkip(),
                "active", stage.isActive());
        if (name != null && !name.isBlank()) {
            stage.setName(name.trim());
        }
        if (allowSkip != null) {
            stage.setAllowSkip(allowSkip);
        }
        if (active != null) {
            stage.setActive(active);
        }
        stages.save(stage);
        audit.record(principal, "PIPELINE_STAGE_UPDATED", "PIPELINE_STAGE", stage.getId(), before,
                Map.of("name", stage.getName(), "allowSkip", stage.isAllowSkip(),
                        "active", stage.isActive()), Map.of());
        return stages(principal).stream().filter(view -> view.id().toString().equals(stage.getId()))
                .findFirst().orElseThrow();
    }

    private boolean claimsApproved(String approvedClaimsJson) {
        return approvedClaimsJson != null && approvedClaimsJson.contains("\"approved\":true");
    }
}
