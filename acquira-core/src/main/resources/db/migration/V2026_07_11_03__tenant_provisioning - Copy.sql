-- ============================================================================
-- V2026_07_11_03: Tenant provisioning script registry + schema migration
-- registry.
--
-- 1. tenant_provision_script  — super-admin-managed SQL scripts executed
--    automatically (in script_order) whenever a new tenant is created, and
--    re-runnable per tenant. Scripts may contain the placeholders
--    ${TENANT_ID} ${INSTITUTION_ID} ${BANK_SHORT_CODE} ${BASE_CURRENCY}
--    ${BANK_NAME} — substituted server-side from the tenant row (never user
--    free-text). Every script MUST be idempotent (ON CONFLICT / IF NOT EXISTS)
--    so re-running "Provision now" is always safe.
-- 2. tenant_provision_log     — one row per script execution per tenant.
-- 3. schema_migration_registry — self-registering catalog of every schema
--    migration (file-first stays the landing mechanism; this table is the
--    visibility layer: what exists, what's applied where). Each new migration
--    from now on appends its own registry INSERT at the bottom of its file.
--
-- Splitter-safe (no DO $$ blocks). Idempotent. On prod (mode=never) apply
-- once via psql.
-- ============================================================================

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

-- ── Menu entry: Tenant Provisioning (SUPER_ADMIN only) ─────────────────────
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

-- ── Seed provisioning scripts (ported from the schema.sql per-tenant seed
--    blocks; each idempotent; ${TENANT_ID} substituted at run time) ─────────

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

-- ── Backfill the migration registry with every migration currently listed in
--    schema-locations (names only; SQL text lives in the files) ─────────────
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

-- Self-register this migration (the pattern every future migration follows).
INSERT INTO schema_migration_registry (migration_name, description, applied_on_dev, applied_on_prod, applied_by)
VALUES ('V2026_07_11_03__tenant_provisioning.sql',
        'Tenant provisioning script registry + schema migration registry + seed scripts + menu',
        TRUE, FALSE, 'system')
ON CONFLICT (migration_name) DO NOTHING;
