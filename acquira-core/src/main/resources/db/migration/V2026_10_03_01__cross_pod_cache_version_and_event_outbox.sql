-- ============================================================================
-- V2026_10_03_01: Cross-pod seams for the core / batch / pdf pod split.
--
-- Two things worked only because batch, pdf and core shared one JVM:
--
--   1. Report-cache eviction. Batch cleared the in-memory report caches when an
--      ingest finished. In its own pod it would clear only its own copy while
--      core/pdf kept serving pre-ingest numbers for the full 6h TTL.
--      report_cache_version is the cross-pod signal: every eviction bumps the
--      row, every JVM polls it (ReportCacheSync) and drops its own caches when
--      the stamp moves. Same shape as rbac_version; tenant_id 0 = all tenants.
--
--   2. Batch -> core application events (IngestRunFinishedEvent,
--      IntegrationRunFailedEvent). They were in-process Spring events, so
--      across pods the failure-alert emails and webhooks would silently stop.
--      event_outbox carries them instead: batch inserts a row (EventOutbox),
--      core claims it with FOR UPDATE SKIP LOCKED and re-publishes it locally
--      (EventOutboxRelay), so the existing listeners are unchanged.
--
-- Both are behaviour-neutral while everything still runs in one JVM.
-- Idempotent; splitter-safe (no $$). Applied once via psql like the other
-- recent scripts (not in the startup schema-locations list).
-- ============================================================================

CREATE TABLE IF NOT EXISTS report_cache_version (
    tenant_id  INT       PRIMARY KEY,
    version    BIGINT    NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO report_cache_version (tenant_id, version) VALUES (0, 1)
ON CONFLICT (tenant_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS event_outbox (
    id           BIGSERIAL    PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    tenant_id    BIGINT,
    payload      TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    last_error   TEXT
);

CREATE INDEX IF NOT EXISTS idx_event_outbox_pending
    ON event_outbox (id) WHERE status = 'PENDING';

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_03_01__cross_pod_cache_version_and_event_outbox.sql')
ON CONFLICT (filename) DO NOTHING;
