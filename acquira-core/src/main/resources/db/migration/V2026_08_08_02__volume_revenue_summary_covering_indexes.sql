-- ============================================================================
-- V2026_08_08_02: Covering indexes for the Volume & Revenue summary
-- (/business/volume-revenue → VolumeRevenueRepository.getSummaryLeg).
--
-- The unfiltered summary (the page's default "this year" load) groups a
-- tenant/date range by month and reads merchant_id (COUNT DISTINCT),
-- is_opt_in (opt-in split), destination (intl split) and the three measures.
-- The existing rollup indexes (V2026_06_29_01/_02) cover only
-- (merchant_id, store_id) + measures — is_opt_in and destination are missing,
-- so every index hit still costs a random heap fetch and the planner cannot
-- run the scan index-only. At production row counts that heap-fetch storm is
-- the difference between a sub-second and a multi-second (or timed-out) load.
--
-- These two indexes cover the summary's full column set, keyed exactly like
-- their siblings (tenant leading, then the date grain).
--
-- Idempotent (IF NOT EXISTS); splitter-safe (no $$). sum_daily_insight is
-- partitioned — plain CREATE INDEX on the parent cascades to every partition
-- and takes a lock while each builds. On an already-large production table,
-- build per-partition with CREATE INDEX CONCURRENTLY first (see the manual
-- variants in V2026_06_29_01), so this auto-run block becomes a no-op.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_sdi_summary_rollup
    ON sum_daily_insight (tenant_id, business_date)
    INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);

CREATE INDEX IF NOT EXISTS idx_smi_summary_rollup
    ON sum_monthly_insight (tenant_id, month_key)
    INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);

-- Attrition report (VolumeRevenueRepository.getAttritionReport): now routed to
-- the merchant-grain sum_daily_merchant when no card-dimension/store filters
-- apply. Its scan is (tenant, ~20-month date range) grouped by merchant_id,
-- summing the three measures — cover it so the whole report is one
-- index-only range scan. sum_daily_merchant is partitioned: same
-- per-partition CONCURRENTLY guidance as above for large production tables.
CREATE INDEX IF NOT EXISTS idx_sdm_attrition_rollup
    ON sum_daily_merchant (tenant_id, business_date, merchant_id)
    INCLUDE (total_txns, total_volume, total_msf);
