package com.infeara.sales.engagement.sequence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SequenceRepository extends JpaRepository<EmailSequence, String> {

    List<EmailSequence> findByWorkspaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(String workspaceId);

    Optional<EmailSequence> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    boolean existsByWorkspaceIdAndNameAndDeletedAtIsNull(String workspaceId, String name);

    Page<EmailSequence> findByWorkspaceIdAndDeletedAtIsNull(String workspaceId, Pageable pageable);

    /** Due enrollments across the workspace, oldest first. */
    @Query("""
            select e from SequenceEnrollment e
             where e.workspaceId = :workspaceId
               and e.status = 'ACTIVE'
               and e.nextRunAt is not null
               and e.nextRunAt <= :now
             order by e.nextRunAt asc
            """)
    List<SequenceEnrollment> findDueEnrollments(@Param("workspaceId") String workspaceId,
                                                @Param("now") Instant now, Pageable pageable);
}
