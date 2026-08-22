-- Reconcile existing revenue_leakage_flags table with schema.sql.
-- Root cause: the table was first created by an older schema (before the
-- detector-enrichment columns + unique constraint existed). Because schema.sql
-- uses CREATE TABLE IF NOT EXISTS, the newer definition was never applied to the
-- already-existing table, so the upsert in RevenueLeakageDetectionService fails
-- (missing column 42703 / missing ON CONFLICT constraint 42P10).
-- This script is idempotent and safe to run on dev and prod.

-- 1. Add any missing enrichment columns
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS merchant_name      VARCHAR(200);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS business_date      DATE;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS metric_value       NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS baseline_value     NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS delta_pct          NUMERIC(9,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS est_monthly_impact NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS status             VARCHAR(20) DEFAULT 'OPEN';
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS is_resolved        BOOLEAN DEFAULT FALSE;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS detected_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS resolved_at        TIMESTAMP;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS resolved_by        VARCHAR(100);

-- 2. Add the unique constraint that ON CONFLICT (...) needs, if it's missing.
--    Wrapped in a DO block because ADD CONSTRAINT has no IF NOT EXISTS.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_revenue_leakage_flag'
    ) THEN
        ALTER TABLE revenue_leakage_flags
            ADD CONSTRAINT uq_revenue_leakage_flag
            UNIQUE (tenant_id, merchant_id, check_type, business_date);
    END IF;
END $$;
