-- ============================================================================
-- V2026_10_25_01: tenant.cmm_minor_divisor — per-tenant override of the CMM
-- minor-unit divisor for amounts in the tenant's BASE currency.
--
-- WHY
-- ---
-- A CMM feed is divided by ref_country.decimal_notation_value (100 for EGP/AED,
-- 1000 for BHD). That one column also drives DISPLAY precision everywhere
-- (CurrencyResolver -> dashboards, Excel, PDF). The Egyptian processor's CMM
-- extract carries EGP with THREE implied decimals (milliemes) while EGP is an
-- ISO 2-decimal currency, so the two concerns must be separable: divide the
-- feed by 1000, keep showing pounds and piastres.
--
--   cmm_minor_divisor NULL  -> legacy: divisor comes from ref_country (default)
--   cmm_minor_divisor 1000  -> this tenant's CMM feed expresses its base
--                              currency with 3 implied decimals
--
-- Applies ONLY to CMM tenants (AMS never divides) and ONLY to amounts whose
-- currency equals tenant.base_currency; foreign-currency txn amounts keep the
-- ref_country divisor. Read by TransactionJobConfig (file/server-folder),
-- BackfillIngestionService and IntegrationPullService (scheduled pull).
-- Set in Tenant Management ("CMM implied decimals"). Companion: Tenant.java,
-- BankController, TenantManagement.jsx.
--
-- Idempotent, splitter-safe.
-- ============================================================================

ALTER TABLE tenant ADD COLUMN IF NOT EXISTS cmm_minor_divisor INTEGER NULL;

ALTER TABLE tenant DROP CONSTRAINT IF EXISTS chk_tenant_cmm_minor_divisor;
ALTER TABLE tenant
    ADD CONSTRAINT chk_tenant_cmm_minor_divisor
    CHECK (cmm_minor_divisor IS NULL OR cmm_minor_divisor IN (1, 10, 100, 1000, 10000));

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_25_01__tenant_cmm_minor_divisor.sql')
ON CONFLICT (filename) DO NOTHING;
