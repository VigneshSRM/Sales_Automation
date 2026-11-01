-- =====================================================================
-- V4 — Meetings and Calendly integration
-- =====================================================================

CREATE TABLE calendly_connections (
    id                    CHAR(36)     NOT NULL,
    workspace_id          CHAR(36)     NOT NULL,
    account_label         VARCHAR(160) NOT NULL,
    calendly_user_uri     VARCHAR(300) NOT NULL,
    organization_uri      VARCHAR(300) NULL,
    token_ciphertext      VARCHAR(2048) NOT NULL,
    token_key_version     INT          NOT NULL DEFAULT 1,
    signing_key_ciphertext VARCHAR(2048) NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    last_verified_at      DATETIME(6)  NULL,
    last_error            VARCHAR(1000) NULL,
    webhook_subscription_uri VARCHAR(300) NULL,
    created_by            CHAR(36)     NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version           BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_calendly_ws_user (workspace_id, calendly_user_uri),
    CONSTRAINT fk_calendly_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_calendly_status CHECK (status IN ('PENDING','ACTIVE','ERROR','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE calendly_event_types (
    id                    CHAR(36)     NOT NULL,
    workspace_id          CHAR(36)     NOT NULL,
    connection_id         CHAR(36)     NOT NULL,
    external_uri          VARCHAR(300) NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    duration_minutes      INT          NOT NULL,
    scheduling_url        VARCHAR(500) NOT NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    synced_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_calendly_event_type (workspace_id, external_uri),
    CONSTRAINT fk_calendly_event_type_conn FOREIGN KEY (connection_id) REFERENCES calendly_connections (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE calendly_webhook_events (
    id                    CHAR(36)     NOT NULL,
    workspace_id          CHAR(36)     NULL,
    provider_event_id     VARCHAR(300) NOT NULL,
    event_type            VARCHAR(80)  NOT NULL,
    signature_valid       BOOLEAN      NOT NULL DEFAULT FALSE,
    payload_json          JSON         NOT NULL,
    processed_at          DATETIME(6)  NULL,
    processing_status     VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    processing_error      VARCHAR(1000) NULL,
    received_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_calendly_event (provider_event_id),
    KEY idx_calendly_events_status (processing_status, received_at),
    CONSTRAINT ck_calendly_events_status CHECK (processing_status IN ('RECEIVED','PROCESSED','FAILED','IGNORED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE meetings (
    id                    CHAR(36)     NOT NULL,
    workspace_id          CHAR(36)     NOT NULL,
    lead_id               CHAR(36)     NULL,
    account_id            CHAR(36)     NULL,
    contact_id            CHAR(36)     NULL,
    opportunity_id        CHAR(36)     NULL,
    owner_user_id         CHAR(36)     NULL,
    external_event_uri    VARCHAR(300) NOT NULL,
    external_event_type   VARCHAR(300) NULL,
    title                 VARCHAR(300) NOT NULL,
    status                VARCHAR(30)  NOT NULL DEFAULT 'SCHEDULED',
    start_at              DATETIME(6)  NOT NULL,
    end_at                DATETIME(6)  NOT NULL,
    timezone              VARCHAR(64)  NOT NULL DEFAULT 'Asia/Kolkata',
    location              VARCHAR(400) NULL,
    meeting_url           VARCHAR(500) NULL,
    invitee_email         VARCHAR(254) NULL,
    invitee_name          VARCHAR(200) NULL,
    rescheduled_from_uri  VARCHAR(300) NULL,
    cancelled_at          DATETIME(6)  NULL,
    cancellation_reason   VARCHAR(500) NULL,
    notes                 VARCHAR(4000) NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    row_version           BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_meetings_external (workspace_id, external_event_uri),
    KEY idx_meetings_time (workspace_id, start_at),
    KEY idx_meetings_lead (lead_id, start_at),
    CONSTRAINT fk_meetings_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT fk_meetings_lead FOREIGN KEY (lead_id) REFERENCES leads (id),
    CONSTRAINT fk_meetings_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_meetings_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunities (id),
    CONSTRAINT ck_meetings_status CHECK (status IN ('SCHEDULED','RESCHEDULED','CANCELLED','COMPLETED','NO_SHOW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE meeting_history (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    meeting_id        CHAR(36)     NOT NULL,
    change_type       VARCHAR(30)  NOT NULL,
    detail            VARCHAR(1000) NULL,
    actor_label       VARCHAR(200) NULL,
    occurred_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_meeting_history (meeting_id, occurred_at),
    CONSTRAINT fk_meeting_history_meeting FOREIGN KEY (meeting_id) REFERENCES meetings (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
