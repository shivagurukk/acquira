-- ============================================================================
-- V2026_08_08_03: tenant.input_format — amount format is now a TENANT setting.
--
-- WHY
-- ---
-- Whether uploaded/pulled amounts need the minor-unit division was decided by
-- two disconnected mechanisms:
--   * file path : filename prefix 'AMS_' (inputTypeFor in FileUploadService)
--                 -> AMS = final decimals, no division; anything else = CMM,
--                 divide by ref_country.decimal_notation_value.
--   * scheduled : integration_report.amounts_minor_units flag.
-- A misnamed file (or a wrong flag) silently loads amounts 1000x off for a
-- 3-decimal-currency tenant (BHD/OMR/KWD). The format is a property of the
-- TENANT's feed, so it now lives on the tenant:
--     input_format = 'CMM' -> amounts are minor units, DIVIDE (legacy default)
--     input_format = 'AMS' -> amounts are final decimals, NO division
--
-- Companion code: Tenant.java (new field), BankController (edit),
-- FileUploadService.inputTypeForTenant (tenant value wins; explicit 'AMS_'
-- filename prefix still honoured as an override for legacy feeds),
-- IntegrationPullService (report.amounts_minor_units, when set, overrides;
-- otherwise the tenant format decides), TenantManagement.jsx (dropdown).
--
-- Default 'CMM' preserves existing behaviour for every current tenant.
-- Idempotent + splitter-safe (no dollar-quoting).
-- ============================================================================

ALTER TABLE tenant ADD COLUMN IF NOT EXISTS input_format VARCHAR(10) NOT NULL DEFAULT 'CMM';

UPDATE tenant SET input_format = 'CMM' WHERE input_format IS NULL;

-- Guard rail: only the two known formats.
ALTER TABLE tenant DROP CONSTRAINT IF EXISTS chk_tenant_input_format;
ALTER TABLE tenant
    ADD CONSTRAINT chk_tenant_input_format CHECK (input_format IN ('CMM', 'AMS'));

-- Grandfather existing scheduled reports: a NULL amounts_minor_units used to
-- mean "no division" (Boolean.TRUE.equals(null) = false). Freeze that as an
-- explicit FALSE so the new tenant-format fallback (NULL -> tenant.input_format)
-- only applies to reports created AFTER this migration. Guarded by
-- schema_migration_log so it runs exactly once and never overwrites a later
-- deliberate NULL reset.
UPDATE integration_report SET amounts_minor_units = FALSE
WHERE amounts_minor_units IS NULL
  AND NOT EXISTS (SELECT 1 FROM schema_migration_log
                  WHERE filename = 'V2026_08_08_03__tenant_input_format.sql');

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_08_03__tenant_input_format.sql') ON CONFLICT (filename) DO NOTHING;
