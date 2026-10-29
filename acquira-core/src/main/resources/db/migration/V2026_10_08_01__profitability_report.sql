-- ============================================================================
-- V2026_10_08_01: Profitability Report (finance) — menu, RBAC and the
-- adjustment-rate table.
--
-- /finance/profitability-report — the bank finance team's monthly MID P&L at
-- the grain  Month x MID x Merchant Type (channel) x MCC Category x
-- Destination x Scheme x Card Interchange, with the column set:
--
--   Count | Volume | MSF | IC | Net revenue (MSF - IC) | Scheme Fees |
--   MPGS Fees | PIE MSF Rebate/Recovery | KFCA Processing Fee |
--   KFCA Per Txn Margin | IC++ Billing | Net Margin
--
-- Count/Volume/MSF/IC/Scheme/MPGS come straight off fact_transaction (the
-- ONE fee-engine pass: interchange_fee, scheme_fee, ecom_fee). The other
-- four lines are NOT priced by the fee engine — they are contractual
-- processor / partner terms the finance team keys in. They live in
-- profitability_adjustment_rate below: a tenant-wide default row per code,
-- optionally overridden per MID, each with an effective window. The report
-- applies them per row as
--
--   amount = volume * pct_of_volume + msf * pct_of_msf + count * per_txn_amount
--
-- Sign convention (fixed in the report, not in the row): PIE rebate and
-- KFCA processing fee are COSTS (deducted); KFCA per-txn margin and IC++
-- billing are INCOME (added).
--
--   net_margin = net_revenue - scheme - mpgs - other_pg - pie - kfca_fee
--                + kfca_margin + icpp_billing
--
-- Menu: FINANCE category, display_order 4 (after Finance Lists). The API is
-- gated by @menuAccess.canAccess('/finance/profitability-report'); rate
-- maintenance by @perm.can('finance.profitability-report','EDIT').
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Menu row (legacy sidebar table + RBAC catalog columns)
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Profitability Report', '/finance/profitability-report', 'Calculator', 'FINANCE', 4
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/finance/profitability-report');

UPDATE sys_menu
   SET menu_key   = COALESCE(menu_key, 'finance.profitability-report'),
       module_key = COALESCE(module_key, 'finance'),
       is_active  = TRUE
 WHERE path = '/finance/profitability-report';

-- Legacy grant table (still the fallback read path until the evaluator
-- cutover is verified — see V2026_09_26_02).
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/finance/profitability-report'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. RBAC: permission catalog + tenant grants for the admin groups
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (perm_code, menu_key, action, description)
SELECT 'finance.profitability-report:VIEW',   'finance.profitability-report', 'VIEW',   'Open Profitability Report'
ON CONFLICT (perm_code) DO NOTHING;
INSERT INTO sys_permission (perm_code, menu_key, action, description)
SELECT 'finance.profitability-report:EXPORT', 'finance.profitability-report', 'EXPORT', 'Download data from Profitability Report'
ON CONFLICT (perm_code) DO NOTHING;
INSERT INTO sys_permission (perm_code, menu_key, action, description)
SELECT 'finance.profitability-report:EDIT',   'finance.profitability-report', 'EDIT',   'Maintain adjustment rates on Profitability Report'
ON CONFLICT (perm_code) DO NOTHING;

-- Module gate: every tenant already has 'finance' from the backfill, but a
-- tenant created after V2026_09_26_02 may not.
INSERT INTO tenant_module (tenant_id, module_key, enabled, updated_by)
SELECT t.tenant_id, 'finance', TRUE, 'V2026_10_08_01'
FROM tenant t
ON CONFLICT (tenant_id, module_key) DO NOTHING;

INSERT INTO tenant_group_perm (tenant_id, group_id, perm_code, effect, granted_by)
SELECT t.tenant_id, g.group_id, p.perm_code, 'ALLOW', 'V2026_10_08_01'
FROM tenant t
CROSS JOIN sys_user_group g
CROSS JOIN sys_permission p
WHERE p.menu_key = 'finance.profitability-report'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (tenant_id, group_id, perm_code) DO NOTHING;

-- Bump the RBAC cache stamp so running evaluators pick the new grant up.
UPDATE rbac_version SET version = version + 1;

-- ---------------------------------------------------------------------------
-- 3. Adjustment rates (finance-keyed; not fee-engine output)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS profitability_adjustment_rate (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       INT NOT NULL REFERENCES tenant(tenant_id),
    mid             VARCHAR(50),                      -- NULL = tenant-wide default
    adj_code        VARCHAR(30) NOT NULL,             -- see CHECK below
    pct_of_volume   DECIMAL(9, 6)  NOT NULL DEFAULT 0, -- 0.001000 = 0.10% of volume
    pct_of_msf      DECIMAL(9, 6)  NOT NULL DEFAULT 0, -- 0.100000 = 10% of MSF
    per_txn_amount  DECIMAL(19, 4) NOT NULL DEFAULT 0, -- settlement ccy per transaction
    effective_from  DATE NOT NULL DEFAULT DATE '2000-01-01',
    effective_to    DATE,                             -- NULL = open-ended
    note            VARCHAR(200),
    updated_by      VARCHAR(100),
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_profit_adj_code CHECK (adj_code IN
        ('PIE_MSF_REBATE', 'KFCA_PROCESSING_FEE', 'KFCA_PER_TXN_MARGIN', 'ICPP_BILLING'))
);

CREATE INDEX IF NOT EXISTS idx_profit_adj_lookup
    ON profitability_adjustment_rate (tenant_id, adj_code, mid);

ALTER TABLE profitability_adjustment_rate ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_profit_adj ON profitability_adjustment_rate;
CREATE POLICY tenant_isolation_profit_adj ON profitability_adjustment_rate
    USING (tenant_id = get_current_tenant());

INSERT INTO schema_migration_log (filename) VALUES ('V2026_10_08_01__profitability_report.sql') ON CONFLICT (filename) DO NOTHING;
