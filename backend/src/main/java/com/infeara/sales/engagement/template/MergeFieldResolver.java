package com.infeara.sales.engagement.template;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Renders templates from an allow-listed set of merge fields (BR-EMAIL-004).
 *
 * Only the fields below may appear in a template. Anything else fails the render
 * instead of silently producing an empty string, which is how personalisation bugs
 * reach customers ("Hi , ..."). Values are HTML-escaped: contact names and website
 * copy are untrusted input, and a template must never become an injection vector.
 */
@Component
public class MergeFieldResolver {

    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "lead.first_name", "lead.last_name", "lead.company_name", "lead.requirement",
            "lead.location", "lead.timeline",
            "contact.full_name", "contact.title",
            "account.name",
            "rep.display_name", "rep.email",
            "workspace.name", "workspace.website",
            "meeting.link", "unsubscribe_url", "privacy_url",
            "opportunity.name", "opportunity.stage");

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    public Set<String> fieldsUsed(String template) {
        Set<String> used = new java.util.LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(template == null ? "" : template);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        return used;
    }

    /** Validates a template at save time so broken templates are rejected up front. */
    public void validate(String subject, String body) {
        Set<String> unknown = new java.util.LinkedHashSet<>(fieldsUsed(subject));
        unknown.addAll(fieldsUsed(body));
        unknown.removeAll(ALLOWED_FIELDS);
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.TEMPLATE_RENDER_FAILED,
                    "Unknown merge field(s): " + String.join(", ", unknown)
                            + ". Allowed fields: " + String.join(", ", ALLOWED_FIELDS));
        }
    }

    public String render(String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        Set<String> used = fieldsUsed(template);
        for (String field : used) {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new ApiException(ErrorCode.TEMPLATE_RENDER_FAILED, "Unknown merge field: " + field);
            }
            if (!values.containsKey(field)) {
                throw new ApiException(ErrorCode.TEMPLATE_RENDER_FAILED,
                        "Missing value for merge field: " + field);
            }
        }
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(escape(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static Map<String, String> values(Object... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put(String.valueOf(pairs[index]), pairs[index + 1] == null ? "" : String.valueOf(pairs[index + 1]));
        }
        return map;
    }
}
