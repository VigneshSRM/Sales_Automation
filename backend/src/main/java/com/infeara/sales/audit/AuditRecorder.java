package com.infeara.sales.audit;

import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.util.Hashing;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CurrentPrincipal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit recording (FR-AUD-001/002, BR-SEC-002).
 *
 * <p>Records are hash-chained per workspace ({@code record_hash = SHA-256(prev_hash ||
 * canonical payload)}) so tampering with, or deletion of, a record is detectable by the
 * verification job. Records are written in the caller's transaction so that a business
 * change and its audit trail commit atomically; security events that have no business
 * transaction use {@link Propagation#REQUIRES_NEW}.
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditLogRepository repository;
    private final JsonCodec jsonCodec;

    public AuditRecorder(AuditLogRepository repository, JsonCodec jsonCodec) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void security(String workspaceId, String actorUserId, String action, String outcome, String ipAddress) {
        if (workspaceId == null) {
            // Workspace-less security events cannot join the per-workspace chain; they are
            // logged with correlation id only (no tenant, no personal data).
            log.warn("security_event action={} outcome={} actor={} ip_hash={}", action, outcome, actorUserId,
                    ipAddress == null ? null : Hashing.sha256Hex(ipAddress).substring(0, 12));
            return;
        }
        write(workspaceId, actorUserId, "USER", action, "SESSION", actorUserId, outcome,
                null, null, Map.of("ip", ipAddress == null ? "" : ipAddress));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CurrentPrincipal actor, String action, String objectType, String objectId,
                       Map<String, Object> before, Map<String, Object> after, Map<String, Object> metadata) {
        write(actor.workspaceId(), actor.userId(), "USER", action, objectType, objectId, "SUCCESS",
                before, after, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSystem(String workspaceId, String action, String objectType, String objectId,
                             Map<String, Object> after, Map<String, Object> metadata) {
        write(workspaceId, null, "SYSTEM", action, objectType, objectId, "SUCCESS", null, after, metadata);
    }

    private void write(String workspaceId, String actorUserId, String actorType, String action, String objectType,
                       String objectId, String outcome, Map<String, Object> before, Map<String, Object> after,
                       Map<String, Object> metadata) {
        AuditLog previous = repository.findFirstByWorkspaceIdOrderByOccurredAtDesc(workspaceId).orElse(null);
        String prevHash = previous == null ? null : previous.getRecordHash();
        String beforeJson = before == null ? null : jsonCodec.write(before);
        String afterJson = after == null ? null : jsonCodec.write(after);
        String metadataJson = metadata == null ? null : jsonCodec.write(metadata);
        String recordId = Ids.newId();
        String correlationId = CorrelationIdFilter.currentRequestId();

        String chainInput = (prevHash == null ? "" : prevHash) + "|" + recordId + "|" + workspaceId + "|"
                + actorType + "|" + (actorUserId == null ? "" : actorUserId) + "|" + action + "|" + objectType + "|"
                + (objectId == null ? "" : objectId) + "|" + outcome + "|" + (beforeJson == null ? "" : beforeJson)
                + "|" + (afterJson == null ? "" : afterJson) + "|" + correlationId;

        AuditLog entry = new AuditLog();
        entry.setId(recordId);
        entry.setWorkspaceId(workspaceId);
        entry.setActorType(actorType);
        entry.setActorUserId(actorUserId);
        entry.setAction(action);
        entry.setObjectType(objectType);
        entry.setObjectId(objectId);
        entry.setOutcome(outcome);
        entry.setBeforeJson(beforeJson);
        entry.setAfterJson(afterJson);
        entry.setMetadataJson(metadataJson);
        entry.setCorrelationId(correlationId);
        entry.setIpAddress(metadata != null && metadata.get("ip") != null
                ? String.valueOf(metadata.get("ip")) : null);
        entry.setPrevHash(prevHash);
        entry.setRecordHash(Hashing.sha256Hex(chainInput));
        repository.save(entry);
    }
}
