package com.infeara.sales.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-SEC-001: the password policy is enforced server-side on every credential write. */
class PasswordPolicyTest {

    @Test
    @DisplayName("accepts a 12+ character password with three character classes")
    void acceptsStrongPassword() {
        assertThatCode(() -> PasswordPolicy.validate("Infeara!2026x")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1!", "alllowercaseletters", "1234567890123", "             "})
    @DisplayName("rejects weak passwords with PASSWORD_POLICY_VIOLATION")
    void rejectsWeakPasswords(String candidate) {
        assertThatThrownBy(() -> PasswordPolicy.validate(candidate))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).errorCode())
                .isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
    }

    @Test
    @DisplayName("rejects null and over-long passwords")
    void rejectsNullAndOverlong() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("Aa1!" + "x".repeat(250)))
                .isInstanceOf(ApiException.class);
    }
}
