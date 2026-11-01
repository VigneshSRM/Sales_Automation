package com.infeara.sales.engagement.mailbox;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailConversationRepository extends JpaRepository<EmailConversation, String> {

    Optional<EmailConversation> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<EmailConversation> findByWorkspaceIdAndThreadKey(String workspaceId, String threadKey);

    /** Unified inbox listing: workspace scope, optional status/assignee/mailbox/search filters. */
    @Query("""
            select c from EmailConversation c
             where c.workspaceId = :workspaceId
               and (:status is null or c.status = :status)
               and (:assignedUserId is null or c.assignedUserId = :assignedUserId)
               and (:unassigned = false or c.assignedUserId is null)
               and (:unreadOnly = false or c.unread = true)
               and (:accountId is null or c.accountId = :accountId)
               and (:term is null or lower(c.subject) like lower(concat('%', :term, '%'))
                    or lower(c.mailboxAddress) like lower(concat('%', :term, '%')))
             order by c.lastMessageAt desc
            """)
    Page<EmailConversation> search(@Param("workspaceId") String workspaceId,
                                   @Param("status") String status,
                                   @Param("assignedUserId") String assignedUserId,
                                   @Param("unassigned") boolean unassigned,
                                   @Param("unreadOnly") boolean unreadOnly,
                                   @Param("accountId") String accountId,
                                   @Param("term") String term,
                                   Pageable pageable);

    List<EmailConversation> findByWorkspaceIdAndLeadId(String workspaceId, String leadId);
}
