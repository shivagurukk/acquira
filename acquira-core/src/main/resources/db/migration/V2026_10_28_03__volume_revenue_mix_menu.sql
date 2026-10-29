-- ============================================================================
-- V2026_10_28_03: Volume & Revenue Mix menu entry.
--
-- /executive/volume-revenue-mix — the Volume & Revenue screen re-cut by
-- DIMENSION instead of MID x SID: the reader groups by any combination of
-- Destination, MCC and Scheme, each row carrying count, settlement volume,
-- MSF, interchange, scheme fee, ECOM fee, net margin and net margin %, closed
-- by a TOTAL row. Reads sum_daily_full (the only summary carrying those three
-- dimensions WITH the fee stack), so its TOTAL ties out with Volume & Revenue
-- for the same period and channel. No ancillary (DCC / rental / FX) columns:
-- those have no destination / MCC / scheme of their own.
-- EXECUTIVE category, display_order 10 (next after Product Summary's 9).
--
-- The API is gated by @menuAccess.canAccess('/executive/volume-revenue-mix'),
-- so this grant IS the access control, not just sidebar visibility.
-- RbacCatalogSync inserts the same row from rbac/menu-catalog.json at boot;
-- this script exists so prod can be seeded ahead of the deploy via psql.
--
-- Granted to Super Admin + Bank Admin. Idempotent; splitter-safe (no $$).
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Volume & Revenue Mix', '/executive/volume-revenue-mix', 'PieChart', 'EXECUTIVE', 10
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/executive/volume-revenue-mix');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/volume-revenue-mix'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/volume-revenue-mix'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_10_28_03__volume_revenue_mix_menu.sql') ON CONFLICT (filename) DO NOTHING;
