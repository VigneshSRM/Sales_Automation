package com.infeara.sales.crm.account;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.api.PageResponse;
import com.infeara.sales.common.error.ApiException;
import com.infeara.sales.common.error.ErrorCode;
import com.infeara.sales.common.paging.PageQuery;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.crm.catalog.Industry;
import com.infeara.sales.crm.catalog.IndustryRepository;
import com.infeara.sales.crm.catalog.LeadSource;
import com.infeara.sales.crm.catalog.LeadSourceRepository;
import com.infeara.sales.crm.contact.Contact;
import com.infeara.sales.crm.contact.ContactRepository;
import com.infeara.sales.crm.dto.PartyDtos;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.sales.pipeline.OpportunityRepository;
import com.infeara.sales.security.CapabilityEvaluator;
import com.infeara.sales.security.CurrentPrincipal;
import com.infeara.sales.security.ScopeGuard;
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

/** Account management (FR-ACC-001..003) with domain-based duplicate protection. */
@Service
public class AccountService {

    private static final Map<String, String> SORTABLE = Map.of("name", "name", "updatedAt", "updatedAt",
            "relationshipStatus", "relationshipStatus");

    private final AccountRepository accounts;
    private final ContactRepository contacts;
    private final OpportunityRepository opportunities;
    private final IndustryRepository industries;
    private final LeadSourceRepository sources;
    private final UserRepository users;
    private final CapabilityEvaluator capabilities;
    private final ScopeGuard scopeGuard;
    private final AuditRecorder audit;
    private final Clock clock;

