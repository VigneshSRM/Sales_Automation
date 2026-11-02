package com.infeara.sales.identity;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.identity.dto.UserDtos;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace user administration (FR-WS-003, FR-RBAC-005). */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final RoleRepository roles;
    private final UserSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final CapabilityEvaluator capabilities;
    private final AuditRecorder audit;
    private final Clock clock;

    public UserAdminService(UserRepository users, UserRoleRepository userRoles, RoleRepository roles,
                            UserSessionRepository sessions, PasswordEncoder passwordEncoder,
                            CapabilityEvaluator capabilities, AuditRecorder audit, Clock clock) {
        this.users = users;
        this.userRoles = userRoles;
        this.roles = roles;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.capabilities = capabilities;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserDtos.UserSummary> list(CurrentPrincipal principal, int page, int size, String status) {
        capabilities.require(principal, "users.manage");
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<User> result = users.findByWorkspaceIdAndDeletedAtIsNull(principal.workspaceId(), pageable);
        List<UserDtos.UserSummary> items = result.getContent().stream()
                .filter(user -> status == null || status.isBlank() || status.equals(user.getStatus()))
                .map(user -> new UserDtos.UserSummary(Ids.parse(user.getId()), user.getDisplayName(),
                        user.getEmail(), user.getStatus(), user.getScopeType(), user.isMfaEnabled(),
                        userRoles.findRoleCodesByUserId(user.getId()), user.getLastLoginAt(), user.getRowVersion()))
                .toList();
        return PageResponse.of(items, pageable.getPageNumber(), pageable.getPageSize(), result.getTotalElements(),
                "displayName:asc", List.of());
    }

    @Transactional
    public UserDtos.UserSummary create(CurrentPrincipal principal, UserDtos.CreateUserRequest request) {
        capabilities.require(principal, "users.manage");
        String email = Normalizer.email(request.email());
        if (email == null || !Normalizer.isValidEmail(email)) {
            throw ApiException.field("email", "INVALID_EMAIL", "A valid email address is required");
        }
        if (users.existsByWorkspaceIdAndEmailNormalized(principal.workspaceId(), email)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A user with this email already exists");
        }
        PasswordPolicy.validate(request.initialPassword());
        List<Role> assignedRoles = request.roleCodes().stream()
                .map(code -> roles.findByCode(code).orElseThrow(
                        () -> ApiException.field("roleCodes", "UNKNOWN_ROLE", "Unknown role: " + code)))
                .toList();
        if (assignedRoles.stream().anyMatch(role -> role.getWorkspaceId() != null
                && !role.getWorkspaceId().equals(principal.workspaceId()))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Roles from another workspace cannot be assigned");
        }

        User user = new User();
        user.setId(Ids.newId());
        user.setWorkspaceId(principal.workspaceId());
        user.setEmail(email);
        user.setEmailNormalized(email);
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.initialPassword()));
        user.setStatus("ACTIVE");
        user.setScopeType(request.scopeType() == null ? "OWN" : request.scopeType());
        user.setTimezone(request.timezone() == null ? "Asia/Kolkata" : request.timezone());
        user.setMustChangePassword(true);
        user.setCreatedBy(principal.userId());
        users.save(user);

        for (Role role : assignedRoles) {
            UserRole link = new UserRole();
            link.setId(Ids.newId());
            link.setWorkspaceId(principal.workspaceId());
            link.setUserId(user.getId());
            link.setRoleId(role.getId());
            link.setAssignedBy(principal.userId());
            userRoles.save(link);
        }
        audit.record(principal, "USER_CREATED", "USER", user.getId(), null,
                Map.of("email", email, "roles", request.roleCodes(), "scope", user.getScopeType()),
                Map.of("mustChangePassword", true));
        return new UserDtos.UserSummary(Ids.parse(user.getId()), user.getDisplayName(), user.getEmail(),
                user.getStatus(), user.getScopeType(), user.isMfaEnabled(),
                userRoles.findRoleCodesByUserId(user.getId()), null, user.getRowVersion());
    }

    @Transactional
    public UserDtos.UserSummary update(CurrentPrincipal principal, UUID userId, UserDtos.UpdateUserRequest request) {
        capabilities.require(principal, "users.manage");
        User user = users.findByIdAndWorkspaceIdAndDeletedAtIsNull(userId.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found"));

        Map<String, Object> before = Map.of("status", user.getStatus(), "scope", user.getScopeType(),
                "displayName", user.getDisplayName());
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.scopeType() != null) {
            user.setScopeType(request.scopeType());
        }
        if (request.timezone() != null) {
            user.setTimezone(request.timezone());
        }
        if (request.status() != null) {
            if ("INACTIVE".equals(request.status()) && user.getId().equals(principal.userId())) {
                throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "You cannot deactivate your own account");
            }
            user.setStatus(request.status());
            if ("INACTIVE".equals(request.status())) {
                user.setDeletedAt(clock.instant());
                sessions.deleteByUserId(user.getId());
            }
        }
        user.setUpdatedBy(principal.userId());
        users.save(user);

        if (request.roleCodes() != null) {
            userRoles.deleteByUserId(user.getId());
            for (String code : request.roleCodes()) {
                Role role = roles.findByCode(code)
                        .orElseThrow(() -> ApiException.field("roleCodes", "UNKNOWN_ROLE", "Unknown role: " + code));
                UserRole link = new UserRole();
                link.setId(Ids.newId());
                link.setWorkspaceId(principal.workspaceId());
                link.setUserId(user.getId());
                link.setRoleId(role.getId());
                link.setAssignedBy(principal.userId());
                userRoles.save(link);
            }
        }
        audit.record(principal, "USER_UPDATED", "USER", user.getId(), before,
                Map.of("status", user.getStatus(), "scope", user.getScopeType(),
                        "displayName", user.getDisplayName()),
                Map.of("roles", request.roleCodes() == null ? List.of() : request.roleCodes()));
        return new UserDtos.UserSummary(Ids.parse(user.getId()), user.getDisplayName(), user.getEmail(),
                user.getStatus(), user.getScopeType(), user.isMfaEnabled(),
                userRoles.findRoleCodesByUserId(user.getId()), user.getLastLoginAt(), user.getRowVersion());
    }

    @Transactional(readOnly = true)
    public List<UserDtos.RoleSummary> roles(CurrentPrincipal principal) {
        List<Role> roleList = roles.findByWorkspaceIdIsNullOrderByCode();
        return roleList.stream()
                .map(role -> new UserDtos.RoleSummary(role.getCode(), role.getName(), role.getDescription(),
                        List.copyOf(role.getCapabilities())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserSelection> assignableUsers(CurrentPrincipal principal) {
        return users.findByWorkspaceIdAndStatusAndDeletedAtIsNull(principal.workspaceId(), "ACTIVE").stream()
                .map(user -> new UserSelection(user.getId(), user.getDisplayName(), user.getEmail()))
                .toList();
    }

    public record UserSelection(String id, String displayName, String email) {
    }
}
