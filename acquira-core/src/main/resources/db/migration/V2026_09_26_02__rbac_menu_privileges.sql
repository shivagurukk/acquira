-- ============================================================================
-- V2026_09_26_02: RBAC / menu privileges — per-tenant grants with an action
-- dimension.  Steps 1-3 of docs/PLAN_RBAC_MENU_PRIVILEGES_2026-09-26.md.
--
-- WHY: sys_group_menu(group_id, menu_id) has no tenant column, so a group
-- means the same thing in every tenant; the grant is binary, so "can see the
-- settlement batch" is the same bit as "can release the payment file"; and
-- screen identity is the URL, so renaming a route silently revokes access.
--
-- WHAT:
--   sys_menu           + menu_key (stable identity), module_key, parent_id
--   sys_permission       catalog of <menu_key>:<ACTION> codes
--   tenant_module        entitlement gate — a module off here hides every
--                        screen under it regardless of grants
--   tenant_group         which groups a tenant exposes (+ tenant-local clones)
--   tenant_group_perm    the actual grant, per tenant, ALLOW or DENY
--   rbac_version         cache stamp, bumped on every RBAC write
--   v_user_permission    the ONE resolution rule, in SQL
--
-- Backfill is behaviour-identical: every existing sys_group_menu row becomes
-- a <menu_key>:VIEW ALLOW for every tenant, and every module is enabled for
-- every tenant.  Nobody gains or loses a screen on apply.
--
-- sys_group_menu is NOT dropped here — it stays as the fallback read path
-- until the evaluator cutover is verified in UAT (step 4 of the plan).
--
-- Idempotent; splitter-safe (no dollar-quoting).  On prod apply once via psql.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Catalog: stable screen identity + module + hierarchy
-- ---------------------------------------------------------------------------
ALTER TABLE sys_menu ADD COLUMN IF NOT EXISTS menu_key   VARCHAR(60);
ALTER TABLE sys_menu ADD COLUMN IF NOT EXISTS module_key VARCHAR(40);
ALTER TABLE sys_menu ADD COLUMN IF NOT EXISTS parent_id  BIGINT REFERENCES sys_menu(menu_id);
ALTER TABLE sys_menu ADD COLUMN IF NOT EXISTS is_active  BOOLEAN NOT NULL DEFAULT TRUE;

-- menu_key is derived from path ONCE and then never follows it again:
--   '/business/loss-making' -> 'business.loss-making'
--   '/dashboard'            -> 'dashboard'
-- path is UNIQUE, so the derivation is unique too.
UPDATE sys_menu
   SET menu_key = REPLACE(LTRIM(path, '/'), '/', '.')
 WHERE menu_key IS NULL
   AND path IS NOT NULL
   AND path <> '';

-- Any row without a usable path gets a synthetic key so the UNIQUE index
-- below can always be created.
UPDATE sys_menu
   SET menu_key = 'menu.' || menu_id
 WHERE menu_key IS NULL OR menu_key = '';

CREATE UNIQUE INDEX IF NOT EXISTS uq_sys_menu_key ON sys_menu (menu_key);

-- module_key = the sellable / entitlement unit.  Category is already the
-- product's own grouping, so use it verbatim rather than inventing a second
-- taxonomy that would immediately drift from the sidebar.
UPDATE sys_menu
   SET module_key = LOWER(REPLACE(COALESCE(NULLIF(category, ''), 'CORE'), ' ', '_'))
 WHERE module_key IS NULL;

CREATE INDEX IF NOT EXISTS idx_sys_menu_module ON sys_menu (module_key);

-- ---------------------------------------------------------------------------
-- 2. Groups: system templates vs tenant-local clones
-- ---------------------------------------------------------------------------
ALTER TABLE sys_user_group ADD COLUMN IF NOT EXISTS is_system       BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sys_user_group ADD COLUMN IF NOT EXISTS owner_tenant_id INT REFERENCES tenant(tenant_id) ON DELETE CASCADE;
ALTER TABLE sys_user_group ADD COLUMN IF NOT EXISTS created_by      VARCHAR(100);
ALTER TABLE sys_user_group ADD COLUMN IF NOT EXISTS created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- group_name was globally UNIQUE, which makes a tenant-local clone named
-- "Finance User" impossible.  Scope uniqueness to the owning tenant instead
-- (owner_tenant_id NULL = a shipped, system-wide template).
ALTER TABLE sys_user_group DROP CONSTRAINT IF EXISTS sys_user_group_group_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_sys_user_group_scope
    ON sys_user_group (COALESCE(owner_tenant_id, 0), group_name);

