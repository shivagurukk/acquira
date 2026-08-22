-- ============================================================================
-- V2026_08_11_02: Executive Sales Pulse + Sales Targets menu entries.
--
-- Two screens:
--   /executive/sales  Executive Sales Pulse — the C-level read. EXECUTIVE
--                     category, display_order 0 so it sits above the existing
--                     Executive Dashboard / Volume & Revenue / Loss-Making
--                     entries. Visible to the executive-facing groups.
--   /sales/targets    Sales Targets — where an admin enters each agent's annual
--                     number. SALES category, and granted ONLY to the admin
--                     groups: a rep must not be able to edit their own target.
--
-- Also renames the existing '/sales/executive' entry from "Sales Executive" to
-- "Sales Hierarchy". That screen is the operational Country -> Team -> Agent
-- drill-down and is NOT being replaced; the rename exists because two sidebar
-- items called "Sales Executive" and "Executive Sales Pulse" are indistinguishable
-- at a glance. The path is untouched, so no bookmark or RBAC grant breaks.
--
-- Idempotent; splitter-safe (no $$). Listed in schema-locations after
-- schema.sql. On prod apply once via psql.
-- ============================================================================

-- ── Executive Sales Pulse ────────────────────────────────────────────────────
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Executive Sales Pulse', '/executive/sales', 'Activity', 'EXECUTIVE', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/executive/sales');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/sales'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- ── Sales Targets (admin-only) ───────────────────────────────────────────────
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Sales Targets', '/sales/targets', 'Target', 'SALES', 6
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/sales/targets');

-- Deliberately a NARROWER grant than the Pulse page above: entering targets is a
-- management control, and the API is guarded to ADMIN/SUPER_ADMIN to match.
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/sales/targets'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- ── Disambiguate the existing hierarchy screen ───────────────────────────────
UPDATE sys_menu
SET menu_name = 'Sales Hierarchy'
WHERE path = '/sales/executive'
  AND menu_name = 'Sales Executive';
