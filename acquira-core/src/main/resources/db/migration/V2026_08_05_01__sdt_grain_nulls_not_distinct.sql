-- =============================================================================
-- V2026_08_05_01 — sum_daily_terminal: stop the rollup double-counting rows
--                  whose store_id / terminal_id are NULL
--
-- PROBLEM
-- -------
-- populateSummaryStep (TransactionJobConfig.java:1428-1440) upserts the daily
-- terminal-grain summary with:
--
--     ON CONFLICT (tenant_id, business_date, merchant_id, store_id, terminal_id)
--     DO UPDATE SET ...
--
-- The arbiter behind that clause is
--     sum_daily_terminal_tenant_id_business_date_merchant_id_stor_key
--     UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id)
-- and store_id / terminal_id / merchant_id are all NULLABLE.
--
-- A plain UNIQUE constraint is NULLS DISTINCT: two rows that are NULL in a key
-- column never collide. So for any summary row whose store or terminal did not
-- resolve, the ON CONFLICT arbiter NEVER matches and each rollup run INSERTS a
-- fresh duplicate instead of updating the existing row.
--
-- Those NULLs are not exotic. The main staging->fact INSERT..SELECT leaves
-- store_id / terminal_id NULL whenever the SID/TID columns are blank or do not
-- match dim_store / dim_terminal; the two fix-up UPDATEs in stagingToFact (see
-- V2026_08_03_01) resolve most but not all of them.
--
-- IMPACT
-- ------
-- Every affected merchant's total_base_volume, total_msf, total_interchange,
-- total_scheme_fee, total_ecom_fee and total_revenue are summed once per rollup
-- run that covered the date. The CEO Volume & Revenue and Loss-Making Merchants
-- screens read this table directly, so the reported figures are inflated by that
-- multiple. On the Loss-Making screen the damage is compounded: the
-- HAVING SUM(total_revenue) < 0 predicate is evaluated on the inflated total, so
-- a re-run can drag a marginally profitable merchant onto the loss list and
-- exaggerate every genuine loss on it.
--
--   Reproduced: three identical upserts of a -500 loss with NULL store/terminal
--   produce three rows summing to -1500. The same three upserts with non-NULL
--   store/terminal collapse to one row at -500.
--
-- FIX
-- ---
-- 1. Collapse the duplicates that already exist, keeping the row written by the
--    MOST RECENT rollup (MAX(summary_id)). Each run writes the complete SUM for
--    its grain, so the newest surviving row already holds the correct value --
--    no rollup re-run is required after this migration.
-- 2. Replace the UNIQUE constraint with a NULLS NOT DISTINCT unique index
--    (PostgreSQL 15+; this deployment is 18.1), which makes the arbiter treat
--    NULLs as equal -- exactly what the ON CONFLICT clause already assumes.
--    The Java upsert needs no change: ON CONFLICT infers its arbiter from the
--    column list, and matches a unique index just as it matched the constraint.
--
-- sum_daily_terminal is RANGE-partitioned on business_date, which is part of the
-- key, so the unique index is creatable on the partitioned parent and propagates
-- to sum_daily_terminal_y2024 / _y2025 / _default automatically.
--
-- APPLY ONCE, VIA psql -- do NOT add to spring.sql.init.schema-locations.
-- Both statements are individually safe to re-run, but prod boots with
-- spring.sql.init.mode=always and the dedupe DELETE is a full-table scan; it has
-- no business running on every restart. Follows the same apply-once convention
-- as V2026_08_01_01 and V2026_08_03_01.
--
--   psql -h <host> -U <user> -d <db> -1 -f V2026_08_05_01__sdt_grain_nulls_not_distinct.sql
--
-- Take a backup first. Verify the scale of the problem before and after with:
--
--   SELECT COUNT(*) AS duplicate_grains FROM (
--     SELECT 1 FROM sum_daily_terminal
--     GROUP BY tenant_id, business_date, merchant_id, store_id, terminal_id
--     HAVING COUNT(*) > 1) d;
--
-- KNOWN RELATED (deliberately NOT fixed here -- separate change, separate blast
-- radius): sum_daily_mcc has the identical defect. Its conflict target is
-- (tenant_id, business_date, mcc, card_scheme); mcc and card_scheme are both
-- nullable and neither is coalesced in the INSERT..SELECT
-- (TransactionJobConfig.java:1381-1390). sum_daily_channel and sum_daily_scheme
-- are safe -- they COALESCE their nullable key column to a sentinel.
-- =============================================================================

-- ── 1. Collapse existing duplicates, keeping the newest row per grain ────────
-- IS NOT DISTINCT FROM is required throughout: plain = never matches NULL, which
-- is the very bug being repaired.
WITH keep AS (
    SELECT tenant_id, business_date, merchant_id, store_id, terminal_id,
           MAX(summary_id) AS keep_id
    FROM sum_daily_terminal
    GROUP BY tenant_id, business_date, merchant_id, store_id, terminal_id
    HAVING COUNT(*) > 1
)
DELETE FROM sum_daily_terminal s
USING keep k
WHERE s.tenant_id     =                  k.tenant_id
  AND s.business_date =                  k.business_date
  AND s.merchant_id   IS NOT DISTINCT FROM k.merchant_id
  AND s.store_id      IS NOT DISTINCT FROM k.store_id
  AND s.terminal_id   IS NOT DISTINCT FROM k.terminal_id
  AND s.summary_id   <> k.keep_id;

-- ── 2. Swap the NULLS DISTINCT constraint for a NULLS NOT DISTINCT index ─────
ALTER TABLE sum_daily_terminal
    DROP CONSTRAINT IF EXISTS sum_daily_terminal_tenant_id_business_date_merchant_id_stor_key;

CREATE UNIQUE INDEX IF NOT EXISTS sum_daily_terminal_grain_key
    ON sum_daily_terminal (tenant_id, business_date, merchant_id, store_id, terminal_id)
    NULLS NOT DISTINCT;
