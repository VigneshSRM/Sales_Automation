package com.infeara.sales.web;

import com.infeara.sales.analytics.GlobalSearchService;
import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "Permission-filtered global search")
public class SearchController {

    private final GlobalSearchService searchService;
    private final CurrentPrincipalProvider principalProvider;

    public SearchController(GlobalSearchService searchService, CurrentPrincipalProvider principalProvider) {
        this.searchService = searchService;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "Search leads, accounts, contacts and opportunities")
    public ApiResponse<List<GlobalSearchService.SearchHit>> search(@RequestParam("q") String query) {
        return ApiResponse.ok(searchService.search(principalProvider.principal(), query),
                CorrelationIdFilter.currentRequestId());
    }
}
