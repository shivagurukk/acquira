-- ============================================================================
-- V2026_10_03_03: persisted status for the PDF pod's long-running jobs
-- (report batches and S3 uploads).
--
-- The jobs run on threads in ONE pdf pod and kept their status only in that
-- pod's memory, so a status poll answered by another replica — or after a
-- restart — found nothing. The owning pod now writes a snapshot here every few
-- seconds (PdfJobStore); any replica can report progress, list jobs or request
-- a cancel, and a job whose owner died shows as INTERRUPTED instead of
-- "running" forever. If this table is missing the pdf pod falls back to the
-- old in-memory behaviour (logged once).
--
-- Idempotent; splitter-safe (no $$). Applied once via psql like the other
-- recent scripts (not in the startup schema-locations list).
-- ============================================================================

CREATE TABLE IF NOT EXISTS pdf_job_status (
    job_id           VARCHAR(80)  PRIMARY KEY,
    kind             VARCHAR(16)  NOT NULL,          -- BATCH | S3_UPLOAD
    tenant_id        BIGINT       NOT NULL,
    status_json      TEXT         NOT NULL,
    finished         BOOLEAN      NOT NULL DEFAULT FALSE,
    cancel_requested BOOLEAN      NOT NULL DEFAULT FALSE,
    started_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pdf_job_status_tenant
    ON pdf_job_status (tenant_id, kind, updated_at);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_10_03_03__pdf_job_status.sql')
ON CONFLICT (filename) DO NOTHING;
