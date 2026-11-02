package com.infeara.sales.engagement.mailbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailAttachmentRepository extends JpaRepository<EmailAttachment, String> {

    List<EmailAttachment> findByMessageIdOrderByFilenameAsc(String messageId);
}
