-- ============================================================================
-- V2026_09_07_04: Volume Drop menu entry.
--
-- /business/volume-drop — per-merchant month-over-month volume comparison:
-- last month full volume vs current MTD, projected month-end (linear + pace)
-- and the drop/gain each implies, with RM / lead ownership columns. Backed by
-- VolumeDropController over sum_daily_merchant. BUSINESS category,
-- display_order 24 (next free after Revenue Mix's 23 from V2026_09_07_03).
--
-- The API is gated by @menuAccess.canAccess('/business/volume-drop'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Volume Drop', '/business/volume-drop', 'TrendingDown', 'BUSINESS', 24
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/volume-drop');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/volume-drop'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- Grant to any uppercase-named admin groups if present (defensive,
-- mirrors other menu migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/volume-drop'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
