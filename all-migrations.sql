-- =====================================================================
-- COMBINED MIGRATIONS  (generated 2026-07-06 01:16)
-- 29 files, concatenated in order.
-- Apply with: psql "$DB_URL" -v ON_ERROR_STOP=1 -f all-migrations.sql
-- =====================================================================


-- #####################################################################
-- [1/29] V2026_02_21__gap_fixes.sql
-- #####################################################################
-- =================================================================
-- Gap Analysis Fixes â€” Schema Migration
-- Date: 2026-02-21
-- Run this manually against your PostgreSQL database
-- =================================================================

-- GAP-7: Index on access_request for frequent queries by (email, status)
CREATE INDEX IF NOT EXISTS idx_access_request_email_status
    ON access_request(email, status);

-- GAP-21: Make users.email NOT NULL and UNIQUE
-- Step 1: Fix any null emails first (set to username@placeholder)
UPDATE users SET email = username || '@placeholder.local'
    WHERE email IS NULL OR email = '';

-- Step 2: Handle duplicates â€” append row id to make unique
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT id, email, ROW_NUMBER() OVER (PARTITION BY LOWER(email) ORDER BY id) as rn
        FROM users
    )
    LOOP
        IF r.rn > 1 THEN
            UPDATE users SET email = r.id || '_' || r.email WHERE id = r.id;
        END IF;
    END LOOP;
END $$;

-- Step 3: Add constraints
ALTER TABLE users ALTER COLUMN email SET NOT NULL;
DO $$ BEGIN
    ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- GAP-25: Ensure role_in_tenant and is_default_tenant columns exist in original DDL
-- (idempotent â€” safe to run if already present)
DO $$ BEGIN
    ALTER TABLE user_tenant_access ADD COLUMN role_in_tenant VARCHAR(50);
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE user_tenant_access ADD COLUMN is_default_tenant BOOLEAN DEFAULT FALSE;
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;



-- #####################################################################
-- [2/29] V2026_02_21_02__pdf_optimization.sql
-- #####################################################################
-- =================================================================
-- PDF Optimization â€” Schema Migration
-- Date: 2026-02-21
-- 1. Add contact_email to dim_merchant
-- 2. Create email_queue table for async email processing
-- =================================================================

-- 1. Add contact_email column to dim_merchant (for merchant report emailing)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'dim_merchant' AND column_name = 'contact_email'
    ) THEN
        ALTER TABLE dim_merchant ADD COLUMN contact_email VARCHAR(255);
    END IF;
END $$;

