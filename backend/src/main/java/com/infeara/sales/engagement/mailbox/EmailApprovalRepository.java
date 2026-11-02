package com.infeara.sales.engagement.mailbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailApprovalRepository extends JpaRepository<EmailApproval, String> {

    Optional<EmailApproval> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<EmailApproval> findFirstByMessageIdAndStatusOrderByCreatedAtDesc(String messageId, String status);

    List<EmailApproval> findByMessageId(String messageId);

    Page<EmailApproval> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(String workspaceId, String status,
                                                                      Pageable pageable);
}
