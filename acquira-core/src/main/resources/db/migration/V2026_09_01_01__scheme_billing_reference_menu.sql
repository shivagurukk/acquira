-- ============================================================================
-- V2026_09_01_01: Scheme Billing Reference menu entry.
--
-- /ops/scheme-billing-reference — read-only, fully static reference of the
-- 74 acquirer-relevant Mastercard Consolidated Billing System (MCBS) report
-- and invoice-file specifications (T0CH/BFIL, TN3A, T0CF, GB/AB reports),
-- extracted offline from the 2 June 2026 DITA-XML manual into
-- frontend/src/data/mcbsAcquirerReports.json. No backend endpoint — the
-- page ships its data in the frontend bundle, so this grant only controls
-- sidebar visibility (RoleGuard on the route gates access).
--
-- OPERATIONS category, display_order 6 (after Ingest Trust's 5).
--
-- Granted to Super Admin + Bank Admin (sidebar is DB-driven).
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Scheme Billing Reference', '/ops/scheme-billing-reference', 'BookOpen', 'OPERATIONS', 6
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/ops/scheme-billing-reference');

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/ops/scheme-billing-reference'
  AND g.group_name IN ('Super Admin', 'Bank Admin')
ON CONFLICT (group_id, menu_id) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE m.path = '/ops/scheme-billing-reference'
  AND g.group_name IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (group_id, menu_id) DO NOTHING;
