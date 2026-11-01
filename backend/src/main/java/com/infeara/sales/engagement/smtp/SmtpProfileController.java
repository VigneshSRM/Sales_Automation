package com.infeara.sales.engagement.smtp;

import com.infeara.sales.common.api.ApiResponse;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/smtp-profiles")
@Tag(name = "SMTP profiles", description = "Outbound mail accounts (secrets never returned)")
public class SmtpProfileController {

    private final SmtpProfileService smtpProfileService;
    private final CurrentPrincipalProvider principalProvider;

    public SmtpProfileController(SmtpProfileService smtpProfileService,
                                 CurrentPrincipalProvider principalProvider) {
        this.smtpProfileService = smtpProfileService;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "List SMTP profiles (no credentials in the payload)")
    public ApiResponse<List<SmtpDtos.SmtpProfileView>> list() {
        return ApiResponse.ok(smtpProfileService.list(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping
    @Operation(summary = "Create an SMTP profile in PENDING_TEST state")
    public ResponseEntity<ApiResponse<SmtpDtos.SmtpProfileView>> create(
            @Valid @RequestBody SmtpDtos.CreateSmtpProfileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(smtpProfileService.create(principalProvider.principal(), request),
                        CorrelationIdFilter.currentRequestId()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a profile (credential rotation forces a re-test)")
    public ApiResponse<SmtpDtos.SmtpProfileView> update(@PathVariable UUID id,
                                                        @Valid @RequestBody
                                                        SmtpDtos.UpdateSmtpProfileRequest request) {
        return ApiResponse.ok(smtpProfileService.update(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Run a live connection test (and optionally a delivery test)")
    public ApiResponse<SmtpDtos.TestConnectionResult> test(@PathVariable UUID id,
                                                           @RequestBody(required = false)
                                                           SmtpDtos.TestConnectionRequest request) {
        return ApiResponse.ok(smtpProfileService.test(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Enable or disable a profile")
    public ApiResponse<SmtpDtos.SmtpProfileView> status(@PathVariable UUID id,
                                                        @RequestBody Map<String, String> body) {
        String status = body.getOrDefault("status", "DISABLED").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ACTIVE", "DISABLED", "ERROR", "PENDING_TEST").contains(status)) {
            throw new com.infeara.sales.common.error.ApiException(
                    com.infeara.sales.common.error.ErrorCode.VALIDATION_FAILED, "Unsupported status: " + status);
        }
        return ApiResponse.ok(smtpProfileService.setStatus(principalProvider.principal(), id, status),
                CorrelationIdFilter.currentRequestId());
    }
}
