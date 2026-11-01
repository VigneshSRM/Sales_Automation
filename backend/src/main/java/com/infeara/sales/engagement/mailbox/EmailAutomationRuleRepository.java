package com.infeara.sales.engagement.mailbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailAutomationRuleRepository extends JpaRepository<EmailAutomationRule, String> {

    List<EmailAutomationRule> findByWorkspaceIdAndEnabledTrueAndDeletedAtIsNullOrderByPriorityAsc(
            String workspaceId);

    List<EmailAutomationRule> findByWorkspaceIdAndDeletedAtIsNullOrderByPriorityAsc(String workspaceId);
}
