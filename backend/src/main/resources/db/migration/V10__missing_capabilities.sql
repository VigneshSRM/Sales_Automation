-- =====================================================================
-- V10 — capabilities that the code enforces but V6 never seeded
--
-- Found by `tools/capability_audit.py` (used-but-not-seeded direction). Without
-- these rows every caller of the guarded path received 403 forever:
--   * leads.reopen        — reopening a disqualified/archived lead
--   * opportunities.assign — reassigning an opportunity owner
--   * notifications.read  — listing/marking notifications (every user needs it)
--
-- Forward-only: V6 is never edited once released.
-- =====================================================================

INSERT INTO capabilities (code, description, module) VALUES
('leads.reopen', 'Reopen a closed, disqualified or archived lead', 'leads'),
('opportunities.assign', 'Assign or reassign opportunity owners', 'opportunities'),
('notifications.read', 'Read and mark notifications as read', 'notifications');

-- Every authenticated user reads their own notifications, so every role gets it.
INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, 'notifications.read' FROM roles r;

-- Reopening is a reversible correction of lead state: sales staff and above.
INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, 'leads.reopen' FROM roles r
 WHERE r.code IN ('SUPER_ADMIN','SALES_DIRECTOR','SALES_MANAGER','SALES_REP','SALES_OPERATIONS');

-- Reassigning an opportunity transfers accountability for revenue: managers and above.
INSERT INTO role_capabilities (role_id, capability)
SELECT r.id, 'opportunities.assign' FROM roles r
 WHERE r.code IN ('SUPER_ADMIN','SALES_DIRECTOR','SALES_MANAGER','SALES_OPERATIONS');
