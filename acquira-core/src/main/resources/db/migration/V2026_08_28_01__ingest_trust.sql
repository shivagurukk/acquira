-- =====================================================================
-- V2026_08_28_01 — Ingestion Trust & Data-Quality Board
-- =====================================================================
-- Creates the durable, tenant-scoped ingestion ledger that the board reads.
--
-- WHY A LEDGER AT ALL
-- -------------------
-- Spring Batch metadata (BATCH_JOB_EXECUTION) was the only record of an
-- ingestion, and it cannot answer the questions ops actually asks:
--   * no queryable tenant_id (it lives inside JobParameters as a string key)
--   * no file identity, size, or hash
--   * read/write counts aggregate wrongly across partitioned steps
--   * BackfillIngestionService / BulkMigrationService are not Spring Batch
--     jobs at all, so they leave no trace whatsoever
--   * it is a purge target for DatabaseMaintenanceService
--
-- ADDITIVE ONLY. Nothing here drops or rewrites an existing object.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. ingest_run — one row per ingestion attempt, every path
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ingest_run (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT      NOT NULL,
    source               VARCHAR(24) NOT NULL,   -- UPLOAD|SERVER_FILE|DB_PULL|BACKFILL|BULK_MIGRATION
    job_execution_id     BIGINT,                 -- Spring Batch id; NULL for backfill/migration
    job_name             VARCHAR(64),
    file_name            VARCHAR(512),           -- sanitised basename, never a raw client-supplied path
    file_bytes           BIGINT,
    file_sha256          CHAR(64),               -- duplicate-resend detection; NULL for very large files
    load_mode            VARCHAR(16),            -- REPLACE|APPEND
    status               VARCHAR(16) NOT NULL,   -- RUNNING|COMPLETED|FAILED|STOPPED
    started_at           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at             TIMESTAMP,
    duration_ms          BIGINT,
    rows_file            BIGINT,
    rows_staged          BIGINT,
    rows_facted          BIGINT,
    rows_summarised      BIGINT,
    rows_rejected        BIGINT,
    fact_rows_deleted    BIGINT,                 -- what REPLACE destroyed before inserting
    min_txn_date         DATE,
    max_txn_date         DATE,
    distinct_days        INT,
    unresolved_merchants INT,
    fee_priced_pct       NUMERIC(5,2),           -- % of loaded fact rows carrying a non-zero MSF
    recon_status         VARCHAR(16),            -- OK|GAP|UNKNOWN
    recon_detail         TEXT,                   -- human-readable classification of each gap
    error_class          VARCHAR(255),
    error_message        TEXT,
    triggered_by         VARCHAR(128),
    correlation_id       VARCHAR(64),
    acknowledged_by      VARCHAR(128),
    acknowledged_at      TIMESTAMP,
    ack_note             TEXT
);

