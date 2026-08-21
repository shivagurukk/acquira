-- ================================================================
-- Acquira CMS — Feature Migration: Security, Alerts, API, Audit
-- Run after schema.sql (additive only, safe to re-run)
-- ================================================================

-- ============================================
-- 1. New Admin Menu Entries
-- ============================================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Security Settings', '/admin/security-settings', 'Shield', 'ADMINISTRATION', 18),
('Alerts & Notifications', '/admin/alerts', 'Bell', 'ADMINISTRATION', 19),
('API Management', '/admin/api-management', 'Code', 'ADMINISTRATION', 20)
ON CONFLICT (path) DO NOTHING;

-- Map new menus → Super Admin (gets everything)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
  AND m.path IN ('/admin/security-settings', '/admin/alerts', '/admin/api-management')
ON CONFLICT DO NOTHING;

-- Map new menus → Bank Admin (gets alerts + security)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Bank Admin'
  AND m.path IN ('/admin/security-settings', '/admin/alerts')
ON CONFLICT DO NOTHING;


-- ============================================
-- 2. Alert Rules Table
-- ============================================
CREATE TABLE IF NOT EXISTS alert_rule (
    rule_id         BIGSERIAL PRIMARY KEY,
    tenant_id       INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    metric          VARCHAR(100) NOT NULL,
    operator        VARCHAR(5)  NOT NULL DEFAULT '>',
    threshold       NUMERIC(15,4) NOT NULL DEFAULT 0,
    severity        VARCHAR(20) NOT NULL DEFAULT 'WARNING',
    recipients      TEXT,
    check_frequency VARCHAR(20) DEFAULT 'DAILY',
    scope           VARCHAR(50) DEFAULT 'ALL_MERCHANTS',
    is_active       BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    created_by      VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_alert_rule_tenant ON alert_rule(tenant_id);

ALTER TABLE alert_rule ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON alert_rule;
CREATE POLICY tenant_isolation_policy ON alert_rule
    USING (tenant_id = get_current_tenant());
ALTER TABLE alert_rule FORCE ROW LEVEL SECURITY;


-- ============================================
-- 3. Alert History Table
-- ============================================
CREATE TABLE IF NOT EXISTS alert_history (
    alert_id        BIGSERIAL PRIMARY KEY,
    tenant_id       INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    rule_id         BIGINT REFERENCES alert_rule(rule_id) ON DELETE SET NULL,
    rule_name       VARCHAR(200),
    severity        VARCHAR(20),
    merchant_id     BIGINT,
    merchant_name   VARCHAR(200),
    message         TEXT,
    metric_value    NUMERIC(15,4),
    threshold_value NUMERIC(15,4),
    triggered_at    TIMESTAMP DEFAULT NOW(),
    acknowledged    BOOLEAN DEFAULT FALSE,
    acknowledged_by VARCHAR(100),
    acknowledged_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_alert_history_tenant ON alert_history(tenant_id);
CREATE INDEX IF NOT EXISTS idx_alert_history_triggered ON alert_history(triggered_at DESC);

ALTER TABLE alert_history ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON alert_history;
CREATE POLICY tenant_isolation_policy ON alert_history
    USING (tenant_id = get_current_tenant());
ALTER TABLE alert_history FORCE ROW LEVEL SECURITY;


-- ============================================
-- 4. API Keys Table
-- ============================================
CREATE TABLE IF NOT EXISTS api_key (
    key_id          BIGSERIAL PRIMARY KEY,
    tenant_id       INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    key_hash        VARCHAR(255) NOT NULL,
    key_prefix      VARCHAR(20) NOT NULL,
    permissions     TEXT,
    is_active       BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMP DEFAULT NOW(),
    last_used_at    TIMESTAMP,
    request_count   BIGINT DEFAULT 0,
    created_by      VARCHAR(100),
    revoked_at      TIMESTAMP,
    revoked_by      VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_api_key_tenant ON api_key(tenant_id);
CREATE INDEX IF NOT EXISTS idx_api_key_prefix ON api_key(key_prefix);

ALTER TABLE api_key ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON api_key;
CREATE POLICY tenant_isolation_policy ON api_key
    USING (tenant_id = get_current_tenant());
ALTER TABLE api_key FORCE ROW LEVEL SECURITY;


-- ============================================
-- 5. Security Policy Seed Defaults
-- ============================================
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type) VALUES
(1, 'security.min_length', '8', 'NUMBER'),
(1, 'security.require_uppercase', 'true', 'BOOLEAN'),
(1, 'security.require_lowercase', 'true', 'BOOLEAN'),
(1, 'security.require_digit', 'true', 'BOOLEAN'),
(1, 'security.require_special_char', 'true', 'BOOLEAN'),
(1, 'security.password_history_count', '5', 'NUMBER'),
(1, 'security.max_failed_attempts', '5', 'NUMBER'),
(1, 'security.lockout_duration_minutes', '15', 'NUMBER'),
(1, 'security.password_expiry_days', '90', 'NUMBER'),
(1, 'security.session_timeout_minutes', '30', 'NUMBER'),
(1, 'security.force_change_on_first_login', 'true', 'BOOLEAN')
ON CONFLICT (tenant_id, setting_key) DO NOTHING;
