package com.infeara.sales.crm.lead;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.paging.PageQuery;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.crm.dto.LeadDtos;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lead endpoints (spec §12 endpoint catalogue). */
@RestController
@RequestMapping("/api/v1/leads")
@Tag(name = "Leads", description = "Capture, qualify, assign and merge leads")
public class LeadController {

    private static final Set<String> ALLOWED_FILTERS = Set.of("status", "owner", "q", "source", "service",
            "scoreBand", "unassigned", "minScore");

    private final LeadService leadService;
    private final CurrentPrincipalProvider principalProvider;

    public LeadController(LeadService leadService, CurrentPrincipalProvider principalProvider) {
        this.leadService = leadService;
        this.principalProvider = principalProvider;
    }

    @PostMapping
    @Operation(summary = "Create a lead (idempotent)")
    public ResponseEntity<ApiResponse<LeadDtos.LeadDetail>> create(
            @Valid @RequestBody LeadDtos.CreateLeadRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var principal = principalProvider.principal();
        var created = leadService.create(principal, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created.detail(), CorrelationIdFilter.currentRequestId()));
    }

    @GetMapping
    @Operation(summary = "Search, filter, sort and paginate leads")
    public ApiResponse<PageResponse<LeadDtos.LeadSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) Map<String, String> filters) {
        var query = PageQuery.of(page, size, sort, direction, filters, ALLOWED_FILTERS);
        return ApiResponse.ok(leadService.list(principalProvider.principal(), query),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lead detail with score breakdown, duplicates and allowed transitions")
    public ApiResponse<LeadDtos.LeadDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(leadService.get(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update editable lead fields (rowVersion required)")
    public ApiResponse<LeadDtos.LeadDetail> update(@PathVariable UUID id,
                                                   @Valid @RequestBody LeadDtos.PatchLeadRequest request) {
        var updated = leadService.update(principalProvider.principal(), id, request);
        return ApiResponse.ok(updated.detail(), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/transition")
    @Operation(summary = "Move a lead through the lifecycle graph")
    public ApiResponse<LeadDtos.LeadDetail> transition(@PathVariable UUID id,
                                                       @Valid @RequestBody LeadDtos.TransitionRequest request) {
        return ApiResponse.ok(leadService.transition(principalProvider.principal(), id, request).detail(),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "Assign or reassign the lead owner")
    public ApiResponse<LeadDtos.LeadDetail> assign(@PathVariable UUID id,
                                                   @Valid @RequestBody LeadDtos.AssignRequest request) {
        return ApiResponse.ok(leadService.assign(principalProvider.principal(), id, request).detail(),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/qualify")
    @Operation(summary = "Run qualification scoring and persist the factor breakdown")
    public ApiResponse<LeadDtos.LeadDetail> qualify(@PathVariable UUID id,
                                                    @Valid @RequestBody LeadDtos.QualifyRequest request) {
        return ApiResponse.ok(leadService.qualify(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/score-override")
    @Operation(summary = "Human score override (reason required, audited)")
    public ApiResponse<LeadDtos.LeadDetail> overrideScore(@PathVariable UUID id,
                                                          @Valid @RequestBody
                                                          LeadDtos.OverrideScoreRequest request) {
        return ApiResponse.ok(leadService.overrideScore(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/merge")
    @Operation(summary = "Merge a duplicate lead into a target lead")
    public ApiResponse<LeadDtos.LeadDetail> merge(@Valid @RequestBody LeadDtos.MergeRequest request) {
        return ApiResponse.ok(leadService.merge(principalProvider.principal(), request).detail(),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/bulk-assign")
    @Operation(summary = "Bulk assign owners with a per-record failure report")
    public ApiResponse<LeadDtos.BulkAssignResult> bulkAssign(
            @Valid @RequestBody LeadDtos.BulkAssignRequest request) {
        return ApiResponse.ok(leadService.bulkAssign(principalProvider.principal(), request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive (soft delete) a lead")
    public ApiResponse<Map<String, String>> archive(@PathVariable UUID id,
                                                   @RequestBody(required = false) Map<String, String> body) {
        leadService.archive(principalProvider.principal(), id, body == null ? null : body.get("reason"));
        return ApiResponse.ok(Map.of("status", "ARCHIVED"), CorrelationIdFilter.currentRequestId());
    }
}
