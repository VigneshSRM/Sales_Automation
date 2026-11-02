package com.infeara.sales.engagement.smtp;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.security.SecretCipher;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Thin, stateless SMTP transport. A fresh JavaMailSender is built per send from the
 * decrypted profile: nothing is cached, so a credential rotation or profile disable
 * takes effect on the very next message. Timeouts are bounded so a wedged MX cannot
 * hold a worker lease.
 */
@Component
public class SmtpSender {

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(20);

    private final SecretCipher secretCipher;

    public SmtpSender(SecretCipher secretCipher) {
        this.secretCipher = secretCipher;
    }

    public record DeliveryResult(String providerMessageId, Instant sentAt) {
    }

    public DeliveryResult send(SmtpProfile profile, String toEmail, String toName, String subject,
                               String htmlBody, String textBody, String replyTo) {
        if (!profile.isUsable()) {
            throw new ApiException(ErrorCode.SMTP_PROFILE_NOT_ACTIVE,
                    "The SMTP profile is not active. Test it and set it to ACTIVE before sending.");
        }
        JavaMailSenderImpl sender = build(profile);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(profile.getFromEmail(),
                    profile.getFromName() == null ? null : profile.getFromName());
            helper.setTo(toEmail);
            String effectiveReplyTo = replyTo != null ? replyTo : profile.getReplyToEmail();
            if (effectiveReplyTo != null && !effectiveReplyTo.isBlank()) {
                helper.setReplyTo(effectiveReplyTo);
            }
            helper.setSubject(subject);
            if (textBody != null && !textBody.isBlank()) {
                helper.setText(textBody, htmlBody);
            } else {
                helper.setText(htmlBody, true);
            }
            helper.setEncodeFilenames(true);
            sender.send(message);
            return new DeliveryResult(message.getMessageID(), Instant.now());
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new ApiException(ErrorCode.SMTP_TEST_FAILED,
                    "The message could not be assembled: " + ex.getMessage());
        } catch (MailException ex) {
            throw new ApiException(ErrorCode.PROVIDER_ERROR,
                    "SMTP delivery failed: " + rootMessage(ex));
        }
    }

    /** Verifies host, TLS mode and credentials by opening a transport and closing it. */
    public void verify(SmtpProfile profile) {
        JavaMailSenderImpl sender = build(profile);
        try {
            sender.testConnection();
        } catch (MessagingException ex) {
            throw new ApiException(ErrorCode.SMTP_TEST_FAILED,
                    "SMTP connection test failed: " + rootMessage(ex));
        }
    }

    private JavaMailSenderImpl build(SmtpProfile profile) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(profile.getHost());
        sender.setPort(profile.getPort());
        sender.setUsername(profile.getUsername());
        sender.setPassword(secretCipher.decrypt(profile.getSecretCiphertext()));
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        sender.setJavaMailProperties(properties(profile));
        return sender;
    }

    private Properties properties(SmtpProfile profile) {
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", (int) CONNECTION_TIMEOUT.toMillis());
        props.put("mail.smtp.timeout", (int) WRITE_TIMEOUT.toMillis());
        props.put("mail.smtp.writetimeout", (int) WRITE_TIMEOUT.toMillis());
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        switch (profile.getTlsMode()) {
            case "STARTTLS" -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            case "IMPLICIT_TLS" -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.starttls.enable", "false");
            }
            case "NONE" -> {
                // Permitted only outside production; the service layer blocks creating a
                // NONE profile when the `prod` profile is active (SEC-RULE: no plaintext auth).
                props.put("mail.smtp.starttls.enable", "false");
            }
            default -> throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Unsupported TLS mode: " + profile.getTlsMode());
        }
        return props;
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName() : message;
    }
}
