-- V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql
-- Sidebar reorganization (sys_menu is DB-driven; schema.sql seeds are
-- ON CONFLICT (path) DO NOTHING and never UPDATE an existing row, and prod
-- runs spring.sql.init.mode=never — so these changes MUST land as explicit
-- idempotent statements here).
--
--   1. Move "Finance Summary" (/finance/summary) from the FINANCE group to
--      BUSINESS. The route/page are unchanged; only the sidebar grouping moves.
--   2. Remove "Performance Trends" (/business/performance) from the sidebar
--      entirely — delete its group grants first (FK), then the menu row.
--
-- Idempotent: UPDATE ... WHERE is naturally repeatable; the DELETEs match
-- nothing on a second run. No DO $$ blocks (ScriptUtils splitter-safe).

-- 1. Finance Summary -> BUSINESS group. display_order 17 parks it after the
--    post-seed Business pages (Retention 15 / Forecasting 16) without colliding.
UPDATE sys_menu
   SET category = 'BUSINESS',
       display_order = 17
 WHERE path = '/finance/summary';

-- 2. Drop Performance Trends. Remove grants first to satisfy the
--    sys_group_menu.menu_id FK, then the menu row itself.
DELETE FROM sys_group_menu
 WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE path = '/business/performance');

DELETE FROM sys_menu
 WHERE path = '/business/performance';
