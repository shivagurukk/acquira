-- ============================================================================
-- V2026_07_04_01: API Management foundation
--   - Extend api_key with lifecycle/security columns (expiry, rate limit, IP allowlist, scopes)
--   - New api_request_log table for per-key usage analytics
--   - RLS on the new table (policy, but NOT FORCE — see note below)
-- Idempotent: safe to re-run. Production runs spring.sql.init.mode=never, so this
-- ALTER migration is the only landing mechanism (CREATE ... IF NOT EXISTS on an
-- existing api_key would silently skip these new columns).
-- ============================================================================

-- =========================
-- 1. EXTEND api_key
-- =========================
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS expires_at            TIMESTAMP;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS rate_limit_per_minute INT DEFAULT 120;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS allowed_ips           TEXT;   -- comma-separated CIDRs/IPs; blank/null = any
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS last_used_ip          VARCHAR(64);
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS updated_at            TIMESTAMP;

-- Covering index for the hot auth path (prefix lookup among active keys).
CREATE INDEX IF NOT EXISTS idx_api_key_prefix_active ON api_key(key_prefix) WHERE is_active = true;

-- =========================
-- 2. API REQUEST LOG
-- =========================
CREATE TABLE IF NOT EXISTS api_request_log (
    log_id      BIGSERIAL PRIMARY KEY,
    tenant_id   INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    key_id      BIGINT,                      -- soft ref to api_key(key_id); kept even if key later revoked
    method      VARCHAR(8),
    endpoint    VARCHAR(300),
    status      INT,
    client_ip   VARCHAR(64),
    latency_ms  INT,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_api_req_log_tenant_key_time
    ON api_request_log(tenant_id, key_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_api_req_log_time
    ON api_request_log(created_at DESC);

-- =========================
-- 3. RLS on api_request_log
-- =========================
-- Policy is enabled for defence-in-depth on tenant-scoped READS (the usage
-- endpoints always filter by tenant_id anyway). We deliberately DO NOT apply
-- FORCE ROW LEVEL SECURITY here: the app connects as the table owner, and the
-- global retention scheduler (ApiRequestLogRetentionScheduler) runs a
-- cross-tenant DELETE with no tenant context — matching how the existing global
-- maintenance jobs operate on the other summary/fact tables. FORCE would filter
-- get_current_tenant()=NULL to zero rows and silently no-op the cleanup.
ALTER TABLE api_request_log ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON api_request_log;
CREATE POLICY tenant_isolation_policy ON api_request_log USING (tenant_id = get_current_tenant());
