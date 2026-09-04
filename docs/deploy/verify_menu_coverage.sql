-- ============================================================================
-- verify_menu_coverage.sql
-- Verifies sys_menu coverage against the frontend route table (App.jsx).
-- Read-only: safe to run on any environment.
--
--   psql -h <host> -p <port> -U postgres -d postgres -f verify_menu_coverage.sql
--
-- Sections:
--   1. Full menu listing by category
--   2. Routed pages with NO sys_menu row (unreachable via sidebar + menu-grant)
--   3. sys_menu rows pointing at paths with NO frontend route (dead menu items)
--   4. display_order collisions within a category
--   5. Duplicate paths in sys_menu
--   6. Menus with zero group grants (invisible to every user)
--
-- NOTE: the route list below is a manual copy of frontend/src/App.jsx.
-- When a route is added or removed there, update the app_routes CTE too.
-- Intentionally excluded (not sidebar pages): /login, /auth/sso/callback,
-- /change-password, /settings/:section, and the "/" redirect.
-- ============================================================================

\echo ''
\echo '=== 1. Current sys_menu by category ==='
SELECT category, display_order, menu_name, path
FROM sys_menu
ORDER BY category, display_order, menu_name;

-- Route table snapshot from frontend/src/App.jsx (2026-08-21)
DROP TABLE IF EXISTS app_routes;
CREATE TEMP TABLE app_routes (path text PRIMARY KEY);
INSERT INTO app_routes (path) VALUES
('/dashboard'),
('/business/ceo-volume-revenue'),
('/business/loss-making'),
('/executive/sales'),
('/executive/daily-merchant'),
('/merchants'),
('/transactions'),
('/merchant-summary'),
('/merchant/universe'),
('/merchant/insight-hub'),
('/trends/hub'),
('/business/dashboard'),
('/business/volume-revenue'),
('/business/merchant-financial'),
('/business/debit-prepaid'),
('/business/attrition'),
('/business/retention'),
('/business/forecasting'),
('/business/top-performers'),
('/business/zero-transaction'),
('/business/heatmap'),
('/business/daily-dashboard'),
('/business/destination-dashboard'),
('/business/card-type-dashboard'),
('/business/local-debit-bank-dashboard'),
('/business/merchant-analytics'),
('/business/comparison'),
('/business/pricing-simulator'),
('/business/opportunity'),
('/business/groups'),
('/explorer'),
('/analytics/interactive'),
('/ai-assistant'),
('/sales/executive'),
('/sales/team-management'),
('/sales/country-management'),
('/sales/agents'),
('/sales/leaderboard'),
('/sales/hierarchy'),
('/sales/targets'),
('/finance/dashboard'),
('/finance/summary'),
('/finance/lists'),
('/business/report-manager'),
('/upload'),
('/ops/server-file'),
('/ops/batch-logs'),
('/business/emails'),
('/business/revenue-leakage'),
('/settings'),
('/users'),
('/tenants'),
('/admin/groups'),
('/admin/smtp-settings'),
('/admin/s3-settings'),
('/admin/audit-logs'),
('/admin/backups'),
('/admin/integration'),
('/admin/integration/connections'),
('/admin/integration/reports'),
('/admin/integration/schedules'),
('/admin/integration/runs'),
('/admin/sso-settings'),
('/admin/email-campaigns'),
('/admin/data-migration'),
('/admin/tenant-provisioning'),
('/admin/security-settings'),
('/admin/maintenance'),
('/admin/bin-management'),
('/admin/alerts'),
('/admin/api-management'),
('/business/budget-targets'),
('/admin/interchange-normalization');

\echo ''
\echo '=== 2. Routed pages with NO sys_menu row (unreachable) ==='
SELECT r.path
FROM app_routes r
LEFT JOIN sys_menu m ON m.path = r.path
WHERE m.menu_id IS NULL
ORDER BY r.path;

\echo ''
\echo '=== 3. sys_menu rows with NO frontend route (dead menu items) ==='
SELECT m.category, m.menu_name, m.path
FROM sys_menu m
LEFT JOIN app_routes r ON r.path = m.path
WHERE r.path IS NULL
ORDER BY m.category, m.path;

\echo ''
\echo '=== 4. display_order collisions within a category ==='
SELECT category, display_order,
       string_agg(menu_name || ' (' || path || ')', ' | ' ORDER BY menu_name) AS colliding_items
FROM sys_menu
GROUP BY category, display_order
HAVING COUNT(*) > 1
ORDER BY category, display_order;

\echo ''
\echo '=== 5. Duplicate paths in sys_menu ==='
SELECT path, COUNT(*) AS row_count,
       string_agg(menu_name, ' | ' ORDER BY menu_id) AS names
FROM sys_menu
GROUP BY path
HAVING COUNT(*) > 1
ORDER BY path;

\echo ''
\echo '=== 6. Menus with zero group grants (invisible to every user) ==='
SELECT m.category, m.menu_name, m.path
FROM sys_menu m
LEFT JOIN sys_group_menu gm ON gm.menu_id = m.menu_id
WHERE gm.menu_id IS NULL
ORDER BY m.category, m.path;

\echo ''
\echo '=== Done. Sections 2-6 should ideally return zero rows. ==='
