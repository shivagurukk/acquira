-- ============================================================================
-- V2026_08_08_01: Remove Executive Dashboard v2 (screen retired).
--
-- The /business/executive-dashboard-v2 route, its page component, and the
-- backing /api/dashboard/v2 controller were deleted from the codebase. This
-- removes the menu entry (and its group grants, via the child table first)
-- from databases that were seeded while the screen existed. The schema.sql
-- seed row was removed in the same change, so fresh installs never get it.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

DELETE FROM sys_group_menu
WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE path = '/business/executive-dashboard-v2');

DELETE FROM sys_menu WHERE path = '/business/executive-dashboard-v2';
