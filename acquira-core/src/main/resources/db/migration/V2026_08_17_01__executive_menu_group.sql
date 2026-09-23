-- ============================================================================
-- V2026_08_17_01__executive_menu_group.sql
--
-- Regroups the executive-facing screens under the EXECUTIVE sidebar category
-- so the post-login landing page (/dashboard) and the five leadership reports
-- sit together at the top of the sidebar (Layout.jsx renders EXECUTIVE first).
--
-- Paths moved (ONLY these — everything else keeps its current category):
--   /dashboard                    -> EXECUTIVE 1  (renamed to 'Executive Dashboard')
--   /business/ceo-volume-revenue  -> EXECUTIVE 2
--   /business/loss-making         -> EXECUTIVE 3
--   /business/attrition           -> EXECUTIVE 4
--   /business/top-performers      -> EXECUTIVE 5
--   /sales/executive              -> EXECUTIVE 6
--
-- Paths are untouched, so no bookmark, route, or RBAC grant breaks; existing
-- sys_group_menu grants carry over because only category/order/name change.
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- ── 1. Make sure every row exists (older envs may miss the safety-net rows) ──
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Executive Dashboard', '/dashboard', 'LayoutDashboard', 'EXECUTIVE', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/dashboard');

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Volume & Revenue', '/business/ceo-volume-revenue', 'TrendingUp', 'EXECUTIVE', 2
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/ceo-volume-revenue');

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Loss-Making Merchants', '/business/loss-making', 'TrendingDown', 'EXECUTIVE', 3
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/loss-making');

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Attrition Report', '/business/attrition', 'TrendingDown', 'EXECUTIVE', 4
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/attrition');

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Top Performers', '/business/top-performers', 'Trophy', 'EXECUTIVE', 5
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/top-performers');

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Sales Hierarchy', '/sales/executive', 'LayoutDashboard', 'EXECUTIVE', 6
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/sales/executive');

-- ── 2. Move the six paths into EXECUTIVE with a stable order ─────────────────
UPDATE sys_menu SET category = 'EXECUTIVE', display_order = 1
WHERE path = '/dashboard';

UPDATE sys_menu SET category = 'EXECUTIVE', display_order = 2
WHERE path = '/business/ceo-volume-revenue';

UPDATE sys_menu SET category = 'EXECUTIVE', display_order = 3
WHERE path = '/business/loss-making';

UPDATE sys_menu SET category = 'EXECUTIVE', display_order = 4
WHERE path = '/business/attrition';

UPDATE sys_menu SET category = 'EXECUTIVE', display_order = 5
WHERE path = '/business/top-performers';

UPDATE sys_menu SET category = 'EXECUTIVE', display_order = 6
WHERE path = '/sales/executive';

-- ── 3. Rename the landing page so the sidebar item reads 'Executive Dashboard'
UPDATE sys_menu SET menu_name = 'Executive Dashboard'
WHERE path = '/dashboard' AND menu_name = 'Dashboard';

-- ── 4. /dashboard is the post-login landing page: every group must hold a
--       grant or menu-gated users would 403 straight after login. ─────────────
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/dashboard'
ON CONFLICT (group_id, menu_id) DO NOTHING;
