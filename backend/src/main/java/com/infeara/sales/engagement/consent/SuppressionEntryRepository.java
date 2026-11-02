package com.infeara.sales.engagement.consent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuppressionEntryRepository extends JpaRepository<SuppressionEntry, String> {

    Optional<SuppressionEntry> findByWorkspaceIdAndEmailNormalized(String workspaceId, String emailNormalized);

    boolean existsByWorkspaceIdAndEmailNormalized(String workspaceId, String emailNormalized);

    Page<SuppressionEntry> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId, Pageable pageable);

    List<SuppressionEntry> findByWorkspaceIdAndEmailNormalizedIn(String workspaceId, List<String> emails);
}
