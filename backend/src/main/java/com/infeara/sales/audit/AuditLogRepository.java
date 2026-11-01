package com.infeara.sales.audit;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    @Query("""
            select a from AuditLog a
             where a.workspaceId = :workspaceId
               and (:objectType is null or a.objectType = :objectType)
               and (:objectId is null or a.objectId = :objectId)
               and (:actorUserId is null or a.actorUserId = :actorUserId)
               and (:from is null or a.occurredAt >= :from)
               and (:to is null or a.occurredAt <= :to)
            """)
    Page<AuditLog> search(@Param("workspaceId") String workspaceId,
                          @Param("objectType") String objectType,
                          @Param("objectId") String objectId,
                          @Param("actorUserId") String actorUserId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);

    Optional<AuditLog> findFirstByWorkspaceIdOrderByOccurredAtDesc(String workspaceId);
}
