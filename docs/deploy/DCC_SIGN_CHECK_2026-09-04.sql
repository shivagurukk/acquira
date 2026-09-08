-- ============================================================================
-- DCC SIGN CHECK — 2026-09-04   (DIAGNOSTIC ONLY, changes nothing)
--
-- Rentals were normalised with ABS because the AFS extract posts them as a
-- DEBIT TO THE MERCHANT and they arrive uniformly negative. DCC is NOT
-- automatically the same case, so run this before deciding.
--
-- WHY DCC DIFFERS
--   * A rental row is a discrete fixed charge. A negative can only be a sign
--     convention.
--   * A DCC row is a markup collected from the CARDHOLDER and split. A negative
--     can legitimately mean a REFUND / reversal of a DCC transaction. If DCC
--     rows are a daily net per SID, a negative day just means refunds exceeded
--     markups that day.
--   * ABS would turn such a reversal into revenue and OVERSTATE the acquirer
--     take. That is the opposite of the bug we just fixed.
--
-- HOW TO READ THE RESULTS
--   ALL rows negative        -> sign convention, same as rentals. ABS is right.
--   Mostly positive, a few
--     negative               -> refunds/reversals. DO NOT ABS. Leave as is.
--   merchant_share and
--     acquirer_share differ
--     in sign                -> a ledger-perspective export (money to the
--                               merchant is a credit, money from them a debit).
--                               Needs a per-column decision, never a blanket ABS.
-- ============================================================================

-- 1. Sign mix per tenant, per column. The headline answer.
SELECT tenant_id,
       COUNT(*)                                            AS rows_total,
       COUNT(*) FILTER (WHERE acquirer_share < 0)          AS acq_negative,
       COUNT(*) FILTER (WHERE acquirer_share > 0)          AS acq_positive,
       COUNT(*) FILTER (WHERE acquirer_share = 0)          AS acq_zero,
       COUNT(*) FILTER (WHERE merchant_share < 0)          AS mer_negative,
       COUNT(*) FILTER (WHERE merchant_share > 0)          AS mer_positive,
       SUM(acquirer_share)                                 AS acq_sum,
       SUM(merchant_share)                                 AS mer_sum
FROM fact_dcc_revenue
GROUP BY tenant_id
ORDER BY tenant_id;

-- 2. Do the two columns ever disagree on sign? A ledger-perspective export
--    shows the merchant's credit positive and the acquirer's cut negative
--    (or vice versa). Any rows here mean a blanket ABS is definitely wrong.
SELECT tenant_id, COUNT(*) AS rows_with_opposite_signs
FROM fact_dcc_revenue
WHERE acquirer_share <> 0 AND merchant_share <> 0
  AND sign(acquirer_share) <> sign(merchant_share)
GROUP BY tenant_id
ORDER BY tenant_id;

-- 3. Are negatives concentrated on a few days (refund days) or spread evenly
--    across every day (convention)? Convention = negatives on ~every day.
SELECT payment_date,
       COUNT(*)                                   AS rows_on_day,
       COUNT(*) FILTER (WHERE acquirer_share < 0) AS negative_rows,
       SUM(acquirer_share)                        AS day_net
FROM fact_dcc_revenue
GROUP BY payment_date
ORDER BY payment_date
LIMIT 60;

-- 4. Sample the actual negative rows, to eyeball against the source file.
SELECT tenant_id, sid, merchant_share, acquirer_share, payment_date
FROM fact_dcc_revenue
WHERE acquirer_share < 0 OR merchant_share < 0
ORDER BY payment_date DESC, sid
LIMIT 25;

-- 5. What Net Spread is currently showing from DCC (this is the number on
--    /executive/net-spread's DCC column). dcc_merchant is informational and is
--    never part of the spread.
SELECT tenant_id,
       SUM(dcc_acquirer)  AS dcc_acquirer_in_summary,
       SUM(dcc_merchant)  AS dcc_merchant_in_summary,
       SUM(rental_amount) AS rental_in_summary
FROM sum_daily_merchant
GROUP BY tenant_id
ORDER BY tenant_id;