UPDATE sys_user_group
   SET is_system = TRUE
 WHERE owner_tenant_id IS NULL
   AND group_name IN ('Super Admin', 'Bank Admin', 'Business User', 'Finance User', 'Ops User',
                      'SUPER_ADMIN', 'ADMIN', 'USER');

-- ---------------------------------------------------------------------------
-- 3. Permission catalog — <menu_key>:<ACTION>
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_permission (
    perm_code   VARCHAR(80)  PRIMARY KEY,           -- 'sales.agents:EDIT'
    menu_key    VARCHAR(60)  NOT NULL,
    action      VARCHAR(10)  NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_sys_permission_action CHECK (action IN ('VIEW','EDIT','APPROVE','EXPORT'))
);
CREATE INDEX IF NOT EXISTS idx_sys_permission_menu ON sys_permission (menu_key);

-- Every screen gets a VIEW permission, always.
INSERT INTO sys_permission (perm_code, menu_key, action, description)
SELECT m.menu_key || ':VIEW', m.menu_key, 'VIEW', 'Open ' || m.menu_name
FROM sys_menu m
WHERE m.menu_key IS NOT NULL
ON CONFLICT (perm_code) DO NOTHING;

-- EDIT exists only where a screen actually writes something.  Seeding EDIT
-- for read-only dashboards would make the matrix unreadable and teach admins
-- to tick boxes that mean nothing.
INSERT INTO sys_permission (perm_code, menu_key, action, description)
SELECT m.menu_key || ':EDIT', m.menu_key, 'EDIT', 'Modify data on ' || m.menu_name
FROM sys_menu m
WHERE m.menu_key IS NOT NULL
  AND (m.category IN ('ADMINISTRATION','OPERATIONS','DATA INTEGRATION','SALES','MERCHANT MGT')
       OR m.path IN ('/business/pricing-simulator','/business/budget-targets'))
ON CONFLICT (perm_code) DO NOTHING;

-- APPROVE is the four-eyes bit: money movement and pricing sign-off.  No
-- screen in this product needs it yet: the pricing / fee-plan screens that did
-- moved to Ledgerline, the acquiring back office, so the action
-- exists in the CHECK constraint and the resolution view and is seeded per
-- screen when a four-eyes screen lands here.

-- EXPORT is separated because "can look at merchant P&L" and "can walk out
-- with the merchant P&L as a file" are different risks.
INSERT INTO sys_permission (perm_code, menu_key, action, description)
SELECT m.menu_key || ':EXPORT', m.menu_key, 'EXPORT', 'Download data from ' || m.menu_name
FROM sys_menu m
WHERE m.menu_key IS NOT NULL
  AND m.category IN ('EXECUTIVE','BUSINESS','MERCHANT MGT')
ON CONFLICT (perm_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Entitlement gate — what this tenant has bought
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant_module (
    tenant_id  INT         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    module_key VARCHAR(40) NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    note       VARCHAR(255),
    updated_by VARCHAR(100),
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, module_key)
);

-- Behaviour-identical backfill: everything every tenant can see today stays on.
INSERT INTO tenant_module (tenant_id, module_key, enabled, updated_by)
SELECT t.tenant_id, m.module_key, TRUE, 'V2026_09_26_02'
FROM tenant t
CROSS JOIN (SELECT DISTINCT module_key FROM sys_menu WHERE module_key IS NOT NULL) m
ON CONFLICT (tenant_id, module_key) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. Tenant bindings — which groups a tenant exposes
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant_group (
    tenant_group_id BIGSERIAL PRIMARY KEY,
    tenant_id       INT     NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    group_id        BIGINT  NOT NULL REFERENCES sys_user_group(group_id) ON DELETE CASCADE,
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,   -- shipped template, tenant cannot edit
    cloned_from     BIGINT  REFERENCES sys_user_group(group_id),
    description     VARCHAR(255),
    created_by      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(100),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_tenant_group UNIQUE (tenant_id, group_id)
);
CREATE INDEX IF NOT EXISTS idx_tenant_group_tenant ON tenant_group (tenant_id);

INSERT INTO tenant_group (tenant_id, group_id, is_system, created_by)
SELECT t.tenant_id, g.group_id, g.is_system, 'V2026_09_26_02'
FROM tenant t
CROSS JOIN sys_user_group g
WHERE g.owner_tenant_id IS NULL
ON CONFLICT (tenant_id, group_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. The grant itself
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant_group_perm (
    tenant_id  INT         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    group_id   BIGINT      NOT NULL REFERENCES sys_user_group(group_id) ON DELETE CASCADE,
    perm_code  VARCHAR(80) NOT NULL REFERENCES sys_permission(perm_code) ON DELETE CASCADE,
    effect     VARCHAR(5)  NOT NULL DEFAULT 'ALLOW',
    granted_by VARCHAR(100),
    granted_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, group_id, perm_code),
    CONSTRAINT chk_tenant_group_perm_effect CHECK (effect IN ('ALLOW','DENY'))
);
CREATE INDEX IF NOT EXISTS idx_tgp_lookup ON tenant_group_perm (tenant_id, group_id, effect);

