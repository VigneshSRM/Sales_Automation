package com.infeara.sales.engagement.mailbox;

import com.infeara.sales.automation.job.JobEngine;
import com.infeara.sales.automation.outbox.OutboxPublisher;
import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.crm.activity.ActivityRecorder;
import com.infeara.sales.engagement.email.EmailMessage;
import com.infeara.sales.engagement.email.EmailMessageRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one fetched message: dedupe, threading, CRM association, activity and follow-up work
 * (addendum 3.2 and 3.3 step 6).
 *
 * <p>Idempotency: {@code orphanDuplicateProtection} is handled by the unique
 * {@code (workspace_id, inbound_key)} constraint plus an explicit existence check, so a re-fetched
 * message (UIDVALIDITY change, crashed worker, overlapping sync) cannot create a second activity or a
 * second AI job.
 */
@Service
public class InboundEmailService {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailService.class);

    private final EmailMessageRepository messages;
    private final EmailConversationRepository conversations;
    private final EmailAttachmentRepository attachments;
    private final ConversationMatcher matcher;
    private final ActivityRecorder activities;
    private final OutboxPublisher outbox;
    private final JobEngine jobs;
    private final JsonCodec jsonCodec;
    private final Clock clock;

    public InboundEmailService(EmailMessageRepository messages, EmailConversationRepository conversations,
                               EmailAttachmentRepository attachments, ConversationMatcher matcher,
                               ActivityRecorder activities, OutboxPublisher outbox, JobEngine jobs,
                               JsonCodec jsonCodec, Clock clock) {
        this.messages = messages;
        this.conversations = conversations;
        this.attachments = attachments;
        this.matcher = matcher;
        this.activities = activities;
        this.outbox = outbox;
        this.jobs = jobs;
        this.jsonCodec = jsonCodec;
        this.clock = clock;
    }

    public record PersistResult(String messageId, String conversationId, boolean duplicate, boolean analysed) {
    }

    /** Runs in its own transaction: one bad message cannot roll back the whole sync batch. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistResult persist(EmailAccount account, ParsedEmail email) {
        String workspaceId = account.getWorkspaceId();
        String inboundKey = inboundKey(account, email);

        Optional<EmailMessage> existing = messages.findByWorkspaceIdAndInboundKey(workspaceId, inboundKey);
        if (existing.isPresent()) {
            log.debug("inbound_duplicate_skipped account={} key={}", account.getId(), inboundKey);
            return new PersistResult(existing.get().getId(), existing.get().getConversationId(), true, false);
        }

        String threadKey = matcher.threadKey(email, account.getMailboxAddress());
        EmailConversation conversation = conversations
                .findByWorkspaceIdAndThreadKey(workspaceId, threadKey)
                .orElseGet(() -> newConversation(account, email, threadKey));
        ConversationMatcher.Match match = matcher.match(workspaceId, email.fromEmail(), email.subject());
        if (conversation.getLeadId() == null && match.assigned()) {
            conversation.setLeadId(match.leadId());
            conversation.setContactId(match.contactId());
            conversation.setCrmAccountId(match.crmAccountId());
            conversation.setOpportunityId(match.opportunityId());
        }
        conversation.setMessageCount(conversation.getMessageCount() + 1);
        conversation.setLastMessageAt(email.receivedAt() == null ? clock.instant() : email.receivedAt());
        conversation.setUnread(true);
        conversation.setStatus("OPEN");
        EmailConversation savedConversation = conversations.save(conversation);

        EmailMessage message = new EmailMessage();
        message.setId(Ids.newId());
        message.setWorkspaceId(workspaceId);
        message.setDirection("INBOUND");
        message.setMailboxAccountId(account.getId());
        message.setConversationId(savedConversation.getId());
        message.setFromEmail(Normalizer.email(email.fromEmail()));
        message.setFromName(email.fromName());
        message.setToEmail(Normalizer.email(email.toEmail() == null ? account.getMailboxAddress() : email.toEmail()));
        message.setCcJson(email.ccEmails() == null || email.ccEmails().isEmpty() ? null
                : jsonCodec.write(email.ccEmails()));
        message.setReplyToEmail(Normalizer.email(email.replyToEmail()));
        message.setSubject(email.subject() == null ? "(no subject)" : email.subject());
        message.setBodyText(email.textBody());
        message.setBodyHtml(email.htmlBody() == null || email.htmlBody().isBlank()
                ? "<p>" + escape(email.textBody()) + "</p>" : email.htmlBody());
        message.setStatus("RECEIVED");
        message.setAiStatus("QUEUED");
        message.setReceivedAt(email.receivedAt());
        message.setMessageIdHeader(email.rfcMessageId());
        message.setInReplyToHeader(email.inReplyTo());
        message.setReferencesHeader(email.references());
        message.setUid(email.uid());
        message.setUidValidity(email.uidValidity());
        message.setAttachmentCount(email.attachments() == null ? 0 : email.attachments().size());
        message.setRawHeadersJson(jsonCodec.write(email.rawHeaders() == null ? Map.of() : email.rawHeaders()));
        message.setMessageKey("INBOUND:" + inboundKey);
        message.setInboundKey(inboundKey);
        message.setLeadId(savedConversation.getLeadId());
        message.setContactId(savedConversation.getContactId());
        message.setAccountId(savedConversation.getCrmAccountId());
        message.setOpportunityId(savedConversation.getOpportunityId());
        EmailMessage saved = messages.save(message);

        if (email.attachments() != null) {
            for (ParsedEmail.ParsedAttachment attachment : email.attachments()) {
                EmailAttachment row = new EmailAttachment();
                row.setId(Ids.newId());
                row.setWorkspaceId(workspaceId);
                row.setMessageId(saved.getId());
                row.setFilename(attachment.filename());
                row.setMediaType(attachment.mediaType());
                row.setSizeBytes(attachment.sizeBytes());
                row.setSha256(attachment.sha256());
                // Attachment bytes are not persisted yet (file storage is a later release); the
                // row states that explicitly instead of implying the file was scanned and stored.
                row.setScanStatus("SKIPPED");
                attachments.save(row);
            }
        }

        if (savedConversation.getLeadId() != null) {
            activities.recordSystem(workspaceId, "LEAD", savedConversation.getLeadId(), "EMAIL",
                    "Reply received: " + saved.getSubject(),
                    summary(email.textBody()),
                    Map.of("messageId", saved.getId(), "conversationId", savedConversation.getId(),
                            "from", String.valueOf(saved.getFromEmail())));
        }
        outbox.publish(workspaceId, "EMAIL_MESSAGE", saved.getId(), "EMAIL_RECEIVED",
                Map.of("messageId", saved.getId(), "conversationId", savedConversation.getId(),
                        "accountId", account.getId(), "from", String.valueOf(saved.getFromEmail()),
                        "autoGenerated", email.isAutoGenerated()));
        jobs.enqueue(workspaceId, "EMAIL_ANALYZE", "email-analyze:" + saved.getId(),
                Map.of("messageId", saved.getId(), "workspaceId", workspaceId), 50);

        log.info("inbound_email_persisted workspace={} message={} conversation={} matched={}",
                workspaceId, saved.getId(), savedConversation.getId(), savedConversation.getLeadId() != null);
        return new PersistResult(saved.getId(), savedConversation.getId(), false, false);
    }

    private EmailConversation newConversation(EmailAccount account, ParsedEmail email, String threadKey) {
        EmailConversation conversation = new EmailConversation();
        conversation.setId(Ids.newId());
        conversation.setWorkspaceId(account.getWorkspaceId());
        conversation.setAccountId(account.getId());
        conversation.setMailboxAddress(account.getMailboxAddress());
        conversation.setThreadKey(threadKey);
        conversation.setSubject(email.subject());
        conversation.setNormalizedSubject(matcher.normalizeSubject(email.subject()));
        conversation.setAssignedUserId(account.getOwnerUserId());
        conversation.setQueueKey(account.getQueueKey());
        conversation.setStatus("OPEN");
        conversation.setPriority("NORMAL");
        conversation.setUnread(true);
        conversation.setMessageCount(0);
        return conversation;
    }

    /** Dedupe key: provider message id when present, else mailbox + UIDVALIDITY + UID. */
    public String inboundKey(EmailAccount account, ParsedEmail email) {
        if (email.rfcMessageId() != null && !email.rfcMessageId().isBlank()) {
            String cleaned = email.rfcMessageId().replaceAll("[<>\\s]", "");
            return truncate(account.getId() + ":" + cleaned, 160);
        }
        return truncate(account.getId() + ":uid:" + email.uidValidity() + ":" + email.uid(), 160);
    }

    private String summary(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
