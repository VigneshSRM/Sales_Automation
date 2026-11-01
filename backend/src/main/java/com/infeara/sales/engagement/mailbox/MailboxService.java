package com.infeara.sales.engagement.mailbox;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.engagement.smtp.SmtpProfile;
import com.infeara.sales.engagement.smtp.SmtpProfileRepository;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.SecretCipher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mailbox configuration lifecycle (addendum 3.1): create, update, test, enable/disable, health.
 *
 * <p>Rules enforced here, not in the UI:
 * <ul>
 *   <li>a mailbox cannot be enabled until a real IMAP connection test has succeeded;</li>
 *   <li>credentials are encrypted with {@link SecretCipher} and never leave the server;</li>
 *   <li>a credential change forces {@code PENDING_TEST} again, because the old test proves nothing;</li>
 *   <li>{@code provider != IMAP} is accepted only as configuration, and syncing reports explicitly
 *       that the adapter is not implemented (no silent fallback to IMAP).</li>
 * </ul>
 */
@Service
public class MailboxService {

    private static final Logger log = LoggerFactory.getLogger(MailboxService.class);

    private final EmailAccountRepository accounts;
    private final EmailSyncStateRepository syncStates;
    private final SmtpProfileRepository smtpProfiles;
    private final UserRepository users;
    private final ImapInboundConnector connector;
    private final SecretCipher cipher;
    private final CapabilityEvaluator capabilities;
    private final AuditRecorder audit;
    private final Clock clock;

    public MailboxService(EmailAccountRepository accounts, EmailSyncStateRepository syncStates,
                          SmtpProfileRepository smtpProfiles, UserRepository users,
                          ImapInboundConnector connector, SecretCipher cipher,
                          CapabilityEvaluator capabilities, AuditRecorder audit, Clock clock) {
        this.accounts = accounts;
        this.syncStates = syncStates;
        this.smtpProfiles = smtpProfiles;
        this.users = users;
        this.connector = connector;
        this.cipher = cipher;
        this.capabilities = capabilities;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MailboxDtos.AccountView> list(CurrentPrincipal principal) {
        capabilities.require(principal, "email.accounts.read");
        return accounts.findByWorkspaceIdAndDeletedAtIsNullOrderByNameAsc(principal.workspaceId()).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public MailboxDtos.AccountView create(CurrentPrincipal principal,
                                          MailboxDtos.CreateAccountRequest request) {
        capabilities.require(principal, "email.accounts.manage");
        String workspaceId = principal.workspaceId();
        if (accounts.findByWorkspaceIdAndNameAndDeletedAtIsNull(workspaceId, request.name()).isPresent()) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "A mailbox with that name already exists");
        }
        if (!cipher.isConfigured()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Credential encryption is not configured; mailbox secrets cannot be stored safely");
        }

        EmailAccount account = new EmailAccount();
        account.setId(Ids.newId());
        account.setWorkspaceId(workspaceId);
        account.setName(request.name().trim());
        account.setProvider(request.provider());
        account.setMailboxAddress(Normalizer.email(request.mailboxAddress()));
        account.setFromName(request.fromName());
        account.setInboundEnabled(request.inboundEnabled());
        account.setOutboundEnabled(request.outboundEnabled());
        account.setSmtpProfileId(Ids.of(request.smtpProfileId()));
        account.setHost(request.host());
        account.setPort(request.port());
        account.setTlsMode(request.tlsMode() == null ? "STARTTLS" : request.tlsMode());
        account.setAuthMethod(request.authMethod() == null ? "PASSWORD" : request.authMethod());
        account.setFolder(request.folder() == null || request.folder().isBlank() ? "INBOX" : request.folder());
        account.setUsername(request.username() == null ? account.getMailboxAddress() : request.username());
        account.setOwnerUserId(Ids.of(request.ownerUserId()));
        account.setQueueKey(request.queueKey());
        account.setPollIntervalSeconds(request.pollIntervalSeconds() == null ? 300
                : request.pollIntervalSeconds());
        account.setStatus("PENDING_TEST");
        account.setCreatedBy(principal.userId());
        if (request.secret() != null && !request.secret().isBlank()) {
            account.setSecretCiphertext(cipher.encrypt(request.secret()));
            account.setSecretKeyVersion(1);
        }
        if (request.oauthRefreshToken() != null && !request.oauthRefreshToken().isBlank()) {
            account.setOauthRefreshCiphertext(cipher.encrypt(request.oauthRefreshToken()));
        }
        if (account.getSmtpProfileId() != null) {
            requireSmtpProfile(workspaceId, account.getSmtpProfileId());
        }
        requireUser(workspaceId, account.getOwnerUserId());
        EmailAccount saved = accounts.save(account);

        EmailSyncState state = new EmailSyncState();
        state.setId(Ids.newId());
        state.setWorkspaceId(workspaceId);
        state.setAccountId(saved.getId());
        state.setFolder(saved.getFolder());
        syncStates.save(state);

        audit.record(principal, "EMAIL_ACCOUNT_CREATED", "EMAIL_ACCOUNT", saved.getId(), null,
                Map.of("provider", saved.getProvider(), "mailbox", saved.getMailboxAddress(),
                        "status", saved.getStatus()), Map.of());
        return toView(saved);
    }

