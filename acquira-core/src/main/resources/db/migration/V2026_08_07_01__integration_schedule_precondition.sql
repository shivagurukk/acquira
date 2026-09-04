-- V2026_08_07_01 — Optional upstream-readiness precondition on integration schedules.
--
-- When precondition_enabled is true, IntegrationPullService runs precondition_sql
-- (a single SELECT) against the SAME external connection BEFORE pulling
-- transactions. The pull proceeds only when the first cell of the first row is
-- truthy (boolean true, non-zero number, or Y/YES/1/TRUE/COMPLETED/SUCCESS/DONE,
-- case-insensitive). A false/empty result defers the pull through the existing
-- retry backoff (5 min, 25 min, then capped at 30 min, up to the connection's
-- max_retries), so the ingest waits for the upstream batch to finish.
-- Manual "Run Now" bypasses the gate.
--
-- Idempotent — safe to re-run.

ALTER TABLE integration_schedule ADD COLUMN IF NOT EXISTS precondition_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE integration_schedule ADD COLUMN IF NOT EXISTS precondition_sql TEXT;
