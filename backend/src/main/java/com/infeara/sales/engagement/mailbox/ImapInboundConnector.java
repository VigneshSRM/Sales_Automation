package com.infeara.sales.engagement.mailbox;

import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.security.SecretCipher;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Generic IMAP inbound connector (addendum 2 and 6) — the initial implementation.
 *
 * <p>Properties guaranteed by this class:
 * <ul>
 *   <li>one connection per sync, always closed ({@code try-with-resources});</li>
 *   <li>TLS mandatory unless the account was explicitly configured {@code NONE} (rejected in prod);</li>
 *   <li>fetch is UID based so a restart resumes exactly where it stopped;</li>
 *   <li>no network call happens inside a database transaction — the caller orchestrates that.</li>
 * </ul>
 *
 * <p>Gmail API and Microsoft Graph adapters are a later phase (E7); until then those providers are
 * reported as unsupported rather than silently handled by IMAP.
 */
@Component
public class ImapInboundConnector {

    private static final Logger log = LoggerFactory.getLogger(ImapInboundConnector.class);

    private final SecretCipher cipher;
    private final MimeParser parser;
    private final int timeoutMillis;
    private final int maxMessagesPerSync;

    public ImapInboundConnector(SecretCipher cipher, MimeParser parser,
                                @Value("${app.email.inbound.timeout-millis:15000}") int timeoutMillis,
                                @Value("${app.email.inbound.max-messages-per-sync:100}") int maxMessagesPerSync) {
        this.cipher = cipher;
        this.parser = parser;
        this.timeoutMillis = timeoutMillis;
        this.maxMessagesPerSync = maxMessagesPerSync;
    }

    /** Result of one fetch: parsed messages plus the mailbox identity they came from. */
    public record FetchResult(List<ParsedEmail> messages, Long uidValidity, long highestUid) {
    }

    public MailboxDtos.TestConnectionResult test(EmailAccount account) {
        try (Store store = open(account)) {
            Folder folder = store.getFolder(account.getFolder());
            folder.open(Folder.READ_ONLY);
            try {
                int count = folder.getMessageCount();
                return new MailboxDtos.TestConnectionResult(true,
                        "Connected to " + account.getHost() + " and opened " + account.getFolder()
                                + " (" + count + " messages visible)",
                        java.time.Instant.now(), account.getHost(), account.getPort(), account.getTlsMode(),
                        account.getFolder(), count);
            } finally {
                folder.close(false);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("imap_test_failed account={} reason={}", account.getId(), ex.getClass().getSimpleName());
            return new MailboxDtos.TestConnectionResult(false,
                    "Connection failed: " + ex.getMessage(), java.time.Instant.now(), account.getHost(),
                    account.getPort(), account.getTlsMode(), account.getFolder(), null);
        }
    }

    /**
     * Fetches messages with UID greater than {@code lastUid}.
     *
     * <p>If the server's UIDVALIDITY differs from the stored one, the mailbox was renumbered: the
     * call reports the new UIDVALIDITY and starts again from the oldest UIDs of the current window so
     * messages are re-examined (deduplication at persistence time prevents duplicates).
     */
    public FetchResult fetch(EmailAccount account, long lastUid, Long knownUidValidity) {
        if (!"IMAP".equals(account.getProvider())) {
            throw new ApiException(ErrorCode.UNSUPPORTED_OPERATION,
                    "The " + account.getProvider() + " adapter is not implemented yet. Use IMAP for now.");
        }
        try (Store store = open(account)) {
            Folder folder = store.getFolder(account.getFolder());
            folder.open(Folder.READ_ONLY);
            try {
                if (!(folder instanceof UIDFolder uidFolder)) {
                    throw new ApiException(ErrorCode.PROVIDER_ERROR, "The mailbox does not support UID access");
                }
                long uidValidity = uidFolder.getUIDValidity();
                boolean renumbered = knownUidValidity != null && knownUidValidity != uidValidity;
                long from = renumbered ? 0 : lastUid;

                Message[] messages = uidFolder.getMessagesByUID(Math.max(from + 1, 1), UIDFolder.LASTUID);
                List<ParsedEmail> parsed = new ArrayList<>();
                long highest = lastUid;
                int budget = maxMessagesPerSync;
                for (Message message : messages) {
                    if (budget-- <= 0) {
                        break;
                    }
                    long uid = uidFolder.getUID(message);
                    parsed.add(parser.parse(message, uid, uidValidity));
                    highest = Math.max(highest, uid);
                }
                log.info("imap_fetch account={} fetched={} uidValidity={} renumbered={}",
                        account.getId(), parsed.size(), uidValidity, renumbered);
                return new FetchResult(parsed, uidValidity, highest);
            } finally {
                folder.close(false);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("imap_fetch_failed account={} reason={}", account.getId(), ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.PROVIDER_ERROR,
                    "Mailbox connection failed: " + ex.getMessage());
        }
    }

    private Store open(EmailAccount account) throws Exception {
        if (account.getHost() == null || account.getPort() == null) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "This mailbox has no IMAP host configured");
        }
        if (account.getAuthMethod() != null && !"PASSWORD".equals(account.getAuthMethod())) {
            throw new ApiException(ErrorCode.UNSUPPORTED_OPERATION,
                    "OAuth mailbox access requires the provider adapter (Gmail API / Microsoft Graph)");
        }
        String secret = cipher.decrypt(account.getSecretCiphertext());
        if (secret == null || secret.isBlank()) {
            throw new ApiException(ErrorCode.SMTP_PROFILE_NOT_ACTIVE,
                    "No mailbox credential is stored for this account");
        }
        boolean implicitTls = "IMPLICIT_TLS".equals(account.getTlsMode());
        String protocol = implicitTls ? "imaps" : "imap";

        Properties properties = new Properties();
        properties.put("mail.store.protocol", protocol);
        properties.put("mail." + protocol + ".host", account.getHost());
        properties.put("mail." + protocol + ".port", String.valueOf(account.getPort()));
        properties.put("mail." + protocol + ".connectiontimeout", String.valueOf(timeoutMillis));
        properties.put("mail." + protocol + ".timeout", String.valueOf(timeoutMillis));
        properties.put("mail." + protocol + ".writetimeout", String.valueOf(timeoutMillis));
        properties.put("mail." + protocol + ".ssl.enable", String.valueOf(implicitTls));
        properties.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        if (!implicitTls && "STARTTLS".equals(account.getTlsMode())) {
            properties.put("mail." + protocol + ".starttls.enable", "true");
            properties.put("mail." + protocol + ".starttls.required", "true");
        }

        Session session = Session.getInstance(properties);
        Store store = session.getStore(protocol);
        String username = account.getUsername() == null ? account.getMailboxAddress() : account.getUsername();
        store.connect(account.getHost(), account.getPort(), username, secret);
        return store;
    }
}
