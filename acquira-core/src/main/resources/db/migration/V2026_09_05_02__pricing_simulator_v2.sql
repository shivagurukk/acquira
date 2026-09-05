-- ============================================================================
-- V2026_09_05_02: Pricing Simulator v2 — segment margin matrix.
--
-- Backend reads sum_daily_full (scheme × card_type × destination with the
-- real fee stack) — NO new data tables. This migration only:
--   1. registers the sidebar/menu row for /business/pricing-simulator
--      (the route + page have existed since v1, but no sys_menu row was ever
--      seeded, so the @menuAccess gate on the new controller needs one),
--   2. grants it to Super Admin + Bank Admin,
--   3. seeds the per-tenant enable flag pricing.simulator_enabled (default
--      'true' — the screen predates the flag, so existing tenants keep it;
--      an admin switches a tenant off in Settings → Regional & Data).
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- 1. Menu row -----------------------------------------------------------------
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
SELECT 'Pricing Simulator', '/business/pricing-simulator', 'SlidersHorizontal', 'BUSINESS', 19
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/business/pricing-simulator');

-- 2. Grants: Super Admin + Bank Admin ----------------------------------------
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g
CROSS JOIN sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin')
  AND m.path = '/business/pricing-simulator'
ON CONFLICT DO NOTHING;

-- 3. Per-tenant calculation flag ---------------------------------------------
INSERT INTO tenant_setting (tenant_id, setting_key, setting_value, setting_type)
SELECT t.tenant_id, 'pricing.simulator_enabled', 'true', 'BOOLEAN'
FROM tenant t
ON CONFLICT (tenant_id, setting_key) DO NOTHING;
