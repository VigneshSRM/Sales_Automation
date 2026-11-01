package com.infeara.sales.web;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.engagement.mailbox.ConversationService;
import com.infeara.sales.engagement.mailbox.MailboxDtos;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified inbox (addendum 4/8): conversations, threaded replies, AI analysis, the approval queue and
 * response-policy rules. Every mutation is audited; nothing here sends mail directly.
 */
@RestController
@RequestMapping("/api/v1/email")
@Tag(name = "Unified inbox", description = "Conversations, replies, AI analysis and response policy")
public class InboxController {

    private final ConversationService conversations;
    private final CurrentPrincipalProvider principalProvider;

    public InboxController(ConversationService conversations, CurrentPrincipalProvider principalProvider) {
        this.conversations = conversations;
        this.principalProvider = principalProvider;
    }

    @GetMapping("/inbox")
    @Operation(summary = "Unified inbox with queue, assignee, unread and search filters")
    public ApiResponse<PageResponse<MailboxDtos.ConversationSummary>> inbox(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID assignee,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.inbox(principal, status, assignee, unassigned, unreadOnly,
                accountId, q, page, size), requestId());
    }

    @GetMapping("/inbox/stats")
    @Operation(summary = "Headline counters for the inbox tiles")
    public ApiResponse<MailboxDtos.InboxStats> stats() {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.stats(principal), requestId());
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "Conversation timeline with the latest AI analysis and pending approval")
    public ApiResponse<MailboxDtos.ConversationDetail> conversation(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.detail(principal, id), requestId());
    }

    @PostMapping("/conversations/{id}/read")
    @Operation(summary = "Mark a conversation read or unread")
    public ApiResponse<MailboxDtos.ConversationSummary> read(@PathVariable UUID id,
                                                             @RequestBody @Valid ReadRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.markRead(principal, id, !Boolean.FALSE.equals(request.read())), requestId());
    }

    @PostMapping("/conversations/{id}/assign")
    @Operation(summary = "Assign a conversation to a user and/or queue")
    public ApiResponse<MailboxDtos.ConversationSummary> assign(@PathVariable UUID id,
                                                               @RequestBody @Valid
                                                               MailboxDtos.AssignRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.assign(principal, id, request), requestId());
    }

    @PostMapping("/conversations/{id}/note")
    @Operation(summary = "Add an internal CRM note to the linked lead or contact")
    public ApiResponse<Void> note(@PathVariable UUID id, @RequestBody @Valid MailboxDtos.NoteRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        conversations.addNote(principal, id, request);
        return ApiResponse.ok(null, requestId());
    }

    @PostMapping("/conversations/{id}/close")
    @Operation(summary = "Close a conversation")
    public ApiResponse<MailboxDtos.ConversationSummary> close(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.close(principal, id), requestId());
    }

    @PostMapping("/conversations/{id}/reply")
    @Operation(summary = "Create a threaded reply; without email.send it waits in the approval queue")
    public ApiResponse<MailboxDtos.MessageView> reply(@PathVariable UUID id,
                                                      @RequestBody @Valid MailboxDtos.ReplyRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.reply(principal, id, request), requestId());
    }

    @PostMapping("/messages/{id}/analyze")
    @Operation(summary = "Run (or re-run) AI analysis on an inbound message")
    public ApiResponse<MailboxDtos.AnalysisView> analyze(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.analyse(principal, id), requestId());
    }

    @GetMapping("/approvals")
    @Operation(summary = "AI and human drafts waiting for a send decision")
    public ApiResponse<PageResponse<MailboxDtos.ApprovalView>> approvals(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.approvalQueue(principal, status, page, size), requestId());
    }

    @PostMapping("/approvals/{id}/approve")
    @Operation(summary = "Approve a draft; the payload hash is re-checked before queuing")
    public ApiResponse<MailboxDtos.ApprovalView> approve(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.approve(principal, id), requestId());
    }

    @PostMapping("/approvals/{id}/reject")
    @Operation(summary = "Reject a draft with a reason")
    public ApiResponse<MailboxDtos.ApprovalView> reject(@PathVariable UUID id,
                                                        @RequestBody @Valid
                                                        MailboxDtos.RejectRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.reject(principal, id, request), requestId());
    }

    @GetMapping("/rules")
    @Operation(summary = "Response-policy rules in evaluation order")
    public ApiResponse<List<MailboxDtos.RuleView>> rules() {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.rules(principal), requestId());
    }

    @PostMapping("/rules")
    @Operation(summary = "Create a response-policy rule (AI may only auto-send when a rule allows it)")
    public ApiResponse<MailboxDtos.RuleView> createRule(@RequestBody @Valid
                                                        MailboxDtos.CreateRuleRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.createRule(principal, request), requestId());
    }

    @PostMapping("/rules/{id}/enabled")
    @Operation(summary = "Enable or disable a response-policy rule")
    public ApiResponse<MailboxDtos.RuleView> setRuleEnabled(@PathVariable UUID id,
                                                            @RequestBody @Valid EnabledRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.setRuleEnabled(principal, id, request.enabled()), requestId());
    }

    private String requestId() {
        return CorrelationIdFilter.currentRequestId();
    }

    /** Small request bodies kept next to their endpoint so the contract stays visible. */
    public record ReadRequest(Boolean read) {
    }

    public record EnabledRequest(boolean enabled) {
    }
}
