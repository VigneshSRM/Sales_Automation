package com.infeara.sales.crm.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, String> {

    List<ServiceOffering> findByWorkspaceIdAndActiveTrueOrderByDivisionAscNameAsc(String workspaceId);

    List<ServiceOffering> findByWorkspaceIdOrderByDivisionAscNameAsc(String workspaceId);

    Optional<ServiceOffering> findByWorkspaceIdAndCode(String workspaceId, String code);
}
