package com.infeara.sales.security;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.util.Hashing;
import com.infeara.sales.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Short-lived, tamper-evident tokens used for the MFA challenge step and MFA
 * enrolment. Tokens are signed with an HMAC key derived from the configured secret
 * material; they carry purpose, subject and expiry and are single-use by design
 * (the subject's state changes on use).
 */
@Service
public class SignedTokenService {

    private static final Logger log = LoggerFactory.getLogger(SignedTokenService.class);
    private static final String SEPARATOR = "|";

    private final Clock clock;
    private final byte[] key;

    public SignedTokenService(Clock clock, AppProperties properties) {
        this.clock = clock;
        String configured = properties.getSecurity().getEncryptionKey();
        if (configured == null || configured.isBlank()) {
            this.key = new byte[32];
            new SecureRandom().nextBytes(this.key);
            log.warn("signed_tokens_use_ephemeral_key: set SMTP_SECRET_ENCRYPTION_KEY for stable MFA tokens");
        } else {
            this.key = Base64.getDecoder().decode(configured);
        }
    }

    public String issue(String purpose, String subject, int ttlSeconds) {
        long expiry = clock.instant().plusSeconds(ttlSeconds).getEpochSecond();
        String payload = purpose + SEPARATOR + subject + SEPARATOR + expiry;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    public String verify(String purpose, String token) {
        if (token == null || !token.contains(".")) {
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Invalid or missing authentication challenge");
        }
        String[] parts = token.split("\\.", 2);
        if (!Hashing.constantTimeEquals(sign(parts[0]), parts[1])) {
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Invalid authentication challenge");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] fields = payload.split("\\" + SEPARATOR);
        if (fields.length != 3 || !fields[0].equals(purpose)) {
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Invalid authentication challenge");
        }
        long expiry = Long.parseLong(fields[2]);
        if (Instant.ofEpochSecond(expiry).isBefore(clock.instant())) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED, "The authentication challenge has expired");
        }
        return fields[1];
    }

    private String sign(String value) {
        return Hashing.hmacSha256Hex(Base64.getEncoder().encodeToString(key), value);
    }
}
