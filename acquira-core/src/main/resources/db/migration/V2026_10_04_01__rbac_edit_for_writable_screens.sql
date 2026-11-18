-- ============================================================================
-- V2026_10_04_01: EDIT permission for writable screens that V2026_09_26_02 did
-- not seed (finding M1 — action levels never enforced).
--
-- WHY: the mutating endpoints behind Data Explorer (saved report templates,
-- report schedules, threshold alerts, governed master items) are now gated on
-- @perm.can('explorer','EDIT').  V2026_09_26_02 seeded EDIT only for the
-- ADMINISTRATION / OPERATIONS / DATA INTEGRATION / SALES / MERCHANT MGT
-- categories (+ two BUSINESS exceptions), and /explorer is BUSINESS, so without
-- this row no group could ever be granted the write and every admin would be
-- read-only on that screen.
--
-- WHAT:
--   1. sys_permission gets '<menu_key>:EDIT' for the listed keys.
--   2. Backfill mirrors V2026_09_26_02 "Backfill 2", restricted to these keys:
--      the admin groups receive EDIT wherever they already hold VIEW.  Every
--      other group lands read-only, which is the safe direction to be wrong in.
--
-- The same keys carry EDIT in rbac/menu-catalog.json, so RbacCatalogSync
-- creates the permission row on a fresh database too; this script exists so
-- the GRANT backfill is applied once on an existing environment.
--
-- Idempotent; splitter-safe (no dollar-quoting).  On prod apply once via psql.
-- Requires V2026_09_26_02 (sys_permission / tenant_group_perm).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Catalog rows
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (perm_code, menu_key, action, description)
SELECT m.menu_key || ':EDIT', m.menu_key, 'EDIT', 'Modify data on ' || m.menu_name
FROM sys_menu m
WHERE m.menu_key IN ('explorer')
ON CONFLICT (perm_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Backfill: EDIT follows the VIEW grant for the admin groups only
--    (copy of V2026_09_26_02 Backfill 2, restricted to the keys above).
-- ---------------------------------------------------------------------------
INSERT INTO tenant_group_perm (tenant_id, group_id, perm_code, effect, granted_by)
SELECT v.tenant_id, v.group_id, p.perm_code, 'ALLOW', 'V2026_10_04_01'
FROM tenant_group_perm v
JOIN sys_permission pv ON pv.perm_code = v.perm_code AND pv.action = 'VIEW'
JOIN sys_permission p  ON p.menu_key   = pv.menu_key AND p.action = 'EDIT'
JOIN sys_user_group g  ON g.group_id   = v.group_id
WHERE v.effect = 'ALLOW'
  AND pv.menu_key IN ('explorer')
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (tenant_id, group_id, perm_code) DO NOTHING;

-- Make the new grants visible without a restart (evaluator cache key).
UPDATE rbac_version SET version = version + 1, updated_at = CURRENT_TIMESTAMP;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_10_04_01__rbac_edit_for_writable_screens.sql') ON CONFLICT (filename) DO NOTHING;
