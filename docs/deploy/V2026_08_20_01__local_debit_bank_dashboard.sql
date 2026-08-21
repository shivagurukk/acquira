-- ============================================================================
-- V2026_08_20_01: Local Debit Bank Dashboard — tenant BIN->bank reference +
--   the local-debit BIN daily pre-aggregate.
--
-- WHY
-- ---
-- "Which LOCAL DEBIT issuing banks generated our acquiring traffic?" The bank
-- identity is not in the feed; it derives from the card BIN (first 6 PAN
-- digits, delivered in clear). The scheme BIN files (ref_bin_range) carry no
-- issuer NAME, so bank naming comes EXCLUSIVELY from a per-tenant uploaded
-- list — ref_tenant_bin_bank. ref_bin / ref_bin_range are deliberately never
-- consulted by this dashboard.
--
-- ref_tenant_bin_bank
-- -------------------
-- One row per (tenant, 6-digit BIN) -> bank name. Uploaded on the dashboard
-- itself (CSV/Excel: BIN, BANK). 8-digit BINs in the source file are
-- truncated to their 6-digit prefix at upload time (matching can only ever
-- see 6 clear digits); 6-prefix collisions across banks keep the first
-- occurrence and are reported in the upload response.
--
-- sum_daily_local_debit_bin
-- -------------------------
-- Grain: (tenant, business_date, merchant_id, bin6) — ONLY rows where
--   UPPER(TRIM(card_type)) = 'DEBIT' AND destination = 'DOMESTIC'
--   AND merchant_id IS NOT NULL   (same merchant rule as sum_daily_full).
-- The strict destination='DOMESTIC' predicate matches the Card Type
-- Dashboard's filtered view of sum_daily_full, so this page's total
-- (matched banks + the query-time "Other Banks" bucket) reconciles exactly
-- with that page's DOMESTIC x DEBIT cell. NULL/UNMAPPED destinations are
-- deliberately excluded — a failed token mapping must not silently count as
-- local.
-- bin6 = LEFT(card_number, 6) when the PAN starts with 6 digits, else the
-- literal '??????' bucket (malformed/absent PANs stay visible in totals).
-- Measures: total_txns, total_volume (settlement store_base_currency_amount,
-- SIGNED — refunds net out), total_msf (signed) — identical bases to
-- sum_daily_full so parity is exact.
--
-- Bank names are resolved at QUERY time (LEFT JOIN ref_tenant_bin_bank);
-- re-uploading a corrected BIN list instantly re-labels ALL history with no
-- summary rebuild.
--
-- POPULATION: TransactionJobConfig.populateSummary (both upload and server
-- file-processing ingest paths), BulkMigrationService.rebuildSummaries,
-- BackfillIngestionService — identical INSERT in all three; included in the
-- clean-slate delete lists and deleteDay.
--
-- PARTITIONING: yearly RANGE on business_date (mirrors sum_daily_full);
-- registered in PartitionMaintenanceService.YEARLY_PARTITIONED_TABLES.
--
-- Idempotent; splitter-safe (no $$). Listed in spring.sql.init.schema-locations
-- for dev; apply once via psql on prod.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ref_tenant_bin_bank (
    tenant_id   INT          NOT NULL,
    bin         VARCHAR(6)   NOT NULL,
    bank_name   VARCHAR(128) NOT NULL,
    source_file VARCHAR(256),
    loaded_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bin)
);

ALTER TABLE ref_tenant_bin_bank ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON ref_tenant_bin_bank;
CREATE POLICY tenant_isolation_policy ON ref_tenant_bin_bank
    USING (tenant_id = get_current_tenant());

CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin (
    summary_id    BIGSERIAL,
    tenant_id     INT NOT NULL,
    business_date DATE NOT NULL,
    merchant_id   BIGINT NOT NULL,
    bin6          VARCHAR(6) NOT NULL,

    total_txns    BIGINT DEFAULT 0,
    total_volume  DECIMAL(19, 2) DEFAULT 0,   -- settlement (store_base_currency_amount), signed
    total_msf     DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, bin6)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2024
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2025
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2026
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2027
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_default
    PARTITION OF sum_daily_local_debit_bin DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_daily_ldb_tenant_date
    ON sum_daily_local_debit_bin (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_daily_ldb_tenant_date_bin
    ON sum_daily_local_debit_bin (tenant_id, business_date, bin6);

ALTER TABLE sum_daily_local_debit_bin ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_local_debit_bin;
CREATE POLICY tenant_isolation_policy ON sum_daily_local_debit_bin
    USING (tenant_id = get_current_tenant());
