-- V2026_08_25_01 — Mark get_current_tenant() STABLE (perf fix for RLS).
--
-- WHY: get_current_tenant() backs the RLS policy `tenant_id = get_current_tenant()`
-- on ~52 tenant tables. It was created with no volatility marker, so it defaulted
-- to VOLATILE — Postgres then re-evaluated it FOR EVERY ROW during any RLS-enforced
-- scan/insert. On the dim_terminal upsert (and its dim_merchant/dim_store joins)
-- this turned into a per-row function call and made terminal mapping in UAT crawl
-- once the session tenant context was set on the batch/backfill path.
--
-- The returned value is a session GUC (app.current_tenant) that cannot change
-- within a single statement, so STABLE is correct and lets the planner hoist the
-- call to once per query. This speeds up RLS on every tenant table, not just
-- dim_terminal. SECURITY DEFINER is preserved.
--
-- Idempotent: CREATE OR REPLACE redefines the existing function in place; the RLS
-- policies that reference it keep working unchanged.

CREATE OR REPLACE FUNCTION get_current_tenant() RETURNS BIGINT AS $$
    SELECT CAST(NULLIF(current_setting('app.current_tenant', true), '') AS BIGINT);
$$ LANGUAGE sql STABLE SECURITY DEFINER;
