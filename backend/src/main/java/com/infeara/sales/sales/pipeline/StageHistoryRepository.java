package com.infeara.sales.sales.pipeline;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StageHistoryRepository extends JpaRepository<StageHistory, String> {

    List<StageHistory> findByOpportunityIdOrderByCreatedAtDesc(String opportunityId);
}
