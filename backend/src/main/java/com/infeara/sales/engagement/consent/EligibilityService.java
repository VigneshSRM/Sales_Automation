package com.infeara.sales.engagement.consent;

import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.crm.contact.Contact;
import com.infeara.sales.crm.contact.ContactRepository;
import com.infeara.sales.crm.lead.LeadRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single source of truth for "may we email this address?" (BR-EMAIL-005).
 *
 * The check is re-evaluated immediately before every send — never trusted from the
 * time the message was composed, because consent can be withdrawn, a hard bounce can
 * arrive, or a lead can be disqualified between composition and delivery.
 */
@Service
public class EligibilityService {

    private final SuppressionEntryRepository suppression;
    private final ConsentRecordRepository consentRecords;
    private final ContactRepository contacts;
    private final LeadRepository leads;

    public EligibilityService(SuppressionEntryRepository suppression, ConsentRecordRepository consentRecords,
                              ContactRepository contacts, LeadRepository leads) {
        this.suppression = suppression;
        this.consentRecords = consentRecords;
        this.contacts = contacts;
        this.leads = leads;
    }

    public record Eligibility(boolean allowed, List<String> blockers, List<String> warnings,
                              String consentStatus, String suppressionReason) {
    }

    @Transactional(readOnly = true)
    public Eligibility evaluate(String workspaceId, String rawEmail) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (rawEmail == null || Normalizer.emailForComparison(rawEmail).isBlank()) {
            return new Eligibility(false, List.of("No recipient address"), warnings, "UNKNOWN", null);
        }
        String email = Normalizer.emailForComparison(rawEmail);

        String suppressionReason = suppression.findByWorkspaceIdAndEmailNormalized(workspaceId, email)
                .map(SuppressionEntry::getReason).orElse(null);
        if (suppressionReason != null) {
            blockers.add("Address is on the suppression list (" + suppressionReason + ")");
        }

        String consentStatus = consentRecords
                .findFirstByWorkspaceIdAndEmailNormalizedOrderByCapturedAtDesc(workspaceId, email)
                .map(ConsentRecord::getConsentStatus)
                .orElse("UNKNOWN");

        List<Contact> matchingContacts = contacts
                .findTop10ByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(workspaceId, email);
        for (Contact contact : matchingContacts) {
            if ("WITHDRAWN".equals(contact.getConsentStatus())) {
                consentStatus = "WITHDRAWN";
            }
            if (!"CLEAR".equals(contact.getSuppressionStatus())) {
                blockers.add("Contact " + contact.fullName() + " is suppressed ("
                        + contact.getSuppressionStatus() + ")");
            }
        }
        if ("WITHDRAWN".equals(consentStatus) || "EXPIRED".equals(consentStatus)) {
            blockers.add("Consent status is " + consentStatus);
        }
        if ("UNKNOWN".equals(consentStatus)) {
            warnings.add("No consent record on file — confirm a lawful basis before sending cold outreach");
        }

        leads.findByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(workspaceId, email).ifPresent(lead -> {
            if ("DISQUALIFIED".equals(lead.getStatus().name())) {
                blockers.add("Lead " + lead.fullName() + " is disqualified");
            }
            if ("NURTURE".equals(lead.getStatus().name())) {
                warnings.add("Lead is in nurture; confirm the sequence is appropriate");
            }
        });

        return new Eligibility(blockers.isEmpty(), List.copyOf(blockers), List.copyOf(warnings),
                consentStatus, suppressionReason);
    }

    @Transactional
    public SuppressionEntry suppress(String workspaceId, String rawEmail, String reason, String source,
                                     String note, String actorUserId) {
        String email = Normalizer.emailForComparison(rawEmail);
        SuppressionEntry entry = suppression.findByWorkspaceIdAndEmailNormalized(workspaceId, email)
                .orElseGet(SuppressionEntry::new);
        if (entry.getId() == null) {
            entry.setId(com.infeara.sales.common.util.Ids.newId());
            entry.setWorkspaceId(workspaceId);
            entry.setEmailNormalized(email);
            entry.setCreatedBy(actorUserId);
        }
        entry.setReason(reason);
        entry.setSource(source);
        entry.setNote(note);
        return suppression.save(entry);
    }

    @Transactional
    public void unsuppress(String workspaceId, String rawEmail) {
        String email = Normalizer.emailForComparison(rawEmail);
        suppression.findByWorkspaceIdAndEmailNormalized(workspaceId, email)
                .ifPresent(suppression::delete);
    }

    @Transactional(readOnly = true)
    public boolean isSuppressed(String workspaceId, String rawEmail) {
        return suppression.existsByWorkspaceIdAndEmailNormalized(workspaceId,
                Normalizer.emailForComparison(rawEmail));
    }
}
