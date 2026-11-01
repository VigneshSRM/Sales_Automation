package com.infeara.sales.workspace;

import com.infeara.sales.audit.AuditRecorder;
import com.infeara.sales.common.json.JsonCodec;
import com.infeara.sales.common.util.Ids;
import com.infeara.sales.common.util.Normalizer;
import com.infeara.sales.config.AppProperties;
import com.infeara.sales.crm.catalog.Industry;
import com.infeara.sales.crm.catalog.IndustryRepository;
import com.infeara.sales.crm.catalog.LeadSource;
import com.infeara.sales.crm.catalog.LeadSourceRepository;
import com.infeara.sales.crm.catalog.LossReason;
import com.infeara.sales.crm.catalog.LossReasonRepository;
import com.infeara.sales.crm.catalog.PipelineStage;
import com.infeara.sales.crm.catalog.PipelineStageRepository;
import com.infeara.sales.crm.catalog.ServiceOffering;
import com.infeara.sales.crm.catalog.ServiceOfferingRepository;
import com.infeara.sales.crm.scoring.ScoringModel;
import com.infeara.sales.crm.scoring.ScoringModelRepository;
import com.infeara.sales.identity.PasswordPolicy;
import com.infeara.sales.identity.Role;
import com.infeara.sales.identity.RoleRepository;
import com.infeara.sales.identity.User;
import com.infeara.sales.identity.UserRepository;
import com.infeara.sales.identity.UserRole;
import com.infeara.sales.identity.UserRoleRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-run provisioning. Creates the workspace, its editable catalogues (services,
 * sources, pipeline stages, loss reasons, scoring model v1) and the first Super Admin.
 *
 * <p>Runs only when {@code app.bootstrap.admin-password} is supplied and the workspace
 * slug does not exist yet. No credentials exist in source control; the admin must
 * enrol MFA on first login (privileged capability) and change the password.
 *
 * <p><strong>Product-owner action required:</strong> the service catalogue seeded here
 * is a placeholder baseline (AMB-05). Exact service names, approved claims and case
 * studies must be reviewed and edited in Administration → Services before AI outreach
 * or proposals are enabled.
 */
