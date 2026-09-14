-- ============================================================================
-- V2026_08_22_02 — sum_daily_finance_rollup: the Finance Summary fast path
-- ============================================================================
-- WHY
-- ---
-- GET /api/finance/summary opens on a YEAR preset. Serving it meant aggregating
-- every sum_monthly_insight row of the complete months, every sum_daily_insight
-- row of the partial month AND every sum_daily_full row of the year for the fee
-- overlay — millions of rows on a large tenant, several seconds per request,
-- paid again each time an ingest evicts the report cache.
--
-- This table holds ONE row per tenant per business day with exactly the
-- measures the screen shows at its MONTH and DAY grains (the MERCHANT
-- drill-down is a single day and keeps reading the detail tables). A year is
-- now <= 365 rows.
--
-- PARITY
-- ------
-- Pivot measures are SUMs over sum_daily_insight with the SAME bucket
-- predicates as VolumeRevenueRepository.getPerformanceDashboardDataDaily; the
-- fee columns are SUMs over sum_daily_full with the SAME predicates as
-- getFinanceFeeOverlay. Both sources are additive, so month/day totals from
-- here equal what the old queries returned. The screen's figures do not
-- change. The Java twin of the statement below is
-- com.acquira.common.service.FinanceRollupSql.REBUILD_INSERT — keep them in
-- step.
--
-- MAINTENANCE
-- -----------
-- The upload job, the backfill service and the summary rebuild each rewrite
-- the days they touch (FinanceRollupSql.rebuildDates / rebuildRange) after
-- sum_daily_insight and sum_daily_full land. The seed at the bottom fills in
-- history ONCE — it is guarded to run only while the table is empty, so the
-- dev startup loop (schema-locations, mode=always) does not re-aggregate the
-- warehouse on every boot.
--
-- PROD
-- ----
-- schema.sql mode is "never" on prod: apply this file once via psql. The seed
-- scans sum_daily_insight and sum_daily_full in full — run it in a quiet
-- window. Until it has run, FinanceSummaryService falls back to the old
-- (slow, correct) queries for any range the rollup has no rows for.
-- ============================================================================

CREATE TABLE IF NOT EXISTS sum_daily_finance_rollup (
    tenant_id        INT  NOT NULL,
    business_date    DATE NOT NULL,

    -- Pivot measures (source: sum_daily_insight)
    dom_debit_cnt    BIGINT         NOT NULL DEFAULT 0,
    dom_debit_vol    DECIMAL(19, 2) NOT NULL DEFAULT 0,
    dom_debit_msf    DECIMAL(21, 4) NOT NULL DEFAULT 0,
    dom_debit_optin  DECIMAL(19, 2) NOT NULL DEFAULT 0,
    dom_credit_cnt   BIGINT         NOT NULL DEFAULT 0,
    dom_credit_vol   DECIMAL(19, 2) NOT NULL DEFAULT 0,
    dom_credit_msf   DECIMAL(21, 4) NOT NULL DEFAULT 0,
    dom_credit_optin DECIMAL(19, 2) NOT NULL DEFAULT 0,
    int_cnt          BIGINT         NOT NULL DEFAULT 0,
    int_vol          DECIMAL(19, 2) NOT NULL DEFAULT 0,
    int_msf          DECIMAL(21, 4) NOT NULL DEFAULT 0,
    int_optin        DECIMAL(19, 2) NOT NULL DEFAULT 0,
    total_vol        DECIMAL(19, 2) NOT NULL DEFAULT 0,
    total_msf        DECIMAL(21, 4) NOT NULL DEFAULT 0,

    -- Fee stack (source: sum_daily_full)
    dom_debit_ic     DECIMAL(21, 4) NOT NULL DEFAULT 0,
    dom_debit_sf     DECIMAL(21, 4) NOT NULL DEFAULT 0,
    dom_credit_ic    DECIMAL(21, 4) NOT NULL DEFAULT 0,
    dom_credit_sf    DECIMAL(21, 4) NOT NULL DEFAULT 0,
    int_ic           DECIMAL(21, 4) NOT NULL DEFAULT 0,
    int_sf           DECIMAL(21, 4) NOT NULL DEFAULT 0,
    total_ic         DECIMAL(21, 4) NOT NULL DEFAULT 0,
    total_sf         DECIMAL(21, 4) NOT NULL DEFAULT 0,
    fee_basis_msf    DECIMAL(21, 4) NOT NULL DEFAULT 0,
    -- PG / e-commerce gateway fee (sum_daily_full.total_ecom_fee). Whole-row
    -- only: it has no card-type split.
    total_pg         DECIMAL(21, 4) NOT NULL DEFAULT 0,

    -- pivot_built: sum_daily_insight had rows for the day. The screen lists a
    -- period only if some day in it is pivot_built — the exact row set the
    -- old pivot produced (fee-only days never made a row of their own).
    pivot_built      BOOLEAN NOT NULL DEFAULT FALSE,
    -- fees_built: sum_daily_full had rows for the day. Lets the screen tell
    -- "no fees this day" from "fees not built for this day" — zeros look the
    -- same otherwise and read as a broken report.
    fees_built       BOOLEAN NOT NULL DEFAULT FALSE,
    built_at         TIMESTAMP NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_id, business_date)
);

