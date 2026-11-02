package com.infeara.sales.crm.lead;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadDuplicateRepository extends JpaRepository<LeadDuplicate, String> {

    List<LeadDuplicate> findByLeadId(String leadId);

    List<LeadDuplicate> findByCandidateTypeAndCandidateId(String candidateType, String candidateId);

    long countByWorkspaceIdAndResolution(String workspaceId, String resolution);
}
