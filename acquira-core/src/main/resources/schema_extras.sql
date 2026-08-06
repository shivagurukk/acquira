-- ============================================================
-- schema_extras.sql — merged migrations + fixes on top of schema.sql
-- ============================================================
-- schema.sql was restored from the original full monolith script
-- (db_script.sql). This file carries everything the previous
-- (lost) de-duplicated schema.sql had merged in on top of it.
--
-- Splitter-safe: single statements only, NO dollar-quoted DO blocks
-- (Spring's spring.sql.init splitter breaks on dollar quoting).
-- Idempotent: IF NOT EXISTS / DROP POLICY IF EXISTS / guarded DELETE.
-- Runs immediately AFTER schema.sql in spring.sql.init.schema-locations.
--
-- Merged from (originals kept in db/migration, NOT listed in
-- schema-locations because they contain DO-blocks):
--   V2026_06_27_01  revenue_leakage_flags reconcile
--   V2026_06_29_01  sum_daily_insight covering indexes
--   V2026_06_29_02  sum_monthly_insight
--   V2026_07_03_01  merchant_churn_score
--   V2026_07_03_02  merchant_segment
-- Plus: email_template_config de-duplication (the monolith script
-- inserts the 3 default templates 5x with no unique key).
-- ============================================================

-- ── email_template_config: dedupe defaults + enforce uniqueness ──
DELETE FROM email_template_config a
USING email_template_config b
WHERE a.id > b.id
  AND a.tenant_id IS NOT DISTINCT FROM b.tenant_id
  AND a.name = b.name;

CREATE UNIQUE INDEX IF NOT EXISTS uq_email_tpl_tenant_name
    ON email_template_config (tenant_id, name);

-- ── V2026_06_27_01: revenue_leakage_flags enrichment columns + upsert key ──
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
CREATE UNIQUE INDEX IF NOT EXISTS uq_revenue_leakage_flag
    ON revenue_leakage_flags (tenant_id, merchant_id, check_type, business_date);

-- ── V2026_07_03_01: merchant_churn_score (ML churn risk) ──
CREATE TABLE IF NOT EXISTS merchant_churn_score (
    churn_id          BIGSERIAL PRIMARY KEY,
    tenant_id         INT NOT NULL,
    merchant_id       BIGINT,
    calc_date         DATE NOT NULL,
    churn_probability NUMERIC(6,4) DEFAULT 0,
    risk_band         VARCHAR(10),
    top_reason        VARCHAR(255),
    model_version     VARCHAR(60),
    scored_by         VARCHAR(20) DEFAULT 'MODEL',
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_churn_score UNIQUE (tenant_id, merchant_id, calc_date)
);
CREATE INDEX IF NOT EXISTS idx_churn_score_tenant_date ON merchant_churn_score (tenant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_churn_score_merchant    ON merchant_churn_score (tenant_id, merchant_id, calc_date DESC);
ALTER TABLE merchant_churn_score ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_churn_score;
CREATE POLICY tenant_isolation_policy ON merchant_churn_score USING (tenant_id = get_current_tenant());

-- ── V2026_07_03_02: merchant_segment (portfolio segmentation) ──
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
CREATE INDEX IF NOT EXISTS idx_merchant_segment_tenant_date ON merchant_segment (tenant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_segment_merchant    ON merchant_segment (tenant_id, merchant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_segment_primary     ON merchant_segment (tenant_id, calc_date, primary_segment);
ALTER TABLE merchant_segment ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_segment;
CREATE POLICY tenant_isolation_policy ON merchant_segment USING (tenant_id = get_current_tenant());

-- ── V2026_06_29_02: sum_monthly_insight (month-grain pre-aggregate) ──
CREATE TABLE IF NOT EXISTS sum_monthly_insight (
    summary_id   BIGSERIAL,
    tenant_id    INT NOT NULL,
    month_key    INT NOT NULL,
    merchant_id  BIGINT,
    store_id     BIGINT,
    terminal_id  BIGINT,
    card_scheme  VARCHAR(50),
    card_type    VARCHAR(50),
    destination  VARCHAR(50),
    channel      VARCHAR(50),
    is_opt_in    BOOLEAN,
    total_txns   BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf    DECIMAL(21, 4) DEFAULT 0,
    PRIMARY KEY (summary_id),
    UNIQUE (tenant_id, month_key, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in)
);
CREATE INDEX IF NOT EXISTS idx_smi_merchant_rollup
    ON sum_monthly_insight (tenant_id, month_key, merchant_id, store_id)
    INCLUDE (total_txns, total_volume, total_msf);
CREATE INDEX IF NOT EXISTS idx_smi_card_rollup
    ON sum_monthly_insight (tenant_id, month_key, card_scheme, card_type, destination, channel)
    INCLUDE (total_txns, total_volume, total_msf);
ALTER TABLE sum_monthly_insight ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_monthly_insight;
CREATE POLICY tenant_isolation_policy ON sum_monthly_insight USING (tenant_id = get_current_tenant());

-- ── V2026_06_29_01: sum_daily_insight covering indexes ──
CREATE INDEX IF NOT EXISTS idx_sdi_merchant_rollup
    ON sum_daily_insight (tenant_id, business_date, merchant_id, store_id)
    INCLUDE (total_txns, total_volume, total_msf);
CREATE INDEX IF NOT EXISTS idx_sdi_card_rollup
    ON sum_daily_insight (tenant_id, business_date, card_scheme, card_type, destination, channel)
    INCLUDE (total_txns, total_volume, total_msf);

-- FEE_ENGINE_APPEND_ANCHOR
