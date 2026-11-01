package com.infeara.sales.crm.dedupe;

import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.crm.account.Account;
import com.infeara.sales.crm.account.AccountRepository;
import com.infeara.sales.crm.contact.Contact;
import com.infeara.sales.crm.contact.ContactRepository;
import com.infeara.sales.crm.dto.LeadDtos;
import com.infeara.sales.crm.lead.Lead;
import com.infeara.sales.crm.lead.LeadRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Duplicate detection (BR-DUP-001..006). Normalisation happens before comparison;
 * exact email and verified phone are strong evidence, domain/name matches are
 * candidates only and fuzzy similarity is a review warning. Nothing is merged
 * automatically.
 */
@Service
public class DuplicateService {

    private static final double FUZZY_THRESHOLD = 0.86;

    private final LeadRepository leads;
    private final ContactRepository contacts;
    private final AccountRepository accounts;

    public DuplicateService(LeadRepository leads, ContactRepository contacts, AccountRepository accounts) {
        this.leads = leads;
        this.contacts = contacts;
        this.accounts = accounts;
    }

    public List<LeadDtos.DuplicateCandidate> candidates(String workspaceId, String emailRaw, String phoneRaw,
                                                        String domainRaw, String companyRaw) {
        String email = Normalizer.emailForComparison(emailRaw);
        String phone = Normalizer.phoneE164(phoneRaw, "91");
        String domain = domainRaw == null ? null : domainRaw.toLowerCase();
        String company = Normalizer.companyName(companyRaw);

        Map<String, LeadDtos.DuplicateCandidate> found = new LinkedHashMap<>();

        if (email != null) {
            for (Lead lead : leads.findTop10ByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNullOrderByCreatedAtDesc(
                    workspaceId, email)) {
                found.put("LEAD:" + lead.getId(), candidate("LEAD", lead.getId(), "EXACT_EMAIL", 0.99,
                        lead.getCompanyName(), "Same normalised email " + email));
            }
            for (Contact contact : contacts.findTop10ByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNull(workspaceId,
                    email)) {
                found.put("CONTACT:" + contact.getId(), candidate("CONTACT", contact.getId(), "EXACT_EMAIL", 0.95,
                        contact.fullName(), "Contact with the same email"));
            }
        }
        if (phone != null) {
            for (Lead lead : leads.findTop10ByWorkspaceIdAndPhoneE164AndDeletedAtIsNullOrderByCreatedAtDesc(
                    workspaceId, phone)) {
                found.put("LEAD:" + lead.getId(), candidate("LEAD", lead.getId(), "EXACT_PHONE", 0.9,
                        lead.getCompanyName(), "Same E.164 number, verify it is not a shared switchboard"));
            }
        }
        if (domain != null) {
            accounts.findByWorkspaceIdAndDomainNormalizedAndDeletedAtIsNull(workspaceId, domain).ifPresent(account ->
                    found.put("ACCOUNT:" + account.getId(), candidate("ACCOUNT", account.getId(),
                            "ACCOUNT_DOMAIN", 0.7, account.getName(), "Company domain already on file")));
            for (Lead lead : leads.findTop10ByWorkspaceIdAndDomainNormalizedAndDeletedAtIsNullOrderByCreatedAtDesc(
                    workspaceId, domain)) {
                found.put("LEAD:" + lead.getId(), candidate("LEAD", lead.getId(), "COMPANY_DOMAIN", 0.65,
                        lead.getCompanyName(), "Another lead uses the same company domain"));
            }
        }
        if (company != null) {
            for (Lead lead : leads.findTop10ByWorkspaceIdAndCompanyNameNormalizedAndDeletedAtIsNull(workspaceId,
                    company)) {
                found.put("LEAD:" + lead.getId(), candidate("LEAD", lead.getId(), "COMPANY_NAME_EXACT", 0.8,
                        lead.getCompanyName(), "Identical normalised company name"));
            }
            double best = 0;
            Lead bestLead = null;
            for (Lead lead : leads.findTop10ByWorkspaceIdAndDomainNormalizedAndDeletedAtIsNullOrderByCreatedAtDesc(
                    workspaceId, domain == null ? " " : domain)) {
                double similarity = Normalizer.similarity(company,
                        Normalizer.companyName(lead.getCompanyName()));
                if (similarity >= FUZZY_THRESHOLD && similarity > best) {
                    best = similarity;
                    bestLead = lead;
                }
            }
            if (bestLead != null) {
                found.putIfAbsent("LEAD:" + bestLead.getId(), candidate("LEAD", bestLead.getId(),
                        "REVIEW_WARNING", best, bestLead.getCompanyName(),
                        "Similar company name (review only, never auto-merged)"));
            }
        }
        List<LeadDtos.DuplicateCandidate> result = new ArrayList<>(found.values());
        result.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return result.stream().limit(10).toList();
    }

    public List<LeadDtos.DuplicateCandidate> forLead(String workspaceId, Lead lead) {
        return candidates(workspaceId, lead.getEmail(), lead.getPhoneE164(), lead.getDomainNormalized(),
                lead.getCompanyName()).stream()
                .filter(candidate -> !candidate.candidateId().toString().equals(lead.getId()))
                .toList();
    }

    public boolean hasStrongEmailDuplicate(String workspaceId, String emailRaw) {
        String email = Normalizer.emailForComparison(emailRaw);
        if (email == null) {
            return false;
        }
        return !leads.findTop10ByWorkspaceIdAndEmailNormalizedAndDeletedAtIsNullOrderByCreatedAtDesc(workspaceId,
                email).isEmpty();
    }

    private LeadDtos.DuplicateCandidate candidate(String type, String id, String matchType, double confidence,
                                                  String display, String detail) {
        return new LeadDtos.DuplicateCandidate(type, UUID.fromString(id), matchType,
                BigDecimal.valueOf(confidence).doubleValue(), display, detail);
    }

    /** Account-level candidate check used when creating an account. */
    public List<Account> accountsByDomain(String workspaceId, String domain) {
        return accounts.findByWorkspaceIdAndDomainNormalizedAndDeletedAtIsNull(workspaceId,
                domain == null ? "" : domain).stream().toList();
    }
}
