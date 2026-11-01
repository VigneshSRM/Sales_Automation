package com.infeara.sales.engagement.template;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Template lifecycle: DRAFT → PENDING_APPROVAL → APPROVED → ARCHIVED.
 * Editing an approved template returns it to DRAFT: an approval covers the exact copy
 * that was reviewed, never a later edit.
 */
@Service
public class TemplateService {

    private final EmailTemplateRepository templates;
    private final MergeFieldResolver resolver;
    private final CapabilityEvaluator capabilities;
    private final AuditRecorder audit;
    private final Clock clock;

    public TemplateService(EmailTemplateRepository templates, MergeFieldResolver resolver,
                           CapabilityEvaluator capabilities, AuditRecorder audit, Clock clock) {
        this.templates = templates;
        this.resolver = resolver;
        this.capabilities = capabilities;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TemplateDtos.TemplateView> list(CurrentPrincipal principal) {
        capabilities.require(principal, "email.read");
        return templates.findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(principal.workspaceId())
                .stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public TemplateDtos.TemplateView get(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "email.read");
        return toView(require(principal, id));
    }

    @Transactional
    public TemplateDtos.TemplateView create(CurrentPrincipal principal, TemplateDtos.CreateTemplateRequest request) {
        capabilities.require(principal, "templates.manage");
        if (templates.existsByWorkspaceIdAndNameAndDeletedAtIsNull(principal.workspaceId(), request.name().trim())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A template with that name already exists");
        }
        resolver.validate(request.subject(), request.body());
        EmailTemplate template = new EmailTemplate();
        template.setId(Ids.newId());
        template.setWorkspaceId(principal.workspaceId());
        template.setName(request.name().trim());
        template.setPurpose(request.purpose() == null ? "OUTREACH" : request.purpose());
        template.setSubjectTemplate(request.subject().trim());
        template.setBodyTemplate(request.body());
        template.setMergeFieldsJson(toJson(resolver.fieldsUsed(request.subject() + " " + request.body())));
        template.setStatus("DRAFT");
        template.setCreatedBy(principal.userId());
        templates.save(template);
        audit.record(principal, "EMAIL_TEMPLATE_CREATED", "EMAIL_TEMPLATE", template.getId(), null,
                Map.of("name", template.getName(), "fields", template.getMergeFieldsJson()), Map.of());
        return toView(template);
    }

    @Transactional
    public TemplateDtos.TemplateView update(CurrentPrincipal principal, UUID id,
                                            TemplateDtos.UpdateTemplateRequest request) {
        capabilities.require(principal, "templates.manage");
        EmailTemplate template = require(principal, id);
        if (request.expectedRowVersion() != null && request.expectedRowVersion() != template.getRowVersion()) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "This template changed since you loaded it. Reload and try again.");
        }
        String subject = request.subject() == null ? template.getSubjectTemplate() : request.subject();
        String body = request.body() == null ? template.getBodyTemplate() : request.body();
        resolver.validate(subject, body);
        String beforeStatus = template.getStatus();
        template.setSubjectTemplate(subject.trim());
        template.setBodyTemplate(body);
        if (request.name() != null && !request.name().isBlank()) {
            template.setName(request.name().trim());
        }
        if (request.purpose() != null) {
            template.setPurpose(request.purpose());
        }
        template.setMergeFieldsJson(toJson(resolver.fieldsUsed(subject + " " + body)));
        if (template.isApproved()) {
            template.setStatus("DRAFT");
            template.setApprovedAt(null);
            template.setApprovedBy(null);
            template.setVersion(template.getVersion() + 1);
        }
        templates.save(template);
        audit.record(principal, "EMAIL_TEMPLATE_UPDATED", "EMAIL_TEMPLATE", template.getId(),
                Map.of("status", beforeStatus), Map.of("status", template.getStatus(),
                        "version", template.getVersion()), Map.of());
        return toView(template);
    }

    @Transactional
    public TemplateDtos.TemplateView submitForApproval(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "templates.manage");
        EmailTemplate template = require(principal, id);
        if (!"DRAFT".equals(template.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a draft template can be submitted for approval");
        }
        template.setStatus("PENDING_APPROVAL");
        templates.save(template);
        return toView(template);
    }

    @Transactional
    public TemplateDtos.TemplateView approve(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "email.approve");
        EmailTemplate template = require(principal, id);
        if (!"PENDING_APPROVAL".equals(template.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a template awaiting approval can be approved");
        }
        if (template.getCreatedBy() != null && template.getCreatedBy().equals(principal.userId())
                && !principal.has("settings.manage")) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Four-eyes rule: the author cannot approve their own template");
        }
        template.setStatus("APPROVED");
        template.setApprovedAt(clock.instant());
        template.setApprovedBy(principal.userId());
        templates.save(template);
        audit.record(principal, "EMAIL_TEMPLATE_APPROVED", "EMAIL_TEMPLATE", template.getId(),
                Map.of("status", "PENDING_APPROVAL"), Map.of("status", "APPROVED"), Map.of());
        return toView(template);
    }

    @Transactional(readOnly = true)
    public TemplateDtos.PreviewResult preview(CurrentPrincipal principal, TemplateDtos.PreviewRequest request) {
        capabilities.require(principal, "email.read");
        resolver.validate(request.subject(), request.body());
        Map<String, String> samples = new LinkedHashMap<>();
        for (String field : resolver.fieldsUsed(request.subject() + " " + request.body())) {
            samples.put(field, sample(field));
        }
        return new TemplateDtos.PreviewResult(
                resolver.render(request.subject(), samples),
                resolver.render(request.body(), samples),
                List.copyOf(samples.keySet()),
                samples.values().stream().toList());
    }

    @Transactional(readOnly = true)
    public EmailTemplate require(CurrentPrincipal principal, UUID id) {
        return templates.findByIdAndWorkspaceIdAndDeletedAtIsNull(id.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Template not found"));
    }

    private String sample(String field) {
        return switch (field) {
            case "lead.first_name" -> "Asha";
            case "lead.last_name" -> "Menon";
            case "lead.company_name" -> "Northwind Logistics";
            case "lead.requirement" -> "Multi-warehouse inventory visibility";
            case "lead.location" -> "Chennai";
            case "lead.timeline" -> "This quarter";
            case "contact.full_name" -> "Asha Menon";
            case "contact.title" -> "Head of operations";
            case "account.name" -> "Northwind Logistics";
            case "rep.display_name" -> principalDisplay();
            case "rep.email" -> "rep@infeara.example";
            case "workspace.name" -> "Infeara Technologies";
            case "workspace.website" -> "https://infeara.example";
            case "meeting.link" -> "https://calendly.com/infeara/discovery";
            case "unsubscribe_url" -> "https://app.example/unsubscribe?token=sample";
            case "privacy_url" -> "https://infeara.example/privacy";
            case "opportunity.name" -> "Northwind — warehouse platform";
            case "opportunity.stage" -> "Discovery";
            default -> "";
        };
    }

    private String principalDisplay() {
        return "Sales Representative";
    }

    private String toJson(java.util.Set<String> fields) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (String field : fields) {
            if (!first) {
                builder.append(',');
            }
            builder.append('"').append(field).append('"');
            first = false;
        }
        return builder.append(']').toString();
    }

    private TemplateDtos.TemplateView toView(EmailTemplate template) {
        List<String> fields = parseJson(template.getMergeFieldsJson());
        return new TemplateDtos.TemplateView(UUID.fromString(template.getId()), template.getName(),
                template.getPurpose(), template.getSubjectTemplate(), template.getBodyTemplate(), fields,
                template.getStatus(), template.getVersion(), template.getApprovedAt(), template.getUpdatedAt(),
                template.getRowVersion());
    }

    private List<String> parseJson(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        String body = json.trim().replaceAll("^\\[|]$", "");
        if (body.isBlank()) {
            return List.of();
        }
        List<String> fields = new java.util.ArrayList<>();
        for (String part : body.split(",")) {
            fields.add(part.trim().replaceAll("^\"|\"$", ""));
        }
        return fields;
    }
}
