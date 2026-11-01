package com.infeara.sales.engagement.mailbox;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.automation.job.JobEngine;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.util.Hashing;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.crm.activity.ActivityRecorder;
import com.infeara.sales.crm.contact.Contact;
import com.infeara.sales.crm.contact.ContactRepository;
import com.infeara.sales.crm.lead.Lead;
import com.infeara.sales.crm.lead.LeadRepository;
import com.infeara.sales.engagement.email.EmailMessage;
import com.infeara.sales.engagement.email.EmailMessageRepository;
import com.infeara.sales.engagement.email.EmailService;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unified inbox and conversation operations (addendum 4).
 *
 * <p>Capabilities are checked per operation, and every write is audited. A reply is never sent by
 * this class directly: approved drafts are queued, and the ordinary email dispatcher performs the
 * SMTP hand-off where eligibility and sending limits are re-checked.
 */
@Service
public class ConversationService {

    private final EmailConversationRepository conversations;
    private final EmailMessageRepository messages;
    private final EmailAttachmentRepository attachments;
    private final EmailAnalysisRepository analyses;
    private final EmailApprovalRepository approvals;
    private final EmailAutomationRuleRepository rules;
    private final EmailAccountRepository accounts;
    private final EmailPolicyService policyService;
    private final EmailIntelligenceService intelligence;
    private final EmailService emailService;
    private final LeadRepository leads;
    private final ContactRepository contacts;
    private final UserRepository users;
    private final ActivityRecorder activities;
    private final CapabilityEvaluator capabilities;
    private final ScopeGuard scopeGuard;
    private final AuditRecorder audit;
    private final JsonCodec jsonCodec;
    private final JobEngine jobs;
    private final Clock clock;

    public ConversationService(EmailConversationRepository conversations, EmailMessageRepository messages,
                               EmailAttachmentRepository attachments, EmailAnalysisRepository analyses,
                               EmailApprovalRepository approvals, EmailAutomationRuleRepository rules,
                               EmailAccountRepository accounts, EmailPolicyService policyService,
                               EmailIntelligenceService intelligence, EmailService emailService,
                               LeadRepository leads, ContactRepository contacts, UserRepository users,
                               ActivityRecorder activities, CapabilityEvaluator capabilities,
                               ScopeGuard scopeGuard, AuditRecorder audit, JsonCodec jsonCodec,
                               JobEngine jobs, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.attachments = attachments;
        this.analyses = analyses;
        this.approvals = approvals;
        this.rules = rules;
        this.accounts = accounts;
        this.policyService = policyService;
        this.intelligence = intelligence;
        this.emailService = emailService;
        this.leads = leads;
        this.contacts = contacts;
        this.users = users;
        this.activities = activities;
        this.capabilities = capabilities;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.jsonCodec = jsonCodec;
        this.jobs = jobs;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ inbox

    @Transactional(readOnly = true)
    public PageResponse<MailboxDtos.ConversationSummary> inbox(CurrentPrincipal principal, String status,
                                                              UUID assignee, boolean unassigned,
                                                              boolean unreadOnly, UUID accountId, String term,
                                                              int page, int size) {
        capabilities.require(principal, "email.conversations.read");
        String assigneeId = assignee == null ? null : Ids.of(assignee);
        if (!"all".equalsIgnoreCase(principal.scopeType()) && assigneeId == null && !unassigned) {
            // Reps see their own conversations plus the shared queue unless they ask otherwise.
            assigneeId = null;
        }
        Page<EmailConversation> result = conversations.search(principal.workspaceId(),
                blankToNull(status), assigneeId, unassigned, unreadOnly,
                accountId == null ? null : Ids.of(accountId), blankToNull(term),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        List<MailboxDtos.ConversationSummary> items = result.getContent().stream().map(this::toSummary).toList();
        return PageResponse.of(items, page, Math.min(Math.max(size, 1), 100), result.getTotalElements(),
                "lastMessageAt:desc", List.of());
    }

    @Transactional(readOnly = true)
    public MailboxDtos.InboxStats stats(CurrentPrincipal principal) {
        capabilities.require(principal, "email.conversations.read");
        List<EmailConversation> all = conversations.search(principal.workspaceId(), null, null, false, false,
                null, null, PageRequest.of(0, 500)).getContent();
        int open = 0;
        int unread = 0;
        int unassigned = 0;
        for (EmailConversation conversation : all) {
            if ("OPEN".equals(conversation.getStatus())) {
                open++;
            }
            if (conversation.isUnread()) {
                unread++;
            }
            if (conversation.getAssignedUserId() == null) {
                unassigned++;
            }
        }
        long awaitingApproval = approvals.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(
                principal.workspaceId(), "PENDING", PageRequest.of(0, 1)).getTotalElements();
        return new MailboxDtos.InboxStats(open, unread, unassigned, (int) awaitingApproval, 0);
    }

    @Transactional
    public MailboxDtos.ConversationDetail detail(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "email.conversations.read");
        EmailConversation conversation = conversation(principal, Ids.of(id));
        List<MailboxDtos.MessageView> views = new ArrayList<>();
        EmailAnalysis latest = null;
        EmailApproval approval = null;
        for (EmailMessage message : messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId())) {
            views.add(toMessageView(message));
            if ("INBOUND".equals(message.getDirection())) {
                EmailAnalysis analysis = analyses.findByMessageId(message.getId()).orElse(null);
                if (analysis != null) {
                    latest = analysis;
                }
                EmailApproval found = approvals.findFirstByMessageIdAndStatusOrderByCreatedAtDesc(message.getId(),
                        "PENDING").orElse(null);
                if (found != null) {
                    approval = found;
                }
            }
        }
        MailboxDtos.AnalysisView analysisView = latest == null ? null : toAnalysisView(latest);
        MailboxDtos.ApprovalView approvalView = approval == null ? null : toApprovalView(approval);
        MailboxDtos.PolicyView policyView = latest == null ? null
                : policyFor(conversation, latest, principal.timezone());
        return new MailboxDtos.ConversationDetail(toSummary(conversation), views, analysisView, approvalView,
                policyView, latest == null ? null : latest.getReplyDraft(), conversation.getRowVersion());
    }

