-- ============================================================
-- V2026_06_29_03: sum_monthly_insight — one-time historical backfill
-- ============================================================
-- WHY
-- ---
-- V2026_06_29_02 created sum_monthly_insight, and populateSummaryStep now keeps
-- it current for every NEW upload (and any month a tenant re-processes). But the
-- months already sitting in sum_daily_insight BEFORE this deploy were never
-- rolled up to the monthly table. Until they are, getSummary()'s monthly routing
-- (VolumeRevenueRepository.canUseMonthly) would read an empty / partial
-- sum_monthly_insight for those historical whole-month ranges and UNDER-REPORT.
--
-- This migration performs the one-time catch-up: it rolls up EVERY month present
-- in sum_daily_insight into sum_monthly_insight, for ALL tenants at once. It is
-- the exact same aggregation the batch job runs per upload, just unscoped to a
-- single tenant/month set.
--
-- IDEMPOTENT — SAFE TO RE-RUN ON EVERY BOOT
-- -----------------------------------------
-- spring.sql.init runs the schema-locations list on every startup while
-- mode=always. This script is written to be safe under that: ON CONFLICT DO
-- UPDATE re-computes the SAME additive SUMs from the same daily rows, so a
-- second run is a no-op in terms of values (it just rewrites identical numbers).
-- It never duplicates rows (the UNIQUE key dedups) and never drops anything.
--
-- It also self-skips the expensive scan once the monthly table is already
-- caught up: the guard below only runs the rollup when sum_monthly_insight is
-- empty OR is missing months that exist in sum_daily_insight. On a steady-state
-- system (every month already rolled up) the INSERT is skipped entirely, so this
-- file costs ~one cheap EXISTS check per boot rather than a full re-aggregation.
--
-- PERFORMANCE NOTE (first run on a large warehouse)
-- -------------------------------------------------
-- On a multi-billion-row sum_daily_insight the first execution is a large
-- GROUP BY and can take a while + hold a write lock on sum_monthly_insight. For
-- a zero-stall cutover you can instead run this same statement MANUALLY via psql
-- BEFORE flipping mode=always→never, optionally tenant-by-tenant:
--     -- per tenant, to keep each transaction smaller:
--     INSERT INTO sum_monthly_insight (...) SELECT ... FROM sum_daily_insight
--     WHERE tenant_id = <T> GROUP BY ... ON CONFLICT (...) DO UPDATE SET ...;
-- Having run it manually, this migration's guard sees the table already caught
-- up and skips — so leaving it wired in is harmless.
-- ============================================================

DO $$
BEGIN
    -- Only do the heavy rollup if there is at least one (tenant, month) present
    -- in the daily table that is NOT yet present in the monthly table. This makes
    -- the migration cheap on every boot after the first successful backfill.
    IF EXISTS (
        SELECT 1
        FROM (
            SELECT DISTINCT tenant_id, CAST(TO_CHAR(business_date, 'YYYYMM') AS INTEGER) AS mk
            FROM sum_daily_insight
        ) d
        LEFT JOIN (
            SELECT DISTINCT tenant_id, month_key AS mk FROM sum_monthly_insight
        ) m ON m.tenant_id = d.tenant_id AND m.mk = d.mk
        WHERE m.mk IS NULL
    ) THEN

        INSERT INTO sum_monthly_insight (
            tenant_id, month_key, merchant_id, store_id, terminal_id,
            card_scheme, card_type, destination, channel, is_opt_in,
            total_txns, total_volume, total_msf
        )
        SELECT
            tenant_id,
            CAST(TO_CHAR(business_date, 'YYYYMM') AS INTEGER),
            merchant_id, store_id, terminal_id,
            card_scheme, card_type, destination, channel, is_opt_in,
            SUM(total_txns), SUM(total_volume), SUM(total_msf)
        FROM sum_daily_insight
        GROUP BY
            tenant_id, TO_CHAR(business_date, 'YYYYMM'),
            merchant_id, store_id, terminal_id,
            card_scheme, card_type, destination, channel, is_opt_in
        ON CONFLICT (tenant_id, month_key, merchant_id, store_id, terminal_id,
                     card_scheme, card_type, destination, channel, is_opt_in)
        DO UPDATE SET
            total_txns   = EXCLUDED.total_txns,
            total_volume = EXCLUDED.total_volume,
            total_msf    = EXCLUDED.total_msf;

        RAISE NOTICE 'sum_monthly_insight backfill: rolled up historical months from sum_daily_insight';
    ELSE
        RAISE NOTICE 'sum_monthly_insight backfill: already caught up - skipped';
    END IF;
END $$;
