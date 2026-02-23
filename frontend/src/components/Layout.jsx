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
} from '@mui/material';
import * as LucideIcons from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import { useTheme } from '../contexts/ThemeContext';
import TenantSwitcher from './TenantSwitcher';
import ThemeToggle from './ThemeToggle';

const DRAWER_WIDTH = 260;

const Layout = () => {
    const navigate = useNavigate();
    const location = useLocation();
    const { menus, logout, username, activeTenant } = useAuth();

    const handleLogout = () => {
        logout();
        navigate('/login');
    };

    // Group menus by category
    const groupedMenus = useMemo(() => {
        const groups = {};
        (menus || []).forEach(menu => {
            const category = (menu.category || 'GENERAL').replace(' UNIVERSE', '');
            if (!groups[category]) groups[category] = [];
            groups[category].push(menu);
        });

        Object.keys(groups).forEach(key => {
            groups[key].sort((a, b) => (a.displayOrder || 0) - (b.displayOrder || 0));
        });

        return groups;
    }, [menus]);

    const categoryOrder = ['EXECUTIVE', 'BUSINESS', 'SALES', 'FINANCE', 'MERCHANT MGT', 'OPERATIONS', 'ADMINISTRATION', 'GENERAL'];
    const sortedCategories = Object.keys(groupedMenus).sort((a, b) => {
        const idxA = categoryOrder.indexOf(a);
        const idxB = categoryOrder.indexOf(b);
        if (idxA !== -1 && idxB !== -1) return idxA - idxB;
        if (idxA !== -1) return -1;
        if (idxB !== -1) return 1;
        return a.localeCompare(b);
    });

    const drawerContent = (
        <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', bgcolor: '#0f172a', color: 'white' }}>
            {/* Header */}
            <Box sx={{ p: 2.5, pb: 2, borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2 }}>
                    <Box sx={{
                        width: 32, height: 32, borderRadius: 2,
                        background: 'linear-gradient(135deg, #3b82f6, #2563eb)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        fontWeight: 'bold', fontSize: '14px', color: 'white'
                    }}>
                        A
                    </Box>
                    <Typography variant="h6" fontWeight="bold" sx={{ fontSize: '1.1rem' }}>Acquira</Typography>
                </Box>

                {/* Tenant Switcher — database-driven, replaces CombinedViewSwitcher */}
                <TenantSwitcher />
            </Box>

            {/* Menu List — driven entirely by database RBAC */}
            <List sx={{ flex: 1, overflowY: 'auto', px: 1.5, pt: 1.5, '&::-webkit-scrollbar': { width: '4px' }, '&::-webkit-scrollbar-thumb': { background: 'rgba(255,255,255,0.1)', borderRadius: '4px' } }}>
                {sortedCategories.map(category => (
                    <React.Fragment key={category}>
                        <Typography variant="caption" sx={{
                            color: 'rgba(255,255,255,0.35)',
                            fontWeight: 700,
                            display: 'block',
                            mt: 2, mb: 0.5, pl: 1.5,
                            fontSize: '0.65rem',
                            letterSpacing: '0.08em',
                            textTransform: 'uppercase',
                        }}>
                            {category}
                        </Typography>
                        {groupedMenus[category].map(menu => {
                            const IconComponent = (LucideIcons && LucideIcons[menu.iconKey])
                                ? LucideIcons[menu.iconKey]
                                : LucideIcons.Circle;
                            const isActive = location.pathname === menu.path;
                            return (
                                <ListItem key={menu.menuId || menu.path} disablePadding sx={{ mb: 0.25 }}>
                                    <ListItemButton
                                        onClick={() => navigate(menu.path)}
                                        selected={isActive}
                                        sx={{
                                            borderRadius: 2,
                                            py: 0.75,
                                            minHeight: 38,
                                            '&.Mui-selected': {
                                                bgcolor: 'rgba(59,130,246,0.15)',
                                                color: '#60a5fa',
                                                '&:hover': { bgcolor: 'rgba(59,130,246,0.2)' }
                                            },
                                            '&:hover': { bgcolor: 'rgba(255,255,255,0.05)' }
                                        }}
                                    >
                                        <ListItemIcon sx={{ minWidth: 36, color: isActive ? '#60a5fa' : 'rgba(255,255,255,0.5)' }}>
                                            <IconComponent size={18} />
                                        </ListItemIcon>
                                        <ListItemText
                                            primary={menu.menuName}
                                            primaryTypographyProps={{
                                                fontSize: '0.82rem',
                                                fontWeight: isActive ? 600 : 400,
                                            }}
                                        />
                                    </ListItemButton>
                                </ListItem>
                            );
                        })}
                    </React.Fragment>
                ))}
            </List>

            {/* Footer — User info + Logout */}
            <Box sx={{ p: 2, borderTop: '1px solid rgba(255,255,255,0.08)' }}>
                {/* User info */}
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1.5, px: 1 }}>
                    <Box sx={{
                        width: 28, height: 28, borderRadius: '50%',
                        bgcolor: 'rgba(255,255,255,0.1)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        fontSize: '0.75rem', fontWeight: 600, color: 'rgba(255,255,255,0.7)',
                    }}>
                        {(username || 'U')[0].toUpperCase()}
                    </Box>
                    <Box sx={{ overflow: 'hidden' }}>
                        <Typography sx={{ fontSize: '0.78rem', fontWeight: 500, color: 'rgba(255,255,255,0.8)', lineHeight: 1.3 }}>
                            {username || 'User'}
                        </Typography>
                        <Typography sx={{ fontSize: '0.65rem', color: 'rgba(255,255,255,0.35)', lineHeight: 1.3 }}>
                            {activeTenant?.bankName || ''}
                        </Typography>
                    </Box>
                </Box>

                {/* #27: Dark mode toggle */}
                <Box sx={{ px: 1, mb: 1 }}>
                    <ThemeToggle />
                </Box>

                <ListItemButton
                    onClick={handleLogout}
                    sx={{
                        borderRadius: 2,
                        py: 0.75,
                        color: '#ef4444',
                        '&:hover': { bgcolor: 'rgba(239,68,68,0.1)' }
                    }}
                >
                    <ListItemIcon sx={{ minWidth: 36, color: '#ef4444' }}>
                        <LucideIcons.LogOut size={18} />
                    </ListItemIcon>
                    <ListItemText primary="Sign Out" primaryTypographyProps={{ fontSize: '0.82rem' }} />
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
                        borderRight: 'none',
                    },
                }}
            >
                {drawerContent}
            </Drawer>
            <Box component="main" sx={{ flexGrow: 1, bgcolor: 'var(--bg, #f8fafc)', color: 'var(--text, #111827)', minHeight: '100vh', width: `calc(100% - ${DRAWER_WIDTH}px)`, transition: 'background-color 0.2s, color 0.2s' }}>
                <Outlet />
            </Box>
        </Box>
    );
};

export default Layout;