CREATE INDEX IF NOT EXISTS ix_ingest_run_tenant_started ON ingest_run (tenant_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_ingest_run_tenant_status  ON ingest_run (tenant_id, status);
CREATE INDEX IF NOT EXISTS ix_ingest_run_sha            ON ingest_run (tenant_id, file_sha256);
CREATE INDEX IF NOT EXISTS ix_ingest_run_jobexec        ON ingest_run (job_execution_id);
CREATE INDEX IF NOT EXISTS ix_ingest_run_started        ON ingest_run (started_at DESC);

-- ---------------------------------------------------------------------
-- 2. ingest_run_stage — one row per pipeline stage per run
-- ---------------------------------------------------------------------
-- Partition WORKER executions (csvWorkerStep:partition7) are deliberately NOT
-- recorded here: Spring Batch's DefaultStepExecutionAggregator already folds
-- their row counts into the manager step (masterIngestStep), so recording both
-- would double-count exactly the way the progress bar used to.
CREATE TABLE IF NOT EXISTS ingest_run_stage (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES ingest_run(id) ON DELETE CASCADE,
    stage_name   VARCHAR(64) NOT NULL,
    seq          INT         NOT NULL,
    status       VARCHAR(16),
    started_at   TIMESTAMP,
    ended_at     TIMESTAMP,
    duration_ms  BIGINT,
    rows_in      BIGINT,
    rows_out     BIGINT,
    rows_skipped BIGINT,
    note         TEXT
);

CREATE INDEX IF NOT EXISTS ix_ingest_stage_run  ON ingest_run_stage (run_id, seq);
CREATE INDEX IF NOT EXISTS ix_ingest_stage_name ON ingest_run_stage (stage_name, started_at DESC);

-- ---------------------------------------------------------------------
-- 3. ingest_day_coverage — what each tenant-day actually holds
-- ---------------------------------------------------------------------
-- Deliberately NOT adding ingest_run_id to fact_transaction: it is the largest
-- partitioned table in the system and the column add plus backfill would be
-- disproportionate. The trade-off is that we attribute a DAY to a run, not an
-- individual fact row — which is the granularity the board needs anyway.
CREATE TABLE IF NOT EXISTS ingest_day_coverage (
    tenant_id       BIGINT NOT NULL,
    txn_date        DATE   NOT NULL,
    rows_fact       BIGINT,
    rows_summary    BIGINT,
    gross_amount    NUMERIC(21,4),
    fee_priced_rows BIGINT,
    last_run_id     BIGINT,
    last_loaded_at  TIMESTAMP,
    load_count      INT DEFAULT 1,
    PRIMARY KEY (tenant_id, txn_date)
);

CREATE INDEX IF NOT EXISTS ix_ingest_coverage_loaded ON ingest_day_coverage (tenant_id, last_loaded_at DESC);

-- ---------------------------------------------------------------------
-- 4. ingest_expectation — what "on time and complete" means per tenant
-- ---------------------------------------------------------------------
-- enabled defaults to FALSE so a newly provisioned tenant does not alert
-- before it has ever received a file.
CREATE TABLE IF NOT EXISTS ingest_expectation (
    tenant_id         BIGINT PRIMARY KEY,
    expected_daily    BOOLEAN     DEFAULT TRUE,
    cutoff_local_time TIME        DEFAULT '09:00',
    timezone          VARCHAR(64) DEFAULT 'Asia/Bahrain',
    sla_minutes       INT         DEFAULT 45,
    min_rows_warn     BIGINT,
    variance_pct      INT         DEFAULT 40,
    fee_coverage_pct  NUMERIC(5,2) DEFAULT 95.00,
    enabled           BOOLEAN     DEFAULT FALSE
);

-- Seed one disabled row per existing tenant so the board has something to show
-- and an admin only has to flip `enabled` rather than invent a row.
INSERT INTO ingest_expectation (tenant_id)
SELECT t.tenant_id FROM tenant t
ON CONFLICT (tenant_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 5. Staging run scope (P0-2)
-- ---------------------------------------------------------------------
-- cleanTargetDayTasklet used to run `DELETE FROM stg_trnx_raw WHERE tenant_id = ?`
-- with no day or run scope at all, despite its name. Two uploads for the same
-- tenant at once meant the second wiped the first's staging mid-flight. Scoping
-- staging by run fixes that AND is what makes a truthful rows_staged possible.
ALTER TABLE stg_trnx_raw ADD COLUMN IF NOT EXISTS ingest_run_id BIGINT;
CREATE INDEX IF NOT EXISTS ix_stg_trnx_raw_run ON stg_trnx_raw (tenant_id, ingest_run_id);

-- ---------------------------------------------------------------------
-- 6. Menu registration
-- ---------------------------------------------------------------------
-- Menu-grant enforcement gates access, so an unseeded/ungranted row renders as
-- an invisible screen. Seed the row, then grant it to the groups that already
-- hold Batch Logs (Super Admin / Bank Admin / Ops User).
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Ingest Trust', '/ops/ingest-trust', 'ShieldCheck', 'OPERATIONS', 5)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT gm.group_id, m.menu_id
FROM sys_menu m
CROSS JOIN (
    SELECT DISTINCT gm2.group_id
    FROM sys_group_menu gm2
    JOIN sys_menu m2 ON m2.menu_id = gm2.menu_id
    WHERE m2.path = '/ops/batch-logs'
) gm
WHERE m.path = '/ops/ingest-trust'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- 7. One-time coverage backfill from existing summaries
-- ---------------------------------------------------------------------
-- Gives the coverage calendar history on day one. Runs predating the ledger
-- have no run to point at, so last_run_id stays NULL and the board renders
-- them as UNKNOWN rather than pretending they were verified.
INSERT INTO ingest_day_coverage (tenant_id, txn_date, rows_summary, gross_amount, last_loaded_at, load_count)
SELECT s.tenant_id,
       s.business_date,
       SUM(s.total_txns),
       SUM(s.total_volume),
       NULL,
       1
FROM sum_daily_full s
GROUP BY s.tenant_id, s.business_date
ON CONFLICT (tenant_id, txn_date) DO NOTHING;
