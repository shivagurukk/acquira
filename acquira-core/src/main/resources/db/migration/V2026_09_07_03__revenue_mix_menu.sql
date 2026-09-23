-- ============================================================================
-- V2026_09_07_03: Revenue Mix menu entry.
--
-- /business/revenue-mix — the finance team's Destination × Card Type P&L
-- matrix (Local / International × Debit / Credit …): #txns, volume and the
-- fee waterfall MSF → ICF → net revenue → scheme fee → net margin, plus net
-- spread on totals. Backed by RevenueMixController over sum_daily_full
-- (fee stack) + sum_daily_merchant (ancillary spread legs). BUSINESS
-- category, display_order 23 (next free after Industry Analytics' 22 from
-- V2026_09_07_02).
--
-- The API is gated by @menuAccess.canAccess('/business/revenue-mix'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Revenue Mix', '/business/revenue-mix', 'PieChart', 'BUSINESS', 23
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/revenue-mix');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/revenue-mix'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- Grant to any uppercase-named admin groups if present (defensive,
-- mirrors other menu migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/revenue-mix'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
