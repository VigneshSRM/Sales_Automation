package com.infeara.sales.security;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM envelope encryption for provider credentials (SMTP passwords, AI API
 * keys, Calendly tokens, webhook signing keys) — FR-SMTP-002, BR-SEC-001.
 *
 * <p>Key material is supplied from outside the database ({@code SMTP_SECRET_ENCRYPTION_KEY}
 * environment/OS keystore). Ciphertext format: {@code <keyId>:<base64(iv||ciphertext+tag)>}.
 * Previous keys may be supplied during rotation so old rows stay readable; re-encryption
 * is performed by {@code SecretRotationService} (operator runbook in the deployment guide).
 * Plaintext secrets are never logged and never returned by any API.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKeySpec> keys = new HashMap<>();
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(AppProperties properties) {
        AppProperties.Security security = properties.getSecurity();
        if (security.getEncryptionKey() != null && !security.getEncryptionKey().isBlank()) {
            keys.put(security.getEncryptionKeyId(), keyFrom(security.getEncryptionKey()));
        }
        if (security.getPreviousEncryptionKeys() != null && !security.getPreviousEncryptionKeys().isBlank()) {
            for (String entry : security.getPreviousEncryptionKeys().split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length == 2) {
                    keys.put(parts[0].trim(), keyFrom(parts[1].trim()));
                }
            }
        }
        this.activeKeyId = security.getEncryptionKeyId();
        if (keys.isEmpty()) {
            log.warn("secret_cipher_unconfigured: set SMTP_SECRET_ENCRYPTION_KEY before storing credentials");
        }
    }

    public boolean isConfigured() {
        return keys.containsKey(activeKeyId);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        SecretKeySpec key = keys.get(activeKeyId);
        if (key == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Credential encryption is not configured on this instance");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return activeKeyId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt secret", ex);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        int separator = stored.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("Malformed ciphertext");
        }
        String keyId = stored.substring(0, separator);
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Encryption key '" + keyId + "' is not available on this instance");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(separator + 1));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Unable to decrypt stored credential");
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    /** Constant, non-reversible display mask for credential UI (never the secret). */
    public static String mask(String lastKnownHint) {
        return lastKnownHint == null || lastKnownHint.isBlank() ? "••••••••" : "••••" + lastKnownHint;
    }

    private SecretKeySpec keyFrom(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalStateException("Encryption key must be 32 bytes (256-bit) base64-encoded");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
