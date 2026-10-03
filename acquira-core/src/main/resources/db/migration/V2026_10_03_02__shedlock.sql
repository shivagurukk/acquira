-- ============================================================================
-- V2026_10_03_02: ShedLock table — one row per scheduled job.
--
-- Every @Scheduled job (and the programmatic cron pulls, webhook delivery and
-- per-tenant integration pulls) now takes a row lock here before running, so a
-- job runs on ONE replica at a time when core / batch are scaled past 1.
-- See SchedulerLockConfig. If this table is missing the app keeps working
-- exactly as before (unlocked, logged once) — safe for a single replica, NOT
-- safe for several, which is why split-role readiness also checks for it.
--
-- Idempotent; splitter-safe (no $$). Applied once via psql like the other
-- recent scripts (not in the startup schema-locations list).
-- ============================================================================

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_03_02__shedlock.sql')
ON CONFLICT (filename) DO NOTHING;
