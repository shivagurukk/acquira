-- ============================================================
-- FIX: Add S3 Report Storage to sidebar for all admin groups
-- Run this directly in psql or pgAdmin to fix the missing menu.
--
-- psql command:
--   psql -h 127.0.0.1 -p 5433 -U postgres -d postgres -f fix_s3_menu.sql
-- ============================================================

-- Step 1: Insert the menu row (safe — ON CONFLICT skips if exists)
INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order)
VALUES ('S3 Report Storage', '/admin/s3-settings', 'Cloud', 'ADMINISTRATION', 7)
ON CONFLICT (path) DO UPDATE
    SET menu_name     = 'S3 Report Storage',
        icon_key      = 'Cloud',
        category      = 'ADMINISTRATION',
        display_order = 7;

-- Step 2: Grant to Super Admin
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin'
  AND m.path = '/admin/s3-settings'
ON CONFLICT DO NOTHING;

-- Step 3: Grant to Admin (if group exists)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Admin'
  AND m.path = '/admin/s3-settings'
ON CONFLICT DO NOTHING;

-- Step 4: Grant to Bank Admin (if group exists)
INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Bank Admin'
  AND m.path = '/admin/s3-settings'
ON CONFLICT DO NOTHING;

-- Verify: check the menu was inserted and granted correctly
SELECT
    m.menu_id,
    m.menu_name,
    m.path,
    m.icon_key,
    m.category,
    m.display_order,
    string_agg(g.group_name, ', ' ORDER BY g.group_name) AS granted_to_groups
FROM sys_menu m
LEFT JOIN sys_group_menu gm ON gm.menu_id = m.menu_id
LEFT JOIN sys_user_group g  ON g.group_id  = gm.group_id
WHERE m.path = '/admin/s3-settings'
GROUP BY m.menu_id, m.menu_name, m.path, m.icon_key, m.category, m.display_order;
