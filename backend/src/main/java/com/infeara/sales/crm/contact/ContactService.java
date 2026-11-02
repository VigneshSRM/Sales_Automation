package com.infeara.sales.crm.contact;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.paging.PageQuery;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.crm.account.Account;
import com.infeara.sales.crm.account.AccountRepository;
import com.infeara.sales.crm.dto.PartyDtos;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contact management (FR-CON-001..003). A contact is never silently reassigned to a
 * different account when the email/domain match is ambiguous (FR-CON-002): the caller
 * must pick the account explicitly, and consent state is recorded with its source.
 */
@Service
public class ContactService {

    private final ContactRepository contacts;
    private final AccountRepository accounts;
    private final CapabilityEvaluator capabilities;
    private final AuditRecorder audit;
    private final Clock clock;

    public ContactService(ContactRepository contacts, AccountRepository accounts,
                          CapabilityEvaluator capabilities, AuditRecorder audit, Clock clock) {
        this.contacts = contacts;
        this.accounts = accounts;
        this.capabilities = capabilities;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PartyDtos.ContactSummary> list(CurrentPrincipal principal, PageQuery query) {
        capabilities.require(principal, "contacts.read");
        Specification<Contact> specification = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspaceId"), principal.workspaceId()));
            predicates.add(cb.isNull(root.get("deletedAt")));
            String accountId = query.filters().get("accountId");
            if (accountId != null && !accountId.isBlank()) {
                predicates.add(cb.equal(root.get("accountId"), accountId));
            }
            String term = query.filters().get("q");
            if (term != null && !term.isBlank()) {
                String like = "%" + term.trim().toLowerCase() + "%";
                predicates.add(cb.or(cb.like(builderLower(cb, root.get("emailNormalized")), like),
                        cb.like(builderLower(cb, root.get("fullNameNormalized")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Sort sort = Sort.by(query.descending() ? Sort.Direction.DESC : Sort.Direction.ASC,
                query.resolveSort(Map.of("updatedAt", "updatedAt", "firstName", "firstName"), "updatedAt"));
        Page<Contact> page = contacts.findAll(specification, PageRequest.of(query.page(), query.size(), sort));
        List<PartyDtos.ContactSummary> items = page.getContent().stream().map(this::toSummary).toList();
        return PageResponse.of(items, query.page(), query.size(), page.getTotalElements(),
                "updatedAt:desc", query.appliedFilters());
    }

    @Transactional(readOnly = true)
    public PartyDtos.ContactDetail get(CurrentPrincipal principal, UUID contactId) {
        capabilities.require(principal, "contacts.read");
        Contact contact = requireContact(principal, contactId);
        return new PartyDtos.ContactDetail(Ids.parse(contact.getId()), contact.getFirstName(),
                contact.getLastName(), contact.getTitle(), contact.getEmail(), contact.getPhone(),
                contact.getPreferredChannel(), contact.getTimezone(), contact.getDecisionRole(),
                contact.getConsentStatus(), contact.getSuppressionStatus(),
                contact.getAccountId() == null ? null : Ids.parse(contact.getAccountId()),
                accountName(contact.getAccountId()), contact.getCreatedAt(), contact.getUpdatedAt(),
                contact.getRowVersion());
    }

    @Transactional
    public PartyDtos.ContactDetail create(CurrentPrincipal principal, PartyDtos.CreateContactRequest request) {
        capabilities.require(principal, "contacts.create");
        String email = Normalizer.email(request.email());
        if (request.email() != null && !request.email().isBlank() && !Normalizer.isValidEmail(request.email())) {
            throw ApiException.field("email", "INVALID_EMAIL", "A valid email address is required");
        }
        String accountId = null;
        if (request.accountId() != null) {
            Account account = accounts.findByIdAndWorkspaceIdAndDeletedAtIsNull(request.accountId().toString(),
                            principal.workspaceId())
                    .orElseThrow(() -> ApiException.field("accountId", "UNKNOWN_ACCOUNT",
                            "Account not found in this workspace"));
            accountId = account.getId();
        } else if (email != null) {
            // Ambiguous domain matches are surfaced, never auto-linked (FR-CON-002).
            String domain = Normalizer.emailDomain(email);
            if (domain != null && accounts.findByWorkspaceIdAndDomainNormalizedAndDeletedAtIsNull(
                    principal.workspaceId(), domain).isPresent()) {
                throw ApiException.field("accountId", "AMBIGUOUS_ACCOUNT_MATCH",
                        "An account already exists for this email domain. Select the account explicitly.");
            }
        }
        Contact contact = new Contact();
        contact.setId(Ids.newId());
        contact.setWorkspaceId(principal.workspaceId());
        contact.setAccountId(accountId);
        contact.setFirstName(request.firstName().trim());
        contact.setLastName(request.lastName() == null ? null : request.lastName().trim());
        contact.setFullNameNormalized(Normalizer.personName(request.firstName(), request.lastName()));
        contact.setTitle(request.title());
        contact.setEmail(email);
        contact.setEmailNormalized(Normalizer.emailForComparison(request.email()));
        contact.setPhone(request.phone());
        contact.setPhoneE164(Normalizer.phoneE164(request.phone(), "91"));
        contact.setPreferredChannel(request.preferredChannel() == null ? "EMAIL" : request.preferredChannel());
        contact.setTimezone(request.timezone());
        contact.setDecisionRole(request.decisionRole() == null ? "UNKNOWN" : request.decisionRole());
        contact.setConsentStatus(request.consentStatus() == null ? "UNKNOWN" : request.consentStatus());
        contact.setOwnerUserId(principal.userId());
        contact.setCreatedBy(principal.userId());
        contacts.save(contact);
        audit.record(principal, "CONTACT_CREATED", "CONTACT", contact.getId(), null,
                Map.of("email", email == null ? "" : email, "accountId", accountId == null ? "" : accountId,
                        "consentStatus", contact.getConsentStatus()),
                Map.of("consentReference", request.consentReference() == null ? "" : request.consentReference(),
                        "consentSource", request.consentSource() == null ? "" : request.consentSource()));
        return get(principal, Ids.parse(contact.getId()));
    }

    @Transactional
    public PartyDtos.ContactDetail update(CurrentPrincipal principal, UUID contactId,
                                          PartyDtos.PatchContactRequest request) {
        capabilities.require(principal, "contacts.update");
        Contact contact = requireContact(principal, contactId);
        if (request.rowVersion() != null && request.rowVersion() != contact.getRowVersion()) {
            throw new ApiException(ErrorCode.STALE_VERSION, "This contact was modified by someone else");
        }
        if (request.firstName() != null) {
            contact.setFirstName(request.firstName().trim());
        }
        if (request.lastName() != null) {
            contact.setLastName(request.lastName().trim());
        }
        contact.setFullNameNormalized(Normalizer.personName(contact.getFirstName(), contact.getLastName()));
        if (request.title() != null) {
            contact.setTitle(request.title());
        }
        if (request.email() != null) {
            if (!Normalizer.isValidEmail(request.email())) {
                throw ApiException.field("email", "INVALID_EMAIL", "A valid email address is required");
            }
            contact.setEmail(Normalizer.email(request.email()));
            contact.setEmailNormalized(Normalizer.emailForComparison(request.email()));
        }
        if (request.phone() != null) {
            contact.setPhone(request.phone());
            contact.setPhoneE164(Normalizer.phoneE164(request.phone(), "91"));
        }
        if (request.preferredChannel() != null) {
            contact.setPreferredChannel(request.preferredChannel());
        }
        if (request.timezone() != null) {
            contact.setTimezone(request.timezone());
        }
        if (request.decisionRole() != null) {
            contact.setDecisionRole(request.decisionRole());
        }
        if (request.consentStatus() != null) {
            contact.setConsentStatus(request.consentStatus());
        }
        contact.setUpdatedBy(principal.userId());
        contacts.save(contact);
        audit.record(principal, "CONTACT_UPDATED", "CONTACT", contact.getId(), null,
                Map.of("consentStatus", contact.getConsentStatus()), Map.of());
        return get(principal, contactId);
    }

    private Contact requireContact(CurrentPrincipal principal, UUID contactId) {
        return contacts.findByIdAndWorkspaceIdAndDeletedAtIsNull(contactId.toString(), principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.CONTACT_NOT_FOUND, "Contact not found"));
    }

    private String accountName(String accountId) {
        return accountId == null ? null : accounts.findById(accountId).map(Account::getName).orElse(null);
    }

    private PartyDtos.ContactSummary toSummary(Contact contact) {
        return new PartyDtos.ContactSummary(Ids.parse(contact.getId()), contact.fullName(), contact.getTitle(),
                contact.getEmail(), contact.getPhone(), contact.getDecisionRole(), contact.getConsentStatus(),
                contact.getSuppressionStatus(),
                contact.getAccountId() == null ? null : Ids.parse(contact.getAccountId()),
                accountName(contact.getAccountId()), contact.getUpdatedAt(), contact.getRowVersion());
    }

    private jakarta.persistence.criteria.Expression<String> builderLower(
            jakarta.persistence.criteria.CriteriaBuilder cb, jakarta.persistence.criteria.Expression<?> path) {
        return cb.lower(path.as(String.class));
    }

    public Clock clock() {
        return clock;
    }
}
