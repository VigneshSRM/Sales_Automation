package com.infeara.sales.crm.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadSourceRepository extends JpaRepository<LeadSource, String> {

    List<LeadSource> findByWorkspaceIdAndActiveTrueOrderByName(String workspaceId);

    Optional<LeadSource> findByWorkspaceIdAndCode(String workspaceId, String code);
}
