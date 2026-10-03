-- ============================================================================
-- V2026_10_03_04: remove the API Management screen and the external API.
--
-- WHY: the outbound partner API (/api/v1 data endpoints, /api/external/reports
-- PDF downloads) and its API-key administration screen are no longer offered.
-- The controllers, the X-API-Key filter, the admin page and the
-- rbac/menu-catalog.json entry are gone from source in the same change as this
-- migration; this deletes whatever an environment already stored, so no tenant
-- is offered a screen that has no route.
--
-- The api_key, api_request_log and api_idempotency tables are left in place
-- (no code reads or writes them any more). Drop them by hand once their
-- contents are no longer needed for audit.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- 1. Grants first (tenant_group_perm.perm_code REFERENCES sys_permission).
DELETE FROM tenant_group_perm
 WHERE perm_code LIKE 'admin.api-management:%';

-- 2. The permission catalog rows.
DELETE FROM sys_permission
 WHERE menu_key = 'admin.api-management';

-- 3. Legacy binary grants, then the screen itself.
DELETE FROM sys_group_menu
 WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE path = '/admin/api-management');

DELETE FROM sys_menu
 WHERE path = '/admin/api-management';

-- 4. Force every session to re-resolve its menu and permission set.
UPDATE rbac_version
   SET version = version + 1,
       updated_at = CURRENT_TIMESTAMP;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_10_03_04__remove_api_management_screen.sql') ON CONFLICT (filename) DO NOTHING;
