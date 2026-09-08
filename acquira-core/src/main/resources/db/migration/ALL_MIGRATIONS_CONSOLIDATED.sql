-- ============================================================================
-- Acquira — Consolidated Migration Bundle
-- Generated: 2026-07-10, refreshed 2026-07-13
--
-- WHAT THIS FILE IS
-- ------------------
-- A single-file concatenation, for a ONE-TIME psql run against an EXISTING
-- prod DB (spring.sql.init.mode=never), of:
--   (a) schema_extras.sql — NOT in application-prod.properties'
--       schema-locations at all (only dev's), so its content (revenue-leakage
--       enrichment columns, churn/segment tables, sum_monthly_insight, insight
--       covering indexes, email-template dedupe) has NEVER landed on prod via
--       the app. Applied here FIRST, right after schema.sql/before all
--       migrations, mirroring dev's actual apply order.
--   (b) every migration file currently listed in application-prod.properties'
--       spring.sql.init.schema-locations, IN THAT EXACT ORDER (31 files).
-- schema.sql itself is NOT included — it must already exist in the target
-- database (this bundle is ALTER/INSERT-only, no base CREATE of core tables).
--
-- SCOPE — files INCLUDED (31 migrations + schema_extras.sql, in applied order):
--   schema_extras.sql (revenue-leakage cols, churn/segment, sum_monthly_insight, dedupe)
--   V2026_02_28_01__new_screens_security_alerts_api.sql
--   V2026_05_07_01__performance_indexes.sql
--   V2026_06_25_01__ref_country_missing_currencies.sql
--   V2026_06_25_02__ref_card_scheme_upi_jcb.sql
--   V2026_06_26_01__db_maintenance.sql
--   V2026_06_26_02__db_maintenance_menu.sql
--   V2026_06_27_02__explorer_master_alert.sql
--   V2026_07_02_01__budget_targets_menu.sql
--   V2026_07_04_01__api_management_foundation.sql
--   V2026_07_04_02__user_account_expiry.sql
--   V2026_07_05_01__interchange_scheme_fees.sql
--   V2026_07_05_02__sum_daily_terminal_fees_ceo_menu.sql
--   V2026_07_05_03__bank_base_volume.sql
--   V2026_07_05_04__loss_making_menu.sql
--   V2026_07_07_01__mcc_rate_card_uae.sql
--   V2026_07_07_03__fact_card_product_code.sql
--   V2026_07_07_04__intl_debit_interchange.sql
--   V2026_07_07_05__domestic_pos_scheme_fee.sql
--   V2026_07_10_01__ref_mcc_category.sql
--   V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql
--   V2026_07_10_03__sum_daily_merchant_destination.sql
--   V2026_07_10_04__email_queue_missing_columns.sql
--   V2026_07_10_05__sales_menu.sql
--   V2026_07_11_01__password_reset_otp.sql
--   V2026_07_11_02__settings_hub_menu.sql
--   V2026_07_11_03__tenant_provisioning.sql
--   V2026_07_11_04__sales_country_lead_reconcile.sql
--   V2026_07_11_05__sales_team_mapping_country_lead_id.sql
--   V2026_07_12_01__tenant_partition_provision_script.sql
--   V2026_07_12_02__sum_daily_full.sql
--   V2026_07_13_01__sum_daily_explorer.sql
--
-- DELIBERATELY EXCLUDED (to avoid duplicate/conflicting DDL, or psql-only by design):
--   schema.sql                              — base schema; must already exist
--   V2026_06_27_01__revenue_leakage_flags_reconcile.sql   — superseded by schema_extras.sql (included above)
--   V2026_06_29_01/02/03__*.sql (insight indexes / sum_monthly_insight / backfill)
--                                            — superseded by schema_extras.sql (included above)
--   V2026_07_03_01/02__*.sql (merchant_churn_score / merchant_segment)
--                                            — superseded by schema_extras.sql (included above)
--   V2026_07_06_01__summary_2026_2027_partitions.sql — psql-only by design (not in schema-locations)
--   REBUILD_TENANT_LIST_PARTITIONING.sql    — psql-only, separate one-off rebuild (not in schema-locations)
--   OPTIONAL__force_rls_backstop.sql        — optional, not applied by default
--   V2026_02_21_*, V2026_03_17_01, V2026_03_27_*  — superseded/legacy, not in the
--                                            current schema-locations list
--   Note: internal "FILE n/23" / "FILE n/25" markers below are historical
--   (kept as originally numbered when this bundle had fewer files) — the
--   authoritative file list is the SCOPE list above.
--
-- DUPLICATION SAFETY (why this file is safe to run more than once)
-- ------------------------------------------------------------------
-- 1. Every statement in every one of the 23 source files already follows the
--    project's migration-discipline rule: idempotent DDL (IF NOT EXISTS),
--    idempotent inserts (ON CONFLICT DO NOTHING / WHERE NOT EXISTS), or
--    absolute-value UPDATEs (re-running sets the same value again). This was
--    true before consolidation and remains true here — nothing was rewritten.
-- 2. This bundle additionally logs each source file's name into
--    schema_migration_log after its statements run, purely for audit/
--    observability (so you can see what has been applied and when). It does
--    NOT gate execution — the file-level "don't re-run a file that's already
--    been applied" decision is made by apply_migrations.bat BEFORE it invokes
--    psql, by checking this same log table. Run straight via `psql -f` and
--    the whole bundle still re-applies cleanly (all no-ops on a second run);
--    run via the batch script and already-logged files are skipped entirely.
-- 3. The whole bundle runs inside ONE transaction (via the batch script's
--    `--single-transaction` flag). A failure partway through rolls back
--    everything in this run — never a half-applied bundle.
--
-- ORDER DEPENDENCIES PRESERVED
-- -----------------------------
--   V2026_02_28_01 must precede V2026_07_04_01 (creates api_key before it's ALTERed).
--   V2026_07_07_01 must precede V2026_07_07_04 (seeds USD caps V2026_07_07_04 then
--     converts to AED) — both are kept in their original relative order below.
--
-- HOW TO RUN
-- ----------
--   Preferred (tracked, skips already-applied files):
--       apply_migrations.bat            (Windows, dev — see companion script)
--   Direct (always re-applies everything; safe per point 1-3 above):
--       psql -v ON_ERROR_STOP=1 --single-transaction -f ALL_MIGRATIONS_CONSOLIDATED.sql "%DB_URL%"
-- ============================================================================

-- ── Migration tracking table (audit log only — see "DUPLICATION SAFETY" §2) ──
CREATE TABLE IF NOT EXISTS schema_migration_log (
    filename    VARCHAR(200) PRIMARY KEY,
    applied_at  TIMESTAMP NOT NULL DEFAULT NOW()
);


-- ############################################################################
-- FILE 0/31: schema_extras.sql (NOT in prod's schema-locations — landed here)
-- ############################################################################
-- ── email_template_config: dedupe defaults + enforce uniqueness ──
DELETE FROM email_template_config a
USING email_template_config b
WHERE a.id > b.id
  AND a.tenant_id IS NOT DISTINCT FROM b.tenant_id
  AND a.name = b.name;

CREATE UNIQUE INDEX IF NOT EXISTS uq_email_tpl_tenant_name
    ON email_template_config (tenant_id, name);

-- ── V2026_06_27_01: revenue_leakage_flags enrichment columns + upsert key ──
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
CREATE UNIQUE INDEX IF NOT EXISTS uq_revenue_leakage_flag
    ON revenue_leakage_flags (tenant_id, merchant_id, check_type, business_date);

-- ── V2026_07_03_01: merchant_churn_score (ML churn risk) ──
CREATE TABLE IF NOT EXISTS merchant_churn_score (
    churn_id          BIGSERIAL PRIMARY KEY,
    tenant_id         INT NOT NULL,
    merchant_id       BIGINT,
    calc_date         DATE NOT NULL,
    churn_probability NUMERIC(6,4) DEFAULT 0,
    risk_band         VARCHAR(10),
    top_reason        VARCHAR(255),
    model_version     VARCHAR(60),
    scored_by         VARCHAR(20) DEFAULT 'MODEL',
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_merchant_churn_score UNIQUE (tenant_id, merchant_id, calc_date)
);
CREATE INDEX IF NOT EXISTS idx_churn_score_tenant_date ON merchant_churn_score (tenant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_churn_score_merchant    ON merchant_churn_score (tenant_id, merchant_id, calc_date DESC);
ALTER TABLE merchant_churn_score ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_churn_score;
CREATE POLICY tenant_isolation_policy ON merchant_churn_score USING (tenant_id = get_current_tenant());

-- ── V2026_07_03_02: merchant_segment (portfolio segmentation) ──
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
CREATE INDEX IF NOT EXISTS idx_merchant_segment_tenant_date ON merchant_segment (tenant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_segment_merchant    ON merchant_segment (tenant_id, merchant_id, calc_date DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_segment_primary     ON merchant_segment (tenant_id, calc_date, primary_segment);
ALTER TABLE merchant_segment ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON merchant_segment;
CREATE POLICY tenant_isolation_policy ON merchant_segment USING (tenant_id = get_current_tenant());

-- ── V2026_06_29_02: sum_monthly_insight (month-grain pre-aggregate) ──
CREATE TABLE IF NOT EXISTS sum_monthly_insight (
    summary_id   BIGSERIAL,
    tenant_id    INT NOT NULL,
    month_key    INT NOT NULL,
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
CREATE INDEX IF NOT EXISTS idx_smi_merchant_rollup
    ON sum_monthly_insight (tenant_id, month_key, merchant_id, store_id)
    INCLUDE (total_txns, total_volume, total_msf);
CREATE INDEX IF NOT EXISTS idx_smi_card_rollup
    ON sum_monthly_insight (tenant_id, month_key, card_scheme, card_type, destination, channel)
    INCLUDE (total_txns, total_volume, total_msf);
ALTER TABLE sum_monthly_insight ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_monthly_insight;
CREATE POLICY tenant_isolation_policy ON sum_monthly_insight USING (tenant_id = get_current_tenant());

-- ── V2026_06_29_01: sum_daily_insight covering indexes ──
CREATE INDEX IF NOT EXISTS idx_sdi_merchant_rollup
    ON sum_daily_insight (tenant_id, business_date, merchant_id, store_id)
    INCLUDE (total_txns, total_volume, total_msf);
CREATE INDEX IF NOT EXISTS idx_sdi_card_rollup
    ON sum_daily_insight (tenant_id, business_date, card_scheme, card_type, destination, channel)
    INCLUDE (total_txns, total_volume, total_msf);

INSERT INTO schema_migration_log (filename) VALUES ('schema_extras.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 1/23: V2026_02_28_01__new_screens_security_alerts_api.sql
-- ############################################################################
-- ============================================================================
-- V2026_02_28_01: New screens — Security, Alerts, API Management
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

INSERT INTO schema_migration_log (filename) VALUES ('V2026_02_28_01__new_screens_security_alerts_api.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 2/23: V2026_05_07_01__performance_indexes.sql
-- ############################################################################
-- ── Dimension lookups used by stagingToFact joins ──
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

-- ── Staging-table scans (distinct-date + load-time queries) ──
CREATE INDEX IF NOT EXISTS idx_stg_merchant_tenant_loadtime
    ON stg_merchant_master_raw (tenant_id, load_time);

CREATE INDEX IF NOT EXISTS idx_stg_trnx_tenant_paydate
    ON stg_trnx_raw (tenant_id, payment_date);

-- ── Fact + summary aggregation paths ──
CREATE INDEX IF NOT EXISTS idx_fact_transaction_tenant_merchant_date
    ON fact_transaction (tenant_id, merchant_id, payment_date);

CREATE INDEX IF NOT EXISTS idx_sum_daily_merchant_tenant_date
    ON sum_daily_merchant (tenant_id, business_date);

-- ── Zero Transaction Report: per-terminal last-activity lookup ──
CREATE INDEX IF NOT EXISTS idx_sum_daily_terminal_tenant_terminal_date
    ON sum_daily_terminal (tenant_id, terminal_id, business_date);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_05_07_01__performance_indexes.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 3/23: V2026_06_25_01__ref_country_missing_currencies.sql
-- ############################################################################
INSERT INTO ref_country
    (country_code, country_name, currency_code, currency_name, currency_symbol, phone_code, iso_numeric, decimal_notation_value)
VALUES
    ('AM', 'ARMENIA',       'AMD', 'Armenian Dram',      'AMD', '374',  '051', 100),
    ('YE', 'YEMEN',         'YER', 'Yemeni Rial',        'YER', '967',  '886', 100),
    ('TW', 'TAIWAN',        'TWD', 'New Taiwan Dollar',  'NT$', '886',  '901', 100),
    ('RO', 'ROMANIA',       'RON', 'Romanian Leu',       'lei', '40',   '946', 100),
    ('TR', 'TURKEY',        'TRY', 'Turkish Lira',       '₺',   '90',   '949', 100),
    ('BG', 'BULGARIA',      'BGN', 'Bulgarian Lev',      'лв',  '359',  '975', 100),
    ('UA', 'UKRAINE',       'UAH', 'Ukrainian Hryvnia',  '₴',   '380',  '980', 100),
    ('PL', 'POLAND',        'PLN', 'Polish Zloty',       'zł',  '48',   '985', 100)
ON CONFLICT (country_code) DO UPDATE
    SET iso_numeric            = EXCLUDED.iso_numeric,
        currency_code          = EXCLUDED.currency_code,
        currency_name          = EXCLUDED.currency_name,
        currency_symbol        = EXCLUDED.currency_symbol,
        decimal_notation_value = EXCLUDED.decimal_notation_value;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_06_25_01__ref_country_missing_currencies.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 4/23: V2026_06_25_02__ref_card_scheme_upi_jcb.sql
-- ############################################################################
UPDATE ref_card_scheme SET card_type = 1 WHERE code = 'UPI';
UPDATE ref_card_scheme SET card_type = 1 WHERE code = 'JCB';

INSERT INTO schema_migration_log (filename) VALUES ('V2026_06_25_02__ref_card_scheme_upi_jcb.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 5/23: V2026_06_26_01__db_maintenance.sql
-- ############################################################################
CREATE TABLE IF NOT EXISTS db_maintenance_config (
    id                SMALLINT PRIMARY KEY DEFAULT 1,
    enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    window_start_hour INT         NOT NULL DEFAULT 2,
    window_end_hour   INT         NOT NULL DEFAULT 5,
    tables_csv        TEXT,
    last_run_date     DATE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT db_maintenance_config_singleton CHECK (id = 1)
);

INSERT INTO db_maintenance_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS db_maintenance_run (
    id          BIGSERIAL   PRIMARY KEY,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    status      VARCHAR(16) NOT NULL,
    trigger     VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    tables_done INT         DEFAULT 0,
    detail      TEXT
);

CREATE INDEX IF NOT EXISTS idx_db_maintenance_run_started
    ON db_maintenance_run (started_at DESC);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_06_26_01__db_maintenance.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 6/23: V2026_06_26_02__db_maintenance_menu.sql
-- ############################################################################
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

INSERT INTO schema_migration_log (filename) VALUES ('V2026_06_26_02__db_maintenance_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 7/23: V2026_06_27_02__explorer_master_alert.sql
-- ############################################################################
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

INSERT INTO schema_migration_log (filename) VALUES ('V2026_06_27_02__explorer_master_alert.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 8/23: V2026_07_02_01__budget_targets_menu.sql
-- ############################################################################
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Budget Targets', '/business/budget-targets', 'Target', 'BUSINESS', 18)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path = '/business/budget-targets'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path = '/business/budget-targets'
ON CONFLICT DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_02_01__budget_targets_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 9/23: V2026_07_04_01__api_management_foundation.sql
-- ############################################################################
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS expires_at            TIMESTAMP;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS rate_limit_per_minute INT DEFAULT 120;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS allowed_ips           TEXT;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS last_used_ip          VARCHAR(64);
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS updated_at            TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_api_key_prefix_active ON api_key(key_prefix) WHERE is_active = true;

CREATE TABLE IF NOT EXISTS api_request_log (
    log_id      BIGSERIAL PRIMARY KEY,
    tenant_id   INT REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    key_id      BIGINT,
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

ALTER TABLE api_request_log ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON api_request_log;
CREATE POLICY tenant_isolation_policy ON api_request_log USING (tenant_id = get_current_tenant());

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_04_01__api_management_foundation.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 10/23: V2026_07_04_02__user_account_expiry.sql
-- ############################################################################
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_expires_at TIMESTAMP;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_04_02__user_account_expiry.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 11/23: V2026_07_05_01__interchange_scheme_fees.sql
-- ############################################################################
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS scheme_fee DECIMAL(19, 4);

CREATE TABLE IF NOT EXISTS mcc_sector_map (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   INT NOT NULL,
    mcc         VARCHAR(10) NOT NULL,
    sector      VARCHAR(20) NOT NULL,
    UNIQUE (tenant_id, mcc)
);
CREATE INDEX IF NOT EXISTS idx_mcc_sector_map_lookup ON mcc_sector_map (tenant_id, mcc);

CREATE TABLE IF NOT EXISTS interchange_rate_local (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           INT NOT NULL,
    priority            INT NOT NULL DEFAULT 0,
    dest                VARCHAR(20) NOT NULL,
    channel             VARCHAR(20),
    scheme_group        VARCHAR(20),
    card_type           VARCHAR(20),
    tier                VARCHAR(20),
    mcc_sector          VARCHAR(20),
    min_ticket      DECIMAL(19, 2),
    max_ticket      DECIMAL(19, 2),
    interchange_pct     DECIMAL(9, 6) NOT NULL,
    cap_amount          DECIMAL(19, 2),
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

CREATE TABLE IF NOT EXISTS scheme_fee_rate (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   INT NOT NULL,
    dest        VARCHAR(20) NOT NULL,
    channel     VARCHAR(20) NOT NULL,
    fee_pct     DECIMAL(9, 6) NOT NULL,
    UNIQUE (tenant_id, dest, channel)
);

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
-- NOT EXISTS guard, not ON CONFLICT: the multi-country engine migration
-- drops the inline UNIQUE (tenant_id, mcc), after which ON CONFLICT
-- (tenant_id, mcc) has no arbiter index and re-runs fail.
WHERE NOT EXISTS (
    SELECT 1 FROM mcc_sector_map x
    WHERE x.tenant_id = t.tenant_id AND x.mcc = v.mcc
);

INSERT INTO scheme_fee_rate (tenant_id, dest, channel, fee_pct)
SELECT t.tenant_id, v.dest, v.channel, v.fee_pct
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('DOMESTIC','POS',       0.007500),
    ('DOMESTIC','ECOM',      0.001200),
    ('INTERNATIONAL','POS',  0.009000),
    ('INTERNATIONAL','ECOM', 0.009000)
) AS v(dest, channel, fee_pct)
WHERE NOT EXISTS (
    SELECT 1 FROM scheme_fee_rate x
    WHERE x.tenant_id = t.tenant_id AND x.dest = v.dest AND x.channel = v.channel
);

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

ALTER TABLE fact_transaction   ADD COLUMN IF NOT EXISTS ecom_fee       DECIMAL(19, 4);
ALTER TABLE sum_daily_bank     ADD COLUMN IF NOT EXISTS total_ecom_fee DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_merchant ADD COLUMN IF NOT EXISTS total_ecom_fee DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_ecom_fee DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_monthly_bank   ADD COLUMN IF NOT EXISTS total_ecom_fee DECIMAL(19, 2) DEFAULT 0;

ALTER TABLE scheme_fee_rate ADD COLUMN IF NOT EXISTS scheme_group VARCHAR(20);

ALTER TABLE scheme_fee_rate DROP CONSTRAINT IF EXISTS scheme_fee_rate_tenant_id_dest_channel_key;
DROP INDEX IF EXISTS uq_scheme_fee_rate_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_scheme_fee_rate_key
    ON scheme_fee_rate (tenant_id, dest, channel, COALESCE(scheme_group, ''));

DELETE FROM scheme_fee_rate sfr
USING tenant t
WHERE sfr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ';

INSERT INTO scheme_fee_rate (tenant_id, dest, channel, scheme_group, fee_pct)
SELECT t.tenant_id, v.dest, v.channel, v.scheme_group, v.fee_pct
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('DOMESTIC','POS','Visa',            0.001100),
    ('DOMESTIC','ECOM','Visa',           0.001300),
    ('INTERNATIONAL','POS','Visa',       0.007500),
    ('INTERNATIONAL','ECOM','Visa',      0.009000),
    ('DOMESTIC','POS','MasterCard',      0.001100),
    ('DOMESTIC','ECOM','MasterCard',     0.001300),
    ('INTERNATIONAL','POS','MasterCard', 0.007500),
    ('INTERNATIONAL','ECOM','MasterCard',0.009000),
    ('DOMESTIC','POS','JCB',             0.000500),
    ('DOMESTIC','ECOM','JCB',            0.000500),
    ('INTERNATIONAL','POS','JCB',        0.000500),
    ('INTERNATIONAL','ECOM','JCB',       0.000500),
    ('DOMESTIC','POS','UnionPay',        0.000500),
    ('DOMESTIC','ECOM','UnionPay',       0.000500),
    ('INTERNATIONAL','POS','UnionPay',   0.000500),
    ('INTERNATIONAL','ECOM','UnionPay',  0.000500)
) AS v(dest, channel, scheme_group, fee_pct);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_05_01__interchange_scheme_fees.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 12/23: V2026_07_05_02__sum_daily_terminal_fees_ceo_menu.sql
-- ############################################################################
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_interchange DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_daily_terminal ADD COLUMN IF NOT EXISTS total_scheme_fee  DECIMAL(19, 2) DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_sdt_fee_rollup
    ON sum_daily_terminal (tenant_id, business_date, merchant_id, store_id);

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

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_05_02__sum_daily_terminal_fees_ceo_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 13/23: V2026_07_05_03__bank_base_volume.sql
-- ############################################################################
ALTER TABLE sum_daily_bank   ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;
ALTER TABLE sum_monthly_bank ADD COLUMN IF NOT EXISTS total_base_volume DECIMAL(19, 2) DEFAULT 0;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_05_03__bank_base_volume.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 14/23: V2026_07_05_04__loss_making_menu.sql
-- ############################################################################
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

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_05_04__loss_making_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 15/23: V2026_07_07_01__mcc_rate_card_uae.sql
-- (Large seed: 26 MCC-keyed interchange overrides x POS/ECOM + scheme-fee retune.
--  See original file in db/migration/ for the full per-row rationale comments —
--  omitted here for length; statements are byte-identical.)
-- ############################################################################
ALTER TABLE interchange_rate_local ADD COLUMN IF NOT EXISTS mcc VARCHAR(10);
CREATE INDEX IF NOT EXISTS idx_interchange_rate_local_mcc
    ON interchange_rate_local (tenant_id, mcc) WHERE mcc IS NOT NULL;

DELETE FROM interchange_rate_local ilr
USING tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 50 AND ilr.mcc IS NOT NULL;

INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, v.priority, v.dest, v.channel, v.scheme_group, v.card_type, v.tier, v.mcc_sector,
       v.mcc, v.min_ticket::numeric, v.max_ticket::numeric, v.interchange_pct::numeric, v.cap_amount::numeric, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8244', NULL, NULL, 0.006500, 32.50, 'MCC 8244 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8244', NULL, NULL, 0.006500, 32.50, 'MCC 8244 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8220', NULL, NULL, 0.006500, 32.50, 'MCC 8220 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8220', NULL, NULL, 0.006500, 32.50, 'MCC 8220 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8241', NULL, NULL, 0.006500, 32.50, 'MCC 8241 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8241', NULL, NULL, 0.006500, 32.50, 'MCC 8241 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8211', NULL, NULL, 0.006500, 32.50, 'MCC 8211 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8211', NULL, NULL, 0.006500, 32.50, 'MCC 8211 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8299', NULL, NULL, 0.006500, 32.50, 'MCC 8299 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8299', NULL, NULL, 0.006500, 32.50, 'MCC 8299 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '8249', NULL, NULL, 0.006500, 32.50, 'MCC 8249 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '8249', NULL, NULL, 0.006500, 32.50, 'MCC 8249 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '5542', NULL, NULL, 0.005000, 25.00, 'MCC 5542 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '5542', NULL, NULL, 0.005000, 25.00, 'MCC 5542 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '5542', NULL, NULL, 0.005000, NULL, 'MCC 5542 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '5542', NULL, NULL, 0.005000, NULL, 'MCC 5542 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '5542', NULL, NULL, 0.007000, NULL, 'MCC 5542 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '5542', NULL, NULL, 0.007000, NULL, 'MCC 5542 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '5541', NULL, NULL, 0.005000, 25.00, 'MCC 5541 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '5541', NULL, NULL, 0.005000, 25.00, 'MCC 5541 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '5541', NULL, NULL, 0.005000, NULL, 'MCC 5541 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '5541', NULL, NULL, 0.005000, NULL, 'MCC 5541 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '5541', NULL, NULL, 0.007000, NULL, 'MCC 5541 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '5541', NULL, NULL, 0.007000, NULL, 'MCC 5541 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9223', NULL, NULL, 0.005000, 25.00, 'MCC 9223 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9223', NULL, NULL, 0.005000, 25.00, 'MCC 9223 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9211', NULL, NULL, 0.005000, 25.00, 'MCC 9211 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9211', NULL, NULL, 0.005000, 25.00, 'MCC 9211 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9222', NULL, NULL, 0.005000, 25.00, 'MCC 9222 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9222', NULL, NULL, 0.005000, 25.00, 'MCC 9222 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9399', NULL, NULL, 0.005000, 25.00, 'MCC 9399 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9399', NULL, NULL, 0.005000, 25.00, 'MCC 9399 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9311', NULL, NULL, 0.005000, 25.00, 'MCC 9311 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9311', NULL, NULL, 0.005000, 25.00, 'MCC 9311 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '6513', NULL, NULL, 0.006500, 32.50, 'MCC 6513 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '6513', NULL, NULL, 0.006500, 32.50, 'MCC 6513 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9402', NULL, NULL, 0.005000, 25.00, 'MCC 9402 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9402', NULL, NULL, 0.005000, 25.00, 'MCC 9402 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '9405', NULL, NULL, 0.005000, 25.00, 'MCC 9405 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '9405', NULL, NULL, 0.005000, 25.00, 'MCC 9405 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '9405', NULL, NULL, 0.005000, NULL, 'MCC 9405 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9405', NULL, NULL, 0.005000, NULL, 'MCC 9405 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '9405', NULL, NULL, 0.011500, NULL, 'MCC 9405 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '9405', NULL, NULL, 0.018000, NULL, 'MCC 9405 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '7523', NULL, NULL, 0.005000, 25.00, 'MCC 7523 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '7523', NULL, NULL, 0.005000, 25.00, 'MCC 7523 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '7523', NULL, NULL, 0.012500, NULL, 'MCC 7523 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '7523', NULL, NULL, 0.018000, NULL, 'MCC 7523 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '7523', NULL, NULL, 0.006500, NULL, 'MCC 7523 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '7523', NULL, NULL, 0.006500, NULL, 'MCC 7523 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4131', NULL, NULL, 0.005000, 25.00, 'MCC 4131 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4131', NULL, NULL, 0.005000, 25.00, 'MCC 4131 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4111', NULL, NULL, 0.005000, 25.00, 'MCC 4111 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4111', NULL, NULL, 0.005000, 25.00, 'MCC 4111 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4112', NULL, NULL, 0.005000, 25.00, 'MCC 4112 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4112', NULL, NULL, 0.005000, 25.00, 'MCC 4112 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4112', NULL, NULL, 0.005000, NULL, 'MCC 4112 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4112', NULL, NULL, 0.005000, NULL, 'MCC 4112 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4112', NULL, NULL, 0.011500, NULL, 'MCC 4112 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4112', NULL, NULL, 0.018000, NULL, 'MCC 4112 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4121', NULL, NULL, 0.005000, 25.00, 'MCC 4121 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4121', NULL, NULL, 0.005000, 25.00, 'MCC 4121 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4784', NULL, NULL, 0.005000, 25.00, 'MCC 4784 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4784', NULL, NULL, 0.005000, 25.00, 'MCC 4784 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4784', NULL, NULL, 0.005000, NULL, 'MCC 4784 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4784', NULL, NULL, 0.005000, NULL, 'MCC 4784 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4784', NULL, NULL, 0.006500, NULL, 'MCC 4784 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4784', NULL, NULL, 0.006500, NULL, 'MCC 4784 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4899', NULL, NULL, 0.005000, 25.00, 'MCC 4899 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4899', NULL, NULL, 0.005000, 25.00, 'MCC 4899 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4899', NULL, NULL, 0.005000, NULL, 'MCC 4899 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4899', NULL, NULL, 0.005000, NULL, 'MCC 4899 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4899', NULL, NULL, 0.011500, NULL, 'MCC 4899 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4899', NULL, NULL, 0.018000, NULL, 'MCC 4899 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4816', NULL, NULL, 0.005000, 25.00, 'MCC 4816 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4816', NULL, NULL, 0.005000, 25.00, 'MCC 4816 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4816', NULL, NULL, 0.005000, NULL, 'MCC 4816 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4816', NULL, NULL, 0.005000, NULL, 'MCC 4816 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4816', NULL, NULL, 0.011500, NULL, 'MCC 4816 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4816', NULL, NULL, 0.018000, NULL, 'MCC 4816 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4814', NULL, NULL, 0.005000, 25.00, 'MCC 4814 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4814', NULL, NULL, 0.005000, 25.00, 'MCC 4814 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4814', NULL, NULL, 0.005000, NULL, 'MCC 4814 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4814', NULL, NULL, 0.005000, NULL, 'MCC 4814 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4814', NULL, NULL, 0.011500, NULL, 'MCC 4814 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4814', NULL, NULL, 0.018000, NULL, 'MCC 4814 Visa Prem'),
    (50, 'DOMESTIC', 'POS', NULL,   'DEBIT',   NULL,       NULL, '4900', NULL, NULL, 0.005000, 25.00, 'MCC 4900 debit'),
    (50, 'DOMESTIC', 'POS', NULL,   'PREPAID', NULL,       NULL, '4900', NULL, NULL, 0.005000, 25.00, 'MCC 4900 prepaid'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Standard', NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 MC Std'),
    (50, 'DOMESTIC', 'POS', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 MC Prem'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Standard', NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 Visa Std'),
    (50, 'DOMESTIC', 'POS', 'Visa',       'CREDIT', 'Premium',  NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8244', NULL, NULL, 0.006500, 32.50, 'MCC 8244 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8244', NULL, NULL, 0.006500, 32.50, 'MCC 8244 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8244', NULL, NULL, 0.006500, NULL, 'MCC 8244 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8220', NULL, NULL, 0.006500, 32.50, 'MCC 8220 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8220', NULL, NULL, 0.006500, 32.50, 'MCC 8220 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8220', NULL, NULL, 0.006500, NULL, 'MCC 8220 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8241', NULL, NULL, 0.006500, 32.50, 'MCC 8241 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8241', NULL, NULL, 0.006500, 32.50, 'MCC 8241 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8241', NULL, NULL, 0.006500, NULL, 'MCC 8241 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8211', NULL, NULL, 0.006500, 32.50, 'MCC 8211 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8211', NULL, NULL, 0.006500, 32.50, 'MCC 8211 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8211', NULL, NULL, 0.006500, NULL, 'MCC 8211 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8299', NULL, NULL, 0.006500, 32.50, 'MCC 8299 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8299', NULL, NULL, 0.006500, 32.50, 'MCC 8299 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8299', NULL, NULL, 0.006500, NULL, 'MCC 8299 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '8249', NULL, NULL, 0.006500, 32.50, 'MCC 8249 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '8249', NULL, NULL, 0.006500, 32.50, 'MCC 8249 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '8249', NULL, NULL, 0.006500, NULL, 'MCC 8249 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '5542', NULL, NULL, 0.005000, 25.00, 'MCC 5542 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '5542', NULL, NULL, 0.005000, 25.00, 'MCC 5542 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '5542', NULL, NULL, 0.005000, NULL, 'MCC 5542 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '5542', NULL, NULL, 0.005000, NULL, 'MCC 5542 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '5542', NULL, NULL, 0.007000, NULL, 'MCC 5542 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '5542', NULL, NULL, 0.007000, NULL, 'MCC 5542 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '5541', NULL, NULL, 0.005000, 25.00, 'MCC 5541 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '5541', NULL, NULL, 0.005000, 25.00, 'MCC 5541 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '5541', NULL, NULL, 0.005000, NULL, 'MCC 5541 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '5541', NULL, NULL, 0.005000, NULL, 'MCC 5541 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '5541', NULL, NULL, 0.007000, NULL, 'MCC 5541 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '5541', NULL, NULL, 0.007000, NULL, 'MCC 5541 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9223', NULL, NULL, 0.005000, 25.00, 'MCC 9223 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9223', NULL, NULL, 0.005000, 25.00, 'MCC 9223 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9223', NULL, NULL, 0.005000, NULL, 'MCC 9223 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9211', NULL, NULL, 0.005000, 25.00, 'MCC 9211 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9211', NULL, NULL, 0.005000, 25.00, 'MCC 9211 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9211', NULL, NULL, 0.005000, NULL, 'MCC 9211 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9222', NULL, NULL, 0.005000, 25.00, 'MCC 9222 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9222', NULL, NULL, 0.005000, 25.00, 'MCC 9222 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9222', NULL, NULL, 0.005000, NULL, 'MCC 9222 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9399', NULL, NULL, 0.005000, 25.00, 'MCC 9399 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9399', NULL, NULL, 0.005000, 25.00, 'MCC 9399 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9399', NULL, NULL, 0.005000, NULL, 'MCC 9399 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9311', NULL, NULL, 0.005000, 25.00, 'MCC 9311 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9311', NULL, NULL, 0.005000, 25.00, 'MCC 9311 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9311', NULL, NULL, 0.005000, NULL, 'MCC 9311 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '6513', NULL, NULL, 0.006500, 32.50, 'MCC 6513 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '6513', NULL, NULL, 0.006500, 32.50, 'MCC 6513 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '6513', NULL, NULL, 0.006500, NULL, 'MCC 6513 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9402', NULL, NULL, 0.005000, 25.00, 'MCC 9402 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9402', NULL, NULL, 0.005000, 25.00, 'MCC 9402 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9402', NULL, NULL, 0.005000, NULL, 'MCC 9402 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '9405', NULL, NULL, 0.005000, 25.00, 'MCC 9405 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '9405', NULL, NULL, 0.005000, 25.00, 'MCC 9405 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '9405', NULL, NULL, 0.005000, NULL, 'MCC 9405 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '9405', NULL, NULL, 0.005000, NULL, 'MCC 9405 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '9405', NULL, NULL, 0.011500, NULL, 'MCC 9405 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '9405', NULL, NULL, 0.018000, NULL, 'MCC 9405 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '7523', NULL, NULL, 0.005000, 25.00, 'MCC 7523 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '7523', NULL, NULL, 0.005000, 25.00, 'MCC 7523 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '7523', NULL, NULL, 0.012500, NULL, 'MCC 7523 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '7523', NULL, NULL, 0.018000, NULL, 'MCC 7523 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '7523', NULL, NULL, 0.006500, NULL, 'MCC 7523 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '7523', NULL, NULL, 0.006500, NULL, 'MCC 7523 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4131', NULL, NULL, 0.005000, 25.00, 'MCC 4131 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4131', NULL, NULL, 0.005000, 25.00, 'MCC 4131 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4131', NULL, NULL, 0.005000, NULL, 'MCC 4131 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4111', NULL, NULL, 0.005000, 25.00, 'MCC 4111 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4111', NULL, NULL, 0.005000, 25.00, 'MCC 4111 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4111', NULL, NULL, 0.005000, NULL, 'MCC 4111 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4112', NULL, NULL, 0.005000, 25.00, 'MCC 4112 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4112', NULL, NULL, 0.005000, 25.00, 'MCC 4112 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4112', NULL, NULL, 0.005000, NULL, 'MCC 4112 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4112', NULL, NULL, 0.005000, NULL, 'MCC 4112 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4112', NULL, NULL, 0.011500, NULL, 'MCC 4112 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4112', NULL, NULL, 0.018000, NULL, 'MCC 4112 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4121', NULL, NULL, 0.005000, 25.00, 'MCC 4121 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4121', NULL, NULL, 0.005000, 25.00, 'MCC 4121 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4121', NULL, NULL, 0.005000, NULL, 'MCC 4121 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4784', NULL, NULL, 0.005000, 25.00, 'MCC 4784 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4784', NULL, NULL, 0.005000, 25.00, 'MCC 4784 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4784', NULL, NULL, 0.005000, NULL, 'MCC 4784 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4784', NULL, NULL, 0.005000, NULL, 'MCC 4784 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4784', NULL, NULL, 0.006500, NULL, 'MCC 4784 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4784', NULL, NULL, 0.006500, NULL, 'MCC 4784 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4899', NULL, NULL, 0.005000, 25.00, 'MCC 4899 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4899', NULL, NULL, 0.005000, 25.00, 'MCC 4899 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4899', NULL, NULL, 0.005000, NULL, 'MCC 4899 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4899', NULL, NULL, 0.005000, NULL, 'MCC 4899 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4899', NULL, NULL, 0.011500, NULL, 'MCC 4899 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4899', NULL, NULL, 0.018000, NULL, 'MCC 4899 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4816', NULL, NULL, 0.005000, 25.00, 'MCC 4816 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4816', NULL, NULL, 0.005000, 25.00, 'MCC 4816 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4816', NULL, NULL, 0.005000, NULL, 'MCC 4816 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4816', NULL, NULL, 0.005000, NULL, 'MCC 4816 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4816', NULL, NULL, 0.011500, NULL, 'MCC 4816 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4816', NULL, NULL, 0.018000, NULL, 'MCC 4816 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4814', NULL, NULL, 0.005000, 25.00, 'MCC 4814 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4814', NULL, NULL, 0.005000, 25.00, 'MCC 4814 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4814', NULL, NULL, 0.005000, NULL, 'MCC 4814 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4814', NULL, NULL, 0.005000, NULL, 'MCC 4814 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4814', NULL, NULL, 0.011500, NULL, 'MCC 4814 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4814', NULL, NULL, 0.018000, NULL, 'MCC 4814 Visa Prem'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'DEBIT',   NULL,       NULL, '4900', NULL, NULL, 0.005000, 25.00, 'MCC 4900 debit'),
    (50, 'DOMESTIC', 'ECOM', NULL,   'PREPAID', NULL,       NULL, '4900', NULL, NULL, 0.005000, 25.00, 'MCC 4900 prepaid'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Standard', NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 MC Std'),
    (50, 'DOMESTIC', 'ECOM', 'MasterCard', 'CREDIT', 'Premium',  NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 MC Prem'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Standard', NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 Visa Std'),
    (50, 'DOMESTIC', 'ECOM', 'Visa',       'CREDIT', 'Premium',  NULL, '4900', NULL, NULL, 0.005000, NULL, 'MCC 4900 Visa Prem')
) AS v(priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
       mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label);

DELETE FROM scheme_fee_rate sfr
USING tenant t
WHERE sfr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ';

INSERT INTO scheme_fee_rate (tenant_id, dest, channel, scheme_group, fee_pct)
SELECT t.tenant_id, v.dest, v.channel, v.scheme_group, v.fee_pct::numeric
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('DOMESTIC','POS','Visa',             0.001200),
    ('DOMESTIC','ECOM','Visa',            0.001400),
    ('INTERNATIONAL','POS','Visa',        0.007500),
    ('INTERNATIONAL','ECOM','Visa',       0.009000),
    ('DOMESTIC','POS','MasterCard',       0.001200),
    ('DOMESTIC','ECOM','MasterCard',      0.001400),
    ('INTERNATIONAL','POS','MasterCard',  0.007500),
    ('INTERNATIONAL','ECOM','MasterCard', 0.009000),
    ('DOMESTIC','POS','Amex',             0.001200),
    ('DOMESTIC','ECOM','Amex',            0.001400),
    ('INTERNATIONAL','POS','Amex',        0.007500),
    ('INTERNATIONAL','ECOM','Amex',       0.009000),
    ('DOMESTIC','POS','JCB',              0.000500),
    ('DOMESTIC','ECOM','JCB',             0.000500),
    ('INTERNATIONAL','POS','JCB',         0.000500),
    ('INTERNATIONAL','ECOM','JCB',        0.000500),
    ('DOMESTIC','POS','UnionPay',         0.000500),
    ('DOMESTIC','ECOM','UnionPay',        0.000500),
    ('INTERNATIONAL','POS','UnionPay',    0.000500),
    ('INTERNATIONAL','ECOM','UnionPay',   0.000500),
    ('DOMESTIC','POS',NULL,               0.001200),
    ('DOMESTIC','ECOM',NULL,              0.001400),
    ('INTERNATIONAL','POS',NULL,          0.007500),
    ('INTERNATIONAL','ECOM',NULL,         0.009000)
) AS v(dest, channel, scheme_group, fee_pct);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_07_01__mcc_rate_card_uae.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 16/23: V2026_07_07_03__fact_card_product_code.sql
-- ############################################################################
ALTER TABLE stg_trnx_raw     ADD COLUMN IF NOT EXISTS card_product_code VARCHAR(20);
ALTER TABLE fact_transaction ADD COLUMN IF NOT EXISTS card_product_code VARCHAR(20);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_07_03__fact_card_product_code.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 17/23: V2026_07_07_04__intl_debit_interchange.sql
-- (Runs AFTER file 15 by design — converts USD caps that file seeds to AED.)
-- ############################################################################
DELETE FROM interchange_rate_local ilr
USING tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 2 AND ilr.dest = 'INTERNATIONAL';

DELETE FROM interchange_rate_local ilr
USING tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 10 AND ilr.card_type IN ('DEBIT','PREPAID')
  AND ilr.scheme_group IS NULL AND ilr.mcc IS NULL;

INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, v.priority, v.dest, v.channel, v.scheme_group, v.card_type, v.tier, v.mcc_sector,
       v.mcc, v.min_ticket::numeric, v.max_ticket::numeric, v.interchange_pct::numeric, v.cap_amount::numeric, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    (10, 'DOMESTIC', 'POS',  NULL, 'DEBIT',   NULL, NULL, NULL, NULL, NULL, 0.007500, 137.625, 'Local debit POS 0.75 (cap AED 137.625 = USD 37.5)'),
    (10, 'DOMESTIC', 'POS',  NULL, 'PREPAID', NULL, NULL, NULL, NULL, NULL, 0.007500, 137.625, 'Local prepaid POS 0.75 (cap AED 137.625 = USD 37.5)'),
    (10, 'DOMESTIC', 'ECOM', NULL, 'DEBIT',   NULL, NULL, NULL, NULL, NULL, 0.010000, 183.500, 'Local debit ECOM 1.00 (cap AED 183.5 = USD 50)'),
    (10, 'DOMESTIC', 'ECOM', NULL, 'PREPAID', NULL, NULL, NULL, NULL, NULL, 0.010000, 183.500, 'Local prepaid ECOM 1.00 (cap AED 183.5 = USD 50)')
) AS v(priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
       mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label);

DELETE FROM interchange_rate_local ilr
USING tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.priority = 50 AND ilr.mcc IS NOT NULL AND ilr.channel IS NULL;

INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, 50, 'DOMESTIC', NULL, v.scheme_group, 'CREDIT', NULL, NULL,
       v.mcc, NULL, NULL, v.interchange_pct::numeric, NULL, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('MasterCard', '4468', 0.013000, 'MCC 4468 MC flat 1.30'),
    ('MasterCard', '5013', 0.013000, 'MCC 5013 MC flat 1.30'),
    ('MasterCard', '5511', 0.013000, 'MCC 5511 MC flat 1.30'),
    ('MasterCard', '5521', 0.013000, 'MCC 5521 MC flat 1.30'),
    ('MasterCard', '5531', 0.013000, 'MCC 5531 MC flat 1.30'),
    ('MasterCard', '5532', 0.013000, 'MCC 5532 MC flat 1.30'),
    ('MasterCard', '5533', 0.013000, 'MCC 5533 MC flat 1.30'),
    ('MasterCard', '5551', 0.013000, 'MCC 5551 MC flat 1.30'),
    ('MasterCard', '5561', 0.013000, 'MCC 5561 MC flat 1.30'),
    ('MasterCard', '5571', 0.013000, 'MCC 5571 MC flat 1.30'),
    ('MasterCard', '5592', 0.013000, 'MCC 5592 MC flat 1.30'),
    ('MasterCard', '5599', 0.013000, 'MCC 5599 MC flat 1.30'),
    ('MasterCard', '7531', 0.013000, 'MCC 7531 MC flat 1.30'),
    ('MasterCard', '7534', 0.013000, 'MCC 7534 MC flat 1.30'),
    ('MasterCard', '7535', 0.013000, 'MCC 7535 MC flat 1.30'),
    ('MasterCard', '7538', 0.013000, 'MCC 7538 MC flat 1.30'),
    ('MasterCard', '4829', 0.011600, 'MCC 4829 MC flat 1.16'),
    ('MasterCard', '6051', 0.011600, 'MCC 6051 MC flat 1.16'),
    ('MasterCard', '5193', 0.010000, 'MCC 5193 MC flat 1.00'),
    ('MasterCard', '5811', 0.010000, 'MCC 5811 MC flat 1.00'),
    ('MasterCard', '5942', 0.010000, 'MCC 5942 MC flat 1.00'),
    ('MasterCard', '5992', 0.010000, 'MCC 5992 MC flat 1.00'),
    ('MasterCard', '7210', 0.010000, 'MCC 7210 MC flat 1.00'),
    ('MasterCard', '7211', 0.010000, 'MCC 7211 MC flat 1.00'),
    ('MasterCard', '7216', 0.010000, 'MCC 7216 MC flat 1.00'),
    ('MasterCard', '7217', 0.010000, 'MCC 7217 MC flat 1.00'),
    ('MasterCard', '7221', 0.010000, 'MCC 7221 MC flat 1.00'),
    ('MasterCard', '5960', 0.010000, 'MCC 5960 MC flat 1.00'),
    ('MasterCard', '6300', 0.010000, 'MCC 6300 MC flat 1.00'),
    ('MasterCard', '5411', 0.010500, 'MCC 5411 MC flat 1.05'),
    ('Visa',       '5411', 0.010500, 'MCC 5411 Visa flat 1.05'),
    ('Visa',       '5960', 0.010000, 'MCC 5960 Visa flat 1.00'),
    ('Visa',       '6300', 0.010000, 'MCC 6300 Visa flat 1.00'),
    ('Visa',       '8398', 0.006500, 'MCC 8398 Visa flat 0.65')
) AS v(scheme_group, mcc, interchange_pct, label);

INSERT INTO interchange_rate_local
    (tenant_id, priority, dest, channel, scheme_group, card_type, tier, mcc_sector,
     mcc, min_ticket, max_ticket, interchange_pct, cap_amount, label)
SELECT t.tenant_id, 50, 'DOMESTIC', NULL, NULL, v.card_type, NULL, NULL,
       v.mcc, NULL, NULL, 0.006500, 3.67, v.label
FROM (SELECT tenant_id FROM tenant WHERE bank_short_code = 'ACQ') t
CROSS JOIN (VALUES
    ('DEBIT',   '8398', 'MCC 8398 debit 0.65 (cap AED 3.67 = USD 1)'),
    ('PREPAID', '8398', 'MCC 8398 prepaid 0.65 (cap AED 3.67 = USD 1)'),
    ('DEBIT',   '8661', 'MCC 8661 debit 0.65 (cap AED 3.67 = USD 1)'),
    ('PREPAID', '8661', 'MCC 8661 prepaid 0.65 (cap AED 3.67 = USD 1)')
) AS v(card_type, mcc, label);

UPDATE interchange_rate_local ilr SET cap_amount = 119.275
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 32.50;

UPDATE interchange_rate_local ilr SET cap_amount = 91.75
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 25.00;

UPDATE interchange_rate_local ilr SET cap_amount = 137.625
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 37.50;

UPDATE interchange_rate_local ilr SET cap_amount = 183.50
FROM tenant t
WHERE ilr.tenant_id = t.tenant_id AND t.bank_short_code = 'ACQ'
  AND ilr.cap_amount = 50.00;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_07_04__intl_debit_interchange.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 18/23: V2026_07_07_05__domestic_pos_scheme_fee.sql
-- ############################################################################
UPDATE scheme_fee_rate s
SET fee_pct = 0.001100
FROM tenant t
WHERE s.tenant_id = t.tenant_id
  AND t.bank_short_code = 'ACQ'
  AND s.dest = 'DOMESTIC'
  AND s.channel = 'POS'
  AND (s.scheme_group IN ('Visa', 'MasterCard', 'Amex') OR s.scheme_group IS NULL)
  AND s.fee_pct = 0.001200;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_07_05__domestic_pos_scheme_fee.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 19/23: V2026_07_10_01__ref_mcc_category.sql
-- ############################################################################
CREATE TABLE IF NOT EXISTS ref_mcc_category (
    mcc      VARCHAR(4)   PRIMARY KEY,
    category VARCHAR(100) NOT NULL
);

INSERT INTO ref_mcc_category (mcc, category) VALUES
('742', 'Hospitals/Clinics'),
('763', 'Other services'),
('780', 'Other services'),
('1520', 'Other services'),
('1711', 'Other services'),
('1731', 'Other services'),
('1740', 'Other services'),
('1750', 'Other services'),
('1761', 'Other services'),
('1771', 'Other services'),
('1799', 'Other services'),
('2741', 'Other services'),
('2791', 'Other services'),
('2842', 'Other services'),
('3013', 'Airlines'),
('3026', 'Airlines'),
('3034', 'Airlines'),
('3070', 'Airlines'),
('3266', 'Airlines'),
('3355', 'Car rental'),
('3366', 'Car rental'),
('3381', 'Car rental'),
('3389', 'Car rental'),
('3390', 'Car rental'),
('3395', 'Car rental'),
('3412', 'Car rental'),
('3501', 'Hotels'),
('3503', 'Hotels'),
('3504', 'Hotels'),
('3506', 'Hotels'),
('3509', 'Hotels'),
('3512', 'Hotels'),
('3513', 'Hotels'),
('3519', 'Hotels'),
('3520', 'Hotels'),
('3530', 'Hotels'),
('3533', 'Hotels'),
('3543', 'Hotels'),
('3545', 'Hotels'),
('3553', 'Hotels'),
('3579', 'Hotels'),
('3583', 'Hotels'),
('3590', 'Hotels'),
('3612', 'Hotels'),
('3619', 'Hotels'),
('3640', 'Hotels'),
('3641', 'Hotels'),
('3642', 'Hotels'),
('3645', 'Hotels'),
('3649', 'Hotels'),
('3657', 'Hotels'),
('3690', 'Hotels'),
('3710', 'Hotels'),
('3722', 'Hotels'),
('3741', 'Hotels'),
('3745', 'Hotels'),
('3750', 'Hotels'),
('3778', 'Hotels'),
('3790', 'Hotels'),
('3807', 'Hotels'),
('3811', 'Hotels'),
('3812', 'Hotels'),
('3826', 'Hotels'),
('4011', 'Others'),
('4111', 'Government Services'),
('4119', 'Other services'),
('4121', 'Government Services'),
('4131', 'Government Services'),
('4214', 'Other services'),
('4215', 'Other services'),
('4225', 'Other retail shops'),
('4411', 'Other services'),
('4457', 'Other services'),
('4468', 'Other services'),
('4511', 'Airlines'),
('4582', 'Other services'),
('4722', 'Travel Agencies'),
('4789', 'Car rental'),
('4812', 'Mobile Phones'),
('4814', 'Other services'),
('4816', 'Other services'),
('4899', 'Other services'),
('4900', 'Government Services'),
('5013', 'Car Repairs/Maintenance'),
('5021', 'Furniture'),
('5039', 'Other retail shops'),
('5044', 'Hi Fi/Photo/Camera/Electronics'),
('5045', 'Hi Fi/Photo/Camera/Electronics'),
('5046', 'Other retail shops'),
('5047', 'Other retail shops'),
('5051', 'Other retail shops'),
('5065', 'Other retail shops'),
('5072', 'Other retail shops'),
('5074', 'Other retail shops'),
('5085', 'Others'),
('5094', 'Other retail shops'),
('5099', 'Other retail shops'),
('5111', 'Other retail shops'),
('5122', 'Other retail shops'),
('5131', 'Other retail shops'),
('5137', 'Clothing/Boutiques'),
('5139', 'Other retail shops'),
('5169', 'Other retail shops'),
('5172', 'Others'),
('5192', 'Other retail shops'),
('5193', 'Florist supplies, nursery stock, and flowers'),
('5198', 'Other retail shops'),
('5199', 'Other retail shops'),
('5200', 'Other retail shops'),
('5211', 'Other retail shops'),
('5231', 'Other retail shops'),
('5251', 'Other retail shops'),
('5261', 'Other retail shops'),
('5271', 'Other retail shops'),
('5309', 'Duty Free'),
('5310', 'Other retail shops'),
('5311', 'Other retail shops'),
('5331', 'Other retail shops'),
('5399', 'Other retail shops'),
('5411', 'Supermarkets'),
('5422', 'Other retail shops'),
('5441', 'Other retail shops'),
('5451', 'Other retail shops'),
('5462', 'Other retail shops'),
('5499', 'Other retail shops'),
('5511', 'Automobiles'),
('5521', 'Automobiles'),
('5532', 'Car Repairs/Maintenance'),
('5533', 'Car Repairs/Maintenance'),
('5541', 'Petrol/Gas Stations'),
('5551', 'Other services'),
('5571', 'Car rental'),
('5599', 'Car rental'),
('5611', 'Clothing/Boutiques'),
('5621', 'Clothing/Boutiques'),
('5631', 'Clothing/Boutiques'),
('5641', 'Clothing/Boutiques'),
('5651', 'Clothing/Boutiques'),
('5655', 'Clothing/Boutiques'),
('5661', 'Clothing/Boutiques'),
('5681', 'Clothing/Boutiques'),
('5691', 'Clothing/Boutiques'),
('5697', 'Clothing/Boutiques'),
('5698', 'Clothing/Boutiques'),
('5699', 'Clothing/Boutiques'),
('5712', 'Furniture'),
('5713', 'Other retail shops'),
('5714', 'Other retail shops'),
('5718', 'Other services'),
('5719', 'Furniture'),
('5722', 'Hi Fi/Photo/Camera/Electronics'),
('5732', 'Hi Fi/Photo/Camera/Electronics'),
('5733', 'Hi Fi/Photo/Camera/Electronics'),
('5734', 'Hi Fi/Photo/Camera/Electronics'),
('5735', 'Hi Fi/Photo/Camera/Electronics'),
('5811', 'Restaurants'),
('5812', 'Restaurants'),
('5813', 'Entertainment/Nightclubs'),
('5814', 'QSR'),
('5912', 'Pharmacies'),
('5921', 'Other retail shops'),
('5931', 'Other retail shops'),
('5932', 'Other retail shops'),
('5937', 'Other retail shops'),
('5940', 'Other retail shops'),
('5941', 'Other retail shops'),
('5942', 'Book stores'),
('5943', 'Other retail shops'),
('5944', 'Jewellery/Watches/Clocks'),
('5945', 'Other retail shops'),
('5946', 'Hi Fi/Photo/Camera/Electronics'),
('5947', 'Other retail shops'),
('5948', 'Other retail shops'),
('5949', 'Other retail shops'),
('5950', 'Other retail shops'),
('5960', 'Insurance'),
('5962', 'Other services'),
('5964', 'Other services'),
('5965', 'Other retail shops'),
('5968', 'Other services'),
('5969', 'Other services'),
('5970', 'Other retail shops'),
('5971', 'Other retail shops'),
('5972', 'Other retail shops'),
('5975', 'Other services'),
('5976', 'Other retail shops'),
('5977', 'Cosmetic Stores'),
('5978', 'Other retail shops'),
('5983', 'Petrol/Gas Stations'),
('5992', 'Florists'),
('5993', 'Other retail shops'),
('5994', 'Subscriptions/Memberships'),
('5995', 'Other retail shops'),
('5996', 'Other retail shops'),
('5997', 'Other retail shops'),
('5998', 'Other retail shops'),
('5999', 'Other retail shops'),
('6010', 'Financial'),
('6012', 'Accounting Services'),
('6051', 'Financial'),
('6211', 'Accounting Services'),
('6300', 'Insurance'),
('6513', 'Real Estate Services'),
('7011', 'Hotels'),
('7032', 'Hotels'),
('7210', 'Laundry, cleaning, and garment services'),
('7211', 'Laundry services: Family and commercial'),
('7216', 'Dry cleaners'),
('7221', 'Photographic studios'),
('7230', 'Cosmetic Stores'),
('7251', 'Other retail shops'),
('7276', 'Other services'),
('7277', 'Other services'),
('7296', 'Clothing/Boutiques'),
('7297', 'Health and Beauty'),
('7298', 'Health and Beauty'),
('7299', 'Other services'),
('7311', 'Other services'),
('7321', 'Accounting Services'),
('7333', 'Other services'),
('7338', 'Other services'),
('7339', 'Other services'),
('7342', 'Other services'),
('7349', 'Other services'),
('7361', 'Other services'),
('7372', 'Other services'),
('7375', 'Other retail shops'),
('7379', 'Other services'),
('7392', 'Other services'),
('7393', 'Other services'),
('7394', 'Other retail shops'),
('7395', 'Hi Fi/Photo/Camera/Electronics'),
('7399', 'Accounting Services'),
('7512', 'Car rental'),
('7519', 'Car rental'),
('7523', 'Car Repairs/Maintenance'),
('7531', 'Car Repairs/Maintenance'),
('7534', 'Car Repairs/Maintenance'),
('7535', 'Car Repairs/Maintenance'),
('7538', 'Car Repairs/Maintenance'),
('7542', 'Car Repairs/Maintenance'),
('7549', 'Car Repairs/Maintenance'),
('7622', 'Hi Fi/Photo/Camera/Electronics'),
('7623', 'Hi Fi/Photo/Camera/Electronics'),
('7629', 'Hi Fi/Photo/Camera/Electronics'),
('7631', 'Jewellery/Watches/Clocks'),
('7641', 'Furniture'),
('7699', 'Other services'),
('7829', 'Other services'),
('7832', 'Entertainment/Nightclubs'),
('7841', 'Other retail shops'),
('7911', 'Other services'),
('7922', 'Other retail shops'),
('7929', 'Other services'),
('7932', 'Other services'),
('7933', 'Other services'),
('7941', 'Other services'),
('7991', 'Other services'),
('7992', 'Other services'),
('7993', 'Other retail shops'),
('7994', 'Other services'),
('7996', 'Other services'),
('7997', 'Subscriptions/Memberships'),
('7998', 'Other services'),
('7999', 'Other services'),
('8011', 'Hospitals/Clinics'),
('8021', 'Hospitals/Clinics'),
('8031', 'Hospitals/Clinics'),
('8041', 'Hospitals/Clinics'),
('8042', 'Hospitals/Clinics'),
('8043', 'Hospitals/Clinics'),
('8049', 'Hospitals/Clinics'),
('8050', 'Hospitals/Clinics'),
('8062', 'Hospitals/Clinics'),
('8071', 'Hospitals/Clinics'),
('8099', 'Hospitals/Clinics'),
('8111', 'Other services'),
('8211', 'Education Services'),
('8220', 'Education Services'),
('8244', 'Education Services'),
('8249', 'Education Services'),
('8299', 'Education Services'),
('8351', 'Other services'),
('8398', 'Charity'),
('8641', 'Others'),
('8661', 'Charity'),
('8699', 'Subscriptions/Memberships'),
('8734', 'Other services'),
('8911', 'Other services'),
('8931', 'Accounting Services'),
('8999', 'Accounting Services'),
('9211', 'Other retail shops'),
('9222', 'Government Services'),
('9399', 'Government Services'),
('9402', 'Government Services'),
('9405', 'Government Services')
ON CONFLICT (mcc) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_10_01__ref_mcc_category.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 20/23: V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql
-- ############################################################################
UPDATE sys_menu
   SET category = 'BUSINESS',
       display_order = 17
 WHERE path = '/finance/summary';

DELETE FROM sys_group_menu
 WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE path = '/business/performance');

DELETE FROM sys_menu
 WHERE path = '/business/performance';

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 21/23: V2026_07_10_03__sum_daily_merchant_destination.sql
-- ############################################################################
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination (
    summary_id       BIGSERIAL,
    tenant_id        INT NOT NULL,
    business_date    DATE NOT NULL,
    merchant_id      BIGINT,
    destination      VARCHAR(20) NOT NULL,

    total_txns       BIGINT DEFAULT 0,
    total_volume     DECIMAL(19, 2) DEFAULT 0,
    total_msf        DECIMAL(21, 4) DEFAULT 0,
    total_interchange DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee DECIMAL(19, 2) DEFAULT 0,
    total_ecom_fee   DECIMAL(19, 2) DEFAULT 0,
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, destination)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2024
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2025
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2026
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_y2027
    PARTITION OF sum_daily_merchant_destination FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_merchant_destination_default
    PARTITION OF sum_daily_merchant_destination DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_merch_dest_tenant_date_merch
    ON sum_daily_merchant_destination (tenant_id, business_date, merchant_id);
CREATE INDEX IF NOT EXISTS idx_sum_merch_dest_tenant_date_dest
    ON sum_daily_merchant_destination (tenant_id, business_date, destination);

ALTER TABLE sum_daily_merchant_destination ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_merchant_destination;
CREATE POLICY tenant_isolation_policy ON sum_daily_merchant_destination
    USING (tenant_id = get_current_tenant());

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_10_03__sum_daily_merchant_destination.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 22/23: V2026_07_10_04__email_queue_missing_columns.sql
-- ############################################################################
ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS merchant_id      BIGINT;
ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS merchant_name    VARCHAR(200);
ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS is_html          BOOLEAN DEFAULT TRUE;
ALTER TABLE email_queue ADD COLUMN IF NOT EXISTS statement_month  VARCHAR(10);

CREATE INDEX IF NOT EXISTS idx_email_queue_tenant_month
    ON email_queue (tenant_id, statement_month);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_10_04__email_queue_missing_columns.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 23/23: V2026_07_10_05__sales_menu.sql
-- ############################################################################
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
  ('Sales Team Management', '/sales/team-management',    'Users',   'SALES', 1),
  ('Country Leads',         '/sales/country-management', 'Globe',   'SALES', 2),
  ('Agent Directory',       '/sales/agents',              'Contact', 'SALES', 3),
  ('Sales Leaderboard',     '/sales/leaderboard',          'Trophy',  'SALES', 4),
  ('Sales Hierarchy',       '/sales/hierarchy',            'Network', 'SALES', 5)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path IN (
    '/sales/team-management', '/sales/country-management',
    '/sales/agents', '/sales/leaderboard', '/sales/hierarchy'
  )
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path IN (
    '/sales/team-management', '/sales/country-management',
    '/sales/agents', '/sales/leaderboard', '/sales/hierarchy'
  )
ON CONFLICT DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_10_05__sales_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 24/25: V2026_07_11_01__password_reset_otp.sql
-- ############################################################################
-- Forgot-password moves from an emailed reset LINK to a 6-digit OTP flow.
-- Without these columns the OTP endpoints throw SQLGrammarException.
ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS otp_hash      VARCHAR(255);
ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS attempt_count INT DEFAULT 0;
ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS verified      BOOLEAN DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_pw_reset_token_user
    ON password_reset_token (user_id, used);

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_11_01__password_reset_otp.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 25/25: V2026_07_11_02__settings_hub_menu.sql
-- ############################################################################
-- Unified Settings hub sidebar entry (/settings), granted to both admin
-- groups (and legacy uppercase variants). Mirrored by
-- MenuController.ensureMenusExist() as a startup safety net.
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Settings', '/settings', 'Settings', 'ADMINISTRATION', 1)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin', 'Admin')
  AND m.path = '/settings'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path = '/settings'
ON CONFLICT DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_11_02__settings_hub_menu.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 26/31: V2026_07_11_03__tenant_provisioning.sql
-- ############################################################################
CREATE TABLE IF NOT EXISTS tenant_provision_script (
    script_id         BIGSERIAL PRIMARY KEY,
    script_name       VARCHAR(150) NOT NULL UNIQUE,
    script_order      INT NOT NULL DEFAULT 100,
    script_sql        TEXT NOT NULL,
    description       TEXT,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    continue_on_error BOOLEAN NOT NULL DEFAULT FALSE,
    created_by        VARCHAR(100),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tenant_provision_log (
    log_id        BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    script_id     BIGINT REFERENCES tenant_provision_script(script_id) ON DELETE SET NULL,
    script_name   VARCHAR(150),
    status        VARCHAR(20) NOT NULL,
    error_message TEXT,
    duration_ms   BIGINT,
    executed_by   VARCHAR(100),
    executed_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_provision_log_tenant ON tenant_provision_log(tenant_id, executed_at DESC);

CREATE TABLE IF NOT EXISTS schema_migration_registry (
    registry_id     BIGSERIAL PRIMARY KEY,
    migration_name  VARCHAR(200) NOT NULL UNIQUE,
    description     TEXT,
    migration_sql   TEXT,
    applied_on_dev  BOOLEAN NOT NULL DEFAULT TRUE,
    applied_on_prod BOOLEAN NOT NULL DEFAULT FALSE,
    applied_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    applied_by      VARCHAR(100),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Tenant Provisioning', '/admin/tenant-provisioning', 'DatabaseZap', 'ADMINISTRATION', 3)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin')
  AND m.path = '/admin/tenant-provisioning'
ON CONFLICT DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN')
  AND m.path = '/admin/tenant-provisioning'
ON CONFLICT DO NOTHING;

INSERT INTO tenant_provision_script (script_name, script_order, description, continue_on_error, created_by, script_sql)
VALUES (
  'default-team-lead', 10,
  'Create the default team lead for auto-assigning unmapped sales users',
  FALSE, 'system',
  'INSERT INTO sales_team_mapping (tenant_id, team_lead_name, team_lead_email, is_default) VALUES (${TENANT_ID}, ''Default Team Lead'', ''default-lead@acquira.com'', true) ON CONFLICT (tenant_id, team_lead_email) DO UPDATE SET is_default = true'
)
ON CONFLICT (script_name) DO NOTHING;

INSERT INTO tenant_provision_script (script_name, script_order, description, continue_on_error, created_by, script_sql)
VALUES (
  'default-country-lead', 20,
  'Create the default country lead (team leads with no country lead roll up here)',
  TRUE, 'system',
  'INSERT INTO sales_country_lead (tenant_id, country_lead_name, country_lead_email, is_default) VALUES (${TENANT_ID}, ''Default Country Lead'', ''default-country-lead@acquira.com'', true) ON CONFLICT (tenant_id, country_lead_email) DO NOTHING'
)
ON CONFLICT (script_name) DO NOTHING;

INSERT INTO tenant_provision_script (script_name, script_order, description, continue_on_error, created_by, script_sql)
VALUES (
  'tenant-settings-defaults', 30,
  'Per-tenant default settings: SSO toggle/keys, password policy, lockout',
  FALSE, 'system',
  'INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type) VALUES (${TENANT_ID}, ''sso_enabled'', ''false'', ''BOOLEAN''), (${TENANT_ID}, ''sso_provider'', ''MICROSOFT'', ''STRING''), (${TENANT_ID}, ''sso_client_id'', '''', ''STRING''), (${TENANT_ID}, ''sso_tenant_id'', '''', ''STRING''), (${TENANT_ID}, ''sso_client_secret'', '''', ''STRING''), (${TENANT_ID}, ''password_history_count'', ''5'', ''NUMBER''), (${TENANT_ID}, ''password_min_length'', ''8'', ''NUMBER''), (${TENANT_ID}, ''max_failed_logins'', ''5'', ''NUMBER''), (${TENANT_ID}, ''lockout_duration_minutes'', ''15'', ''NUMBER''), (${TENANT_ID}, ''password_reset_token_expiry_hours'', ''1'', ''NUMBER'') ON CONFLICT (tenant_id, setting_key) DO NOTHING'
)
ON CONFLICT (script_name) DO NOTHING;

INSERT INTO tenant_provision_script (script_name, script_order, description, continue_on_error, created_by, script_sql)
VALUES (
  'default-email-templates', 40,
  'Copy the default email templates (Statement/Welcome/Alert) from the earliest tenant',
  TRUE, 'system',
  'INSERT INTO email_template_config (tenant_id, name, template_type, subject_template, body_html, is_active, is_default_for_type) SELECT ${TENANT_ID}, name, template_type, subject_template, body_html, is_active, is_default_for_type FROM email_template_config WHERE tenant_id = (SELECT MIN(tenant_id) FROM tenant) AND ${TENANT_ID} <> (SELECT MIN(tenant_id) FROM tenant) ON CONFLICT DO NOTHING'
)
ON CONFLICT (script_name) DO NOTHING;

INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by) VALUES
('schema.sql',                                                    'Base schema (full monolith script)', TRUE, TRUE, 'backfill'),
('schema_extras.sql',                                             'Merged extras on top of schema.sql', TRUE, TRUE, 'backfill'),
('V2026_02_28_01__new_screens_security_alerts_api.sql',           'alert_rule/alert_history/api_key base tables + menus', TRUE, TRUE, 'backfill'),
('V2026_05_07_01__performance_indexes.sql',                       'Batch-ingest performance index set', TRUE, TRUE, 'backfill'),
('V2026_06_25_01__ref_country_missing_currencies.sql',            'ref_country currency gap fill', TRUE, TRUE, 'backfill'),
('V2026_06_25_02__ref_card_scheme_upi_jcb.sql',                   'UPI/JCB card schemes', TRUE, TRUE, 'backfill'),
('V2026_06_26_01__db_maintenance.sql',                            'DB maintenance job config', TRUE, TRUE, 'backfill'),
('V2026_06_26_02__db_maintenance_menu.sql',                       'DB maintenance menu row', TRUE, TRUE, 'backfill'),
('V2026_06_27_02__explorer_master_alert.sql',                     'Explorer master items + threshold alerts', TRUE, TRUE, 'backfill'),
('V2026_07_02_01__budget_targets_menu.sql',                       'Budget targets menu row', TRUE, TRUE, 'backfill'),
('V2026_07_04_01__api_management_foundation.sql',                 'api_key extensions', TRUE, TRUE, 'backfill'),
('V2026_07_04_02__user_account_expiry.sql',                       'users.account_expires_at', TRUE, TRUE, 'backfill'),
('V2026_07_05_01__interchange_scheme_fees.sql',                   'Interchange + scheme fee rate tables', TRUE, TRUE, 'backfill'),
('V2026_07_05_02__sum_daily_terminal_fees_ceo_menu.sql',          'sum_daily_terminal fee columns + CEO menu', TRUE, TRUE, 'backfill'),
('V2026_07_05_03__bank_base_volume.sql',                          'sum_daily_bank base volume', TRUE, TRUE, 'backfill'),
('V2026_07_05_04__loss_making_menu.sql',                          'Loss-making menu row', TRUE, TRUE, 'backfill'),
('V2026_07_07_01__mcc_rate_card_uae.sql',                         'UAE MCC interchange rate card', TRUE, TRUE, 'backfill'),
('V2026_07_07_03__fact_card_product_code.sql',                    'card_product_code on stg/fact', TRUE, TRUE, 'backfill'),
('V2026_07_07_04__intl_debit_interchange.sql',                    'International debit interchange rates', TRUE, TRUE, 'backfill'),
('V2026_07_07_05__domestic_pos_scheme_fee.sql',                   'Domestic POS scheme fee rows', TRUE, TRUE, 'backfill'),
('V2026_07_10_01__ref_mcc_category.sql',                          'ref_mcc_category', TRUE, TRUE, 'backfill'),
('V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql','Menu re-shuffle', TRUE, TRUE, 'backfill'),
('V2026_07_10_03__sum_daily_merchant_destination.sql',            'sum_daily_merchant_destination (dom/intl split)', TRUE, TRUE, 'backfill'),
('V2026_07_10_04__email_queue_missing_columns.sql',               'email_queue missing columns', TRUE, TRUE, 'backfill'),
('V2026_07_10_05__sales_menu.sql',                                'Sales screens menu rows', TRUE, TRUE, 'backfill'),
('V2026_07_11_01__password_reset_otp.sql',                        'Password reset OTP', TRUE, TRUE, 'backfill'),
('V2026_07_11_02__settings_hub_menu.sql',                         'Settings hub menu row', TRUE, TRUE, 'backfill')
ON CONFLICT (migration_name) DO NOTHING;

INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_07_11_03__tenant_provisioning.sql',
        'Tenant provisioning script registry + schema migration registry + seed scripts + menu',
        TRUE, FALSE, 'system')
ON CONFLICT (migration_name) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_11_03__tenant_provisioning.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 27/31: V2026_07_11_04__sales_country_lead_reconcile.sql
-- ############################################################################
CREATE TABLE IF NOT EXISTS sales_country_lead (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT NOT NULL,
    country_lead_name  VARCHAR(255) NOT NULL,
    country_lead_email VARCHAR(255) NOT NULL,
    country_code       VARCHAR(2),
    is_default         BOOLEAN DEFAULT FALSE,
    created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sales_country_lead_tenant ON sales_country_lead(tenant_id);

DELETE FROM sales_country_lead a
USING sales_country_lead b
WHERE a.tenant_id = b.tenant_id
  AND a.country_lead_email = b.country_lead_email
  AND a.id > b.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sales_country_lead_tenant_email
    ON sales_country_lead(tenant_id, country_lead_email);

INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_07_11_04__sales_country_lead_reconcile.sql',
        'sales_country_lead: canonical DDL + dedupe + unique index (fixes ON CONFLICT / provisioning script failure)',
        TRUE, FALSE, 'system')
ON CONFLICT (migration_name) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_11_04__sales_country_lead_reconcile.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 28/31: V2026_07_11_05__sales_team_mapping_country_lead_id.sql
-- ############################################################################
ALTER TABLE sales_team_mapping
    ADD COLUMN IF NOT EXISTS country_lead_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_sales_team_country_lead
    ON sales_team_mapping(country_lead_id)
    WHERE country_lead_id IS NOT NULL;

INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_07_11_05__sales_team_mapping_country_lead_id.sql',
        'sales_team_mapping: add country_lead_id column + partial index (fixes findAllByTenantId + country leaderboard column-does-not-exist errors)',
        TRUE, FALSE, 'system')
ON CONFLICT (migration_name) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_11_05__sales_team_mapping_country_lead_id.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 29/31: V2026_07_12_01__tenant_partition_provision_script.sql
-- ############################################################################
INSERT INTO tenant_provision_script (script_name, script_order, description, continue_on_error, created_by, script_sql)
VALUES (
  'tenant-partitions', 5,
  'Create the tenant''s physical partition set (fact_transaction + 9 summary tables, current + next year). Requires REBUILD_TENANT_LIST_PARTITIONING.sql to have been applied; fails harmlessly before that.',
  TRUE, 'system',
  'SELECT ensure_tenant_partitions(${TENANT_ID})'
)
ON CONFLICT (script_name) DO NOTHING;

INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_07_12_01__tenant_partition_provision_script.sql',
        'Provisioning hook: per-tenant partition creation on tenant create (order 5, before seeds)',
        TRUE, FALSE, 'system')
ON CONFLICT (migration_name) DO NOTHING;

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_12_01__tenant_partition_provision_script.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 30/31: V2026_07_12_02__sum_daily_full.sql
-- ############################################################################
CREATE TABLE IF NOT EXISTS sum_daily_full (
    summary_id        BIGSERIAL,
    tenant_id         INT NOT NULL,
    business_date     DATE NOT NULL,

    merchant_id       BIGINT,
    store_id          BIGINT,
    mcc               VARCHAR(10),
    channel           VARCHAR(50),
    destination       VARCHAR(50),
    card_scheme       VARCHAR(50),
    card_type         VARCHAR(50),
    is_opt_in         BOOLEAN,

    total_txns        BIGINT DEFAULT 0,
    total_volume      DECIMAL(19, 2) DEFAULT 0,
    total_msf         DECIMAL(21, 4) DEFAULT 0,
    total_interchange DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee  DECIMAL(19, 2) DEFAULT 0,
    total_ecom_fee    DECIMAL(19, 2) DEFAULT 0,
    total_net_revenue DECIMAL(19, 2) DEFAULT 0,
    dcc_optin_count   BIGINT DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, store_id, mcc, channel,
            destination, card_scheme, card_type, is_opt_in)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_full_y2024
    PARTITION OF sum_daily_full FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_full_y2025
    PARTITION OF sum_daily_full FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_full_y2026
    PARTITION OF sum_daily_full FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_full_y2027
    PARTITION OF sum_daily_full FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_full_default
    PARTITION OF sum_daily_full DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_daily_full_tenant_date
    ON sum_daily_full (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_daily_full_tenant_date_merch
    ON sum_daily_full (tenant_id, business_date, merchant_id);

ALTER TABLE sum_daily_full ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_full;
CREATE POLICY tenant_isolation_policy ON sum_daily_full
    USING (tenant_id = get_current_tenant());

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_12_02__sum_daily_full.sql') ON CONFLICT (filename) DO NOTHING;


-- ############################################################################
-- FILE 31/31: V2026_07_13_01__sum_daily_explorer.sql
-- ############################################################################
CREATE TABLE IF NOT EXISTS sum_daily_explorer (
    summary_id                BIGSERIAL,
    tenant_id                 INT NOT NULL,
    business_date             DATE NOT NULL,

    merchant_id               BIGINT,
    store_id                  BIGINT,
    terminal_id               BIGINT,
    transaction_type          VARCHAR(50),
    card_scheme               VARCHAR(50),
    card_type                 VARCHAR(50),
    destination                VARCHAR(50),
    channel                    VARCHAR(50),
    txn_currency               VARCHAR(10),
    store_base_currency        VARCHAR(10),
    is_opt_in                  BOOLEAN,

    total_txns                BIGINT DEFAULT 0,
    total_txn_currency_amount DECIMAL(19, 2) DEFAULT 0,
    total_base_volume         DECIMAL(19, 2) DEFAULT 0,
    total_msf                 DECIMAL(21, 4) DEFAULT 0,
    total_vat                 DECIMAL(19, 2) DEFAULT 0,
    total_settled              DECIMAL(19, 2) DEFAULT 0,
    total_interchange          DECIMAL(19, 2) DEFAULT 0,
    total_scheme_fee           DECIMAL(19, 2) DEFAULT 0,

    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id,
            transaction_type, card_scheme, card_type, destination, channel,
            txn_currency, is_opt_in)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_explorer_y2024
    PARTITION OF sum_daily_explorer FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_explorer_y2025
    PARTITION OF sum_daily_explorer FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_explorer_y2026
    PARTITION OF sum_daily_explorer FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_explorer_y2027
    PARTITION OF sum_daily_explorer FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_explorer_default
    PARTITION OF sum_daily_explorer DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_daily_explorer_tenant_date
    ON sum_daily_explorer (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_daily_explorer_tenant_date_merch
    ON sum_daily_explorer (tenant_id, business_date, merchant_id);

ALTER TABLE sum_daily_explorer ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_explorer;
CREATE POLICY tenant_isolation_policy ON sum_daily_explorer
    USING (tenant_id = get_current_tenant());

INSERT INTO schema_migration_log (filename) VALUES ('V2026_07_13_01__sum_daily_explorer.sql') ON CONFLICT (filename) DO NOTHING;


-- ============================================================================
-- END OF BUNDLE — schema_extras.sql + 31/31 migration files applied.
-- ============================================================================
