-- ============================================================================
-- V2026_09_11_01: Interchange (scheme) clearing-file data-integrity feature.
--
-- Adds the INTERCHANGE menu section (Outgoing + Incoming screens) and the
-- tables that back a scheme data-integrity validator: Acquira reads Visa
-- BASE II clearing files (168-byte fixed-width CTF, later Mastercard IPM) from
-- a watched server folder, parses them into transactions, and validates each
-- file against the scheme's own data-element requirements (structural,
-- balancing via TC 91/92 control totals, and field edit/requiredness rules).
--
--   interchange_file        — one row per parsed clearing file
--   interchange_transaction — one row per logical transaction in a file
--   interchange_violation   — one row per data-integrity violation found
--   stg_visa_clearing_raw   — run-scoped raw 168-byte record staging
--
-- Each file read is recorded as an ingest_run (source SERVER_FILE) via the
-- existing IngestRunJobListener; interchange_file.ingest_run_id links to it.
--
-- The APIs are gated by @menuAccess.canAccess('/interchange/outgoing') and
-- '/interchange/incoming', so these grants ARE the access control.
--
-- ADDITIVE ONLY. Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- ---------------------------------------------------------------------
-- 1. Menu — INTERCHANGE section with two screens
-- ---------------------------------------------------------------------
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Outgoing', '/interchange/outgoing', 'ArrowUpFromLine', 'INTERCHANGE', 1
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/interchange/outgoing');

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Incoming', '/interchange/incoming', 'ArrowDownToLine', 'INTERCHANGE', 2
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/interchange/incoming');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path IN ('/interchange/outgoing', '/interchange/incoming')
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path IN ('/interchange/outgoing', '/interchange/incoming')
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. interchange_file — one row per parsed clearing file
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS interchange_file (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL,
    ingest_run_id       BIGINT,                     -- FK-ish to ingest_run.id (soft link)
    scheme              VARCHAR(12)  NOT NULL,      -- VISA | MASTERCARD
    direction           VARCHAR(10)  NOT NULL,      -- OUTGOING | INCOMING
    format              VARCHAR(8)   NOT NULL DEFAULT 'CTF',  -- CTF | ITF | IPM
    file_name           VARCHAR(512) NOT NULL,      -- sanitised basename
    file_bytes          BIGINT,
    file_sha256         CHAR(64),                   -- duplicate-file detection
    record_bytes        INT          NOT NULL DEFAULT 168,    -- 168 CTF / 170 ITF
    record_count        BIGINT       NOT NULL DEFAULT 0,      -- physical TCRs read
    batch_count         INT          NOT NULL DEFAULT 0,
    transaction_count   BIGINT       NOT NULL DEFAULT 0,      -- logical transactions
    monetary_count      BIGINT       NOT NULL DEFAULT 0,
    processing_date     DATE,                       -- from TC 90 (YYDDD)
    settlement_date     DATE,                       -- from TC 90 incoming
    center_info_block   VARCHAR(8),                 -- CIB from TC 90
    test_file           BOOLEAN      NOT NULL DEFAULT FALSE,  -- TC 90 Test Option = TEST
    status              VARCHAR(16)  NOT NULL DEFAULT 'PARSED',-- PARSED | INVALID | ERROR
    violation_count     INT          NOT NULL DEFAULT 0,
    error_count         INT          NOT NULL DEFAULT 0,      -- severity ERROR
    warning_count       INT          NOT NULL DEFAULT 0,      -- severity WARN
    balanced            BOOLEAN,                    -- TC 91/92 control totals reconciled
    parsed_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    triggered_by        VARCHAR(128)
);
CREATE INDEX IF NOT EXISTS ix_ic_file_tenant_dir   ON interchange_file (tenant_id, direction, parsed_at DESC);
CREATE INDEX IF NOT EXISTS ix_ic_file_tenant_sha   ON interchange_file (tenant_id, file_sha256);

