-- Cleanup of orphan tenant_id=9 rows (a removed test tenant with no row in `tenant`).
-- Confirmed by a full scan on 2026-08-25: ALL orphan rows across the schema belong
-- to tenant_id=9 only. This is LOCAL-DB test residue, not production data.
-- Review the counts (SELECT block) before committing the DELETE block.

-- 1. Verify what will be removed
SELECT 'merchant_churn_score' t, count(*) FROM merchant_churn_score WHERE tenant_id=9
UNION ALL SELECT 'merchant_segment',         count(*) FROM merchant_segment         WHERE tenant_id=9
UNION ALL SELECT 'pdf_batch_log',            count(*) FROM pdf_batch_log            WHERE tenant_id=9
UNION ALL SELECT 'sales_agent_profile',      count(*) FROM sales_agent_profile      WHERE tenant_id=9
UNION ALL SELECT 'sales_country_lead',       count(*) FROM sales_country_lead       WHERE tenant_id=9
UNION ALL SELECT 'sales_team_mapping',       count(*) FROM sales_team_mapping       WHERE tenant_id=9
UNION ALL SELECT 'sales_user_assignment',    count(*) FROM sales_user_assignment    WHERE tenant_id=9
UNION ALL SELECT 'sum_daily_explorer',       count(*) FROM sum_daily_explorer       WHERE tenant_id=9
UNION ALL SELECT 'sum_daily_finance_rollup', count(*) FROM sum_daily_finance_rollup WHERE tenant_id=9
UNION ALL SELECT 'sum_daily_full',           count(*) FROM sum_daily_full           WHERE tenant_id=9
UNION ALL SELECT 'sum_monthly_insight',      count(*) FROM sum_monthly_insight      WHERE tenant_id=9
UNION ALL SELECT 'tenant_provision_log',     count(*) FROM tenant_provision_log     WHERE tenant_id=9;

-- 2. Delete (partitioned parents cascade to their _yXXXX children automatically)
BEGIN;
DELETE FROM merchant_churn_score     WHERE tenant_id = 9;
DELETE FROM merchant_segment         WHERE tenant_id = 9;
DELETE FROM pdf_batch_log            WHERE tenant_id = 9;
DELETE FROM sales_agent_profile      WHERE tenant_id = 9;
DELETE FROM sales_country_lead       WHERE tenant_id = 9;
DELETE FROM sales_team_mapping       WHERE tenant_id = 9;
DELETE FROM sales_user_assignment    WHERE tenant_id = 9;
DELETE FROM sum_daily_explorer       WHERE tenant_id = 9;
DELETE FROM sum_daily_finance_rollup WHERE tenant_id = 9;
DELETE FROM sum_daily_full           WHERE tenant_id = 9;
DELETE FROM sum_monthly_insight      WHERE tenant_id = 9;
DELETE FROM tenant_provision_log     WHERE tenant_id = 9;
COMMIT;

-- 3. Confirm none remain (should return 0 rows)
SELECT c.table_name, 9 AS orphan_tenant
FROM information_schema.columns c
WHERE c.column_name='tenant_id' AND c.table_schema='public' AND c.table_name<>'tenant'
  AND EXISTS (
    SELECT 1 FROM pg_class pc JOIN pg_namespace n ON n.oid=pc.relnamespace
    WHERE pc.relname=c.table_name AND n.nspname='public' AND pc.relispartition=false
  );
-- (informational list; to actually re-scan counts, re-run the scan query used during the audit)
