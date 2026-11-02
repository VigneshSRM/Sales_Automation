package com.infeara.sales.analytics;

import com.infeara.sales.crm.account.Account;
import com.infeara.sales.crm.account.AccountRepository;
import com.infeara.sales.crm.contact.Contact;
import com.infeara.sales.crm.contact.ContactRepository;
import com.infeara.sales.crm.lead.Lead;
import com.infeara.sales.crm.lead.LeadRepository;
import com.infeara.sales.sales.pipeline.Opportunity;
import com.infeara.sales.sales.pipeline.OpportunityRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permission-filtered global search (spec §4, FR-SEARCH-001).
 *
 * Each record type requires its own read capability, so a user never learns that a
 * record exists in a module they cannot open. Results are capped per type to keep the
 * header search fast; the paginated list pages remain the way to browse.
 */
@Service
public class GlobalSearchService {

    private static final int PER_TYPE_LIMIT = 5;

    private final LeadRepository leads;
    private final AccountRepository accounts;
    private final ContactRepository contacts;
    private final OpportunityRepository opportunities;
    private final CapabilityEvaluator capabilities;

    public GlobalSearchService(LeadRepository leads, AccountRepository accounts, ContactRepository contacts,
                               OpportunityRepository opportunities, CapabilityEvaluator capabilities) {
        this.leads = leads;
        this.accounts = accounts;
        this.contacts = contacts;
        this.opportunities = opportunities;
        this.capabilities = capabilities;
    }

    public record SearchHit(String type, String id, String title, String subtitle, String link) {
    }

    @Transactional(readOnly = true)
    public List<SearchHit> search(CurrentPrincipal principal, String term) {
        if (term == null || term.trim().length() < 2) {
            return List.of();
        }
        String query = term.trim();
        List<SearchHit> hits = new ArrayList<>();

        if (capabilities.has(principal, "leads.read")) {
            for (Lead lead : leads.search(principal.workspaceId(), query,
                    PageRequest.of(0, PER_TYPE_LIMIT))) {
                hits.add(new SearchHit("LEAD", lead.getId(), lead.fullName(),
                        lead.getCompanyName() + " · " + lead.getStatus().name(),
                        "/leads/detail?id=" + lead.getId()));
            }
        }
        if (capabilities.has(principal, "accounts.read")) {
            for (Account account : accounts
                    .findTop5ByWorkspaceIdAndDeletedAtIsNullAndNameContainingIgnoreCase(
                            principal.workspaceId(), query)) {
                hits.add(new SearchHit("ACCOUNT", account.getId(), account.getName(),
                        account.getDomainNormalized() == null ? "Account" : account.getDomainNormalized(),
                        "/accounts/detail?id=" + account.getId()));
            }
        }
        if (capabilities.has(principal, "contacts.read")) {
            for (Contact contact : contacts
                    .findTop5ByWorkspaceIdAndDeletedAtIsNullAndFullNameNormalizedContaining(
                            principal.workspaceId(), query.toLowerCase(java.util.Locale.ROOT))) {
                hits.add(new SearchHit("CONTACT", contact.getId(), contact.fullName(),
                        contact.getEmail() == null ? "Contact" : contact.getEmail(),
                        "/contacts/detail?id=" + contact.getId()));
            }
        }
        if (capabilities.has(principal, "opportunities.read")) {
            for (Opportunity opportunity : opportunities
                    .findTop5ByWorkspaceIdAndDeletedAtIsNullAndNameContainingIgnoreCase(
                            principal.workspaceId(), query)) {
                hits.add(new SearchHit("OPPORTUNITY", opportunity.getId(), opportunity.getName(),
                        opportunity.getCurrency() + " " + opportunity.getAmount().toPlainString(),
                        "/opportunities/detail?id=" + opportunity.getId()));
            }
        }
        return hits;
    }
}
