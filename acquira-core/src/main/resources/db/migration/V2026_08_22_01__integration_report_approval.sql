-- ============================================================================
-- V2026_08_22_01__integration_report_approval.sql
--
-- Separation of duties for external-DB report SQL.
--
-- WHY: integration_report.sql_text is arbitrary SQL that executes against the
-- CUSTOMER's production database (core banking / switch) using the stored
-- service credentials. Until now any bank ADMIN could author it and any
-- schedule would run it, and Connection.setReadOnly is a no-op on the Oracle
-- and MSSQL drivers — so the application layer was the only control and it
-- was not applying one.
--
-- The approved_by column already existed but was written by nothing. This
-- migration turns it into a real gate: a report must be approved before any
-- pull (scheduled, manual or retry) will execute its SQL, and editing the SQL
-- revokes the approval so the change must be re-reviewed.
--
-- GRANDFATHERING: existing reports are marked approved by 'LEGACY-PRE-APPROVAL'
-- so an upgrade does not silently stop live feeds. Only reports created or
-- SQL-edited after this migration require an explicit approval. Review those
-- legacy rows and re-approve them deliberately if you want a clean audit trail.
--
-- Idempotent (ADD COLUMN IF NOT EXISTS / guarded UPDATE); splitter-safe (no $$).
-- ============================================================================

ALTER TABLE integration_report ADD COLUMN IF NOT EXISTS approved_at TIMESTAMP;

-- Widen approved_by if an older environment created it narrow.
ALTER TABLE integration_report ALTER COLUMN approved_by TYPE VARCHAR(255);

-- Grandfather every pre-existing report exactly once. The source_note-style
-- sentinel makes the re-run a no-op and makes legacy approvals visible in the
-- UI as distinct from a real human approval.
UPDATE integration_report
   SET approved_by = 'LEGACY-PRE-APPROVAL',
       approved_at = CURRENT_TIMESTAMP
 WHERE approved_by IS NULL
   AND sql_text IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_intg_report_approved
    ON integration_report (tenant_id, approved_by);
