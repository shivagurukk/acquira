-- ============================================================================
-- Tenant 1 recovery — PART 2 (READ-ONLY). Run after part 1. Send all output.
--
-- What part 1 showed (2026-10-13):
--   * 12,870 tenant-1 merchants were DELETED from dim_merchant; the summaries
--     (1.44M sum_daily_merchant rows) and 91.6M fact rows still point at the
--     old merchant_ids.
--   * Volume & Revenue therefore joins to only 7% of the data
--     (94.5M of 1,292M AED for Sep-2026).
--   * dbPullMerchantJob for tenant 1 ran every night since (9–13 Oct) and
--     re-created the merchants with NEW merchant_ids.
--   * dbPullTransactionJob for tenant 1 has FAILED every night since 9 Oct.
--
-- Recovery idea: the stores survived. fact_transaction / sum_daily_insight
-- carry store_id + OLD merchant_id; dim_store now carries store_id + NEW
-- merchant_id (re-linked by the nightly pull). So old_id -> store_id -> new_id
-- gives a mapping, and the new rows can be moved back onto the old ids.
-- ============================================================================

-- 1. Why is the tenant-1 transaction pull failing?
SELECT e.job_execution_id, e.start_time, e.status, e.exit_code,
       LEFT(e.exit_message, 600) AS exit_message
FROM batch_job_execution e
JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
JOIN batch_job_execution_params p ON p.job_execution_id = e.job_execution_id
WHERE i.job_name = 'dbPullTransactionJob'
  AND p.parameter_name = 'tenantId' AND p.parameter_value = '1'
  AND e.status = 'FAILED'
ORDER BY e.start_time DESC
LIMIT 5;

SELECT run_id, tenant_id, status, started_at, ended_at, LEFT(error_message, 600) AS error_message
FROM ingest_run
WHERE tenant_id = 1 AND started_at >= TIMESTAMP '2026-10-09'
ORDER BY started_at DESC
LIMIT 10;

-- 2. Build the old -> new merchant_id mapping through store_id.
DROP TABLE IF EXISTS tmp_t1_map;
CREATE TEMP TABLE tmp_t1_map AS
WITH old_ids AS (
    SELECT DISTINCT s.merchant_id AS old_id, s.store_id
    FROM sum_daily_insight s
    WHERE s.tenant_id = 1
      AND s.store_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = s.merchant_id)
)
SELECT o.old_id, st.merchant_id AS new_id, COUNT(DISTINCT o.store_id) AS stores
FROM old_ids o
JOIN dim_store st ON st.store_id = o.store_id AND st.tenant_id = 1
JOIN dim_merchant nm ON nm.merchant_id = st.merchant_id AND nm.tenant_id = 1
GROUP BY o.old_id, st.merchant_id;

-- 2a. Coverage: how many of the 12,870 orphaned merchants can be mapped,
--     and is the mapping clean (exactly one new id per old id and vice versa)?
SELECT COUNT(*)                                   AS mapped_pairs,
       COUNT(DISTINCT old_id)                     AS old_ids_mapped,
       COUNT(DISTINCT new_id)                     AS new_ids_mapped,
       COUNT(*) - COUNT(DISTINCT old_id)          AS old_ids_with_2plus_new,   -- must be 0
       COUNT(*) - COUNT(DISTINCT new_id)          AS new_ids_with_2plus_old    -- must be 0
FROM tmp_t1_map;

-- 2b. Orphaned merchants that could NOT be mapped (no surviving store link).
SELECT COUNT(DISTINCT s.merchant_id) AS unmapped_old_ids,
       COUNT(*)                      AS unmapped_summary_rows,
       SUM(s.total_volume)           AS unmapped_volume
FROM sum_daily_merchant s
WHERE s.tenant_id = 1
  AND NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = s.merchant_id)
  AND NOT EXISTS (SELECT 1 FROM tmp_t1_map x WHERE x.old_id = s.merchant_id);

-- 2c. Ambiguous ones, if any (for inspection).
SELECT * FROM tmp_t1_map
WHERE old_id IN (SELECT old_id FROM tmp_t1_map GROUP BY old_id HAVING COUNT(*) > 1)
   OR new_id IN (SELECT new_id FROM tmp_t1_map GROUP BY new_id HAVING COUNT(*) > 1)
ORDER BY old_id LIMIT 50;

-- 3. Do the NEW merchant rows have anything hanging off them yet? (Expected:
--    only dim_store. If facts/summaries already reference new ids, say so.)
SELECT 'dim_store' AS tbl, COUNT(*) FROM dim_store d JOIN tmp_t1_map x ON x.new_id = d.merchant_id
UNION ALL SELECT 'fact_transaction', COUNT(*) FROM fact_transaction f JOIN tmp_t1_map x ON x.new_id = f.merchant_id
UNION ALL SELECT 'sum_daily_merchant', COUNT(*) FROM sum_daily_merchant s JOIN tmp_t1_map x ON x.new_id = s.merchant_id
UNION ALL SELECT 'sum_daily_insight', COUNT(*) FROM sum_daily_insight s JOIN tmp_t1_map x ON x.new_id = s.merchant_id
UNION ALL SELECT 'email_queue', COUNT(*) FROM email_queue q JOIN tmp_t1_map x ON x.new_id = q.merchant_id;

-- 4. Foreign keys that point at dim_merchant (the fix must defer these).
SELECT c.conname, c.conrelid::regclass AS tbl, c.condeferrable, c.confupdtype
FROM pg_constraint c
WHERE c.contype = 'f' AND c.confrelid = 'dim_merchant'::regclass
ORDER BY 2;

-- 5. Attributes the re-pulled rows lost. The nightly pull does not know the
--    RM / referral / risk / contact fields that were set in the app.
SELECT COUNT(*) FILTER (WHERE sales_user_id IS NOT NULL)      AS with_rm,
       COUNT(*) FILTER (WHERE referral_partner IS NOT NULL)   AS with_partner,
       COUNT(*) FILTER (WHERE contact_email IS NOT NULL)      AS with_contact_email,
       COUNT(*)                                               AS tenant1_merchants
FROM dim_merchant WHERE tenant_id = 1;

-- 6. Is the merchant master really shared? The 14,195 shared internal_ids from
--    part 1 mean tenant 1 and tenant 8 pull the same merchants. Which
--    integration configs feed dbPullMerchantJob, and are their source queries
--    tenant-filtered? (column names may differ — adjust if this errors)
SELECT * FROM integration_config
WHERE job_name ILIKE '%merchant%' OR name ILIKE '%merchant%'
LIMIT 10;
