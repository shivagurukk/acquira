-- ============================================================================
-- V2026_09_11_02: DIMP (Data Integrity Monitoring Program) dashboard menu entry.
--
-- /interchange/dimp — a per-tenant Data Integrity assessment for Mastercard
-- acquiring clearing: the 24 Acquirer Clearing (1240) DIMP edits with their
-- status (implemented / requires-auth / not-implemented), per-edit violation
-- counts and rates, and a by-month trend. Third row in the INTERCHANGE section.
--
-- Gated by @menuAccess.canAccess('/interchange/dimp'); this grant IS the access
-- control. Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'DIMP Dashboard', '/interchange/dimp', 'ShieldCheck', 'INTERCHANGE', 3
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/interchange/dimp');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/interchange/dimp'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/interchange/dimp'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
