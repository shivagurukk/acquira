-- ============================================================================
-- UAT ONLY — scoped reset of tenant 8 (AFS Bahrain) for a clean re-ingest.
-- ============================================================================
-- WHY: tenant 8 accumulated duplicate MERCHANT dimensions (32 mids = 1 real row
-- + AUTO_SID_ placeholders created when transactions were ingested before the
-- master). The buggy staging->fact joins (now fixed with LATERAL LIMIT 1) also
-- wrote fan-out fact rows. The terminal "duplicates" (tid '01','Benefit PG',
-- 'MPGS'…) are NOT real dupes — distinct terminals sharing a junk/gateway tid —
-- so they are left alone; the LATERAL fix resolves them at load time.
--
-- This wipes ONLY tenant 8's transactional + dimensional + summary data and its
-- staging, so you can re-ingest cleanly. Re-ingest order MUST be:
--     1) merchant master file   2) transaction file(s)
-- Loading the master first means NO AUTO_ placeholders are ever created.
--
-- Run on UAT only, in a maintenance window. Review counts before COMMIT.
-- Everything is scoped `WHERE tenant_id = 8`.
-- ============================================================================

\set TENANT 8

BEGIN;

-- 1. Fact (partitioned parent cascades to all fact_transaction_y* children)
DELETE FROM fact_transaction WHERE tenant_id = :TENANT;

-- 2. All daily/monthly summaries (partitioned parents cascade to _y* children)
DELETE FROM sum_daily_bank                 WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_merchant             WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_merchant_destination WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_mcc                   WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_scheme                WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_channel               WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_terminal              WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_finance               WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_finance_rollup        WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_insight               WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_full                  WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_local_debit_bin       WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_explorer              WHERE tenant_id = :TENANT;
DELETE FROM sum_daily_merchant_attribute    WHERE tenant_id = :TENANT;
DELETE FROM sum_monthly_bank                WHERE tenant_id = :TENANT;
DELETE FROM sum_monthly_card                WHERE tenant_id = :TENANT;
DELETE FROM sum_monthly_insight             WHERE tenant_id = :TENANT;
DELETE FROM sum_monthly_merchant_metrics    WHERE tenant_id = :TENANT;

-- 3. Merchant-derived / operational metadata keyed by merchant_id
DELETE FROM merchant_activity_summary   WHERE tenant_id = :TENANT;
DELETE FROM merchant_opportunity_score  WHERE tenant_id = :TENANT;
DELETE FROM merchant_churn_score        WHERE tenant_id = :TENANT;
DELETE FROM merchant_segment            WHERE tenant_id = :TENANT;
DELETE FROM merchant_lifecycle_status   WHERE tenant_id = :TENANT;
DELETE FROM merchant_contact            WHERE tenant_id = :TENANT;
DELETE FROM merchant_risk_profile       WHERE tenant_id = :TENANT;
DELETE FROM merchant_document           WHERE tenant_id = :TENANT;
DELETE FROM merchant_contract           WHERE tenant_id = :TENANT;
DELETE FROM merchant_note               WHERE tenant_id = :TENANT;
DELETE FROM merchant_sales_assignment_history WHERE tenant_id = :TENANT;
DELETE FROM merchant_settlement_config  WHERE tenant_id = :TENANT;
DELETE FROM revenue_leakage_flags       WHERE tenant_id = :TENANT;

-- 4. Dimensions — order matters: terminal -> store -> merchant (FK children first)
DELETE FROM dim_terminal WHERE tenant_id = :TENANT;
DELETE FROM dim_store    WHERE tenant_id = :TENANT;
DELETE FROM dim_merchant WHERE tenant_id = :TENANT;

-- 5. Staging (both raw feeds)
DELETE FROM stg_trnx_raw            WHERE tenant_id = :TENANT;
DELETE FROM stg_merchant_master_raw WHERE tenant_id = :TENANT;

-- 6. Verify nothing remains for tenant 8 in the key tables (expect all 0)
SELECT 'fact'        AS t, count(*) FROM fact_transaction WHERE tenant_id = :TENANT
UNION ALL SELECT 'dim_merchant', count(*) FROM dim_merchant WHERE tenant_id = :TENANT
UNION ALL SELECT 'dim_store',    count(*) FROM dim_store    WHERE tenant_id = :TENANT
UNION ALL SELECT 'dim_terminal', count(*) FROM dim_terminal WHERE tenant_id = :TENANT
UNION ALL SELECT 'sum_daily_full', count(*) FROM sum_daily_full WHERE tenant_id = :TENANT;

-- Review the counts above. If correct:
--   COMMIT;
-- otherwise:
--   ROLLBACK;
COMMIT;

-- ============================================================================
-- After COMMIT, re-ingest via the app with the FIXED build (LATERAL LIMIT 1),
-- MASTER FIRST then TRANSACTIONS, so no AUTO_ placeholders are created.
-- ============================================================================
