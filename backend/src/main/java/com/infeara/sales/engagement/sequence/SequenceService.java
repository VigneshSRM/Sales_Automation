package com.infeara.sales.engagement.sequence;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.crm.activity.ActivityRecorder;
import com.infeara.sales.crm.lead.Lead;
import com.infeara.sales.crm.lead.LeadRepository;
import com.infeara.sales.engagement.consent.EligibilityService;
import com.infeara.sales.engagement.email.EmailDtos;
import com.infeara.sales.engagement.email.EmailService;
import com.infeara.sales.engagement.smtp.SmtpProfile;
import com.infeara.sales.engagement.smtp.SmtpProfileRepository;
import com.infeara.sales.engagement.template.EmailTemplate;
import com.infeara.sales.engagement.template.EmailTemplateRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Follow-up cadence management and the sequence engine (FR-SEQUENCE-001..004).
 *
 * Stop rules (BR-EMAIL-007): a reply, unsubscribe, complaint, hard bounce or a booked
 * meeting stops the enrollment — the engine asks the caller to stop, and the inbound
 * email handler calls {@link #stop} directly. The engine never re-enrols a stopped lead
 * automatically, so a reply can never be followed by "just checking in" a day later.
 */
@Service
public class SequenceService {

    private final SequenceRepository sequences;
    private final SequenceVersionRepository versions;
    private final SequenceStepRepository steps;
    private final SequenceEnrollmentRepository enrollments;
    private final SequenceStepRunRepository stepRuns;
    private final EmailTemplateRepository templates;
    private final SmtpProfileRepository smtpProfiles;
    private final EmailService emailService;
    private final EligibilityService eligibility;
    private final LeadRepository leads;
    private final CapabilityEvaluator capabilities;
    private final ActivityRecorder activities;
    private final AuditRecorder audit;
    private final Clock clock;

    public SequenceService(SequenceRepository sequences, SequenceVersionRepository versions,
                           SequenceStepRepository steps, SequenceEnrollmentRepository enrollments,
                           SequenceStepRunRepository stepRuns, EmailTemplateRepository templates,
                           SmtpProfileRepository smtpProfiles, EmailService emailService,
                           EligibilityService eligibility, LeadRepository leads,
                           CapabilityEvaluator capabilities, ActivityRecorder activities, AuditRecorder audit,
                           Clock clock) {
        this.sequences = sequences;
        this.versions = versions;
        this.steps = steps;
        this.enrollments = enrollments;
        this.stepRuns = stepRuns;
        this.templates = templates;
        this.smtpProfiles = smtpProfiles;
        this.emailService = emailService;
        this.eligibility = eligibility;
        this.leads = leads;
        this.capabilities = capabilities;
        this.activities = activities;
        this.audit = audit;
        this.clock = clock;
    }

    public record StepInput(int dayOffset, UUID templateId, String subjectOverride, Boolean stopOnReply) {
    }

    public record CreateSequenceRequest(String name, String description, List<StepInput> steps) {
    }

    public record StepView(int stepNo, int dayOffset, String templateName, String subjectOverride,
                           boolean stopOnReply) {
    }

    public record SequenceView(UUID id, String name, String description, String status, Integer activeVersion,
                               int stepCount, List<StepView> steps, Instant updatedAt, long rowVersion) {
    }

    public record EnrollmentView(UUID id, UUID sequenceId, String sequenceName, String leadName, String status,
                                 int currentStep, int totalSteps, Instant nextRunAt, Instant enrolledAt,
                                 String stopReason) {
    }

    public record EngineResult(int processed, int sent, int stopped, int skipped) {
    }

    @Transactional
    public SequenceView create(CurrentPrincipal principal, CreateSequenceRequest request) {
        capabilities.require(principal, "sequences.manage");
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A sequence name is required");
        }
        if (request.steps() == null || request.steps().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A sequence needs at least one step");
        }
        if (sequences.existsByWorkspaceIdAndNameAndDeletedAtIsNull(principal.workspaceId(), request.name().trim())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A sequence with that name already exists");
        }
        EmailSequence sequence = new EmailSequence();
        sequence.setId(Ids.newId());
        sequence.setWorkspaceId(principal.workspaceId());
        sequence.setName(request.name().trim());
        sequence.setDescription(request.description());
        sequence.setStatus("DRAFT");
        sequence.setCreatedBy(principal.userId());
        sequences.save(sequence);

        SequenceVersion version = new SequenceVersion();
        version.setId(Ids.newId());
        version.setWorkspaceId(principal.workspaceId());
        version.setSequenceId(sequence.getId());
        version.setVersion(1);
        version.setStatus("DRAFT");
        version.setCreatedBy(principal.userId());
        versions.save(version);

        int stepNo = 1;
        for (StepInput input : request.steps()) {
            // Captured by the validation lambda below, which requires an effectively-final value.
            final int currentStepNo = stepNo;
            if (input.templateId() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Every step must reference an approved template");
            }
            templates.findByIdAndWorkspaceIdAndDeletedAtIsNull(input.templateId().toString(),
                            principal.workspaceId())
                    .filter(EmailTemplate::isApproved)
                    .orElseThrow(() -> new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                            "Step " + currentStepNo + ": the template must exist and be approved"));
            SequenceStep step = new SequenceStep();
            step.setId(Ids.newId());
            step.setWorkspaceId(principal.workspaceId());
            step.setSequenceVersionId(version.getId());
            step.setStepNo(stepNo);
            step.setDayOffset(Math.max(0, input.dayOffset()));
            step.setTemplateId(input.templateId().toString());
            step.setSubjectOverride(input.subjectOverride());
            step.setStopOnReply(input.stopOnReply() == null || input.stopOnReply());
            steps.save(step);
            stepNo++;
        }
        audit.record(principal, "SEQUENCE_CREATED", "SEQUENCE", sequence.getId(), null,
                Map.of("name", sequence.getName(), "steps", String.valueOf(stepNo - 1)), Map.of());
        return toView(sequence);
    }

    @Transactional
    public SequenceView activate(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "sequences.manage");
        EmailSequence sequence = require(principal, id);
        SequenceVersion version = versions.findFirstBySequenceIdAndStatusOrderByVersionDesc(sequence.getId(),
                        "DRAFT")
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "There is no draft version to activate"));
        List<SequenceStep> versionSteps = steps.findBySequenceVersionIdOrderByStepNoAsc(version.getId());
        if (versionSteps.isEmpty()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "A sequence needs at least one step");
        }
        versions.findBySequenceIdOrderByVersionDesc(sequence.getId()).stream()
                .filter(other -> "ACTIVE".equals(other.getStatus()))
                .forEach(other -> {
                    other.setStatus("ARCHIVED");
                    versions.save(other);
                });
        version.setStatus("ACTIVE");
        versions.save(version);
        sequence.setActiveVersion(version.getVersion());
        sequence.setStatus("ACTIVE");
        sequences.save(sequence);
        audit.record(principal, "SEQUENCE_ACTIVATED", "SEQUENCE", sequence.getId(),
                Map.of("status", "DRAFT"), Map.of("status", "ACTIVE", "version", version.getVersion()), Map.of());
        return toView(sequence);
    }

    @Transactional
    public SequenceView pause(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "sequences.manage");
        EmailSequence sequence = require(principal, id);
        sequence.setStatus("PAUSED");
        sequences.save(sequence);
        return toView(sequence);
    }

    @Transactional
    public EnrollmentView enroll(CurrentPrincipal principal, UUID sequenceId, UUID leadId, UUID smtpProfileId) {
        capabilities.require(principal, "sequences.manage");
        EmailSequence sequence = require(principal, sequenceId);
        if (!"ACTIVE".equals(sequence.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only an active sequence can enrol leads");
        }
        SequenceVersion version = versions.findFirstBySequenceIdAndStatusOrderByVersionDesc(sequence.getId(),
                        "ACTIVE")
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "The sequence has no active version"));
        Lead lead = leads.findByIdAndWorkspaceIdAndDeletedAtIsNull(leadId.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.LEAD_NOT_FOUND, "Lead not found"));
        if (lead.getEmail() == null || lead.getEmail().isBlank()) {
            throw new ApiException(ErrorCode.CONSENT_MISSING, "The lead has no email address");
        }
        EligibilityService.Eligibility check = eligibility.evaluate(principal.workspaceId(), lead.getEmail());
        if (!check.allowed()) {
            throw new ApiException(ErrorCode.RECIPIENT_SUPPRESSED,
                    "The lead cannot be enrolled: " + String.join("; ", check.blockers()));
        }
        String activeKey = sequence.getId() + ":" + lead.getId();
        if (enrollments.findByWorkspaceIdAndActiveKey(principal.workspaceId(), activeKey).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "The lead is already enrolled in this sequence");
        }
        SmtpProfile profile = smtpProfileId == null
                ? smtpProfiles.findFirstByWorkspaceIdAndStatusAndDeletedAtIsNullOrderByNameAsc(
                        principal.workspaceId(), "ACTIVE").orElse(null)
                : smtpProfiles.findByIdAndWorkspaceIdAndDeletedAtIsNull(smtpProfileId.toString(),
                        principal.workspaceId()).orElse(null);
        if (profile == null) {
            throw new ApiException(ErrorCode.SMTP_PROFILE_NOT_ACTIVE,
                    "An active SMTP profile is required before enrolling leads");
        }

        SequenceEnrollment enrollment = new SequenceEnrollment();
        enrollment.setId(Ids.newId());
        enrollment.setWorkspaceId(principal.workspaceId());
        enrollment.setSequenceId(sequence.getId());
        enrollment.setSequenceVersionId(version.getId());
        enrollment.setLeadId(lead.getId());
        enrollment.setSmtpProfileId(profile.getId());
        enrollment.setStatus("ACTIVE");
        enrollment.setCurrentStep(0);
        enrollment.setActiveKey(activeKey);
        enrollment.setEnrolledBy(principal.userId());
        enrollment.setNextRunAt(clock.instant());
        enrollments.save(enrollment);
        activities.record(principal, "LEAD", lead.getId(), "SYSTEM",
                "Enrolled in sequence " + sequence.getName(), null,
                Map.of("enrollmentId", enrollment.getId(), "sequenceId", sequence.getId()));
        audit.record(principal, "SEQUENCE_ENROLLED", "SEQUENCE", sequence.getId(), null,
                Map.of("leadId", lead.getId(), "enrollmentId", enrollment.getId()), Map.of());
        return toView(enrollment, sequence, lead, steps.findBySequenceVersionIdOrderByStepNoAsc(version.getId()).size());
    }

    @Transactional
    public EnrollmentView stop(CurrentPrincipal principal, UUID enrollmentId, String reason) {
        capabilities.require(principal, "sequences.manage");
        SequenceEnrollment enrollment = enrollments.findByIdAndWorkspaceId(enrollmentId.toString(),
                        principal.workspaceId())
                .orElseThrow(() -> ApiException.notFound("Enrollment"));
        stopInternal(enrollment, reason == null ? "MANUAL" : reason);
        audit.record(principal, "SEQUENCE_STOPPED", "SEQUENCE_ENROLLMENT", enrollment.getId(),
                Map.of("status", "ACTIVE"),
                Map.of("status", enrollment.getStatus(), "reason", enrollment.getStopReason()), Map.of());
        EmailSequence sequence = sequences.findById(enrollment.getSequenceId()).orElse(null);
        Lead lead = leads.findById(enrollment.getLeadId()).orElse(null);
        int total = sequence == null ? 0
                : steps.findBySequenceVersionIdOrderByStepNoAsc(enrollment.getSequenceVersionId()).size();
        return toView(enrollment, sequence, lead, total);
    }

    /** Called by the inbound email handler when a reply or hard bounce arrives. */
    @Transactional
    public int stopAllForLead(String workspaceId, String leadId, String reason) {
        List<SequenceEnrollment> active = enrollments.findByWorkspaceIdAndLeadIdAndStatus(workspaceId, leadId,
                "ACTIVE");
        for (SequenceEnrollment enrollment : active) {
            stopInternal(enrollment, reason);
        }
        return active.size();
    }

    private void stopInternal(SequenceEnrollment enrollment, String reason) {
        enrollment.setStatus("STOPPED");
        enrollment.setStopReason(reason);
        enrollment.setStoppedAt(clock.instant());
        enrollment.setNextRunAt(null);
        // Clearing the active key frees the unique slot so the lead can be enrolled
        // again deliberately, and keeps the historical row for audit.
        enrollment.setActiveKey(null);
        enrollments.save(enrollment);
    }

    /**
     * Engine tick: for every due enrollment, create the next step's message through the
     * normal composer (so approval policy still applies) and advance the clock to the
     * following step's day offset.
     */
    @Transactional
    public EngineResult runDue(String workspaceId, int limit) {
        List<SequenceEnrollment> due = sequences.findDueEnrollments(workspaceId, clock.instant(),
                PageRequest.of(0, limit));
        int sent = 0;
        int stopped = 0;
        int skipped = 0;
        for (SequenceEnrollment enrollment : due) {
            List<SequenceStep> versionSteps =
                    steps.findBySequenceVersionIdOrderByStepNoAsc(enrollment.getSequenceVersionId());
            SequenceStep next = versionSteps.stream()
                    .filter(step -> step.getStepNo() > enrollment.getCurrentStep())
                    .findFirst().orElse(null);
            if (next == null) {
                enrollment.setStatus("COMPLETED");
                enrollment.setActiveKey(null);
                enrollment.setNextRunAt(null);
                enrollments.save(enrollment);
                continue;
            }
            Lead lead = leads.findById(enrollment.getLeadId()).orElse(null);
            if (lead == null || lead.getEmail() == null || lead.getDeletedAt() != null) {
                stopInternal(enrollment, "LEAD_UNAVAILABLE");
                stopped++;
                continue;
            }
            EligibilityService.Eligibility check = eligibility.evaluate(workspaceId, lead.getEmail());
            if (!check.allowed()) {
                stopInternal(enrollment, "RECIPIENT_SUPPRESSED");
                stopped++;
                continue;
            }
            EmailTemplate template = templates.findById(next.getTemplateId()).orElse(null);
            if (template == null || !template.isApproved()) {
                stopInternal(enrollment, "TEMPLATE_UNAVAILABLE");
                stopped++;
                continue;
            }
            SequenceStepRun run = new SequenceStepRun();
            run.setId(Ids.newId());
            run.setWorkspaceId(workspaceId);
            run.setEnrollmentId(enrollment.getId());
            run.setStepNo(next.getStepNo());
            run.setStatus("EXECUTED");
            EmailDtos.DraftResult draft = emailService.composeSystem(workspaceId, enrollment.getSmtpProfileId(),
                    template.getId(), lead.getId(), lead.getEmail(),
                    next.getSubjectOverride(), enrollment.getId(), enrollment.getEnrolledBy());
            run.setEmailMessageId(draft.id().toString());
            run.setDetail("Message " + draft.id() + " created with status " + draft.status());
            stepRuns.save(run);
            enrollment.setCurrentStep(next.getStepNo());
            SequenceStep following = versionSteps.stream()
                    .filter(step -> step.getStepNo() > next.getStepNo())
                    .findFirst().orElse(null);
            enrollment.setNextRunAt(following == null ? null
                    : atDayOffset(clock.instant(), following.getDayOffset() - next.getDayOffset()));
            if (following == null && "QUEUED".equals(draft.status())) {
                // Last step already dispatched; the enrollment completes once nothing is left.
                enrollment.setStatus("COMPLETED");
                enrollment.setActiveKey(null);
            }
            enrollments.save(enrollment);
            sent++;
        }
        return new EngineResult(due.size(), sent, stopped, skipped);
    }

    @Transactional(readOnly = true)
    public List<SequenceView> list(CurrentPrincipal principal) {
        capabilities.require(principal, "email.read");
        return sequences.findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(principal.workspaceId())
                .stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public Page<EnrollmentView> enrollmentPage(CurrentPrincipal principal, String status, int page, int size) {
        capabilities.require(principal, "email.read");
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<SequenceEnrollment> result = status == null || status.isBlank()
                ? enrollments.findByWorkspaceIdOrderByEnrolledAtDesc(principal.workspaceId(), pageable)
                : enrollments.findByWorkspaceIdAndStatusOrderByEnrolledAtDesc(principal.workspaceId(), status,
                        pageable);
        return result.map(enrollment -> {
            EmailSequence sequence = sequences.findById(enrollment.getSequenceId()).orElse(null);
            Lead lead = leads.findById(enrollment.getLeadId()).orElse(null);
            int total = steps.findBySequenceVersionIdOrderByStepNoAsc(enrollment.getSequenceVersionId()).size();
            return toView(enrollment, sequence, lead, total);
        });
    }

    @Transactional(readOnly = true)
    public EmailSequence require(CurrentPrincipal principal, UUID id) {
        return sequences.findByIdAndWorkspaceIdAndDeletedAtIsNull(id.toString(), principal.workspaceId())
                .orElseThrow(() -> ApiException.notFound("Sequence"));
    }

    private Instant atDayOffset(Instant from, int days) {
        LocalDate base = from.atZone(ZoneOffset.UTC).toLocalDate();
        return base.plusDays(Math.max(0, days)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private SequenceView toView(EmailSequence sequence) {
        SequenceVersion active = versions.findBySequenceIdOrderByVersionDesc(sequence.getId()).stream()
                .filter(version -> "ACTIVE".equals(version.getStatus()))
                .findFirst()
                .orElseGet(() -> versions.findBySequenceIdOrderByVersionDesc(sequence.getId()).stream()
                        .findFirst().orElse(null));
        List<StepView> stepViews = new ArrayList<>();
        if (active != null) {
            for (SequenceStep step : steps.findBySequenceVersionIdOrderByStepNoAsc(active.getId())) {
                String templateName = step.getTemplateId() == null ? null
                        : templates.findById(step.getTemplateId()).map(EmailTemplate::getName).orElse(null);
                stepViews.add(new StepView(step.getStepNo(), step.getDayOffset(), templateName,
                        step.getSubjectOverride(), step.isStopOnReply()));
            }
        }
        return new SequenceView(UUID.fromString(sequence.getId()), sequence.getName(), sequence.getDescription(),
                sequence.getStatus(), sequence.getActiveVersion(), stepViews.size(), stepViews,
                sequence.getUpdatedAt(), sequence.getRowVersion());
    }

    private EnrollmentView toView(SequenceEnrollment enrollment, EmailSequence sequence, Lead lead, int total) {
        return new EnrollmentView(UUID.fromString(enrollment.getId()),
                UUID.fromString(enrollment.getSequenceId()),
                sequence == null ? null : sequence.getName(),
                lead == null ? null : lead.fullName(), enrollment.getStatus(), enrollment.getCurrentStep(), total,
                enrollment.getNextRunAt(), enrollment.getEnrolledAt(), enrollment.getStopReason());
    }
}
