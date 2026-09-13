-- ============================================================================
-- V2026_09_10_02: Integration Hub — one sidebar entry, four tabs inside.
--
-- The DATA INTEGRATION group listed FIVE menu rows (Integration Hub + DB
-- Connections + Report Configs + Schedules + Run History) that all opened the
-- same page on different tabs. Collapse to the single "Integration Hub" row;
-- Connections / Report configs / Schedules / Run history stay as tabs within
-- the hub. The deep-link routes remain in App.jsx, they just leave the sidebar.
--
-- The four sub-rows were only ever created by MenuController's startup safety
-- net (no migration made them); that safety net no longer lists them, so this
-- one-time delete is all that's needed.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

DELETE FROM sys_group_menu
 WHERE menu_id IN (
   SELECT menu_id FROM sys_menu
    WHERE path IN (
      '/admin/integration/connections',
      '/admin/integration/reports',
      '/admin/integration/schedules',
      '/admin/integration/runs'
    )
 );

DELETE FROM sys_menu
 WHERE path IN (
   '/admin/integration/connections',
   '/admin/integration/reports',
   '/admin/integration/schedules',
   '/admin/integration/runs'
 );
