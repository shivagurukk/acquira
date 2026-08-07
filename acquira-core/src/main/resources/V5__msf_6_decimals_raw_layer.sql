-- =================================================================
-- V5: raw-layer MSF precision — the source CSV carries MSF at 6
-- decimal places, but stg_trnx_raw.msf / fact_transaction.msf were
-- DECIMAL(19,4), so Postgres rounded the last two digits on every
-- insert. Widen ONLY the MSF column at the raw layer so fact stores
-- the file's value verbatim.
--
-- DECIMAL(21,6) keeps the same 15 integer digits as DECIMAL(19,4).
-- Summary tables stay at scale 4 (V4): they are rebuilt from fact by
-- SUM(msf), so each stored rollup differs from the exact sum by at
-- most 0.00005 — reconciliation at 4 dp is unaffected.
--
-- Apply manually:  psql "$DB_URL" -v ON_ERROR_STOP=1 -f V5__msf_6_decimals_raw_layer.sql
--
-- WARNING: changing a numeric column's scale REWRITES the table under
-- an ACCESS EXCLUSIVE lock. fact_transaction is the largest table in
-- the database (~10M rows) — run this OFF-PEAK; expect minutes, and
-- uploads/dashboard queries will block on it while it runs. The parent
-- ALTER cascades to every partition automatically.
--
-- AFTER applying: re-upload the affected months' files (the 5th/6th
-- decimals were rounded away at insert, so they are not in fact and a
-- Summary Rebuild alone cannot recover them). The upload rebuilds the
-- summaries itself.
-- =================================================================

ALTER TABLE IF EXISTS stg_trnx_raw     ALTER COLUMN msf TYPE DECIMAL(21, 6);
ALTER TABLE IF EXISTS fact_transaction ALTER COLUMN msf TYPE DECIMAL(21, 6);

ANALYZE fact_transaction;
