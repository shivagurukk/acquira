-- ============================================================================
-- V2026_08_29_02: Rentals screen menu entry.
--
-- /business/rentals — terminal/store/merchant rental charges from the
-- dedicated rental feed (fact_rental), level tabs driven by the tenant's
-- input_format (CMM = store only, AMS = merchant/store/terminal).
-- BUSINESS category, display_order 21 (next free after Local Debit Banks'
-- 20 from V2026_08_20_02).
--
-- The API is gated by @menuAccess.canAccess('/business/rentals'), so this
-- grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Rentals', '/business/rentals', 'Receipt', 'BUSINESS', 21
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/rentals');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/rentals'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/rentals'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
