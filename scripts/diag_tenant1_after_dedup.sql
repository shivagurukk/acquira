-- ============================================================================
-- Diagnose tenant-1 report damage after the cross-tenant duplicate-merchant
-- cleanup (2026-10-13). READ-ONLY. Run all of it and send the output.
--
-- Why the Executive Summary YTD still looks right while Volume & Revenue and
-- the other menus are wrong: Executive Summary reads sum_daily_bank /
-- sum_monthly_bank (tenant-level, no merchant_id). Every other report reads
-- sum_daily_insight / sum_daily_merchant / sum_daily_terminal JOINed to
-- dim_merchant. If tenant 1's dim_merchant rows were deleted or flagged, those
-- joins drop the rows while the bank-level totals are untouched.
-- ============================================================================

-- 1. How many merchants does each tenant have now, by status / report flag?
SELECT tenant_id, status, generate_report_flag, COUNT(*) AS merchants
FROM dim_merchant
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

-- 2. Tenant-1 merchant-level summary rows that no longer have a merchant.
--    Non-zero here = dim_merchant rows were DELETED (not just inactivated).
SELECT 'sum_daily_merchant'  AS tbl, COUNT(*) AS orphan_rows, COUNT(DISTINCT s.merchant_id) AS orphan_merchants
FROM sum_daily_merchant s LEFT JOIN dim_merchant m ON m.merchant_id = s.merchant_id
WHERE s.tenant_id = 1 AND m.merchant_id IS NULL
UNION ALL
SELECT 'sum_daily_insight', COUNT(*), COUNT(DISTINCT s.merchant_id)
FROM sum_daily_insight s LEFT JOIN dim_merchant m ON m.merchant_id = s.merchant_id
WHERE s.tenant_id = 1 AND m.merchant_id IS NULL
UNION ALL
SELECT 'sum_daily_terminal', COUNT(*), COUNT(DISTINCT s.merchant_id)
FROM sum_daily_terminal s LEFT JOIN dim_merchant m ON m.merchant_id = s.merchant_id
WHERE s.tenant_id = 1 AND m.merchant_id IS NULL;

-- 3. Did tenant 1 lose transactions? (Compare with ~75.2M reported by the guard.)
SELECT tenant_id, COUNT(*) AS fact_rows, MIN(payment_date), MAX(payment_date)
FROM fact_transaction
WHERE tenant_id IN (1, 8)
GROUP BY 1;

-- 4. Same month, two sources: bank-level (what Executive Summary shows) vs
--    merchant-level joined to dim_merchant (what Volume & Revenue shows).
SELECT 'sum_daily_bank'                 AS source, SUM(total_volume) AS volume, SUM(total_txns) AS txns
FROM sum_daily_bank
WHERE tenant_id = 1 AND business_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-30'
UNION ALL
SELECT 'sum_daily_merchant (all rows)', SUM(total_volume), SUM(total_txns)
FROM sum_daily_merchant
WHERE tenant_id = 1 AND business_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-30'
UNION ALL
SELECT 'sum_daily_merchant JOIN dim_merchant', SUM(s.total_volume), SUM(s.total_txns)
FROM sum_daily_merchant s JOIN dim_merchant m ON m.merchant_id = s.merchant_id AND m.tenant_id = s.tenant_id
WHERE s.tenant_id = 1 AND s.business_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-30';

-- 5. Are tenant 1 and tenant 8 really the same merchant master? If this is in
--    the thousands, the "duplicates" were tenant 1's real merchants.
SELECT COUNT(*) AS shared_internal_ids
FROM dim_merchant a JOIN dim_merchant b
  ON b.internal_id = a.internal_id AND b.mid = a.mid
WHERE a.tenant_id = 1 AND b.tenant_id = 8;

-- 6. Last few summary batch runs — did anything re-aggregate tenant 1 after
--    the cleanup? A rebuild inner-joins dim_merchant, so it would write empty
--    merchant summaries if the merchants were gone.
SELECT e.job_execution_id, i.job_name, e.start_time, e.status,
       MAX(CASE WHEN p.parameter_name = 'tenantId' THEN p.parameter_value END) AS tenant_id
FROM batch_job_execution e
JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
LEFT JOIN batch_job_execution_params p ON p.job_execution_id = e.job_execution_id
WHERE e.start_time >= TIMESTAMP '2026-10-08 00:00'
GROUP BY 1, 2, 3, 4
ORDER BY e.start_time DESC
LIMIT 20;
