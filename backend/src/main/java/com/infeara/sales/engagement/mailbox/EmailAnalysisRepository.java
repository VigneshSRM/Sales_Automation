package com.infeara.sales.engagement.mailbox;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailAnalysisRepository extends JpaRepository<EmailAnalysis, String> {

    Optional<EmailAnalysis> findByMessageId(String messageId);
}