    public AccountService(AccountRepository accounts, ContactRepository contacts,
                          OpportunityRepository opportunities, IndustryRepository industries,
                          LeadSourceRepository sources, UserRepository users, CapabilityEvaluator capabilities,
                          ScopeGuard scopeGuard, AuditRecorder audit, Clock clock) {
        this.accounts = accounts;
        this.contacts = contacts;
        this.opportunities = opportunities;
        this.industries = industries;
        this.sources = sources;
        this.users = users;
        this.capabilities = capabilities;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PartyDtos.AccountSummary> list(CurrentPrincipal principal, PageQuery query) {
        capabilities.require(principal, "accounts.read");
        Specification<Account> specification = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspaceId"), principal.workspaceId()));
            predicates.add(cb.isNull(root.get("deletedAt")));
            String term = query.filters().get("q");
            if (term != null && !term.isBlank()) {
                String like = "%" + term.trim().toLowerCase() + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("domainNormalized"), "")), like)));
            }
            String status = query.filters().get("relationshipStatus");
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("relationshipStatus"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Sort sort = Sort.by(query.descending() ? Sort.Direction.DESC : Sort.Direction.ASC,
                query.resolveSort(SORTABLE, "updatedAt"));
        Page<Account> page = accounts.findAll(specification,
                PageRequest.of(query.page(), query.size(), sort));
        List<PartyDtos.AccountSummary> items = page.getContent().stream().map(this::toSummary).toList();
        return PageResponse.of(items, query.page(), query.size(), page.getTotalElements(),
                (query.sortBy() == null ? "updatedAt" : query.sortBy()) + ":" + (query.descending() ? "desc" : "asc"),
                query.appliedFilters());
    }

    @Transactional(readOnly = true)
    public PartyDtos.AccountDetail get(CurrentPrincipal principal, UUID accountId) {
        capabilities.require(principal, "accounts.read");
        Account account = requireAccount(principal, accountId);
        List<PartyDtos.ContactSummary> contactViews = contacts
                .findByAccountIdAndDeletedAtIsNullOrderByFirstNameAsc(account.getId()).stream()
                .map(this::toContactSummary)
                .toList();
        return new PartyDtos.AccountDetail(Ids.parse(account.getId()), account.getName(), account.getLegalName(),
                account.getWebsite(), account.getDomainNormalized(),
                industryCode(account.getIndustryId()), account.getSizeBand(), account.getHeadquarters(),
                account.getDescription(), account.getRelationshipStatus(),
                account.getOwnerUserId() == null ? null : Ids.parse(account.getOwnerUserId()),
                ownerName(account.getOwnerUserId()), contactViews, account.getCreatedAt(), account.getUpdatedAt(),
                account.getRowVersion());
    }

    @Transactional
    public PartyDtos.AccountDetail create(CurrentPrincipal principal, PartyDtos.CreateAccountRequest request) {
        capabilities.require(principal, "accounts.create");
        String domain = Normalizer.websiteDomain(request.website());
        if (domain != null) {
            accounts.findByWorkspaceIdAndDomainNormalizedAndDeletedAtIsNull(principal.workspaceId(), domain)
                    .ifPresent(existing -> {
                        throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                                "An account with this domain already exists: " + existing.getName());
                    });
        }
        Account account = new Account();
        account.setId(Ids.newId());
        account.setWorkspaceId(principal.workspaceId());
        account.setName(request.name().trim());
        account.setNameNormalized(Normalizer.companyName(request.name()));
        account.setLegalName(trimOrNull(request.legalName()));
        account.setWebsite(trimOrNull(request.website()));
        account.setDomainNormalized(domain);
        account.setIndustryId(resolveIndustry(principal.workspaceId(), request.industryCode()));
        account.setSizeBand(trimOrNull(request.sizeBand()));
        account.setHeadquarters(trimOrNull(request.headquarters()));
        account.setDescription(trimOrNull(request.description()));
        account.setRelationshipStatus(request.relationshipStatus() == null ? "PROSPECT"
                : request.relationshipStatus());
        account.setOwnerUserId(resolveOwner(principal, request.ownerUserId()));
        account.setSourceId(resolveSource(principal.workspaceId(), request.sourceCode()));
        account.setCreatedBy(principal.userId());
        accounts.save(account);
        audit.record(principal, "ACCOUNT_CREATED", "ACCOUNT", account.getId(), null,
                Map.of("name", account.getName(), "domain", domain == null ? "" : domain), Map.of());
        return get(principal, Ids.parse(account.getId()));
    }

    @Transactional
    public PartyDtos.AccountDetail update(CurrentPrincipal principal, UUID accountId,
                                          PartyDtos.PatchAccountRequest request) {
        capabilities.require(principal, "accounts.update");
        Account account = requireAccount(principal, accountId);
        if (request.rowVersion() != null && request.rowVersion() != account.getRowVersion()) {
            throw new ApiException(ErrorCode.STALE_VERSION, "This account was modified by someone else");
        }
        Map<String, Object> before = Map.of("name", account.getName(),
                "relationshipStatus", account.getRelationshipStatus());
        if (request.name() != null) {
            account.setName(request.name().trim());
            account.setNameNormalized(Normalizer.companyName(request.name()));
        }
        if (request.website() != null) {
            account.setWebsite(trimOrNull(request.website()));
            account.setDomainNormalized(Normalizer.websiteDomain(request.website()));
        }
        if (request.industryCode() != null) {
            account.setIndustryId(resolveIndustry(principal.workspaceId(), request.industryCode()));
        }
        if (request.headquarters() != null) {
            account.setHeadquarters(trimOrNull(request.headquarters()));
        }
        if (request.description() != null) {
            account.setDescription(trimOrNull(request.description()));
        }
        if (request.relationshipStatus() != null) {
            account.setRelationshipStatus(request.relationshipStatus());
        }
        if (request.ownerUserId() != null) {
            account.setOwnerUserId(resolveOwner(principal, request.ownerUserId()));
        }
        account.setUpdatedBy(principal.userId());
        accounts.save(account);
        audit.record(principal, "ACCOUNT_UPDATED", "ACCOUNT", account.getId(), before,
                Map.of("name", account.getName(), "relationshipStatus", account.getRelationshipStatus()),
                Map.of());
        return get(principal, accountId);
    }

    private Account requireAccount(CurrentPrincipal principal, UUID accountId) {
        Account account = accounts.findByIdAndWorkspaceIdAndDeletedAtIsNull(accountId.toString(),
                        principal.workspaceId())
                .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
        scopeGuard.requireAccess(principal, account.getOwnerUserId(), "account");
        return account;
    }

    private PartyDtos.AccountSummary toSummary(Account account) {
        long openOpps = opportunities.countByAccountIdAndCloseDateIsNullAndDeletedAtIsNull(account.getId());
        return new PartyDtos.AccountSummary(Ids.parse(account.getId()), account.getName(),
                account.getDomainNormalized(), industryCode(account.getIndustryId()), account.getSizeBand(),
                account.getRelationshipStatus(), ownerName(account.getOwnerUserId()),
                (int) contacts.countByAccountIdAndDeletedAtIsNull(account.getId()),
                (int) openOpps, account.getUpdatedAt(), account.getRowVersion());
    }

    private PartyDtos.ContactSummary toContactSummary(Contact contact) {
        return new PartyDtos.ContactSummary(Ids.parse(contact.getId()), contact.fullName(), contact.getTitle(),
                contact.getEmail(), contact.getPhone(), contact.getDecisionRole(), contact.getConsentStatus(),
                contact.getSuppressionStatus(),
                contact.getAccountId() == null ? null : Ids.parse(contact.getAccountId()),
                contact.getAccountId() == null ? null
                        : accounts.findById(contact.getAccountId()).map(Account::getName).orElse(null),
                contact.getUpdatedAt(), contact.getRowVersion());
    }

    private String ownerName(String userId) {
        return userId == null ? null : users.findById(userId).map(User::getDisplayName).orElse(null);
    }

    private String industryCode(String industryId) {
        return industryId == null ? null
                : industries.findById(industryId).map(Industry::getCode).orElse(null);
    }

    private String resolveIndustry(String workspaceId, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return industries.findByWorkspaceIdAndCode(workspaceId, code)
                .orElseThrow(() -> ApiException.field("industryCode", "UNKNOWN_INDUSTRY",
                        "Unknown industry code")).getId();
    }

    private String resolveOwner(CurrentPrincipal principal, UUID ownerUserId) {
        if (ownerUserId == null) {
            return null;
        }
        return users.findByIdAndWorkspaceIdAndDeletedAtIsNull(ownerUserId.toString(), principal.workspaceId())
                .orElseThrow(() -> ApiException.field("ownerUserId", "UNKNOWN_OWNER",
                        "Owner must be an active workspace user")).getId();
    }

    private String resolveSource(String workspaceId, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return sources.findByWorkspaceIdAndCode(workspaceId, code).map(LeadSource::getId).orElse(null);
    }

    private static String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public Clock clock() {
        return clock;
    }
}
