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

    private static final Map<LeadStatus, Set<LeadStatus>> ALLOWED = Map.ofEntries(
            Map.entry(NEW, Set.of(RESEARCHING, QUALIFIED, NURTURE, DISQUALIFIED)),
            Map.entry(RESEARCHING, Set.of(QUALIFIED, NURTURE, DISQUALIFIED)),
            Map.entry(QUALIFIED, Set.of(CONTACTED, NURTURE, DISQUALIFIED)),
            Map.entry(CONTACTED, Set.of(ENGAGED, NURTURE, DISQUALIFIED)),
            Map.entry(ENGAGED, Set.of(MEETING_SCHEDULED, NURTURE, DISQUALIFIED)),
            Map.entry(MEETING_SCHEDULED, Set.of(DISCOVERY_COMPLETED, NURTURE)),
            Map.entry(DISCOVERY_COMPLETED, Set.of(CONVERTED, NURTURE, DISQUALIFIED)),
            Map.entry(NURTURE, Set.of(QUALIFIED, DISQUALIFIED)),
            Map.entry(DISQUALIFIED, Set.of(REOPENED)),
            Map.entry(REOPENED, Set.of(QUALIFIED, RESEARCHING, NURTURE, DISQUALIFIED)),
            Map.entry(CONVERTED, Set.<LeadStatus>of()),
            Map.entry(ARCHIVED, Set.<LeadStatus>of()));

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
