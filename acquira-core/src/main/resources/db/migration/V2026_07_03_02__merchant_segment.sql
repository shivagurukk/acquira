-- ============================================================
-- V2026_07_03_02: Merchant segmentation (Phase 1, 6 data-backed segments)
-- ============================================================
-- Cross-cutting portfolio segmentation. One row per (tenant, merchant, calc_date):
-- the latest per merchant is the merchant's current segment. Computed in-batch after
-- business metrics, from sum_daily_merchant (settlement total_base_volume) + margin +
-- dim_merchant onboarding, using per-tenant trailing-90d percentiles.
--
--   primary_segment   : one of STRATEGIC | VOLUME_DRIVER | PROFIT_DRIVER |
--                        AT_RISK | NEW | LONG_TAIL
--   secondary_tags    : comma-separated other qualifying segments (may be empty)
--   segment_reason    : short human-readable why
--   segment_score     : 0..100 confidence/priority signal
--   model_version     : rule-set version (seg-rules-v1)
--
-- Idempotent + ALTER-based so it lands on prod (spring.sql.init.mode=never).
-- RLS-enabled to match every other tenant-scoped table.
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_segment (
    segment_id        BIGSERIAL PRIMARY KEY,
    tenant_id         INT NOT NULL,
    merchant_id       BIGINT,
    calc_date         DATE NOT NULL,
    primary_segment   VARCHAR(30),
    secondary_tags    VARCHAR(255),
    segment_reason    VARCHAR(255),
    segment_score     NUMERIC(5,2) DEFAULT 0,
    total_volume      NUMERIC(19,2),
    net_revenue       NUMERIC(19,2),
    net_margin_pct    NUMERIC(9,2),
    effective_bps     NUMERIC(9,2),
    net_take_bps      NUMERIC(9,2),
    volume_growth_pct NUMERIC(9,2),
    days_since_last   INT,
    model_version     VARCHAR(60),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_segment UNIQUE (tenant_id, merchant_id, calc_date)
);

-- Defensive column adds (CREATE TABLE IF NOT EXISTS skips updated defs on an
-- already-existing table).
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS primary_segment   VARCHAR(30);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS secondary_tags    VARCHAR(255);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS segment_reason    VARCHAR(255);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS segment_score     NUMERIC(5,2) DEFAULT 0;
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS total_volume      NUMERIC(19,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS net_revenue       NUMERIC(19,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS net_margin_pct    NUMERIC(9,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS effective_bps     NUMERIC(9,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS net_take_bps      NUMERIC(9,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS volume_growth_pct NUMERIC(9,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS days_since_last   INT;
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS model_version     VARCHAR(60);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Unique constraint the batch upsert's ON CONFLICT needs.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_merchant_segment'
    ) THEN
        ALTER TABLE merchant_segment
            ADD CONSTRAINT uq_merchant_segment
            UNIQUE (tenant_id, merchant_id, calc_date);
    END IF;
END $$;

-- Read paths: "latest per merchant for tenant" and "segment-mix for tenant/date".
CREATE INDEX IF NOT EXISTS idx_merchant_segment_tenant_date
    ON merchant_segment (tenant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_segment_merchant
    ON merchant_segment (tenant_id, merchant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_segment_primary
    ON merchant_segment (tenant_id, calc_date, primary_segment);

-- Tenant isolation (defence-in-depth; app already scopes by tenant_id).
ALTER TABLE merchant_segment ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_segment;
CREATE POLICY tenant_isolation_policy ON merchant_segment
    USING (tenant_id = get_current_tenant());