    @Transactional
    public MailboxDtos.ConversationSummary markRead(CurrentPrincipal principal, UUID id, boolean read) {
        capabilities.require(principal, "email.conversations.read");
        EmailConversation conversation = conversation(principal, Ids.of(id));
        conversation.setUnread(!read);
        return toSummary(conversations.save(conversation));
    }

    @Transactional
    public MailboxDtos.ConversationSummary assign(CurrentPrincipal principal, UUID id,
                                                  MailboxDtos.AssignRequest request) {
        capabilities.require(principal, "email.conversations.assign");
        EmailConversation conversation = conversation(principal, Ids.of(id));
        String previousAssignee = conversation.getAssignedUserId();
        if (request.userId() != null) {
            User user = users.findByIdAndWorkspaceIdAndDeletedAtIsNull(Ids.of(request.userId()),
                            principal.workspaceId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found in this workspace"));
            conversation.setAssignedUserId(user.getId());
        }
        if (request.queueKey() != null) {
            conversation.setQueueKey(request.queueKey().isBlank() ? null : request.queueKey());
        }
        EmailConversation saved = conversations.save(conversation);
        audit.record(principal, "EMAIL_CONVERSATION_ASSIGNED", "EMAIL_CONVERSATION", saved.getId(),
                Map.of("assignedUserId", String.valueOf(previousAssignee)),
                Map.of("assignedUserId", String.valueOf(saved.getAssignedUserId()),
                        "queueKey", String.valueOf(saved.getQueueKey())), Map.of());
        if (request.note() != null && !request.note().isBlank() && saved.getLeadId() != null) {
            activities.record(principal, "LEAD", saved.getLeadId(), "NOTE", "Inbox assignment note",
                    request.note(), Map.of("conversationId", saved.getId()));
        }
        return toSummary(saved);
    }

    @Transactional
    public void addNote(CurrentPrincipal principal, UUID id, MailboxDtos.NoteRequest request) {
        capabilities.require(principal, "email.conversations.read");
        EmailConversation conversation = conversation(principal, Ids.of(id));
        String relatedType = conversation.getLeadId() != null ? "LEAD"
                : conversation.getContactId() != null ? "CONTACT" : null;
        String relatedId = conversation.getLeadId() != null ? conversation.getLeadId()
                : conversation.getContactId();
        if (relatedType == null || relatedId == null) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Attach this conversation to a lead or contact before adding a CRM note");
        }
        activities.record(principal, relatedType, relatedId, "NOTE", "Inbox internal note", request.note(),
                Map.of("conversationId", conversation.getId()));
    }

