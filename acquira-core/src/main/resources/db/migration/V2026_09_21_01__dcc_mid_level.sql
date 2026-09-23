-- ============================================================================
-- V2026_09_21_01: DCC revenue feed — MID-level file support.
--
-- Some DCC extracts arrive at MERCHANT (MID) level instead of STORE (SID)
-- level: MID, Tenant Id, Acquirer Share [, Merchant Share], Date.
-- DccRevenueJobConfig now reads an optional MID column; a row with no SID
-- resolves via dim_merchant (leading zeros ignored, feed MIDs are padded)
-- and lands in fact_dcc_revenue with merchant_id set and store_id/sid NULL —
-- which is all AncillarySql needs, since the summary rollup is per merchant.
--
-- Idempotent; splitter-safe (no $$).
-- ============================================================================

ALTER TABLE stg_dcc_revenue_raw ADD COLUMN IF NOT EXISTS mid VARCHAR(50);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_21_01__dcc_mid_level.sql') ON CONFLICT (filename) DO NOTHING;
