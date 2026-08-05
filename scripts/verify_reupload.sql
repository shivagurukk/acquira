-- =============================================================================
-- verify_reupload.sql — post-upload reconciliation suite
--
-- Run after ANY transaction upload / re-upload to prove the summary tables
-- agree with fact_transaction. Every check returns PASS or FAIL in a `result`
-- column; a fully healthy load returns PASS for all of them.
--
-- Usage (psql):
--     \set tenant 1
--     \set d1 '2026-05-01'
--     \set d2 '2026-05-31'
--     \i scripts/verify_reupload.sql
--
-- These are read-only SELECTs. Safe to run against production.
--
-- WHY THIS FILE EXISTS: the build has no database-backed test infrastructure
-- (no Testcontainers, no H2, no src/test/resources), and acquira-batch has no
-- src/test directory at all — so none of the ingestion or summary logic is
-- covered by an automated test. Until that infrastructure exists, this script
-- is the executable specification for "the re-upload worked".
-- =============================================================================

\echo '=== T1: fact vs sum_daily_bank (volume + count per day) ==='
-- The headline reconciliation. sum_daily_bank aggregates every fact row for a
-- day with no dimension join, so it must match fact exactly. Any drift here
-- means the summary rebuild did not cover the same rows the fact load wrote.
WITH f AS (
    SELECT DATE(payment_date) AS d, COUNT(*) AS txns, SUM(store_base_currency_amount) AS vol
    FROM fact_transaction
    WHERE tenant_id = :tenant AND DATE(payment_date) BETWEEN :'d1' AND :'d2'
    GROUP BY 1
), s AS (
    SELECT business_date AS d, total_txns AS txns, total_volume AS vol
    FROM sum_daily_bank
    WHERE tenant_id = :tenant AND business_date BETWEEN :'d1' AND :'d2'
)
SELECT COALESCE(f.d, s.d) AS business_date,
       f.txns AS fact_txns, s.txns AS summary_txns,
       f.vol  AS fact_vol,  s.vol  AS summary_vol,
       CASE WHEN f.d IS NULL                       THEN 'FAIL: orphan summary row (no fact data)'
            WHEN s.d IS NULL                       THEN 'FAIL: missing summary row'
            WHEN f.txns <> s.txns                  THEN 'FAIL: txn count mismatch'
            WHEN COALESCE(f.vol,0) <> COALESCE(s.vol,0) THEN 'FAIL: volume mismatch'
            ELSE 'PASS' END AS result
FROM f FULL OUTER JOIN s ON f.d = s.d
ORDER BY 1;

\echo '=== T2: sum_daily_bank vs sum_daily_merchant (unresolved-merchant drift) ==='
-- sum_daily_merchant INNER JOINs dim_merchant, so fact rows with a NULL or
-- unresolvable merchant_id are silently EXCLUDED from it while still counting
-- in sum_daily_bank. A non-zero delta here is not a rebuild bug — it is the
-- size of your unresolved-merchant problem, and it means the two dashboards
-- built on these tables will legitimately disagree.
SELECT b.business_date,
       b.total_txns AS bank_txns,
       COALESCE(m.txns, 0) AS merchant_txns,
       b.total_txns - COALESCE(m.txns, 0) AS unresolved_txns,
       CASE WHEN b.total_txns = COALESCE(m.txns, 0) THEN 'PASS'
            ELSE 'WARN: rows excluded from merchant grain (NULL merchant_id)' END AS result
FROM sum_daily_bank b
LEFT JOIN (
    SELECT business_date, SUM(total_txns) AS txns
    FROM sum_daily_merchant WHERE tenant_id = :tenant GROUP BY 1
) m ON m.business_date = b.business_date
WHERE b.tenant_id = :tenant AND b.business_date BETWEEN :'d1' AND :'d2'
ORDER BY 1;

\echo '=== T3: monthly rollups vs their daily source ==='
-- sum_monthly_bank is rebuilt from sum_daily_bank for the WHOLE month, even
-- when only some days were uploaded. This catches a partial-month upload that
-- rebuilt the monthly row from an incomplete daily set.
SELECT mb.month_key,
       mb.total_txns AS monthly_txns,
       d.txns        AS sum_of_daily_txns,
       CASE WHEN mb.total_txns = d.txns THEN 'PASS'
            ELSE 'FAIL: monthly rollup does not equal sum of its days' END AS result
FROM sum_monthly_bank mb
JOIN (
    SELECT CAST(TO_CHAR(business_date,'YYYYMM') AS INTEGER) AS mk,
           SUM(total_txns) AS txns
    FROM sum_daily_bank WHERE tenant_id = :tenant GROUP BY 1
) d ON d.mk = mb.month_key
WHERE mb.tenant_id = :tenant
  AND mb.month_key BETWEEN CAST(TO_CHAR(DATE :'d1','YYYYMM') AS INTEGER)
                       AND CAST(TO_CHAR(DATE :'d2','YYYYMM') AS INTEGER)
