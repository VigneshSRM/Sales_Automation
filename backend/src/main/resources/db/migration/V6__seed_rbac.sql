-- =====================================================================
-- V6 — Capability catalogue, system roles and role→capability mapping.
--
-- System roles have workspace_id = NULL and are shared by every workspace; a
-- workspace may clone them to customise (Sales Ops). Role capability codes are
-- stored in role_capabilities.capability (an @ElementCollection on Role), and the
-- `capabilities` catalogue below is the authoritative list of codes the API checks.
-- =====================================================================

CREATE TABLE capabilities (
    code        VARCHAR(64)  NOT NULL,
    description VARCHAR(300) NOT NULL,
    module      VARCHAR(60)  NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO capabilities (code, description, module) VALUES
('leads.read','View leads within the allowed scope','leads'),
('leads.create','Capture leads','leads'),
('leads.update','Edit lead details','leads'),
('leads.assign','Assign or reassign lead owners','leads'),
('leads.qualify','Run scoring and qualification','leads'),
('leads.override_score','Override a computed score with a reason','leads'),
('leads.merge','Merge duplicate leads','leads'),
('leads.archive','Archive or delete leads','leads'),
('leads.export','Export lead data','leads'),
('accounts.read','View accounts','accounts'),
('accounts.create','Create accounts','accounts'),
('accounts.update','Edit accounts','accounts'),
('contacts.read','View contacts','contacts'),
('contacts.create','Create contacts','contacts'),
('contacts.update','Edit contacts','contacts'),
('opportunities.read','View opportunities and pipeline','opportunities'),
('opportunities.create','Create opportunities','opportunities'),
('opportunities.update','Edit opportunities','opportunities'),
('opportunities.transition','Move opportunities through stages','opportunities'),
('opportunities.close','Close opportunities as won or lost','opportunities'),
('activities.read','View record timelines','activities'),
('activities.create','Record calls, notes and meetings','activities'),
('tasks.manage','Create and complete tasks','tasks'),
('email.read','View email history','email'),
('email.compose','Compose and schedule email','email'),
('email.send','Send approved email','email'),
('email.approve','Approve outbound email','email'),
('templates.manage','Manage email templates','email'),
('sequences.manage','Manage follow-up sequences','email'),
('campaigns.manage','Manage campaigns','email'),
('smtp.manage','Configure SMTP profiles','email'),
('suppression.manage','Manage suppression and consent','email'),
('meetings.read','View meetings','meetings'),
('meetings.manage','Book, reschedule and cancel meetings','meetings'),
('calendly.manage','Manage the Calendly connection','meetings'),
('proposals.read','View proposals','commercial'),
('proposals.manage','Create and edit proposals','commercial'),
('proposals.approve','Approve proposals','commercial'),
('proposals.send','Send proposals','commercial'),
('ai.read','View AI agents and executions','ai'),
('ai.configure','Configure AI providers and agents','ai'),
('ai.run','Run AI agents','ai'),
('ai.approve','Decide AI approvals','ai'),
('workflows.read','View automations','automation'),
('workflows.manage','Create and edit automations','automation'),
('jobs.manage','Inspect and replay durable jobs','admin'),
('integrations.manage','Configure integrations','admin'),
('reports.read','View dashboards and reports','reports'),
('reports.export','Export reports','reports'),
('users.manage','Invite and manage users','admin'),
('roles.manage','Assign roles and capabilities','admin'),
('catalog.manage','Manage catalogues and scoring models','admin'),
('audit.read','Read the audit log','admin'),
('settings.manage','Manage workspace settings','admin'),
('imports.manage','Run data imports','admin');

INSERT INTO roles (id, workspace_id, code, name, description, system_role) VALUES
(UUID(), NULL, 'SUPER_ADMIN', 'Super Administrator', 'Full platform administration for the workspace.', TRUE),
(UUID(), NULL, 'SALES_DIRECTOR', 'Sales Director', 'Workspace-wide visibility of demand, pipeline and revenue with approval rights.', TRUE),
(UUID(), NULL, 'SALES_MANAGER', 'Sales Manager', 'Team-scoped visibility with assignment, approval and pipeline management.', TRUE),
(UUID(), NULL, 'SALES_REP', 'Sales Representative', 'Owns leads and opportunities; composes email for approval.', TRUE),
(UUID(), NULL, 'MARKETING_MANAGER', 'Marketing Manager', 'Owns campaigns, templates and sequences plus lead intake.', TRUE),
(UUID(), NULL, 'MARKETING_EXECUTIVE', 'Marketing Executive', 'Builds campaigns and sequences; cannot approve sends.', TRUE),
(UUID(), NULL, 'SALES_OPERATIONS', 'Sales Operations', 'Configures catalogues, scoring, automations and data quality.', TRUE),
(UUID(), NULL, 'TECHNICAL_CONSULTANT', 'Technical Consultant', 'Supports technical assessment and proposal scoping.', TRUE),
(UUID(), NULL, 'FINANCE_COMMERCIAL', 'Finance / Commercial', 'Reviews commercial terms, approvals and revenue reporting.', TRUE),
(UUID(), NULL, 'READ_ONLY_AUDITOR', 'Read-only / Auditor', 'Reads records and the audit log; no write access.', TRUE);

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r CROSS JOIN capabilities c WHERE r.code = 'SUPER_ADMIN';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','leads.create','leads.update','leads.assign','leads.qualify','leads.override_score',
 'leads.merge','leads.archive','leads.export','accounts.read','accounts.create','accounts.update',
 'contacts.read','contacts.create','contacts.update','opportunities.read','opportunities.create',
 'opportunities.update','opportunities.transition','opportunities.close','activities.read',
 'activities.create','tasks.manage','email.read','email.compose','email.send','email.approve',
 'templates.manage','sequences.manage','campaigns.manage','meetings.read','meetings.manage',
 'proposals.read','proposals.manage','proposals.approve','proposals.send','ai.read','ai.run',
 'ai.approve','workflows.read','reports.read','reports.export','users.manage','audit.read')
