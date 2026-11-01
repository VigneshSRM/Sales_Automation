package com.infeara.sales.engagement.mailbox;

import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.crm.contact.Contact;
import com.infeara.sales.crm.contact.ContactRepository;
import com.infeara.sales.crm.lead.Lead;
import com.infeara.sales.crm.lead.LeadRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Threading and CRM association (addendum 3.2 step 5).
 *
 * <p>Thread identity prefers the RFC headers ({@code References} then {@code In-Reply-To} then
 * {@code Message-ID}); subject + mailbox is only the fallback. CRM matching requires an unambiguous
 * hit: several candidate leads yields no association, which routes the conversation to a human
 * instead of attaching it to the wrong customer.
 */
@Component
public class ConversationMatcher {

    private static final Pattern REPLY_PREFIX = Pattern.compile(
            "(?i)^\\s*((re|fw|fwd|aw|sv)\\s*(\\[\\d+\\])?\\s*:\\s*)+");

    private final ContactRepository contacts;
    private final LeadRepository leads;

    public ConversationMatcher(ContactRepository contacts, LeadRepository leads) {
        this.contacts = contacts;
        this.leads = leads;
    }

    public String normalizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        String stripped = REPLY_PREFIX.matcher(subject).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Stable thread key: the oldest RFC id in the chain, else mailbox + normalized subject. */
    public String threadKey(ParsedEmail email, String mailboxAddress) {
        String references = email.references();
        if (references != null && !references.isBlank()) {
            String[] ids = references.trim().split("\\s+");
            if (ids.length > 0 && !ids[0].isBlank()) {
                return "rfc:" + truncate(ids[0], 280);
            }
        }
        if (email.inReplyTo() != null && !email.inReplyTo().isBlank()) {
            return "rfc:" + truncate(email.inReplyTo(), 280);
        }
        if (email.rfcMessageId() != null && !email.rfcMessageId().isBlank()) {
            return "rfc:" + truncate(email.rfcMessageId(), 280);
        }
        return "subject:" + Normalizer.emailForComparison(mailboxAddress) + ":"
                + truncate(normalizeSubject(email.subject()), 200);
    }

    public Match match(String workspaceId, String fromEmail, String subject) {
        String normalized = Normalizer.email(fromEmail);
        if (normalized == null) {
            return Match.unassigned(subject);
        }
        List<Contact> candidates = contacts
                .findTop10ByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(workspaceId, normalized);
        if (candidates.size() == 1) {
            Contact contact = candidates.get(0);
            Optional<Lead> lead = leads.findByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(workspaceId,
                    normalized);
            return new Match(lead.map(Lead::getId).orElse(null), contact.getId(),
                    contact.getAccountId(), null, "MATCHED_CONTACT");
        }
        if (candidates.size() > 1) {
            return Match.unassigned("AMBIGUOUS_CONTACT");
        }
        Optional<Lead> lead = leads.findByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(workspaceId,
                normalized);
        return lead.map(found -> new Match(found.getId(), found.getContactId(), found.getAccountId(), null,
                        "MATCHED_LEAD"))
                .orElseGet(() -> Match.unassigned("NO_MATCH"));
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** CRM association result; {@code reason} explains an unassigned outcome for the inbox UI. */
    public record Match(String leadId, String contactId, String crmAccountId, String opportunityId,
                        String reason) {

        static Match unassigned(String reason) {
            return new Match(null, null, null, null, reason);
        }

        public boolean assigned() {
            return leadId != null || contactId != null;
        }
    }
}
