-- ============================================================================
-- V2026_08_31_01: DCC revenue feed — staging + fact + ancillary summary columns.
--
-- DCC revenue arrives in a DEDICATED file at STORE (SID) level, through the
-- same three channels as rentals: screen upload, Server File Processor, and
-- the scheduled integration pull. DccRevenueJobConfig (dccLoadJob /
-- dbPullDccJob) stages rows here and applies them.
--
-- File shape (header-name mapped, order-independent):
--   SID, Tenant Id, Merchant Share, Acquirer Share, Date
-- Amounts are tenant base currency, MAJOR units. "Tenant Id" (bank short
-- code, institution id, or numeric tenant id) is VALIDATED against the
-- uploading tenant — a mismatched row is REJECTED, it never routes data.
--
-- REPLACE-BY-DATE SEMANTICS (decision 2026-08-31, unlike rentals' dedupe):
-- the apply step DELETEs fact_dcc_revenue for exactly the (tenant, dates)
-- present in the file, then inserts fresh — so re-uploading a corrected day
-- fully supersedes the previous numbers. Idempotent on repeat upload.
--
-- ANCILLARY SUMMARY COLUMNS: sum_daily_merchant and sum_daily_finance_rollup
-- gain dcc_acquirer / dcc_merchant / rental_amount so every dashboard reading
-- the summary layer can show Net Spread
--   (= total_margin + dcc_acquirer + rental_amount)
-- without joining facts. The columns are ALWAYS recomputed from
-- fact_dcc_revenue / fact_rental by AncillarySql (acquira-common) — after
-- every summary rebuild and after every DCC/rental apply — never carried
-- forward. net_spread itself is derived at read time, never stored.
-- Grain rule: dimensionally-sliced summaries (sum_daily_full, terminal,
-- monthly_card, ...) deliberately do NOT get these columns — a store-level
-- charge cannot be honestly attributed to a scheme/card/destination slice.
--
-- Idempotent; splitter-safe (no $$). Listed in spring.sql.init.schema-locations
-- for dev; apply once via psql on prod.
-- ============================================================================

CREATE TABLE IF NOT EXISTS stg_dcc_revenue_raw (
    raw_id         BIGSERIAL PRIMARY KEY,
    tenant_id      INT,
    file_id        BIGINT,
    load_time      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status         VARCHAR(20) DEFAULT 'PENDING',  -- PENDING|PROCESSED|REJECTED|UNMATCHED
    error_message  TEXT,

    sid            VARCHAR(50),
    file_tenant_id VARCHAR(50),                    -- as filed; validated, never trusted
    merchant_share DECIMAL(19,4),
    acquirer_share DECIMAL(19,4),
    payment_date   DATE
);

CREATE INDEX IF NOT EXISTS idx_stg_dcc_tenant ON stg_dcc_revenue_raw (tenant_id, status);

ALTER TABLE stg_dcc_revenue_raw ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON stg_dcc_revenue_raw;
CREATE POLICY tenant_isolation_policy ON stg_dcc_revenue_raw
    USING (tenant_id = get_current_tenant());

CREATE TABLE IF NOT EXISTS fact_dcc_revenue (
    dcc_id         BIGSERIAL PRIMARY KEY,
    tenant_id      INT NOT NULL,
    merchant_id    BIGINT,
    store_id       BIGINT,
    sid            VARCHAR(50),
    merchant_share DECIMAL(19,4) NOT NULL,
    acquirer_share DECIMAL(19,4) NOT NULL,
    payment_date   DATE NOT NULL,
    file_id        BIGINT,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fact_dcc_tenant_date
    ON fact_dcc_revenue (tenant_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_fact_dcc_tenant_merchant_date
    ON fact_dcc_revenue (tenant_id, merchant_id, payment_date);

ALTER TABLE fact_dcc_revenue ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON fact_dcc_revenue;
CREATE POLICY tenant_isolation_policy ON fact_dcc_revenue
    USING (tenant_id = get_current_tenant());

-- Ancillary columns on the merchant-day summary. NOT NULL DEFAULT 0 so every
-- existing consumer's SUM() keeps working unchanged.
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS dcc_acquirer  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS dcc_merchant  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4) NOT NULL DEFAULT 0;

-- Same three on the tenant-day finance rollup fast path.
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS dcc_acquirer  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS dcc_merchant  DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS rental_amount DECIMAL(19,4) NOT NULL DEFAULT 0;

-- One-time seed for rental history already in fact_rental (loaded before this
-- migration existed) — the same statements AncillarySql runs after every
-- apply, so re-running is harmless (it recomputes to the same values).
-- fact_dcc_revenue is empty at migration time; its rows arrive through the
-- apply job, which maintains these columns itself.
INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id,
    total_txns, total_volume, total_base_volume, total_msf, total_interchange,
    total_scheme_fee, total_margin, rental_amount)
SELECT tenant_id, payment_date, merchant_id, 0, 0, 0, 0, 0, 0, 0, SUM(rental_amount)
FROM fact_rental
WHERE merchant_id IS NOT NULL
GROUP BY tenant_id, payment_date, merchant_id
ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

INSERT INTO sum_daily_finance_rollup (tenant_id, business_date, rental_amount)
SELECT tenant_id, payment_date, SUM(rental_amount)
FROM fact_rental
GROUP BY tenant_id, payment_date
ON CONFLICT (tenant_id, business_date) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;
