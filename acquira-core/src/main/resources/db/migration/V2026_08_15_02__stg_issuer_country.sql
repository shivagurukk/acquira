-- ============================================================================
-- V2026_08_15_02: issuer_country on staging (BIN typing, card_type_source).
--
-- The ingestion processor now resolves the PAN's 6-digit clear prefix against
-- ref_bin / ref_bin_range when a tenant opted into card_type_source='BIN'
-- (V2026_08_08_06). The resolved issuer country is stored on every matched
-- row (staging -> fact) as metadata; the card TYPE is overwritten ONLY when
-- the issuer country equals tenant.home_country_code (local cards).
-- International cards keep the feed's typing untouched, and destination /
-- fee inputs are never derived from this column.
--
-- fact_transaction.issuer_country already exists (V2026_08_10_01); this adds
-- the staging leg. Idempotent; splitter-safe. Listed in spring.sql.init
-- schema-locations after schema.sql. On prod apply once via psql.
-- ============================================================================

ALTER TABLE stg_trnx_raw ADD COLUMN IF NOT EXISTS issuer_country VARCHAR(10);