@Component
public class WorkspaceBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceBootstrapService.class);

    private final WorkspaceRepository workspaces;
    private final WorkspaceSettingRepository settings;
    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final IndustryRepository industries;
    private final ServiceOfferingRepository services;
    private final LeadSourceRepository sources;
    private final PipelineStageRepository stages;
    private final LossReasonRepository lossReasons;
    private final ScoringModelRepository scoringModels;
    private final PasswordEncoder passwordEncoder;
    private final JsonCodec jsonCodec;
    private final AuditRecorder audit;
    private final AppProperties properties;
    private final Clock clock;

    public WorkspaceBootstrapService(WorkspaceRepository workspaces, WorkspaceSettingRepository settings,
                                     UserRepository users, RoleRepository roles, UserRoleRepository userRoles,
                                     IndustryRepository industries, ServiceOfferingRepository services,
                                     LeadSourceRepository sources, PipelineStageRepository stages,
                                     LossReasonRepository lossReasons, ScoringModelRepository scoringModels,
                                     PasswordEncoder passwordEncoder, JsonCodec jsonCodec, AuditRecorder audit,
                                     AppProperties properties, Clock clock) {
        this.workspaces = workspaces;
        this.settings = settings;
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.industries = industries;
        this.services = services;
        this.sources = sources;
        this.stages = stages;
        this.lossReasons = lossReasons;
        this.scoringModels = scoringModels;
        this.passwordEncoder = passwordEncoder;
        this.jsonCodec = jsonCodec;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap config = properties.getBootstrap();
        if (workspaces.existsBySlug(config.getWorkspaceSlug())) {
            return;
        }
        if (config.getAdminEmail() == null || config.getAdminEmail().isBlank()
                || config.getAdminPassword() == null || config.getAdminPassword().isBlank()) {
            log.warn("bootstrap_skipped: set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD for first run");
            return;
        }
        PasswordPolicy.validate(config.getAdminPassword());

        Workspace workspace = new Workspace();
        workspace.setId(Ids.newId());
        workspace.setName(config.getWorkspaceName());
        workspace.setSlug(config.getWorkspaceSlug());
        workspace.setDefaultTimezone(config.getWorkspaceTimezone());
        // flush (not just save): Hibernate orders INSERTs alphabetically by entity name, so every
        // dependent row (audit_logs sorts before workspaces) would otherwise hit the foreign key
        // before the workspace itself reaches the database - the failure Tomcat reported first.
        workspaces.saveAndFlush(workspace);
        String workspaceId = workspace.getId();

        seedSetting(workspaceId, "sending.window", Map.of(
                "startHourLocal", 9, "endHourLocal", 17, "weekdaysOnly", true,
                "fallbackTimezone", config.getWorkspaceTimezone()));
        seedSetting(workspaceId, "email.unsubscribeFooter", Map.of(
                "required", true,
                "text", "You are receiving this email because of a previous business enquiry. "
                        + "Unsubscribe at any time."));
        seedSetting(workspaceId, "duplicate.policy", Map.of("warnOnCreate", true, "fuzzyThreshold", 0.86));

        seedIndustries(workspaceId);
        seedServices(workspaceId);
        List<LeadSource> createdSources = seedSources(workspaceId);
        seedStages(workspaceId);
        seedLossReasons(workspaceId);
        seedScoringModel(workspaceId);

        String email = Normalizer.email(config.getAdminEmail());
        User admin = new User();
        admin.setId(Ids.newId());
        admin.setWorkspaceId(workspaceId);
        admin.setEmail(email);
        admin.setEmailNormalized(email);
        admin.setDisplayName(config.getAdminDisplayName());
        admin.setPasswordHash(passwordEncoder.encode(config.getAdminPassword()));
        admin.setStatus("ACTIVE");
        admin.setScopeType("WORKSPACE");
        admin.setTimezone(config.getWorkspaceTimezone());
        admin.setMustChangePassword(true);
        admin.setCreatedBy(null);
        users.save(admin);

        Role superAdmin = roles.findByCode("SUPER_ADMIN").orElseThrow(
                () -> new IllegalStateException("V8 seed missing SUPER_ADMIN role"));
        UserRole link = new UserRole();
        link.setId(Ids.newId());
        link.setWorkspaceId(workspaceId);
        link.setUserId(admin.getId());
        link.setRoleId(superAdmin.getId());
        link.setAssignedBy(null);
        userRoles.save(link);

        audit.recordSystem(workspaceId, "WORKSPACE_BOOTSTRAPPED", "WORKSPACE", workspaceId,
                Map.of("slug", workspace.getSlug(), "sources", createdSources.size(),
                        "adminEmail", email),
                Map.of("catalogueVersion", "v1", "requiresProductOwnerReview", true));
        log.info("bootstrap_complete workspace={} admin={} (MFA enrolment required at first login)",
                workspace.getSlug(), email);
    }

    private void seedSetting(String workspaceId, String key, Object value) {
        WorkspaceSetting setting = new WorkspaceSetting();
        setting.setId(Ids.newId());
        setting.setWorkspaceId(workspaceId);
        setting.setSettingKey(key);
        setting.setSettingValue(jsonCodec.write(value));
        settings.save(setting);
    }

    private void seedIndustries(String workspaceId) {
        record Seed(String code, String name) {
        }
        List.of(new Seed("IT_SERVICES", "IT & Technology Services"),
                new Seed("BFSI", "Banking, Financial Services & Insurance"),
                new Seed("MANUFACTURING", "Manufacturing"),
                new Seed("HEALTHCARE", "Healthcare & Life Sciences"),
                new Seed("RETAIL", "Retail & E-commerce"),
                new Seed("LOGISTICS", "Logistics & Supply Chain"),
                new Seed("EDUCATION", "Education"),
                new Seed("OTHER", "Other")).forEach(seed -> {
                    Industry industry = new Industry();
                    industry.setId(Ids.newId());
                    industry.setWorkspaceId(workspaceId);
                    industry.setCode(seed.code());
                    industry.setName(seed.name());
                    industries.save(industry);
                });
    }

    private void seedServices(String workspaceId) {
        // PLACEHOLDER baseline — must be reviewed by the product owner (AMB-05).
        record Seed(String code, String name, String division, String description) {
        }
        List.of(new Seed("CYBER_SECURITY", "Cybersecurity & Compliance", "Cybersecurity",
                        "Security assessments, compliance readiness and managed security operations."),
                new Seed("IT_INFRA", "IT Infrastructure & Managed Services", "Infrastructure",
                        "Infrastructure design, operations and managed service desk."),
                new Seed("CLOUD", "Cloud Solutions", "Cloud",
                        "Cloud migration, landing zones and cost optimisation."),
                new Seed("AI_AUTOMATION", "AI, Automation & Software Development", "Digital",
                        "Automation, application development and applied AI delivery."),
                new Seed("TURNKEY_ODC", "Turnkey & ODC Services", "Delivery",
                        "Turnkey project delivery and dedicated offshore development centres."))
                .forEach(seed -> {
                    ServiceOffering service = new ServiceOffering();
                    service.setId(Ids.newId());
                    service.setWorkspaceId(workspaceId);
                    service.setCode(seed.code());
                    service.setName(seed.name());
                    service.setDivision(seed.division());
                    service.setDescription(seed.description());
                    service.setApprovedClaims(jsonCodec.write(Map.of("approved", false,
                            "note", "Awaiting product-owner approval of service claims (AMB-05)")));
                    services.save(service);
                });
    }

    private List<LeadSource> seedSources(String workspaceId) {
        record Seed(String code, String name, String type, String duplicatePolicy) {
        }
        List<Seed> seeds = List.of(
                new Seed("WEBSITE", "Website enquiry", "WEBSITE", "WARN"),
                new Seed("MANUAL", "Manual entry", "MANUAL", "WARN"),
                new Seed("IMPORT", "Approved import", "IMPORT", "REJECT_ON_DUPLICATE"),
                new Seed("REFERRAL", "Referral", "REFERRAL", "WARN"),
                new Seed("EMAIL_ENQUIRY", "Email enquiry", "EMAIL_ENQUIRY", "WARN"),
                new Seed("CALENDLY", "Calendly booking", "CALENDLY", "ALLOW"));
        return seeds.stream().map(seed -> {
            LeadSource source = new LeadSource();
            source.setId(Ids.newId());
            source.setWorkspaceId(workspaceId);
            source.setCode(seed.code());
            source.setName(seed.name());
            source.setSourceType(seed.type());
            source.setDuplicatePolicy(seed.duplicatePolicy());
            sources.save(source);
            return source;
        }).toList();
    }

    private void seedStages(String workspaceId) {
        record Seed(String code, String name, int order, String terminal) {
        }
        List.of(new Seed("NEW_OPPORTUNITY", "New Opportunity", 1, "NONE"),
                new Seed("QUALIFICATION", "Qualification", 2, "NONE"),
                new Seed("DISCOVERY", "Discovery", 3, "NONE"),
                new Seed("TECHNICAL_ASSESSMENT", "Technical Assessment", 4, "NONE"),
                new Seed("PROPOSAL_PREPARATION", "Proposal Preparation", 5, "NONE"),
                new Seed("PROPOSAL_SENT", "Proposal Sent", 6, "NONE"),
                new Seed("NEGOTIATION", "Negotiation", 7, "NONE"),
                new Seed("CLOSED_WON", "Closed Won", 8, "WON"),
                new Seed("CLOSED_LOST", "Closed Lost", 9, "LOST"))
                .forEach(seed -> {
                    PipelineStage stage = new PipelineStage();
                    stage.setId(Ids.newId());
                    stage.setWorkspaceId(workspaceId);
                    stage.setCode(seed.code());
                    stage.setName(seed.name());
                    stage.setOrderNo(seed.order());
                    stage.setTerminalType(seed.terminal());
                    stage.setRequiredFields(jsonCodec.write(requiredFieldsFor(seed.code())));
                    stages.save(stage);
                });
    }

    private List<String> requiredFieldsFor(String stageCode) {
        return switch (stageCode) {
            case "QUALIFICATION" -> List.of("qualificationAssessment");
            case "DISCOVERY" -> List.of("discoveryInteraction");
            case "TECHNICAL_ASSESSMENT" -> List.of("technicalOwnerId");
            case "PROPOSAL_PREPARATION" -> List.of("proposalDraft");
            case "PROPOSAL_SENT" -> List.of("approvedProposalVersion", "sendEvidence");
            case "NEGOTIATION" -> List.of("nextStep");
            case "CLOSED_WON" -> List.of("closeDate", "finalAmount", "decisionContactId");
            case "CLOSED_LOST" -> List.of("lossReasonId");
            default -> List.of();
        };
    }

    private void seedLossReasons(String workspaceId) {
        record Seed(String code, String name) {
        }
        List.of(new Seed("PRICE", "Price / budget"), new Seed("COMPETITOR", "Lost to competitor"),
                new Seed("TIMING", "Timing not right"), new Seed("NO_DECISION", "No decision made"),
                new Seed("SCOPE_MISMATCH", "Scope or capability mismatch"),
                new Seed("NO_RESPONSE", "No response from prospect"),
                new Seed("OTHER", "Other (see notes)")).forEach(seed -> {
                    LossReason reason = new LossReason();
                    reason.setId(Ids.newId());
                    reason.setWorkspaceId(workspaceId);
                    reason.setCode(seed.code());
                    reason.setName(seed.name());
                    lossReasons.save(reason);
                });
    }

    private void seedScoringModel(String workspaceId) {
        ScoringModel model = new ScoringModel();
        model.setId(Ids.newId());
        model.setWorkspaceId(workspaceId);
        model.setVersion(1);
        model.setName("Default qualification model");
        model.setFactorJson(jsonCodec.write(List.of(
                Map.of("code", "INDUSTRY_SERVICE_FIT", "weight", 15),
                Map.of("code", "COMPANY_SIZE_FIT", "weight", 10),
                Map.of("code", "REQUIREMENT_FIT", "weight", 20),
                Map.of("code", "BUDGET_COMPATIBILITY", "weight", 15),
                Map.of("code", "PROJECT_TIMELINE", "weight", 10),
                Map.of("code", "DECISION_MAKER_ROLE", "weight", 15),
                Map.of("code", "MEANINGFUL_ENGAGEMENT", "weight", 15))));
        model.setBandsJson(jsonCodec.write(Map.of("highPriority", 80, "qualifiedCandidate", 60, "nurture", 40)));
        model.setActive(true);
        scoringModels.save(model);
    }
}
