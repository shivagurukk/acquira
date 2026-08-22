-- ============================================================
-- V2026_06_26_01: Nightly database maintenance (VACUUM ANALYZE)
-- ============================================================
-- Backs DatabaseMaintenanceService + MaintenanceController.
-- A single-row config table (admin-editable from the UI) plus a
-- run-history table. Idempotent: safe to re-run on every startup.
--
-- The job runs VACUUM (ANALYZE) on the high-churn tables inside a
-- configurable night window, and ONLY when no Spring Batch job is
-- running, so vacuum never competes with ingestion.
-- ============================================================

CREATE TABLE IF NOT EXISTS db_maintenance_config (
    id                SMALLINT PRIMARY KEY DEFAULT 1,
    enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    window_start_hour INT         NOT NULL DEFAULT 2,   -- inclusive, server local time
    window_end_hour   INT         NOT NULL DEFAULT 5,   -- exclusive; if start > end the window wraps midnight
    tables_csv        TEXT,                              -- NULL = service default list
    last_run_date     DATE,                              -- "already ran today" guard
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT db_maintenance_config_singleton CHECK (id = 1)
);

-- Seed the single config row (no-op if it already exists)
INSERT INTO db_maintenance_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS db_maintenance_run (
    id          BIGSERIAL   PRIMARY KEY,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    status      VARCHAR(16) NOT NULL,            -- RUNNING | SUCCESS | FAILED | SKIPPED
    trigger     VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED | MANUAL
    tables_done INT         DEFAULT 0,
    detail      TEXT
);

CREATE INDEX IF NOT EXISTS idx_db_maintenance_run_started
    ON db_maintenance_run (started_at DESC);
