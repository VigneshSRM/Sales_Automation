package com.infeara.sales.web;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.engagement.mailbox.ConversationService;
import com.infeara.sales.engagement.mailbox.MailboxDtos;
import com.infeara.sales.engagement.mailbox.MailboxService;
import com.infeara.sales.engagement.mailbox.MailboxSyncService;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mailbox connection management (addendum 8).
 *
 * <p>Credentials are write-only: they are accepted here, encrypted immediately and never returned in
 * any response. A mailbox must pass a real connection test before it can be enabled, so a
 * misconfigured host cannot silently sit in the inbox pipeline.
 */
@RestController
@RequestMapping("/api/v1/email/accounts")
@Tag(name = "Mailbox accounts", description = "Inbound mailbox connections and sync health")
public class MailboxAccountController {

    private final MailboxService mailboxes;
    private final MailboxSyncService syncService;
    private final ConversationService conversations;
    private final CurrentPrincipalProvider principalProvider;

    public MailboxAccountController(MailboxService mailboxes, MailboxSyncService syncService,
                                    ConversationService conversations,
                                    CurrentPrincipalProvider principalProvider) {
        this.mailboxes = mailboxes;
        this.syncService = syncService;
        this.conversations = conversations;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "List configured mailboxes with sync health")
    public ApiResponse<List<MailboxDtos.AccountView>> list() {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(mailboxes.list(principal), requestId());
    }

    @PostMapping
    @Operation(summary = "Create a mailbox connection (status starts PENDING_TEST)")
    public ResponseEntity<ApiResponse<MailboxDtos.AccountView>> create(
            @Valid @RequestBody MailboxDtos.CreateAccountRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        MailboxDtos.AccountView view = mailboxes.create(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(view, requestId()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a mailbox; credential changes force a new connection test")
    public ApiResponse<MailboxDtos.AccountView> update(@PathVariable UUID id,
                                                       @Valid @RequestBody
                                                       MailboxDtos.UpdateAccountRequest request) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(mailboxes.update(principal, id, request), requestId());
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Open a real IMAP session and report what the server answered")
    public ApiResponse<MailboxDtos.TestConnectionResult> test(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(mailboxes.test(principal, id), requestId());
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Enable or disable a mailbox (enable requires a passing test)")
    public ApiResponse<MailboxDtos.AccountView> status(@PathVariable UUID id,
                                                       @RequestBody Map<String, Boolean> body) {
        CurrentPrincipal principal = principalProvider.principal();
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.ok(mailboxes.setEnabled(principal, id, enabled), requestId());
    }

    @PostMapping("/{id}/sync")
    @Operation(summary = "Queue an immediate synchronisation (executed by a leased job)")
    public ApiResponse<Map<String, String>> sync(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(conversations.requestSync(principal, id), requestId());
    }

    @PostMapping("/{id}/sync-now")
    @Operation(summary = "Run a synchronisation in this request (small mailboxes, admin diagnostics)")
    public ApiResponse<MailboxDtos.SyncResult> syncNow(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        mailboxes.list(principal);
        return ApiResponse.ok(syncService.sync(id.toString()), requestId());
    }

    @GetMapping("/{id}/sync-state")
    @Operation(summary = "UID cursor and failure state for one mailbox")
    public ApiResponse<MailboxDtos.SyncStateView> syncState(@PathVariable UUID id) {
        CurrentPrincipal principal = principalProvider.principal();
        return ApiResponse.ok(mailboxes.syncStateView(principal, id), requestId());
    }

    private String requestId() {
        return CorrelationIdFilter.currentRequestId();
    }
}
