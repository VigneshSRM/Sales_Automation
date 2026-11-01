package com.infeara.sales.web;

import com.infeara.sales.audit.AuditLog;
import com.infeara.sales.audit.AuditLogRepository;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only audit access. There is deliberately no update or delete endpoint. */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit", description = "Append-only security audit trail")
public class AuditController {

    private final AuditLogRepository repository;
    private final CurrentPrincipalProvider principalProvider;
    private final CapabilityEvaluator capabilities;

    public AuditController(AuditLogRepository repository, CurrentPrincipalProvider principalProvider,
                           CapabilityEvaluator capabilities) {
        this.repository = repository;
        this.principalProvider = principalProvider;
        this.capabilities = capabilities;
    }

    @GetMapping
    @Operation(summary = "Query the audit trail (requires audit.read)")
    public ApiResponse<PageResponse<AuditView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        CurrentPrincipal principal = principalProvider.principal();
        capabilities.require(principal, "audit.read");
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<AuditLog> result = repository.search(principal.workspaceId(), blankToNull(objectType),
                blankToNull(objectId), blankToNull(actorUserId), from, to, pageable);
        List<AuditView> items = result.getContent().stream()
                .map(log -> new AuditView(UUID.fromString(log.getId()), log.getActorType(),
                        log.getActorUserId(), log.getAction(), log.getObjectType(), log.getObjectId(),
                        log.getOutcome(), log.getCorrelationId(), log.getOccurredAt(), log.getRecordHash(),
                        log.getPrevHash()))
                .toList();
        return ApiResponse.ok(PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(),
                result.getTotalElements(), "occurredAt:desc", List.of()),
                CorrelationIdFilter.currentRequestId());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record AuditView(UUID id, String actorType, String actorUserId, String action, String objectType,
                            String objectId, String outcome, String correlationId, Instant occurredAt,
                            String recordHash, String prevHash) {
    }
}
