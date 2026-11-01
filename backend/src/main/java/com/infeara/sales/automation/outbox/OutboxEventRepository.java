package com.infeara.sales.automation.outbox;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * Candidate selection only. The authoritative claim is {@link #claim}, a single
     * conditional UPDATE that is atomic across instances.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE status IN ('PENDING','RETRY')
               AND available_at <= :now
               AND (locked_until IS NULL OR locked_until < :now)
             ORDER BY available_at, id
             LIMIT :limit
            """, nativeQuery = true)
    List<OutboxEvent> findClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
               SET status = 'PROCESSING',
                   locked_by = :worker,
                   locked_until = :lockedUntil,
                   attempt_count = attempt_count + 1
             WHERE id = :id AND status IN ('PENDING','RETRY')
            """, nativeQuery = true)
    int claim(@Param("id") String id, @Param("worker") String worker,
              @Param("lockedUntil") LocalDateTime lockedUntil);

    long countByStatus(String status);
}
