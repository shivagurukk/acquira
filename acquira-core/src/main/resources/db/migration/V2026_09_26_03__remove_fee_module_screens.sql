-- ============================================================================
-- V2026_09_26_03: remove the transaction-fee screens from this product.
--
-- WHY: the fee / MDR pricing module belongs to Ledgerline, the acquiring back
-- office. It was briefly built here on 2026-09-26 and removed the same day,
-- but two things survived that removal:
--   * V2026_09_26_02 seeded 'fees.plans:APPROVE', 'fees.assignments:APPROVE'
--     and 'fees.charges:APPROVE' into sys_permission, plus the EDIT/EXPORT
--     permissions derived from the 'TRANSACTION FEES' category;
--   * rbac/menu-catalog.json listed the four /fees/* screens, so
--     RbacCatalogSync re-inserted them into sys_menu on every restart.
-- The catalog entries and the seeds are gone from source in the same change as
-- this migration; this deletes whatever an environment already stored, so no
-- tenant is offered a screen that has no route.
--
-- Nothing else referenced these rows: the fee tables (fee_plan, fee_plan_rule,
-- fee_plan_assignment, fee_charge) and the feeplan.* settings were dropped when
-- the module was removed.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql, after
-- V2026_09_26_02.
-- ============================================================================

-- 1. Grants first (tenant_group_perm.perm_code REFERENCES sys_permission).
DELETE FROM tenant_group_perm
 WHERE perm_code LIKE 'fees.%';

-- 2. The permission catalog rows.
DELETE FROM sys_permission
 WHERE menu_key LIKE 'fees.%';

-- 3. Legacy binary grants, then the screens themselves.
DELETE FROM sys_group_menu
 WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE path LIKE '/fees/%');

DELETE FROM sys_menu
 WHERE path LIKE '/fees/%';

-- 4. The module entitlement row.
DELETE FROM tenant_module
 WHERE module_key = 'transaction_fees';

-- 5. Settings, in case an environment kept them (the module removal deleted
--    these locally; other environments never had the module).
DELETE FROM tenant_setting
 WHERE setting_key LIKE 'feeplan.%';

-- 6. Force every session to re-resolve its menu and permission set.
UPDATE rbac_version
   SET version = version + 1,
       updated_at = CURRENT_TIMESTAMP;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_26_03__remove_fee_module_screens.sql') ON CONFLICT (filename) DO NOTHING;
