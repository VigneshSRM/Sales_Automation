package com.infeara.sales.automation.job;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, String> {

    Optional<Job> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    @Query(value = """
            SELECT * FROM jobs
             WHERE status IN ('PENDING','RETRY')
               AND available_at <= :now
               AND (locked_until IS NULL OR locked_until < :now)
             ORDER BY priority, available_at, id
             LIMIT :limit
            """, nativeQuery = true)
    List<Job> findClaimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE jobs
               SET status = 'PROCESSING',
                   locked_by = :worker,
                   locked_until = :lockedUntil,
                   attempt_count = attempt_count + 1
             WHERE id = :id AND status IN ('PENDING','RETRY')
            """, nativeQuery = true)
    int claim(@Param("id") String id, @Param("worker") String worker,
              @Param("lockedUntil") LocalDateTime lockedUntil);

    Page<Job> findByWorkspaceIdAndStatus(String workspaceId, String status, Pageable pageable);

    Page<Job> findByWorkspaceId(String workspaceId, Pageable pageable);

    @Query("select count(j) from Job j where j.status = :status")
    long countByStatus(@Param("status") String status);
}
