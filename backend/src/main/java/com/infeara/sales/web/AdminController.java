package com.infeara.sales.web;

import com.infeara.sales.automation.integration.IntegrationStatusService;
import com.infeara.sales.automation.job.JobAdminService;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * System administration: durable job inspection/replay and integration health.
 *
 * <p>Every number returned here is read from the database, and every integration status reflects
 * configuration that actually exists — an unconfigured provider is reported as such rather than
 * being presented as healthy.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration", description = "Jobs, outbox and integration health")
public class AdminController {

    private final JobAdminService jobAdmin;
    private final IntegrationStatusService integrations;
    private final CurrentPrincipalProvider principalProvider;

    public AdminController(JobAdminService jobAdmin, IntegrationStatusService integrations,
                           CurrentPrincipalProvider principalProvider) {
        this.jobAdmin = jobAdmin;
        this.integrations = integrations;
        this.principalProvider = principalProvider;
    }

    @GetMapping("/jobs")
    @Operation(summary = "List durable jobs by status (PENDING, RETRY, DEAD, COMPLETED)")
    public ApiResponse<PageResponse<JobAdminService.JobView>> listJobs(
            @RequestParam(defaultValue = "DEAD") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(jobAdmin.list(principal, status, page, size),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/jobs/{id}/replay")
    @Operation(summary = "Replay a dead or failed job (audited)")
    public ApiResponse<Map<String, String>> replay(@PathVariable UUID id) {
        jobAdmin.replay(principalProvider.principal(), id);
        return ApiResponse.ok(Map.of("status", "REQUEUED"), CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/automation-health")
    @Operation(summary = "Outbox and job backlog health")
    public ApiResponse<JobAdminService.AutomationHealth> automationHealth() {
        return ApiResponse.ok(jobAdmin.automationHealth(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/integration-health")
    @Operation(summary = "Status of each configured integration, read from configuration")
    public ApiResponse<List<IntegrationStatusService.IntegrationHealth>> integrationHealth() {
        return ApiResponse.ok(integrations.health(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }
}
