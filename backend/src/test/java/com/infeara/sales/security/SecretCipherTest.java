package com.infeara.sales.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.config.AppProperties;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BR-SEC-004: integration secrets are encrypted at rest with AES-256-GCM, never stored plain. */
class SecretCipherTest {

    private SecretCipher cipher;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (index * 7 + 1);
        }
        properties.getSecurity().setEncryptionKey(Base64.getEncoder().encodeToString(key));
        properties.getSecurity().setEncryptionKeyId("k1");
        cipher = new SecretCipher(properties);
    }

    @Test
    @DisplayName("round-trips a secret and never leaks the plaintext into the ciphertext")
    void roundTrip() {
        String plaintext = "smtp-app-password-!42";
        String stored = cipher.encrypt(plaintext);

        assertThat(stored).isNotBlank().doesNotContain(plaintext).startsWith("k1:");
        assertThat(cipher.decrypt(stored)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("a different IV is used for every encryption of the same value")
    void usesFreshIv() {
        assertThat(cipher.encrypt("same-value")).isNotEqualTo(cipher.encrypt("same-value"));
    }

    @Test
    @DisplayName("tampered ciphertext is rejected instead of returning garbage")
    void detectsTampering() {
        String stored = cipher.encrypt("secret");
        String tampered = stored.substring(0, stored.length() - 4) + "AAAA";

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("encryption is refused when no key is configured")
    void refusesWithoutKey() {
        SecretCipher unconfigured = new SecretCipher(new AppProperties());
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.encrypt("secret")).isInstanceOf(ApiException.class);
    }
}
