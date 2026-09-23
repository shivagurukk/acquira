-- ============================================================================
-- V2026_08_20_02: Local Debit Bank Dashboard menu entry.
--
-- /business/local-debit-bank-dashboard — BIN-wise local debit issuing-bank
-- split (txn count + volume, merchant drill-down), backed by
-- LocalDebitBankDashboardController over sum_daily_local_debit_bin with bank
-- names resolved from the tenant-uploaded ref_tenant_bin_bank list.
-- BUSINESS category, display_order 20 (next free after Card Type Dashboard's
-- 19 from V2026_08_19_03).
--
-- The API is gated by @menuAccess.canAccess('/business/local-debit-bank-dashboard'),
-- so this grant IS the access control, not just sidebar visibility.
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

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
