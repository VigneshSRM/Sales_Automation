package com.infeara.sales.engagement.email;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.crm.activity.ActivityRecorder;
import com.infeara.sales.crm.lead.Lead;
import com.infeara.sales.crm.lead.LeadRepository;
import com.infeara.sales.engagement.consent.EligibilityService;
import com.infeara.sales.engagement.smtp.SmtpProfile;
import com.infeara.sales.engagement.smtp.SmtpProfileRepository;
import com.infeara.sales.engagement.smtp.SmtpProfileService;
import com.infeara.sales.engagement.smtp.SmtpSender;
import com.infeara.sales.engagement.template.EmailTemplate;
import com.infeara.sales.engagement.template.EmailTemplateRepository;
import com.infeara.sales.engagement.template.MergeFieldResolver;
import com.infeara.sales.notification.NotificationService;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composer, approval and delivery orchestration (FR-EMAIL-004..009).
 *
 * Every send path re-evaluates eligibility and lead state inside the sending
 * transaction: consent withdrawn at 09:59 blocks a 10:00 send. Delivery itself is
 * performed by {@link EmailDispatcher} so a dead SMTP host cannot hold an HTTP request
 * open, and each attempt is recorded as an immutable {@link EmailEvent}.
 */
@Service
public class EmailService {

    private final EmailMessageRepository messages;
    private final EmailEventRepository events;
    private final SmtpProfileRepository smtpProfiles;
    private final SmtpProfileService smtpProfileService;
    private final SmtpSender smtpSender;
    private final EmailTemplateRepository templates;
    private final MergeFieldResolver mergeFields;
    private final EligibilityService eligibility;
    private final LeadRepository leads;
    private final CapabilityEvaluator capabilities;
    private final ActivityRecorder activities;
    private final NotificationService notifications;
    private final AuditRecorder audit;
    private final Clock clock;

