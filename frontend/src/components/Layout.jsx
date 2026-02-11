import React, { useMemo, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
    Box,
    Drawer,
    List,
    ListItem,
    ListItemButton,
    ListItemIcon,
    ListItemText,
    Typography,
    IconButton,
    Avatar,
    Divider,
    Collapse,
    AppBar,
    Toolbar
} from '@mui/material';
import * as LucideIcons from 'lucide-react';
import CombinedViewSwitcher from './CombinedViewSwitcher';

const DRAWER_WIDTH = 260;

const Layout = () => {
    const navigate = useNavigate();
    const location = useLocation();

    // For mobile responsiveness if needed later, currently permanent
    const [mobileOpen, setMobileOpen] = useState(false);

    const handleLogout = () => {
        localStorage.clear();
        navigate('/');
    };

    const menus = useMemo(() => {
        try {
            const storedMenus = localStorage.getItem('menus');
            let parsed = storedMenus ? JSON.parse(storedMenus) : [];

            // Filter out unwanted menus
            const menusToRemove = ['Sales Analytics', 'Zero Sales', 'Profitability', 'P&L Views', 'Sales Trends', 'Lifecycle'];
            parsed = parsed.filter(m => !menusToRemove.includes(m.menuName));

            // INJECT BUSINESS MENUS
            const businessMenus = [
                { menuId: 101, menuName: 'Volume & Revenue', path: '/business/volume-revenue', iconKey: 'BarChart3', category: 'BUSINESS', displayOrder: 1 },
                { menuId: 102, menuName: 'Merchant Financial', path: '/business/merchant-financial', iconKey: 'DollarSign', category: 'BUSINESS', displayOrder: 2 },
                { menuId: 109, menuName: 'Merchant Report Manager', path: '/business/report-manager', iconKey: 'FileText', category: 'BUSINESS', displayOrder: 9 },
                { menuId: 103, menuName: 'Performance Trends', path: '/business/performance', iconKey: 'TrendingUp', category: 'BUSINESS', displayOrder: 3 },
                { menuId: 104, menuName: 'Debit & Prepaid Metrics', path: '/business/debit-prepaid', iconKey: 'CreditCard', category: 'BUSINESS', displayOrder: 4 },
                { menuId: 105, menuName: 'Attrition Report', path: '/business/attrition', iconKey: 'Activity', category: 'BUSINESS', displayOrder: 5 },
                { menuId: 106, menuName: 'Merchant Growth Heatmap', path: '/business/heatmap', iconKey: 'Grid', category: 'BUSINESS', displayOrder: 6 },
                { menuId: 107, menuName: 'Daily Merchant Dashboard', path: '/business/daily-dashboard', iconKey: 'Calendar', category: 'BUSINESS', displayOrder: 7 },
                { menuId: 108, menuName: 'Merchant Analytics', path: '/business/merchant-analytics', iconKey: 'BarChart2', category: 'BUSINESS', displayOrder: 8 },
                // Keep Executive Dashboard if needed, or remove if replaced by above
                { menuId: 114, menuName: 'Executive Dashboard', path: '/business/executive-dashboard-v2', iconKey: 'Presentation', category: 'EXECUTIVE', displayOrder: 0 },
                { menuId: 115, menuName: 'Data Explorer', path: '/explorer', iconKey: 'Compass', category: 'BUSINESS', displayOrder: 12 },
                { menuId: 116, menuName: 'AI Assistant', path: '/ai-assistant', iconKey: 'BrainCircuit', category: 'BUSINESS', displayOrder: 13 },
            ];

            const salesMenus = [
                { menuId: 201, menuName: 'Team Management', path: '/sales/team-management', iconKey: 'Users', category: 'SALES', displayOrder: 10 },
            ];

            // Append business menus if not existing
            const existingPaths = new Set(parsed.map(m => m.path));
            businessMenus.forEach(m => {
                if (!existingPaths.has(m.path)) parsed.push(m);
            });
            salesMenus.forEach(m => {
                if (!existingPaths.has(m.path)) parsed.push(m);
            });

            return parsed;
        } catch (e) {
            console.error("Failed to parse menus", e);
            return [];
        }
    }, []);

    // Group menus by category
    const groupedMenus = useMemo(() => {
        const groups = {};
        menus.forEach(menu => {
            const category = menu.category || 'GENERAL';
            // Normalize category name for simpler display
            const normalizedCat = category.replace(' UNIVERSE', '');
            if (!groups[normalizedCat]) groups[normalizedCat] = [];
            groups[normalizedCat].push(menu);
        });

        Object.keys(groups).forEach(key => {
            groups[key].sort((a, b) => a.displayOrder - b.displayOrder);
        });

        return groups;
    }, [menus]);

    const categoryOrder = ['EXECUTIVE', 'BUSINESS', 'SALES', 'FINANCE', 'OPERATIONS', 'ADMINISTRATION', 'GENERAL'];
    const sortedCategories = Object.keys(groupedMenus).sort((a, b) => {
        const idxA = categoryOrder.indexOf(a);
        const idxB = categoryOrder.indexOf(b);
        if (idxA !== -1 && idxB !== -1) return idxA - idxB;
        if (idxA !== -1) return -1;
        if (idxB !== -1) return 1;
        return a.localeCompare(b);
    });

    const allowedTenants = JSON.parse(localStorage.getItem('allowedTenants') || '[]');
    const currentTenantId = localStorage.getItem('defaultTenantId');

    const handleSwitchTenant = async (e) => {
        // Implementation remains same
        const newTenantId = e.target.value;
        const tenant = allowedTenants.find(t => t.tenantId == newTenantId);
        if (tenant) {
            localStorage.setItem('defaultTenantId', tenant.tenantId);
            try {
                const token = localStorage.getItem('token');
                const res = await fetch('/api/auth/switch-context', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                    body: JSON.stringify({ tenantId: tenant.tenantId })
                });
                if (res.ok) {
                    const data = await res.json();
                    localStorage.setItem('menus', JSON.stringify(data.menus));
                    window.location.reload();
                }
            } catch (err) { console.error("Switch failed", err); }
        }
    };

    const drawerContent = (
        <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', bgcolor: '#0f172a', color: 'white' }}>
            {/* Header */}
            <Box sx={{ p: 3, borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 2 }}>
                    <Box sx={{ width: 32, height: 32, bgcolor: 'primary.main', borderRadius: 2 }} />
                    <Typography variant="h6" fontWeight="bold">Acquira</Typography>
                </Box>

                {/* Multi-Tenant Switcher */}
                <div style={{ padding: '4px 0' }}>
                    <CombinedViewSwitcher
                        onContextChange={() => {
                            // Reload handled by component or here if needed
                            // For improved UX we could trigger menu fetch here instead of full reload
                            // but component does reload.
                        }}
                    />
                </div>
            </Box>

            {/* Menu List */}
            <List sx={{ flex: 1, overflowY: 'auto', px: 2, pt: 2 }}>
                {sortedCategories.map(category => (
                    <React.Fragment key={category}>
                        <Typography variant="caption" sx={{ color: 'rgba(255,255,255,0.5)', fontWeight: 'bold', display: 'block', mt: 2, mb: 1, pl: 1 }}>
                            {category}
                        </Typography>
                        {groupedMenus[category].map(menu => {
                            const IconComponent = (LucideIcons && LucideIcons[menu.iconKey]) ? LucideIcons[menu.iconKey] : LucideIcons.Circle;
                            const isActive = location.pathname === menu.path;
                            return (
                                <ListItem key={menu.menuId} disablePadding sx={{ mb: 0.5 }}>
                                    <ListItemButton
                                        onClick={() => navigate(menu.path)}
                                        selected={isActive}
                                        sx={{
                                            borderRadius: 2,
                                            '&.Mui-selected': {
                                                bgcolor: 'rgba(255,255,255,0.1)',
                                                color: '#60a5fa', // Blue 400
                                                '&:hover': { bgcolor: 'rgba(255,255,255,0.15)' }
                                            },
                                            '&:hover': { bgcolor: 'rgba(255,255,255,0.05)' }
                                        }}
                                    >
                                        <ListItemIcon sx={{ minWidth: 40, color: isActive ? '#60a5fa' : 'rgba(255,255,255,0.7)' }}>
                                            <IconComponent size={20} />
                                        </ListItemIcon>
                                        <ListItemText
                                            primary={menu.menuName}
                                            primaryTypographyProps={{ fontSize: '0.875rem', fontWeight: isActive ? 600 : 400 }}
                                        />
                                    </ListItemButton>
                                </ListItem>
                            );
                        })}
                    </React.Fragment>
                ))}
            </List>

            {/* Footer */}
            <Box sx={{ p: 2, borderTop: '1px solid rgba(255,255,255,0.1)' }}>
                <ListItemButton onClick={handleLogout} sx={{ borderRadius: 2, color: '#ef4444', '&:hover': { bgcolor: 'rgba(239,68,68,0.1)' } }}>
                    <ListItemIcon sx={{ minWidth: 40, color: '#ef4444' }}><LucideIcons.LogOut size={20} /></ListItemIcon>
                    <ListItemText primary="Sign Out" />
                </ListItemButton>
            </Box>
        </Box>
    );

    return (
        <Box sx={{ display: 'flex' }}>
            <Drawer
                variant="permanent"
                sx={{
                    width: DRAWER_WIDTH,
                    flexShrink: 0,
                    '& .MuiDrawer-paper': {
                        width: DRAWER_WIDTH,
                        boxSizing: 'border-box',
                        borderRight: 'none'
                    },
                }}
            >
                {drawerContent}
            </Drawer>
            <Box component="main" sx={{ flexGrow: 1, bgcolor: '#f8fafc', minHeight: '100vh', width: `calc(100% - ${DRAWER_WIDTH}px)` }}>
                <Outlet />
            </Box>
        </Box>
    );
};

export default Layout;
