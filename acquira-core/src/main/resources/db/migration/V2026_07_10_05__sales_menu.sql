-- ============================================================
-- V2026_07_10_05: Sales suite menu entries
-- Routes for all five Sales screens exist in App.jsx and their
-- controllers (SalesTeamController, SalesCountryLeadController,
-- SalesAgentProfileController, LeaderboardController,
-- SalesPortfolioController) are all live — but only Team Management
-- and Leaderboard ever had sys_menu rows (from an earlier seed), so
-- Country Leads, Agent Directory, and the Sales Hierarchy Explorer
-- were unreachable from the sidebar.
--
-- Registers the full SALES category, ordered to match the natural
-- hierarchy drill-down: Team Management -> Country Leads -> Agent
-- Directory -> Leaderboard -> Hierarchy.
--
-- Idempotent: ON CONFLICT guards make re-runs a no-op (prod runs
-- spring.sql.init.mode=never, so this migration is the landing path).
-- This is also mirrored in MenuController.ensureMenusExist() as a
-- startup safety net, matching the Budget Targets pattern
-- (V2026_07_02_01__budget_targets_menu.sql).
-- ============================================================

-- 1. Insert menu items (idempotent per path)
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
  ('Sales Team Management', '/sales/team-management',    'Users',   'SALES', 1),
  ('Country Leads',         '/sales/country-management', 'Globe',   'SALES', 2),
  ('Agent Directory',       '/sales/agents',              'Contact', 'SALES', 3),
  ('Sales Leaderboard',     '/sales/leaderboard',          'Trophy',  'SALES', 4),
  ('Sales Hierarchy',       '/sales/hierarchy',            'Network', 'SALES', 5)
ON CONFLICT (path) DO NOTHING;

-- 2. Grant to Super Admin + Bank Admin (seeded group names on prod)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path IN (
    '/sales/team-management', '/sales/country-management',
    '/sales/agents', '/sales/leaderboard', '/sales/hierarchy'
  )
ON CONFLICT DO NOTHING;

-- 3. Grant to any uppercase-named admin groups if present (defensive,
--    mirrors other migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path IN (
    '/sales/team-management', '/sales/country-management',
    '/sales/agents', '/sales/leaderboard', '/sales/hierarchy'
  )
ON CONFLICT DO NOTHING;