    @Transactional
    public MailboxDtos.AccountView update(CurrentPrincipal principal, UUID id,
                                          MailboxDtos.UpdateAccountRequest request) {
        capabilities.require(principal, "email.accounts.manage");
        EmailAccount account = require(principal.workspaceId(), Ids.of(id));
        if (request.expectedRowVersion() != null && request.expectedRowVersion() != account.getRowVersion()) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "This mailbox changed since you loaded it. Refresh and try again.");
        }
        Map<String, Object> before = Map.of("name", String.valueOf(account.getName()),
                "status", account.getStatus(), "host", String.valueOf(account.getHost()));
        boolean credentialChanged = false;

        if (request.name() != null) {
            account.setName(request.name().trim());
        }
        if (request.mailboxAddress() != null) {
            account.setMailboxAddress(Normalizer.email(request.mailboxAddress()));
        }
        if (request.fromName() != null) {
            account.setFromName(request.fromName());
        }
        if (request.inboundEnabled() != null) {
            account.setInboundEnabled(request.inboundEnabled());
        }
        if (request.outboundEnabled() != null) {
            account.setOutboundEnabled(request.outboundEnabled());
        }
        if (request.smtpProfileId() != null) {
            requireSmtpProfile(principal.workspaceId(), Ids.of(request.smtpProfileId()));
            account.setSmtpProfileId(Ids.of(request.smtpProfileId()));
        }
        if (request.host() != null) {
            account.setHost(request.host());
            credentialChanged = true;
        }
        if (request.port() != null) {
            account.setPort(request.port());
            credentialChanged = true;
        }
        if (request.tlsMode() != null) {
            account.setTlsMode(request.tlsMode());
            credentialChanged = true;
        }
        if (request.folder() != null) {
            account.setFolder(request.folder());
        }
        if (request.username() != null) {
            account.setUsername(request.username());
            credentialChanged = true;
        }
        if (request.secret() != null && !request.secret().isBlank()) {
            account.setSecretCiphertext(cipher.encrypt(request.secret()));
            credentialChanged = true;
        }
        if (request.oauthRefreshToken() != null && !request.oauthRefreshToken().isBlank()) {
            account.setOauthRefreshCiphertext(cipher.encrypt(request.oauthRefreshToken()));
            credentialChanged = true;
        }
        if (request.ownerUserId() != null) {
            requireUser(principal.workspaceId(), Ids.of(request.ownerUserId()));
            account.setOwnerUserId(Ids.of(request.ownerUserId()));
        }
        if (request.queueKey() != null) {
            account.setQueueKey(request.queueKey());
        }
        if (request.pollIntervalSeconds() != null) {
            account.setPollIntervalSeconds(request.pollIntervalSeconds());
        }
        if (credentialChanged && "ACTIVE".equals(account.getStatus())) {
            account.setStatus("PENDING_TEST");
            log.info("mailbox_credentials_changed account={} status=PENDING_TEST", account.getId());
        }
        EmailAccount saved = accounts.save(account);
        audit.record(principal, "EMAIL_ACCOUNT_UPDATED", "EMAIL_ACCOUNT", saved.getId(), before,
                Map.of("name", saved.getName(), "status", saved.getStatus(),
                        "credentialsRotated", credentialChanged), Map.of());
        return toView(saved);
    }

    @Transactional
    public MailboxDtos.TestConnectionResult test(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "email.accounts.manage");
        EmailAccount account = require(principal.workspaceId(), Ids.of(id));
        MailboxDtos.TestConnectionResult result = connector.test(account);

        account.setLastHealthAt(clock.instant());
        account.setLastError(result.ok() ? null : truncate(result.detail(), 1000));
        if (!result.ok() && "ACTIVE".equals(account.getStatus())) {
            account.setStatus("ERROR");
        }
        accounts.save(account);
        audit.record(principal, "EMAIL_ACCOUNT_TESTED", "EMAIL_ACCOUNT", account.getId(), null,
                Map.of("ok", result.ok(), "detail", truncate(result.detail(), 300)), Map.of());
        return result;
    }

    @Transactional
    public MailboxDtos.AccountView setEnabled(CurrentPrincipal principal, UUID id, boolean enabled) {
        capabilities.require(principal, "email.accounts.manage");
        EmailAccount account = require(principal.workspaceId(), Ids.of(id));
        if (enabled) {
            EmailSyncState state = syncStates.findByAccountId(account.getId()).orElse(null);
            boolean tested = account.getLastHealthAt() != null && account.getLastError() == null;
            if (!tested) {
                throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Run a successful connection test before enabling this mailbox");
            }
            account.setStatus("ACTIVE");
            if (state != null) {
                state.setReconciliationStatus("IDLE");
                state.setConsecutiveFailures(0);
                syncStates.save(state);
            }
        } else {
            account.setStatus("DISABLED");
        }
        EmailAccount saved = accounts.save(account);
        audit.record(principal, enabled ? "EMAIL_ACCOUNT_ENABLED" : "EMAIL_ACCOUNT_DISABLED",
                "EMAIL_ACCOUNT", saved.getId(), null, Map.of("status", saved.getStatus()), Map.of());
        return toView(saved);
    }

    /** Read-only sync health for the settings screen. */
    @Transactional(readOnly = true)
    public MailboxDtos.SyncStateView syncStateView(CurrentPrincipal principal, UUID id) {
        capabilities.require(principal, "email.accounts.read");
        EmailAccount account = require(principal.workspaceId(), Ids.of(id));
        EmailSyncState state = syncStates.findByAccountId(account.getId()).orElse(null);
        if (state == null) {
            return new MailboxDtos.SyncStateView(null, 0L, null, null, 0, null, "NOT_STARTED");
        }
        return new MailboxDtos.SyncStateView(state.getUidValidity(), state.getLastUid(), state.getLastSyncAt(),
                state.getLastSuccessAt(), state.getConsecutiveFailures(), state.getLastError(),
                state.getReconciliationStatus());
    }

    // ---------------------------------------------------------------- worker-facing API

    @Transactional(readOnly = true)
    public Optional<EmailAccount> loadForSync(String accountId) {
        return accounts.findById(accountId).filter(account -> account.getDeletedAt() == null);
    }

    @Transactional(readOnly = true)
    public EmailSyncState syncState(String accountId) {
        return syncStates.findByAccountId(accountId).orElse(null);
    }

    @Transactional
    public void markSyncStarted(String accountId) {
        syncStates.findByAccountId(accountId).ifPresent(state -> {
            state.setLastSyncAt(clock.instant());
            state.setReconciliationStatus("RUNNING");
            syncStates.save(state);
        });
    }

    @Transactional
    public void markSyncSuccess(String accountId, Long uidValidity, long lastUid, String folder) {
        syncStates.findByAccountId(accountId).ifPresent(state -> {
            state.setUidValidity(uidValidity);
            state.setLastUid(lastUid);
            state.setFolder(folder);
            state.setLastSuccessAt(clock.instant());
            state.setConsecutiveFailures(0);
            state.setLastError(null);
            state.setReconciliationStatus("IDLE");
            syncStates.save(state);
        });
        accounts.findById(accountId).ifPresent(account -> {
            account.setLastHealthAt(clock.instant());
            account.setLastError(null);
            accounts.save(account);
        });
    }

    @Transactional
    public void markSyncFailure(String accountId, String detail) {
        syncStates.findByAccountId(accountId).ifPresent(state -> {
            state.setConsecutiveFailures(state.getConsecutiveFailures() + 1);
            state.setLastError(truncate(detail, 1000));
            state.setReconciliationStatus("FAILED");
            syncStates.save(state);
        });
        accounts.findById(accountId).ifPresent(account -> {
            account.setLastHealthAt(clock.instant());
            account.setLastError(truncate(detail, 1000));
            account.setStatus("ERROR");
            accounts.save(account);
        });
    }

    @Transactional(readOnly = true)
    public List<EmailAccount> inboundActiveAccounts() {
        return accounts.findByStatusAndInboundEnabledTrueAndDeletedAtIsNull("ACTIVE");
    }

    @Transactional(readOnly = true)
    public EmailAccount requireAccount(String workspaceId, String accountId) {
        return accounts.findByIdAndWorkspaceIdAndDeletedAtIsNull(accountId, workspaceId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Mailbox not found"));
    }

    // ---------------------------------------------------------------- helpers

    private EmailAccount require(String workspaceId, String accountId) {
        return accounts.findByIdAndWorkspaceIdAndDeletedAtIsNull(accountId, workspaceId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Mailbox not found"));
    }

    private void requireSmtpProfile(String workspaceId, String smtpProfileId) {
        SmtpProfile profile = smtpProfiles.findByIdAndWorkspaceIdAndDeletedAtIsNull(smtpProfileId, workspaceId)
                .orElseThrow(() -> new ApiException(ErrorCode.SMTP_PROFILE_NOT_FOUND, "SMTP profile not found"));
        if (!profile.isUsable()) {
            throw new ApiException(ErrorCode.SMTP_PROFILE_NOT_ACTIVE,
                    "The selected SMTP profile is not active; outbound replies would fail");
        }
    }

    private void requireUser(String workspaceId, String userId) {
        if (userId == null) {
            return;
        }
        users.findByIdAndWorkspaceIdAndDeletedAtIsNull(userId, workspaceId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Owner not found in this workspace"));
    }

    MailboxDtos.AccountView toView(EmailAccount account) {
        EmailSyncState state = syncStates.findByAccountId(account.getId()).orElse(null);
        String smtpName = account.getSmtpProfileId() == null ? null
                : smtpProfiles.findById(account.getSmtpProfileId()).map(SmtpProfile::getName).orElse(null);
        String ownerName = account.getOwnerUserId() == null ? null
                : users.findById(account.getOwnerUserId()).map(User::getDisplayName).orElse(null);
        MailboxDtos.SyncStateView syncView = state == null ? null
                : new MailboxDtos.SyncStateView(state.getUidValidity(), state.getLastUid(),
                        state.getLastSyncAt(), state.getLastSuccessAt(), state.getConsecutiveFailures(),
                        state.getLastError(), state.getReconciliationStatus());
        return new MailboxDtos.AccountView(Ids.parse(account.getId()), account.getName(), account.getProvider(),
                account.getMailboxAddress(), account.getFromName(), account.isInboundEnabled(),
                account.isOutboundEnabled(), smtpName, account.getHost(), account.getPort(),
                account.getTlsMode(), account.getFolder(), account.getAuthMethod(), account.getUsername(),
                account.getStatus(), account.getPollIntervalSeconds(), account.getLastHealthAt(),
                account.getLastError(), account.getSecretCiphertext() != null,
                account.getOauthRefreshCiphertext() != null, ownerName, account.getQueueKey(), syncView,
                account.getRowVersion());
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Instant now() {
        return clock.instant();
    }
}
