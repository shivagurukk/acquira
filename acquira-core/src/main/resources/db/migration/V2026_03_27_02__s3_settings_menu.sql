-- ============================================================
-- V2026_03_27_02: Add S3 Report Storage settings menu entry
-- Accessible by Super Admin and Admin groups
-- ============================================================

-- 1. Insert menu item
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('S3 Report Storage', '/admin/s3-settings', 'Cloud', 'ADMINISTRATION', 15)
ON CONFLICT (path) DO NOTHING;

-- 2. Grant access to Super Admin group
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
  AND m.path = '/admin/s3-settings'
ON CONFLICT DO NOTHING;

-- 3. Grant access to Admin group (if it exists)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Admin'
  AND m.path = '/admin/s3-settings'
ON CONFLICT DO NOTHING;
