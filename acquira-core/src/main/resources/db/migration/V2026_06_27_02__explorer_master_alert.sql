-- ============================================================================
-- V2026_06_27_02 — Data Explorer governance: master items + threshold alerts
--
-- Idempotent. Safe to run on production (spring.sql.init.mode=never), where
-- schema.sql is NOT auto-applied. Run once after deploying the Phase 4.x build.
--   psql "$DB_URL" -f V2026_06_27_02__explorer_master_alert.sql
-- ============================================================================

CREATE TABLE IF NOT EXISTS explorer_master_item (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT NOT NULL,
    item_type    VARCHAR(20) NOT NULL,
    item_key     VARCHAR(120) NOT NULL,
    label        VARCHAR(160) NOT NULL,
    definition   TEXT,
    description  VARCHAR(255),
    created_by   VARCHAR(120),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_master_item UNIQUE (tenant_id, item_type, item_key)
);
CREATE INDEX IF NOT EXISTS idx_master_item_tenant ON explorer_master_item (tenant_id);

CREATE TABLE IF NOT EXISTS explorer_alert (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL,
    name              VARCHAR(160) NOT NULL,
    measure_key       VARCHAR(120) NOT NULL,
    calc_json         TEXT,
    filter_json       TEXT,
    window_days       INTEGER DEFAULT 1,
    operator          VARCHAR(4) NOT NULL,
    threshold         DOUBLE PRECISION NOT NULL,
    severity          VARCHAR(20) DEFAULT 'WARNING',
    recipients        TEXT,
    is_enabled        BOOLEAN DEFAULT TRUE,
    last_value        DOUBLE PRECISION,
    last_checked_at   TIMESTAMP,
    last_triggered_at TIMESTAMP,
    created_by        VARCHAR(120),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_explorer_alert_enabled ON explorer_alert (is_enabled);
CREATE INDEX IF NOT EXISTS idx_explorer_alert_tenant ON explorer_alert (tenant_id);

ANALYZE explorer_master_item;
ANALYZE explorer_alert;
