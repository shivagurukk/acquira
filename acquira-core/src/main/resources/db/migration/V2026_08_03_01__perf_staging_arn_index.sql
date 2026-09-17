-- =============================================================================
-- V2026_08_03_01 — ingestion performance: index the staging join key
--
-- The stagingToFact step resolves any store_id / terminal_id the main
-- INSERT..SELECT could not, with two UPDATEs shaped like:
--
--     UPDATE fact_transaction f SET store_id = s.store_id
--       FROM dim_store s, stg_trnx_raw stg
--      WHERE ... AND f.payment_date = stg.payment_date AND f.arn = stg.arn ...
--
-- stg_trnx_raw had indexes on tenant_id, (tenant_id, payment_date), mid,
-- card_scheme, destination and transaction_type — but NOTHING on arn, which is
-- the selective half of that join predicate. Postgres therefore had to build a
-- hash over the entire staging table (or worse, seq-scan it per outer row when
-- statistics were stale) for both fix-ups on every single upload.
--
-- The composite below covers the whole join key. tenant_id leads to match the
-- table's existing tenant-leading index convention (see
-- V2026_08_01_01__perf_indexes_tenant_leading.sql) and to keep the index useful
-- under row-level security, which always adds a tenant_id predicate.
--
-- Safe to re-run: IF NOT EXISTS. Staging is small relative to fact_transaction
-- (one upload's worth of rows), so the write cost during ingest is negligible
-- next to the join it removes.
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_stg_txn_arn_date
    ON stg_trnx_raw (tenant_id, arn, payment_date);

-- Refresh planner statistics immediately so the very next ingest benefits
-- without waiting for autovacuum to notice the new index.
ANALYZE stg_trnx_raw;