-- Backfill 1: every existing menu grant becomes a VIEW grant in every tenant.
INSERT INTO tenant_group_perm (tenant_id, group_id, perm_code, effect, granted_by)
SELECT t.tenant_id, gm.group_id, m.menu_key || ':VIEW', 'ALLOW', 'V2026_09_26_02'
FROM tenant t
CROSS JOIN sys_group_menu gm
JOIN sys_menu m ON m.menu_id = gm.menu_id
WHERE m.menu_key IS NOT NULL
ON CONFLICT (tenant_id, group_id, perm_code) DO NOTHING;

-- Backfill 2: EDIT and EXPORT follow the VIEW grant for the two admin groups
-- only.  Everyone else lands read-only, which is the safe direction to be
-- wrong in — an over-restricted Finance User raises a ticket, an
-- over-permitted one moves money.
INSERT INTO tenant_group_perm (tenant_id, group_id, perm_code, effect, granted_by)
SELECT v.tenant_id, v.group_id, p.perm_code, 'ALLOW', 'V2026_09_26_02'
FROM tenant_group_perm v
JOIN sys_permission pv ON pv.perm_code = v.perm_code AND pv.action = 'VIEW'
JOIN sys_permission p  ON p.menu_key   = pv.menu_key AND p.action IN ('EDIT','EXPORT')
JOIN sys_user_group g  ON g.group_id   = v.group_id
WHERE v.effect = 'ALLOW'
  AND g.group_name IN ('Super Admin', 'Bank Admin', 'SUPER_ADMIN', 'ADMIN')
ON CONFLICT (tenant_id, group_id, perm_code) DO NOTHING;

-- Backfill 3: APPROVE goes to Super Admin only.  Maker-checker is meaningless
-- if the group that drafts is the group that approves.
INSERT INTO tenant_group_perm (tenant_id, group_id, perm_code, effect, granted_by)
SELECT t.tenant_id, g.group_id, p.perm_code, 'ALLOW', 'V2026_09_26_02'
FROM tenant t
CROSS JOIN sys_user_group g
CROSS JOIN sys_permission p
WHERE p.action = 'APPROVE'
  AND g.group_name IN ('Super Admin', 'SUPER_ADMIN')
  AND g.owner_tenant_id IS NULL
ON CONFLICT (tenant_id, group_id, perm_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 7. Cache stamp — bumped on every RBAC write, read by the evaluator's cache
--    key.  tenant_id 0 is the global row (catalog changes affect all
--    tenants), so no FK here.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rbac_version (
    tenant_id  INT       PRIMARY KEY,
    version    BIGINT    NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO rbac_version (tenant_id, version) VALUES (0, 1)
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO rbac_version (tenant_id, version)
SELECT t.tenant_id, 1 FROM tenant t
ON CONFLICT (tenant_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8. The resolution rule, written ONCE.
--
--    effective(user, tenant) =
--          perms of the user's group in that tenant
--        - DENY rows                      (deny always wins)
--        * perms whose module is enabled for the tenant
--        * perms whose screen is active
--
--    The ROLE_SUPER_ADMIN bypass lives in the evaluator, not here: this view
--    is also what the admin matrix reads, and a super-admin seeing
--    "everything" there would hide what the group actually grants.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_user_permission AS
SELECT uta.user_id,
       uta.tenant_id,
       uta.group_id,
       p.perm_code,
       p.menu_key,
       p.action,
       m.menu_id,
       m.module_key
FROM user_tenant_access uta
JOIN tenant_group_perm tgp
     ON tgp.tenant_id = uta.tenant_id
    AND tgp.group_id  = uta.group_id
    AND tgp.effect    = 'ALLOW'
JOIN sys_permission p ON p.perm_code  = tgp.perm_code
JOIN sys_menu       m ON m.menu_key   = p.menu_key AND m.is_active
JOIN tenant_module tm ON tm.tenant_id = uta.tenant_id
                     AND tm.module_key = m.module_key
                     AND tm.enabled
WHERE NOT EXISTS (
        SELECT 1 FROM tenant_group_perm d
         WHERE d.tenant_id = uta.tenant_id
           AND d.group_id  = uta.group_id
           AND d.perm_code = p.perm_code
           AND d.effect    = 'DENY');

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_26_02__rbac_menu_privileges.sql') ON CONFLICT (filename) DO NOTHING;
