package com.infeara.sales.crm.account;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.paging.PageQuery;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.crm.dto.PartyDtos;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
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

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Company records")
public class AccountController {

    private static final Set<String> ALLOWED_FILTERS = Set.of("q", "relationshipStatus");

    private final AccountService accountService;
    private final CurrentPrincipalProvider principalProvider;

    public AccountController(AccountService accountService, CurrentPrincipalProvider principalProvider) {
        this.accountService = accountService;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "List accounts")
    public ApiResponse<PageResponse<PartyDtos.AccountSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) Map<String, String> filters) {
        PageQuery query = PageQuery.of(page, size, sort, direction, filters, ALLOWED_FILTERS);
        return ApiResponse.ok(accountService.list(principalProvider.principal(), query),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Account detail with contacts")
    public ApiResponse<PartyDtos.AccountDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(accountService.get(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping
    @Operation(summary = "Create an account")
    public ResponseEntity<ApiResponse<PartyDtos.AccountDetail>> create(
            @Valid @RequestBody PartyDtos.CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(accountService.create(principalProvider.principal(), request),
                        CorrelationIdFilter.currentRequestId()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update an account")
    public ApiResponse<PartyDtos.AccountDetail> update(@PathVariable UUID id,
                                                       @Valid @RequestBody PartyDtos.PatchAccountRequest request) {
        return ApiResponse.ok(accountService.update(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }
}
