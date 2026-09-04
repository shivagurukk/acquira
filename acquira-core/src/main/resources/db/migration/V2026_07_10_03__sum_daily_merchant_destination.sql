-- ============================================================================
-- V2026_07_10_03: sum_daily_merchant_destination — merchant x destination
--   (DOMESTIC/INTERNATIONAL) pre-aggregate, WITH real fees.
--
-- WHY
-- ---
-- No existing summary table has destination split at merchant grain WITH
-- interchange/scheme/ecom fees:
--   sum_daily_insight   -> has destination + merchant grain, but NO fee columns
--                          (interchange/scheme are always 0 there).
--   sum_daily_merchant  -> has real fees, but NO destination split.
--   sum_daily_finance   -> has a dom/intl split, but only at BANK grain.
-- The Domestic vs International report (by MID) needs both dimensions at once,
-- so this table carries destination as part of the grain alongside the same
-- fee columns sum_daily_merchant already has.
--
-- GRAIN
-- -----
-- One row per (tenant, business_date, merchant_id, destination). destination
-- is normalized to 'DOMESTIC' / 'INTERNATIONAL' (UPPER, NULL treated as
-- DOMESTIC — matches the UPPER(destination)='INTERNATIONAL' convention already
-- used throughout TransactionJobConfig / VolumeRevenueRepository).
--
-- MEASURES (settlement currency, store_base_currency_amount — never cardholder
-- txn_currency_amount, per project data-sourcing rules)
-- -----------------------------------------------------------------------
-- total_txns, total_volume, total_msf, total_interchange, total_scheme_fee,
-- total_ecom_fee, total_net_revenue (= msf - interchange - scheme_fee - ecom_fee).
--
-- POPULATION
-- ----------
-- Written by populateSummaryStep (TransactionJobConfig), same pass as
-- sum_daily_merchant, straight off fact_transaction. Included in the
-- clean-slate DELETE list so multi-upload-per-month re-aggregation can't
-- leave orphan rows (see project data-integrity learnings).
--
-- PARTITIONING
-- ------------
-- Yearly RANGE partitions on business_date, y2024..y2027 + a _default catch-all
-- (mirrors sum_daily_merchant / sum_daily_terminal). Registered with
-- PartitionMaintenanceService.YEARLY_PARTITIONED_TABLES so future years
-- (2028+) get auto-created at startup like every other yearly summary table.
--
-- HOW THIS RUNS
-- -------------
-- Brand-new table, so CREATE TABLE IF NOT EXISTS is safe and idempotent with
-- no schema-drift risk. No DO $$ blocks (Spring's sql.init splitter breaks on
-- dollar-quoting) — RLS policy uses the same DROP POLICY IF EXISTS / CREATE
-- POLICY pair schema.sql itself uses. Listed in
-- spring.sql.init.schema-locations so it lands automatically in dev; apply
-- once via psql on prod (schema.sql mode=never there).
-- ============================================================================

CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination (
    summary_id       BIGSERIAL,
    tenant_id        INT NOT NULL,
    business_date    DATE NOT NULL,
    merchant_id      BIGINT,
    destination      VARCHAR(20) NOT NULL,   -- DOMESTIC / INTERNATIONAL

    total_txns       BIGINT DEFAULT 0,
    total_volume     DECIMAL(19, 2) DEFAULT 0,   -- settlement (store_base_currency_amount)
    total_msf        DECIMAL(19, 2) DEFAULT 0,
    total_interchange DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee DECIMAL(19, 2) DEFAULT 0,
    total_ecom_fee   DECIMAL(19, 2) DEFAULT 0,
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, destination)
) PARTITION BY RANGE (business_date);

-- Partitions — mirror sum_daily_merchant's yearly ranges, extended through 2027
-- (sum_daily_merchant itself only got _y2026 in schema.sql and needed a
-- separate 2027 migration; starting this brand-new table with 2027 already
-- present avoids repeating that gap).
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2024
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2025
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2026
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2027
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_default
    PARTITION OF sum_daily_merchant_destination DEFAULT;

-- Indexes — (tenant, date, merchant) for the per-MID report; (tenant, date,
-- destination) for destination-only rollups/KPI totals.
CREATE INDEX IF NOT EXISTS idx_sum_merch_dest_tenant_date_merch
    ON sum_daily_merchant_destination (tenant_id, business_date, merchant_id);
CREATE INDEX IF NOT EXISTS idx_sum_merch_dest_tenant_date_dest
    ON sum_daily_merchant_destination (tenant_id, business_date, destination);

-- Row-Level Security — defence-in-depth backstop under app-level tenant
-- scoping, matching every other sum_daily_* table.
ALTER TABLE sum_daily_merchant_destination ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_merchant_destination;
CREATE POLICY tenant_isolation_policy ON sum_daily_merchant_destination
    USING (tenant_id = get_current_tenant());
