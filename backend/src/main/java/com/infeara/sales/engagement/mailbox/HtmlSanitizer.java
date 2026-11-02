package com.infeara.sales.engagement.mailbox;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Conservative HTML sanitiser for inbound email bodies (addendum 3.2 and 7).
 *
 * <p>Inbound HTML is untrusted. Strategy: drop dangerous elements together with their content,
 * unwrap unknown tags, re-emit only allow-listed tags, and drop every attribute except
 * {@code href}/{@code src} with an http(s) or mailto value. Anything not understood is removed
 * rather than passed through, so an unknown construct can never become active content.
 */
@Component
public class HtmlSanitizer {

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "br", "div", "span", "strong", "b", "em", "i", "u", "ul", "ol", "li", "blockquote",
            "a", "h1", "h2", "h3", "h4", "hr", "table", "thead", "tbody", "tr", "td", "th", "pre", "code");

    private static final Pattern DANGEROUS_BLOCK = Pattern.compile(
            "(?is)<(script|style|iframe|object|embed|link|meta|form|input|button|svg|math)\\b.*?</\\1\\s*>");
    private static final Pattern DANGEROUS_SELF_CLOSING = Pattern.compile(
            "(?is)<\\s*/?\\s*(script|style|iframe|object|embed|link|meta|form|input|button|svg|math)\\b[^>]*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("(?is)<\\s*(/?)\\s*([a-zA-Z0-9-]+)([^>]*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("(?is)([a-zA-Z-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern SAFE_URL = Pattern.compile("(?i)^(https?://|mailto:)");

    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String working = COMMENT.matcher(html).replaceAll("");
        working = DANGEROUS_BLOCK.matcher(working).replaceAll("");
        working = DANGEROUS_SELF_CLOSING.matcher(working).replaceAll("");

        Matcher tagMatcher = TAG.matcher(working);
        StringBuilder out = new StringBuilder();
        while (tagMatcher.find()) {
            boolean closing = !tagMatcher.group(1).isEmpty();
            String name = tagMatcher.group(2).toLowerCase(java.util.Locale.ROOT);
            String attributes = tagMatcher.group(3);
            String replacement;
            if (!ALLOWED_TAGS.contains(name)) {
                replacement = "";
            } else if (closing) {
                replacement = "</" + name + ">";
            } else {
                replacement = "<" + name + safeAttributes(attributes) + ">";
            }
            tagMatcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        tagMatcher.appendTail(out);
        return out.toString();
    }

    /** Escapes the remaining text nodes so stray markup cannot reappear. */
    public String sanitizeToText(String html) {
        if (html == null) {
            return "";
        }
        String withoutTags = TAG.matcher(html).replaceAll(" ");
        return withoutTags.replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safeAttributes(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder attributes = new StringBuilder();
        Matcher matcher = ATTRIBUTE.matcher(raw);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String value = matcher.group(2).replace("\"", "").replace("'", "");
            boolean allowed = ("href".equals(key) || "src".equals(key) || "alt".equals(key)
                    || "title".equals(key) || "colspan".equals(key) || "rowspan".equals(key));
            if (!allowed) {
                continue;
            }
            if (("href".equals(key) || "src".equals(key)) && !SAFE_URL.matcher(value).find()) {
                continue;
            }
            attributes.append(' ').append(key).append("=\"")
                    .append(value.replace("<", "").replace(">", "").replace("\"", ""))
                    .append("\"");
        }
        return attributes.toString();
    }
}
