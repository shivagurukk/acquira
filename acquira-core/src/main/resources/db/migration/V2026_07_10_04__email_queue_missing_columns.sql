-- ============================================================
-- V2026_07_10_04 — email_queue: add columns EmailController /
-- EmailQueueProcessor have always expected but were never actually
-- created by schema.sql.
--
-- ROOT CAUSE: schema.sql's email_queue CREATE TABLE (duplicated 5x in
-- the restored monolith, all identical) only ever defined:
--   id, recipient, subject, body, attachment_path, status,
--   error_message, retry_count, created_at, sent_at
-- tenant_id was patched in later (line ~10058 of schema.sql) but
-- merchant_id / merchant_name / is_html / statement_month were not —
-- despite EmailController.enqueueForMerchant() INSERTing all four,
-- EmailController.getStats()/getLogs() SELECTing them, and
-- EmailQueueProcessor.processQueue() SELECTing is_html. Every one of
-- those calls has been throwing BadSqlGrammarException.
--
-- Idempotent (ADD COLUMN IF NOT EXISTS), single statements only
-- (no DO $$ blocks — breaks Spring's schema-locations splitter).
-- ============================================================

ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS merchant_id      BIGINT;
ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS merchant_name    VARCHAR(200);
ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS is_html          BOOLEAN DEFAULT TRUE;
ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS statement_month  VARCHAR(10);

-- EmailController.getStats()/getLogs()/batchStatus() all filter on
-- (tenant_id, statement_month) — index it the same way sum_daily_* etc. do.
CREATE INDEX IF NOT EXISTS idx_email_queue_tenant_month
    ON email_queue (tenant_id, statement_month);
