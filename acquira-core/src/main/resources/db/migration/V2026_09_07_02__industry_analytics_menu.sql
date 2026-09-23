-- ============================================================================
-- V2026_09_07_02: Industry Analytics menu entry.
--
-- /business/industry-analytics — the acquiring P&L split by industry (MCC
-- sector, ref_mcc_category vocabulary): volume, MSF, ICF, scheme fee, net
-- revenue and net spread with %-of-volume. Backed by
-- IndustryAnalyticsController over sum_daily_full (fee stack) +
-- sum_daily_merchant (ancillary spread legs). BUSINESS category,
-- display_order 22 (next free after Rentals' 21 from V2026_08_29_02).
--
-- The API is gated by @menuAccess.canAccess('/business/industry-analytics'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Industry Analytics', '/business/industry-analytics', 'Factory', 'BUSINESS', 22
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/industry-analytics');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/industry-analytics'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- Grant to any uppercase-named admin groups if present (defensive,
-- mirrors other menu migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/industry-analytics'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
