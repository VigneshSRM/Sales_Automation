package com.infeara.sales.security;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.identity.TeamMemberRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Record-level authorization (FR-RBAC-002). Scope is OWN, TEAM or WORKSPACE:
 * workspace isolation is enforced by every repository query; this guard adds the
 * owner/team dimension for records that carry an owner.
 */
@Component
public class ScopeGuard {

    private final TeamMemberRepository teamMembers;

    public ScopeGuard(TeamMemberRepository teamMembers) {
        this.teamMembers = teamMembers;
    }

    public boolean canAccess(CurrentPrincipal principal, String ownerUserId) {
        if (principal == null) {
            return false;
        }
        if ("WORKSPACE".equals(principal.scopeType())) {
            return true;
        }
        if (ownerUserId == null) {
            // Unassigned records are visible to users who may assign leads.
            return principal.has("leads.assign");
        }
        if (ownerUserId.equals(principal.userId())) {
            return true;
        }
        if ("TEAM".equals(principal.scopeType())) {
            return sharesTeam(principal.userId(), ownerUserId);
        }
        return false;
    }

    public void requireAccess(CurrentPrincipal principal, String ownerUserId, String what) {
        if (!canAccess(principal, ownerUserId)) {
            throw new ApiException(ErrorCode.RECORD_SCOPE_DENIED,
                    "You do not have access to this " + what + " (record scope)");
        }
    }

    private boolean sharesTeam(String callerId, String ownerId) {
        List<String> callerTeams = teamMembers.findByUserId(callerId).stream()
                .map(member -> member.getTeamId()).toList();
        if (callerTeams.isEmpty()) {
            return false;
        }
        return teamMembers.findByUserId(ownerId).stream()
                .anyMatch(member -> callerTeams.contains(member.getTeamId()));
    }
}
