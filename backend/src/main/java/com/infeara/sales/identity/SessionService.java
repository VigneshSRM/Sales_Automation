package com.infeara.sales.identity;

import com.infeara.sales.common.util.Hashing;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.config.AppProperties;
import com.infeara.sales.security.CurrentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side session management (ADR-005). Only a SHA-256 hash of the cookie token
 * is stored. Idle expiry is tracked with a per-instance throttle so that a normal
 * page load does not write to the database on every request.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final long TOUCH_INTERVAL_SECONDS = 60;

    private final UserSessionRepository sessions;
    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final AppProperties properties;
    private final Clock clock;

    /** sessionId -> epoch second of last persisted touch (per instance). */
    private final Map<String, Long> touched = new ConcurrentHashMap<>();

    public SessionService(UserSessionRepository sessions, UserRepository users, UserRoleRepository userRoles,
                          AppProperties properties, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.userRoles = userRoles;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedSession create(User user, String ipAddress, String userAgent) {
        String rawToken = Hashing.randomToken(32);
        Instant now = clock.instant();
        UserSession session = new UserSession();
        session.setId(Ids.newId());
        session.setWorkspaceId(user.getWorkspaceId());
        session.setUserId(user.getId());
        session.setTokenHash(Hashing.sha256Hex(rawToken));
        session.setIpAddress(truncate(ipAddress, 64));
        session.setUserAgent(truncate(userAgent, 400));
        session.setCreatedAt(now);
        session.setLastSeenAt(now);
        session.setExpiresAt(now.plusSeconds(properties.getSecurity().getSessionTtlMinutes() * 60L));
        sessions.save(session);
        user.setLastLoginAt(now);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        users.save(user);
        return new IssuedSession(rawToken, session.getId(), session.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public CurrentPrincipal resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        UserSession session = sessions.findByTokenHash(Hashing.sha256Hex(rawToken)).orElse(null);
        if (session == null) {
            return null;
        }
        Instant now = clock.instant();
        if (!session.isUsable(now)) {
            return null;
        }
        long idleLimit = properties.getSecurity().getSessionIdleMinutes() * 60L;
        if (session.getLastSeenAt().plusSeconds(idleLimit).isBefore(now)) {
            log.debug("session_idle_expired session={}", session.getId());
            return null;
        }
        User user = users.findById(session.getUserId()).orElse(null);
        if (user == null || !user.isActive()) {
            return null;
        }
        List<String> capabilities = userRoles.findCapabilitiesByUserId(user.getId());
        touch(session, now);
        return new CurrentPrincipal(user.getId(), user.getWorkspaceId(), user.getEmail(), user.getDisplayName(),
                user.getScopeType(), user.getTimezone(), user.isMfaEnabled(), session.getId(),
                Set.copyOf(capabilities));
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null) {
            return;
        }
        sessions.findByTokenHash(Hashing.sha256Hex(rawToken)).ifPresent(session -> {
            session.setRevokedAt(clock.instant());
            sessions.save(session);
            touched.remove(session.getId());
        });
    }

    @Transactional
    public void revokeAllForUser(String userId) {
        sessions.deleteByUserId(userId);
        touched.remove(userId);
    }

    public Instant expiryFor(String rawToken) {
        return sessions.findByTokenHash(Hashing.sha256Hex(rawToken)).map(UserSession::getExpiresAt).orElse(null);
    }

    private void touch(UserSession session, Instant now) {
        Long last = touched.get(session.getId());
        if (last != null && now.getEpochSecond() - last < TOUCH_INTERVAL_SECONDS) {
            return;
        }
        touched.put(session.getId(), now.getEpochSecond());
        session.setLastSeenAt(now);
        sessions.save(session);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record IssuedSession(String token, String sessionId, Instant expiresAt) {
    }
}