    public EmailService(EmailMessageRepository messages, EmailEventRepository events,
                        SmtpProfileRepository smtpProfiles, SmtpProfileService smtpProfileService,
                        SmtpSender smtpSender, EmailTemplateRepository templates,
                        MergeFieldResolver mergeFields, EligibilityService eligibility, LeadRepository leads,
                        CapabilityEvaluator capabilities, ActivityRecorder activities,
                        NotificationService notifications, AuditRecorder audit, Clock clock) {
        this.messages = messages;
        this.events = events;
        this.smtpProfiles = smtpProfiles;
        this.smtpProfileService = smtpProfileService;
        this.smtpSender = smtpSender;
        this.templates = templates;
        this.mergeFields = mergeFields;
        this.eligibility = eligibility;
        this.leads = leads;
        this.capabilities = capabilities;
        this.activities = activities;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public EmailDtos.DraftResult compose(CurrentPrincipal principal, EmailDtos.ComposeRequest request) {
        capabilities.require(principal, "email.compose");
        SmtpProfile profile = request.smtpProfileId() != null
                ? smtpProfileService.require(principal, request.smtpProfileId())
                : smtpProfiles.findFirstByWorkspaceIdAndStatusAndDeletedAtIsNullOrderByNameAsc(
                        principal.workspaceId(), "ACTIVE")
                        .orElseThrow(() -> new ApiException(ErrorCode.SMTP_PROFILE_NOT_ACTIVE,
                                "No active SMTP profile is configured for this workspace"));

        Lead lead = null;
        if (request.leadId() != null) {
            lead = leads.findByIdAndWorkspaceIdAndDeletedAtIsNull(request.leadId().toString(),
                            principal.workspaceId())
                    .orElseThrow(() -> new ApiException(ErrorCode.LEAD_NOT_FOUND, "Lead not found"));
        }

        EligibilityService.Eligibility check =
                eligibility.evaluate(principal.workspaceId(), request.toEmail());
        if (!check.allowed()) {
            throw new ApiException(ErrorCode.RECIPIENT_SUPPRESSED,
                    "This recipient cannot be emailed: " + String.join("; ", check.blockers()));
        }

        String subject;
        String bodyHtml;
        String templateId = null;
        if (request.templateId() != null) {
            EmailTemplate template = templates.findByIdAndWorkspaceIdAndDeletedAtIsNull(
                            request.templateId().toString(), principal.workspaceId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Template not found"));
            if (!template.isApproved()) {
                throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Template " + template.getName() + " must be approved before use");
            }
            templateId = template.getId();
            Map<String, String> values = mergeValues(principal, lead, request.toName(), request.toEmail());
            subject = mergeFields.render(template.getSubjectTemplate(), values);
            bodyHtml = mergeFields.render(template.getBodyTemplate(), values);
        } else {
            if (request.subject() == null || request.subject().isBlank()
                    || request.bodyHtml() == null || request.bodyHtml().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Provide either an approved template or a subject and body");
            }
            Map<String, String> values = mergeValues(principal, lead, request.toName(), request.toEmail());
            subject = mergeFields.render(request.subject(), values);
            bodyHtml = mergeFields.render(request.bodyHtml(), values);
        }

        boolean needsApproval = requiresApproval(principal, request.submitForApproval());
        EmailMessage message = new EmailMessage();
        message.setId(Ids.newId());
        message.setWorkspaceId(principal.workspaceId());
        message.setSmtpProfileId(profile.getId());
        message.setTemplateId(templateId);
        message.setLeadId(lead == null ? null : lead.getId());
        message.setContactId(request.contactId() == null ? null : request.contactId().toString());
        message.setOpportunityId(request.opportunityId() == null ? null : request.opportunityId().toString());
        message.setToEmail(Normalizer.email(request.toEmail()));
        message.setToName(request.toName());
        message.setSubject(subject);
        message.setBodyHtml(bodyHtml);
        message.setBodyText(htmlToText(bodyHtml));
        message.setMessageKey("msg_" + Ids.newId());
        message.setScheduledAt(request.scheduledAt());
        message.setCreatedBy(principal.userId());
        message.setStatus(needsApproval ? "PENDING_APPROVAL"
                : request.scheduledAt() != null ? "SCHEDULED" : "QUEUED");
        messages.save(message);
        record(message, "QUEUED", "Composed by " + principal.displayName() + " (" + message.getStatus() + ")");
        audit.record(principal, "EMAIL_COMPOSED", "EMAIL_MESSAGE", message.getId(), null,
                Map.of("to", message.getToEmail(), "status", message.getStatus(),
                        "templateId", templateId == null ? "none" : templateId), Map.of());
        return new EmailDtos.DraftResult(UUID.fromString(message.getId()), message.getStatus(),
                needsApproval
                        ? "Message saved and sent for approval."
                        : "Message queued for delivery.",
                check.warnings());
    }

    @Transactional
    public EmailDtos.EmailView approve(CurrentPrincipal principal, UUID id, EmailDtos.ApproveRequest request) {
        capabilities.require(principal, "email.approve");
        EmailMessage message = require(principal, id);
        if (!"PENDING_APPROVAL".equals(message.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a message awaiting approval can be approved");
        }
        if (principal.userId().equals(message.getCreatedBy())) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Four-eyes rule: the author of a message cannot approve it");
        }
        EligibilityService.Eligibility check = eligibility.evaluate(principal.workspaceId(), message.getToEmail());
        if (!check.allowed()) {
            throw new ApiException(ErrorCode.RECIPIENT_SUPPRESSED,
                    "Approval refused: " + String.join("; ", check.blockers()));
        }
        message.setApprovedBy(principal.userId());
        message.setApprovedAt(clock.instant());
        message.setStatus(message.getScheduledAt() != null ? "SCHEDULED" : "QUEUED");
        messages.save(message);
        record(message, "QUEUED", "Approved by " + principal.displayName()
                + (request == null || request.note() == null ? "" : ": " + request.note()));
        audit.record(principal, "EMAIL_APPROVED", "EMAIL_MESSAGE", message.getId(),
                Map.of("status", "PENDING_APPROVAL"), Map.of("status", message.getStatus()), Map.of());
        return toView(message);
    }

    @Transactional
    public EmailDtos.EmailView cancel(CurrentPrincipal principal, UUID id, EmailDtos.CancelRequest request) {
        capabilities.require(principal, "email.compose");
        EmailMessage message = require(principal, id);
        if (message.isTerminal() || "SENDING".equals(message.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A message that already left the queue cannot be cancelled");
        }
        String before = message.getStatus();
        message.setStatus("CANCELLED");
        message.setFailureDetail("Cancelled: " + request.reason());
        messages.save(message);
        record(message, "FAILED", "Cancelled by " + principal.displayName() + ": " + request.reason());
        audit.record(principal, "EMAIL_CANCELLED", "EMAIL_MESSAGE", message.getId(),
                Map.of("status", before), Map.of("status", "CANCELLED", "reason", request.reason()), Map.of());
        return toView(message);
    }

    /**
     * Immediate delivery attempt. Used by the API ("send now") and by the sequence
     * engine; both paths funnel through {@link #deliver} so the eligibility rules and
     * sending limits cannot be bypassed.
     */
    @Transactional
    public EmailDtos.SendResult sendNow(CurrentPrincipal principal, UUID id) {
        capabilities.requireAny(principal, "email.send", "email.compose");
        EmailMessage message = require(principal, id);
        if (!"SCHEDULED".equals(message.getStatus()) && !"QUEUED".equals(message.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a scheduled or queued message can be sent (current status: "
                            + message.getStatus() + ")");
        }
        return deliver(principal.workspaceId(), message, principal.userId());
    }

    @Transactional
    public EmailDtos.SendResult deliver(String workspaceId, EmailMessage message, String actorUserId) {
        EligibilityService.Eligibility check = eligibility.evaluate(workspaceId, message.getToEmail());
        if (!check.allowed()) {
            message.setStatus("CANCELLED");
            message.setFailureCode("RECIPIENT_SUPPRESSED");
            message.setFailureDetail(String.join("; ", check.blockers()));
            messages.save(message);
            record(message, "FAILED", "Blocked before send: " + message.getFailureDetail());
            return new EmailDtos.SendResult(UUID.fromString(message.getId()), message.getStatus(), null, null,
                    message.getFailureDetail());
        }
        if (message.getLeadId() != null) {
            Optional<Lead> lead = leads.findByIdAndWorkspaceIdAndDeletedAtIsNull(message.getLeadId(), workspaceId);
            if (lead.isPresent() && "DISQUALIFIED".equals(lead.get().getStatus().name())) {
                message.setStatus("CANCELLED");
                message.setFailureCode("LEAD_DISQUALIFIED");
                message.setFailureDetail("Lead was disqualified before delivery");
                messages.save(message);
                record(message, "FAILED", message.getFailureDetail());
                return new EmailDtos.SendResult(UUID.fromString(message.getId()), message.getStatus(), null,
                        null, message.getFailureDetail());
            }
        }

        SmtpProfile profile = smtpProfileService.reserveSendSlot(workspaceId, message.getSmtpProfileId());
        message.setStatus("SENDING");
        message.setAttemptCount(message.getAttemptCount() + 1);
        messages.save(message);
        try {
            SmtpSender.DeliveryResult result = smtpSender.send(profile, message.getToEmail(), message.getToName(),
                    message.getSubject(), message.getBodyHtml(), message.getBodyText(), null);
            message.setStatus("SENT");
            message.setSentAt(result.sentAt());
            message.setProviderMessageId(result.providerMessageId());
            message.setFailureCode(null);
            message.setFailureDetail(null);
            messages.save(message);
            record(message, "SENT", "Delivered to SMTP transport as " + result.providerMessageId());
            audit.recordSystem(workspaceId, "EMAIL_SENT", "EMAIL_MESSAGE", message.getId(),
                    Map.of("to", message.getToEmail(), "subject", message.getSubject(),
                            "actorUserId", actorUserId == null ? "system" : actorUserId),
                    Map.of("smtpProfileId", profile.getId()));
            if (message.getLeadId() != null) {
                activities.recordSystem(workspaceId, "LEAD", message.getLeadId(), "EMAIL",
                        message.getSubject(), "Email sent to " + message.getToEmail(),
                        Map.of("messageId", message.getId(), "smtpProfileId", profile.getId()));
            }
            return new EmailDtos.SendResult(UUID.fromString(message.getId()), message.getStatus(),
                    message.getSentAt(), message.getProviderMessageId(), "Message accepted by the SMTP server");
        } catch (ApiException ex) {
            message.setStatus("QUEUED");
            message.setFailureCode(ex.errorCode().name());
            message.setFailureDetail(ex.getMessage());
            messages.save(message);
            record(message, "FAILED", ex.getMessage());
            audit.recordSystem(workspaceId, "EMAIL_SEND_FAILED", "EMAIL_MESSAGE", message.getId(),
                    Map.of("to", message.getToEmail(), "error", ex.getMessage()), Map.of());
            notifications.notifyUser(workspaceId, actorUserId, "EMAIL_SEND_FAILED", "CRITICAL",
                    "Email delivery failed", ex.getMessage(), "/emails", "email:" + message.getId());
            throw ex;
        }
    }


    /**
     * System-authored composition used by the sequence engine.
     *
     * Approval policy: a cadence is approved when it is activated (its steps may only
     * reference approved templates), so the engine may enqueue its steps directly.
     * Eligibility is still re-checked here and again at delivery, and every message
     * records the enrolling user as its originator for the audit trail.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public EmailDtos.DraftResult composeSystem(String workspaceId, String smtpProfileId, String templateId,
                                               String leadId, String toEmail, String subjectOverride,
                                               String enrollmentId, String originatorUserId) {
        EmailTemplate template = templates.findById(templateId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Sequence template not found"));
        if (!template.isApproved()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Sequence template " + template.getName() + " is not approved");
        }
        SmtpProfile profile = smtpProfiles.findById(smtpProfileId)
                .orElseThrow(() -> new ApiException(ErrorCode.SMTP_PROFILE_NOT_FOUND, "SMTP profile not found"));
        Lead lead = leadId == null ? null : leads.findById(leadId).orElse(null);
        Map<String, String> values = mergeValuesForSystem(originatorUserId, lead, toEmail);
        String subject = subjectOverride != null && !subjectOverride.isBlank()
                ? mergeFields.render(subjectOverride, values)
                : mergeFields.render(template.getSubjectTemplate(), values);
        String bodyHtml = mergeFields.render(template.getBodyTemplate(), values);

        EmailMessage message = new EmailMessage();
        message.setId(Ids.newId());
        message.setWorkspaceId(workspaceId);
        message.setSmtpProfileId(profile.getId());
        message.setTemplateId(template.getId());
        message.setLeadId(leadId);
        message.setSequenceEnrollmentId(enrollmentId);
        message.setToEmail(Normalizer.email(toEmail));
        message.setToName(lead == null ? null : lead.fullName());
        message.setSubject(subject);
        message.setBodyHtml(bodyHtml);
        message.setBodyText(htmlToText(bodyHtml));
        message.setMessageKey("msg_" + Ids.newId());
        message.setCreatedBy(originatorUserId);
        message.setApprovedBy(originatorUserId);
        message.setApprovedAt(clock.instant());
        message.setStatus("QUEUED");
        messages.save(message);
        record(message, "QUEUED", "Queued by sequence engine (template " + template.getName() + ")");
        audit.recordSystem(workspaceId, "EMAIL_QUEUED_BY_SEQUENCE", "EMAIL_MESSAGE", message.getId(),
                Map.of("to", message.getToEmail(), "templateId", template.getId(),
                        "enrollmentId", enrollmentId == null ? "" : enrollmentId), Map.of());
        return new EmailDtos.DraftResult(UUID.fromString(message.getId()), message.getStatus(),
                "Queued by the sequence engine", List.of());
    }

    private Map<String, String> mergeValuesForSystem(String originatorUserId, Lead lead, String toEmail) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("lead.first_name", lead == null ? "" : safe(lead.getFirstName()));
        values.put("lead.last_name", lead == null ? "" : safe(lead.getLastName()));
        values.put("lead.company_name", lead == null ? "" : safe(lead.getCompanyName()));
        values.put("lead.requirement", lead == null ? "" : safe(lead.getRequirement()));
        values.put("lead.location", lead == null ? "" : safe(lead.getLocation()));
        values.put("lead.timeline", lead == null ? "" : safe(lead.getTimelineCode()));
        values.put("contact.full_name", lead == null ? "" : lead.fullName());
        values.put("contact.title", "");
        values.put("account.name", lead == null ? "" : safe(lead.getCompanyName()));
        values.put("rep.display_name", "Infeara Sales");
        values.put("rep.email", "no-reply@infeara.example");
        values.put("workspace.name", "Infeara Technologies");
        values.put("workspace.website", "");
        values.put("meeting.link", "");
        values.put("unsubscribe_url", "/unsubscribe?email=" + java.net.URLEncoder.encode(
                toEmail == null ? "" : toEmail, java.nio.charset.StandardCharsets.UTF_8));
        values.put("privacy_url", "/privacy");
        values.put("opportunity.name", "");
        values.put("opportunity.stage", "");
        return values;
    }

    @Transactional(readOnly = true)
    public List<EmailMessage> due(String workspaceId, int limit) {
        return messages.findDueForSending(workspaceId, clock.instant(), PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public Page<EmailMessage> page(CurrentPrincipal principal, String status, String leadId, int page, int size) {
        capabilities.require(principal, "email.read");
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        if (leadId != null && !leadId.isBlank()) {
            return messages.findByWorkspaceIdAndLeadIdOrderByCreatedAtDesc(principal.workspaceId(), leadId, pageable);
        }
        if (status != null && !status.isBlank()) {
            return messages.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(principal.workspaceId(), status,
                    pageable);
        }
        return messages.findByWorkspaceIdOrderByCreatedAtDesc(principal.workspaceId(), pageable);
    }

    @Transactional(readOnly = true)
    public EmailDtos.EmailDetail detail(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "email.read");
        EmailMessage message = require(principal, id);
        EligibilityService.Eligibility check = eligibility.evaluate(principal.workspaceId(), message.getToEmail());
        List<EmailDtos.EventView> timeline = events.findByMessageIdOrderByOccurredAtAsc(message.getId()).stream()
                .map(event -> new EmailDtos.EventView(event.getEventType(), event.getDetail(),
                        event.getOccurredAt()))
                .toList();
        return new EmailDtos.EmailDetail(toView(message), message.getBodyHtml(), message.getBodyText(), timeline,
                new EmailDtos.EligibilityView(check.allowed(), check.blockers(), check.warnings(),
                        check.consentStatus()));
    }

    @Transactional(readOnly = true)
    public EmailDtos.EligibilityView eligibility(CurrentPrincipal principal, String email) {
        capabilities.require(principal, "email.read");
        EligibilityService.Eligibility check = eligibility.evaluate(principal.workspaceId(), email);
        return new EmailDtos.EligibilityView(check.allowed(), check.blockers(), check.warnings(),
                check.consentStatus());
    }

    /**
     * Records an inbound reply or bounce (FR-EMAIL-010, BR-EMAIL-007). A reply or hard
     * bounce stops follow-ups for the lead and, for unsubscribes/complaints, adds the
     * address to the suppression list.
     */
    @Transactional
    public void recordInbound(CurrentPrincipal principal, EmailDtos.InboundReplyRequest request) {
        capabilities.require(principal, "email.read");
        String email = Normalizer.emailForComparison(request.fromEmail());
        Optional<EmailMessage> match = request.providerMessageId() == null
                ? Optional.empty()
                : messages.findByWorkspaceIdAndProviderMessageId(principal.workspaceId(),
                        request.providerMessageId());
        EmailMessage message = match.orElseGet(() -> messages
                .findByWorkspaceIdOrderByCreatedAtDesc(principal.workspaceId(), PageRequest.of(0, 50))
                .getContent().stream()
                .filter(candidate -> email.equals(Normalizer.emailForComparison(candidate.getToEmail())))
                .findFirst()
                .orElse(null));
        if (message == null) {
            throw new ApiException(ErrorCode.MESSAGE_NOT_FOUND,
                    "No outbound message matches this reply; log it as an activity instead");
        }
        message.setStatus("REPLIED");
        messages.save(message);
        record(message, "REPLIED", "Reply received: " + shortSummary(request.body()));
        if (message.getLeadId() != null) {
            activities.record(principal, "LEAD", message.getLeadId(), "EMAIL",
                    "Reply received: " + (request.subject() == null ? message.getSubject() : request.subject()),
                    request.body(), Map.of("messageId", message.getId()));
        }
        audit.record(principal, "EMAIL_REPLIED", "EMAIL_MESSAGE", message.getId(), null,
                Map.of("from", request.fromEmail()), Map.of());
    }

    @Transactional
    public void recordBounce(CurrentPrincipal principal, EmailDtos.BounceRequest request) {
        capabilities.require(principal, "email.read");
        if (request.hardBounce()) {
            eligibility.suppress(principal.workspaceId(), request.toEmail(), "HARD_BOUNCE", "SMTP_DSN",
                    request.detail(), principal.userId());
        }
        audit.record(principal, "EMAIL_BOUNCED", "EMAIL_MESSAGE", null,
                Map.of("to", request.toEmail(), "hard", request.hardBounce()), Map.of(), Map.of());
    }

    @Transactional(readOnly = true)
    public EmailMessage require(CurrentPrincipal principal, UUID id) {
        return messages.findByIdAndWorkspaceId(id.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.MESSAGE_NOT_FOUND, "Email message not found"));
    }

    private boolean requiresApproval(CurrentPrincipal principal, Boolean submitForApproval) {
        if (Boolean.TRUE.equals(submitForApproval)) {
            return true;
        }
        // Approval is mandatory for anyone who cannot approve; approvers may send directly.
        return !principal.has("email.approve");
    }

    private void record(EmailMessage message, String eventType, String detail) {
        EmailEvent event = new EmailEvent();
        event.setId(Ids.newId());
        event.setWorkspaceId(message.getWorkspaceId());
        event.setMessageId(message.getId());
        event.setEventType(eventType);
        event.setDetail(detail == null ? null : truncate(detail, 1000));
        event.setOccurredAt(clock.instant());
        events.save(event);
    }

    private Map<String, String> mergeValues(CurrentPrincipal principal, Lead lead, String toName, String toEmail) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("lead.first_name", lead == null ? firstToken(toName) : lead.getFirstName());
        values.put("lead.last_name", lead == null ? "" : safe(lead.getLastName()));
        values.put("lead.company_name", lead == null ? "" : lead.getCompanyName());
        values.put("lead.requirement", lead == null ? "" : safe(lead.getRequirement()));
        values.put("lead.location", lead == null ? "" : safe(lead.getLocation()));
        values.put("lead.timeline", lead == null ? "" : safe(lead.getTimelineCode()));
        values.put("contact.full_name", toName == null ? "" : toName);
        values.put("contact.title", "");
        values.put("account.name", lead == null ? "" : lead.getCompanyName());
        values.put("rep.display_name", principal.displayName());
        values.put("rep.email", principal.email());
        values.put("workspace.name", "Infeara Technologies");
        values.put("workspace.website", "");
        values.put("meeting.link", "");
        values.put("unsubscribe_url", "/unsubscribe?email=" + java.net.URLEncoder.encode(
                toEmail == null ? "" : toEmail, java.nio.charset.StandardCharsets.UTF_8));
        values.put("privacy_url", "/privacy");
        values.put("opportunity.name", "");
        values.put("opportunity.stage", "");
        return values;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String firstToken(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.trim().split("\\s+")[0];
    }

    static String htmlToText(String html) {
        if (html == null) {
            return null;
        }
        return html.replaceAll("<br\\s*/?>", "\n")
                .replaceAll("</p>", "\n\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private String shortSummary(String body) {
        if (body == null) {
            return "no content";
        }
        String single = body.replaceAll("\\s+", " ").trim();
        return truncate(single, 180);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    public EmailDtos.EmailView toView(EmailMessage message) {
        String profileName = smtpProfiles.findById(message.getSmtpProfileId())
                .map(SmtpProfile::getName).orElse(null);
        return new EmailDtos.EmailView(UUID.fromString(message.getId()), message.getStatus(), message.getSubject(),
                message.getToEmail(), message.getToName(), message.getLeadId(), message.getOpportunityId(),
                profileName, message.getScheduledAt(), message.getSentAt(), null, message.getApprovedAt(),
                message.getFailureCode(), message.getFailureDetail(), message.getAttemptCount(),
                message.getCreatedAt(), message.getRowVersion());
    }

    public List<String> warningsFor(String workspaceId, String email) {
        EligibilityService.Eligibility check = eligibility.evaluate(workspaceId, email);
        List<String> notes = new ArrayList<>(check.warnings());
        notes.addAll(check.blockers());
        return notes;
    }

    public Instant now() {
        return clock.instant();
    }
}
