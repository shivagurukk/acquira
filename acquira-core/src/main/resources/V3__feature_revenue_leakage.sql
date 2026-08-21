-- ================================================================
-- Acquira CMS — Feature Migration: Revenue Leakage / Anomaly Detection
-- Additive + idempotent. Safe to run on an existing prod database
-- (spring.sql.init.mode=never). Fresh/dev installs get the same result
-- from schema.sql.
-- ================================================================

-- ============================================
-- 1. Enrich revenue_leakage_flags
-- ============================================
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS merchant_name      VARCHAR(200);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS business_date      DATE;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS metric_value       NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS baseline_value     NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS delta_pct          NUMERIC(9,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS est_monthly_impact NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS status             VARCHAR(20) DEFAULT 'OPEN';
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS resolved_at        TIMESTAMP;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS resolved_by        VARCHAR(100);

-- Backfill status for any pre-existing rows.
UPDATE revenue_leakage_flags
   SET status = CASE WHEN is_resolved THEN 'RESOLVED' ELSE 'OPEN' END
 WHERE status IS NULL;

-- Upsert key for the detector. A UNIQUE INDEX is used (vs a table constraint)
-- so it can be added idempotently to an existing table.
CREATE UNIQUE INDEX IF NOT EXISTS uq_revenue_leakage_flag
    ON revenue_leakage_flags (tenant_id, merchant_id, check_type, business_date);

-- Read-path indexes.
CREATE INDEX IF NOT EXISTS idx_revenue_leakage_tenant_status
    ON revenue_leakage_flags (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_revenue_leakage_tenant_detected
    ON revenue_leakage_flags (tenant_id, detected_at DESC);

-- ============================================
-- 2. Menu entry + RBAC mapping
-- ============================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Revenue Leakage', '/business/revenue-leakage', 'ShieldAlert', 'BUSINESS', 16)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin', 'Business User')
  AND m.path = '/business/revenue-leakage'
ON CONFLICT DO NOTHING;

-- ============================================
-- 3. Detector thresholds (per tenant; defaults)
-- ============================================
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'leakage.min_daily_volume', '100', 'NUMBER' FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'leakage.volume_drop_pct', '0.40', 'NUMBER' FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'leakage.msf_rate_drop_pct', '0.30', 'NUMBER' FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'leakage.default_msf_rate', '0.02', 'NUMBER' FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;
