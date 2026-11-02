package com.infeara.sales.sales.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only cross-module evidence queries for opportunity stage gates (BR-OPP-002..007).
 * Kept as explicit SQL so the gate logic does not depend on entities owned by modules
 * delivered in later releases (proposals, meetings).
 */
@Repository
public class OpportunityGateQueries {

    private final JdbcTemplate jdbc;

    public OpportunityGateQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasApprovedProposal(String opportunityId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM proposal_versions pv
                  JOIN proposals p ON p.id = pv.proposal_id
                 WHERE p.opportunity_id = ? AND pv.status IN ('APPROVED','SENT','ACCEPTED')
                """, Integer.class, opportunityId);
        return count != null && count > 0;
    }

    public boolean hasProposalSendEvidence(String opportunityId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM proposal_sends ps
                  JOIN proposal_versions pv ON pv.id = ps.proposal_version_id
                  JOIN proposals p ON p.id = pv.proposal_id
                 WHERE p.opportunity_id = ?
                """, Integer.class, opportunityId);
        return count != null && count > 0;
    }

    public boolean hasDiscoveryInteraction(String opportunityId, String workspaceId) {
        Integer activities = jdbc.queryForObject("""
                SELECT COUNT(*) FROM activities
                 WHERE workspace_id = ? AND related_type = 'OPPORTUNITY' AND related_id = ?
                   AND type IN ('MEETING','CALL','NOTE')
                """, Integer.class, workspaceId, opportunityId);
        Integer meetings = jdbc.queryForObject("""
                SELECT COUNT(*) FROM meetings WHERE workspace_id = ? AND opportunity_id = ?
                   AND status IN ('SCHEDULED','RESCHEDULED','COMPLETED')
                """, Integer.class, workspaceId, opportunityId);
        return (activities != null && activities > 0) || (meetings != null && meetings > 0);
    }

    public boolean hasDraftProposal(String opportunityId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM proposals WHERE opportunity_id = ?", Integer.class, opportunityId);
        return count != null && count > 0;
    }
}
