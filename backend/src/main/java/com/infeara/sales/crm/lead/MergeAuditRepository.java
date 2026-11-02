package com.infeara.sales.crm.lead;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MergeAuditRepository extends JpaRepository<MergeAudit, String> {

    List<MergeAudit> findByTargetIdOrderByCreatedAtDesc(String targetId);
}
