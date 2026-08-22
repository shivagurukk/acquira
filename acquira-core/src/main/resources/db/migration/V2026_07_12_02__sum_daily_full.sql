-- ============================================================================
-- V2026_07_12_02: sum_daily_full — the fully-dimensional daily settlement
--   pre-aggregate, WITH real fees.
--
-- WHY
-- ---
-- No single existing summary table carries settlement volume AND the full
-- dimensional cross-tab AND real fees at once:
--   sum_daily_insight   -> full dimensional grain (mid/sid/tid/scheme/card_type/
--                          destination/channel/opt_in) BUT cardholder-currency
--                          total_volume only, and NO interchange/scheme/ecom/net.
--   sum_daily_merchant  -> settlement (total_base_volume) + real fees, but ONLY
--                          merchant grain (no scheme/card_type/channel/mcc split).
--   sum_daily_merchant_destination -> merchant x destination + fees, no card
--                          dimensions or channel/mcc.
-- The Debit/Prepaid tiles (and any settlement-basis, dimension-sliced KPI) need
-- settlement volume split by card_type / channel / destination / scheme / mcc
-- together — which is exactly this table's grain.
--
-- GRAIN
-- -----
-- One row per (tenant, business_date, merchant_id, store_id, mcc, channel,
-- destination, card_scheme, card_type, is_opt_in).
--   - channel      : dim_terminal.type, COALESCE 'POS' (same rule as
--                    sum_daily_insight / sum_daily_channel).
--   - mcc          : dim_store.mcc.
--   - is_opt_in    : fact_transaction.dcc (DCC opt-in flag).
--   - card_scheme  : normalized the same way as sum_daily_insight (blank/'NULL'
--                    falls back to card_type, else 'Unclassified').
--
-- MEASURES (settlement currency, store_base_currency_amount — never cardholder
-- txn_currency_amount, per project data-sourcing rules)
-- -----------------------------------------------------------------------
-- total_txns, total_volume (settlement), total_msf, total_interchange,
-- total_scheme_fee, total_ecom_fee,
-- total_net_revenue (= msf - interchange - scheme_fee - ecom_fee),
-- dcc_optin_count (COUNT of rows with dcc IS TRUE — redundant with the
-- is_opt_in grain but kept as an explicit measure for convenience).
--
-- POPULATION
-- ----------
-- Written by populateSummaryStep (TransactionJobConfig) as a phase-1 task in the
-- same pass as sum_daily_insight, straight off fact_transaction AFTER the fee
-- UPDATE in stagingToFactStep has run (so interchange/scheme/ecom are populated).
-- Included in the clean-slate DELETE list so multi-upload-per-month
-- re-aggregation can't leave orphan rows.
--
-- PARTITIONING
-- ------------
-- Yearly RANGE partitions on business_date, y2024..y2027 + a _default catch-all
-- (mirrors sum_daily_insight / sum_daily_merchant_destination). Registered with
-- PartitionMaintenanceService.YEARLY_PARTITIONED_TABLES so future years get
-- auto-created at startup like every other yearly summary table.
--
-- HOW THIS RUNS
-- -------------
-- Brand-new table, so CREATE TABLE IF NOT EXISTS is safe and idempotent with no
-- schema-drift risk. No DO $$ blocks (Spring's sql.init splitter breaks on
-- dollar-quoting) — RLS uses the same DROP POLICY IF EXISTS / CREATE POLICY pair
-- schema.sql itself uses. Listed in spring.sql.init.schema-locations so it lands
-- automatically in dev; apply once via psql on prod (schema.sql mode=never).
-- ============================================================================

CREATE TABLE IF NOT EXISTS sum_daily_full (
    summary_id        BIGSERIAL,
    tenant_id         INT NOT NULL,
    business_date     DATE NOT NULL,

    merchant_id       BIGINT,
    store_id          BIGINT,
    mcc               VARCHAR(10),
    channel           VARCHAR(50),
    destination       VARCHAR(50),
    card_scheme       VARCHAR(50),
    card_type         VARCHAR(50),
    is_opt_in         BOOLEAN,

    total_txns        BIGINT DEFAULT 0,
    total_volume      DECIMAL(19, 2) DEFAULT 0,   -- settlement (store_base_currency_amount)
    total_msf         DECIMAL(19, 2) DEFAULT 0,
    total_interchange DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee  DECIMAL(19, 2) DEFAULT 0,
    total_ecom_fee    DECIMAL(19, 2) DEFAULT 0,
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,
    dcc_optin_count   BIGINT DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, store_id, mcc, channel,
            destination, card_scheme, card_type, is_opt_in)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_full_y2024
    PARTITION OF sum_daily_full FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_full_y2025
    PARTITION OF sum_daily_full FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_full_y2026
    PARTITION OF sum_daily_full FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_full_y2027
    PARTITION OF sum_daily_full FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_full_default
    PARTITION OF sum_daily_full DEFAULT;

-- Indexes — (tenant, date) is the universal hot path for every KPI/trend read;
-- (tenant, date, merchant) for per-MID slices.
CREATE INDEX IF NOT EXISTS idx_sum_daily_full_tenant_date
    ON sum_daily_full (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_daily_full_tenant_date_merch
    ON sum_daily_full (tenant_id, business_date, merchant_id);

-- Row-Level Security — defence-in-depth backstop under app-level tenant
-- scoping, matching every other sum_daily_* table.
ALTER TABLE sum_daily_full ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_full;
CREATE POLICY tenant_isolation_policy ON sum_daily_full
    USING (tenant_id = get_current_tenant());
