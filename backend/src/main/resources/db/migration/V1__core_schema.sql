-- =====================================================================
-- V1 — Core schema, generated from the JPA entity model.
-- Regenerate with: python3 tools/generate_schema.py
-- Do not hand-edit: adjust the entities or the generator's constraint
-- tables instead (docs/database/ERD.md explains the model).
-- MariaDB 11.4 / InnoDB / utf8mb4 / all timestamps UTC.
-- =====================================================================
-- Foreign keys are declared inline but parent tables are created in
-- alphabetical order, so referential checks are relaxed for this script only.
SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE accounts (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    name_normalized VARCHAR(200) NOT NULL,
    legal_name VARCHAR(200),
    trading_name VARCHAR(200),
    website VARCHAR(400),
    domain_normalized VARCHAR(255),
    industry_id VARCHAR(36),
    size_band VARCHAR(40),
    headquarters VARCHAR(200),
    locations_json JSON,
    description TEXT,
    relationship_status VARCHAR(32) NOT NULL,
    owner_user_id VARCHAR(36),
    source_id VARCHAR(36),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36),
    updated_by VARCHAR(36),
    deleted_at DATETIME(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_accounts_workspace_id_name_normalized (workspace_id, name_normalized),
    KEY ix_accounts_workspace_id_domain_normalized (workspace_id, domain_normalized),
    KEY ix_accounts_workspace_id_owner_user_id (workspace_id, owner_user_id),
    CONSTRAINT fk_accounts_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_accounts_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE activities (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    type VARCHAR(32) NOT NULL,
    related_type VARCHAR(30) NOT NULL,
    related_id VARCHAR(36) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_user_id VARCHAR(36),
    subject VARCHAR(300),
    body TEXT,
    payload_json JSON,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_activities_workspace_id_related_type_related_id (workspace_id, related_type, related_id),
    KEY ix_activities_workspace_id_actor_user_id_occurred_at (workspace_id, actor_user_id, occurred_at),
    CONSTRAINT fk_activities_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_activities_1 CHECK (related_type IN ('LEAD','ACCOUNT','CONTACT','OPPORTUNITY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_logs (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_user_id VARCHAR(36),
    action VARCHAR(80) NOT NULL,
    object_type VARCHAR(60) NOT NULL,
    object_id VARCHAR(36),
    outcome VARCHAR(16) NOT NULL,
    before_json JSON,
    after_json JSON,
    metadata_json JSON,
    correlation_id VARCHAR(64),
    ip_address VARCHAR(64),
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    prev_hash VARCHAR(64),
    record_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_audit_logs_workspace_id_occurred_at (workspace_id, occurred_at),
    KEY ix_audit_logs_object_type_object_id (object_type, object_id),
    KEY ix_audit_logs_workspace_id_action (workspace_id, action),
    CONSTRAINT fk_audit_logs_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE contacts (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100),
    full_name_normalized VARCHAR(220) NOT NULL,
    title VARCHAR(160),
    email VARCHAR(254),
    email_normalized VARCHAR(254),
    phone VARCHAR(40),
    phone_e164 VARCHAR(20),
    phone_verified BOOLEAN NOT NULL,
    preferred_channel VARCHAR(20) NOT NULL,
    timezone VARCHAR(64),
    decision_role VARCHAR(40),
    communication_preferences JSON,
    consent_status VARCHAR(24) NOT NULL,
    suppression_status VARCHAR(24) NOT NULL,
    owner_user_id VARCHAR(36),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36),
    updated_by VARCHAR(36),
    deleted_at DATETIME(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_contacts_account_id (account_id),
    KEY ix_contacts_workspace_id_email_normalized (workspace_id, email_normalized),
    KEY ix_contacts_workspace_id_phone_e164 (workspace_id, phone_e164),
    KEY ix_contacts_workspace_id_full_name_normalized (workspace_id, full_name_normalized),
    CONSTRAINT fk_contacts_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_contacts_account_id FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_contacts_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE idempotency_keys (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    scope VARCHAR(80) NOT NULL,
    key_value VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INT,
    response_json TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_keys_workspace_id_scope_key_value (workspace_id, scope, key_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE industries (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_industries_workspace_id_code (workspace_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE job_executions (
    id VARCHAR(36) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    worker_id VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6),
    duration_ms BIGINT,
    error_code VARCHAR(60),
    error_message TEXT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_executions_job_id_attempt_no (job_id, attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE jobs (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    job_type VARCHAR(80) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    priority INT NOT NULL,
    attempt_count INT NOT NULL,
    max_attempts INT NOT NULL,
    available_at DATETIME(6) NOT NULL,
    locked_until DATETIME(6),
    locked_by VARCHAR(80),
    idempotency_key VARCHAR(160),
    dedupe_scope VARCHAR(80),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6),
    last_error TEXT,
    correlation_id VARCHAR(64),
    PRIMARY KEY (id),
    KEY ix_jobs_status_available_at (status, available_at),
    KEY ix_jobs_workspace_id_status (workspace_id, status),
    CONSTRAINT ck_jobs_1 CHECK (status IN ('PENDING','CLAIMED','RETRY','COMPLETED','DEAD','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE lead_duplicates (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    lead_id VARCHAR(36) NOT NULL,
    candidate_type VARCHAR(20) NOT NULL,
    candidate_id VARCHAR(36) NOT NULL,
    match_type VARCHAR(40) NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    resolution VARCHAR(24) NOT NULL,
    resolved_by VARCHAR(36),
    resolved_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_lead_duplicates_workspace_id_resolution (workspace_id, resolution),
    CONSTRAINT fk_lead_duplicates_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_lead_duplicates_lead_id FOREIGN KEY (lead_id) REFERENCES leads (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE lead_score_history (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    lead_id VARCHAR(36) NOT NULL,
    model_version INT NOT NULL,
    total INT NOT NULL,
    band VARCHAR(24) NOT NULL,
    factor_json JSON NOT NULL,
    evidence_json JSON,
    calculation_source VARCHAR(24) NOT NULL,
    override_reason VARCHAR(500),
    overridden_by VARCHAR(36),
    calculated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_lead_score_history_lead_id_calculated_at (lead_id, calculated_at),
    CONSTRAINT fk_lead_score_history_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_lead_score_history_lead_id FOREIGN KEY (lead_id) REFERENCES leads (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE lead_sources (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    duplicate_policy VARCHAR(32) NOT NULL,
    webhook_secret_ciphertext VARCHAR(512),
    webhook_secret_key_id VARCHAR(64),
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_lead_sources_workspace_id_code (workspace_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE leads (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    contact_id VARCHAR(36),
    account_id VARCHAR(36),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100),
    email VARCHAR(254),
    email_normalized VARCHAR(254),
    phone VARCHAR(40),
    phone_e164 VARCHAR(20),
    whatsapp VARCHAR(40),
    company_name VARCHAR(200) NOT NULL,
    company_name_normalized VARCHAR(200) NOT NULL,
    domain_normalized VARCHAR(255),
    industry_id VARCHAR(36),
    location VARCHAR(200),
    employee_band VARCHAR(40),
    requirement TEXT,
    budget_amount DECIMAL(19,4),
    budget_currency VARCHAR(3),
    timeline_code VARCHAR(40),
    source_id VARCHAR(36) NOT NULL,
    source_evidence_ref VARCHAR(400),
    campaign_id VARCHAR(36),
    primary_service_id VARCHAR(36),
    owner_user_id VARCHAR(36),
    status VARCHAR(32) NOT NULL,
    score INT,
    score_band VARCHAR(24),
    scoring_model_version INT,
    qualification_assessment JSON,
    consent_reference VARCHAR(200),
    merged_into_id VARCHAR(36),
    converted_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36),
    updated_by VARCHAR(36),
    deleted_at DATETIME(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_leads_workspace_id_status_created_at (workspace_id, status, created_at),
    KEY ix_leads_workspace_id_owner_user_id_status (workspace_id, owner_user_id, status),
    KEY ix_leads_workspace_id_email_normalized (workspace_id, email_normalized),
    KEY ix_leads_workspace_id_phone_e164 (workspace_id, phone_e164),
    KEY ix_leads_workspace_id_domain_normalized (workspace_id, domain_normalized),
    KEY ix_leads_workspace_id_company_name_normalized (workspace_id, company_name_normalized),
    CONSTRAINT fk_leads_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_leads_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT fk_leads_account_id FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_leads_contact_id FOREIGN KEY (contact_id) REFERENCES contacts (id),
    CONSTRAINT fk_leads_industry_id FOREIGN KEY (industry_id) REFERENCES industries (id),
    CONSTRAINT fk_leads_source_id FOREIGN KEY (source_id) REFERENCES lead_sources (id),
    CONSTRAINT fk_leads_primary_service_id FOREIGN KEY (primary_service_id) REFERENCES services (id),
    CONSTRAINT ck_leads_1 CHECK (score IS NULL OR (score BETWEEN 0 AND 100)),
    CONSTRAINT ck_leads_2 CHECK (status IN ('NEW','RESEARCHING','QUALIFIED','CONTACTED','ENGAGED','MEETING_SCHEDULED','DISCOVERY_COMPLETED','CONVERTED','NURTURE','DISQUALIFIED','REOPENED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE loss_reasons (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    active BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_loss_reasons_workspace_id_code (workspace_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE merge_audits (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    source_ids_json JSON NOT NULL,
    field_decisions_json JSON NOT NULL,
    reason VARCHAR(500),
    actor_user_id VARCHAR(36),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_merge_audits_workspace_id_created_at (workspace_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notification_preferences (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    notification_type VARCHAR(60) NOT NULL,
    in_app_enabled BOOLEAN NOT NULL,
    email_enabled BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_preferences_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notifications (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    recipient_user_id VARCHAR(36) NOT NULL,
    type VARCHAR(60) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT,
    link VARCHAR(400),
    payload_json JSON,
    dedupe_key VARCHAR(200),
    read_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_notifications_recipient_user_id_read_at_created_at (recipient_user_id, read_at, created_at),
    CONSTRAINT fk_notifications_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_notifications_recipient_user_id FOREIGN KEY (recipient_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE opportunities (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    primary_contact_id VARCHAR(36),
    lead_id VARCHAR(36),
    service_id VARCHAR(36),
    name VARCHAR(200) NOT NULL,
    stage_id VARCHAR(36) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    probability INT NOT NULL,
    expected_close_date DATE,
    close_date DATE,
    final_amount DECIMAL(19,4),
    final_currency VARCHAR(3),
    loss_reason_id VARCHAR(36),
    loss_notes TEXT,
    competitor VARCHAR(200),
    qualification_assessment JSON,
    decision_contact_id VARCHAR(36),
    technical_owner_id VARCHAR(36),
    next_step VARCHAR(500),
    open_objections JSON,
    owner_user_id VARCHAR(36),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36),
    updated_by VARCHAR(36),
    deleted_at DATETIME(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_opportunities_workspace_id_stage_id (workspace_id, stage_id),
    KEY ix_opportunities_account_id (account_id),
    KEY ix_opportunities_workspace_id_owner_user_id (workspace_id, owner_user_id),
    KEY ix_opportunities_workspace_id_close_date (workspace_id, close_date),
    CONSTRAINT fk_opportunities_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_opportunities_account_id FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_opportunities_lead_id FOREIGN KEY (lead_id) REFERENCES leads (id),
    CONSTRAINT fk_opportunities_primary_contact_id FOREIGN KEY (primary_contact_id) REFERENCES contacts (id),
    CONSTRAINT fk_opportunities_decision_contact_id FOREIGN KEY (decision_contact_id) REFERENCES contacts (id),
    CONSTRAINT fk_opportunities_service_id FOREIGN KEY (service_id) REFERENCES services (id),
    CONSTRAINT fk_opportunities_stage_id FOREIGN KEY (stage_id) REFERENCES pipeline_stages (id),
    CONSTRAINT fk_opportunities_loss_reason_id FOREIGN KEY (loss_reason_id) REFERENCES loss_reasons (id),
    CONSTRAINT fk_opportunities_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT ck_opportunities_1 CHECK (amount >= 0),
    CONSTRAINT ck_opportunities_2 CHECK (probability BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE outbox_events (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(60) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL,
    max_attempts INT NOT NULL,
    available_at DATETIME(6) NOT NULL,
    locked_until DATETIME(6),
    locked_by VARCHAR(80),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6),
    last_error TEXT,
    correlation_id VARCHAR(64),
    PRIMARY KEY (id),
    KEY ix_outbox_events_status_available_at (status, available_at),
    CONSTRAINT ck_outbox_events_1 CHECK (status IN ('PENDING','CLAIMED','PROCESSED','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE pipeline_stages (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    order_no INT NOT NULL,
    terminal_type VARCHAR(8) NOT NULL,
    allow_skip BOOLEAN NOT NULL,
    required_fields JSON,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pipeline_stages_workspace_id_code (workspace_id, code),
    UNIQUE KEY uk_pipeline_stages_workspace_id_order_no (workspace_id, order_no),
    CONSTRAINT ck_pipeline_stages_1 CHECK (terminal_type IN ('NONE','WON','LOST'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE roles (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36),
    code VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(255),
    system_role BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scoring_models (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    version INT NOT NULL,
    name VARCHAR(120) NOT NULL,
    factor_json JSON NOT NULL,
    bands_json JSON NOT NULL,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36),
    PRIMARY KEY (id),
    UNIQUE KEY uk_scoring_models_workspace_id_version (workspace_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE services (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(160) NOT NULL,
    division VARCHAR(80) NOT NULL,
    description TEXT,
    approved_claims JSON,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_services_workspace_id_code (workspace_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE stage_history (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    opportunity_id VARCHAR(36) NOT NULL,
    from_stage_id VARCHAR(36),
    to_stage_id VARCHAR(36) NOT NULL,
    actor_user_id VARCHAR(36),
    reason VARCHAR(500),
    rule_version VARCHAR(40) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_stage_history_opportunity_id_created_at (opportunity_id, created_at),
    CONSTRAINT fk_stage_history_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_stage_history_opportunity_id FOREIGN KEY (opportunity_id) REFERENCES opportunities (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tasks (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    owner_user_id VARCHAR(36) NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    due_at DATETIME(6),
    status VARCHAR(20) NOT NULL,
    priority VARCHAR(10) NOT NULL,
    related_type VARCHAR(30),
    related_id VARCHAR(36),
    reminder_policy VARCHAR(40) NOT NULL,
    reminder_sent_at DATETIME(6),
    completed_at DATETIME(6),
    completion_note TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36),
    deleted_at DATETIME(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_tasks_workspace_id_owner_user_id_status_due_at (workspace_id, owner_user_id, status, due_at),
    KEY ix_tasks_workspace_id_status_due_at (workspace_id, status, due_at),
    CONSTRAINT fk_tasks_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_tasks_owner_user_id FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT ck_tasks_1 CHECK (status IN ('OPEN','IN_PROGRESS','COMPLETED','CANCELLED')),
    CONSTRAINT ck_tasks_2 CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE team_members (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    team_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_team_members_team_id FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_team_members_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE teams (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    manager_user_id VARCHAR(36),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_teams_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_teams_manager_user_id FOREIGN KEY (manager_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_roles (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    role_id VARCHAR(36) NOT NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    assigned_by VARCHAR(36),
    PRIMARY KEY (id),
    CONSTRAINT fk_user_roles_user_id FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role_id FOREIGN KEY (role_id) REFERENCES roles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_sessions (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    ip_address VARCHAR(64),
    user_agent VARCHAR(400),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sessions_token_hash (token_hash),
    KEY ix_user_sessions_user_id_revoked_at (user_id, revoked_at),
    KEY ix_user_sessions_expires_at (expires_at),
    CONSTRAINT fk_user_sessions_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_user_sessions_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE users (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    email VARCHAR(254) NOT NULL,
    email_normalized VARCHAR(254) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255),
    status VARCHAR(24) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    locale VARCHAR(16) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    mfa_enabled BOOLEAN NOT NULL,
    mfa_secret_ciphertext VARCHAR(512),
    mfa_secret_key_id VARCHAR(64),
    mfa_enrolled_at DATETIME(6),
    mfa_last_used_step BIGINT,
    failed_login_count INT NOT NULL,
    locked_until DATETIME(6),
    last_login_at DATETIME(6),
    must_change_password BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    created_by VARCHAR(36),
    updated_by VARCHAR(36),
    deleted_at DATETIME(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_users_workspace_id_status (workspace_id, status),
    CONSTRAINT fk_users_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workspace_settings (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    setting_key VARCHAR(120) NOT NULL,
    setting_value JSON NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(36),
    PRIMARY KEY (id),
    CONSTRAINT fk_workspace_settings_workspace_id FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workspaces (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    default_timezone VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspaces_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE role_capabilities (
    role_id CHAR(36) NOT NULL,
    capability VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, capability),
    CONSTRAINT fk_role_capabilities_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;