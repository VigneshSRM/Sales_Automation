package com.infeara.sales.crm.activity;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, String> {

    Page<Activity> findByWorkspaceIdAndRelatedTypeAndRelatedIdOrderByOccurredAtDesc(
            String workspaceId, String relatedType, String relatedId, Pageable pageable);

    List<Activity> findTop50ByWorkspaceIdOrderByOccurredAtDesc(String workspaceId);

    long countByWorkspaceIdAndRelatedTypeAndRelatedId(String workspaceId, String relatedType, String relatedId);

    Page<Activity> findByWorkspaceIdOrderByOccurredAtDesc(String workspaceId, Pageable pageable);

    Page<Activity> findByWorkspaceIdAndActorUserIdOrderByOccurredAtDesc(String workspaceId, String actorUserId,
                                                                       Pageable pageable);
}
