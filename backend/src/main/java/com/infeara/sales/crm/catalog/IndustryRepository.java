package com.infeara.sales.crm.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndustryRepository extends JpaRepository<Industry, String> {

    List<Industry> findByWorkspaceIdAndActiveTrueOrderByName(String workspaceId);

    Optional<Industry> findByWorkspaceIdAndCode(String workspaceId, String code);
}
