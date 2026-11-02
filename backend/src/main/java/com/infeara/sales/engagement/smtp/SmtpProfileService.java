package com.infeara.sales.engagement.smtp;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.config.AppProperties;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.SecretCipher;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SMTP profile administration (FR-EMAIL-001..003).
 *
 * Rules enforced here and nowhere else:
 *  - the secret is encrypted on write and never returned;
 *  - a profile must pass a live connection test before it can be activated;
 *  - a profile that has never been tested cannot send;
 *  - TLS mode NONE is rejected when the production profile is active;
 *  - deleting/rotating credentials is audited without recording the secret itself.
 */
@Service
public class SmtpProfileService {

    private final SmtpProfileRepository profiles;
    private final SmtpSender sender;
    private final SecretCipher cipher;
    private final CapabilityEvaluator capabilities;
    private final AuditRecorder audit;
    private final AppProperties appProperties;
    private final Environment environment;
    private final Clock clock;

    public SmtpProfileService(SmtpProfileRepository profiles, SmtpSender sender, SecretCipher cipher,
                              CapabilityEvaluator capabilities, AuditRecorder audit,
                              AppProperties appProperties, Environment environment, Clock clock) {
        this.profiles = profiles;
        this.sender = sender;
        this.cipher = cipher;
        this.capabilities = capabilities;
        this.audit = audit;
        this.appProperties = appProperties;
        this.environment = environment;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SmtpDtos.SmtpProfileView> list(CurrentPrincipal principal) {
        capabilities.require(principal, "smtp.manage");
        return profiles.findByWorkspaceIdAndDeletedAtIsNullOrderByNameAsc(principal.workspaceId()).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public SmtpDtos.SmtpProfileView create(CurrentPrincipal principal, SmtpDtos.CreateSmtpProfileRequest request) {
        capabilities.require(principal, "smtp.manage");
        requireEncryptionConfigured();
        if (profiles.existsByWorkspaceIdAndNameAndDeletedAtIsNull(principal.workspaceId(), request.name().trim())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "An SMTP profile with that name already exists");
        }
        requireTlsAllowedForEnvironment(request.tlsMode());
        SmtpProfile profile = new SmtpProfile();
        profile.setId(Ids.newId());
        profile.setWorkspaceId(principal.workspaceId());
        applyFields(profile, request.name(), request.fromName(), request.fromEmail(), request.replyToEmail(),
                request.host(), request.port(), request.tlsMode(), request.username(),
                request.dailyLimit(), request.hourlyLimit());
        profile.setSecretCiphertext(cipher.encrypt(request.secret()));
        profile.setSecretKeyVersion(1);
        profile.setStatus("PENDING_TEST");
        profile.setCreatedBy(principal.userId());
        profiles.save(profile);
        audit.record(principal, "SMTP_PROFILE_CREATED", "SMTP_PROFILE", profile.getId(), null,
                Map.of("name", profile.getName(), "host", profile.getHost(), "port", profile.getPort(),
                        "tlsMode", profile.getTlsMode(), "secret", SecretCipher.mask(request.secret())),
                Map.of());
        return toView(profile);
    }

    @Transactional
    public SmtpDtos.SmtpProfileView update(CurrentPrincipal principal, UUID id,
                                          SmtpDtos.UpdateSmtpProfileRequest request) {
        capabilities.require(principal, "smtp.manage");
        SmtpProfile profile = require(principal, id);
        if (request.expectedRowVersion() != null && request.expectedRowVersion() != profile.getRowVersion()) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "This profile changed since you loaded it. Reload and try again.");
        }
        if (request.tlsMode() != null) {
            requireTlsAllowedForEnvironment(request.tlsMode());
        }
        Map<String, Object> before = Map.of("name", profile.getName(), "host", profile.getHost(),
                "port", profile.getPort(), "tlsMode", profile.getTlsMode(), "status", profile.getStatus());
        boolean credentialsChanged = request.secret() != null && !request.secret().isBlank();
        applyFields(profile, request.name(), request.fromName(), request.fromEmail(), request.replyToEmail(),
                request.host(), request.port(), request.tlsMode(), request.username(),
                request.dailyLimit(), request.hourlyLimit());
        if (credentialsChanged) {
            requireEncryptionConfigured();
            profile.setSecretCiphertext(cipher.encrypt(request.secret()));
            profile.setSecretKeyVersion(profile.getSecretKeyVersion() + 1);
            // A profile whose credentials changed must be re-tested before it may send again.
            profile.setStatus("PENDING_TEST");
            profile.setLastTestDetail("Credentials rotated; connection test required.");
        }
        profiles.save(profile);
        audit.record(principal, "SMTP_PROFILE_UPDATED", "SMTP_PROFILE", profile.getId(), before,
                Map.of("name", profile.getName(), "host", profile.getHost(), "port", profile.getPort(),
                        "tlsMode", profile.getTlsMode(), "status", profile.getStatus(),
                        "credentialsRotated", credentialsChanged), Map.of());
        return toView(profile);
    }

    /**
     * Live connection test. When a recipient is supplied the test also sends a real
     * message, because many providers accept a login and then reject the MAIL FROM —
     * a profile must never be marked usable on the strength of an authentication
     * handshake alone.
     */
    @Transactional
    public SmtpDtos.TestConnectionResult test(CurrentPrincipal principal, UUID id,
                                              SmtpDtos.TestConnectionRequest request) {
        capabilities.require(principal, "smtp.manage");
        SmtpProfile profile = require(principal, id);
        Instant now = clock.instant();
        try {
            String detail;
            if (request != null && request.recipient() != null && !request.recipient().isBlank()) {
                sender.send(profile, request.recipient().trim(), null,
                        "Infeara Sales OS — SMTP delivery test",
                        "<p>This message confirms that the SMTP profile <strong>"
                                + profile.getName() + "</strong> can deliver mail.</p>",
                        "This message confirms that the SMTP profile " + profile.getName()
                                + " can deliver mail.",
                        null);
                detail = "Authentication and delivery to " + request.recipient().trim() + " succeeded.";
            } else {
                sender.verify(profile);
                detail = "Authentication and TLS handshake succeeded. No message was sent.";
            }
            profile.setStatus("ACTIVE");
            profile.setLastTestedAt(now);
            profile.setLastTestDetail(detail);
            profiles.save(profile);
            audit.record(principal, "SMTP_PROFILE_TESTED", "SMTP_PROFILE", profile.getId(),
                    Map.of("status", "PENDING_TEST"), Map.of("status", "ACTIVE", "detail", detail), Map.of());
            return new SmtpDtos.TestConnectionResult(true, detail, now, profile.getHost(), profile.getPort(),
                    profile.getTlsMode());
        } catch (ApiException ex) {
            profile.setStatus("ERROR");
            profile.setLastTestedAt(now);
            profile.setLastTestDetail(ex.getMessage());
            profiles.save(profile);
            audit.record(principal, "SMTP_PROFILE_TEST_FAILED", "SMTP_PROFILE", profile.getId(),
                    Map.of("status", profile.getStatus()), Map.of("error", ex.getMessage()), Map.of());
            throw ex;
        }
    }

    @Transactional
    public SmtpDtos.SmtpProfileView setStatus(CurrentPrincipal principal, UUID id, String status) {
        capabilities.require(principal, "smtp.manage");
        SmtpProfile profile = require(principal, id);
        if ("ACTIVE".equals(status) && profile.getLastTestedAt() == null) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "A profile must pass a connection test before it can be activated");
        }
        String before = profile.getStatus();
        profile.setStatus(status);
        profiles.save(profile);
        audit.record(principal, "SMTP_PROFILE_STATUS_CHANGED", "SMTP_PROFILE", profile.getId(),
                Map.of("status", before), Map.of("status", status), Map.of());
        return toView(profile);
    }

    /** Reserves one send against the profile's daily limit; call inside the send transaction. */
    @Transactional
    public SmtpProfile reserveSendSlot(String workspaceId, String profileId) {
        SmtpProfile profile = profiles.findByIdAndWorkspaceIdAndDeletedAtIsNull(profileId, workspaceId)
                .orElseThrow(() -> new ApiException(ErrorCode.SMTP_PROFILE_NOT_FOUND, "SMTP profile not found"));
        if (!profile.isUsable()) {
            throw new ApiException(ErrorCode.SMTP_PROFILE_NOT_ACTIVE,
                    "SMTP profile " + profile.getName() + " is not active");
        }
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(profile.getSentTodayDate())) {
            profile.setSentTodayDate(today);
            profile.setSentToday(0);
        }
        if (profile.getSentToday() >= profile.getDailyLimit()) {
            throw new ApiException(ErrorCode.SENDING_LIMIT_REACHED,
                    "The daily sending limit for " + profile.getName() + " has been reached");
        }
        profile.setSentToday(profile.getSentToday() + 1);
        return profiles.save(profile);
    }

    public SmtpProfile require(CurrentPrincipal principal, UUID id) {
        return profiles.findByIdAndWorkspaceIdAndDeletedAtIsNull(id.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.SMTP_PROFILE_NOT_FOUND, "SMTP profile not found"));
    }

    private void applyFields(SmtpProfile profile, String name, String fromName, String fromEmail,
                             String replyTo, String host, Integer port, String tlsMode, String username,
                             Integer dailyLimit, Integer hourlyLimit) {
        if (name != null && !name.isBlank()) {
            profile.setName(name.trim());
        }
        if (fromName != null && !fromName.isBlank()) {
            profile.setFromName(fromName.trim());
        }
        if (fromEmail != null && !fromEmail.isBlank()) {
            profile.setFromEmail(Normalizer.email(fromEmail));
        }
        if (replyTo != null) {
            profile.setReplyToEmail(replyTo.isBlank() ? null : Normalizer.email(replyTo));
        }
        if (host != null && !host.isBlank()) {
            profile.setHost(host.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (port != null) {
            profile.setPort(port);
        }
        if (tlsMode != null && !tlsMode.isBlank()) {
            if (!SmtpDtos.isValidTlsMode(tlsMode)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unsupported TLS mode");
            }
            profile.setTlsMode(tlsMode);
        }
        if (username != null && !username.isBlank()) {
            profile.setUsername(username.trim());
        }
        if (dailyLimit != null) {
            profile.setDailyLimit(dailyLimit);
        }
        if (hourlyLimit != null) {
            profile.setHourlyLimit(hourlyLimit);
        }
    }

    private void requireEncryptionConfigured() {
        if (!cipher.isConfigured()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "SMTP_SECRET_ENCRYPTION_KEY is not configured, so credentials cannot be stored securely");
        }
    }

    private void requireTlsAllowedForEnvironment(String tlsMode) {
        if ("NONE".equals(tlsMode) && isProduction()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "TLS mode NONE is not permitted in production");
        }
    }

    private boolean isProduction() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return "production".equalsIgnoreCase(appProperties.getEnvironment());
    }

    private SmtpDtos.SmtpProfileView toView(SmtpProfile profile) {
        return new SmtpDtos.SmtpProfileView(UUID.fromString(profile.getId()), profile.getName(),
                profile.getFromName(), profile.getFromEmail(), profile.getReplyToEmail(), profile.getHost(),
                profile.getPort(), profile.getTlsMode(), profile.getUsername(), profile.getDailyLimit(),
                profile.getHourlyLimit(), profile.getStatus(), profile.getLastTestedAt(),
                profile.getLastTestDetail(), profile.getSentToday(), profile.getRowVersion());
    }
}
