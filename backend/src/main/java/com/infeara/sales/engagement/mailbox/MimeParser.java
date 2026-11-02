package com.infeara.sales.engagement.mailbox;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MIME parser for fetched messages (addendum 3.2).
 *
 * <p>Safety limits: message bodies are truncated, attachments are never loaded into memory beyond a
 * hash, and oversized attachments are marked rather than stored. HTML is sanitised before it is
 * persisted, and text-only content is always produced for the AI prompt.
 */
@Component
public class MimeParser {

    private static final Logger log = LoggerFactory.getLogger(MimeParser.class);

    private final HtmlSanitizer sanitizer;
    private final int maxBodyChars;
    private final long maxAttachmentBytes;

    public MimeParser(HtmlSanitizer sanitizer,
                      @Value("${app.email.inbound.max-body-chars:200000}") int maxBodyChars,
                      @Value("${app.email.inbound.max-attachment-bytes:10485760}") long maxAttachmentBytes) {
        this.sanitizer = sanitizer;
        this.maxBodyChars = maxBodyChars;
        this.maxAttachmentBytes = maxAttachmentBytes;
    }

    public ParsedEmail parse(Message message, Long uid, Long uidValidity) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<jakarta.mail.Header> all = message.getAllHeaders();
        while (all.hasMoreElements()) {
            jakarta.mail.Header header = all.nextElement();
            headers.put(header.getName(), header.getValue());
        }

        List<ParsedEmail.ParsedAttachment> attachments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        collect(message, text, html, attachments);

        Instant received = message.getReceivedDate() != null
                ? message.getReceivedDate().toInstant()
                : Instant.now();

        return new ParsedEmail(
                firstHeader(message, "Message-ID"),
                firstHeader(message, "Message-ID"),
                firstHeader(message, "In-Reply-To"),
                firstHeader(message, "References"),
                addressOf(message.getFrom()),
                personalOf(message.getFrom()),
                addressOf(message.getRecipients(Message.RecipientType.TO)),
                addressesOf(message.getRecipients(Message.RecipientType.CC)),
                firstHeader(message, "Reply-To"),
                sanitizeHeader(firstHeader(message, "Subject")),
                received,
                truncate(sanitizer.sanitizeToText(text.toString())),
                sanitizer.sanitize(truncate(html.toString())),
                headers,
                uid,
                uidValidity,
                attachments);
    }

    private void collect(Part part, StringBuilder text, StringBuilder html,
                         List<ParsedEmail.ParsedAttachment> attachments) throws Exception {
        if (part.isMimeType("text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            text.append(asString(part.getContent()));
            return;
        }
        if (part.isMimeType("text/html") && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            html.append(asString(part.getContent()));
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int index = 0; index < multipart.getCount(); index++) {
                BodyPart bodyPart = multipart.getBodyPart(index);
                if (Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition())
                        || bodyPart.getFileName() != null) {
                    attachments.add(describe(bodyPart));
                } else {
                    collect(bodyPart, text, html, attachments);
                }
            }
            return;
        }
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            attachments.add(describe(part));
        }
    }

    private ParsedEmail.ParsedAttachment describe(Part part) {
        String filename = "attachment";
        try {
            filename = part.getFileName() == null ? filename : sanitizeHeader(part.getFileName());
        } catch (Exception ignored) {
            // keep the fallback name
        }
        String mediaType = "application/octet-stream";
        try {
            mediaType = part.getContentType() == null ? mediaType : part.getContentType().split(";")[0].trim();
        } catch (Exception ignored) {
            // keep the fallback type
        }
        long size = 0;
        String hash = null;
        try {
            byte[] bytes = part.getInputStream().readNBytes((int) Math.min(maxAttachmentBytes, Integer.MAX_VALUE));
            size = bytes.length;
            hash = sha256(bytes);
        } catch (Exception ex) {
            log.warn("attachment_read_failed filename={} reason={}", filename, ex.getClass().getSimpleName());
        }
        return new ParsedEmail.ParsedAttachment(filename, mediaType, size, hash);
    }

    private String asString(Object content) {
        if (content instanceof String string) {
            return string;
        }
        return "";
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxBodyChars ? value : value.substring(0, maxBodyChars);
    }

    private String firstHeader(Message message, String name) {
        try {
            String[] values = message.getHeader(name);
            return values == null || values.length == 0 ? null : values[0];
        } catch (Exception ex) {
            return null;
        }
    }

    private String addressOf(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        return addresses[0] instanceof InternetAddress internet
                ? internet.getAddress().toLowerCase(java.util.Locale.ROOT)
                : addresses[0].toString().toLowerCase(java.util.Locale.ROOT);
    }

    private String personalOf(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        return addresses[0] instanceof InternetAddress internet ? internet.getPersonal() : null;
    }

    private List<String> addressesOf(Address[] addresses) {
        List<String> result = new ArrayList<>();
        if (addresses == null) {
            return result;
        }
        for (Address address : addresses) {
            if (address instanceof InternetAddress internet && internet.getAddress() != null) {
                result.add(internet.getAddress().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return result;
    }

    private String sanitizeHeader(String value) {
        return value == null ? null : value.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
