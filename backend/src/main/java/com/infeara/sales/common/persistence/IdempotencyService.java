package com.infeara.sales.common.persistence;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.util.Hashing;
import com.infeara.sales.common.util.Ids;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent command support (spec §12, BR-EMAIL-002). A duplicate submission with the
 * same key returns the stored response instead of repeating the side effect; reusing a
 * key with a different payload is a conflict.
 */
@Service
public class IdempotencyService {

    private static final int RETENTION_HOURS = 24;

    private final IdempotencyKeyRepository repository;
    private final JsonCodec jsonCodec;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository repository, JsonCodec jsonCodec, Clock clock) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
        this.clock = clock;
    }

    /**
     * @return the previously stored response body when this key was already processed,
     *         or empty when the caller owns the execution and must call {@link #remember}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public java.util.Optional<String> replay(String workspaceId, String scope, String key, String requestHash) {
        if (key == null || key.isBlank()) {
            return java.util.Optional.empty();
        }
        if (key.length() > 160 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST,
                    "Idempotency-Key must be 1-160 characters of [A-Za-z0-9._:-]");
        }
        IdempotencyKey existing = repository.findByWorkspaceIdAndScopeAndKeyValue(workspaceId, scope, key)
                .orElse(null);
        if (existing == null) {
            IdempotencyKey record = new IdempotencyKey();
            record.setId(Ids.newId());
            record.setWorkspaceId(workspaceId);
            record.setScope(scope);
            record.setKeyValue(key);
            record.setRequestHash(requestHash);
            record.setExpiresAt(clock.instant().plusSeconds(RETENTION_HOURS * 3600L));
            repository.save(record);
            return java.util.Optional.empty();
        }
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This Idempotency-Key was already used with a different request payload");
        }
        if (existing.getResponseJson() == null) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "A request with this Idempotency-Key is still being processed");
        }
        return java.util.Optional.of(existing.getResponseJson());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(String workspaceId, String scope, String key, String requestHash, int status, Object body) {
        if (key == null || key.isBlank()) {
            return;
        }
        repository.findByWorkspaceIdAndScopeAndKeyValue(workspaceId, scope, key).ifPresent(record -> {
            record.setRequestHash(requestHash);
            record.setResponseStatus(status);
            record.setResponseJson(jsonCodec.write(body));
            repository.save(record);
        });
    }

    public String hashOf(Object request) {
        return Hashing.sha256Hex(jsonCodec.write(request));
    }

    public static String requireKeyHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "An Idempotency-Key header is required");
        }
        return headerValue.trim();
    }

    public Instant now() {
        return clock.instant();
    }
}
