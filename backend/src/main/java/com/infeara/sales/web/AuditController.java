package com.infeara.sales.web;

import com.infeara.sales.audit.AuditQueryService;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only audit access. There is deliberately no update or delete endpoint. */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit", description = "Append-only security audit trail")
public class AuditController {

    private final AuditQueryService auditQuery;
    private final CurrentPrincipalProvider principalProvider;

    public AuditController(AuditQueryService auditQuery, CurrentPrincipalProvider principalProvider) {
        this.auditQuery = auditQuery;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "Query the audit trail (requires audit.read)")
    public ApiResponse<PageResponse<AuditQueryService.AuditEntry>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(auditQuery.search(principal, objectType, objectId, actorUserId, from, to, page, size),
                CorrelationIdFilter.currentRequestId());
    }
}
