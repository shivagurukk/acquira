-- V2026_02_21_03: Add Data Migration menu entry
-- Only Super Admin can see this

INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES
('Data Migration', '/admin/data-migration', 'DatabaseZap', 'ADMINISTRATION', 7)
ON CONFLICT (path) DO NOTHING;

INSERT INTO sys_group_menu (group_id, menu_id)
SELECT g.group_id, m.menu_id
FROM sys_user_group g, sys_menu m
WHERE g.group_name = 'Super Admin' AND m.path = '/admin/data-migration'
ON CONFLICT DO NOTHING;
