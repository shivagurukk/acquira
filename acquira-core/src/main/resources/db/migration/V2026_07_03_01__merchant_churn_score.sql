-- ============================================================
-- V2026_07_03_01: Merchant churn-risk score (ML, Phase 1)
-- ============================================================
-- Backs the churn-prediction model (Smile RandomForest) scored at the
-- end of the transaction batch, into a table shaped like
-- merchant_opportunity_score. One row per (tenant, merchant, calc_date):
-- the latest row per merchant is what the Attrition Report reads.
--
--   churn_probability : 0.0 .. 1.0  (model output; 30-60 day dormancy risk)
--   risk_band         : LOW | MEDIUM | HIGH  (derived from probability)
--   top_reason        : short human-readable driver (e.g. "Volume down 62%")
--   model_version     : which trained model produced this (audit / staleness)
--   scored_by         : MODEL | HEURISTIC  (HEURISTIC = cold-start fallback)
--
-- Idempotent + ALTER-based so it lands on prod (spring.sql.init.mode=never).
-- RLS-enabled to match every other tenant-scoped table.
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_churn_score (
    churn_id          BIGSERIAL PRIMARY KEY,
    tenant_id         INT NOT NULL,
    merchant_id       BIGINT,
    calc_date         DATE NOT NULL,
    churn_probability NUMERIC(6,4) DEFAULT 0,      -- 0.0000 .. 1.0000
    risk_band         VARCHAR(10),                 -- LOW | MEDIUM | HIGH
    top_reason        VARCHAR(255),
    model_version     VARCHAR(60),
    scored_by         VARCHAR(20) DEFAULT 'MODEL', -- MODEL | HEURISTIC
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_churn_score UNIQUE (tenant_id, merchant_id, calc_date)
);

-- Columns added defensively in case an older/partial table already exists
-- (CREATE TABLE IF NOT EXISTS would skip an updated definition otherwise).
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS churn_probability NUMERIC(6,4) DEFAULT 0;
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS risk_band         VARCHAR(10);
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS top_reason        VARCHAR(255);
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS model_version     VARCHAR(60);
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS scored_by         VARCHAR(20) DEFAULT 'MODEL';
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Unique constraint that the batch upsert's ON CONFLICT needs.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_merchant_churn_score'
    ) THEN
        ALTER TABLE merchant_churn_score
            ADD CONSTRAINT uq_merchant_churn_score
            UNIQUE (tenant_id, merchant_id, calc_date);
    END IF;
END $$;

-- Read path: "latest row per merchant for this tenant" + attrition join.
CREATE INDEX IF NOT EXISTS idx_churn_score_tenant_date
    ON merchant_churn_score (tenant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_churn_score_merchant
    ON merchant_churn_score (tenant_id, merchant_id, calc_date DESC);

-- Tenant isolation (defence-in-depth; app already scopes by tenant_id).
ALTER TABLE merchant_churn_score ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_churn_score;
CREATE POLICY tenant_isolation_policy ON merchant_churn_score
    USING (tenant_id = get_current_tenant());
