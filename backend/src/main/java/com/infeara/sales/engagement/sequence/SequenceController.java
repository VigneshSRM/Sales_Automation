package com.infeara.sales.engagement.sequence;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/v1/sequences")
@Tag(name = "Sequences", description = "Follow-up cadences and enrollments")
public class SequenceController {

    private final SequenceService sequenceService;
    private final CurrentPrincipalProvider principalProvider;

    public SequenceController(SequenceService sequenceService, CurrentPrincipalProvider principalProvider) {
        this.sequenceService = sequenceService;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "List sequences with their active cadence")
    public ApiResponse<List<SequenceService.SequenceView>> list() {
        return ApiResponse.ok(sequenceService.list(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping
    @Operation(summary = "Create a draft sequence with day-offset steps")
    public ResponseEntity<ApiResponse<SequenceService.SequenceView>> create(
            @Valid @RequestBody SequenceService.CreateSequenceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(sequenceService.create(principalProvider.principal(), request),
                        CorrelationIdFilter.currentRequestId()));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate the draft version (archives the previous one)")
    public ApiResponse<SequenceService.SequenceView> activate(@PathVariable UUID id) {
        return ApiResponse.ok(sequenceService.activate(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause a sequence so no new steps are queued")
    public ApiResponse<SequenceService.SequenceView> pause(@PathVariable UUID id) {
        return ApiResponse.ok(sequenceService.pause(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/enroll")
    @Operation(summary = "Enrol a lead (eligibility and duplicate enrollment are enforced)")
    public ResponseEntity<ApiResponse<SequenceService.EnrollmentView>> enroll(
            @PathVariable UUID id, @Valid @RequestBody EnrollRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(sequenceService.enroll(principalProvider.principal(), id,
                        request.leadId(), request.smtpProfileId()), CorrelationIdFilter.currentRequestId()));
    }

    @GetMapping("/enrollments")
    @Operation(summary = "List enrollments by status")
    public ApiResponse<PageResponse<SequenceService.EnrollmentView>> enrollments(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<SequenceService.EnrollmentView> result =
                sequenceService.enrollmentPage(principalProvider.principal(), status, page, size);
        return ApiResponse.ok(PageResponse.of(result.getContent(), page, size, result.getTotalElements(),
                "enrolledAt:desc", List.of()), CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/enrollments/{id}/stop")
    @Operation(summary = "Stop an enrollment (reply, unsubscribe, booking or manual)")
    public ApiResponse<SequenceService.EnrollmentView> stop(@PathVariable UUID id,
                                                            @RequestBody(required = false) StopRequest request) {
        return ApiResponse.ok(sequenceService.stop(principalProvider.principal(), id,
                request == null ? "MANUAL" : request.reason()), CorrelationIdFilter.currentRequestId());
    }

    public record EnrollRequest(@NotNull UUID leadId, UUID smtpProfileId) {
    }

    public record StopRequest(String reason) {
    }
}
