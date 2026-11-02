package com.infeara.sales.crm.scoring;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringModelRepository extends JpaRepository<ScoringModel, String> {

    Optional<ScoringModel> findFirstByWorkspaceIdAndActiveTrueOrderByVersionDesc(String workspaceId);

    Optional<ScoringModel> findFirstByWorkspaceIdOrderByVersionDesc(String workspaceId);
}
