-- ============================================================================
-- V2026_08_19_02: Executive Daily Merchant Dashboard menu entry.
--
-- Registers /executive/daily-merchant under the EXECUTIVE category (order 7,
-- after the six screens grouped by V2026_08_17_01). New page: single business
-- date + acquiring filters over sum_daily_full with the full fee set
-- (Vol/Count/MSF/ICF/SF/PG/NM). Distinct from /business/daily-dashboard (the
-- month heat-grid), which is untouched.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Daily Merchant Performance', '/executive/daily-merchant', 'CalendarClock', 'EXECUTIVE', 7
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/executive/daily-merchant');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/daily-merchant'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;
