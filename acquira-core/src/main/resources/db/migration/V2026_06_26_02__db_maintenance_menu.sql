-- ============================================================
-- V2026_06_26_02: Add Database Maintenance menu entry
-- Accessible by Super Admin and Admin groups
-- ============================================================

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
