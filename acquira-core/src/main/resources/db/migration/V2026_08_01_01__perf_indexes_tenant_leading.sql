-- ============================================================================
-- V2026_08_01_01: Tenant-leading indexes for the report read path, and removal
--   of duplicate / dead-weight indexes on the hottest write tables.
--
-- ⚠ RUN THIS WITH psql DIRECTLY — do NOT add it to spring.sql.init.schema-
--   locations. It uses CREATE INDEX CONCURRENTLY, which cannot run inside a
--   transaction, and Spring's sql.init wraps each script in one (the same
--   reason V2026_05_07_01 had to leave its CONCURRENTLY variants commented
--   out as a manual pre-step).
--
--     dev : psql -h 127.0.0.1 -p 5433 -U postgres     -d postgres   -f V2026_08_01_01__perf_indexes_tenant_leading.sql
--     prod: psql -h 127.0.0.1 -p 5432 -U acquira_user -d acquira_db -f V2026_08_01_01__perf_indexes_tenant_leading.sql
--
-- WHY (perf audit 2026-08-01, 6 tenants x ~900k txns/day):
--   1. sum_daily_merchant_attribute has NO tenant-leading index at all, yet
--      every read is `WHERE tenant_id = ? AND business_date BETWEEN ? AND ?`.
--      The planner falls back to partition seq scans.
--   2. merchant_daily_metrics (deliberately unpartitioned, grows ~#merchants
--      per day forever) is read as (tenant_id, report_date) by the daily
--      dashboard, but its indexes are (report_date), (mid), (merchant_id).
--   3. sum_monthly_card — one row per card x merchant x month, the largest
--      unpartitioned table in the system — has only (merchant_id, month_key).
--   4. idx_fact_transaction_tenant_merchant_date (V2026_05_07_01) duplicates
--      idx_fact_txn_tenant_merchant_date (schema.sql) column-for-column on the
--      hottest write table: double index maintenance on every ingest row.
--   5. idx_fact_txn_card indexes card_number over the whole fact table for no
--      report query (card lookups go through sum_monthly_card); at 300M+
--      rows/yr it is the single most expensive index to maintain.
--
-- All statements are idempotent (IF NOT EXISTS / IF EXISTS).
--
-- LOCKING NOTES:
--   * CONCURRENTLY is used only on the UNPARTITIONED tables — PostgreSQL does
--     not support CREATE/DROP INDEX CONCURRENTLY on a partitioned parent.
--   * The partitioned-table statements (1, 4, 5) take a SHARE lock: reads
--     continue, writes to those tables block for the build/drop duration.
--     Run this OUTSIDE the ingest window (ingest is the only writer).
--   * Re-run after any CONCURRENTLY failure — it leaves an INVALID index
--     behind; `DROP INDEX` it first if \d shows one.
-- ============================================================================

-- 1. Tenant-leading read path for the EAV attribute summary.
--    (Partitioned parent — plain CREATE INDEX cascades to every partition.)
CREATE INDEX IF NOT EXISTS idx_sdma_tenant_date_merchant
    ON sum_daily_merchant_attribute (tenant_id, business_date, merchant_id, attribute_type);

-- 2. Daily-dashboard read path (unpartitioned — safe to build online).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mdm_tenant_report_date
    ON merchant_daily_metrics (tenant_id, report_date);

-- 3. Month-grain card rollup reads (unpartitioned — safe to build online).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_smc_tenant_month_merchant
    ON sum_monthly_card (tenant_id, month_key, merchant_id);

-- 4. Duplicate of idx_fact_txn_tenant_merchant_date — drop the migration copy.
--    (Partitioned index — CONCURRENTLY not supported; drop is metadata-fast.)
DROP INDEX IF EXISTS idx_fact_transaction_tenant_merchant_date;

-- 5. Unused, write-amplifying single-column index on the fact table.
DROP INDEX IF EXISTS idx_fact_txn_card;

-- Self-register (visible in Admin > Tenant Provisioning > Migration Registry).
INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_08_01_01__perf_indexes_tenant_leading',
        'Tenant-leading indexes for report reads (sdma, mdm, smc); drop duplicate + card index on fact_transaction',
        TRUE, FALSE, 'perf-audit')
ON CONFLICT (migration_name) DO NOTHING;
