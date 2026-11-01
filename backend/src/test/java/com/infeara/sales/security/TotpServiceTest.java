package com.infeara.sales.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.infeara.sales.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-AUTH-002: TOTP enrolment and verification, with replay protection. */
class TotpServiceTest {

    private static final Instant FIXED = Instant.parse("2026-11-01T09:30:00Z");

    private TotpService totp;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().getMfa().setWindowSteps(1);
        totp = new TotpService(Clock.fixed(FIXED, ZoneOffset.UTC), properties);
    }

    @Test
    @DisplayName("generates a Base32 secret of usable length")
    void generatesSecret() {
        String secret = totp.generateSecret();
        assertThat(secret).hasSizeGreaterThanOrEqualTo(16).doesNotContain("=");
        assertThat(totp.generateSecret()).isNotEqualTo(secret);
    }

    @Test
    @DisplayName("accepts the current code and rejects a stale one")
    void verifiesCurrentCode() {
        String secret = totp.generateSecret();
        String code = totp.generate(secret, totp.currentStep());

        assertThat(totp.verify(secret, code, null)).isTrue();
        assertThat(totp.verify(secret, code, totp.currentStep())).isFalse(); // replay rejected
        assertThat(totp.verify(secret, "000000", null)).isFalse();
        assertThat(totp.verify(secret, "12345", null)).isFalse();           // wrong length
    }

    @Test
    @DisplayName("tolerates one step of clock drift, not more")
    void toleratesOneStepDrift() {
        String secret = totp.generateSecret();
        String previousStepCode = totp.generate(secret, totp.currentStep() - 1);
        String twoStepsBack = totp.generate(secret, totp.currentStep() - 2);

        assertThat(totp.verify(secret, previousStepCode, null)).isTrue();
        assertThat(totp.verify(secret, twoStepsBack, null)).isFalse();
    }

    @Test
    @DisplayName("builds an otpauth provisioning URI for authenticator apps")
    void buildsProvisioningUri() {
        String uri = totp.provisioningUri("JBSWY3DPEHPK3PXP", "admin@infeara.example");
        assertThat(uri).startsWith("otpauth://totp/").contains("secret=JBSWY3DPEHPK3PXP");
    }
}
