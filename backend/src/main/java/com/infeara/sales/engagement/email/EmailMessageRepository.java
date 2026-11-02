package com.infeara.sales.engagement.email;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, String> {

    Optional<EmailMessage> findByIdAndWorkspaceId(String id, String workspaceId);

    Optional<EmailMessage> findByWorkspaceIdAndProviderMessageId(String workspaceId, String providerMessageId);

    Page<EmailMessage> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId, Pageable pageable);

    Page<EmailMessage> findByWorkspaceIdAndStatusOrderByCreatedAtDesc(String workspaceId, String status,
                                                                     Pageable pageable);

    Page<EmailMessage> findByWorkspaceIdAndLeadIdOrderByCreatedAtDesc(String workspaceId, String leadId,
                                                                     Pageable pageable);

    @Query("""
            select m from EmailMessage m
             where m.workspaceId = :workspaceId
               and m.status = 'QUEUED'
               and (m.scheduledAt is null or m.scheduledAt <= :now)
             order by m.scheduledAt asc
            """)
    List<EmailMessage> findDueForSending(@Param("workspaceId") String workspaceId,
                                         @Param("now") Instant now, Pageable pageable);

    @Query("""
            select m.workspaceId, m.status, count(m)
              from EmailMessage m
             where m.workspaceId = :workspaceId
             group by m.workspaceId, m.status
            """)
    List<Object[]> countByStatus(@Param("workspaceId") String workspaceId);

    long countByWorkspaceIdAndSentAtAfter(String workspaceId, Instant since);

    List<EmailMessage> findByWorkspaceIdAndStatusAndScheduledAtBefore(String workspaceId, String status,
                                                                     Instant before);

    boolean existsByWorkspaceIdAndInboundKey(String workspaceId, String inboundKey);

    java.util.Optional<EmailMessage> findByWorkspaceIdAndInboundKey(String workspaceId, String inboundKey);

    java.util.List<EmailMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    long countByConversationId(String conversationId);

    java.util.Optional<EmailMessage> findFirstByConversationIdOrderByReceivedAtDesc(String conversationId);

    java.util.Optional<EmailMessage> findFirstByWorkspaceIdAndMessageIdHeader(String workspaceId,
                                                                             String messageIdHeader);
}
