-- ============================================================================
-- V2026_02_28_01: New screens — Security, Alerts, API Management
-- ============================================================================

-- =========================
-- 1. ALERT RULE TABLE
-- =========================
CREATE TABLE IF NOT EXISTS alert_rule (
    rule_id     BIGSERIAL PRIMARY KEY,
    tenant_id   INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    metric      VARCHAR(100) NOT NULL,    -- e.g. 'daily_volume_drop', 'zero_txn_days'
    operator    VARCHAR(5) NOT NULL DEFAULT '>',
    threshold   NUMERIC(18,4) NOT NULL DEFAULT 0,
    severity    VARCHAR(20) NOT NULL DEFAULT 'WARNING',   -- INFO, WARNING, CRITICAL
    recipients  TEXT,                      -- comma-separated emails
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    check_frequency VARCHAR(20) DEFAULT 'DAILY',  -- HOURLY, DAILY, WEEKLY
    scope       VARCHAR(50) DEFAULT 'ALL_MERCHANTS',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_alert_rule_tenant ON alert_rule(tenant_id);

-- =========================
-- 2. ALERT HISTORY TABLE
-- =========================
CREATE TABLE IF NOT EXISTS alert_history (
    alert_id        BIGSERIAL PRIMARY KEY,
    rule_id         BIGINT REFERENCES alert_rule(rule_id) ON DELETE SET NULL,
    tenant_id       INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    rule_name       VARCHAR(200),
    severity        VARCHAR(20),
    merchant_name   VARCHAR(200),
    merchant_id     BIGINT,
    message         TEXT,
    metric_value    NUMERIC(18,4),
    acknowledged    BOOLEAN DEFAULT FALSE,
    acknowledged_by VARCHAR(100),
    acknowledged_at TIMESTAMP,
    triggered_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_alert_history_tenant ON alert_history(tenant_id);
CREATE INDEX IF NOT EXISTS idx_alert_history_triggered ON alert_history(triggered_at DESC);

-- =========================
-- 3. API KEY TABLE
-- =========================
CREATE TABLE IF NOT EXISTS api_key (
    key_id          BIGSERIAL PRIMARY KEY,
    tenant_id       INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    key_hash        VARCHAR(255) NOT NULL,     -- bcrypt hash of the full key
    key_prefix      VARCHAR(20) NOT NULL,      -- first 12 chars for display (aqr_mob_a3f2...)
    permissions     TEXT,                       -- JSON array: ["read:transactions","read:merchants"]
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(100),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used       TIMESTAMP,
    request_count   BIGINT DEFAULT 0,
    revoked_at      TIMESTAMP,
    revoked_by      VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_api_key_tenant ON api_key(tenant_id);
CREATE INDEX IF NOT EXISTS idx_api_key_hash ON api_key(key_hash);

-- =========================
-- 4. RLS POLICIES
-- =========================
ALTER TABLE alert_rule ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON alert_rule;
CREATE POLICY tenant_isolation_policy ON alert_rule USING (tenant_id = get_current_tenant());
ALTER TABLE alert_rule FORCE ROW LEVEL SECURITY;

ALTER TABLE alert_history ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON alert_history;
CREATE POLICY tenant_isolation_policy ON alert_history USING (tenant_id = get_current_tenant());
ALTER TABLE alert_history FORCE ROW LEVEL SECURITY;

ALTER TABLE api_key ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON api_key;
CREATE POLICY tenant_isolation_policy ON api_key USING (tenant_id = get_current_tenant());
ALTER TABLE api_key FORCE ROW LEVEL SECURITY;

-- =========================
-- 5. NEW MENU ENTRIES
-- =========================
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Security Settings', '/admin/security-settings', 'Shield', 'ADMINISTRATION', 18),
('Alerts & Notifications', '/admin/alerts', 'Bell', 'ADMINISTRATION', 19),
('API Management', '/admin/api-management', 'Code', 'ADMINISTRATION', 20)
ON CONFLICT (path) DO NOTHING;

-- Super Admin gets all new menus
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
ON CONFLICT DO NOTHING;

-- Bank Admin gets Security Settings and Alerts
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Bank Admin'
  AND m.path IN ('/admin/security-settings', '/admin/alerts', '/admin/api-management')
ON CONFLICT DO NOTHING;
