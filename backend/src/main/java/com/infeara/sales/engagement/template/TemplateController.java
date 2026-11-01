package com.infeara.sales.engagement.template;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/email-templates")
@Tag(name = "Email templates", description = "Template library with merge-field validation")
public class TemplateController {

    private final TemplateService templateService;
    private final CurrentPrincipalProvider principalProvider;

    public TemplateController(TemplateService templateService, CurrentPrincipalProvider principalProvider) {
        this.templateService = templateService;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "List templates")
    public ApiResponse<List<TemplateDtos.TemplateView>> list() {
        return ApiResponse.ok(templateService.list(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Template detail")
    public ApiResponse<TemplateDtos.TemplateView> get(@PathVariable UUID id) {
        return ApiResponse.ok(templateService.get(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping
    @Operation(summary = "Create a draft template")
    public ResponseEntity<ApiResponse<TemplateDtos.TemplateView>> create(
            @Valid @RequestBody TemplateDtos.CreateTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(templateService.create(principalProvider.principal(), request),
                        CorrelationIdFilter.currentRequestId()));
    }

    @PostMapping("/{id}")
    @Operation(summary = "Update a template (returns an approved template to draft)")
    public ApiResponse<TemplateDtos.TemplateView> update(@PathVariable UUID id,
                                                         @Valid @RequestBody
                                                         TemplateDtos.UpdateTemplateRequest request) {
        return ApiResponse.ok(templateService.update(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft for approval")
    public ApiResponse<TemplateDtos.TemplateView> submit(@PathVariable UUID id) {
        return ApiResponse.ok(templateService.submitForApproval(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a template (author cannot self-approve)")
    public ApiResponse<TemplateDtos.TemplateView> approve(@PathVariable UUID id) {
        return ApiResponse.ok(templateService.approve(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/preview")
    @Operation(summary = "Render a template with sample merge values")
    public ApiResponse<TemplateDtos.PreviewResult> preview(
            @Valid @RequestBody TemplateDtos.PreviewRequest request) {
        return ApiResponse.ok(templateService.preview(principalProvider.principal(), request),
                CorrelationIdFilter.currentRequestId());
    }
}
