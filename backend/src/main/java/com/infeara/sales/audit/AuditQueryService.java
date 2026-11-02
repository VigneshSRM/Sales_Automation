package com.infeara.sales.audit;

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
 * Read-only audit access with its capability check inside the service, so the controller stays a
 * transport adapter (ADR-001) and the architecture rule "controllers never touch repositories" holds.
 */
@Service
public class AuditQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository repository;
    private final CapabilityEvaluator capabilities;

    public AuditQueryService(AuditLogRepository repository, CapabilityEvaluator capabilities) {
        this.repository = repository;
        this.capabilities = capabilities;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> search(CurrentPrincipal principal, String objectType, String objectId,
                                           String actorUserId, Instant from, Instant to, int page, int size) {
        capabilities.require(principal, "audit.read");
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(0, page), pageSize);
        Page<AuditLog> result = repository.search(principal.workspaceId(), blankToNull(objectType),
                blankToNull(objectId), blankToNull(actorUserId), from, to, pageable);
        List<AuditEntry> items = result.getContent().stream()
                .map(log -> new AuditEntry(UUID.fromString(log.getId()), log.getActorType(),
                        log.getActorUserId(), log.getAction(), log.getObjectType(), log.getObjectId(),
                        log.getOutcome(), log.getCorrelationId(), log.getOccurredAt(), log.getRecordHash(),
                        log.getPrevHash()))
                .toList();
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(),
                result.getTotalElements(), "occurredAt:desc", List.of());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record AuditEntry(UUID id, String actorType, String actorUserId, String action, String objectType,
                             String objectId, String outcome, String correlationId, Instant occurredAt,
                             String recordHash, String prevHash) {
    }
}
