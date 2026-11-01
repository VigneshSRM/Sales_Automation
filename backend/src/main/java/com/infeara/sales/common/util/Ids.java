package com.infeara.sales.common.util;

import java.util.UUID;

/**
 * Identifier helpers. Identifiers are UUIDs (v4) exposed as UUID in the API and
 * persisted as CHAR(36) (spec §11: "use UUIDs … choose one representation and
 * standardize"), which keeps MariaDB comparisons index-friendly and avoids a
 * binary/char mismatch between Hibernate validation and the Flyway schema.
 */
public final class Ids {

    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String of(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    public static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new com.infeara.sales.common.error.ApiException(
                    com.infeara.sales.common.error.ErrorCode.MALFORMED_REQUEST, "Invalid identifier format");
        }
    }

    public static boolean isValid(String raw) {
        try {
            UUID.fromString(raw);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
