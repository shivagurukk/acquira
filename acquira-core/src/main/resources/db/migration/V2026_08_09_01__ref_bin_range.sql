-- ============================================================================
-- V2026_08_09_01: ref_bin_range — scheme BIN/account-range reference data.
--
-- WHY A RANGE TABLE (alongside the 8-digit ref_bin)
-- -------------------------------------------------
-- Scheme BIN files are RANGE-based, not BIN-based. The Visa BIN list carries
-- 9-digit account ranges that split BELOW the 8-digit boundary (verified in
-- AFS's own file: 402627000-402627001 vs 402627002-402627004 carry different
-- products), so collapsing to 8 digits either loses granularity or explodes
-- to ~50M rows. Ranges load here LOSSLESS (593K rows for the current Visa
-- file); ref_bin (8-digit, manually uploaded CSV/XLSX) stays untouched as the
-- AMS product-type mapping per the 2026-08-08 decision.
--
-- Loaded from Super Admin > BIN Management. File type is detected by NAME:
--   VISA*  -> fixed-width Visa BIN list parser (this table, scheme VISA)
--   T067*  -> rejected until the Mastercard SMS layout doc is provided
--   other  -> CSV/XLSX -> ref_bin (8-digit) as before
--
-- CONFIG ONLY: no ingestion or fee logic reads this table yet.
--
-- range_low/range_high are the file's 9-digit account-range bounds stored
-- zero-padded; lookups compare on the PAN's leading 9 digits. bin6 is the
-- licensed BIN column from the file (may differ from the range prefix).
-- funding_source keeps the raw scheme letter (C/D/P/H/R); card_type is the
-- Acquira-normalized bucket.
--
-- Idempotent + splitter-safe.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ref_bin_range (
    id             BIGSERIAL PRIMARY KEY,
    scheme         VARCHAR(20) NOT NULL,       -- VISA / MASTERCARD
    range_low      VARCHAR(9)  NOT NULL,       -- 9-digit, zero-padded
    range_high     VARCHAR(9)  NOT NULL,
    bin6           VARCHAR(8),                 -- licensed BIN column from the file
    region_code    VARCHAR(2),                 -- scheme region (Visa: 1-6)
    issuer_country VARCHAR(2),                 -- ISO alpha-2 from the file
    product_code   VARCHAR(5),                 -- scheme product id (F, N, C, G1, ...)
    funding_source VARCHAR(2),                 -- raw scheme letter (C/D/P/H/R)
    card_type      VARCHAR(20),                -- normalized CREDIT/DEBIT/PREPAID
    source_file    VARCHAR(200),
    loaded_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ref_bin_range_lookup ON ref_bin_range (scheme, range_low, range_high);
CREATE INDEX IF NOT EXISTS idx_ref_bin_range_country ON ref_bin_range (issuer_country);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_09_01__ref_bin_range.sql') ON CONFLICT (filename) DO NOTHING;
