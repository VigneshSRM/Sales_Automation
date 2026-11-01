package com.infeara.sales.engagement.mailbox;

import com.infeara.sales.automation.job.JobEngine;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Inbound synchronisation orchestration (addendum 3.2).
 *
 * <p>Ordering rules that this class enforces:
 * <ul>
 *   <li>No network I/O inside a database transaction: the IMAP session is opened and drained by
 *       {@link ImapInboundConnector} first, then each message is written in its own transaction by
 *       {@link InboundEmailService}.</li>
 *   <li>One sync per mailbox at a time, guarded in-process; the durable job lease provides the
 *       cross-process guard, and the {@code inbound_key} unique index provides the final dedupe.</li>
 *   <li>UIDVALIDITY changes are handled by re-reading from UID 0; duplicates are then absorbed.</li>
 * </ul>
 *
 * <p>Single-instance caveat: the in-process lock is advisory only. Running the WAR on several Tomcat
 * nodes is safe (jobs are leased) but wastes one redundant IMAP session per concurrent attempt.
 */
@Service
public class MailboxSyncService {

    private static final Logger log = LoggerFactory.getLogger(MailboxSyncService.class);

    private final MailboxService mailboxes;
    private final ImapInboundConnector connector;
    private final InboundEmailService inbound;
    private final JobEngine jobs;
    private final Clock clock;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public MailboxSyncService(MailboxService mailboxes, ImapInboundConnector connector,
                              InboundEmailService inbound, JobEngine jobs, Clock clock) {
        this.mailboxes = mailboxes;
        this.connector = connector;
        this.inbound = inbound;
        this.jobs = jobs;
        this.clock = clock;
    }

    /** Scheduled discovery: every active mailbox gets a leased sync job, at most once per interval. */
    @Scheduled(fixedDelayString = "${app.email.inbound.discovery-interval-ms:60000}", initialDelay = 30000)
    @org.springframework.transaction.annotation.Transactional
    public void discover() {
        for (EmailAccount account : mailboxes.inboundActiveAccounts()) {
            try {
                enqueueSync(account);
            } catch (RuntimeException ex) {
                log.warn("mailbox_sync_enqueue_failed account={} reason={}", account.getId(), ex.getMessage());
            }
        }
    }

    private void enqueueSync(EmailAccount account) {
        long bucket = clock.millis() / Math.max(account.getPollIntervalSeconds(), 60) / 1000L;
        jobs.enqueue(account.getWorkspaceId(), "MAILBOX_SYNC",
                "mailbox-sync:" + account.getId() + ":" + bucket,
                java.util.Map.of("accountId", account.getId()), 40);
    }

    /**
     * Synchronises one mailbox. Returns the counters that were persisted; never throws for a single
     * malformed message, and records the failure on the mailbox so the UI can show it.
     */
    public MailboxDtos.SyncResult sync(String accountId) {
        EmailAccount account = mailboxes.loadForSync(accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Mailbox not found"));
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Mailbox " + account.getName() + " is " + account.getStatus()
                            + "; only ACTIVE mailboxes are synchronised");
        }
        if (!account.isInboundEnabled()) {
            return new MailboxDtos.SyncResult(null, account.getMailboxAddress(), 0, 0, 0, 0,
                    "Inbound is disabled for this mailbox", clock.instant());
        }
        if (!inFlight.add(accountId)) {
            return new MailboxDtos.SyncResult(null, account.getMailboxAddress(), 0, 0, 0, 0,
                    "A sync is already running for this mailbox", clock.instant());
        }
        try {
            EmailSyncState state = mailboxes.syncState(accountId);
            long lastUid = state == null ? 0 : state.getLastUid();
            Long uidValidity = state == null ? null : state.getUidValidity();
            mailboxes.markSyncStarted(accountId);

            ImapInboundConnector.FetchResult fetched = connector.fetch(account, lastUid, uidValidity);
            int persisted = 0;
            int duplicates = 0;
            int failed = 0;
            for (ParsedEmail email : fetched.messages()) {
                try {
                    InboundEmailService.PersistResult result = inbound.persist(account, email);
                    if (result.duplicate()) {
                        duplicates++;
                    } else {
                        persisted++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("inbound_message_persist_failed account={} uid={} reason={}",
                            accountId, email.uid(), ex.getMessage());
                }
            }
            mailboxes.markSyncSuccess(accountId, fetched.uidValidity(), fetched.highestUid(),
                    account.getFolder());
            log.info("mailbox_sync_complete account={} fetched={} persisted={} duplicates={} failed={}",
                    accountId, fetched.messages().size(), persisted, duplicates, failed);
            return new MailboxDtos.SyncResult(null, account.getMailboxAddress(), fetched.messages().size(),
                    persisted, duplicates, failed,
                    failed == 0 ? "All messages persisted" : failed + " message(s) could not be stored",
                    clock.instant());
        } catch (RuntimeException ex) {
            mailboxes.markSyncFailure(accountId, ex.getMessage());
            throw ex;
        } finally {
            inFlight.remove(accountId);
        }
    }

    /** Used by the API to report health without leaking credentials. */
    public List<EmailAccount> activeAccounts(String workspaceId) {
        return mailboxes.inboundActiveAccounts().stream()
                .filter(account -> account.getWorkspaceId().equals(workspaceId))
                .toList();
    }

    public Instant now() {
        return clock.instant();
    }
}
