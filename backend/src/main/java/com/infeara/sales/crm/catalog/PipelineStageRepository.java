package com.infeara.sales.crm.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineStageRepository extends JpaRepository<PipelineStage, String> {

    List<PipelineStage> findByWorkspaceIdAndActiveTrueOrderByOrderNoAsc(String workspaceId);

    List<PipelineStage> findByWorkspaceIdOrderByOrderNoAsc(String workspaceId);

    Optional<PipelineStage> findByWorkspaceIdAndCode(String workspaceId, String code);

    Optional<PipelineStage> findByIdAndWorkspaceId(String id, String workspaceId);
}
