package com.infeara.sales.security;

import com.infeara.sales.common.util.Hashing;
import com.infeara.sales.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * RFC 6238 TOTP for MFA (FR-AUTH-005). Codes are 6 digits, 30-second steps, SHA-1
 * (compatible with standard authenticator apps). Replay of the previously accepted
 * step is rejected via {@code users.mfa_last_used_step}.
 */
@Service
public class TotpService {

    private static final int DIGITS = 6;
    private static final int STEP_SECONDS = 30;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final Clock clock;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public TotpService(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public String generateSecret() {
        byte[] buffer = new byte[20];
        random.nextBytes(buffer);
        return base32Encode(buffer);
    }

    public String provisioningUri(String secret, String accountEmail) {
        String issuer = properties.getSecurity().getMfa().getIssuer();
        String label = urlEncode(issuer) + ":" + urlEncode(accountEmail);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    public long currentStep() {
        return clock.instant().getEpochSecond() / STEP_SECONDS;
    }

    public boolean verify(String secret, String code, Long lastUsedStep) {
        if (secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long step = currentStep();
        int window = Math.max(0, properties.getSecurity().getMfa().getWindowSteps());
        for (long candidate = step - window; candidate <= step + window; candidate++) {
            if (lastUsedStep != null && candidate <= lastUsedStep) {
                continue;
            }
            if (Hashing.constantTimeEquals(generate(secret, candidate), code)) {
                return true;
            }
        }
        return false;
    }

    public String generate(String secret, long step) {
        try {
            byte[] key = base32Decode(secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception ex) {
            throw new IllegalStateException("TOTP generation failed", ex);
        }
    }

    public Instant now() {
        return clock.instant();
    }

    private static String base32Encode(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            result.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return result.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String normalized = encoded.replace("=", "").replace(" ", "").toUpperCase();
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[normalized.length() * 5 / 8];
        int index = 0;
        for (char c : normalized.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid base32 character");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
