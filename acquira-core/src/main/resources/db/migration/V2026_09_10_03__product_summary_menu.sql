-- ============================================================================
-- V2026_09_10_03: Product Summary dashboard menu entry.
--
-- /executive/product-summary — per-tenant P&L broken down by revenue PRODUCT:
-- Acquiring · POS, Acquiring · ECOM, DCC, Rental and (when netspread.fx_enabled
-- is on) FX income — each with volume, MSF, interchange, scheme fee, net margin
-- and net spread, closed by a TOTAL row. Acquiring rows come from the
-- channel-grain sum_daily_full (same source as the channel selector); the
-- ancillary rows from sum_daily_merchant (same columns as the Net Spread page).
-- EXECUTIVE category, display_order 9 (next after Net Spread's 8).
--
-- The API is gated by @menuAccess.canAccess('/executive/product-summary'), so
-- this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Product Summary', '/executive/product-summary', 'PackageOpen', 'EXECUTIVE', 9
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/executive/product-summary');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/product-summary'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/product-summary'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
