package com.infeara.sales.engagement.email;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/emails")
@Tag(name = "Email", description = "Composer, approvals and delivery history")
public class EmailController {

    private final EmailService emailService;
    private final CurrentPrincipalProvider principalProvider;

    public EmailController(EmailService emailService, CurrentPrincipalProvider principalProvider) {
        this.emailService = emailService;
        this.principalProvider = principalProvider;
    }

    @PostMapping
    @Operation(summary = "Compose an email (approved template or free text)")
    public ResponseEntity<ApiResponse<EmailDtos.DraftResult>> compose(
            @Valid @RequestBody EmailDtos.ComposeRequest request) {
        EmailDtos.DraftResult result = emailService.compose(principalProvider.principal(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(result, CorrelationIdFilter.currentRequestId()));
    }

    @GetMapping
    @Operation(summary = "List messages by status or lead")
    public ApiResponse<PageResponse<EmailDtos.EmailView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String leadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        var result = emailService.page(principalProvider.principal(), status, leadId, page, size);
        List<EmailDtos.EmailView> items = result.getContent().stream().map(emailService::toView).toList();
        return ApiResponse.ok(PageResponse.of(items, page, Math.min(size, 100), result.getTotalElements(),
                "createdAt:desc", List.of()), CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Message detail with delivery events and live eligibility")
    public ApiResponse<EmailDtos.EmailDetail> detail(@PathVariable UUID id) {
        return ApiResponse.ok(emailService.detail(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/eligibility")
    @Operation(summary = "Check whether an address may be emailed right now")
    public ApiResponse<EmailDtos.EligibilityView> eligibility(@RequestParam String email) {
        return ApiResponse.ok(emailService.eligibility(principalProvider.principal(), email),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a pending message (four-eyes enforced)")
    public ApiResponse<EmailDtos.EmailView> approve(@PathVariable UUID id,
                                                    @RequestBody(required = false)
                                                    EmailDtos.ApproveRequest request) {
        return ApiResponse.ok(emailService.approve(principalProvider.principal(), id,
                request == null ? new EmailDtos.ApproveRequest(null) : request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/send")
    @Operation(summary = "Deliver immediately (re-checks eligibility and sending limits)")
    public ApiResponse<EmailDtos.SendResult> send(@PathVariable UUID id) {
        return ApiResponse.ok(emailService.sendNow(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a message that has not been handed to SMTP")
    public ApiResponse<EmailDtos.EmailView> cancel(@PathVariable UUID id,
                                                   @Valid @RequestBody EmailDtos.CancelRequest request) {
        return ApiResponse.ok(emailService.cancel(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/inbound-reply")
    @Operation(summary = "Record an inbound reply against the originating message")
    public ApiResponse<Map<String, String>> inbound(@Valid @RequestBody EmailDtos.InboundReplyRequest request) {
        emailService.recordInbound(principalProvider.principal(), request);
        return ApiResponse.ok(Map.of("status", "RECORDED"), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/bounce")
    @Operation(summary = "Record a bounce notification")
    public ApiResponse<Map<String, String>> bounce(@Valid @RequestBody EmailDtos.BounceRequest request) {
        emailService.recordBounce(principalProvider.principal(), request);
        return ApiResponse.ok(Map.of("status", "RECORDED"), CorrelationIdFilter.currentRequestId());
    }
}
