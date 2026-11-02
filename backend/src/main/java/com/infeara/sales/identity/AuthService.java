package com.infeara.sales.identity;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Hashing;
import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.config.AppProperties;
import com.infeara.sales.identity.dto.AuthDtos;
import com.infeara.sales.identity.dto.UserDtos;
import com.infeara.sales.security.SecretCipher;
import com.infeara.sales.security.SignedTokenService;
import com.infeara.sales.security.TotpService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication use cases: password login with lockout, MFA challenge and
 * enrolment, session issue/revocation and password change (FR-AUTH-001..006).
 * Every outcome is audited (FR-AUTH-008).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MFA_CHALLENGE_TTL_SECONDS = 300;

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totp;
    private final SignedTokenService tokens;
    private final SecretCipher cipher;
    private final AuditRecorder audit;
    private final AppProperties properties;
    private final Clock clock;

    public AuthService(UserRepository users, UserRoleRepository userRoles, SessionService sessions,
                       PasswordEncoder passwordEncoder, TotpService totp, SignedTokenService tokens,
                       SecretCipher cipher, AuditRecorder audit, AppProperties properties, Clock clock) {
        this.users = users;
        this.userRoles = userRoles;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.totp = totp;
        this.tokens = tokens;
        this.cipher = cipher;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request, String ip, String userAgent) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        List<User> candidates;
        if (request.workspaceSlug() != null && !request.workspaceSlug().isBlank()) {
            candidates = users.findByWorkspaceSlugAndEmail(request.workspaceSlug().trim(), email)
                    .map(List::of).orElse(List.of());
        } else {
            candidates = users.findByEmailNormalizedAndDeletedAtIsNull(email);
        }
        if (candidates.isEmpty()) {
            audit.security(null, null, "AUTH_LOGIN_FAILED", "UNKNOWN_ACCOUNT", ip);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
        if (candidates.size() > 1) {
            throw ApiException.field("workspaceSlug", "AMBIGUOUS_ACCOUNT",
                    "This email exists in more than one workspace; supply workspaceSlug");
        }
        User user = candidates.get(0);
        Instant now = clock.instant();

        if ("INACTIVE".equals(user.getStatus())) {
            audit.security(user.getWorkspaceId(), user.getId(), "AUTH_LOGIN_BLOCKED", "INACTIVE", ip);
            throw new ApiException(ErrorCode.ACCOUNT_INACTIVE, "This account is not active");
        }
        if (user.isLocked(now)) {
            audit.security(user.getWorkspaceId(), user.getId(), "AUTH_LOGIN_BLOCKED", "LOCKED", ip);
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED,
                    "Account temporarily locked after repeated failed attempts. Try again later.");
        }
        boolean passwordValid = user.getPasswordHash() != null
                && passwordEncoder.matches(request.password(), user.getPasswordHash());
        if (!passwordValid) {
            registerFailedAttempt(user, now, ip);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        List<String> capabilities = userRoles.findCapabilitiesByUserId(user.getId());
        boolean privileged = capabilities.contains("system.settings") || capabilities.contains("users.manage")
                || capabilities.contains("smtp.manage") || capabilities.contains("integrations.manage")
                || capabilities.contains("agents.configure") || capabilities.contains("workflows.publish")
                || capabilities.contains("reports.export");

        if (properties.getSecurity().getMfa().isEnabled() && (user.isMfaEnabled() || privileged)) {
            if (!user.isMfaEnabled()) {
                String enrolmentToken = tokens.issue("MFA_ENROL", user.getId(), MFA_CHALLENGE_TTL_SECONDS);
                audit.security(user.getWorkspaceId(), user.getId(), "AUTH_MFA_ENROLMENT_REQUIRED", "SUCCESS", ip);
                return new AuthDtos.LoginResponse(AuthDtos.LoginStatus.MFA_ENROLMENT_REQUIRED, null, null,
                        enrolmentToken, null,
                        "Multi-factor authentication enrolment is required for your role");
            }
            String challengeToken = tokens.issue("MFA_CHALLENGE", user.getId(), MFA_CHALLENGE_TTL_SECONDS);
            audit.security(user.getWorkspaceId(), user.getId(), "AUTH_MFA_CHALLENGE", "SUCCESS", ip);
            return new AuthDtos.LoginResponse(AuthDtos.LoginStatus.MFA_REQUIRED, null, challengeToken, null, null,
                    "Enter the 6-digit code from your authenticator app");
        }

        SessionService.IssuedSession session = sessions.create(user, ip, userAgent);
        audit.security(user.getWorkspaceId(), user.getId(), "AUTH_LOGIN_SUCCESS", "SUCCESS", ip);
        return new AuthDtos.LoginResponse(AuthDtos.LoginStatus.AUTHENTICATED, session.token(), null, null,
                profile(user), "Signed in");
    }

    @Transactional
    public AuthDtos.LoginResponse verifyMfa(AuthDtos.MfaVerifyRequest request, String ip, String userAgent) {
        String userId = tokens.verify("MFA_CHALLENGE", request.challengeToken());
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Unknown authentication challenge"));
        String secret = cipher.decrypt(user.getMfaSecretCiphertext());
        if (secret == null || !totp.verify(secret, request.code(), user.getMfaLastUsedStep())) {
            user.setFailedLoginCount(user.getFailedLoginCount() + 1);
            users.save(user);
            audit.security(user.getWorkspaceId(), user.getId(), "AUTH_MFA_FAILED", "INVALID_CODE", ip);
            throw new ApiException(ErrorCode.MFA_INVALID_CODE, "That code is invalid or has already been used");
        }
        user.setMfaLastUsedStep(totp.currentStep());
        SessionService.IssuedSession session = sessions.create(user, ip, userAgent);
        audit.security(user.getWorkspaceId(), user.getId(), "AUTH_LOGIN_SUCCESS", "MFA", ip);
        return new AuthDtos.LoginResponse(AuthDtos.LoginStatus.AUTHENTICATED, session.token(), null, null,
                profile(user), "Signed in");
    }

    @Transactional
    public MfaEnrolment startMfaEnrolment(String enrolmentToken) {
        String userId = tokens.verify("MFA_ENROL", enrolmentToken);
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Unknown enrolment token"));
        String secret = totp.generateSecret();
        user.setMfaSecretCiphertext(cipher.encrypt(secret));
        user.setMfaSecretKeyId(cipher.activeKeyId());
        user.setMfaEnabled(false);
        users.save(user);
        return new MfaEnrolment(totp.provisioningUri(secret, user.getEmail()), secret);
    }

    @Transactional
    public AuthDtos.LoginResponse confirmMfaEnrolment(AuthDtos.MfaEnrollConfirmRequest request, String ip,
                                                      String userAgent) {
        String userId = tokens.verify("MFA_ENROL", request.enrolmentToken());
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Unknown enrolment token"));
        String secret = cipher.decrypt(user.getMfaSecretCiphertext());
        if (secret == null || !totp.verify(secret, request.code(), null)) {
            throw new ApiException(ErrorCode.MFA_INVALID_CODE, "The enrolment code did not verify");
        }
        user.setMfaEnabled(true);
        user.setMfaEnrolledAt(clock.instant());
        user.setMfaLastUsedStep(totp.currentStep());
        SessionService.IssuedSession session = sessions.create(user, ip, userAgent);
        audit.security(user.getWorkspaceId(), user.getId(), "AUTH_MFA_ENROLLED", "SUCCESS", ip);
        return new AuthDtos.LoginResponse(AuthDtos.LoginStatus.AUTHENTICATED, session.token(), null, null,
                profile(user), "Multi-factor authentication is now active");
    }

    @Transactional
    public void logout(String rawToken, String userId) {
        sessions.revoke(rawToken);
        if (userId != null) {
            audit.security(null, userId, "AUTH_LOGOUT", "SUCCESS", null);
        }
    }

    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword) {
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }
        PasswordPolicy.validate(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        users.save(user);
        sessions.revokeAllForUser(user.getId());
    }

    /** Profile for a user id, or {@code null} when the session user no longer exists. */
    @Transactional(readOnly = true)
    public UserDtos.Profile profileFor(String userId) {
        return users.findById(userId).map(this::profile).orElse(null);
    }

    /** The session user, or {@code UNAUTHENTICATED} when the row is gone. */
    @Transactional(readOnly = true)
    public User requireUser(String userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Session user no longer exists"));
    }

    public UserDtos.Profile profile(User user) {
        List<String> capabilities = userRoles.findCapabilitiesByUserId(user.getId());
        List<String> roles = userRoles.findRoleCodesByUserId(user.getId());
        return new UserDtos.Profile(com.infeara.sales.common.util.Ids.parse(user.getId()), user.getDisplayName(),
                user.getEmail(), user.getStatus(), user.getScopeType(), user.getTimezone(), user.isMfaEnabled(),
                roles, capabilities, user.getRowVersion());
    }

    private void registerFailedAttempt(User user, Instant now, String ip) {
        int failures = user.getFailedLoginCount() + 1;
        user.setFailedLoginCount(failures);
        if (failures >= properties.getSecurity().getMaxFailedLogins()) {
            user.setLockedUntil(now.plusSeconds(properties.getSecurity().getLockoutMinutes() * 60L));
            user.setFailedLoginCount(0);
        }
        users.save(user);
        audit.security(user.getWorkspaceId(), user.getId(), "AUTH_LOGIN_FAILED", "INVALID_PASSWORD", ip);
        log.info("login_failed user={} attempts={}", user.getId(), failures);
    }

    public record MfaEnrolment(String provisioningUri, String secret) {
    }
}
