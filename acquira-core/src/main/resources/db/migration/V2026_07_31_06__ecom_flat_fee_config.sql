-- ============================================================================
-- V2026_07_31_06: ECOM flat fee becomes per-country config (was hardcoded 0.18).
--
-- WHY
-- ---
-- stagingToFactStep computed the ECOM flat fee as a LITERAL 0.18 for every ECOM
-- transaction: `CASE WHEN channel='ECOM' THEN 0.18`. That is a UAE rule (0.18
-- AED per ECOM txn). Once fee computation became country-aware (V2026_07_31_02),
-- that literal started charging 0.18 in each country's SETTLEMENT currency —
-- 0.18 BHD / 0.18 OMR / 0.18 EGP for Bahrain / Oman / Egypt — which is wrong
-- (0.18 BHD is ~10x 0.18 AED).
--
-- This migration moves the flat fee into a country-level config table (same
-- model as interchange_rate_local / scheme_fee_rate: tenant_id NULL = country
-- default, non-null = per-tenant override). The companion TransactionJobConfig
-- change reads it: `CASE WHEN channel='ECOM' THEN COALESCE(<resolved fee>, 0)`.
--
--   AE  -> 0.18 (the value previously hardcoded; behaviour unchanged for UAE).
--   BH/OM/EG (and any other country) -> NO row -> COALESCE(...,0) -> ZERO ECOM
--   flat fee until a real per-country value is seeded. This stops the wrong-
--   currency charge immediately; add a row when the real figure is known.
--
-- Splitter-safe (no dollar-quoting); idempotent (IF NOT EXISTS / NOT EXISTS).
-- ============================================================================

CREATE TABLE IF NOT EXISTS ecom_flat_fee (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     INT,                          -- NULL = country default, non-null = per-tenant override
    country_code  VARCHAR(2)    NOT NULL,
    fee_amount    DECIMAL(19,4) NOT NULL,        -- flat fee per ECOM txn, in the country's settlement currency
    label         VARCHAR(80)
);

-- FK to ref_country (DROP-then-ADD for idempotency; no dollar-quoted block).
ALTER TABLE ecom_flat_fee DROP CONSTRAINT IF EXISTS fk_ecom_flat_fee_country;
ALTER TABLE ecom_flat_fee
    ADD CONSTRAINT fk_ecom_flat_fee_country
    FOREIGN KEY (country_code) REFERENCES ref_country(country_code);

-- One default row per country, plus at most one override per (real) tenant.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_flat_fee_key
    ON ecom_flat_fee (country_code, COALESCE(tenant_id, 0));

-- Seed the UAE default = 0.18 (the previously-hardcoded value). Guarded so it
-- seeds once and never clobbers a later in-UI edit.
INSERT INTO ecom_flat_fee (tenant_id, country_code, fee_amount, label)
SELECT NULL, 'AE', 0.1800, 'UAE ECOM flat 0.18 (AED)'
WHERE NOT EXISTS (SELECT 1 FROM ecom_flat_fee WHERE country_code = 'AE' AND tenant_id IS NULL);

-- BH / OM / EG are intentionally NOT seeded here: with no row the ingest applies
-- a ZERO ECOM flat fee (COALESCE(...,0)). Seed a real value per country later.

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_31_06__ecom_flat_fee_config.sql') ON CONFLICT (filename) DO NOTHING;