-- 2. Email queue table â€” fallback when JavaMailSender is not configured
--    External mail agent or cron job can poll this table to send emails
CREATE TABLE IF NOT EXISTS email_queue (
    id              BIGSERIAL PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    attachment_path VARCHAR(1000),
    status          VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, SENT, FAILED
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    sent_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_queue_status ON email_queue(status);



-- #####################################################################
-- [3/29] V2026_02_21_03__data_migration_menu.sql
-- #####################################################################
-- V2026_02_21_03: Add Data Migration menu entry
-- Only Super Admin can see this

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Data Migration', '/admin/data-migration', 'DatabaseZap', 'ADMINISTRATION', 7)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin' AND m.path = '/admin/data-migration'
ON CONFLICT DO NOTHING;



-- #####################################################################
-- [4/29] V2026_02_21_04__improvements.sql
-- #####################################################################
-- ======================================================
-- V2026_02_21_04: Platform Improvements
-- #7  SSO State Tokens table (persist across restart)
-- #14 Refresh Token tracking (rotation + revocation)
-- #15 API Rate Limit tracking
-- #26 Email Queue processor index
-- ======================================================

-- #14: Refresh Token tracking for rotation and revocation
CREATE TABLE IF NOT EXISTS refresh_token (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(100) NOT NULL,
    token_hash      VARCHAR(128) NOT NULL UNIQUE, -- SHA-256 of the actual token
    issued_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    replaced_by     VARCHAR(128), -- hash of the new token (for rotation tracking)
    user_agent      VARCHAR(500),
    ip_address      VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_username ON refresh_token(username);
CREATE INDEX IF NOT EXISTS idx_refresh_token_hash ON refresh_token(token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_token_expires ON refresh_token(expires_at) WHERE revoked = FALSE;

-- #7: SSO State Tokens (persist across restart / multi-instance)
CREATE TABLE IF NOT EXISTS sso_state_token (
    state_token     VARCHAR(100) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_sso_state_expires ON sso_state_token(expires_at);

-- #26: Ensure email_queue has processing indexes
CREATE INDEX IF NOT EXISTS idx_email_queue_pending ON email_queue(status, created_at) WHERE status = 'PENDING';



-- #####################################################################
-- [5/29] V2026_02_21_05__fix_partition_strategy.sql
-- #####################################################################
-- ======================================================
-- V2026_02_21_05: Fix Partition Strategy (#21)
-- Detach yearly fact_transaction partitions and create monthly ones.
-- Only runs if yearly partitions exist.
-- ======================================================

-- Step 1: Check if yearly partitions exist and detach them
-- We move data from yearly -> monthly via the DEFAULT partition.
-- The PartitionMaintenanceService will create monthly partitions on next startup.

DO $$
DECLARE
    part_name TEXT;
BEGIN
    -- Detach yearly partitions (fact_transaction_y2024, fact_transaction_y2025, etc.)
    FOR part_name IN
        SELECT tablename FROM pg_tables
        WHERE tablename ~ '^fact_transaction_y\d{4}$'
        AND schemaname = 'public'
    LOOP
        RAISE NOTICE 'Detaching yearly partition: %', part_name;
        -- Move data to default partition by detaching
        EXECUTE format('ALTER TABLE fact_transaction DETACH PARTITION %I', part_name);
        -- Copy data into the main table (will land in default or matching monthly partition)
        EXECUTE format('INSERT INTO fact_transaction SELECT * FROM %I ON CONFLICT DO NOTHING', part_name);
        -- Drop the old yearly partition
        EXECUTE format('DROP TABLE IF EXISTS %I', part_name);
        RAISE NOTICE 'Migrated and dropped: %', part_name;
    END LOOP;

    -- Ensure default partition exists
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'fact_transaction_default') THEN
        EXECUTE 'CREATE TABLE fact_transaction_default PARTITION OF fact_transaction DEFAULT';
    END IF;
END $$;



-- #####################################################################
-- [6/29] V2026_02_28_01__new_screens_security_alerts_api.sql
-- #####################################################################
-- ============================================================================
-- V2026_02_28_01: New screens â€” Security, Alerts, API Management
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



-- #####################################################################
-- [7/29] V2026_03_17_01__move_report_manager_to_operations.sql
-- #####################################################################
-- This migration is intentionally empty.
-- The menu update for Merchant Report Manager â†’ OPERATIONS
-- was moved to data.sql (startup script) for idempotent execution on every restart.
-- See: acquira-core/src/main/resources/data.sql section 11.



-- #####################################################################
-- [8/29] V2026_03_27_01__s3_tenant_settings.sql
-- #####################################################################
-- ============================================================
-- S3 Report Storage: per-tenant settings in tenant_setting
-- ============================================================
-- Settings stored as key-value rows using existing tenant_setting table.
-- No new tables needed. Keys used by S3SettingsController:
--
--   s3.enabled          BOOLEAN   "true" / "false"
--   s3.region           STRING    AWS region (e.g. "me-south-1")
--   s3.bucket           STRING    S3 bucket name
--   s3.prefix           STRING    Key prefix inside bucket (e.g. "reports")
--   s3.accessKeyId      STRING    IAM access key ID (plain text â€” not sensitive)
--   s3.secretAccessKey  ENCRYPTED AES-256-GCM encrypted, base64-encoded
--
-- The setting_type column uses "ENCRYPTED" to mark values that must
-- be decrypted before use. Plain values use "STRING" or "BOOLEAN".
-- ============================================================

-- 1. Fast per-tenant key lookup index (idempotent)
CREATE INDEX IF NOT EXISTS idx_tenant_setting_tenant_key
    ON tenant_setting(tenant_id, setting_key);

-- 2. Extend setting_type to include ENCRYPTED (if a CHECK constraint exists)
--    Safe to run even if constraint does not exist.
DO $$
BEGIN
    -- Drop old constraint if it exists and doesn't include ENCRYPTED
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'tenant_setting'
          AND constraint_name = 'setting_type_check'
    ) THEN
        ALTER TABLE tenant_setting DROP CONSTRAINT IF EXISTS setting_type_check;
    END IF;

    -- Re-add with ENCRYPTED included
    BEGIN
        ALTER TABLE tenant_setting
            ADD CONSTRAINT setting_type_check
            CHECK (setting_type IN ('STRING', 'BOOLEAN', 'NUMBER', 'JSON', 'ENCRYPTED'));
    EXCEPTION WHEN duplicate_object THEN
        -- Already exists with the right values
        NULL;
    END;
END
$$;

-- 3. Add comment for clarity
COMMENT ON TABLE tenant_setting IS
    'Per-tenant key-value configuration. Sensitive values (e.g. S3 secret key) '
    'are stored with setting_type=ENCRYPTED using AES-256-GCM encryption. '
    'Encryption key is configured via app.encryption.key in application.properties.';



-- #####################################################################
-- [9/29] V2026_03_27_02__s3_settings_menu.sql
-- #####################################################################
-- ============================================================
-- V2026_03_27_02: Add S3 Report Storage settings menu entry
-- Accessible by Super Admin and Admin groups
-- ============================================================

-- 1. Insert menu item
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('S3 Report Storage', '/admin/s3-settings', 'Cloud', 'ADMINISTRATION', 15)
ON CONFLICT (path) DO NOTHING;

-- 2. Grant access to Super Admin group
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
  AND m.path = '/admin/s3-settings'
ON CONFLICT DO NOTHING;

-- 3. Grant access to Admin group (if it exists)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Admin'
  AND m.path = '/admin/s3-settings'
ON CONFLICT DO NOTHING;



-- #####################################################################
-- [10/29] V2026_05_07_01__performance_indexes.sql
-- #####################################################################
-- ============================================================
-- V2026_05_07_01: Performance indexes for batch ingestion
-- ============================================================
-- pg_stat_user_indexes showed idx_dim_store_sid, idx_dim_terminal_tid,
-- idx_dim_terminal_store, and idx_dim_merchant_mid had ZERO scans, while
-- the batch ingestion SQL (TransactionJobConfig.stagingToFactTasklet and
-- the populateSummary aggregations) relies on lookups by
-- (tenant_id, sid / mid / tid) and (tenant_id, merchant_id, payment_date).
-- Without composite indexes that lead with tenant_id, the planner falls
-- back to sequential scans, making uploads take 10+ minutes on tenants
-- with many existing rows.
--
-- HOW THIS RUNS
-- -------------
-- This project does NOT use Flyway or Liquibase. SQL is applied via
-- Spring's spring.sql.init mechanism. This file is wired into
-- application.properties / application-prod.properties through:
--
--   spring.sql.init.schema-locations=classpath:schema.sql,\
--       classpath:db/migration/V2026_05_07_01__performance_indexes.sql
--
-- Spring runs each listed script on startup inside a transaction.
-- Therefore the statements below intentionally do NOT use
-- CREATE INDEX CONCURRENTLY (which is illegal inside a transaction).
--
-- Every statement is guarded with IF NOT EXISTS, so this file is
-- idempotent: the index build cost is paid only on the first startup
-- after deploy; subsequent startups skip already-existing indexes in
-- milliseconds.
--
-- FIRST-RUN WARNING (large existing tables)
-- -----------------------------------------
-- A plain CREATE INDEX takes a SHARE lock that blocks writes to the
-- table while the index builds. On an already-large fact_transaction
-- this means the first startup after deploy can block uploads for
-- several minutes. If that is unacceptable for a production cutover,
-- build the indexes ONCE manually via psql using the CONCURRENTLY
-- variants at the bottom of this file BEFORE deploying this build,
-- then this script becomes a no-op on startup.
-- ============================================================

-- â”€â”€ Dimension lookups used by stagingToFact joins â”€â”€
CREATE INDEX IF NOT EXISTS idx_dim_store_tenant_sid
    ON dim_store (tenant_id, sid)
    WHERE sid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dim_merchant_tenant_mid
    ON dim_merchant (tenant_id, mid)
    WHERE mid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dim_terminal_tenant_tid
    ON dim_terminal (tenant_id, tid)
    WHERE tid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dim_terminal_tenant_store
    ON dim_terminal (tenant_id, store_id);

-- â”€â”€ Staging-table scans (distinct-date + load-time queries) â”€â”€
CREATE INDEX IF NOT EXISTS idx_stg_merchant_tenant_loadtime
    ON stg_merchant_master_raw (tenant_id, load_time);

CREATE INDEX IF NOT EXISTS idx_stg_trnx_tenant_paydate
    ON stg_trnx_raw (tenant_id, payment_date);

-- â”€â”€ Fact + summary aggregation paths â”€â”€
CREATE INDEX IF NOT EXISTS idx_fact_transaction_tenant_merchant_date
    ON fact_transaction (tenant_id, merchant_id, payment_date);

CREATE INDEX IF NOT EXISTS idx_sum_daily_merchant_tenant_date
    ON sum_daily_merchant (tenant_id, business_date);

-- â”€â”€ Zero Transaction Report: per-terminal last-activity lookup â”€â”€
-- The report derives each terminal's last active day via a correlated
-- subquery:  SELECT MAX(business_date) FROM sum_daily_terminal
--            WHERE terminal_id = ? AND tenant_id = ?
-- Now that the report runs over the FULL portfolio (server-side pagination,
-- no more LIMIT 500), this index lets the MAX be answered straight from the
-- index per terminal instead of scanning sum_daily_terminal repeatedly.
-- Leads with tenant_id (consistent with the other composite indexes) and
-- ends with business_date so the MAX is a cheap index scan.
CREATE INDEX IF NOT EXISTS idx_sum_daily_terminal_tenant_terminal_date
    ON sum_daily_terminal (tenant_id, terminal_id, business_date);

-- ============================================================
-- MANUAL CONCURRENTLY VARIANTS (do NOT run inside a transaction)
-- ============================================================
-- Run these by hand via psql on a large production database BEFORE
-- deploying, so the auto-run block above finds every index already
-- present and becomes an instant no-op. Run them ONE AT A TIME;
-- CONCURRENTLY cannot be combined with other statements in a
-- transaction and must be issued outside any BEGIN/COMMIT block.
--
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dim_store_tenant_sid
--     ON dim_store (tenant_id, sid) WHERE sid IS NOT NULL;
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dim_merchant_tenant_mid
--     ON dim_merchant (tenant_id, mid) WHERE mid IS NOT NULL;
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dim_terminal_tenant_tid
--     ON dim_terminal (tenant_id, tid) WHERE tid IS NOT NULL;
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dim_terminal_tenant_store
--     ON dim_terminal (tenant_id, store_id);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stg_merchant_tenant_loadtime
--     ON stg_merchant_master_raw (tenant_id, load_time);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_stg_trnx_tenant_paydate
--     ON stg_trnx_raw (tenant_id, payment_date);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fact_transaction_tenant_merchant_date
--     ON fact_transaction (tenant_id, merchant_id, payment_date);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sum_daily_merchant_tenant_date
--     ON sum_daily_merchant (tenant_id, business_date);
-- CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sum_daily_terminal_tenant_terminal_date
--     ON sum_daily_terminal (tenant_id, terminal_id, business_date);
-- ============================================================



-- #####################################################################
-- [11/29] V2026_06_25_01__ref_country_missing_currencies.sql
-- #####################################################################
-- ============================================================
-- Migration: V2026_06_25_01__ref_country_missing_currencies
-- Purpose:   Insert 8 currency codes that were missing from
--            ref_country and triggered "currency not found"
--            warnings during transaction batch processing.
--
--            Missing codes identified from batch logs:
--              051 (AMD), 886 (YER), 901 (TWD), 946 (RON),
--              949 (TRY), 975 (BGN), 980 (UAH), 985 (PLN)
--
--            All have decimal_notation_value = 100 (2 decimal
--            places), matching ISO 4217 for each currency.
--
--            ON CONFLICT DO NOTHING is safe to run multiple
--            times â€” PK is country_code (alpha-2).
-- ============================================================

INSERT INTO ref_country
    (country_code, country_name, currency_code, currency_name, currency_symbol, phone_code, iso_numeric, decimal_notation_value)
VALUES
    ('AM', 'ARMENIA',       'AMD', 'Armenian Dram',      'AMD', '374',  '051', 100),
    ('YE', 'YEMEN',         'YER', 'Yemeni Rial',        'YER', '967',  '886', 100),
    ('TW', 'TAIWAN',        'TWD', 'New Taiwan Dollar',  'NT$', '886',  '901', 100),
    ('RO', 'ROMANIA',       'RON', 'Romanian Leu',       'lei', '40',   '946', 100),
    ('TR', 'TURKEY',        'TRY', 'Turkish Lira',       'â‚º',   '90',   '949', 100),
    ('BG', 'BULGARIA',      'BGN', 'Bulgarian Lev',      'Ð»Ð²',  '359',  '975', 100),
    ('UA', 'UKRAINE',       'UAH', 'Ukrainian Hryvnia',  'â‚´',   '380',  '980', 100),
    ('PL', 'POLAND',        'PLN', 'Polish Zloty',       'zÅ‚',  '48',   '985', 100)
ON CONFLICT (country_code) DO UPDATE
    SET iso_numeric            = EXCLUDED.iso_numeric,
        currency_code          = EXCLUDED.currency_code,
        currency_name          = EXCLUDED.currency_name,
        currency_symbol        = EXCLUDED.currency_symbol,
        decimal_notation_value = EXCLUDED.decimal_notation_value;
-- Note: ON CONFLICT DO UPDATE (rather than DO NOTHING) ensures that if a row
-- for the country already exists but was missing iso_numeric or decimal_notation_value,
-- those columns are backfilled. country_name and phone_code are left as-is
-- if the row already exists (safe â€” those columns are not in the SET clause
-- above to avoid overwriting any local customisations).



-- #####################################################################
-- [12/29] V2026_06_25_02__ref_card_scheme_upi_jcb.sql
-- #####################################################################
-- ============================================================
-- Migration: V2026_06_25_02__ref_card_scheme_upi_jcb
-- Purpose:   Fix card_type for UPI and JCB in ref_card_scheme
--            so transactionTenantProcessor maps them to CREDIT.
--
-- Background:
--   UPI (id=8) and JCB (id=9) were seeded in schema.sql with
--   card_type=0 (Generic). The transactionTenantProcessor switch
--   maps card_type: 0=DEBIT, 1=CREDIT, 2=DEBIT, 3=CREDIT, 4=DEBIT.
--   With card_type=0 both were bucketed as DEBIT, causing:
--     - Credit card count understated on P8 card analytics
--     - Debit count overstated
--
--   JCB cards from UnionPay terminal feeds also arrive with
--   CardScheme = 'NULL' (literal string). The batch SQL fix maps
--   NULL scheme rows to their card_type ('JCB') so this row being
--   correct in ref_card_scheme closes the full chain.
--
--   card_type=1 = Credit (per existing ref_card_scheme comments).
--   Both UPI (UnionPay International) and JCB are credit instruments.
--
-- Safe to re-run: UPDATE on PK id â€” idempotent.
-- ============================================================

UPDATE ref_card_scheme SET card_type = 1 WHERE code = 'UPI';
UPDATE ref_card_scheme SET card_type = 1 WHERE code = 'JCB';



-- #####################################################################
-- [13/29] V2026_06_26_01__db_maintenance.sql
-- #####################################################################
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



-- #####################################################################
-- [14/29] V2026_06_26_02__db_maintenance_menu.sql
-- #####################################################################
-- ============================================================
-- V2026_06_26_02: Add Database Maintenance menu entry
-- Accessible by Super Admin and Admin groups
-- ============================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Database Maintenance', '/admin/maintenance', 'Database', 'ADMINISTRATION', 16)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
  AND m.path = '/admin/maintenance'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Admin'
  AND m.path = '/admin/maintenance'
ON CONFLICT DO NOTHING;



-- #####################################################################
-- [15/29] V2026_06_27_01__revenue_leakage_flags_reconcile.sql
-- #####################################################################
-- Reconcile existing revenue_leakage_flags table with schema.sql.
-- Root cause: the table was first created by an older schema (before the
-- detector-enrichment columns + unique constraint existed). Because schema.sql
-- uses CREATE TABLE IF NOT EXISTS, the newer definition was never applied to the
-- already-existing table, so the upsert in RevenueLeakageDetectionService fails
-- (missing column 42703 / missing ON CONFLICT constraint 42P10).
-- This script is idempotent and safe to run on dev and prod.

-- 1. Add any missing enrichment columns
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS merchant_name      VARCHAR(200);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS business_date      DATE;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS metric_value       NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS baseline_value     NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS delta_pct          NUMERIC(9,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS est_monthly_impact NUMERIC(19,2);
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS status             VARCHAR(20) DEFAULT 'OPEN';
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS is_resolved        BOOLEAN DEFAULT FALSE;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS detected_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS resolved_at        TIMESTAMP;
ALTER TABLE revenue_leakage_flags ADD COLUMN IF NOT EXISTS resolved_by        VARCHAR(100);

-- 2. Add the unique constraint that ON CONFLICT (...) needs, if it's missing.
--    Wrapped in a DO block because ADD CONSTRAINT has no IF NOT EXISTS.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_revenue_leakage_flag'
    ) THEN
        ALTER TABLE revenue_leakage_flags
            ADD CONSTRAINT uq_revenue_leakage_flag
            UNIQUE (tenant_id, merchant_id, check_type, business_date);
    END IF;
END $$;



-- #####################################################################
-- [16/29] V2026_06_27_02__explorer_master_alert.sql
-- #####################################################################
-- ============================================================================
-- V2026_06_27_02 â€” Data Explorer governance: master items + threshold alerts
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



-- #####################################################################
-- [17/29] V2026_06_29_01__insight_covering_indexes.sql
-- #####################################################################
-- ============================================================
-- V2026_06_29_01: Covering indexes for sum_daily_insight at scale
-- ============================================================
-- WHY
-- ---
-- sum_daily_insight is the cross-tab that powers the Analytics Explorer,
-- Interactive Explorer, Insight Hub, Merchant Comparison, and every
-- dimension-filtered Business/Finance KPI + trend. Its grain is
--   (tenant, date, merchant, store, terminal, scheme, type, destination,
--    channel, opt_in)
-- so it is by far the largest summary table â€” at 10 tenants x ~999K txns/day
-- x 5 years it can reach the hundreds-of-millions to multi-billion row range,
-- depending on dimensional cardinality.
--
-- Both query engines (AnalyticsExplorerController summary grain, alias s; and
-- VolumeRevenueRepository, alias s) drive every read the same way:
--
--   WHERE s.tenant_id = ?              -- always
--     AND s.business_date BETWEEN ? AND ?   -- always (partition pruning)
--     [AND s.card_scheme  IN (...)]    -- Explorer card filters
--     [AND s.card_type    IN (...)]
--     [AND s.destination  IN (...)]
--     [AND s.channel      IN (...)]
--   GROUP BY <merchant_id/store_id>  OR  <card_scheme/card_type/destination/channel>
--   -- aggregating SUM(total_txns), SUM(total_volume), SUM(total_msf)
--
-- The only existing index, idx_sum_insight_tenant_date (tenant_id,
-- business_date), correctly serves the leading predicate but stops there.
-- Once a tenant's date slice is large, PostgreSQL still reads every matching
-- row from the heap to (a) apply the card-level filters, (b) fetch merchant_id
-- / store_id for the GROUP BY + dim joins, and (c) read the three SUM columns.
-- That heap read is the cost that turns these screens from milliseconds into
-- tens of seconds.
--
-- WHAT
-- ----
-- Two composite covering indexes, each INCLUDE-ing the three measure columns
-- so the common aggregations become index-only scans (no heap visit) on top of
-- the already-pruned partition:
--
--   1. idx_sdi_merchant_rollup  â€” merchant/store-grained rollups
--        (VolumeRevenueRepository: volume-revenue, merchant-financial,
--         performance dashboard, debit/prepaid, attrition, etc.)
--   2. idx_sdi_card_rollup      â€” card-dimension cross-tabs + card filters
--        (Explorer / Interactive / scheme & card-type breakdowns)
--
-- Both lead with (tenant_id, business_date) to match the mandatory predicates
-- and to keep partition pruning + range scan intact.
--
-- PARTITIONED-TABLE NOTE
-- ----------------------
-- sum_daily_insight is partitioned by RANGE (business_date). Creating an index
-- on the partitioned PARENT (PostgreSQL 11+) automatically creates a matching
-- local index on every existing and future partition, so this one statement
-- covers _y2024 / _y2025 / _default and any monthly partitions added later by
-- PartitionMaintenanceService. No per-partition DDL needed.
--
-- HOW THIS RUNS (same mechanism as V2026_05_07_01__performance_indexes.sql)
-- ------------------------------------------------------------------------
-- This project does NOT use Flyway/Liquibase. SQL is applied via Spring's
-- spring.sql.init mechanism, which runs each listed script inside a
-- transaction. CREATE INDEX CONCURRENTLY is illegal inside a transaction, so
-- the auto-run statements below are PLAIN CREATE INDEX (IF NOT EXISTS, so the
-- build cost is paid once and skipped on later startups).
--
-- Wire it in application*.properties alongside the existing perf-index file:
--   spring.sql.init.schema-locations=classpath:schema.sql,\
--       classpath:db/migration/V2026_05_07_01__performance_indexes.sql,\
--       classpath:db/migration/V2026_06_29_01__insight_covering_indexes.sql
--
-- FIRST-RUN WARNING (large existing table)
-- ----------------------------------------
-- A plain CREATE INDEX on the parent takes a lock that blocks writes to each
-- partition while its local index builds. On an already-large sum_daily_insight
-- this can block the ingest/upsert path (populateSummaryStep) for minutes. For
-- a production cutover, build these ONCE by hand with the CONCURRENTLY variants
-- at the bottom BEFORE deploying this build, so the auto-run block finds the
-- indexes already present and becomes an instant no-op.
-- ============================================================

-- â”€â”€ 1. Merchant/store rollup path (VolumeRevenueRepository) â”€â”€
-- Range-scan the tenant/date slice, group by merchant_id/store_id, sum the
-- measures straight from the index. store_id is included as a key (not just an
-- INCLUDE column) because several rollups GROUP BY both m.mid and st.sid, and
-- store_id is the join key to dim_store.
CREATE INDEX IF NOT EXISTS idx_sdi_merchant_rollup
    ON sum_daily_insight (tenant_id, business_date, merchant_id, store_id)
    INCLUDE (total_txns, total_volume, total_msf);

-- â”€â”€ 2. Card-dimension cross-tab path (Explorer / Interactive) â”€â”€
-- Serves both the card-level IN (...) filters and the GROUP BY on
-- scheme/type/destination/channel, with the SUMs answered from the index.
CREATE INDEX IF NOT EXISTS idx_sdi_card_rollup
    ON sum_daily_insight (tenant_id, business_date, card_scheme, card_type, destination, channel)
    INCLUDE (total_txns, total_volume, total_msf);

-- ============================================================
-- MANUAL CONCURRENTLY VARIANTS (run ONE AT A TIME, outside any transaction)
-- ============================================================
-- Run by hand via psql on a large production DB BEFORE deploying this build so
-- the auto-run block above becomes an instant no-op. CONCURRENTLY on a
-- partitioned parent: PostgreSQL creates the parent index INVALID, then you
-- build each partition's index concurrently and the parent validates. The
-- simplest robust approach is to create the parent index ONLY (ONLY keyword)
-- then CONCURRENTLY build per partition, or â€” most operationally simple â€” just
-- create concurrently per partition and attach. For a straightforward path,
-- create the parent index normally during a low-traffic window; the partitions
-- are individually far smaller than the whole.
--
-- Per-partition concurrent build example (repeat for every partition):
--
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sdi_merchant_rollup_y2026
--       ON sum_daily_insight_y2026 (tenant_id, business_date, merchant_id, store_id)
--       INCLUDE (total_txns, total_volume, total_msf);
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sdi_card_rollup_y2026
--       ON sum_daily_insight_y2026 (tenant_id, business_date, card_scheme, card_type, destination, channel)
--       INCLUDE (total_txns, total_volume, total_msf);
--
-- Then create the parent index with ONLY so it just attaches the existing
-- partition indexes without rebuilding:
--
--   CREATE INDEX IF NOT EXISTS idx_sdi_merchant_rollup
--       ON ONLY sum_daily_insight (tenant_id, business_date, merchant_id, store_id)
--       INCLUDE (total_txns, total_volume, total_msf);
--   ALTER INDEX idx_sdi_merchant_rollup ATTACH PARTITION idx_sdi_merchant_rollup_y2026;
--   -- (repeat ATTACH for each partition; once all attached the parent is VALID)
-- ============================================================



-- #####################################################################
-- [18/29] V2026_06_29_02__sum_monthly_insight.sql
-- #####################################################################
-- ============================================================
-- V2026_06_29_02: sum_monthly_insight â€” month-grain pre-aggregate
-- ============================================================
-- WHY
-- ---
-- sum_daily_insight is day-grained and, at 10 tenants x ~999K txns/day x 5y,
-- reaches the hundreds-of-millions to multi-billion row range. Even with the
-- covering indexes (V2026_06_29_01) and partition pruning, a WIDE date range
-- (a full year, or "all time") on the Explorer / Interactive / Business pages
-- still aggregates many months of day rows live. You cannot sum a billion rows
-- in 2 seconds; the only way to make wide ranges fast is to NOT read day rows
-- for them.
--
-- sum_monthly_insight is sum_daily_insight rolled up to month grain. A 12-month
-- query reads ~12 month-rows per (merchant x dimensional combo) instead of 365
-- day-rows â€” roughly a 30x reduction in rows scanned and aggregated. Queries
-- whose range is wider than a threshold (the app decides, e.g. > 90 days) read
-- this table; narrow ranges keep using sum_daily_insight for exact day grain.
--
-- GRAIN
-- -----
-- Identical dimensional grain to sum_daily_insight, with business_date replaced
-- by month_key (YYYYMM INT, same convention as sum_monthly_bank). Measures are
-- additive (SUM), so monthly = SUM(daily) reconciles exactly.
--
-- POPULATION
-- ----------
-- Written by populateSummaryStep (TransactionJobConfig) in the same pass that
-- writes sum_monthly_bank â€” rolled up FROM sum_daily_insight for the months in
-- scope, via INSERT ... ON CONFLICT DO UPDATE (idempotent re-aggregation).
--
-- NOT PARTITIONED
-- ---------------
-- Like sum_monthly_bank / sum_daily_mcc, this is a plain table. Even at 5y x 10
-- tenants its row count is the daily table divided by ~30, and month_key range
-- predicates + the index below keep it fast without partition overhead.
--
-- HOW THIS RUNS â€” migration file (NOT schema.sql)
-- -----------------------------------------------
-- schema.sql begins with DROP TABLE ... CASCADE for every table and is the dev
-- reset path; on prod it must NEVER run (spring.sql.init.mode=never after first
-- boot). New tables/columns therefore land via idempotent migration scripts
-- wired into spring.sql.init.schema-locations, exactly like the other
-- db/migration files. CREATE TABLE IF NOT EXISTS makes this safe to re-run.
-- ============================================================

CREATE TABLE IF NOT EXISTS sum_monthly_insight (
    summary_id   BIGSERIAL,
    tenant_id    INT NOT NULL,
    month_key    INT NOT NULL,          -- YYYYMM, e.g. 202606

    merchant_id  BIGINT,
    store_id     BIGINT,
    terminal_id  BIGINT,

    card_scheme  VARCHAR(50),
    card_type    VARCHAR(50),
    destination  VARCHAR(50),
    channel      VARCHAR(50),
    is_opt_in    BOOLEAN,

    total_txns   BIGINT DEFAULT 0,
    total_volume DECIMAL(19, 2) DEFAULT 0,
    total_msf    DECIMAL(21, 4) DEFAULT 0,

    PRIMARY KEY (summary_id),
    UNIQUE (tenant_id, month_key, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in)
);

-- Covering indexes mirroring the day-grain ones, so the same merchant/store and
-- card-dimension rollups are index-only on the monthly table too.
CREATE INDEX IF NOT EXISTS idx_smi_merchant_rollup
    ON sum_monthly_insight (tenant_id, month_key, merchant_id, store_id)
    INCLUDE (total_txns, total_volume, total_msf);

CREATE INDEX IF NOT EXISTS idx_smi_card_rollup
    ON sum_monthly_insight (tenant_id, month_key, card_scheme, card_type, destination, channel)
    INCLUDE (total_txns, total_volume, total_msf);

-- Row-Level Security: match the rest of the warehouse (defence-in-depth under
-- the app-level tenant scoping). get_current_tenant() is defined in schema.sql.
ALTER TABLE sum_monthly_insight ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'sum_monthly_insight' AND policyname = 'tenant_isolation_policy'
    ) THEN
        CREATE POLICY tenant_isolation_policy ON sum_monthly_insight
            USING (tenant_id = get_current_tenant());
    END IF;
END $$;



-- #####################################################################
-- [19/29] V2026_06_29_03__sum_monthly_insight_backfill.sql
-- #####################################################################
-- ============================================================
-- V2026_06_29_03: sum_monthly_insight â€” one-time historical backfill
-- ============================================================
-- WHY
-- ---
-- V2026_06_29_02 created sum_monthly_insight, and populateSummaryStep now keeps
-- it current for every NEW upload (and any month a tenant re-processes). But the
-- months already sitting in sum_daily_insight BEFORE this deploy were never
-- rolled up to the monthly table. Until they are, getSummary()'s monthly routing
-- (VolumeRevenueRepository.canUseMonthly) would read an empty / partial
-- sum_monthly_insight for those historical whole-month ranges and UNDER-REPORT.
--
-- This migration performs the one-time catch-up: it rolls up EVERY month present
-- in sum_daily_insight into sum_monthly_insight, for ALL tenants at once. It is
-- the exact same aggregation the batch job runs per upload, just unscoped to a
-- single tenant/month set.
--
-- IDEMPOTENT â€” SAFE TO RE-RUN ON EVERY BOOT
-- -----------------------------------------
-- spring.sql.init runs the schema-locations list on every startup while
-- mode=always. This script is written to be safe under that: ON CONFLICT DO
-- UPDATE re-computes the SAME additive SUMs from the same daily rows, so a
-- second run is a no-op in terms of values (it just rewrites identical numbers).
-- It never duplicates rows (the UNIQUE key dedups) and never drops anything.
--
-- It also self-skips the expensive scan once the monthly table is already
-- caught up: the guard below only runs the rollup when sum_monthly_insight is
-- empty OR is missing months that exist in sum_daily_insight. On a steady-state
-- system (every month already rolled up) the INSERT is skipped entirely, so this
-- file costs ~one cheap EXISTS check per boot rather than a full re-aggregation.
--
-- PERFORMANCE NOTE (first run on a large warehouse)
-- -------------------------------------------------
-- On a multi-billion-row sum_daily_insight the first execution is a large
-- GROUP BY and can take a while + hold a write lock on sum_monthly_insight. For
-- a zero-stall cutover you can instead run this same statement MANUALLY via psql
-- BEFORE flipping mode=alwaysâ†’never, optionally tenant-by-tenant:
--     -- per tenant, to keep each transaction smaller:
--     INSERT INTO sum_monthly_insight (...) SELECT ... FROM sum_daily_insight
--     WHERE tenant_id = <T> GROUP BY ... ON CONFLICT (...) DO UPDATE SET ...;
-- Having run it manually, this migration's guard sees the table already caught
-- up and skips â€” so leaving it wired in is harmless.
-- ============================================================

DO $$
BEGIN
    -- Only do the heavy rollup if there is at least one (tenant, month) present
    -- in the daily table that is NOT yet present in the monthly table. This makes
    -- the migration cheap on every boot after the first successful backfill.
    IF EXISTS (
        SELECT 1
        FROM (
            SELECT DISTINCT tenant_id, CAST(TO_CHAR(business_date, 'YYYYMM') AS INTEGER) AS mk
            FROM sum_daily_insight
        ) d
        LEFT JOIN (
            SELECT DISTINCT tenant_id, month_key AS mk FROM sum_monthly_insight
        ) m ON m.tenant_id = d.tenant_id AND m.mk = d.mk
        WHERE m.mk IS NULL
    ) THEN

        INSERT INTO sum_monthly_insight (
            tenant_id, month_key, merchant_id, store_id, terminal_id,
            card_scheme, card_type, destination, channel, is_opt_in,
            total_txns, total_volume, total_msf
        )
        SELECT
            tenant_id,
            CAST(TO_CHAR(business_date, 'YYYYMM') AS INTEGER),
            merchant_id, store_id, terminal_id,
            card_scheme, card_type, destination, channel, is_opt_in,
            SUM(total_txns), SUM(total_volume), SUM(total_msf)
        FROM sum_daily_insight
        GROUP BY
            tenant_id, TO_CHAR(business_date, 'YYYYMM'),
            merchant_id, store_id, terminal_id,
            card_scheme, card_type, destination, channel, is_opt_in
        ON CONFLICT (tenant_id, month_key, merchant_id, store_id, terminal_id,
                     card_scheme, card_type, destination, channel, is_opt_in)
        DO UPDATE SET
            total_txns   = EXCLUDED.total_txns,
            total_volume = EXCLUDED.total_volume,
            total_msf    = EXCLUDED.total_msf;

        RAISE NOTICE 'sum_monthly_insight backfill: rolled up historical months from sum_daily_insight';
    ELSE
        RAISE NOTICE 'sum_monthly_insight backfill: already caught up - skipped';
    END IF;
END $$;



-- #####################################################################
-- [20/29] V2026_07_02_01__budget_targets_menu.sql
-- #####################################################################
-- ============================================================
-- V2026_07_02_01: Budget Targets menu entry
-- Actual-vs-budget attainment page. Targets are entered here and
-- compared against sum_monthly_bank actuals by BudgetTargetController.
-- Route: /business/budget-targets  (ADMIN / SUPER_ADMIN only)
-- Placed in the BUSINESS category, after the analytics screens.
-- Idempotent: ON CONFLICT guards make re-runs a no-op (prod runs
-- spring.sql.init.mode=never, so this migration is the landing path).
-- ============================================================

-- 1. Insert menu item
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Budget Targets', '/business/budget-targets', 'Target', 'BUSINESS', 18)
ON CONFLICT (path) DO NOTHING;

-- 2. Grant to Super Admin + Bank Admin (seeded group names on prod)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path = '/business/budget-targets'
ON CONFLICT DO NOTHING;

-- 3. Grant to any uppercase-named admin groups if present (defensive,
--    mirrors other migrations that hedge against both naming styles)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path = '/business/budget-targets'
ON CONFLICT DO NOTHING;



-- #####################################################################
-- [21/29] V2026_07_03_01__merchant_churn_score.sql
-- #####################################################################
-- ============================================================
-- V2026_07_03_01: Merchant churn-risk score (ML, Phase 1)
-- ============================================================
-- Backs the churn-prediction model (Smile RandomForest) scored at the
-- end of the transaction batch, into a table shaped like
-- merchant_opportunity_score. One row per (tenant, merchant, calc_date):
-- the latest row per merchant is what the Attrition Report reads.
--
--   churn_probability : 0.0 .. 1.0  (model output; 30-60 day dormancy risk)
--   risk_band         : LOW | MEDIUM | HIGH  (derived from probability)
--   top_reason        : short human-readable driver (e.g. "Volume down 62%")
--   model_version     : which trained model produced this (audit / staleness)
--   scored_by         : MODEL | HEURISTIC  (HEURISTIC = cold-start fallback)
--
-- Idempotent + ALTER-based so it lands on prod (spring.sql.init.mode=never).
-- RLS-enabled to match every other tenant-scoped table.
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_churn_score (
    churn_id          BIGSERIAL PRIMARY KEY,
    tenant_id         INT NOT NULL,
    merchant_id       BIGINT,
    calc_date         DATE NOT NULL,
    churn_probability NUMERIC(6,4) DEFAULT 0,      -- 0.0000 .. 1.0000
    risk_band         VARCHAR(10),                 -- LOW | MEDIUM | HIGH
    top_reason        VARCHAR(255),
    model_version     VARCHAR(60),
    scored_by         VARCHAR(20) DEFAULT 'MODEL', -- MODEL | HEURISTIC
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_churn_score UNIQUE (tenant_id, merchant_id, calc_date)
);

-- Columns added defensively in case an older/partial table already exists
-- (CREATE TABLE IF NOT EXISTS would skip an updated definition otherwise).
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS churn_probability NUMERIC(6,4) DEFAULT 0;
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS risk_band         VARCHAR(10);
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS top_reason        VARCHAR(255);
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS model_version     VARCHAR(60);
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS scored_by         VARCHAR(20) DEFAULT 'MODEL';
ALTER TABLE merchant_churn_score ADD COLUMN IF NOT EXISTS created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Unique constraint that the batch upsert's ON CONFLICT needs.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_merchant_churn_score'
    ) THEN
        ALTER TABLE merchant_churn_score
            ADD CONSTRAINT uq_merchant_churn_score
            UNIQUE (tenant_id, merchant_id, calc_date);
    END IF;
END $$;

-- Read path: "latest row per merchant for this tenant" + attrition join.
CREATE INDEX IF NOT EXISTS idx_churn_score_tenant_date
    ON merchant_churn_score (tenant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_churn_score_merchant
    ON merchant_churn_score (tenant_id, merchant_id, calc_date DESC);

-- Tenant isolation (defence-in-depth; app already scopes by tenant_id).
ALTER TABLE merchant_churn_score ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_churn_score;
CREATE POLICY tenant_isolation_policy ON merchant_churn_score
    USING (tenant_id = get_current_tenant());



-- #####################################################################
-- [22/29] V2026_07_03_02__merchant_segment.sql
-- #####################################################################
-- ============================================================
-- V2026_07_03_02: Merchant segmentation (Phase 1, 6 data-backed segments)
-- ============================================================
-- Cross-cutting portfolio segmentation. One row per (tenant, merchant, calc_date):
-- the latest per merchant is the merchant's current segment. Computed in-batch after
-- business metrics, from sum_daily_merchant (settlement total_base_volume) + margin +
-- dim_merchant onboarding, using per-tenant trailing-90d percentiles.
--
--   primary_segment   : one of STRATEGIC | VOLUME_DRIVER | PROFIT_DRIVER |
--                        AT_RISK | NEW | LONG_TAIL
--   secondary_tags    : comma-separated other qualifying segments (may be empty)
--   segment_reason    : short human-readable why
--   segment_score     : 0..100 confidence/priority signal
--   model_version     : rule-set version (seg-rules-v1)
--
-- Idempotent + ALTER-based so it lands on prod (spring.sql.init.mode=never).
-- RLS-enabled to match every other tenant-scoped table.
-- ============================================================

CREATE TABLE IF NOT EXISTS merchant_segment (
    segment_id        BIGSERIAL PRIMARY KEY,
    tenant_id         INT NOT NULL,
    merchant_id       BIGINT,
    calc_date         DATE NOT NULL,
    primary_segment   VARCHAR(30),
    secondary_tags    VARCHAR(255),
    segment_reason    VARCHAR(255),
    segment_score     NUMERIC(5,2) DEFAULT 0,
    total_volume      NUMERIC(19,2),
    net_revenue       NUMERIC(19,2),
    net_margin_pct    NUMERIC(9,2),
    effective_bps     NUMERIC(9,2),
    net_take_bps      NUMERIC(9,2),
    volume_growth_pct NUMERIC(9,2),
    days_since_last   INT,
    model_version     VARCHAR(60),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_segment UNIQUE (tenant_id, merchant_id, calc_date)
);

-- Defensive column adds (CREATE TABLE IF NOT EXISTS skips updated defs on an
-- already-existing table).
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS primary_segment   VARCHAR(30);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS secondary_tags    VARCHAR(255);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS segment_reason    VARCHAR(255);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS segment_score     NUMERIC(5,2) DEFAULT 0;
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS total_volume      NUMERIC(19,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS net_revenue       NUMERIC(19,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS net_margin_pct    NUMERIC(9,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS effective_bps     NUMERIC(9,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS net_take_bps      NUMERIC(9,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS volume_growth_pct NUMERIC(9,2);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS days_since_last   INT;
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS model_version     VARCHAR(60);
ALTER TABLE merchant_segment ADD COLUMN IF NOT EXISTS created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Unique constraint the batch upsert's ON CONFLICT needs.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_merchant_segment'
    ) THEN
        ALTER TABLE merchant_segment
            ADD CONSTRAINT uq_merchant_segment
            UNIQUE (tenant_id, merchant_id, calc_date);
    END IF;
END $$;

-- Read paths: "latest per merchant for tenant" and "segment-mix for tenant/date".
CREATE INDEX IF NOT EXISTS idx_merchant_segment_tenant_date
    ON merchant_segment (tenant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_segment_merchant
    ON merchant_segment (tenant_id, merchant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_segment_primary
    ON merchant_segment (tenant_id, calc_date, primary_segment);

-- Tenant isolation (defence-in-depth; app already scopes by tenant_id).
ALTER TABLE merchant_segment ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_segment;
CREATE POLICY tenant_isolation_policy ON merchant_segment
    USING (tenant_id = get_current_tenant());



-- #####################################################################
-- [23/29] V2026_07_04_01__api_management_foundation.sql
-- #####################################################################
-- ============================================================================
-- V2026_07_04_01: API Management foundation
--   - Extend api_key with lifecycle/security columns (expiry, rate limit, IP allowlist, scopes)
--   - New api_request_log table for per-key usage analytics
--   - RLS on the new table (policy, but NOT FORCE â€” see note below)
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
-- cross-tenant DELETE with no tenant context â€” matching how the existing global
-- maintenance jobs operate on the other summary/fact tables. FORCE would filter
-- get_current_tenant()=NULL to zero rows and silently no-op the cleanup.
ALTER TABLE api_request_log ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON api_request_log;
CREATE POLICY tenant_isolation_policy ON api_request_log USING (tenant_id = get_current_tenant());



-- #####################################################################
-- [24/29] V2026_07_04_02__user_account_expiry.sql
-- #####################################################################
-- ============================================================================
-- V2026_07_04_02: User account expiry
--   Optional per-user cutoff timestamp. After it passes, the user is blocked at
--   login (AuthController) and auto-deactivated (is_active flipped off). NULL =
--   never expires. Settable/editable in Admin > User Management (Create/Edit).
--
-- This column is also added in schema.sql's users ALTER block (which runs on
-- every startup), so on this platform the column already lands without listing
-- this file in spring.sql.init.schema-locations. This standalone file exists for
-- the record and for any environment that applies db/migration/*.sql directly.
-- Idempotent and safe to run repeatedly on dev and prod.
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS account_expires_at TIMESTAMP;



-- #####################################################################
-- [25/29] V2026_07_05_01__interchange_scheme_fees.sql
-- #####################################################################
-- ============================================================================
-- V2026_07_05_01: Compute interchange fee AND scheme fee ourselves, at ingest.
--
-- WHY
-- ---
-- Until now interchange_fee was whatever the source feed sent (staging -> fact ->
-- every SUM(interchange_fee) rollup), and total_scheme_fee was hardcoded 0 in
-- every summary. Profit (msf - interchange - scheme_fee) was therefore wrong:
-- interchange was untrusted feed data and scheme fee was missing entirely.
--
-- This migration adds three rate-config tables + one fact column so BOTH fees
-- are computed by us at ingest (stagingToFactStep), off the SETTLEMENT amount
-- (store_base_currency_amount, single-currency) -- never the cardholder amount
-- (txn_currency_amount). Fees/profit are settlement-currency and reconcile
-- against total_base_volume (see project data-sourcing rules).
--
-- TWO INDEPENDENT FEES
-- --------------------
-- 1. INTERCHANGE (interchange_rate_local + mcc_sector_map): resolved by
--    priority (higher wins):
--      30 credit ticket thresholds (Auto >= 36700 AED; MC-only REX >= 3670 AED),
--         channel-specific (POS vs ECOM rates differ)
--      20 MCC/sector override: Govt .35 / Gas .50 / RE .30 / Edu .30 / Trans .45
--      11 JCB / UPI flat 1.75 (any tier/type)
--      10 scheme x tier x card_type base: Debit/Prepaid .75 (+37.5 cap);
--         Visa Std 1.15 / Prem 1.80; MC Std 1.25 / Prem 1.80
--       1 INTERNATIONAL flat 1.85 (scheme-agnostic)
-- 2. SCHEME FEE (scheme_fee_rate): destination x channel percentage
--      DOMESTIC POS .10 / ECOM .12 ; INTERNATIONAL POS .70 / ECOM .90
--
-- RESOLUTION MODEL
-- ----------------
-- interchange_rate_local rows carry `priority` (higher wins) and nullable match
-- columns (NULL = wildcard). stagingToFactStep selects the highest-priority row
-- whose non-null columns all match the transaction (ORDER BY priority DESC, id
-- LIMIT 1 in a LATERAL). Rates are retunable in-table with no rebuild.
--
-- TIER / CHANNEL / SCHEME derivation
-- ----------------------------------
-- scheme : card_scheme matched against ref_card_scheme by CODE or NAME (feed
--          sends both granular codes like VICP/MCPM and full names like
--          'UnionPay International'); group_name gives Visa/MasterCard/JCB/UPI.
-- tier   : ref_card_scheme.card_subtype (2 = Premium, else Standard). Plain or
--          unknown codes fall back to Standard.
-- channel: dim_terminal.type exact whitelist -> ECOM for 'ECOM PROFILE',
--          'MPGS', 'PAY BY LINK', 'PAY ON'; everything else (incl. SoftPOS,
--          physical devices, None/NULL/no terminal) -> POS.
--
-- SEED IDEMPOTENCY (important)
-- ----------------------------
-- schema-locations scripts run on EVERY startup. interchange_rate_local match
-- columns are nullable, and Postgres unique constraints treat NULLs as
-- distinct, so ON CONFLICT can NOT be used to make its seed idempotent. The
-- seed is therefore guarded with NOT EXISTS (tenant has zero rows): first run
-- seeds, later runs no-op, and in-UI rate edits are never clobbered.
-- mcc_sector_map / scheme_fee_rate keys are NOT NULL, so plain ON CONFLICT
-- works there.
--
-- SEED TARGET TENANT
-- ------------------
-- The default UAE rate card is seeded for the tenant whose bank_short_code =
-- 'ACQ' (Acquira Bank, institution_id BANK001, the code that appears as the
-- feed's Entity Name). Matching on institution_id = 'ACQ' was WRONG -- 'ACQ'
-- is the short code, not the institution_id (which is 'BANK001') -- and seeded
-- ZERO rows, silently leaving every fee on feed-fallback (scheme fee 0,
-- interchange = raw feed value). bank_short_code = 'ACQ' is the correct,
-- stable match.
--
-- HOW THIS RUNS
-- -------------
-- Splitter-safe: NO $$ dollar-quoting (that broke spring.sql.init before), so
-- this file is listed in spring.sql.init.schema-locations. On prod (sql.init
-- disabled), apply once via psql -- all statements are idempotent.
--
-- Unseeded tenants get no rate rows; the ingest UPDATE only touches rows with
-- a matching rate, so the feed value survives untouched and ingestion can
-- never break for an unseeded tenant.
--
-- NOTE 6513: it is 'RE' (flat 0.30 sector override) and NOT 'REX', so Real
-- Estate proper does NOT get the MC 3670-AED threshold -- only Exchange House
-- MCCs (6051, 4829) do. Conscious call: one sector per MCC, and the flat RE
-- override is the lower/safer treatment. Flip the seed row if business wants
-- 6513 on the threshold instead.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. fact_transaction.scheme_fee  (interchange_fee already exists)
-- ---------------------------------------------------------------------------
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS scheme_fee DECIMAL(19, 4);

-- ---------------------------------------------------------------------------
-- 2. mcc_sector_map -- MCC -> sector
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mcc_sector_map (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   INT NOT NULL,
    mcc         VARCHAR(10) NOT NULL,
    sector      VARCHAR(20) NOT NULL,
    UNIQUE (tenant_id, mcc)
);
CREATE INDEX IF NOT EXISTS idx_mcc_sector_map_lookup ON mcc_sector_map (tenant_id, mcc);

-- ---------------------------------------------------------------------------
-- 3. interchange_rate_local -- priority-ordered, channel-aware rate rows
--    NULL match column = wildcard. No unique constraint across the nullable
--    match columns (NULLs defeat it); integrity is by seed guard + admin UI.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS interchange_rate_local (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           INT NOT NULL,
    priority            INT NOT NULL DEFAULT 0,
    dest                VARCHAR(20) NOT NULL,          -- DOMESTIC / INTERNATIONAL
    channel             VARCHAR(20),                   -- POS / ECOM / NULL(any)
    scheme_group        VARCHAR(20),                   -- Visa / MasterCard / JCB / UPI / NULL(any)
    card_type           VARCHAR(20),                   -- CREDIT / DEBIT / PREPAID / NULL(any)
    tier                VARCHAR(20),                   -- Standard / Premium / NULL(any)
    mcc_sector          VARCHAR(20),                   -- Govt/Gas/RE/Edu/Trans/Auto/REX / NULL(any)
    min_ticket      DECIMAL(19, 2),                -- inclusive lower bound / NULL
    max_ticket      DECIMAL(19, 2),                -- exclusive upper bound / NULL
    interchange_pct     DECIMAL(9, 6) NOT NULL,        -- 0.011500 = 1.15%
    cap_amount          DECIMAL(19, 2),                -- 37.5 AED debit cap / NULL
    label               VARCHAR(80)
);
CREATE INDEX IF NOT EXISTS idx_interchange_rate_local_lookup
    ON interchange_rate_local (tenant_id, dest, priority DESC);

-- Legacy rename gap (2026-08-17): databases provisioned from a pre-2026-08-11
-- copy of this dump still carry min_ticket_aed/max_ticket_aed while the code
-- queries min_ticket/max_ticket ("column ilr.min_ticket does not exist" at
-- transactionLoadJob/stagingToFactStep). Idempotent without a DO block: on an
-- already-renamed DB the ADDs re-create empty legacy columns, the backfill
-- no-ops, and the DROPs converge.
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS min_ticket DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS max_ticket DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS min_ticket_aed DECIMAL(19,4);
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS max_ticket_aed DECIMAL(19,4);
UPDATE interchange_rate_local
   SET min_ticket = COALESCE(min_ticket, min_ticket_aed),
       max_ticket = COALESCE(max_ticket, max_ticket_aed)
 WHERE (min_ticket IS NULL AND min_ticket_aed IS NOT NULL)
    OR (max_ticket IS NULL AND max_ticket_aed IS NOT NULL);
ALTER TABLE interchange_rate_local DROP COLUMN IF EXISTS min_ticket_aed;
ALTER TABLE interchange_rate_local DROP COLUMN IF EXISTS max_ticket_aed;

-- ---------------------------------------------------------------------------
-- 4. scheme_fee_rate -- destination x channel percentage
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheme_fee_rate (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   INT NOT NULL,
    dest        VARCHAR(20) NOT NULL,
    channel     VARCHAR(20) NOT NULL,
    fee_pct     DECIMAL(9, 6) NOT NULL,
    UNIQUE (tenant_id, dest, channel)
);

-- ===========================================================================
-- SEED (tenant bank_short_code = 'ACQ')
-- ===========================================================================

-- MCC -> sector (base sectors + Auto dealers + REX Exchange House)
INSERT INTO mcc_sector_map (tenant_id, mcc, sector)
SELECT t.tenant_id, v.mcc, v.sector
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('8211','Edu'),('8220','Edu'),('8241','Edu'),('8244','Edu'),('8249','Edu'),('8299','Edu'),
    ('5541','Gas'),('5542','Gas'),
    ('9211','Govt'),('9222','Govt'),('9223','Govt'),('9311','Govt'),('9399','Govt'),
    ('4111','Govt'),('4112','Govt'),('4121','Govt'),('4131','Govt'),
    ('4814','Govt'),('4816','Govt'),('4899','Govt'),('4900','Govt'),
    ('6513','RE'),
    ('4784','Trans'),('7523','Trans'),
    ('5511','Auto'),('5521','Auto'),('5551','Auto'),('5561','Auto'),
    ('5571','Auto'),('5592','Auto'),('5598','Auto'),('5599','Auto'),
    ('6051','REX'),('4829','REX')
) AS v(mcc, sector)
ON CONFLICT (tenant_id, mcc) DO NOTHING;

-- Scheme fee grid
INSERT INTO scheme_fee_rate (tenant_id, dest, channel, fee_pct)
SELECT t.tenant_id, v.dest, v.channel, v.fee_pct
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('DOMESTIC','POS',       0.001000),
    ('DOMESTIC','ECOM',      0.001200),
    ('INTERNATIONAL','POS',  0.007000),
    ('INTERNATIONAL','ECOM', 0.009000)
) AS v(dest, channel, fee_pct)
ON CONFLICT (tenant_id, dest, channel) DO NOTHING;

-- Interchange rates. Guarded seed: only when the tenant has ZERO rows
-- (nullable match columns make ON CONFLICT unusable -- see header).
INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, v.priority, v.dest, v.channel, v.scheme_group, v.card_type, v.tier, v.mcc_sector,
       v.min_ticket, v.max_ticket, v.interchange_pct, v.cap_amount, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    (1,  'INTERNATIONAL', NULL,   NULL,          NULL,      NULL,       NULL,    NULL,      NULL,      0.018500, NULL, 'Intl flat 1.85'),

    (10, 'DOMESTIC', NULL,   NULL,          'DEBIT',   NULL,       NULL,    NULL,      NULL,      0.007500, 37.5, 'Local debit 0.75 (cap 37.5)'),
    (10, 'DOMESTIC', NULL,   NULL,          'PREPAID', NULL,       NULL,    NULL,      NULL,      0.007500, 37.5, 'Local prepaid 0.75 (cap 37.5)'),

    (10, 'DOMESTIC', NULL,   'Visa',        'CREDIT',  'Standard', NULL,    NULL,      NULL,      0.011500, NULL, 'Local Visa Std 1.15'),
    (10, 'DOMESTIC', NULL,   'Visa',        'CREDIT',  'Premium',  NULL,    NULL,      NULL,      0.018000, NULL, 'Local Visa Prem 1.80'),
    (10, 'DOMESTIC', NULL,   'MasterCard',  'CREDIT',  'Standard', NULL,    NULL,      NULL,      0.012500, NULL, 'Local MC Std 1.25'),
    (10, 'DOMESTIC', NULL,   'MasterCard',  'CREDIT',  'Premium',  NULL,    NULL,      NULL,      0.018000, NULL, 'Local MC Prem 1.80'),

    (11, 'DOMESTIC', NULL,   'JCB',         NULL,      NULL,       NULL,    NULL,      NULL,      0.017500, NULL, 'Local JCB flat 1.75'),
    (11, 'DOMESTIC', NULL,   'UnionPay',    NULL,      NULL,       NULL,    NULL,      NULL,      0.017500, NULL, 'Local UPI flat 1.75'),

    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'Govt',  NULL,      NULL,      0.003500, NULL, 'Sector Govt 0.35'),
    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'Gas',   NULL,      NULL,      0.005000, NULL, 'Sector Gas 0.50'),
    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'RE',    NULL,      NULL,      0.003000, NULL, 'Sector RE 0.30'),
    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'Edu',   NULL,      NULL,      0.003000, NULL, 'Sector Edu 0.30'),
    (20, 'DOMESTIC', NULL,   NULL,          NULL,      NULL,       'Trans', NULL,      NULL,      0.004500, NULL, 'Sector Trans 0.45'),

    (30, 'DOMESTIC', 'POS',  NULL,          'CREDIT',  NULL,       'Auto',  NULL,      36700.00,  0.015000, NULL, 'Auto POS <36700 -> 1.50'),
    (30, 'DOMESTIC', 'POS',  NULL,          'CREDIT',  NULL,       'Auto',  36700.00,  NULL,      0.009000, NULL, 'Auto POS >=36700 -> 0.90'),
    (30, 'DOMESTIC', 'ECOM', NULL,          'CREDIT',  NULL,       'Auto',  NULL,      36700.00,  0.013000, NULL, 'Auto ECOM <36700 -> 1.30'),
    (30, 'DOMESTIC', 'ECOM', NULL,          'CREDIT',  NULL,       'Auto',  36700.00,  NULL,      0.005000, NULL, 'Auto ECOM >=36700 -> 0.50'),

    (30, 'DOMESTIC', 'POS',  'MasterCard',  'CREDIT',  NULL,       'REX',   NULL,      3670.00,   0.006500, NULL, 'MC REX POS <3670 -> 0.65'),
    (30, 'DOMESTIC', 'POS',  'MasterCard',  'CREDIT',  NULL,       'REX',   3670.00,   NULL,      0.011500, NULL, 'MC REX POS >=3670 -> 1.15'),
    (30, 'DOMESTIC', 'ECOM', 'MasterCard',  'CREDIT',  NULL,       'REX',   NULL,      3670.00,   0.005000, NULL, 'MC REX ECOM <3670 -> 0.50'),
    (30, 'DOMESTIC', 'ECOM', 'MasterCard',  'CREDIT',  NULL,       'REX',   3670.00,   NULL,      0.011500, NULL, 'MC REX ECOM >=3670 -> 1.15')
) AS v(priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
       min_ticket, max_ticket, interchange_pct, cap_amount, label)
WHERE NOT EXISTS (SELECT 1 FROM interchange_rate_local x WHERE x.tenant_id = t.tenant_id);



-- #####################################################################
-- [26/29] V2026_07_05_02__sum_daily_terminal_fees_ceo_menu.sql
-- #####################################################################
-- ============================================================================
-- V2026_07_05_02: Store-grain fee columns + CEO Volume & Revenue screen menu.
--
-- WHY
-- ---
-- The CEO Volume & Revenue report needs MID + SID + interchange + scheme fee +
-- net margin in ONE fast read. No existing summary table has all of them:
--   - sum_daily_merchant has the fees but store_id is always NULL (no SID)
--   - sum_daily_insight has store_id but no interchange / scheme fee columns
-- Scanning fact_transaction per page-load is not acceptable for dashboards.
--
-- FIX: extend sum_daily_terminal (already partitioned + indexed + batch-written
-- at merchant x store x terminal day grain) with the three missing measures.
-- populateSummaryStep (TransactionJobConfig) fills them from fact_transaction
-- in the same rollup pass. Any MID/SID (or terminal) fee report is then a
-- summary read â€” same speed class as every other page.
--
--   total_base_volume  settlement volume (store_base_currency_amount) â€” the
--                      figure fees/margin are computed against
--   total_interchange  SUM(interchange_fee)  (computed at ingest, ours)
--   total_scheme_fee   SUM(scheme_fee)       (computed at ingest, ours)
--   total_revenue      (existing) already = msf - interchange - scheme_fee
--
-- Existing rows get 0 in the new columns; environment is being wiped and
-- re-ingested, so no backfill is shipped.
--
-- Also registers the new Executive screen '/business/ceo-volume-revenue' in
-- sys_menu and grants it to Super Admin + Bank Admin (sidebar is DB-driven).
-- Category is 'EXECUTIVE' (uppercase) to match schema.sql's existing Executive
-- rows so all Executive screens group together in the sidebar.
--
-- Splitter-safe (no $$); idempotent; listed in spring.sql.init.schema-locations
-- AFTER schema.sql so the ALTERs land on dev resets too. On prod apply once
-- via psql.
-- ============================================================================

ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_interchange DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_scheme_fee  DECIMAL(19, 2) DEFAULT 0;

-- Covering index for the MID/SID rollup read path (tenant + date range,
-- grouped by merchant/store).
CREATE INDEX IF NOT EXISTS idx_sdt_fee_rollup
    ON sum_daily_terminal (tenant_id, business_date, merchant_id, store_id);

-- â”€â”€ Sidebar registration â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Volume & Revenue', '/business/ceo-volume-revenue', 'TrendingUp', 'EXECUTIVE', 3
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/ceo-volume-revenue');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/ceo-volume-revenue'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;



-- #####################################################################
-- [27/29] V2026_07_05_03__bank_base_volume.sql
-- #####################################################################
-- ============================================================================
-- V2026_07_05_03: total_base_volume on sum_daily_bank / sum_monthly_bank.
--
-- WHY
-- ---
-- The CEO landing dashboard (ceo-summary) read volume from
-- sum_daily_bank.total_volume â€” CARDHOLDER currency â€” while the CEO
-- Volume & Revenue screen uses SETTLEMENT volume (total_base_volume on
-- sum_daily_terminal), the figure interchange/scheme fees are computed
-- against. On international mix the two "volume" numbers diverge, which is
-- exactly the kind of inconsistency a CEO will notice across two screens.
--
-- FIX: carry settlement volume at bank grain too. populateSummaryStep fills
-- sum_daily_bank.total_base_volume from fact rows and sum_monthly_bank rolls
-- it up from the daily table. ceo-summary switches its volume / avg-ticket /
-- margin math to the settlement figure, making both CEO screens (and any
-- future bank-grain fee reporting) internally consistent: fees, net revenue,
-- and margin all reference the same currency basis.
--
-- total_volume (cardholder) stays untouched â€” every existing consumer keeps
-- its current semantics.
--
-- Existing rows get 0; environment is being wiped and re-ingested, so no
-- backfill is shipped. Splitter-safe (no $$); idempotent; listed in
-- spring.sql.init.schema-locations after schema.sql. On prod apply once via
-- psql.
-- ============================================================================

ALTER TABLE sum_daily_bank   ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_monthly_bank ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;



-- #####################################################################
-- [28/29] V2026_07_05_04__loss_making_menu.sql
-- #####################################################################
-- ============================================================================
-- V2026_07_05_04: Loss-Making Merchants report menu entry (Executive).
--
-- Registers /business/loss-making under the EXECUTIVE category so the sidebar
-- shows three Executive screens: Dashboard, Volume & Revenue, Loss-Making
-- Merchants. Reuses the ceo-volume-revenue endpoint with lossOnly=true; no new
-- backend endpoint. Granted to Super Admin + Bank Admin (sidebar is DB-driven).
--
-- Idempotent; splitter-safe (no $$). Listed in schema-locations after
-- schema.sql. On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Loss-Making Merchants', '/business/loss-making', 'TrendingDown', 'EXECUTIVE', 4
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/loss-making');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/loss-making'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;



-- #####################################################################
-- [29/29] OPTIONAL__force_rls_backstop.sql
-- #####################################################################
-- ============================================================================
-- OPTIONAL / MANUAL MIGRATION -- FORCE ROW LEVEL SECURITY (tenant backstop)
-- ============================================================================
--
-- STATUS: NOT wired into spring.sql.init.schema-locations. This file will NOT
--         run automatically on startup. Apply it by hand, deliberately, only
--         after the precondition below is verified. It is named
--         OPTIONAL__... (double underscore, no version-date prefix) precisely
--         so it is never picked up by any V<date>__ ordering convention.
--
-- ----------------------------------------------------------------------------
-- WHY THIS EXISTS
-- ----------------------------------------------------------------------------
-- schema.sql runs `ALTER TABLE <t> ENABLE ROW LEVEL SECURITY` + a
-- `tenant_isolation_policy USING (tenant_id = get_current_tenant())` on every
-- business table. However, PostgreSQL EXEMPTS THE TABLE OWNER from RLS unless
-- the table is ALSO put into FORCE mode. The application connects as the
-- schema/table owner, so in practice RLS has been a NO-OP for the app: the only
-- thing actually isolating tenants is the explicit `WHERE tenant_id = ?` that
-- each query carries. When getCurrentTenantId() returned the wrong tenant
-- (fixed 2026 in TenantService: it ignored the switched X-Tenant-Id and used
-- the user's DB default), RLS did NOT catch the leak -- because RLS was being
-- bypassed by the owner. This migration turns RLS into a real backstop so a
-- single missed/incorrect tenant filter can no longer leak cross-tenant rows.
--
-- ----------------------------------------------------------------------------
-- PRECONDITION -- MUST be true before applying, or ingestion WILL break
-- ----------------------------------------------------------------------------
-- Under FORCE RLS, EVERY statement -- including batch INSERT ... SELECT into
-- fact/summary/staging tables -- is filtered by the policy, for the owner too.
-- A statement that runs on a DB connection where `app.current_tenant` is NOT
-- set will see get_current_tenant() = NULL, the policy `tenant_id = NULL`
-- evaluates to NULL (not true), and the statement silently affects ZERO rows.
-- That means: reads return empty, and (critically) batch writes insert nothing.
--
-- TenantAspect sets app.current_tenant via set_config(..., false) around every
-- method matching `com.acquira..service..*` and `com.acquira..repository..*`.
-- Before forcing RLS you MUST confirm that ALL write paths to the tables listed
-- below flow through those pointcuts on the SAME connection that runs the SQL.
-- In particular re-check:
--   * acquira-batch tasklets that use raw JdbcTemplate / EntityManager native
--     SQL (staging -> fact, summary population, monthly rollups, delete-day)
--   * any @Async / scheduled worker (context is thread-local; it does NOT
--     propagate to async threads automatically)
--   * MigrationController delete-day + backfill jobs
-- Verify by running one real MERCHANT + TRANSACTION upload on a copy of the DB
-- WITH this migration applied, and confirming fact_transaction + every sum_*
-- table populate exactly as before. If any table comes back empty, a write
-- path is missing tenant context -- fix that FIRST, do not force RLS yet.
--
-- ----------------------------------------------------------------------------
-- HOW TO APPLY (manually, e.g. psql)
-- ----------------------------------------------------------------------------
--   psql "$DATABASE_URL" -f OPTIONAL__force_rls_backstop.sql
-- Roll back with the companion block at the bottom (commented) if ingestion
-- misbehaves.
--
-- Idempotent: re-running is safe. FORCE is set only on tables that already have
-- RLS ENABLED, so this never gets ahead of schema.sql.
-- ============================================================================

DO '
DECLARE
    t text;
    rls_tables text[] := ARRAY[
        ''stg_merchant_master_raw'', ''stg_trnx_raw'',
        ''dim_merchant'', ''dim_store'', ''dim_terminal'', ''dim_bank_account'',
        ''bank_budget_target'', ''merchant_lifecycle_status'',
        ''merchant_activity_summary'', ''merchant_opportunity_score'',
        ''revenue_leakage_flags'', ''merchant_contact'', ''merchant_contract'',
        ''merchant_document'', ''merchant_risk_profile'',
        ''merchant_settlement_config'', ''merchant_note'',
        ''fact_transaction'',
        ''sum_daily_bank'', ''sum_daily_channel'', ''sum_daily_finance'',
        ''sum_daily_insight'', ''sum_daily_merchant'',
        ''sum_daily_merchant_attribute'', ''sum_daily_scheme'',
        ''sum_daily_terminal'', ''sum_monthly_bank'', ''sum_monthly_card'',
        ''sum_monthly_insight'', ''sum_monthly_merchant_metrics'',
        ''merchant_daily_metrics''
    ];
BEGIN
    FOREACH t IN ARRAY rls_tables LOOP
        -- Only force tables that actually exist AND already have RLS enabled,
        -- so this migration can never run ahead of schema.sql.
        IF EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = t AND c.relrowsecurity = true
              AND n.nspname = current_schema()
        ) THEN
            EXECUTE format(''ALTER TABLE %I FORCE ROW LEVEL SECURITY'', t);
            RAISE NOTICE ''FORCE RLS enabled on %'', t;
        ELSE
            RAISE NOTICE ''Skipped % (missing or RLS not enabled)'', t;
        END IF;
    END LOOP;
END;
';

-- ----------------------------------------------------------------------------
-- ROLLBACK (uncomment and run if ingestion or reads come back empty)
-- ----------------------------------------------------------------------------
-- DO '
-- DECLARE
--     t text;
--     rls_tables text[] := ARRAY[
--         ''stg_merchant_master_raw'', ''stg_trnx_raw'',
--         ''dim_merchant'', ''dim_store'', ''dim_terminal'', ''dim_bank_account'',
--         ''bank_budget_target'', ''merchant_lifecycle_status'',
--         ''merchant_activity_summary'', ''merchant_opportunity_score'',
--         ''revenue_leakage_flags'', ''merchant_contact'', ''merchant_contract'',
--         ''merchant_document'', ''merchant_risk_profile'',
--         ''merchant_settlement_config'', ''merchant_note'',
--         ''fact_transaction'',
--         ''sum_daily_bank'', ''sum_daily_channel'', ''sum_daily_finance'',
--         ''sum_daily_insight'', ''sum_daily_merchant'',
--         ''sum_daily_merchant_attribute'', ''sum_daily_scheme'',
--         ''sum_daily_terminal'', ''sum_monthly_bank'', ''sum_monthly_card'',
--         ''sum_monthly_insight'', ''sum_monthly_merchant_metrics'',
--         ''merchant_daily_metrics''
--     ];
-- BEGIN
--     FOREACH t IN ARRAY rls_tables LOOP
--         IF EXISTS (SELECT 1 FROM pg_class WHERE relname = t) THEN
--             EXECUTE format(''ALTER TABLE %I NO FORCE ROW LEVEL SECURITY'', t);
--         END IF;
--     END LOOP;
-- END;
-- ';




-- #####################################################################
-- V2026_08_19_01__dim_merchant_date_of_onboarding.sql
-- #####################################################################
-- Business onboarding date ("Date of Onboarding" in the merchant master file).
-- Distinct from created_date (CRM/ETL record-creation stamp). Open-date filters
-- and the Top Performers signed-by-RM board key off this column.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'dim_merchant' AND column_name = 'date_of_onboarding'
    ) THEN
        ALTER TABLE dim_merchant ADD COLUMN date_of_onboarding TIMESTAMP;
    END IF;
END $$;


-- #####################################################################
-- V2026_08_19_02__executive_daily_merchant_menu.sql
-- #####################################################################
-- Executive Daily Merchant Dashboard menu (/executive/daily-merchant),
-- EXECUTIVE order 7. New page: single business date + acquiring filters over
-- sum_daily_full with the full fee set (Vol/Count/MSF/ICF/SF/PG/NM).
-- Distinct from /business/daily-dashboard (month heat-grid), untouched.
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Daily Merchant Performance', '/executive/daily-merchant', 'CalendarClock', 'EXECUTIVE', 7
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/executive/daily-merchant');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/executive/daily-merchant'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;
