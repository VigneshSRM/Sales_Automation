package com.infeara.sales.security;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Capability checks used by application services (FR-RBAC-003/004). Hiding a button
 * is never authorization: every mutating use case calls {@link #require}.
 */
@Component
public class CapabilityEvaluator {

    public boolean has(CurrentPrincipal principal, String capability) {
        return principal != null && principal.has(capability);
    }

    public void require(CurrentPrincipal principal, String capability) {
        if (!has(principal, capability)) {
            throw new ApiException(ErrorCode.CAPABILITY_REQUIRED,
                    "This action requires the '" + capability + "' capability");
        }
    }

    public void requireAny(CurrentPrincipal principal, String... capabilities) {
        if (principal == null || !principal.anyOf(capabilities)) {
            throw new ApiException(ErrorCode.CAPABILITY_REQUIRED,
                    "This action requires one of the capabilities " + String.join(", ", capabilities));
        }
    }

    /**
     * Privileged capabilities require a satisfied MFA session (FR-AUTH-004).
     * The check is defensive: login already enforces enrolment for these users.
     */
    public void requireMfaSatisfied(CurrentPrincipal principal) {
        if (principal == null || !principal.mfaSatisfied()) {
            throw new ApiException(ErrorCode.MFA_REQUIRED,
                    "Multi-factor authentication is required for this action");
        }
    }
}
