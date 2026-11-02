-- =====================================================================
-- V6 — Workflow automation (trigger → condition → action) with run history
-- =====================================================================

CREATE TABLE workflows (
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
    UNIQUE KEY uk_workflows_ws_name (workspace_id, name),
    CONSTRAINT fk_workflows_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id),
    CONSTRAINT ck_workflows_status CHECK (status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workflow_versions (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    workflow_id       CHAR(36)     NOT NULL,
    version           INT          NOT NULL,
    trigger_type      VARCHAR(60)  NOT NULL,
    trigger_config    JSON         NOT NULL,
    conditions_json   JSON         NOT NULL,
    actions_json      JSON         NOT NULL,
    max_runs_per_object_per_day INT NOT NULL DEFAULT 5,
    status            VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_by        CHAR(36)     NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_version (workflow_id, version),
    CONSTRAINT fk_workflow_version_wf FOREIGN KEY (workflow_id) REFERENCES workflows (id) ON DELETE CASCADE,
    CONSTRAINT ck_trigger_type CHECK (trigger_type IN ('LEAD_CREATED','LEAD_QUALIFIED','EMAIL_RECEIVED',
        'EMAIL_BOUNCED','MEETING_BOOKED','MEETING_CANCELLED','STAGE_CHANGED','PROPOSAL_SENT','DEAL_WON',
        'FOLLOW_UP_OVERDUE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workflow_runs (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    workflow_id       CHAR(36)     NOT NULL,
    workflow_version_id CHAR(36)   NOT NULL,
    trigger_type      VARCHAR(60)  NOT NULL,
    trigger_object_type VARCHAR(30) NOT NULL,
    trigger_object_id CHAR(36)     NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    depth             INT          NOT NULL DEFAULT 0,
    root_run_id       CHAR(36)     NULL,
    active_key        VARCHAR(160) NULL,
    started_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at       DATETIME(6)  NULL,
    error_detail      VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_run_active (workspace_id, active_key),
    KEY idx_workflow_runs (workspace_id, started_at),
    CONSTRAINT fk_wf_run_wf FOREIGN KEY (workflow_id) REFERENCES workflows (id),
    CONSTRAINT ck_wf_run_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','SKIPPED','THROTTLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE workflow_run_steps (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NOT NULL,
    run_id            CHAR(36)     NOT NULL,
    step_no           INT          NOT NULL,
    action_type       VARCHAR(60)  NOT NULL,
    action_config     JSON         NULL,
    status            VARCHAR(20)  NOT NULL,
    detail            VARCHAR(2000) NULL,
    started_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_step (run_id, step_no),
    CONSTRAINT fk_run_step_run FOREIGN KEY (run_id) REFERENCES workflow_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE scheduled_jobs_config (
    id                CHAR(36)     NOT NULL,
    workspace_id      CHAR(36)     NULL,
    job_code          VARCHAR(80)  NOT NULL,
    cron_expression   VARCHAR(80)  NOT NULL,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at       DATETIME(6)  NULL,
    last_status       VARCHAR(20)  NULL,
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_scheduled_job (job_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
