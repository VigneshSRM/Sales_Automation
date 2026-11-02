package com.infeara.sales.crm.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, String> {

    Optional<Task> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    Page<Task> findByWorkspaceIdAndOwnerUserIdAndDeletedAtIsNull(String workspaceId, String ownerUserId,
                                                                Pageable pageable);

    Page<Task> findByWorkspaceIdAndDeletedAtIsNull(String workspaceId, Pageable pageable);

    List<Task> findByWorkspaceIdAndRelatedTypeAndRelatedIdAndDeletedAtIsNull(String workspaceId, String relatedType,
                                                                            String relatedId);

    @Query("""
            select t from Task t
             where t.workspaceId = :workspaceId and t.deletedAt is null
               and t.status in ('OPEN','IN_PROGRESS') and t.dueAt < :now
             order by t.dueAt asc
            """)
    Page<Task> findOverdue(@Param("workspaceId") String workspaceId, @Param("now") Instant now, Pageable pageable);

    long countByWorkspaceIdAndOwnerUserIdAndStatusInAndDeletedAtIsNull(String workspaceId, String ownerUserId,
                                                                      List<String> statuses);
}
