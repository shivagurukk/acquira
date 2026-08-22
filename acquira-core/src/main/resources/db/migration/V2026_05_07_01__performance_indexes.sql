-- ============================================================
-- V2026_05_07_01: Performance indexes for batch ingestion
-- ============================================================
-- pg_stat_user_indexes showed idx_dim_store_sid, idx_dim_terminal_tid,
-- idx_dim_terminal_store, and idx_dim_merchant_mid had ZERO scans, while
-- the batch ingestion SQL (TransactionJobConfig.stagingToFactTasklet and
-- the populateSummary aggregations) relies on lookups by
-- (tenant_id, sid / mid / tid) and (tenant_id, merchant_id, payment_date).
-- Without composite indexes that lead with tenant_id, the planner falls
-- back to sequential scans, making uploads take 10+ minutes on tenants
-- with many existing rows.
--
-- HOW THIS RUNS
-- -------------
-- This project does NOT use Flyway or Liquibase. SQL is applied via
-- Spring's spring.sql.init mechanism. This file is wired into
-- application.properties / application-prod.properties through:
--
--   spring.sql.init.schema-locations=classpath:schema.sql,\
--       classpath:db/migration/V2026_05_07_01__performance_indexes.sql
--
-- Spring runs each listed script on startup inside a transaction.
-- Therefore the statements below intentionally do NOT use
-- CREATE INDEX CONCURRENTLY (which is illegal inside a transaction).
--
-- Every statement is guarded with IF NOT EXISTS, so this file is
-- idempotent: the index build cost is paid only on the first startup
-- after deploy; subsequent startups skip already-existing indexes in
-- milliseconds.
--
-- FIRST-RUN WARNING (large existing tables)
-- -----------------------------------------
-- A plain CREATE INDEX takes a SHARE lock that blocks writes to the
-- table while the index builds. On an already-large fact_transaction
-- this means the first startup after deploy can block uploads for
-- several minutes. If that is unacceptable for a production cutover,
-- build the indexes ONCE manually via psql using the CONCURRENTLY
-- variants at the bottom of this file BEFORE deploying this build,
-- then this script becomes a no-op on startup.
-- ============================================================

-- ── Dimension lookups used by stagingToFact joins ──
CREATE INDEX IF NOT EXISTS idx_dim_store_tenant_sid
    ON dim_store (tenant_id, sid)
    WHERE sid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dim_merchant_tenant_mid
    ON dim_merchant (tenant_id, mid)
    WHERE mid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dim_terminal_tenant_tid
    ON dim_terminal (tenant_id, tid)
    WHERE tid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dim_terminal_tenant_store
    ON dim_terminal (tenant_id, store_id);

-- ── Staging-table scans (distinct-date + load-time queries) ──
CREATE INDEX IF NOT EXISTS idx_stg_merchant_tenant_loadtime
    ON stg_merchant_master_raw (tenant_id, load_time);

CREATE INDEX IF NOT EXISTS idx_stg_trnx_tenant_paydate
    ON stg_trnx_raw (tenant_id, payment_date);

-- ── Fact + summary aggregation paths ──
-- (V2026_08_01_01) idx_fact_transaction_tenant_merchant_date REMOVED: it was a
-- column-for-column duplicate of idx_fact_txn_tenant_merchant_date from
-- schema.sql — two identical indexes maintained on every ingest row of the
-- hottest table. Recreating it here on startup would undo that migration's
-- DROP, so the statement is gone, not commented.

CREATE INDEX IF NOT EXISTS idx_sum_daily_merchant_tenant_date
    ON sum_daily_merchant (tenant_id, business_date);

-- ── Zero Transaction Report: per-terminal last-activity lookup ──
-- The report derives each terminal's last active day via a correlated
-- subquery:  SELECT MAX(business_date) FROM sum_daily_terminal
--            WHERE terminal_id = ? AND tenant_id = ?
-- Now that the report runs over the FULL portfolio (server-side pagination,
-- no more LIMIT 500), this index lets the MAX be answered straight from the
-- index per terminal instead of scanning sum_daily_terminal repeatedly.
-- Leads with tenant_id (consistent with the other composite indexes) and
-- ends with business_date so the MAX is a cheap index scan.
CREATE INDEX IF NOT EXISTS idx_sum_daily_terminal_tenant_terminal_date
    ON sum_daily_terminal (tenant_id, terminal_id, business_date);

-- ============================================================
-- MANUAL CONCURRENTLY VARIANTS (do NOT run inside a transaction)
-- ============================================================
-- Run these by hand via psql on a large production database BEFORE
-- deploying, so the auto-run block above finds every index already
-- present and becomes an instant no-op. Run them ONE AT A TIME;
-- CONCURRENTLY cannot be combined with other statements in a
-- transaction and must be issued outside any BEGIN/COMMIT block.
--
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dim_store_tenant_sid
--     ON dim_store (tenant_id, sid) WHERE sid IS NOT NULL;
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dim_merchant_tenant_mid
--     ON dim_merchant (tenant_id, mid) WHERE mid IS NOT NULL;
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dim_terminal_tenant_tid
--     ON dim_terminal (tenant_id, tid) WHERE tid IS NOT NULL;
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dim_terminal_tenant_store
--     ON dim_terminal (tenant_id, store_id);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stg_merchant_tenant_loadtime
--     ON stg_merchant_master_raw (tenant_id, load_time);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stg_trnx_tenant_paydate
--     ON stg_trnx_raw (tenant_id, payment_date);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fact_transaction_tenant_merchant_date
--     ON fact_transaction (tenant_id, merchant_id, payment_date);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sum_daily_merchant_tenant_date
--     ON sum_daily_merchant (tenant_id, business_date);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sum_daily_terminal_tenant_terminal_date
--     ON sum_daily_terminal (tenant_id, terminal_id, business_date);
-- ============================================================
