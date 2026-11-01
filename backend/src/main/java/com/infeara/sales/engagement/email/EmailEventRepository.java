package com.infeara.sales.engagement.email;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailEventRepository extends JpaRepository<EmailEvent, String> {

    List<EmailEvent> findByMessageIdOrderByOccurredAtAsc(String messageId);

    boolean existsByMessageIdAndEventType(String messageId, String eventType);
}
