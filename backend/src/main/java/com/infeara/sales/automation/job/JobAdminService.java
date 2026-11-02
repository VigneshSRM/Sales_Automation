package com.infeara.sales.automation.job;

import com.infeara.sales.automation.outbox.OutboxEventRepository;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator surface for the durable job engine and the outbox. The capability check lives here, not
 * in the controller, so the transport layer stays a thin adapter and the architecture test
 * "controllers never depend on repositories" keeps holding as the admin surface grows.
 */
@Service
public class JobAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final JobRepository jobs;
    private final JobEngine jobEngine;
    private final OutboxEventRepository outbox;
    private final CapabilityEvaluator capabilities;

    public JobAdminService(JobRepository jobs, JobEngine jobEngine, OutboxEventRepository outbox,
                           CapabilityEvaluator capabilities) {
        this.jobs = jobs;
        this.jobEngine = jobEngine;
        this.outbox = outbox;
        this.capabilities = capabilities;
    }

    @Transactional(readOnly = true)
    public PageResponse<JobView> list(CurrentPrincipal principal, String status, int page, int size) {
        capabilities.require(principal, "jobs.manage");
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(0, page), pageSize);
        Page<Job> result = status == null || "ALL".equalsIgnoreCase(status)
                ? jobs.findByWorkspaceId(principal.workspaceId(), pageable)
                : jobs.findByWorkspaceIdAndStatus(principal.workspaceId(), status.toUpperCase(), pageable);
        List<JobView> items = result.getContent().stream()
                .map(job -> new JobView(UUID.fromString(job.getId()), job.getJobType(), job.getStatus(),
                        job.getAttemptCount(), job.getMaxAttempts(), job.getLastError(), job.getAvailableAt(),
                        job.getCompletedAt()))
                .toList();
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(),
                result.getTotalElements(), "createdAt:desc", List.of());
    }

    @Transactional
    public void replay(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "jobs.manage");
        jobEngine.replay(principal.workspaceId(), id.toString(), principal.userId(), principal.email());
    }

    @Transactional(readOnly = true)
    public AutomationHealth automationHealth(CurrentPrincipal principal) {
        capabilities.require(principal, "jobs.manage");
        return new AutomationHealth(outbox.countByStatus("PENDING"), outbox.countByStatus("DEAD"),
                jobs.countByStatus("PENDING") + jobs.countByStatus("RETRY"), jobs.countByStatus("DEAD"));
    }

    public record JobView(UUID id, String jobType, String status, int attemptCount, int maxAttempts,
                          String lastError, Instant availableAt, Instant completedAt) {
    }

    public record AutomationHealth(long pendingOutbox, long deadOutbox, long pendingJobs, long deadJobs) {
    }
}
