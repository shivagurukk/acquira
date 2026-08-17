-- ============================================================================
-- HOTFIX (2026-08-17): interchange_rate_local ticket-column rename gap.
--
-- Symptom on the deployed cluster: every transaction file upload fails at
-- transactionLoadJob/stagingToFactStep with
--     ERROR: column ilr.min_ticket does not exist
--
-- Cause: the application (since 2026-08-11) queries min_ticket / max_ticket,
-- but a database provisioned from a pre-rename dump still has
-- min_ticket_aed / max_ticket_aed. The startup migration chain contains the
-- rename (V2026_07_05_01), but it never reaches a database running with
-- SPRING_SQL_INIT_MODE=never.
--
-- Apply once against the deployed database, e.g.:
--   kubectl exec -it <postgres-pod> -n <namespace> -- \
--     psql -U <user> -d <db> -f - < PATCH_2026-08-17_interchange_ticket_rename.sql
-- (or paste into any psql session). Idempotent: safe to re-run, safe on a
-- database that is already renamed.
-- ============================================================================

ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS min_ticket DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS max_ticket DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS min_ticket_aed DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS max_ticket_aed DECIMAL(19,4);

UPDATE interchange_rate_local
   SET min_ticket = COALESCE(min_ticket, min_ticket_aed),
       max_ticket = COALESCE(max_ticket, max_ticket_aed)
 WHERE (min_ticket IS NULL AND min_ticket_aed IS NOT NULL)
    OR (max_ticket IS NULL AND max_ticket_aed IS NOT NULL);

ALTER TABLE interchange_rate_local DROP COLUMN IF EXISTS min_ticket_aed;
ALTER TABLE interchange_rate_local DROP COLUMN IF EXISTS max_ticket_aed;

-- Verification: both queries should return the renamed columns / zero rows.
-- SELECT column_name FROM information_schema.columns
--  WHERE table_name = 'interchange_rate_local' AND column_name LIKE '%ticket%';
