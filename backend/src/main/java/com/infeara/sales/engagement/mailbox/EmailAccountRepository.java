package com.infeara.sales.engagement.mailbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailAccountRepository extends JpaRepository<EmailAccount, String> {

    Optional<EmailAccount> findByIdAndWorkspaceIdAndDeletedAtIsNull(String id, String workspaceId);

    Optional<EmailAccount> findByWorkspaceIdAndNameAndDeletedAtIsNull(String workspaceId, String name);

    List<EmailAccount> findByWorkspaceIdAndDeletedAtIsNullOrderByNameAsc(String workspaceId);

    List<EmailAccount> findByStatusAndInboundEnabledTrueAndDeletedAtIsNull(String status);

    List<EmailAccount> findByWorkspaceIdAndStatusAndDeletedAtIsNull(String workspaceId, String status);
}