-- ---------------------------------------------------------------------
-- 3. interchange_transaction — one row per logical transaction
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS interchange_transaction (
    id                  BIGSERIAL PRIMARY KEY,
    file_id             BIGINT       NOT NULL REFERENCES interchange_file (id) ON DELETE CASCADE,
    tenant_id           BIGINT       NOT NULL,
    seq_in_file         BIGINT       NOT NULL,      -- ordinal of this transaction within the file
    batch_number        INT,
    transaction_code    VARCHAR(4)   NOT NULL,      -- TC 05/06/07/25/... (pos 1-2)
    tc_qualifier        VARCHAR(2),                 -- pos 3
    tcr_present         VARCHAR(16),                -- e.g. "0,1,3,5" TCRs seen
    account_masked      VARCHAR(24),                -- first6..last4 only, never full PAN
    acquirer_ref_number VARCHAR(23),                -- ARN (TCR0 pos 27-49) — join key
    acquirer_bid        VARCHAR(8),
    purchase_date       VARCHAR(8),                 -- MMDD as read
    destination_amount  NUMERIC(18,2),
    destination_ccy     VARCHAR(3),
    source_amount       NUMERIC(18,2),
    source_ccy          VARCHAR(3),
    merchant_name       VARCHAR(25),
    merchant_country    VARCHAR(3),
    mcc                 VARCHAR(4),
    fee_program_ind     VARCHAR(3),                 -- FPI (TCR1 pos 76-78)
    reimbursement_attr  VARCHAR(1),                 -- TCR0 pos 168
    monetary            BOOLEAN      NOT NULL DEFAULT TRUE,
    violation_count     INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_ic_txn_file  ON interchange_transaction (file_id, seq_in_file);
CREATE INDEX IF NOT EXISTS ix_ic_txn_arn   ON interchange_transaction (tenant_id, acquirer_ref_number);

-- ---------------------------------------------------------------------
-- 4. interchange_violation — one row per data-integrity violation
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS interchange_violation (
    id                  BIGSERIAL PRIMARY KEY,
    file_id             BIGINT       NOT NULL REFERENCES interchange_file (id) ON DELETE CASCADE,
    transaction_id      BIGINT       REFERENCES interchange_transaction (id) ON DELETE CASCADE, -- NULL = file/batch-level
    tenant_id           BIGINT       NOT NULL,
    record_no           BIGINT,                     -- physical 1-based record index in file
    transaction_code    VARCHAR(4),
    tcr                 VARCHAR(2),
    field_name          VARCHAR(64),
    field_position      VARCHAR(16),                -- "27-49"
    category            VARCHAR(24)  NOT NULL,      -- STRUCTURAL | BALANCING | FIELD_EDIT | REQUIRED
    severity            VARCHAR(8)   NOT NULL,      -- ERROR | WARN | INFO
    requiredness        VARCHAR(1),                 -- M | C | O (for REQUIRED violations)
    rule_code           VARCHAR(48),                -- machine key e.g. PAN_MOD10, TCR91_TCR_COUNT
    message             TEXT         NOT NULL,      -- human-readable
    expected_value      VARCHAR(64),
    actual_value        VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS ix_ic_viol_file     ON interchange_violation (file_id, severity);
CREATE INDEX IF NOT EXISTS ix_ic_viol_txn      ON interchange_violation (transaction_id);
CREATE INDEX IF NOT EXISTS ix_ic_viol_rule     ON interchange_violation (tenant_id, rule_code);

-- ---------------------------------------------------------------------
-- 5. stg_visa_clearing_raw — run-scoped raw record staging
--    Run-scoped by ingest_run_id from day one so concurrent tenant ingests
--    never collide (per the stg_trnx_raw lesson).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stg_visa_clearing_raw (
    id              BIGSERIAL PRIMARY KEY,
    ingest_run_id   BIGINT       NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    file_name       VARCHAR(512) NOT NULL,
    record_no       BIGINT       NOT NULL,
    transaction_code VARCHAR(4)  NOT NULL,
    tcr             VARCHAR(2)   NOT NULL,
    raw_record      VARCHAR(200) NOT NULL,          -- the 168/170-byte record, verbatim
    loaded_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_stg_visa_clearing_run ON stg_visa_clearing_raw (ingest_run_id, record_no);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_11_01__interchange_scheme_integrity.sql') ON CONFLICT (filename) DO NOTHING;
