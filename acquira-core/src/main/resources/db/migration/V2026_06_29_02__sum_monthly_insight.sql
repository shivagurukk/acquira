-- ============================================================
-- V2026_06_29_02: sum_monthly_insight — month-grain pre-aggregate
-- ============================================================
-- WHY
-- ---
-- sum_daily_insight is day-grained and, at 10 tenants x ~999K txns/day x 5y,
-- reaches the hundreds-of-millions to multi-billion row range. Even with the
-- covering indexes (V2026_06_29_01) and partition pruning, a WIDE date range
-- (a full year, or "all time") on the Explorer / Interactive / Business pages
-- still aggregates many months of day rows live. You cannot sum a billion rows
-- in 2 seconds; the only way to make wide ranges fast is to NOT read day rows
-- for them.
--
-- sum_monthly_insight is sum_daily_insight rolled up to month grain. A 12-month
-- query reads ~12 month-rows per (merchant x dimensional combo) instead of 365
-- day-rows — roughly a 30x reduction in rows scanned and aggregated. Queries
-- whose range is wider than a threshold (the app decides, e.g. > 90 days) read
-- this table; narrow ranges keep using sum_daily_insight for exact day grain.
--
-- GRAIN
-- -----
-- Identical dimensional grain to sum_daily_insight, with business_date replaced
-- by month_key (YYYYMM INT, same convention as sum_monthly_bank). Measures are
-- additive (SUM), so monthly = SUM(daily) reconciles exactly.
--
-- POPULATION
-- ----------
-- Written by populateSummaryStep (TransactionJobConfig) in the same pass that
-- writes sum_monthly_bank — rolled up FROM sum_daily_insight for the months in
-- scope, via INSERT ... ON CONFLICT DO UPDATE (idempotent re-aggregation).
--
-- NOT PARTITIONED
-- ---------------
-- Like sum_monthly_bank / sum_daily_mcc, this is a plain table. Even at 5y x 10
-- tenants its row count is the daily table divided by ~30, and month_key range
-- predicates + the index below keep it fast without partition overhead.
--
-- HOW THIS RUNS — migration file (NOT schema.sql)
-- -----------------------------------------------
-- schema.sql begins with DROP TABLE ... CASCADE for every table and is the dev
-- reset path; on prod it must NEVER run (spring.sql.init.mode=never after first
-- boot). New tables/columns therefore land via idempotent migration scripts
-- wired into spring.sql.init.schema-locations, exactly like the other
-- db/migration files. CREATE TABLE IF NOT EXISTS makes this safe to re-run.
-- ============================================================

CREATE TABLE IF NOT EXISTS sum_monthly_insight (
    summary_id   BIGSERIAL,
    tenant_id    INT NOT NULL,
    month_key    INT NOT NULL,          -- YYYYMM, e.g. 202606

    merchant_id  BIGINT,
    store_id     BIGINT,
    terminal_id  BIGINT,

    card_scheme  VARCHAR(50),
    card_type    VARCHAR(50),
    destination  VARCHAR(50),
    channel      VARCHAR(50),
    is_opt_in    BOOLEAN,

    total_txns   BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf    DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id),
    UNIQUE (tenant_id, month_key, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in)
);

-- Covering indexes mirroring the day-grain ones, so the same merchant/store and
-- card-dimension rollups are index-only on the monthly table too.
CREATE INDEX IF NOT EXISTS idx_smi_merchant_rollup
    ON sum_monthly_insight (tenant_id, month_key, merchant_id, store_id)
    INCLUDE (total_txns, total_volume, total_msf);

CREATE INDEX IF NOT EXISTS idx_smi_card_rollup
    ON sum_monthly_insight (tenant_id, month_key, card_scheme, card_type, destination, channel)
    INCLUDE (total_txns, total_volume, total_msf);

-- Row-Level Security: match the rest of the warehouse (defence-in-depth under
-- the app-level tenant scoping). get_current_tenant() is defined in schema.sql.
ALTER TABLE sum_monthly_insight ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'sum_monthly_insight' AND policyname = 'tenant_isolation_policy'
    ) THEN
        CREATE POLICY tenant_isolation_policy ON sum_monthly_insight
            USING (tenant_id = get_current_tenant());
    END IF;
END $$;
