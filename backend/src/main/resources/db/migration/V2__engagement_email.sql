-- =====================================================================
-- V3 — Engagement: SMTP profiles, templates, messages, events, consent,
--      suppression, sequences, campaigns, inbound sync state.
-- =====================================================================

CREATE TABLE smtp_profiles (
    id                    CHAR(36)     NOT NULL,
    workspace_id          CHAR(36)     NOT NULL,
    name                  VARCHAR(80)  NOT NULL,
    from_name             VARCHAR(120) NOT NULL,
    from_email            VARCHAR(254) NOT NULL,
    reply_to_email        VARCHAR(254) NULL,
    host                  VARCHAR(253) NOT NULL,
    port                  INT          NOT NULL,
    tls_mode              VARCHAR(20)  NOT NULL,
    username              VARCHAR(254) NOT NULL,
    secret_ciphertext     VARCHAR(2048) NOT NULL,
    secret_key_version    INT          NOT NULL DEFAULT 1,
    daily_limit           INT          NOT NULL DEFAULT 200,
    hourly_limit          INT          NOT NULL DEFAULT 50,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING_TEST',
    last_tested_at        DATETIME(6)  NULL,
    last_test_detail      VARCHAR(500) NULL,
    sent_today            INT          NOT NULL DEFAULT 0,
    sent_today_date       DATE         NULL,
    created_by            CHAR(36)     NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at            DATETIME(6)  NULL,
    row_version           BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_smtp_ws_name (workspace_id, name),
    CONSTRAINT fk_smtp_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_smtp_status CHECK (status IN ('PENDING_TEST','ACTIVE','DISABLED','ERROR')),
    CONSTRAINT ck_smtp_tls CHECK (tls_mode IN ('STARTTLS','IMPLICIT_TLS','NONE')),
    CONSTRAINT ck_smtp_port CHECK (port >= 1 AND port <= 65535)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_templates (
    id                CHAR(36)      NOT NULL,
    workspace_id      CHAR(36)      NOT NULL,
    name              VARCHAR(160)  NOT NULL,
    purpose           VARCHAR(40)   NOT NULL DEFAULT 'OUTREACH',
    subject_template  VARCHAR(300)  NOT NULL,
    body_template     MEDIUMTEXT    NOT NULL,
    merge_fields_json JSON          NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    version           INT           NOT NULL DEFAULT 1,
    approved_by       CHAR(36)      NULL,
    approved_at       DATETIME(6)   NULL,
    created_by        CHAR(36)      NULL,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at        DATETIME(6)   NULL,
    row_version       BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_templates_ws_name (workspace_id, name),
    CONSTRAINT fk_templates_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_templates_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_messages (
    id                  CHAR(36)     NOT NULL,
    workspace_id        CHAR(36)     NOT NULL,
    smtp_profile_id     CHAR(36)     NOT NULL,
    template_id         CHAR(36)     NULL,
    lead_id             CHAR(36)     NULL,
    contact_id          CHAR(36)     NULL,
    account_id          CHAR(36)     NULL,
    opportunity_id      CHAR(36)     NULL,
    sequence_enrollment_id CHAR(36)  NULL,
    campaign_id         CHAR(36)     NULL,
    to_email            VARCHAR(254) NOT NULL,
    to_name             VARCHAR(200) NULL,
    subject             VARCHAR(300) NOT NULL,
    body_html           MEDIUMTEXT   NOT NULL,
    body_text           MEDIUMTEXT   NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    scheduled_at        DATETIME(6)  NULL,
    sent_at             DATETIME(6)  NULL,
    provider_message_id VARCHAR(200) NULL,
    message_key         VARCHAR(120) NOT NULL,
    failure_code        VARCHAR(60)  NULL,
    failure_detail      VARCHAR(1000) NULL,
    attempt_count       INT          NOT NULL DEFAULT 0,
    approved_by         CHAR(36)     NULL,
    approved_at         DATETIME(6)  NULL,
    created_by          CHAR(36)     NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_message_key (workspace_id, message_key),
    KEY idx_email_status (workspace_id, status, scheduled_at),
    KEY idx_email_lead (lead_id, created_at),
    KEY idx_email_thread (workspace_id, provider_message_id),
    CONSTRAINT fk_email_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_email_smtp FOREIGN KEY (smtp_profile_id) REFERENCES smtp_profiles (id),
    CONSTRAINT fk_email_template FOREIGN KEY (template_id) REFERENCES email_templates (id),
    CONSTRAINT fk_email_lead FOREIGN KEY (lead_id) REFERENCES leads (id),
    CONSTRAINT fk_email_contact FOREIGN KEY (contact_id) REFERENCES contacts (id),
    CONSTRAINT fk_email_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id),
    CONSTRAINT ck_email_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','SCHEDULED','QUEUED','SENDING',
        'SENT','DELIVERED','BOUNCED','REPLIED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_events (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    message_id        CHAR(36)     NOT NULL,
    event_type        VARCHAR(40)  NOT NULL,
    detail            VARCHAR(1000) NULL,
    payload_json      JSON         NULL,
    occurred_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_email_events_message (message_id, occurred_at),
    CONSTRAINT fk_email_events_message FOREIGN KEY (message_id) REFERENCES email_messages (id) ON DELETE CASCADE,
    CONSTRAINT ck_email_events_type CHECK (event_type IN ('QUEUED','SENT','DELIVERED','BOUNCED_SOFT',
        'BOUNCED_HARD','REPLIED','COMPLAINT','UNSUBSCRIBED','FAILED','OPENED_ESTIMATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE suppression_entries (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    email_normalized  VARCHAR(254) NOT NULL,
    reason            VARCHAR(40)  NOT NULL,
    source            VARCHAR(60)  NULL,
    note              VARCHAR(500) NULL,
    created_by        CHAR(36)     NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_suppression_email (workspace_id, email_normalized),
    CONSTRAINT fk_suppression_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_suppression_reason CHECK (reason IN ('UNSUBSCRIBED','HARD_BOUNCE','COMPLAINT','MANUAL','DO_NOT_CONTACT','LEGAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE consent_records (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    email_normalized  VARCHAR(254) NOT NULL,
    contact_id        CHAR(36)     NULL,
    lead_id           CHAR(36)     NULL,
    consent_status    VARCHAR(30)  NOT NULL,
    lawful_basis      VARCHAR(60)  NULL,
    reference         VARCHAR(300) NULL,
    captured_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at        DATETIME(6)  NULL,
    captured_by       CHAR(36)     NULL,
    PRIMARY KEY (id),
    KEY idx_consent_email (workspace_id, email_normalized, captured_at),
    CONSTRAINT fk_consent_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_consent_status CHECK (consent_status IN ('UNKNOWN','GRANTED','WITHDRAWN','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_sequences (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    name              VARCHAR(160) NOT NULL,
    description       VARCHAR(1000) NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    active_version    INT          NULL,
    created_by        CHAR(36)     NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at        DATETIME(6)  NULL,
    row_version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sequences_ws_name (workspace_id, name),
    CONSTRAINT fk_sequences_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_sequences_status CHECK (status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sequence_versions (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    sequence_id       CHAR(36)     NOT NULL,
    version           INT          NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_by        CHAR(36)     NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sequence_version (sequence_id, version),
    CONSTRAINT fk_seq_version_seq FOREIGN KEY (sequence_id) REFERENCES email_sequences (id) ON DELETE CASCADE,
    CONSTRAINT ck_seq_version_status CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sequence_steps (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    sequence_version_id CHAR(36)   NOT NULL,
    step_no           INT          NOT NULL,
    day_offset        INT          NOT NULL,
    template_id       CHAR(36)     NULL,
    subject_override  VARCHAR(300) NULL,
    stop_on_reply     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sequence_step (sequence_version_id, step_no),
    CONSTRAINT fk_seq_step_version FOREIGN KEY (sequence_version_id) REFERENCES sequence_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_seq_step_template FOREIGN KEY (template_id) REFERENCES email_templates (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sequence_enrollments (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    sequence_id       CHAR(36)     NOT NULL,
    sequence_version_id CHAR(36)   NOT NULL,
    lead_id           CHAR(36)     NOT NULL,
    contact_id        CHAR(36)     NULL,
    smtp_profile_id   CHAR(36)     NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    current_step      INT          NOT NULL DEFAULT 0,
    active_key        VARCHAR(160) NULL,
    enrolled_by       CHAR(36)     NULL,
    enrolled_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    stopped_at        DATETIME(6)  NULL,
    stop_reason       VARCHAR(60)  NULL,
    next_run_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enrollment_active (workspace_id, active_key),
    KEY idx_enrollment_due (status, next_run_at),
    CONSTRAINT fk_enrollment_seq FOREIGN KEY (sequence_id) REFERENCES email_sequences (id),
    CONSTRAINT fk_enrollment_version FOREIGN KEY (sequence_version_id) REFERENCES sequence_versions (id),
    CONSTRAINT fk_enrollment_lead FOREIGN KEY (lead_id) REFERENCES leads (id),
    CONSTRAINT ck_enrollment_status CHECK (status IN ('ACTIVE','PAUSED','COMPLETED','STOPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sequence_step_runs (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    enrollment_id     CHAR(36)     NOT NULL,
    step_no           INT          NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    email_message_id  CHAR(36)     NULL,
    detail            VARCHAR(1000) NULL,
    executed_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_step_run (enrollment_id, step_no),
    CONSTRAINT fk_step_run_enrollment FOREIGN KEY (enrollment_id) REFERENCES sequence_enrollments (id) ON DELETE CASCADE,
    CONSTRAINT fk_step_run_message FOREIGN KEY (email_message_id) REFERENCES email_messages (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE campaigns (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    name              VARCHAR(160) NOT NULL,
    objective         VARCHAR(500) NULL,
    service_id        CHAR(36)     NULL,
    template_id       CHAR(36)     NULL,
    smtp_profile_id   CHAR(36)     NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    scheduled_at      DATETIME(6)  NULL,
    target_lead_query JSON         NULL,
    created_by        CHAR(36)     NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaigns_ws_name (workspace_id, name),
    CONSTRAINT fk_campaigns_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_campaigns_template FOREIGN KEY (template_id) REFERENCES email_templates (id),
    CONSTRAINT ck_campaigns_status CHECK (status IN ('DRAFT','SCHEDULED','RUNNING','PAUSED','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inbound_sync_state (
    id                    CHAR(36)     NOT NULL,
    workspace_id          CHAR(36)     NOT NULL,
    smtp_profile_id       CHAR(36)     NOT NULL,
    mailbox               VARCHAR(254) NOT NULL,
    protocol              VARCHAR(20)  NOT NULL DEFAULT 'IMAP',
    last_uid              BIGINT       NOT NULL DEFAULT 0,
    last_synced_at        DATETIME(6)  NULL,
    consecutive_failures  INT          NOT NULL DEFAULT 0,
    last_error            VARCHAR(1000) NULL,
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound_profile (smtp_profile_id),
    CONSTRAINT fk_inbound_smtp FOREIGN KEY (smtp_profile_id) REFERENCES smtp_profiles (id) ON DELETE CASCADE,
    CONSTRAINT ck_inbound_protocol CHECK (protocol IN ('IMAP','POP3','MAILBOX_API','WEBHOOK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE unsubscribe_tokens (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    email_normalized  VARCHAR(254) NOT NULL,
    token_hash        CHAR(64)     NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    used_at           DATETIME(6)  NULL,
    expires_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_unsub_token (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
