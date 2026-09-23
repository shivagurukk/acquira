-- ============================================================================
-- V2026_09_05_05: Enterprise API v1 — outbound webhooks, API-key lifecycle,
-- idempotency keys, daily quotas. Everything tenant-scoped.
--
--   1. webhook_endpoint  — per-tenant outbound webhook subscriptions
--                          (HMAC secret stored encrypted via CryptoService).
--   2. webhook_delivery  — delivery ledger with retry/backoff state.
--   3. api_idempotency   — Idempotency-Key replay store for external POST/PUT.
--   4. api_key           — + quota_per_day (daily request ceiling per key)
--                          + rotated_at / rotated_by lifecycle columns.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- 1. Webhook endpoints --------------------------------------------------------
CREATE TABLE IF NOT EXISTS webhook_endpoint (
    endpoint_id          BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT NOT NULL,
    name                 VARCHAR(120) NOT NULL,
    url                  VARCHAR(1000) NOT NULL,
    -- enc:v1: token (AES-256-GCM); used to HMAC-SHA256 sign every delivery
    secret               VARCHAR(500) NOT NULL,
    -- JSON array of subscribed event types, e.g. ["ingest.run.completed"]
    events               TEXT NOT NULL DEFAULT '[]',
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_by           VARCHAR(120),
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP,
    last_success_at      TIMESTAMP,
    last_failure_at      TIMESTAMP,
    consecutive_failures INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_webhook_endpoint_tenant
    ON webhook_endpoint (tenant_id, is_active);

-- 2. Delivery ledger ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS webhook_delivery (
    delivery_id     BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL,
    endpoint_id     BIGINT NOT NULL REFERENCES webhook_endpoint(endpoint_id) ON DELETE CASCADE,
    event_type      VARCHAR(80) NOT NULL,
    -- stable id integrators can use to de-duplicate re-deliveries
    event_id        VARCHAR(64) NOT NULL,
    payload         TEXT NOT NULL,
    -- PENDING -> SUCCESS | FAILED (will retry) -> DEAD (retries exhausted)
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response_status INTEGER,
    response_body   VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_due
    ON webhook_delivery (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_tenant
    ON webhook_delivery (tenant_id, created_at DESC);

-- 3. Idempotency replay store -------------------------------------------------
CREATE TABLE IF NOT EXISTS api_idempotency (
    tenant_id       BIGINT NOT NULL,
    idem_key        VARCHAR(120) NOT NULL,
    method          VARCHAR(10) NOT NULL,
    endpoint        VARCHAR(300) NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, idem_key)
);
CREATE INDEX IF NOT EXISTS idx_api_idempotency_age ON api_idempotency (created_at);

-- 4. api_key lifecycle + quota ------------------------------------------------
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS quota_per_day INTEGER;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS rotated_at TIMESTAMP;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS rotated_by VARCHAR(120);
