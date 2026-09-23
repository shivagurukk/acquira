-- ============================================================================
-- V2026_08_19_03: Card Type Dashboard menu entry.
--
-- /business/card-type-dashboard — Credit / Debit / Prepaid split screen, the
-- card-type replica of the Destination Dashboard, backed by
-- CardTypeDashboardController over sum_daily_full (settlement basis + full
-- fee stack). BUSINESS category, display_order 19 (next free after
-- Destination Dashboard's 18 from V2026_08_15_01).
--
-- The API is gated by @menuAccess.canAccess('/business/card-type-dashboard'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Card Type Dashboard', '/business/card-type-dashboard', 'CreditCard', 'BUSINESS', 19
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/card-type-dashboard');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/card-type-dashboard'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- Grant to any uppercase-named admin groups if present (defensive,
-- mirrors other menu migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/card-type-dashboard'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
