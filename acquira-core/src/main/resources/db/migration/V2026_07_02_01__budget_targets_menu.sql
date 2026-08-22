-- ============================================================
-- V2026_07_02_01: Budget Targets menu entry
-- Actual-vs-budget attainment page. Targets are entered here and
-- compared against sum_monthly_bank actuals by BudgetTargetController.
-- Route: /business/budget-targets  (ADMIN / SUPER_ADMIN only)
-- Placed in the BUSINESS category, after the analytics screens.
-- Idempotent: ON CONFLICT guards make re-runs a no-op (prod runs
-- spring.sql.init.mode=never, so this migration is the landing path).
-- ============================================================

-- 1. Insert menu item
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Budget Targets', '/business/budget-targets', 'Target', 'BUSINESS', 18)
ON CONFLICT (path) DO NOTHING;

-- 2. Grant to Super Admin + Bank Admin (seeded group names on prod)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path = '/business/budget-targets'
ON CONFLICT DO NOTHING;

-- 3. Grant to any uppercase-named admin groups if present (defensive,
--    mirrors other migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path = '/business/budget-targets'
ON CONFLICT DO NOTHING;
