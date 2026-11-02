package com.infeara.sales.web;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.crm.catalog.CatalogService;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalogues", description = "Services, sources, stages, loss reasons and industries")
public class CatalogController {

    private final CatalogService catalog;
    private final CurrentPrincipalProvider principalProvider;

    public CatalogController(CatalogService catalog, CurrentPrincipalProvider principalProvider) {
        this.catalog = catalog;
        this.principalProvider = principalProvider;
    }

    @GetMapping("/services")
    @Operation(summary = "Service catalogue")
    public ApiResponse<List<CatalogService.ServiceView>> services() {
        return ApiResponse.ok(catalog.services(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @PatchMapping("/services/{id}")
    @Operation(summary = "Update a service offering")
    public ApiResponse<CatalogService.ServiceView> updateService(@PathVariable UUID id,
                                                                @RequestBody ServiceUpdate body) {
        return ApiResponse.ok(catalog.updateService(principalProvider.principal(), id, body.name(),
                body.division(), body.description(), body.active()), CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/sources")
    @Operation(summary = "Lead sources")
    public ApiResponse<List<CatalogService.SourceView>> sources() {
        return ApiResponse.ok(catalog.sources(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/stages")
    @Operation(summary = "Pipeline stages")
    public ApiResponse<List<CatalogService.StageView>> stages() {
        return ApiResponse.ok(catalog.stages(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @PatchMapping("/stages/{id}")
    @Operation(summary = "Update a pipeline stage")
    public ApiResponse<CatalogService.StageView> updateStage(@PathVariable UUID id,
                                                            @RequestBody StageUpdate body) {
        return ApiResponse.ok(catalog.updateStage(principalProvider.principal(), id, body.name(),
                body.allowSkip(), body.active()), CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/loss-reasons")
    @Operation(summary = "Controlled loss reasons")
    public ApiResponse<List<CatalogService.SimpleRef>> lossReasons() {
        return ApiResponse.ok(catalog.lossReasons(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/industries")
    @Operation(summary = "Industries")
    public ApiResponse<List<CatalogService.SimpleRef>> industries() {
        return ApiResponse.ok(catalog.industries(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    public record ServiceUpdate(String name, String division, String description, Boolean active) {
    }

    public record StageUpdate(String name, Boolean allowSkip, Boolean active) {
    }
}
