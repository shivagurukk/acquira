-- ============================================================================
-- V2026_08_15_01: Destination Dashboard menu entry.
--
-- /business/destination-dashboard — Domestic vs International split screen
-- backed by DestinationDashboardController (endpoints existed since
-- V2026_07_10_03 era; this migration ships alongside the first frontend
-- screen). BUSINESS category, display_order 18 (next free after the
-- MenuController-seeded Retention 15 / Forecasting 16 / Top Performers 17).
--
-- The API is gated by @menuAccess.canAccess('/business/destination-dashboard'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Idempotent; splitter-safe (no $$). Listed in spring.sql.init
-- schema-locations after schema.sql. On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Destination Dashboard', '/business/destination-dashboard', 'Globe', 'BUSINESS', 18
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/destination-dashboard');

-- Grant to Super Admin + Bank Admin (seeded group names on prod)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path = '/business/destination-dashboard'
ON CONFLICT DO NOTHING;

-- Grant to any uppercase-named admin groups if present (defensive,
-- mirrors other menu migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path = '/business/destination-dashboard'
ON CONFLICT DO NOTHING;
