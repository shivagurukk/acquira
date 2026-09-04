-- ============================================================================
-- V2026_07_13_01: sum_daily_explorer — the Data Explorer history table.
--
-- WHY
-- ---
-- The Data Explorer (/explorer, DataExplorerController) previously pivoted the
-- STAGING tables (stg_trnx_raw / stg_merchant_master_raw), which are truncated
-- on every upload — so the page could only ever show the LAST upload, never
-- history. This table is the permanent, partition-pruned replacement for its
-- "transaction" source. (The "merchant" source moves to dim_* joins — no new
-- table needed there.)
--
-- No existing summary table carries the full explorer field set:
--   sum_daily_insight -> no transaction_type / txn_currency / vat / settled,
--                        cardholder volume only, no fees.
--   sum_daily_full    -> settlement + fees + mcc, but no transaction_type /
--                        txn_currency / vat / settled / terminal grain.
--
-- GRAIN
-- -----
-- One row per (tenant, business_date, merchant_id, store_id, terminal_id,
-- transaction_type, card_scheme, card_type, destination, channel,
-- txn_currency, is_opt_in).
--   - business_date : DATE(payment_date) — consistent with every dashboard.
--   - channel       : dim_terminal.type, COALESCE 'POS' (same rule as
--                     sum_daily_insight / sum_daily_full).
--   - card_scheme   : normalized exactly like sum_daily_insight (blank/'NULL'
--                     falls back to card_type, else 'Unclassified').
--   - is_opt_in     : fact_transaction.dcc.
-- Row-level identifiers (arn, rrn, card_number, auth_code) are intentionally
-- NOT here — those belong to the Transactions page (fact browser), not an
-- aggregating explorer.
--
-- MEASURES
-- --------
-- total_txns,
-- total_txn_currency_amount (cardholder ccy),
-- total_base_volume         (settlement, store_base_currency_amount),
-- total_msf, total_vat, total_settled (total_amount_settled),
-- total_interchange, total_scheme_fee.
-- AVG in the explorer is computed as SUM(x)/SUM(total_txns) (weighted), never
-- AVG over these pre-aggregated rows.
--
-- POPULATION
-- ----------
-- Written by populateSummaryStep (TransactionJobConfig) as a phase-1 task in
-- the same pass as sum_daily_full, straight off fact_transaction AFTER the fee
-- UPDATE in stagingToFactStep. Included in the clean-slate DELETE list so
-- multi-upload re-aggregation can't leave orphan rows. No backfill migration:
-- history lands as data is (re-)uploaded.
--
-- PARTITIONING
-- ------------
-- Yearly RANGE partitions on business_date, y2024..y2027 + _default, and
-- registered in PartitionMaintenanceService.YEARLY_PARTITIONED_TABLES so
-- future years are auto-created at startup.
--
-- HOW THIS RUNS
-- -------------
-- Brand-new table: CREATE TABLE IF NOT EXISTS is safe and idempotent. No DO $$
-- blocks (Spring sql.init splitter). RLS uses the DROP POLICY IF EXISTS /
-- CREATE POLICY pair like every other sum_daily_* table. Listed in
-- spring.sql.init.schema-locations for dev; apply once via psql on prod.
-- ============================================================================

CREATE TABLE IF NOT EXISTS sum_daily_explorer (
    summary_id                BIGSERIAL,
    tenant_id                 INT NOT NULL,
    business_date             DATE NOT NULL,

    merchant_id               BIGINT,
    store_id                  BIGINT,
    terminal_id               BIGINT,
    transaction_type          VARCHAR(50),
    card_scheme               VARCHAR(50),
    card_type                 VARCHAR(50),
    destination               VARCHAR(50),
    channel                   VARCHAR(50),
    txn_currency              VARCHAR(10),
    store_base_currency       VARCHAR(10),
    is_opt_in                 BOOLEAN,

    total_txns                BIGINT DEFAULT 0,
    total_txn_currency_amount DECIMAL(19, 2) DEFAULT 0,  -- cardholder currency
    total_base_volume         DECIMAL(19, 2) DEFAULT 0,  -- settlement currency
    total_msf                 DECIMAL(19, 2) DEFAULT 0,
    total_vat                 DECIMAL(19, 2) DEFAULT 0,
    total_settled             DECIMAL(19, 2) DEFAULT 0,
    total_interchange         DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee          DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id,
            transaction_type, card_scheme, card_type, destination, channel,
            txn_currency, is_opt_in)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_explorer_y2024
    PARTITION OF sum_daily_explorer FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_explorer_y2025
    PARTITION OF sum_daily_explorer FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_explorer_y2026
    PARTITION OF sum_daily_explorer FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_explorer_y2027
    PARTITION OF sum_daily_explorer FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_explorer_default
    PARTITION OF sum_daily_explorer DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_daily_explorer_tenant_date
    ON sum_daily_explorer (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_daily_explorer_tenant_date_merch
    ON sum_daily_explorer (tenant_id, business_date, merchant_id);

ALTER TABLE sum_daily_explorer ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_explorer;
CREATE POLICY tenant_isolation_policy ON sum_daily_explorer
    USING (tenant_id = get_current_tenant());
