-- ============================================================================
-- V2026_09_04_02: Heal fact_rental rows that straddle the ABS sign fix.
--
-- THE DEFECT (seen on UAT 2026-09-04): loads made BEFORE commit 14b22bd stored
-- rental amounts with the feed's raw NEGATIVE sign and a row_hash built from
-- that negative amount. Re-uploading the same file on a FIXED backend
-- normalises the amount to positive BEFORE hashing, so the new hash never
-- collides with the old row and ON CONFLICT (tenant_id, row_hash) DO NOTHING
-- appends a SECOND, positive copy of every charge. Every entity then carries
-- -x and +x: all rental sums show exactly 0.000, charge counts double, the
-- one-time-fee coverage panel reports thousands of "billed 2+ times", and
-- Net Spread's rental column (AncillarySql over the same fact) reads 0.
--
-- THE HEAL, in order:
--   1. delete each stale negative row whose positive twin exists;
--   2. ABS the surviving negatives (charges only present in pre-fix loads)
--      and rebuild their hash from the normalised amount, so a future
--      re-upload of the same charge dedupes instead of duplicating again;
--   3. re-derive the rental_amount overlay on sum_daily_merchant and
--      sum_daily_finance_rollup from the healed fact (mirrors AncillarySql —
--      summary-rebuild-drift rule: if that class changes, change this too).
--
-- Idempotent (re-running finds no negatives and re-derives the same overlay).
-- Splitter-safe (no $$). On prod/UAT apply once via psql. Report caches may
-- serve the old zeros for up to the cache TTL — restart the pod or run any
-- ingest to evict immediately.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Drop stale negative rows that were re-appended as positive by a reload
-- ----------------------------------------------------------------------------
DELETE FROM fact_rental n
WHERE n.rental_amount < 0
  AND EXISTS (
    SELECT 1 FROM fact_rental p
    WHERE p.tenant_id = n.tenant_id
      AND p.level = n.level
      AND COALESCE(p.mid, '') = COALESCE(n.mid, '')
      AND COALESCE(p.sid, '') = COALESCE(n.sid, '')
      AND COALESCE(p.tid, '') = COALESCE(n.tid, '')
      AND p.payment_date = n.payment_date
      AND p.rental_amount = ABS(n.rental_amount));

-- ----------------------------------------------------------------------------
-- 2. Normalise negatives that have no positive twin (pre-fix-only charges)
-- ----------------------------------------------------------------------------
-- Hash rebuilt exactly like RentalJobConfig builds it (ids | amount | date,
-- with the NORMALISED amount) so the next upload of the same charge is a
-- dedupe no-op. Step 1 already removed every row whose normalised hash would
-- collide, so the unique (tenant_id, row_hash) constraint cannot trip here.
UPDATE fact_rental
SET rental_amount = ABS(rental_amount),
    row_hash = md5(COALESCE(mid, '') || '|' || COALESCE(sid, '') || '|'
                   || COALESCE(tid, '') || '|' || CAST(ABS(rental_amount) AS TEXT)
                   || '|' || CAST(payment_date AS TEXT))
WHERE rental_amount < 0;

-- ----------------------------------------------------------------------------
-- 3a. Re-derive rental_amount on sum_daily_merchant (AncillarySql mirror)
-- ----------------------------------------------------------------------------
UPDATE sum_daily_merchant SET rental_amount = 0 WHERE rental_amount <> 0;

INSERT INTO sum_daily_merchant (tenant_id, business_date, merchant_id,
    total_txns, total_volume, total_base_volume, total_msf, total_interchange,
    total_scheme_fee, total_margin, rental_amount)
SELECT tenant_id, payment_date, merchant_id, 0, 0, 0, 0, 0, 0, 0,
       SUM(rental_amount)
FROM fact_rental
WHERE merchant_id IS NOT NULL
GROUP BY tenant_id, payment_date, merchant_id
ON CONFLICT (tenant_id, business_date, merchant_id) DO UPDATE SET
    rental_amount = EXCLUDED.rental_amount;

-- Ancillary-only rows whose ancillary is now 0 again must go (same cleanup
-- rule as AncillarySql.MERCH_CLEANUP).
DELETE FROM sum_daily_merchant
WHERE COALESCE(total_txns, 0) = 0 AND COALESCE(total_volume, 0) = 0
  AND dcc_acquirer = 0 AND dcc_merchant = 0 AND rental_amount = 0;

-- ----------------------------------------------------------------------------
-- 3b. Re-derive rental_amount on sum_daily_finance_rollup
-- ----------------------------------------------------------------------------
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
