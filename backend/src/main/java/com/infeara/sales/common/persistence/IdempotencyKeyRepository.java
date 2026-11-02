package com.infeara.sales.common.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    Optional<IdempotencyKey> findByWorkspaceIdAndScopeAndKeyValue(String workspaceId, String scope, String keyValue);
}