    @Transactional
    public MailboxDtos.ConversationSummary close(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "email.conversations.assign");
        EmailConversation conversation = conversation(principal, Ids.of(id));
        conversation.setStatus("CLOSED");
        conversation.setUnread(false);
        return toSummary(conversations.save(conversation));
    }

    // ------------------------------------------------------------------ replies

    /**
     * Human-authored reply. It is always created as a draft first; sending happens through the
     * dispatcher so suppression, consent and limits are honoured.
     */
    @Transactional
    public MailboxDtos.MessageView reply(CurrentPrincipal principal, UUID id, MailboxDtos.ReplyRequest request) {
        capabilities.require(principal, "email.compose");
        EmailConversation conversation = conversation(principal, Ids.of(id));
        EmailAccount account = accountFor(conversation);
        EmailMessage lastInbound = messages.findFirstByConversationIdOrderByReceivedAtDesc(conversation.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "There is no inbound message to reply to"));
        if (account.getSmtpProfileId() == null) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "This mailbox has no outbound SMTP account configured; configure one before replying");
        }

        EmailMessage draft = new EmailMessage();
        draft.setId(Ids.newId());
        draft.setWorkspaceId(principal.workspaceId());
        draft.setDirection("OUTBOUND");
        draft.setSmtpProfileId(account.getSmtpProfileId());
        draft.setMailboxAccountId(account.getId());
        draft.setConversationId(conversation.getId());
        draft.setLeadId(conversation.getLeadId());
        draft.setContactId(conversation.getContactId());
        draft.setAccountId(conversation.getCrmAccountId());
        draft.setOpportunityId(conversation.getOpportunityId());
        draft.setToEmail(lastInbound.getFromEmail());
        draft.setToName(lastInbound.getFromName());
        draft.setSubject(request.subject() == null ? replySubject(lastInbound.getSubject()) : request.subject());
        draft.setBodyHtml(toHtml(request.body()));
        draft.setBodyText(request.body());
        draft.setMessageKey("manual-reply:" + Ids.newId());
        draft.setInReplyToHeader(lastInbound.getMessageIdHeader());
        draft.setReferencesHeader((lastInbound.getReferencesHeader() == null ? ""
                : lastInbound.getReferencesHeader() + " ")
                + (lastInbound.getMessageIdHeader() == null ? "" : lastInbound.getMessageIdHeader()));
        draft.setCreatedBy(principal.userId());

        boolean canSend = capabilities.has(principal, "email.send");
        boolean sendNow = Boolean.TRUE.equals(request.sendImmediately());
        draft.setStatus(sendNow && canSend ? "QUEUED" : "PENDING_APPROVAL");
        EmailMessage saved = messages.save(draft);

        if (sendNow && canSend) {
            emailService.deliver(principal.workspaceId(), saved, principal.userId());
        } else {
            EmailApproval approval = new EmailApproval();
            approval.setId(Ids.newId());
            approval.setWorkspaceId(principal.workspaceId());
            approval.setMessageId(saved.getId());
            approval.setConversationId(conversation.getId());
            approval.setStatus("PENDING");
            approval.setReasonCode("HUMAN_DRAFT_REQUIRES_SEND_RIGHT");
            approval.setRequestedBy(principal.userId());
            approval.setPayloadHash(Hashing.sha256Hex(saved.getSubject() + "\n" + saved.getBodyHtml()));
            approval.setExpiresAt(clock.instant().plusSeconds(7 * 24 * 3600));
            approvals.save(approval);
        }
        audit.record(principal, "EMAIL_REPLY_DRAFTED", "EMAIL_MESSAGE", saved.getId(), null,
                Map.of("conversationId", conversation.getId(), "status", saved.getStatus()), Map.of());
        return toMessageView(saved);
    }

    @Transactional
    public MailboxDtos.ApprovalView approve(CurrentPrincipal principal, UUID approvalId) {
        capabilities.require(principal, "email.drafts.approve");
        EmailApproval approval = approvals.findByIdAndWorkspaceId(Ids.of(approvalId), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Approval not found"));
        if (!"PENDING".equals(approval.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "This approval has already been decided");
        }
        if (approval.getExpiresAt() != null && approval.getExpiresAt().isBefore(clock.instant())) {
            approval.setStatus("EXPIRED");
            approvals.save(approval);
            throw new ApiException(ErrorCode.APPROVAL_EXPIRED, "This approval request has expired");
        }
        if (principal.userId().equals(approval.getRequestedBy())) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "You cannot approve a draft you created (four-eyes rule)");
        }
        EmailMessage message = messages.findById(approval.getMessageId())
                .orElseThrow(() -> new ApiException(ErrorCode.MESSAGE_NOT_FOUND, "Draft not found"));
        String currentHash = Hashing.sha256Hex(message.getSubject() + "\n" + message.getBodyHtml());
        if (!currentHash.equals(approval.getPayloadHash())) {
            throw new ApiException(ErrorCode.APPROVAL_INVALIDATED,
                    "The draft changed after it was submitted for approval; review it again");
        }
        message.setStatus("QUEUED");
        message.setApprovedBy(principal.userId());
        message.setApprovedAt(clock.instant());
        messages.save(message);

        approval.setStatus("APPROVED");
        approval.setDecidedBy(principal.userId());
        approval.setDecidedAt(clock.instant());
        EmailApproval saved = approvals.save(approval);
        audit.record(principal, "EMAIL_DRAFT_APPROVED", "EMAIL_MESSAGE", message.getId(), null,
                Map.of("approvalId", saved.getId(), "status", saved.getStatus()), Map.of());
        return toApprovalView(saved);
    }

    @Transactional
    public MailboxDtos.ApprovalView reject(CurrentPrincipal principal, UUID approvalId,
                                           MailboxDtos.RejectRequest request) {
        capabilities.require(principal, "email.drafts.approve");
        EmailApproval approval = approvals.findByIdAndWorkspaceId(Ids.of(approvalId), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Approval not found"));
        if (!"PENDING".equals(approval.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "This approval has already been decided");
        }
        approval.setStatus("REJECTED");
        approval.setDecidedBy(principal.userId());
        approval.setDecidedAt(clock.instant());
        approval.setRejectionReason(request.reason());
        approvals.save(approval);

        messages.findById(approval.getMessageId()).ifPresent(message -> {
            message.setStatus("CANCELLED");
            message.setFailureCode("DRAFT_REJECTED");
            message.setFailureDetail(request.reason());
            messages.save(message);
        });
        audit.record(principal, "EMAIL_DRAFT_REJECTED", "EMAIL_APPROVAL", approval.getId(), null,
                Map.of("reason", request.reason()), Map.of());
        return toApprovalView(approval);
    }

    @Transactional(readOnly = true)
    public PageResponse<MailboxDtos.ApprovalView> approvalQueue(CurrentPrincipal principal, String status,
                                                               int page, int size) {
        capabilities.require(principal, "email.conversations.read");
        Page<EmailApproval> result = approvals.findByWorkspaceIdAndStatusOrderByCreatedAtDesc(
                principal.workspaceId(), status == null || status.isBlank() ? "PENDING" : status,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return PageResponse.of(result.getContent().stream().map(this::toApprovalView).toList(), page,
                Math.min(Math.max(size, 1), 100), result.getTotalElements(), "createdAt:desc", List.of());
    }

    // ------------------------------------------------------------------ analysis & rules

    /** Manual (re-)analysis from the inbox: network call first, then a short write transaction. */
    public MailboxDtos.AnalysisView analyse(CurrentPrincipal principal, UUID messageId) {
        capabilities.require(principal, "email.analyze");
        EmailMessage message = messages.findByIdAndWorkspaceId(Ids.of(messageId), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.MESSAGE_NOT_FOUND, "Message not found"));
        EmailIntelligenceService.RawAnalysis raw = intelligence.requestAnalysis(message.getId());
        EmailAnalysis applied = intelligence.applyAnalysis(message.getId(), raw);
        audit.record(principal, "EMAIL_ANALYZED_MANUALLY", "EMAIL_MESSAGE", message.getId(), null,
                Map.of("intent", applied.getIntent(),
                        "confidence", applied.getConfidence().toPlainString()), Map.of());
        return toAnalysisView(applied);
    }

    @Transactional(readOnly = true)
    public List<MailboxDtos.RuleView> rules(CurrentPrincipal principal) {
        capabilities.require(principal, "email.read");
        return rules.findByWorkspaceIdAndDeletedAtIsNullOrderByPriorityAsc(principal.workspaceId()).stream()
                .map(this::toRuleView)
                .toList();
    }

    @Transactional
    public MailboxDtos.RuleView createRule(CurrentPrincipal principal, MailboxDtos.CreateRuleRequest request) {
        capabilities.require(principal, "email.rules.manage");
        EmailAutomationRule rule = new EmailAutomationRule();
        rule.setId(Ids.newId());
        rule.setWorkspaceId(principal.workspaceId());
        rule.setName(request.name().trim());
        rule.setPriority(request.priority() == null ? 100 : request.priority());
        rule.setEnabled(request.enabled() == null || request.enabled());
        rule.setMode(request.mode());
        rule.setAllowedIntentsJson(request.allowedIntents() == null || request.allowedIntents().isEmpty() ? null
                : jsonCodec.write(request.allowedIntents()));
        rule.setBlockedIntentsJson(request.blockedIntents() == null || request.blockedIntents().isEmpty() ? null
                : jsonCodec.write(request.blockedIntents()));
        rule.setMinConfidence(request.minConfidence() == null ? new BigDecimal("0.850")
                : request.minConfidence());
        if (request.businessDays() != null && !request.businessDays().isEmpty()) {
            Map<String, Object> hours = new LinkedHashMap<>();
            hours.put("days", request.businessDays());
            hours.put("start", request.businessHoursStart() == null ? "09:00" : request.businessHoursStart());
            hours.put("end", request.businessHoursEnd() == null ? "18:00" : request.businessHoursEnd());
            rule.setBusinessHoursJson(jsonCodec.write(hours));
        }
        rule.setDelaySeconds(request.delaySeconds() == null ? 0 : request.delaySeconds());
        rule.setCreatedBy(principal.userId());
        EmailAutomationRule saved = rules.save(rule);
        audit.record(principal, "EMAIL_RULE_CREATED", "EMAIL_RULE", saved.getId(), null,
                Map.of("name", saved.getName(), "mode", saved.getMode()), Map.of());
        return toRuleView(saved);
    }

    @Transactional
    public MailboxDtos.RuleView setRuleEnabled(CurrentPrincipal principal, UUID ruleId, boolean enabled) {
        capabilities.require(principal, "email.rules.manage");
        EmailAutomationRule rule = rules.findById(Ids.of(ruleId))
                .filter(found -> found.getWorkspaceId().equals(principal.workspaceId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Rule not found"));
        rule.setEnabled(enabled);
        EmailAutomationRule saved = rules.save(rule);
        audit.record(principal, enabled ? "EMAIL_RULE_ENABLED" : "EMAIL_RULE_DISABLED", "EMAIL_RULE",
                saved.getId(), null, Map.of("enabled", enabled), Map.of());
        return toRuleView(saved);
    }

    /** Explicit opt-in re-run of a mailbox sync, executed by a leased job (never inline). */
    @Transactional
    public Map<String, String> requestSync(CurrentPrincipal principal, UUID accountId) {
        capabilities.require(principal, "email.accounts.manage");
        EmailAccount account = accounts.findByIdAndWorkspaceIdAndDeletedAtIsNull(Ids.of(accountId),
                        principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Mailbox not found"));
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Only an active mailbox can be synchronised");
        }
        String jobId = jobs.enqueue(principal.workspaceId(), "MAILBOX_SYNC",
                "mailbox-sync:" + account.getId() + ":" + (clock.millis() / 60_000),
                Map.of("accountId", account.getId()), 40);
        audit.record(principal, "EMAIL_SYNC_REQUESTED", "EMAIL_ACCOUNT", account.getId(), null,
                Map.of("jobId", String.valueOf(jobId)), Map.of());
        return Map.of("status", "QUEUED", "accountId", account.getId(), "jobId", String.valueOf(jobId));
    }

    // ------------------------------------------------------------------ mapping

    private EmailConversation conversation(CurrentPrincipal principal, String conversationId) {
        EmailConversation conversation = conversations.findByIdAndWorkspaceId(conversationId,
                        principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conversation not found"));
        if (conversation.getAssignedUserId() != null) {
            scopeGuard.requireAccess(principal, conversation.getAssignedUserId(), "conversation");
        }
        return conversation;
    }

    private EmailAccount accountFor(EmailConversation conversation) {
        if (conversation.getAccountId() == null) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "This conversation is not linked to a mailbox account");
        }
        return accounts.findById(conversation.getAccountId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Mailbox not found"));
    }

    MailboxDtos.ConversationSummary toSummary(EmailConversation conversation) {
        String leadName = conversation.getLeadId() == null ? null
                : leads.findById(conversation.getLeadId())
                        .map(lead -> lead.getFirstName() + " " + (lead.getLastName() == null ? ""
                                : lead.getLastName()))
                        .map(String::trim).orElse(null);
        String contactName = conversation.getContactId() == null ? null
                : contacts.findById(conversation.getContactId()).map(Contact::fullName).orElse(null);
        String assignedName = conversation.getAssignedUserId() == null ? null
                : users.findById(conversation.getAssignedUserId()).map(User::getDisplayName).orElse(null);
        boolean awaiting = conversation.getId() != null && approvals
                .findFirstByMessageIdAndStatusOrderByCreatedAtDesc(
                        latestInboundId(conversation.getId()), "PENDING").isPresent();
        return new MailboxDtos.ConversationSummary(Ids.parse(conversation.getId()), conversation.getSubject(),
                conversation.getMailboxAddress(), Ids.parse(conversation.getAccountId()),
                Ids.parse(conversation.getLeadId()), leadName, Ids.parse(conversation.getContactId()),
                contactName, Ids.parse(conversation.getAssignedUserId()), assignedName,
                conversation.getQueueKey(), conversation.getStatus(), conversation.getPriority(),
                conversation.isUnread(), conversation.getMessageCount(), conversation.getLastMessageAt(),
                conversation.getLeadId() == null && conversation.getContactId() == null ? "UNASSIGNED" : "MATCHED",
                awaiting);
    }

    private String latestInboundId(String conversationId) {
        return messages.findFirstByConversationIdOrderByReceivedAtDesc(conversationId)
                .map(EmailMessage::getId).orElse("");
    }

    MailboxDtos.MessageView toMessageView(EmailMessage message) {
        List<MailboxDtos.AttachmentView> attachmentViews = attachments
                .findByMessageIdOrderByFilenameAsc(message.getId()).stream()
                .map(attachment -> new MailboxDtos.AttachmentView(Ids.parse(attachment.getId()),
                        attachment.getFilename(), attachment.getMediaType(), attachment.getSizeBytes(),
                        attachment.getScanStatus()))
                .toList();
        return new MailboxDtos.MessageView(Ids.parse(message.getId()), message.getDirection(),
                message.getFromEmail(), message.getFromName(), message.getToEmail(), message.getSubject(),
                message.getBodyText(), message.getBodyHtml(), message.getReceivedAt(), message.getSentAt(),
                message.getStatus(), message.getMessageIdHeader(), message.getInReplyToHeader(),
                message.getAiStatus(), attachmentViews);
    }

    MailboxDtos.AnalysisView toAnalysisView(EmailAnalysis analysis) {
        List<Map<String, Object>> extracted = analysis.getExtractedJson() == null ? List.of()
                : jsonCodec.readListOfMaps(analysis.getExtractedJson());
        List<String> questions = readStrings(analysis.getQuestionsJson());
        List<String> risks = readStrings(analysis.getRisksJson());
        return new MailboxDtos.AnalysisView(analysis.getIntent(), analysis.getSubIntent(),
                analysis.getSentiment(), analysis.getUrgency(), analysis.getBuyingSignal(),
                analysis.getConfidence(), analysis.getRecommendedAction(), analysis.getReplyDraft(),
                analysis.getProvider(), analysis.getModel(), analysis.getPromptVersion(),
                analysis.isValidated(), analysis.getCreatedAt(), extracted, questions, risks);
    }

    MailboxDtos.ApprovalView toApprovalView(EmailApproval approval) {
        String requester = approval.getRequestedBy() == null ? "AI policy engine"
                : users.findById(approval.getRequestedBy()).map(User::getDisplayName).orElse("User");
        String decider = approval.getDecidedBy() == null ? null
                : users.findById(approval.getDecidedBy()).map(User::getDisplayName).orElse("User");
        return new MailboxDtos.ApprovalView(Ids.parse(approval.getId()), Ids.parse(approval.getMessageId()),
                approval.getStatus(), approval.getReasonCode(), requester, decider, approval.getDecidedAt(),
                approval.getRejectionReason(), approval.getExpiresAt(), approval.getCreatedAt());
    }

    MailboxDtos.RuleView toRuleView(EmailAutomationRule rule) {
        List<String> allowed = readStrings(rule.getAllowedIntentsJson());
        List<String> blocked = readStrings(rule.getBlockedIntentsJson());
        List<String> days = List.of();
        String start = null;
        String end = null;
        if (rule.getBusinessHoursJson() != null && !rule.getBusinessHoursJson().isBlank()) {
            Map<String, Object> hours = jsonCodec.readMap(rule.getBusinessHoursJson());
            Object dayList = hours.get("days");
            if (dayList instanceof List<?> list) {
                days = list.stream().map(String::valueOf).toList();
            }
            start = hours.get("start") == null ? null : String.valueOf(hours.get("start"));
            end = hours.get("end") == null ? null : String.valueOf(hours.get("end"));
        }
        return new MailboxDtos.RuleView(Ids.parse(rule.getId()), rule.getName(), rule.getPriority(),
                rule.isEnabled(), rule.getMode(), allowed, blocked, rule.getMinConfidence(), days, start, end,
                rule.getDelaySeconds(), rule.getCreatedAt(), rule.getRowVersion());
    }

    private MailboxDtos.PolicyView policyFor(EmailConversation conversation, EmailAnalysis analysis,
                                             String timezone) {
        EmailPolicyService.Decision decision = policyService.decide(conversation.getWorkspaceId(),
                analysis.getIntent(), analysis.getConfidence(), false, conversation.getAccountId(),
                timezone == null || timezone.isBlank() ? "Asia/Kolkata" : timezone);
        return new MailboxDtos.PolicyView(decision.mode(), decision.reasonCode(), decision.ruleName(),
                decision.mayAutoSend(), decision.delaySeconds());
    }

    private List<String> readStrings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<?> raw = jsonCodec.read(json, List.class);
        List<String> values = new ArrayList<>();
        for (Object item : raw) {
            values.add(String.valueOf(item));
        }
        return values;
    }

    private String replySubject(String subject) {
        if (subject == null) {
            return "Re: your message";
        }
        return subject.toLowerCase(java.util.Locale.ROOT).startsWith("re:") ? subject : "Re: " + subject;
    }

    private String toHtml(String text) {
        StringBuilder html = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            html.append("<p>").append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</p>");
        }
        return html.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Used by the sync job to record that a conversation has been linked after the fact. */
    @Transactional
    public void linkConversation(String workspaceId, String conversationId, String leadId, String contactId,
                                 String crmAccountId) {
        conversations.findByIdAndWorkspaceId(conversationId, workspaceId).ifPresent(conversation -> {
            conversation.setLeadId(leadId);
            conversation.setContactId(contactId);
            conversation.setCrmAccountId(crmAccountId);
            conversations.save(conversation);
        });
    }
}
