-- =====================================================================
-- V8 — Two-Way Email Integration & AI Auto-Response Engine
--      (specification addendum, phases E1–E6)
--
-- Inbound mailbox access is a separate capability from outbound SMTP:
-- email_accounts owns mailbox connectivity, email_messages carries BOTH
-- directions (direction column), and every network call is executed by a
-- leased job so no I/O happens inside a database transaction.
-- =====================================================================

CREATE TABLE email_accounts (
    id                        CHAR(36)     NOT NULL,
    workspace_id              CHAR(36)     NOT NULL,
    name                      VARCHAR(80)  NOT NULL,
    provider                  VARCHAR(20)  NOT NULL DEFAULT 'IMAP',
    mailbox_address           VARCHAR(254) NOT NULL,
    from_name                 VARCHAR(120) NULL,
    inbound_enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    outbound_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    smtp_profile_id           CHAR(36)     NULL,
    host                      VARCHAR(253) NULL,
    port                      INT          NULL,
    tls_mode                  VARCHAR(20)  NULL,
    auth_method               VARCHAR(20)  NOT NULL DEFAULT 'PASSWORD',
    folder                    VARCHAR(120) NOT NULL DEFAULT 'INBOX',
    username                  VARCHAR(254) NULL,
    secret_ciphertext         VARCHAR(2048) NULL,
    secret_key_version        INT          NOT NULL DEFAULT 1,
    oauth_refresh_ciphertext  VARCHAR(4096) NULL,
    oauth_scopes              VARCHAR(500) NULL,
    owner_user_id             CHAR(36)     NULL,
    team_id                   CHAR(36)     NULL,
    queue_key                 VARCHAR(60)  NULL,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'PENDING_TEST',
    poll_interval_seconds     INT          NOT NULL DEFAULT 300,
    last_health_at            DATETIME(6)  NULL,
    last_error                VARCHAR(1000) NULL,
    created_by                CHAR(36)     NULL,
    created_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                DATETIME(6)  NULL,
    row_version               BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_account_ws_name (workspace_id, name),
    UNIQUE KEY uk_email_account_mailbox (workspace_id, mailbox_address),
    KEY idx_email_account_status (workspace_id, status),
    CONSTRAINT fk_email_account_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_email_account_smtp FOREIGN KEY (smtp_profile_id) REFERENCES smtp_profiles (id),
    CONSTRAINT ck_email_account_provider CHECK (provider IN ('IMAP','GMAIL_API','MICROSOFT_GRAPH')),
    CONSTRAINT ck_email_account_auth CHECK (auth_method IN ('PASSWORD','OAUTH2')),
    CONSTRAINT ck_email_account_status CHECK (status IN ('PENDING_TEST','ACTIVE','DISABLED','ERROR')),
    CONSTRAINT ck_email_account_tls CHECK (tls_mode IS NULL OR tls_mode IN ('STARTTLS','IMPLICIT_TLS','NONE')),
    CONSTRAINT ck_email_account_inbound CHECK (inbound_enabled = FALSE OR provider <> 'IMAP' OR host IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_sync_state (
    id                    CHAR(36)     NOT NULL,
    workspace_id          CHAR(36)     NOT NULL,
    account_id            CHAR(36)     NOT NULL,
    folder                VARCHAR(120) NOT NULL DEFAULT 'INBOX',
    uid_validity          BIGINT       NULL,
    last_uid              BIGINT       NOT NULL DEFAULT 0,
    provider_cursor       VARCHAR(500) NULL,
    last_sync_at          DATETIME(6)  NULL,
    last_success_at       DATETIME(6)  NULL,
    consecutive_failures  INT          NOT NULL DEFAULT 0,
    last_error            VARCHAR(1000) NULL,
    reconciliation_status VARCHAR(20)  NOT NULL DEFAULT 'IDLE',
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_sync_account (account_id),
    CONSTRAINT fk_email_sync_account FOREIGN KEY (account_id) REFERENCES email_accounts (id) ON DELETE CASCADE,
    CONSTRAINT ck_email_sync_status CHECK (reconciliation_status IN ('IDLE','RUNNING','RECONCILING','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_conversations (
    id                     CHAR(36)     NOT NULL,
    workspace_id           CHAR(36)     NOT NULL,
    account_id             CHAR(36)     NULL,
    mailbox_address        VARCHAR(254) NOT NULL,
    thread_key             VARCHAR(300) NOT NULL,
    subject                VARCHAR(300) NULL,
    normalized_subject     VARCHAR(300) NULL,
    participant_emails_json JSON        NULL,
    lead_id                CHAR(36)     NULL,
    contact_id             CHAR(36)     NULL,
    crm_account_id         CHAR(36)     NULL,
    opportunity_id         CHAR(36)     NULL,
    assigned_user_id       CHAR(36)     NULL,
    queue_key              VARCHAR(60)  NULL,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    priority               VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    unread                 BOOLEAN      NOT NULL DEFAULT TRUE,
    message_count          INT          NOT NULL DEFAULT 0,
    last_message_at        DATETIME(6)  NULL,
    created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version            BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_thread (workspace_id, thread_key),
    KEY idx_email_conv_inbox (workspace_id, status, last_message_at),
    KEY idx_email_conv_assigned (workspace_id, assigned_user_id, unread),
    KEY idx_email_conv_lead (lead_id),
    CONSTRAINT fk_email_conv_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_email_conv_account FOREIGN KEY (account_id) REFERENCES email_accounts (id),
    CONSTRAINT fk_email_conv_lead FOREIGN KEY (lead_id) REFERENCES leads (id),
    CONSTRAINT fk_email_conv_contact FOREIGN KEY (contact_id) REFERENCES contacts (id),
    CONSTRAINT fk_email_conv_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id),
    CONSTRAINT ck_email_conv_status CHECK (status IN ('OPEN','CLOSED','SNOOZED','SPAM')),
    CONSTRAINT ck_email_conv_priority CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------
-- email_messages becomes bidirectional. Inbound rows have no SMTP
-- profile (nothing was sent from here) and carry the RFC headers that
-- make threading possible.
-- ---------------------------------------------------------------
ALTER TABLE email_messages
    DROP CONSTRAINT ck_email_status,
    MODIFY COLUMN smtp_profile_id CHAR(36) NULL,
    ADD COLUMN direction VARCHAR(10) NOT NULL DEFAULT 'OUTBOUND' AFTER workspace_id,
    ADD COLUMN mailbox_account_id CHAR(36) NULL AFTER smtp_profile_id,
    ADD COLUMN conversation_id CHAR(36) NULL AFTER mailbox_account_id,
    ADD COLUMN message_id_header VARCHAR(400) NULL AFTER conversation_id,
    ADD COLUMN in_reply_to_header VARCHAR(400) NULL AFTER message_id_header,
    ADD COLUMN references_header VARCHAR(2000) NULL AFTER in_reply_to_header,
    ADD COLUMN from_email VARCHAR(254) NULL AFTER to_name,
    ADD COLUMN from_name VARCHAR(200) NULL AFTER from_email,
    ADD COLUMN cc_json JSON NULL AFTER from_name,
    ADD COLUMN reply_to_email VARCHAR(254) NULL AFTER cc_json,
    ADD COLUMN received_at DATETIME(6) NULL AFTER sent_at,
    ADD COLUMN raw_headers_json JSON NULL AFTER body_text,
    ADD COLUMN uid BIGINT NULL AFTER raw_headers_json,
    ADD COLUMN uid_validity BIGINT NULL AFTER uid,
    ADD COLUMN attachment_count INT NOT NULL DEFAULT 0 AFTER uid_validity,
    ADD COLUMN inbound_key VARCHAR(160) NULL AFTER message_key,
    ADD COLUMN ai_status VARCHAR(20) NOT NULL DEFAULT 'NONE' AFTER status,
    ADD CONSTRAINT ck_email_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','SCHEDULED','QUEUED','SENDING',
        'SENT','DELIVERED','BOUNCED','REPLIED','FAILED','CANCELLED','RECEIVED')),
    ADD CONSTRAINT ck_email_direction CHECK (direction IN ('OUTBOUND','INBOUND')),
    ADD CONSTRAINT ck_email_ai_status CHECK (ai_status IN ('NONE','QUEUED','ANALYZING','ANALYZED','FAILED')),
    ADD UNIQUE KEY uk_email_inbound_key (workspace_id, inbound_key),
    ADD KEY idx_email_conversation (conversation_id, received_at),
    ADD KEY idx_email_rfc_id (workspace_id, message_id_header);

ALTER TABLE email_messages
    ADD CONSTRAINT fk_email_mailbox_account FOREIGN KEY (mailbox_account_id) REFERENCES email_accounts (id),
    ADD CONSTRAINT fk_email_conversation FOREIGN KEY (conversation_id) REFERENCES email_conversations (id);

CREATE TABLE email_attachments (
    id             CHAR(36)     NOT NULL,
    workspace_id   CHAR(36)     NOT NULL,
    message_id     CHAR(36)     NOT NULL,
    filename       VARCHAR(255) NOT NULL,
    media_type     VARCHAR(160) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    storage_ref    VARCHAR(500) NULL,
    sha256         CHAR(64)     NULL,
    scan_status    VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_email_att_message (message_id),
    CONSTRAINT fk_email_att_message FOREIGN KEY (message_id) REFERENCES email_messages (id) ON DELETE CASCADE,
    CONSTRAINT ck_email_att_scan CHECK (scan_status IN ('PENDING','CLEAN','INFECTED','SKIPPED','TOO_LARGE')),
    CONSTRAINT ck_email_att_size CHECK (size_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_analysis (
    id                 CHAR(36)     NOT NULL,
    workspace_id       CHAR(36)     NOT NULL,
    message_id         CHAR(36)     NOT NULL,
    intent             VARCHAR(40)  NOT NULL,
    sub_intent         VARCHAR(80)  NULL,
    sentiment          VARCHAR(20)  NULL,
    urgency            VARCHAR(20)  NULL,
    buying_signal      VARCHAR(20)  NULL,
    confidence         DECIMAL(4,3) NOT NULL,
    extracted_json     JSON         NULL,
    questions_json     JSON         NULL,
    risks_json         JSON         NULL,
    recommended_action VARCHAR(500) NULL,
    reply_draft        MEDIUMTEXT   NULL,
    evidence_json      JSON         NULL,
    provider           VARCHAR(40)  NOT NULL,
    model              VARCHAR(120) NOT NULL,
    prompt_version     VARCHAR(40)  NOT NULL,
    token_input        INT          NULL,
    token_output       INT          NULL,
    cost_usd           DECIMAL(10,6) NULL,
    validated          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_analysis_message (message_id),
    CONSTRAINT fk_email_analysis_message FOREIGN KEY (message_id) REFERENCES email_messages (id) ON DELETE CASCADE,
    CONSTRAINT fk_email_analysis_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_email_analysis_conf CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_email_analysis_intent CHECK (intent IN ('PRICING_ENQUIRY','SERVICE_ENQUIRY','TECHNICAL_QUESTION',
        'MEETING_REQUEST','PROPOSAL_REQUEST','OBJECTION','NEGOTIATION','CUSTOMER_SUPPORT','COMPLAINT',
        'UNSUBSCRIBE','OUT_OF_OFFICE','BOUNCE','SPAM','GENERAL_RESPONSE','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_automation_rules (
    id                  CHAR(36)     NOT NULL,
    workspace_id        CHAR(36)     NOT NULL,
    name                VARCHAR(160) NOT NULL,
    priority            INT          NOT NULL DEFAULT 100,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    mode                VARCHAR(20)  NOT NULL,
    allowed_intents_json JSON        NULL,
    blocked_intents_json JSON        NULL,
    min_confidence      DECIMAL(4,3) NOT NULL DEFAULT 0.850,
    business_hours_json JSON         NULL,
    delay_seconds       INT          NOT NULL DEFAULT 0,
    account_id          CHAR(36)     NULL,
    created_by          CHAR(36)     NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at          DATETIME(6)  NULL,
    row_version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_email_rule_ws_name (workspace_id, name),
    CONSTRAINT fk_email_rule_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_email_rule_mode CHECK (mode IN ('AUTOMATIC','APPROVAL_REQUIRED','HUMAN','NO_RESPONSE')),
    CONSTRAINT ck_email_rule_conf CHECK (min_confidence >= 0 AND min_confidence <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE email_approvals (
    id               CHAR(36)     NOT NULL,
    workspace_id     CHAR(36)     NOT NULL,
    message_id       CHAR(36)     NOT NULL,
    conversation_id  CHAR(36)     NULL,
    analysis_id      CHAR(36)     NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reason_code      VARCHAR(60)  NULL,
    requested_by     CHAR(36)     NULL,
    decided_by       CHAR(36)     NULL,
    decided_at       DATETIME(6)  NULL,
    rejection_reason VARCHAR(1000) NULL,
    payload_hash     CHAR(64)     NOT NULL,
    expires_at       DATETIME(6)  NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_email_appr_status (workspace_id, status, created_at),
    CONSTRAINT fk_email_appr_message FOREIGN KEY (message_id) REFERENCES email_messages (id) ON DELETE CASCADE,
    CONSTRAINT fk_email_appr_analysis FOREIGN KEY (analysis_id) REFERENCES email_analysis (id),
    CONSTRAINT ck_email_appr_status CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Unified-inbox reads are always workspace scoped and ordered by recency.
CREATE INDEX idx_email_conv_recent ON email_conversations (workspace_id, last_message_at, status);
CREATE INDEX idx_email_msg_direction ON email_messages (workspace_id, direction, received_at);
