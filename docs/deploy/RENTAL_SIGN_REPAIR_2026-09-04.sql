-- ============================================================================
-- RENTAL SIGN REPAIR — 2026-09-04
--
-- WHY
-- ---
-- The AFS extract reports a rental as a DEBIT POSTED TO THE MERCHANT, so the
-- amount arrives negative ('...,03362738,-3,08-MAY-26'). To the acquirer that
-- same charge is INCOME, and Net Spread ADDS rental to net margin
--     net_spread = total_margin + dcc_acquirer + rental_amount
-- so a negative rental SUBTRACTED revenue and pushed the spread DOWN.
--
-- RentalJobConfig now normalises the sign in staging (ABS) before the dedupe
-- hash, so every FUTURE load lands positive. This script repairs rows that
-- were ALREADY applied with the negative sign, and re-derives the summary
-- columns that were built from them.
--
-- Run AFTER deploying the matching batch build. Idempotent: the UPDATEs match
-- nothing on a second run.
--
--   psql -h <host> -U <user> -d <db> -f RENTAL_SIGN_REPAIR_2026-09-04.sql
--
-- CHECK FIRST — see what you are about to change:
--   SELECT tenant_id, sign(rental_amount) AS sgn, COUNT(*), SUM(rental_amount)
--   FROM fact_rental GROUP BY 1,2 ORDER BY 1,2;
-- If any tenant shows a MIX of signs, stop and review: a genuine credit or
-- reversal would be flipped into a charge by this script.
-- ============================================================================

BEGIN;

-- 1. The fact.
UPDATE fact_rental SET rental_amount = ABS(rental_amount) WHERE rental_amount < 0;

-- 2. Latest-charge convenience columns on the dims (same statements the apply
--    tasklet runs, so they re-derive from the corrected fact).
UPDATE dim_merchant m SET rental_amount = l.rental_amount FROM (
  SELECT DISTINCT ON (merchant_id) tenant_id, merchant_id, rental_amount FROM fact_rental
  WHERE level='MERCHANT' AND merchant_id IS NOT NULL
  ORDER BY merchant_id, payment_date DESC, rental_id DESC) l
WHERE m.tenant_id = l.tenant_id AND m.merchant_id = l.merchant_id;

UPDATE dim_store s SET rental_amount = l.rental_amount FROM (
  SELECT DISTINCT ON (store_id) tenant_id, store_id, rental_amount FROM fact_rental
  WHERE level='STORE' AND store_id IS NOT NULL
  ORDER BY store_id, payment_date DESC, rental_id DESC) l
WHERE s.tenant_id = l.tenant_id AND s.store_id = l.store_id;

UPDATE dim_terminal t SET rental_amount = l.rental_amount FROM (
  SELECT DISTINCT ON (terminal_id) tenant_id, terminal_id, rental_amount FROM fact_rental
  WHERE level='TERMINAL' AND terminal_id IS NOT NULL
  ORDER BY terminal_id, payment_date DESC, rental_id DESC) l
WHERE t.tenant_id = l.tenant_id AND t.terminal_id = l.terminal_id;

-- 3. Re-derive the ancillary summary columns from the corrected fact — the
--    same four statements AncillarySql runs, widened to every tenant/day that
--    fact_rental touches. Net Spread reads these, so without this step the
--    dashboards keep showing the old negative totals.
UPDATE sum_daily_merchant s SET rental_amount = 0
WHERE s.rental_amount <> 0;

INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id,
    total_txns, total_volume, total_base_volume, total_msf, total_interchange,
    total_scheme_fee, total_margin, rental_amount)
SELECT tenant_id, payment_date, merchant_id, 0, 0, 0, 0, 0, 0, 0, SUM(rental_amount)
FROM fact_rental
WHERE merchant_id IS NOT NULL
GROUP BY tenant_id, payment_date, merchant_id
ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

-- Ancillary-only rows whose ancillary is now 0 must go, or they read as
-- fake-active merchant-days forever.
DELETE FROM sum_daily_merchant
WHERE COALESCE(total_txns,0) = 0 AND COALESCE(total_volume,0) = 0
  AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0;

UPDATE sum_daily_finance_rollup SET rental_amount = 0 WHERE rental_amount <> 0;

INSERT INTO sum_daily_finance_rollup (tenant_id, business_date, rental_amount)
SELECT tenant_id, payment_date, SUM(rental_amount)
FROM fact_rental
GROUP BY tenant_id, payment_date
ON CONFLICT (tenant_id, business_date) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

DELETE FROM sum_daily_finance_rollup
WHERE pivot_built = FALSE AND fees_built = FALSE
  AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0;

COMMIT;

ANALYZE fact_rental;
ANALYZE sum_daily_merchant;
ANALYZE sum_daily_finance_rollup;

-- VERIFY — every row should now be >= 0, and the summary should match the fact:
--   SELECT MIN(rental_amount) FROM fact_rental;                       -- >= 0
--   SELECT SUM(rental_amount) FROM fact_rental;                       -- = below
--   SELECT SUM(rental_amount) FROM sum_daily_finance_rollup;          -- = above
