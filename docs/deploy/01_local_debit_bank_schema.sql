-- ============================================================================
-- Local Debit Bank Dashboard — STEP 1 of 3: schema + menu.
--
-- Creates the tenant BIN->bank reference table, the domestic-debit daily
-- pre-aggregate, and the sidebar menu entry (which is also the API's access
-- gate). Safe to run on UAT and PROD.
--
-- RUN:
--   psql -h <host> -p <port> -U <user> -d <db> -v ON_ERROR_STOP=1 \
--        -f 01_local_debit_bank_schema.sql
--
-- Fully idempotent — re-running changes nothing. No dollar-quoting, so it is
-- also safe through Spring's sql.init splitter.
-- ============================================================================

-- ── Tenant BIN -> bank list (the ONLY source of bank names for this page) ──
CREATE TABLE IF NOT EXISTS ref_tenant_bin_bank (
    tenant_id   INT          NOT NULL,
    bin         VARCHAR(6)   NOT NULL,
    bank_name   VARCHAR(128) NOT NULL,
    source_file VARCHAR(256),
    loaded_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, bin)
);

ALTER TABLE ref_tenant_bin_bank ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON ref_tenant_bin_bank;
CREATE POLICY tenant_isolation_policy ON ref_tenant_bin_bank
    USING (tenant_id = get_current_tenant());

-- ── Daily pre-aggregate: DOMESTIC x DEBIT only, per merchant per 6-digit BIN ──
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin (
    summary_id    BIGSERIAL,
    tenant_id     INT NOT NULL,
    business_date DATE NOT NULL,
    merchant_id   BIGINT NOT NULL,
    bin6          VARCHAR(6) NOT NULL,
    total_txns    BIGINT DEFAULT 0,
    total_volume  DECIMAL(19, 2) DEFAULT 0,   -- settlement, signed (refunds net out)
    total_msf     DECIMAL(19, 2) DEFAULT 0,
    PRIMARY KEY (summary_id, business_date),
    UNIQUE (tenant_id, business_date, merchant_id, bin6)
) PARTITION BY RANGE (business_date);

CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2024
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2025
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2026
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_y2027
    PARTITION OF sum_daily_local_debit_bin FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS sum_daily_local_debit_bin_default
    PARTITION OF sum_daily_local_debit_bin DEFAULT;

CREATE INDEX IF NOT EXISTS idx_sum_daily_ldb_tenant_date
    ON sum_daily_local_debit_bin (tenant_id, business_date);
CREATE INDEX IF NOT EXISTS idx_sum_daily_ldb_tenant_date_bin
    ON sum_daily_local_debit_bin (tenant_id, business_date, bin6);

ALTER TABLE sum_daily_local_debit_bin ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_policy ON sum_daily_local_debit_bin;
CREATE POLICY tenant_isolation_policy ON sum_daily_local_debit_bin
    USING (tenant_id = get_current_tenant());

-- ── Menu entry (BUSINESS, order 20). This grant IS the API access control. ──
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Local Debit Banks', '/business/local-debit-bank-dashboard', 'Landmark', 'BUSINESS', 20
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/local-debit-bank-dashboard');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/local-debit-bank-dashboard'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/business/local-debit-bank-dashboard'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- ── Verify ──
SELECT 'ref_tenant_bin_bank'       AS object, to_regclass('ref_tenant_bin_bank')       IS NOT NULL AS created
UNION ALL
SELECT 'sum_daily_local_debit_bin', to_regclass('sum_daily_local_debit_bin') IS NOT NULL
UNION ALL
SELECT 'menu entry', EXISTS (SELECT 1 FROM sys_menu WHERE path='/business/local-debit-bank-dashboard');