WHERE r.code = 'SALES_DIRECTOR';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','leads.create','leads.update','leads.assign','leads.qualify','leads.merge',
 'accounts.read','accounts.create','accounts.update','contacts.read','contacts.create','contacts.update',
 'opportunities.read','opportunities.create','opportunities.update','opportunities.transition',
 'activities.read','activities.create','tasks.manage','email.read','email.compose','email.send',
 'email.approve','meetings.read','meetings.manage','proposals.read','proposals.manage','proposals.send',
 'ai.read','ai.run','ai.approve','workflows.read','reports.read')
WHERE r.code = 'SALES_MANAGER';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','leads.create','leads.update','leads.qualify','accounts.read','contacts.read',
 'contacts.create','contacts.update','opportunities.read','opportunities.create','opportunities.update',
 'opportunities.transition','activities.read','activities.create','tasks.manage','email.read',
 'email.compose','meetings.read','meetings.manage','proposals.read','ai.read','ai.run','reports.read')
WHERE r.code = 'SALES_REP';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','leads.create','leads.update','leads.assign','leads.export','contacts.read',
 'accounts.read','activities.read','activities.create','tasks.manage','email.read','email.compose',
 'email.approve','templates.manage','sequences.manage','campaigns.manage','smtp.manage',
 'suppression.manage','ai.read','ai.run','workflows.read','reports.read','reports.export')
WHERE r.code = 'MARKETING_MANAGER';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','leads.create','contacts.read','accounts.read','activities.read','activities.create',
 'tasks.manage','email.read','email.compose','templates.manage','sequences.manage','campaigns.manage',
 'reports.read')
WHERE r.code = 'MARKETING_EXECUTIVE';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','leads.update','leads.assign','leads.qualify','leads.merge','leads.export',
 'accounts.read','contacts.read','opportunities.read','activities.read','tasks.manage','email.read',
 'templates.manage','sequences.manage','suppression.manage','meetings.read','proposals.read',
 'ai.read','ai.configure','workflows.read','workflows.manage','jobs.manage','integrations.manage',
 'catalog.manage','imports.manage','reports.read','reports.export','settings.manage','users.manage')
WHERE r.code = 'SALES_OPERATIONS';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','leads.update','accounts.read','contacts.read','opportunities.read','opportunities.update',
 'activities.read','activities.create','tasks.manage','meetings.read','proposals.read','proposals.manage',
 'ai.read','ai.run','reports.read')
WHERE r.code = 'TECHNICAL_CONSULTANT';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','accounts.read','opportunities.read','opportunities.close','proposals.read',
 'proposals.approve','reports.read','reports.export','audit.read')
WHERE r.code = 'FINANCE_COMMERCIAL';

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c ON c.code IN (
 'leads.read','accounts.read','contacts.read','opportunities.read','activities.read','email.read',
 'meetings.read','proposals.read','ai.read','workflows.read','reports.read','audit.read')
WHERE r.code = 'READ_ONLY_AUDITOR';
