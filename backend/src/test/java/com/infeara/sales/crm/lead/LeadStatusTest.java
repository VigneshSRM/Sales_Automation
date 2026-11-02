package com.infeara.sales.crm.lead;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BR-LEAD-001: the lifecycle graph is a domain rule, not a UI convention. */
class LeadStatusTest {

    @Test
    @DisplayName("allows the documented forward path")
    void allowsForwardPath() {
        assertThat(LeadStatus.NEW.canTransitionTo(LeadStatus.QUALIFIED)).isTrue();
        assertThat(LeadStatus.QUALIFIED.canTransitionTo(LeadStatus.CONTACTED)).isTrue();
        assertThat(LeadStatus.CONTACTED.canTransitionTo(LeadStatus.ENGAGED)).isTrue();
        assertThat(LeadStatus.ENGAGED.canTransitionTo(LeadStatus.MEETING_SCHEDULED)).isTrue();
        assertThat(LeadStatus.MEETING_SCHEDULED.canTransitionTo(LeadStatus.DISCOVERY_COMPLETED)).isTrue();
        assertThat(LeadStatus.DISCOVERY_COMPLETED.canTransitionTo(LeadStatus.CONVERTED)).isTrue();
    }

    @Test
    @DisplayName("refuses to skip qualification")
    void refusesSkipping() {
        assertThat(LeadStatus.NEW.canTransitionTo(LeadStatus.CONTACTED)).isFalse();
        assertThat(LeadStatus.NEW.canTransitionTo(LeadStatus.CONVERTED)).isFalse();
        assertThat(LeadStatus.QUALIFIED.canTransitionTo(LeadStatus.CONVERTED)).isFalse();
    }

    @Test
    @DisplayName("converted and archived leads are terminal")
    void terminalStates() {
        assertThat(LeadStatus.CONVERTED.canTransitionTo(LeadStatus.QUALIFIED)).isFalse();
        assertThat(LeadStatus.ARCHIVED.canTransitionTo(LeadStatus.NEW)).isFalse();
    }

    @Test
    @DisplayName("a disqualified lead can only be reopened or left alone")
    void disqualifiedMustBeReopened() {
        assertThat(LeadStatus.DISQUALIFIED.canTransitionTo(LeadStatus.REOPENED)).isTrue();
        assertThat(LeadStatus.DISQUALIFIED.canTransitionTo(LeadStatus.QUALIFIED)).isFalse();
        assertThat(LeadStatus.REOPENED.canTransitionTo(LeadStatus.QUALIFIED)).isTrue();
    }
}
