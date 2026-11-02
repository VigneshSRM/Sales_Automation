package com.infeara.sales.automation.integration;

import com.infeara.sales.ai.AiGateway;
import com.infeara.sales.engagement.mailbox.EmailAccount;
import com.infeara.sales.engagement.mailbox.EmailAccountRepository;
import com.infeara.sales.engagement.smtp.SmtpProfile;
import com.infeara.sales.engagement.smtp.SmtpProfileRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports what is actually configured, read from the database rather than hard-coded prose.
 *
 * <p>{@code CONFIGURED} means a row exists and has passed its own health check;
 * {@code NOT_CONFIGURED} means the operator has not set it up. Neither state is faked: an
 * integration that cannot work says so here before a user relies on it.
 */
@Service
public class IntegrationStatusService {

    private final SmtpProfileRepository smtpProfiles;
    private final EmailAccountRepository mailboxes;
    private final AiGateway aiGateway;
    private final CapabilityEvaluator capabilities;

    public IntegrationStatusService(SmtpProfileRepository smtpProfiles, EmailAccountRepository mailboxes,
                                    AiGateway aiGateway, CapabilityEvaluator capabilities) {
        this.smtpProfiles = smtpProfiles;
        this.mailboxes = mailboxes;
        this.aiGateway = aiGateway;
        this.capabilities = capabilities;
    }

    @Transactional(readOnly = true)
    public List<IntegrationHealth> health(CurrentPrincipal principal) {
        capabilities.require(principal, "integrations.manage");
        List<IntegrationHealth> items = new ArrayList<>();

        List<SmtpProfile> profiles = smtpProfiles
                .findByWorkspaceIdAndDeletedAtIsNullOrderByNameAsc(principal.workspaceId());
        long activeProfiles = profiles.stream().filter(p -> "ACTIVE".equals(p.getStatus())).count();
        items.add(activeProfiles == 0
                ? new IntegrationHealth("SMTP outbound", "NOT_CONFIGURED", "Release 3",
                        profiles.isEmpty()
                                ? "No sender profile exists. Outbound email, sequences and replies are disabled."
                                : profiles.size() + " profile(s) exist but none is ACTIVE; send a test to activate one.")
                : new IntegrationHealth("SMTP outbound", "CONFIGURED", "Release 3",
                        activeProfiles + " active sender profile(s); sending uses the configured daily/hourly caps."));

        List<EmailAccount> accounts = mailboxes
                .findByWorkspaceIdAndDeletedAtIsNullOrderByNameAsc(principal.workspaceId());
        long activeMailboxes = accounts.stream().filter(a -> "ACTIVE".equals(a.getStatus())).count();
        long failing = accounts.stream().filter(a -> a.getLastError() != null).count();
        items.add(activeMailboxes == 0
                ? new IntegrationHealth("Inbound mailbox (IMAP)", "NOT_CONFIGURED", "Addendum §2",
                        accounts.isEmpty()
                                ? "No mailbox is connected. The unified inbox stays empty because inbound "
                                        + "mail is only ever read from a real mailbox."
                                : accounts.size() + " mailbox(es) exist but none passed its connection test.")
                : new IntegrationHealth("Inbound mailbox (IMAP)", failing > 0 ? "DEGRADED" : "CONFIGURED",
                        "Addendum §2", activeMailboxes + " active mailbox(es)"
                                + (failing > 0 ? ", " + failing + " reporting a sync error" : "") + "."));

        items.add(aiGateway.anyProviderConfigured()
                ? new IntegrationHealth("AI provider", "CONFIGURED", "Addendum §5",
                        "An API key is present. Analysis uses the configured model and validates the "
                                + "JSON Schema before anything is stored.")
                : new IntegrationHealth("AI provider", "NOT_CONFIGURED", "Addendum §5",
                        "No AI API key is configured. Email analysis returns AI_PROVIDER_UNAVAILABLE "
                                + "instead of a fabricated result."));

        items.add(new IntegrationHealth("Calendly", "NOT_CONFIGURED", "Release 4",
                "Booking links and webhook subscriptions are not configured."));
        return items;
    }

    public record IntegrationHealth(String integration, String status, String release, String detail) {
    }
}
