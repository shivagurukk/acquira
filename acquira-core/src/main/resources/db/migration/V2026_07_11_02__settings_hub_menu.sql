-- ============================================================================
-- V2026_07_11_02: Unified Settings hub menu entry.
--
-- Registers the single "Settings" sidebar item that opens the consolidated
-- settings hub (/settings). The hub embeds the existing settings pages as
-- panels; those pages keep their own routes, so this migration ONLY adds the
-- new entry point and does not remove anything (reversible, low risk).
--
-- Access: Bank Admin gets everything and Super Admin can also view — so the
-- item is granted to BOTH admin groups (and the legacy uppercase variants),
-- with NO super-admin-only gating, matching the App.jsx RoleGuard.
--
-- Idempotent (ON CONFLICT DO NOTHING). Splitter-safe (no DO $$ blocks). On
-- prod (spring.sql.init.mode=never) apply once via psql. Mirrored by
-- MenuController.ensureMenusExist() as a startup safety net if listed there.
-- ============================================================================

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('Settings', '/settings', 'Settings', 'ADMINISTRATION', 1)
ON CONFLICT (path) DO NOTHING;

-- Grant to the seeded admin groups (title-case) ...
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('Super Admin', 'Bank Admin', 'Admin')
  AND m.path = '/settings'
ON CONFLICT DO NOTHING;

-- ... and to any uppercase-named admin groups (defensive, mirrors other
-- migrations that hedge against both naming styles).
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name IN ('SUPER_ADMIN', 'ADMIN')
  AND m.path = '/settings'
ON CONFLICT DO NOTHING;
