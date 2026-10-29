-- ============================================================================
-- rbac_resync_new_tenant_2026-10-27.sql
--
-- Seeds the per-tenant RBAC rows for tenants created AFTER the application last
-- started (symptom: a new tenant, e.g. Egypt, whose users see an empty sidebar
-- and 403s while the same group works on every other tenant).
--
-- Root cause: tenant_module / tenant_group / tenant_group_perm were only
-- populated by RbacCatalogSync at startup (CROSS JOIN tenant), and group edits
-- in Admin > RBAC only wrote the legacy sys_group_menu. The code fix makes
-- tenant creation and group edits re-run this sync; this script is the
-- equivalent for an environment that already has the orphaned tenant.
--
-- Idempotent and additive (ON CONFLICT DO NOTHING everywhere): a DENY a tenant
-- admin set explicitly is never overwritten. Safe to run on any environment.
--
--   psql -h <host> -p <port> -U postgres -d postgres -f rbac_resync_new_tenant_2026-10-27.sql
-- ============================================================================

\echo '=== Before: tenants and their RBAC row counts ==='
SELECT t.tenant_id, t.bank_name,
       (SELECT COUNT(*) FROM tenant_module     x WHERE x.tenant_id = t.tenant_id) AS modules,
       (SELECT COUNT(*) FROM tenant_group      x WHERE x.tenant_id = t.tenant_id) AS groups,
       (SELECT COUNT(*) FROM tenant_group_perm x WHERE x.tenant_id = t.tenant_id) AS grants
FROM tenant t ORDER BY t.tenant_id;

-- 1. Every module enabled for every tenant (entitlement is an admin decision,
--    taken afterwards in the UI, same default as RbacCatalogSync).
INSERT INTO tenant_module (tenant_id, module_key, enabled, updated_by)
SELECT t.tenant_id, m.module_key, TRUE, 'rbac_resync_2026-10-27'
FROM tenant t
CROSS JOIN (SELECT DISTINCT module_key FROM sys_menu WHERE module_key IS NOT NULL) m
ON CONFLICT (tenant_id, module_key) DO NOTHING;

-- 2. Every system-wide group bound to every tenant.
INSERT INTO tenant_group (tenant_id, group_id, is_system, created_by)
SELECT t.tenant_id, g.group_id, g.is_system, 'rbac_resync_2026-10-27'
FROM tenant t
CROSS JOIN sys_user_group g
WHERE g.owner_tenant_id IS NULL
ON CONFLICT (tenant_id, group_id) DO NOTHING;

-- 3. Mirror the legacy group->menu grants into per-tenant VIEW grants.
INSERT INTO tenant_group_perm (tenant_id, group_id, perm_code, effect, granted_by)
SELECT t.tenant_id, gm.group_id, m.menu_key || ':VIEW', 'ALLOW', 'rbac_resync_2026-10-27'
FROM tenant t
CROSS JOIN sys_group_menu gm
JOIN sys_menu m ON m.menu_id = gm.menu_id
WHERE m.menu_key IS NOT NULL
  AND EXISTS (SELECT 1 FROM sys_permission p WHERE p.perm_code = m.menu_key || ':VIEW')
ON CONFLICT (tenant_id, group_id, perm_code) DO NOTHING;

-- 4. EDIT / EXPORT follow VIEW for the two admin groups (same rule as
--    V2026_09_26_02 backfill 2), so a new tenant's Bank Admin is not read-only.
INSERT INTO tenant_group_perm (tenant_id, group_id, perm_code, effect, granted_by)
SELECT v.tenant_id, v.group_id, p.perm_code, 'ALLOW', 'rbac_resync_2026-10-27'
FROM tenant_group_perm v
JOIN sys_permission pv ON pv.perm_code = v.perm_code AND pv.action = 'VIEW'
JOIN sys_permission p  ON p.menu_key   = pv.menu_key AND p.action IN ('EDIT','EXPORT')
JOIN sys_user_group g  ON g.group_id   = v.group_id
WHERE v.effect = 'ALLOW'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (tenant_id, group_id, perm_code) DO NOTHING;

-- 5. Bump the cache stamp so running evaluators drop their cached sets.
INSERT INTO rbac_version (tenant_id, version, updated_at)
SELECT t.tenant_id, 1, CURRENT_TIMESTAMP FROM tenant t
ON CONFLICT (tenant_id) DO UPDATE SET version = rbac_version.version + 1, updated_at = CURRENT_TIMESTAMP;
UPDATE rbac_version SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = 0;

\echo '=== After ==='
SELECT t.tenant_id, t.bank_name,
       (SELECT COUNT(*) FROM tenant_module     x WHERE x.tenant_id = t.tenant_id) AS modules,
       (SELECT COUNT(*) FROM tenant_group      x WHERE x.tenant_id = t.tenant_id) AS groups,
       (SELECT COUNT(*) FROM tenant_group_perm x WHERE x.tenant_id = t.tenant_id) AS grants
FROM tenant t ORDER BY t.tenant_id;
