package com.infeara.sales.identity;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;

/**
 * Password policy: at least 12 characters and at least three of {lowercase,
 * uppercase, digit, symbol}. Documented in SECURITY-PLAN.md; the policy is enforced
 * server-side on every credential write.
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < 12 || password.length() > 200) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "Password must be between 12 and 200 characters");
        }
        int classes = 0;
        if (password.matches(".*[a-z].*")) {
            classes++;
        }
        if (password.matches(".*[A-Z].*")) {
            classes++;
        }
        if (password.matches(".*\\d.*")) {
            classes++;
        }
        if (password.matches(".*[^A-Za-z0-9].*")) {
            classes++;
        }
        if (classes < 3) {
            throw new ApiException(ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "Password must contain at least three of: lowercase, uppercase, digit, symbol");
        }
    }
}
