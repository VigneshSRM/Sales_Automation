-- =====================================================================
-- V9 — capabilities for the two-way email module (addendum 3.1, 3.4, 4)
--      Added in a new migration rather than editing the applied V6 seed.
-- =====================================================================

INSERT INTO capabilities (code, description, module) VALUES
('email.accounts.manage', 'Configure, test, enable and disable inbound mailbox connections', 'email'),
('email.accounts.read', 'View inbound mailbox connection status and sync health', 'email'),
('email.conversations.read', 'Read the unified inbox and conversations', 'email'),
('email.conversations.assign', 'Assign conversations to users and queues', 'email'),
('email.analyze', 'Run or re-run AI analysis of an inbound message', 'email'),
('email.rules.manage', 'Configure AI response policy rules and automation modes', 'email'),
('email.drafts.approve', 'Approve or reject AI-drafted replies before sending', 'email'),
('email.attachments.read', 'Download attachments from inbound messages', 'email');

-- Super administrators and sales directors already hold every capability through the
-- CROSS JOIN in V6 only for rows present at that time, so re-grant explicitly here.
INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c
   ON c.code IN ('email.accounts.manage','email.accounts.read','email.conversations.read',
                 'email.conversations.assign','email.analyze','email.rules.manage',
                 'email.drafts.approve','email.attachments.read')
 WHERE r.code IN ('SUPER_ADMIN','SALES_DIRECTOR');

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c
   ON c.code IN ('email.accounts.read','email.conversations.read','email.conversations.assign',
                 'email.analyze','email.drafts.approve','email.attachments.read')
 WHERE r.code IN ('SALES_MANAGER');

INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, c.code FROM roles r JOIN capabilities c
   ON c.code IN ('email.conversations.read','email.analyze','email.attachments.read')
 WHERE r.code IN ('SALES_REP','MARKETING_MANAGER','MARKETING_EXECUTIVE','SALES_OPERATIONS',
                  'TECHNICAL_CONSULTANT','FINANCE_COMMERCIAL','READ_ONLY_AUDITOR');
