package com.infeara.sales.crm.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LossReasonRepository extends JpaRepository<LossReason, String> {

    List<LossReason> findByWorkspaceIdAndActiveTrueOrderByName(String workspaceId);

    Optional<LossReason> findByWorkspaceIdAndCode(String workspaceId, String code);
}
