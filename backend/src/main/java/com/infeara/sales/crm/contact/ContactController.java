package com.infeara.sales.crm.contact;

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
@RequestMapping("/api/v1/contacts")
@Tag(name = "Contacts", description = "People and communication preferences")
public class ContactController {

    private static final Set<String> ALLOWED_FILTERS = Set.of("q", "accountId");

    private final ContactService contactService;
    private final CurrentPrincipalProvider principalProvider;

    public ContactController(ContactService contactService, CurrentPrincipalProvider principalProvider) {
        this.contactService = contactService;
        this.principalProvider = principalProvider;
    }

    @GetMapping
    @Operation(summary = "List contacts")
    public ApiResponse<PageResponse<PartyDtos.ContactSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(required = false) Map<String, String> filters) {
        PageQuery query = PageQuery.of(page, size, sort, direction, filters, ALLOWED_FILTERS);
        return ApiResponse.ok(contactService.list(principalProvider.principal(), query),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Contact detail")
    public ApiResponse<PartyDtos.ContactDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(contactService.get(principalProvider.principal(), id),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping
    @Operation(summary = "Create a contact")
    public ResponseEntity<ApiResponse<PartyDtos.ContactDetail>> create(
            @Valid @RequestBody PartyDtos.CreateContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(contactService.create(principalProvider.principal(), request),
                        CorrelationIdFilter.currentRequestId()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a contact")
    public ApiResponse<PartyDtos.ContactDetail> update(@PathVariable UUID id,
                                                       @Valid @RequestBody PartyDtos.PatchContactRequest request) {
        return ApiResponse.ok(contactService.update(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }
}
