-- ============================================================================
-- V2026_07_05_04: Loss-Making Merchants report menu entry (Executive).
--
-- Registers /business/loss-making under the EXECUTIVE category so the sidebar
-- shows three Executive screens: Dashboard, Volume & Revenue, Loss-Making
-- Merchants. Reuses the ceo-volume-revenue endpoint with lossOnly=true; no new
-- backend endpoint. Granted to Super Admin + Bank Admin (sidebar is DB-driven).
--
-- Idempotent; splitter-safe (no $$). Listed in schema-locations after
-- schema.sql. On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Loss-Making Merchants', '/business/loss-making', 'TrendingDown', 'EXECUTIVE', 4
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/loss-making');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/loss-making'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;
