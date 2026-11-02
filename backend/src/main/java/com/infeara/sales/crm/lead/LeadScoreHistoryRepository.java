package com.infeara.sales.crm.lead;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadScoreHistoryRepository extends JpaRepository<LeadScoreHistory, String> {

    List<LeadScoreHistory> findByLeadIdOrderByCalculatedAtDesc(String leadId);

    LeadScoreHistory findFirstByLeadIdOrderByCalculatedAtDesc(String leadId);
}
