package com.infeara.sales.security;

import java.util.Set;

/**
 * Authenticated caller context. Every service obtains it from the security context,
 * never from request parameters (prevents client-supplied tenant/user spoofing).
 */
public record CurrentPrincipal(
        String userId,
        String workspaceId,
        String email,
        String displayName,
        String scopeType,
        String timezone,
        boolean mfaSatisfied,
        String sessionId,
        Set<String> capabilities) {

    public boolean has(String capability) {
        return capabilities.contains(capability);
    }

    public boolean anyOf(String... capabilityCodes) {
        for (String code : capabilityCodes) {
            if (capabilities.contains(code)) {
                return true;
            }
        }
        return false;
    }
}
