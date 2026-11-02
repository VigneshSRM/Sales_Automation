package com.infeara.sales.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stored idempotency record. Unique on (workspace, scope, key) so a duplicate
 * submission can never create a second lead/email/booking (spec §12, BR-EMAIL-002).
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyKey {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "workspace_id", length = 36, nullable = false)
    private String workspaceId;

    @Column(name = "scope", length = 80, nullable = false)
    private String scope;

    @Column(name = "key_value", length = 160, nullable = false)
    private String keyValue;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
