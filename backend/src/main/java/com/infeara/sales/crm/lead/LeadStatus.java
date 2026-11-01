package com.infeara.sales.crm.lead;

import java.util.Map;
import java.util.Set;

/**
 * Lead lifecycle graph (BR-LEAD-001). Transitions are a domain concern: the
 * database stores the status, this enum owns the legal edges.
 */
public enum LeadStatus {
    NEW,
    RESEARCHING,
    QUALIFIED,
    CONTACTED,
    ENGAGED,
    MEETING_SCHEDULED,
    DISCOVERY_COMPLETED,
    CONVERTED,
    NURTURE,
    DISQUALIFIED,
    REOPENED,
    ARCHIVED;

    private static final Map<LeadStatus, Set<LeadStatus>> ALLOWED = Map.of(
            NEW, Set.of(RESEARCHING, QUALIFIED, NURTURE, DISQUALIFIED),
            RESEARCHING, Set.of(QUALIFIED, NURTURE, DISQUALIFIED),
            QUALIFIED, Set.of(CONTACTED, NURTURE, DISQUALIFIED),
            CONTACTED, Set.of(ENGAGED, NURTURE, DISQUALIFIED),
            ENGAGED, Set.of(MEETING_SCHEDULED, NURTURE, DISQUALIFIED),
            MEETING_SCHEDULED, Set.of(DISCOVERY_COMPLETED, NURTURE),
            DISCOVERY_COMPLETED, Set.of(CONVERTED, NURTURE, DISQUALIFIED),
            NURTURE, Set.of(QUALIFIED, DISQUALIFIED),
            DISQUALIFIED, Set.of(REOPENED),
            REOPENED, Set.of(QUALIFIED, RESEARCHING, NURTURE, DISQUALIFIED),
            CONVERTED, Set.of(),
            ARCHIVED, Set.of());

    public boolean canTransitionTo(LeadStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return this == CONVERTED || this == ARCHIVED;
    }

    public Set<LeadStatus> allowedTargets() {
        return ALLOWED.getOrDefault(this, Set.of());
    }
}
