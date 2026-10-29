-- ============================================================================
-- Cross-tenant duplicate merchants: FIND, then FIX.
--
-- Symptom (2026-10-08): JUMEIRAH GULF OF BAHRAIN 2 exists in dim_merchant under
-- BOTH tenant 8 (correct) and tenant 1, with identical internal_id / mid / name /
-- emails. A tenant-1 report batch therefore emailed a Bahrain merchant from
-- tenant 1's SMTP. Cause: the same merchant-master file was imported under both
-- tenants; the upsert key is (tenant_id, internal_id) so the second import
-- INSERTed new rows instead of conflicting.
--
-- Usage: run section 1 first and READ the output. Only then run section 2.
-- Adjust the two tenant ids in the CTE below (wrong_tenant / right_tenant).
-- ============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. FIND
-- ─────────────────────────────────────────────────────────────────────────────

-- 1a. Which tenants share merchants, and how many?  (whole-database view)
SELECT a.tenant_id AS tenant_a, b.tenant_id AS tenant_b, COUNT(*) AS shared_merchants
FROM dim_merchant a
JOIN dim_merchant b
  ON  b.internal_id = a.internal_id
  AND b.mid         = a.mid
  AND b.tenant_id   > a.tenant_id
GROUP BY 1, 2
ORDER BY 3 DESC;

-- 1b. The duplicate list for the pair you care about.
--     wrong_tenant = the tenant that should NOT own these merchants.
DROP TABLE IF EXISTS tmp_xtenant_dups;
CREATE TEMP TABLE tmp_xtenant_dups AS
WITH p AS (SELECT 1::bigint AS wrong_tenant, 8::bigint AS right_tenant)
SELECT w.merchant_id AS wrong_merchant_id,
       r.merchant_id AS right_merchant_id,
       w.tenant_id   AS wrong_tenant,
       r.tenant_id   AS right_tenant,
       w.internal_id, w.mid, w.name, w.contact_email,
       w.generate_report_flag AS wrong_report_flag
FROM p
JOIN dim_merchant w ON w.tenant_id = p.wrong_tenant
JOIN dim_merchant r ON r.tenant_id = p.right_tenant
                   AND r.internal_id = w.internal_id
                   AND r.mid         = w.mid
                   AND r.name IS NOT DISTINCT FROM w.name;

SELECT COUNT(*)                                        AS dup_rows,
       COUNT(*) FILTER (WHERE wrong_report_flag = 1)   AS dup_rows_emailable
FROM tmp_xtenant_dups;

SELECT * FROM tmp_xtenant_dups ORDER BY name LIMIT 100;

-- 1c. Which import created the wrong-tenant copies?
SELECT e.job_execution_id, e.start_time, e.status,
       MAX(CASE WHEN p.parameter_name = 'tenantId'     THEN p.parameter_value END) AS tenant_id,
       MAX(CASE WHEN p.parameter_name = 'fullPath'     THEN p.parameter_value END) AS file,
       MAX(CASE WHEN p.parameter_name = 'triggeredBy'  THEN p.parameter_value END) AS who,
       MAX(CASE WHEN p.parameter_name = 'ingestSource' THEN p.parameter_value END) AS source
FROM batch_job_execution e
JOIN batch_job_instance i          ON i.job_instance_id = e.job_instance_id
JOIN batch_job_execution_params p  ON p.job_execution_id = e.job_execution_id
WHERE i.job_name = 'merchantMasterJob'
GROUP BY 1, 2, 3
ORDER BY e.start_time DESC
LIMIT 30;

-- 1d. Do the wrong-tenant copies carry any data?  If fact_transaction rows exist
--     under the wrong tenant, the TRANSACTION file was mis-routed too — stop and
--     treat that separately (do not delete the merchants).
SELECT 'fact_transaction' AS tbl, COUNT(*) FROM fact_transaction f
  JOIN tmp_xtenant_dups d ON d.wrong_merchant_id = f.merchant_id AND f.tenant_id = d.wrong_tenant
UNION ALL
SELECT 'dim_store', COUNT(*) FROM dim_store s
  JOIN tmp_xtenant_dups d ON d.wrong_merchant_id = s.merchant_id AND s.tenant_id = d.wrong_tenant
UNION ALL
SELECT 'sum_daily_merchant', COUNT(*) FROM sum_daily_merchant s
  JOIN tmp_xtenant_dups d ON d.wrong_merchant_id = s.merchant_id AND s.tenant_id = d.wrong_tenant
