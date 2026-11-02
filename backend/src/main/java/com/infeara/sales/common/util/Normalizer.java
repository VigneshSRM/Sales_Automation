package com.infeara.sales.common.util;

import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical normalisation used by duplicate detection (BR-DUP-001/002) and by
 * unique constraints. Normalisation rules live here so that the same rule is
 * applied when writing and when searching.
 */
public final class Normalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_DIGIT = Pattern.compile("[^0-9+]");
    private static final Pattern PLUS_TAG = Pattern.compile("\\+[^@]*@");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
            "\\b(pvt|private|ltd|limited|llp|inc|incorporated|corp|corporation|llc|gmbh|plc|co|company|technologies|technology|solutions|services)\\b");

    private Normalizer() {
    }

    /** Trims, lowercases and strips control characters; returns null for blank input. */
    public static String email(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = java.text.Normalizer.normalize(raw.trim(), Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Cntrl}\\s]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Email used for duplicate comparison: lowercased and plus-tag stripped at the
     * domain boundary per policy, keeping the local part otherwise intact.
     */
    public static String emailForComparison(String raw) {
        String normalized = email(raw);
        if (normalized == null) {
            return null;
        }
        return PLUS_TAG.matcher(normalized).replaceFirst("@");
    }

    public static boolean isValidEmail(String raw) {
        String normalized = email(raw);
        return normalized != null && EMAIL.matcher(normalized).matches() && normalized.length() <= 254;
    }

    /** Extracts the lowercased domain of an email address, or null. */
    public static String emailDomain(String raw) {
        String normalized = email(raw);
        if (normalized == null) {
            return null;
        }
        int at = normalized.lastIndexOf('@');
        return at < 0 ? null : normalized.substring(at + 1);
    }

    /**
     * Best-effort E.164 normalisation. Numbers that cannot be normalised with the
     * supplied default country code are returned as null: they are never treated as
     * strong duplicate evidence (BR-DUP-002).
     */
    public static String phoneE164(String raw, String defaultCountryDialCode) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = NON_DIGIT.matcher(raw).replaceAll("");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.length() >= 8 && digits.length() <= 15) {
            if (!digits.startsWith("+") && digits.length() == 10 && defaultCountryDialCode != null) {
                return "+" + defaultCountryDialCode + digits;
            }
            return "+" + digits;
        }
        return null;
    }

    /** Company name used for fuzzy/likelihood comparison. */
    public static String companyName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = java.text.Normalizer.normalize(raw.trim(), Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        normalized = COMPANY_SUFFIX.matcher(normalized).replaceAll(" ");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }

    /** Domain extracted from a website URL; null when the URL is not http(s). */
    public static String websiteDomain(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Human name used for display-name comparisons only (never sole matching evidence). */
    public static String personName(String first, String last) {
        String combined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return java.text.Normalizer.normalize(combined, Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Jaro-Winkler similarity in [0,1] used for review-only warnings (BR-DUP-003). */
    public static double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        if (a.equals(b)) {
            return 1d;
        }
        int[] matchWindow = {Math.max(a.length(), b.length()) / 2 - 1};
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int start = Math.max(0, i - matchWindow[0]);
            int end = Math.min(i + matchWindow[0] + 1, b.length());
            for (int j = start; j < end; j++) {
                if (!bMatched[j] && a.charAt(i) == b.charAt(j)) {
                    aMatched[i] = true;
                    bMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0d;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (aMatched[i]) {
                while (!bMatched[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(a.length(), b.length())); i++) {
            if (a.charAt(i) == b.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
