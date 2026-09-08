-- =================================================================
-- PDF Optimization — Schema Migration
-- Date: 2026-02-21
-- 1. Add contact_email to dim_merchant
-- 2. Create email_queue table for async email processing
-- =================================================================

-- 1. Add contact_email column to dim_merchant (for merchant report emailing)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'dim_merchant' AND column_name = 'contact_email'
    ) THEN
        ALTER TABLE dim_merchant ADD COLUMN contact_email VARCHAR(255);
    END IF;
END $$;

-- 2. Email queue table — fallback when JavaMailSender is not configured
--    External mail agent or cron job can poll this table to send emails
CREATE TABLE IF NOT EXISTS email_queue (
    id              BIGSERIAL PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    attachment_path VARCHAR(1000),
    status          VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, SENT, FAILED
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    sent_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_queue_status ON email_queue(status);
