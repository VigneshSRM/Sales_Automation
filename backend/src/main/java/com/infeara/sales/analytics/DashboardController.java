package com.infeara.sales.analytics;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Executive KPIs and the workspace activity feed")
public class DashboardController {

    private final DashboardService dashboard;
    private final ActivityFeedService activityFeed;
    private final CurrentPrincipalProvider principalProvider;

    public DashboardController(DashboardService dashboard, ActivityFeedService activityFeed,
                               CurrentPrincipalProvider principalProvider) {
        this.dashboard = dashboard;
        this.activityFeed = activityFeed;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "KPI payload with metric definitions and limitations")
    public ApiResponse<DashboardService.DashboardView> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(dashboard.dashboard(principalProvider.principal(), from, to),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/activity")
    @Operation(summary = "Recent workspace activity")
    public ApiResponse<PageResponse<ActivityFeedService.FeedItem>> activity(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(activityFeed.feed(principalProvider.principal(), page, size),
                CorrelationIdFilter.currentRequestId());
    }
}
