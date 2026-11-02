package com.infeara.sales.workspace;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceSettingRepository extends JpaRepository<WorkspaceSetting, String> {

    List<WorkspaceSetting> findByWorkspaceId(String workspaceId);

    Optional<WorkspaceSetting> findByWorkspaceIdAndSettingKey(String workspaceId, String settingKey);
}