ORDER BY 1;

\echo '=== T4: stale / orphan summary rows (scenario 3) ==='
-- Every summary table must have a business_date that still exists in fact.
-- A row here means a merchant/day that vanished from the re-upload kept its
-- summary row — the exact "stale record" failure mode.
SELECT tbl, business_date, rows_orphaned,
       'FAIL: summary rows for a date with no fact data' AS result
FROM (
    SELECT 'sum_daily_bank' AS tbl, business_date, COUNT(*) AS rows_orphaned
      FROM sum_daily_bank s WHERE s.tenant_id = :tenant
       AND s.business_date BETWEEN :'d1' AND :'d2'
       AND NOT EXISTS (SELECT 1 FROM fact_transaction f
                       WHERE f.tenant_id = s.tenant_id AND DATE(f.payment_date) = s.business_date)
     GROUP BY 1,2
    UNION ALL
    SELECT 'sum_daily_merchant', business_date, COUNT(*)
      FROM sum_daily_merchant s WHERE s.tenant_id = :tenant
       AND s.business_date BETWEEN :'d1' AND :'d2'
       AND NOT EXISTS (SELECT 1 FROM fact_transaction f
                       WHERE f.tenant_id = s.tenant_id AND DATE(f.payment_date) = s.business_date)
     GROUP BY 1,2
    UNION ALL
    SELECT 'sum_daily_insight', business_date, COUNT(*)
      FROM sum_daily_insight s WHERE s.tenant_id = :tenant
       AND s.business_date BETWEEN :'d1' AND :'d2'
       AND NOT EXISTS (SELECT 1 FROM fact_transaction f
                       WHERE f.tenant_id = s.tenant_id AND DATE(f.payment_date) = s.business_date)
     GROUP BY 1,2
) x ORDER BY 1,2;

\echo '=== T5: orphan merchant rows within a day (scenario 3, finer grain) ==='
-- A merchant that traded on day D in upload #1 but is absent from upload #2
-- must NOT retain a sum_daily_merchant row for day D.
SELECT s.business_date, s.merchant_id, s.total_txns,
       'FAIL: merchant summary row with no matching fact rows' AS result
FROM sum_daily_merchant s
WHERE s.tenant_id = :tenant
  AND s.business_date BETWEEN :'d1' AND :'d2'
  AND NOT EXISTS (
      SELECT 1 FROM fact_transaction f
      WHERE f.tenant_id = s.tenant_id
        AND f.merchant_id = s.merchant_id
        AND DATE(f.payment_date) = s.business_date)
ORDER BY 1,2;

\echo '=== T6: duplicate transactions (scenario 7 - idempotency) ==='
-- fact_transaction has NO unique constraint on any business key (the PK is the
-- surrogate transaction_id + payment_date), so duplicates are only prevented by
-- the delete-before-insert in stagingToFactStep. If a re-upload duplicated rows,
-- the same ARN appears more than once for the same day and amount.
SELECT arn, DATE(payment_date) AS d, COUNT(*) AS copies,
       'FAIL: duplicate ARN for the same day' AS result
FROM fact_transaction
WHERE tenant_id = :tenant
  AND DATE(payment_date) BETWEEN :'d1' AND :'d2'
  AND NULLIF(TRIM(arn), '') IS NOT NULL
GROUP BY 1,2 HAVING COUNT(*) > 1
ORDER BY copies DESC LIMIT 50;

\echo '=== T7: rows stranded in the default partition ==='
-- Rows land here when no monthly partition existed at insert time (historical
-- backload). Not wrong, but they lose partition pruning permanently.
SELECT COUNT(*) AS rows_in_default_partition,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'WARN: backdated rows missed their partition' END AS result
FROM fact_transaction_default WHERE tenant_id = :tenant;

\echo '=== T8: activity snapshots that look backdate-broken ==='
-- Every merchant marked ONBOARDED with zero 30-day activity on a calc_date that
-- DOES have fact rows is the signature of the CURRENT_DATE-60-days window bug.
SELECT a.calc_date, COUNT(*) AS merchants_zeroed,
       CASE WHEN COUNT(*) = 0 THEN 'PASS'
            ELSE 'FAIL: zeroed activity rows on a date that has transactions' END AS result
FROM merchant_activity_summary a
WHERE a.tenant_id = :tenant
  AND a.calc_date BETWEEN :'d1' AND :'d2'
  AND a.status = 'ONBOARDED' AND COALESCE(a.last_30d_cnt,0) = 0
  AND EXISTS (SELECT 1 FROM fact_transaction f
              WHERE f.tenant_id = a.tenant_id AND f.merchant_id = a.merchant_id
                AND DATE(f.payment_date) = a.calc_date)
GROUP BY 1 ORDER BY 1;