-- Same-day addition (PG fee column) for any environment that created the
-- table from the first cut of this file.
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS total_pg DECIMAL(21, 4) NOT NULL DEFAULT 0;
ALTER TABLE sum_daily_finance_rollup ADD COLUMN IF NOT EXISTS pivot_built BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sum_daily_finance_rollup ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_finance_rollup;
CREATE POLICY tenant_isolation_policy ON sum_daily_finance_rollup
    USING (tenant_id = get_current_tenant());

-- ----------------------------------------------------------------------------
-- One-time seed of history. Guarded: runs only while the table is empty.
-- ----------------------------------------------------------------------------
INSERT INTO sum_daily_finance_rollup (tenant_id, business_date,
    dom_debit_cnt, dom_debit_vol, dom_debit_msf, dom_debit_optin,
    dom_credit_cnt, dom_credit_vol, dom_credit_msf, dom_credit_optin,
    int_cnt, int_vol, int_msf, int_optin, total_vol, total_msf,
    dom_debit_ic, dom_debit_sf, dom_credit_ic, dom_credit_sf,
    int_ic, int_sf, total_ic, total_sf, fee_basis_msf, total_pg,
    pivot_built, fees_built, built_at)
SELECT COALESCE(p.tenant_id, f.tenant_id), COALESCE(p.business_date, f.business_date),
    COALESCE(p.dom_debit_cnt, 0), COALESCE(p.dom_debit_vol, 0), COALESCE(p.dom_debit_msf, 0), COALESCE(p.dom_debit_optin, 0),
    COALESCE(p.dom_credit_cnt, 0), COALESCE(p.dom_credit_vol, 0), COALESCE(p.dom_credit_msf, 0), COALESCE(p.dom_credit_optin, 0),
    COALESCE(p.int_cnt, 0), COALESCE(p.int_vol, 0), COALESCE(p.int_msf, 0), COALESCE(p.int_optin, 0),
    COALESCE(p.total_vol, 0), COALESCE(p.total_msf, 0),
    COALESCE(f.dom_debit_ic, 0), COALESCE(f.dom_debit_sf, 0), COALESCE(f.dom_credit_ic, 0), COALESCE(f.dom_credit_sf, 0),
    COALESCE(f.int_ic, 0), COALESCE(f.int_sf, 0), COALESCE(f.total_ic, 0), COALESCE(f.total_sf, 0), COALESCE(f.fee_basis_msf, 0),
    COALESCE(f.total_pg, 0),
    (p.business_date IS NOT NULL), (f.business_date IS NOT NULL), NOW()
FROM (
    SELECT s.tenant_id, s.business_date,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID') THEN s.total_txns ELSE 0 END)   AS dom_debit_cnt,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID') THEN s.total_volume ELSE 0 END) AS dom_debit_vol,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID') THEN s.total_msf ELSE 0 END)    AS dom_debit_msf,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID') AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) AS dom_debit_optin,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID') THEN s.total_txns ELSE 0 END)   AS dom_credit_cnt,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID') THEN s.total_volume ELSE 0 END) AS dom_credit_vol,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID') THEN s.total_msf ELSE 0 END)    AS dom_credit_msf,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID') AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) AS dom_credit_optin,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC' THEN s.total_txns ELSE 0 END)   AS int_cnt,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC' THEN s.total_volume ELSE 0 END) AS int_vol,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC' THEN s.total_msf ELSE 0 END)    AS int_msf,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC' AND s.is_opt_in = true THEN s.total_volume ELSE 0 END) AS int_optin,
        SUM(s.total_volume) AS total_vol,
        SUM(s.total_msf)    AS total_msf
    FROM sum_daily_insight s
    GROUP BY s.tenant_id, s.business_date
) p
FULL OUTER JOIN (
    SELECT s.tenant_id, s.business_date,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID') THEN COALESCE(s.total_interchange,0) ELSE 0 END) AS dom_debit_ic,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) IN ('DEBIT','PREPAID') THEN COALESCE(s.total_scheme_fee,0) ELSE 0 END)  AS dom_debit_sf,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID') THEN COALESCE(s.total_interchange,0) ELSE 0 END) AS dom_credit_ic,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) = 'DOMESTIC' AND UPPER(COALESCE(s.card_type,'')) NOT IN ('DEBIT','PREPAID') THEN COALESCE(s.total_scheme_fee,0) ELSE 0 END)  AS dom_credit_sf,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC' THEN COALESCE(s.total_interchange,0) ELSE 0 END) AS int_ic,
        SUM(CASE WHEN UPPER(COALESCE(s.destination,'')) <> 'DOMESTIC' THEN COALESCE(s.total_scheme_fee,0) ELSE 0 END)  AS int_sf,
        SUM(COALESCE(s.total_interchange,0)) AS total_ic,
        SUM(COALESCE(s.total_scheme_fee,0))  AS total_sf,
        SUM(COALESCE(s.total_msf,0))         AS fee_basis_msf,
        SUM(COALESCE(s.total_ecom_fee,0))    AS total_pg
    FROM sum_daily_full s
    GROUP BY s.tenant_id, s.business_date
) f ON p.tenant_id = f.tenant_id AND p.business_date = f.business_date
WHERE NOT EXISTS (SELECT 1 FROM sum_daily_finance_rollup)
ON CONFLICT (tenant_id, business_date) DO NOTHING;
