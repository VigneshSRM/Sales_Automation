package com.infeara.sales.engagement.mailbox;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSyncStateRepository extends JpaRepository<EmailSyncState, String> {

    Optional<EmailSyncState> findByAccountId(String accountId);

    Optional<EmailSyncState> findByIdAndWorkspaceId(String id, String workspaceId);
}