UNION ALL
SELECT 'email_queue (any status)', COUNT(*) FROM email_queue q
  JOIN tmp_xtenant_dups d ON d.wrong_merchant_id = q.merchant_id AND q.tenant_id = d.wrong_tenant;

-- 1e. Every table that references dim_merchant, with a row count for the
--     wrong-tenant copies. Generic, so nothing is missed as the schema grows.
DO $$
DECLARE r RECORD; n BIGINT;
BEGIN
  CREATE TEMP TABLE IF NOT EXISTS tmp_xtenant_refs(tbl TEXT, col TEXT, rows_hit BIGINT);
  TRUNCATE tmp_xtenant_refs;
  FOR r IN
    SELECT DISTINCT c.conrelid::regclass AS tbl, a.attname AS col
    FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
    WHERE c.contype = 'f' AND c.confrelid = 'dim_merchant'::regclass
  LOOP
    EXECUTE format('SELECT COUNT(*) FROM %s t JOIN tmp_xtenant_dups d ON d.wrong_merchant_id = t.%I',
                   r.tbl, r.col) INTO n;
    INSERT INTO tmp_xtenant_refs VALUES (r.tbl::text, r.col, n);
  END LOOP;
END $$;
SELECT * FROM tmp_xtenant_refs ORDER BY rows_hit DESC, tbl;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. FIX   (run only after reading section 1)
-- ─────────────────────────────────────────────────────────────────────────────
BEGIN;

-- 2a. Stop the wrong-tenant emails that are still waiting to go out.
UPDATE email_queue q
SET    status = 'CANCELLED',
       error_message = COALESCE(error_message, '') || ' [cross-tenant duplicate merchant, cancelled 2026-10-08]'
FROM   tmp_xtenant_dups d
WHERE  q.merchant_id = d.wrong_merchant_id
AND    q.tenant_id   = d.wrong_tenant
AND    q.status IN ('PENDING', 'RETRY', 'FAILED');

-- 2b. Immediately stop future batches picking them up, whatever happens below.
UPDATE dim_merchant m
SET    generate_report_flag = 0,
       status = 'INACTIVE'
FROM   tmp_xtenant_dups d
WHERE  m.merchant_id = d.wrong_merchant_id;

-- 2c. Hard-delete the wrong-tenant copies that nothing references.
--     Copies WITH dependents are left (flagged off above) and listed afterwards.
DO $$
DECLARE r RECORD;
BEGIN
  CREATE TEMP TABLE IF NOT EXISTS tmp_xtenant_blocked(merchant_id BIGINT PRIMARY KEY);
  TRUNCATE tmp_xtenant_blocked;
  FOR r IN
    SELECT DISTINCT c.conrelid::regclass AS tbl, a.attname AS col
    FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
    WHERE c.contype = 'f' AND c.confrelid = 'dim_merchant'::regclass
  LOOP
    EXECUTE format('INSERT INTO tmp_xtenant_blocked SELECT DISTINCT t.%I FROM %s t '
                || 'JOIN tmp_xtenant_dups d ON d.wrong_merchant_id = t.%I ON CONFLICT DO NOTHING',
                r.col, r.tbl, r.col);
  END LOOP;
  -- fact_transaction / email_queue may not carry an FK — include them explicitly.
  INSERT INTO tmp_xtenant_blocked
    SELECT DISTINCT f.merchant_id FROM fact_transaction f JOIN tmp_xtenant_dups d ON d.wrong_merchant_id = f.merchant_id
  ON CONFLICT DO NOTHING;
  INSERT INTO tmp_xtenant_blocked
    SELECT DISTINCT q.merchant_id FROM email_queue q JOIN tmp_xtenant_dups d ON d.wrong_merchant_id = q.merchant_id
  ON CONFLICT DO NOTHING;
END $$;

DELETE FROM dim_merchant m
USING tmp_xtenant_dups d
WHERE m.merchant_id = d.wrong_merchant_id
AND   NOT EXISTS (SELECT 1 FROM tmp_xtenant_blocked b WHERE b.merchant_id = m.merchant_id);

-- 2d. What is left behind (flagged off + INACTIVE, but still present because
--     other rows point at it). Decide on these by hand.
SELECT d.*, 'kept: has dependent rows' AS note
FROM tmp_xtenant_dups d
JOIN tmp_xtenant_blocked b ON b.merchant_id = d.wrong_merchant_id
ORDER BY d.name;

-- Check the counts, then:
-- COMMIT;     -- or ROLLBACK;
