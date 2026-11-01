-- =====================================================================
-- V7 — Commercial: price book, proposals, customers, onboarding,
--      renewals, expansion, report definitions
-- =====================================================================

CREATE TABLE price_book_items (
    id                CHAR(36)      NOT NULL,
    workspace_id      CHAR(36)      NOT NULL,
    service_id        CHAR(36)      NULL,
    sku               VARCHAR(60)   NOT NULL,
    name              VARCHAR(200)  NOT NULL,
    unit              VARCHAR(40)   NOT NULL DEFAULT 'PACKAGE',
    list_price        DECIMAL(15,2) NOT NULL,
    currency          CHAR(3)       NOT NULL,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version       BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_price_book_sku (workspace_id, sku),
    CONSTRAINT fk_price_book_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_price_book_service FOREIGN KEY (service_id) REFERENCES services (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE proposals (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    opportunity_id    CHAR(36)     NOT NULL,
    account_id        CHAR(36)     NOT NULL,
    title             VARCHAR(300) NOT NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    active_version    INT          NULL,
    created_by        CHAR(36)     NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_proposals_opp (opportunity_id),
    CONSTRAINT fk_proposals_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_proposals_opp FOREIGN KEY (opportunity_id) REFERENCES opportunities (id),
    CONSTRAINT fk_proposals_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_proposals_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','SENT','ACCEPTED','REJECTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE proposal_versions (
    id                CHAR(36)      NOT NULL,
    workspace_id      CHAR(36)      NOT NULL,
    proposal_id       CHAR(36)      NOT NULL,
    version           INT           NOT NULL,
    scope_summary     VARCHAR(4000) NOT NULL,
    line_items_json   JSON          NOT NULL,
    total_amount      DECIMAL(15,2) NOT NULL,
    currency          CHAR(3)       NOT NULL,
    valid_until       DATE          NULL,
    terms             VARCHAR(8000) NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    approved_by       CHAR(36)      NULL,
    approved_at       DATETIME(6)   NULL,
    created_by        CHAR(36)      NULL,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_proposal_version (proposal_id, version),
    CONSTRAINT fk_proposal_version_prop FOREIGN KEY (proposal_id) REFERENCES proposals (id) ON DELETE CASCADE,
    CONSTRAINT ck_proposal_version_status CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE proposal_sends (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    proposal_id       CHAR(36)     NOT NULL,
    proposal_version_id CHAR(36)   NOT NULL,
    email_message_id  CHAR(36)     NULL,
    sent_to           VARCHAR(254) NOT NULL,
    sent_by           CHAR(36)     NOT NULL,
    sent_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    delivery_status   VARCHAR(30)  NOT NULL DEFAULT 'QUEUED',
    detail            VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    KEY idx_proposal_sends (proposal_id, sent_at),
    CONSTRAINT fk_proposal_send_prop FOREIGN KEY (proposal_id) REFERENCES proposals (id) ON DELETE CASCADE,
    CONSTRAINT fk_proposal_send_version FOREIGN KEY (proposal_version_id) REFERENCES proposal_versions (id),
    CONSTRAINT fk_proposal_send_email FOREIGN KEY (email_message_id) REFERENCES email_messages (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customers (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    account_id        CHAR(36)     NOT NULL,
    opportunity_id    CHAR(36)     NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT 'ONBOARDING',
    start_date        DATE         NULL,
    renewal_date      DATE         NULL,
    contract_value    DECIMAL(15,2) NULL,
    currency          CHAR(3)      NULL,
    owner_user_id     CHAR(36)     NULL,
    notes             VARCHAR(4000) NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customers_account (workspace_id, account_id),
    CONSTRAINT fk_customers_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_customers_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_customers_status CHECK (status IN ('ONBOARDING','ACTIVE','AT_RISK','CHURNED','RENEWED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE onboarding_checklists (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    customer_id       CHAR(36)     NOT NULL,
    template_name     VARCHAR(160) NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_onboarding_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE onboarding_tasks (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    checklist_id      CHAR(36)     NOT NULL,
    title             VARCHAR(300) NOT NULL,
    owner_user_id     CHAR(36)     NULL,
    due_at            DATETIME(6)  NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    completed_at      DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_onboarding_task_checklist FOREIGN KEY (checklist_id) REFERENCES onboarding_checklists (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE renewal_reminders (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    customer_id       CHAR(36)     NOT NULL,
    due_date          DATE         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    owner_user_id     CHAR(36)     NULL,
    notes             VARCHAR(1000) NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_renewals_due (workspace_id, status, due_date),
    CONSTRAINT fk_renewals_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE expansion_suggestions (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    customer_id       CHAR(36)     NOT NULL,
    service_id        CHAR(36)     NULL,
    rationale         VARCHAR(2000) NOT NULL,
    confidence        DECIMAL(4,3) NOT NULL DEFAULT 0.500,
    status            VARCHAR(20)  NOT NULL DEFAULT 'SUGGESTED',
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_expansion_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE report_definitions (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    code              VARCHAR(60)  NOT NULL,
    name              VARCHAR(160) NOT NULL,
    description       VARCHAR(1000) NULL,
    query_spec_json   JSON         NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_code (workspace_id, code),
    CONSTRAINT fk_reports_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE saved_views (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    user_id           CHAR(36)     NULL,
    entity_type       VARCHAR(30)  NOT NULL,
    name              VARCHAR(120) NOT NULL,
    filters_json      JSON         NOT NULL,
    shared            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_saved_view (workspace_id, entity_type, user_id, name),
    CONSTRAINT fk_saved_views_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
