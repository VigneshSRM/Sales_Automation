package com.infeara.sales.engagement.email;

import com.infeara.sales.config.AppProperties;
import com.infeara.sales.engagement.smtp.SmtpProfile;
import com.infeara.sales.engagement.smtp.SmtpProfileRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker tick that delivers queued email.
 *
 * The dispatcher is deliberately dumb: it selects due messages per workspace, then
 * delegates each one to the transactional {@link EmailService#deliver} so a single
 * failure cannot roll back a whole batch, and so eligibility is re-checked per message
 * immediately before the SMTP hand-off. A separate thread pool is not used: work is
 * already durable in MariaDB, so a crash simply leaves the message QUEUED.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final int BATCH_SIZE = 20;

    private final EmailService emailService;
    private final SmtpProfileRepository smtpProfiles;
    private final AppProperties properties;

    public EmailDispatcher(EmailService emailService, SmtpProfileRepository smtpProfiles,
                           AppProperties properties) {
        this.emailService = emailService;
        this.smtpProfiles = smtpProfiles;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.worker.tick-interval-ms:5000}",
            initialDelayString = "${app.worker.tick-interval-ms:5000}")
    public void tick() {
        if (!properties.getWorker().isEnabled()) {
            return;
        }
        List<SmtpProfile> profiles = smtpProfiles.findAll().stream()
                .filter(profile -> profile.isUsable())
                .toList();
        for (SmtpProfile profile : profiles) {
            List<EmailMessage> due = emailService.due(profile.getWorkspaceId(), BATCH_SIZE);
            for (EmailMessage message : due) {
                if (!profile.getId().equals(message.getSmtpProfileId())) {
                    continue;
                }
                try {
                    emailService.deliver(profile.getWorkspaceId(), message, null);
                } catch (RuntimeException ex) {
                    // Delivery failures are already persisted on the message and in the
                    // audit log; keep the loop alive for the remaining messages.
                    log.warn("Email delivery failed for message {}: {}", message.getId(), ex.getMessage());
                }
            }
        }
    }
}
