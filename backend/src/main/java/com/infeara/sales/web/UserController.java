package com.infeara.sales.web;

import com.infeara.sales.common.api.ApiResponse;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.web.CorrelationIdFilter;
import com.infeara.sales.identity.UserAdminService;
import com.infeara.sales.identity.dto.UserDtos;
import com.infeara.sales.security.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Users and roles", description = "Workspace user and RBAC administration")
public class UserController {

    private final UserAdminService userAdmin;
    private final CurrentPrincipalProvider principalProvider;

    public UserController(UserAdminService userAdmin, CurrentPrincipalProvider principalProvider) {
        this.userAdmin = userAdmin;
        this.principalProvider = principalProvider;
    }

    @GetMapping("/users")
    @Operation(summary = "List workspace users")
    public ApiResponse<PageResponse<UserDtos.UserSummary>> list(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "25") int size,
                                                                @RequestParam(required = false) String status) {
        return ApiResponse.ok(userAdmin.list(principalProvider.principal(), page, size, status),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/users/assignable")
    @Operation(summary = "Users available as record owners")
    public ApiResponse<List<UserAdminService.UserSelection>> assignable() {
        return ApiResponse.ok(userAdmin.assignableUsers(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }

    @PostMapping("/users")
    @Operation(summary = "Create a user")
    public ApiResponse<UserDtos.UserSummary> create(@Valid @RequestBody UserDtos.CreateUserRequest request) {
        return ApiResponse.ok(userAdmin.create(principalProvider.principal(), request),
                CorrelationIdFilter.currentRequestId());
    }

    @PatchMapping("/users/{id}")
    @Operation(summary = "Update a user, roles or status")
    public ApiResponse<UserDtos.UserSummary> update(@PathVariable UUID id,
                                                    @Valid @RequestBody UserDtos.UpdateUserRequest request) {
        return ApiResponse.ok(userAdmin.update(principalProvider.principal(), id, request),
                CorrelationIdFilter.currentRequestId());
    }

    @GetMapping("/roles")
    @Operation(summary = "List roles and their capabilities")
    public ApiResponse<List<UserDtos.RoleSummary>> roles() {
        return ApiResponse.ok(userAdmin.roles(principalProvider.principal()),
                CorrelationIdFilter.currentRequestId());
    }
}
