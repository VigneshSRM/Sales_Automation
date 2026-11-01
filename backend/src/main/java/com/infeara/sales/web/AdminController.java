package com.infeara.sales.web;

import com.infeara.sales.automation.job.Job;
import com.infeara.sales.automation.job.JobEngine;
import com.infeara.sales.automation.job.JobRepository;
import com.infeara.sales.automation.outbox.OutboxEventRepository;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * System administration: durable job inspection/replay and integration health.
 * Integration rows for SMTP/Calendly/AI are added by their releases; until then the
 * health payload reports them as not configured rather than pretending they are fine.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration", description = "Jobs, outbox and integration health")
public class AdminController {

    private final JobRepository jobs;
    private final JobEngine jobEngine;
    private final OutboxEventRepository outbox;
    private final CurrentPrincipalProvider principalProvider;
    private final CapabilityEvaluator capabilities;

    public AdminController(JobRepository jobs, JobEngine jobEngine, OutboxEventRepository outbox,
                           CurrentPrincipalProvider principalProvider, CapabilityEvaluator capabilities) {
        this.jobs = jobs;
        this.jobEngine = jobEngine;
        this.outbox = outbox;
        this.principalProvider = principalProvider;
        this.capabilities = capabilities;
    }

    @GetMapping("/jobs")
    @Operation(summary = "List durable jobs by status (PENDING, RETRY, DEAD, COMPLETED)")
    public ApiResponse<PageResponse<JobView>> listJobs(@RequestParam(defaultValue = "DEAD") String status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "25") int size) {
        CurrentPrincipal principal = principalProvider.principal();
        capabilities.require(principal, "jobs.manage");
        Page<Job> result = "ALL".equalsIgnoreCase(status)
                ? jobs.findByWorkspaceId(principal.workspaceId(), PageRequest.of(page, Math.min(size, 100)))
                : jobs.findByWorkspaceIdAndStatus(principal.workspaceId(), status,
                        PageRequest.of(page, Math.min(size, 100)));
        List<JobView> items = result.getContent().stream()
                .map(job -> new JobView(UUID.fromString(job.getId()), job.getJobType(), job.getStatus(),
                        job.getAttemptCount(), job.getMaxAttempts(), job.getLastError(), job.getAvailableAt(),
                        job.getCompletedAt()))
                .toList();
        return ApiResponse.ok(PageResponse.of(items, page, Math.min(size, 100), result.getTotalElements(),
                "createdAt:desc", List.of()), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/jobs/{id}/replay")
    @Operation(summary = "Replay a dead or failed job (audited)")
    public ApiResponse<Map<String, String>> replay(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        capabilities.require(principal, "jobs.manage");
        jobEngine.replay(principal.workspaceId(), id.toString(), principal.userId(), principal.email());
        return ApiResponse.ok(Map.of("status", "REQUEUED"), CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/automation-health")
    @Operation(summary = "Outbox and job backlog health")
    public ApiResponse<AutomationHealth> automationHealth() {
        CurrentPrincipal principal = principalProvider.principal();
        capabilities.require(principal, "jobs.manage");
        return ApiResponse.ok(new AutomationHealth(outbox.countByStatus("PENDING"),
                outbox.countByStatus("DEAD"), jobs.countByStatus("PENDING") + jobs.countByStatus("RETRY"),
                jobs.countByStatus("DEAD")), CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/integration-health")
    @Operation(summary = "Status of each configured integration")
    public ApiResponse<List<IntegrationHealth>> integrationHealth() {
        CurrentPrincipal principal = principalProvider.principal();
        capabilities.require(principal, "integrations.manage");
        return ApiResponse.ok(List.of(
                new IntegrationHealth("SMTP / IMAP", "NOT_CONFIGURED",
                        "Delivered in Release 3 — see DEVELOPMENT_STATUS.md",
                        "No sender profile is configured; outbound email is disabled."),
                new IntegrationHealth("Calendly", "NOT_CONFIGURED",
                        "Delivered in Release 4 — webhook verification is implemented in the schema-ready design",
                        "Booking links and webhook subscriptions are not configured."),
                new IntegrationHealth("AI providers", "NOT_CONFIGURED",
                        "Delivered in Release 5", "No provider credentials are configured; agents are disabled.")),
                CorrelationIdFilter.currentRequestId());
    }

    public record JobView(UUID id, String jobType, String status, int attemptCount, int maxAttempts,
                          String lastError, java.time.Instant availableAt, java.time.Instant completedAt) {
    }

    public record AutomationHealth(long pendingOutbox, long deadOutbox, long pendingJobs, long deadJobs) {
    }

    public record IntegrationHealth(String integration, String status, String release, String detail) {
    }
}
