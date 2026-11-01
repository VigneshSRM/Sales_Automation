package com.infeara.sales.sales.pipeline;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.sales.dto.OpportunityDtos;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/opportunities")
@Tag(name = "Opportunities", description = "Pipeline, stage transitions and forecast")
public class OpportunityController {

    private final OpportunityService opportunityService;
    private final CurrentPrincipalProvider principalProvider;

    public OpportunityController(OpportunityService opportunityService,
                                 CurrentPrincipalProvider principalProvider) {
        this.opportunityService = opportunityService;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "List opportunities (server-side paging, filtering and sorting)")
    public ApiResponse<PageResponse<OpportunityDtos.OpportunitySummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) String stageId,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String ownerUserId,
            @RequestParam(required = false, name = "q") String term) {
        var result = opportunityService.list(principalProvider.principal(), stageId, accountId, ownerUserId,
                term, page, size, sort, direction);
        return ApiResponse.ok(PageResponse.of(result.items(), page, Math.min(Math.max(1, size), 100),
                result.total(), (sort == null ? "updatedAt" : sort) + ":" + direction, List.of()),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/board")
    @Operation(summary = "Pipeline board grouped by stage with per-currency totals")
    public ApiResponse<OpportunityDtos.BoardView> board() {
        return ApiResponse.ok(opportunityService.board(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/forecast")
    @Operation(summary = "Forecast aggregates with metric definitions")
    public ApiResponse<OpportunityDtos.ForecastView> forecast(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? start.plusMonths(1).minusDays(1) : to;
        return ApiResponse.ok(opportunityService.forecast(principalProvider.principal(), start, end),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Opportunity workspace with stage history and available stages")
    public ApiResponse<OpportunityDtos.OpportunityDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(opportunityService.get(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping
    @Operation(summary = "Create an opportunity")
    public ResponseEntity<ApiResponse<OpportunityDtos.OpportunityDetail>> create(
            @Valid @RequestBody OpportunityDtos.CreateOpportunityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(opportunityService.create(principalProvider.principal(), request),
                        CorrelationIdFilter.currentRequestId()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update opportunity fields")
    public ApiResponse<OpportunityDtos.OpportunityDetail> update(
            @PathVariable UUID id, @Valid @RequestBody OpportunityDtos.PatchOpportunityRequest request) {
        return ApiResponse.ok(opportunityService.update(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/transition")
    @Operation(summary = "Validated stage transition (server-side gates)")
    public ApiResponse<OpportunityDtos.OpportunityDetail> transition(
            @PathVariable UUID id, @Valid @RequestBody OpportunityDtos.TransitionRequest request) {
        return ApiResponse.ok(opportunityService.transition(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }
}
