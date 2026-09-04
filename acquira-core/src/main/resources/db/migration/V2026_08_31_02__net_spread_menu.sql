-- ============================================================================
-- V2026_08_31_02: Net Spread dashboard menu entry.
--
-- /executive/net-spread — replica of the Executive Daily Merchant Performance
-- layout at MERCHANT grain over sum_daily_merchant, extended with the
-- ancillary revenue columns (DCC acquirer share, rental income) and the
-- derived Net Spread = net margin + DCC acquirer share + rental income.
-- EXECUTIVE category, display_order 8 (next after Daily Merchant
-- Performance's 7 from V2026_08_19_02).
--
-- The API is gated by @menuAccess.canAccess('/executive/net-spread'), so this
-- grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Net Spread', '/executive/net-spread', 'Layers', 'EXECUTIVE', 8
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/executive/net-spread');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/net-spread'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/net-spread'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
