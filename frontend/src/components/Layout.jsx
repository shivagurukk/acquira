import React, { useMemo, useState, useCallback, useEffect, useRef } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
    Box, Drawer, List, ListItem, ListItemButton, ListItemIcon, ListItemText,
    Typography, Collapse, Tooltip, IconButton, Avatar, TextField, InputAdornment,
    useMediaQuery
} from '@mui/material';
import * as LucideIcons from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import TenantSwitcher from './TenantSwitcher';
import ThemeToggle from './ThemeToggle';

const DRAWER_WIDTH  = 256;
const COLLAPSED_WIDTH = 64;

/* ─── Dark Sidebar Tokens ─── */
const S = {
    bg:       '#0f172a',
    bgHover:  'rgba(255,255,255,0.04)',
    bgActive: 'rgba(59,130,246,0.12)',
    border:   'rgba(255,255,255,0.06)',
    text:     'rgba(255,255,255,0.85)',
    textSec:  'rgba(255,255,255,0.4)',
    textMuted:'rgba(255,255,255,0.2)',
    accent:   '#3b82f6',
};

const catMeta = {
    EXECUTIVE:          { icon: 'LayoutDashboard' },
    BUSINESS:           { icon: 'BarChart3'       },
    SALES:              { icon: 'TrendingUp'       },
    FINANCE:            { icon: 'Wallet'           },
    'MERCHANT MGT':     { icon: 'Store'            },
    OPERATIONS:         { icon: 'Settings2'        },
    ADMINISTRATION:     { icon: 'ShieldCheck'      },
    'DATA INTEGRATION': { icon: 'Database'         },
    GENERAL:            { icon: 'Grid3x3'          },
};

const catColors = {
    EXECUTIVE:      { color: '#60a5fa', bg: 'rgba(96,165,250,0.1)'  },
    BUSINESS:       { color: '#60a5fa', bg: 'rgba(96,165,250,0.1)'  },
    SALES:          { color: '#34d399', bg: 'rgba(52,211,153,0.1)'  },
    FINANCE:        { color: '#fbbf24', bg: 'rgba(251,191,36,0.1)'  },
    'MERCHANT MGT': { color: '#60a5fa', bg: 'rgba(96,165,250,0.1)'  },
    OPERATIONS:     { color: '#94a3b8', bg: 'rgba(148,163,184,0.08)'},
    ADMINISTRATION: { color: '#f87171', bg: 'rgba(248,113,113,0.1)' },
    'DATA INTEGRATION':{ color: '#a78bfa', bg: 'rgba(167,139,250,0.1)'},
    GENERAL:        { color: '#94a3b8', bg: 'rgba(148,163,184,0.08)'},
};

const categoryOrder = [
    'EXECUTIVE','BUSINESS','SALES','FINANCE',
    'MERCHANT MGT','OPERATIONS','ADMINISTRATION','DATA INTEGRATION','GENERAL',
];

const Layout = () => {
    const navigate  = useNavigate();
    const location  = useLocation();
    const { menus, logout, username, activeTenant, activeTenantId, tenantVersion } = useAuth();

    const [collapsed,   setCollapsed]   = useState(false);
    const [openGroups,  setOpenGroups]  = useState({});
    const [searchTerm,  setSearchTerm]  = useState('');
    const [mobileOpen,  setMobileOpen]  = useState(false);
    const searchRef = useRef(null);

    const handleLogout = () => { logout(); navigate('/login'); };
    const toggleGroup  = useCallback((cat) => {
        setOpenGroups(prev => ({ ...prev, [cat]: !prev[cat] }));
    }, []);

    useEffect(() => {
        const handler = (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
                e.preventDefault();
                if (collapsed) setCollapsed(false);
                setTimeout(() => searchRef.current?.focus(), 100);
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [collapsed]);

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

    const sortedCategories = useMemo(() =>
        Object.keys(groupedMenus).sort((a, b) => {
            const iA = categoryOrder.indexOf(a), iB = categoryOrder.indexOf(b);
            if (iA !== -1 && iB !== -1) return iA - iB;
            if (iA !== -1) return -1;
            if (iB !== -1) return 1;
            return a.localeCompare(b);
        }), [groupedMenus]);

    const filteredCategories = useMemo(() => {
        if (!searchTerm.trim()) return sortedCategories;
        const q = searchTerm.toLowerCase();
        return sortedCategories.filter(cat =>
            groupedMenus[cat]?.some(m => m.menuName.toLowerCase().includes(q))
        );
    }, [sortedCategories, groupedMenus, searchTerm]);

    const filterItems = (items) => {
        if (!searchTerm.trim()) return items;
        const q = searchTerm.toLowerCase();
        return items.filter(m => m.menuName.toLowerCase().includes(q));
    };

    useEffect(() => {
        sortedCategories.forEach(cat => {
            if (groupedMenus[cat]?.some(m => location.pathname === m.path)) {
                setOpenGroups(prev => prev[cat] ? prev : { ...prev, [cat]: true });
            }
        });
    }, [location.pathname]);

    useEffect(() => {
        if (searchTerm.trim()) {
            const expanded = {};
            filteredCategories.forEach(c => (expanded[c] = true));
            setOpenGroups(prev => ({ ...prev, ...expanded }));
        }
    }, [searchTerm]);

    const isMobile = useMediaQuery('(max-width:768px)');
    const w = isMobile ? DRAWER_WIDTH : (collapsed ? COLLAPSED_WIDTH : DRAWER_WIDTH);

    useEffect(() => { if (isMobile) setMobileOpen(false); }, [location.pathname]);

    const drawerContent = (
        <Box sx={{
            display: 'flex', flexDirection: 'column', height: '100%',
            bgcolor: S.bg, color: S.text, width: w,
            overflow: 'hidden',
            transition: 'width 0.22s cubic-bezier(0.4,0,0.2,1)',
        }}>
            {/* ══ Brand ══ */}
            <Box sx={{
                px: collapsed ? 0 : 2, py: 1.8,
                display: 'flex', alignItems: 'center',
                justifyContent: collapsed ? 'center' : 'space-between',
                borderBottom: `1px solid ${S.border}`,
                minHeight: 60, flexShrink: 0,
            }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.4, cursor: 'pointer', flex: 1, minWidth: 0 }}
                    onClick={() => navigate('/dashboard')}>
                    <Box sx={{
                        width: 34, height: 34, borderRadius: '10px', flexShrink: 0,
                        background: 'linear-gradient(135deg, #3b82f6, #8b5cf6)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        boxShadow: '0 4px 12px rgba(99,102,241,0.35)',
                        fontSize: 15, fontWeight: 800, color: '#fff', fontFamily: 'system-ui',
                    }}>A</Box>
                    {!collapsed && (
                        <Box sx={{ minWidth: 0 }}>
                            <Typography sx={{ fontSize: '0.92rem', fontWeight: 700, color: '#fff', letterSpacing: '-0.02em', lineHeight: 1.2 }}>
                                Acquira
                            </Typography>
                            <Typography sx={{ fontSize: '0.62rem', color: S.textSec, textTransform: 'uppercase', letterSpacing: '0.08em', lineHeight: 1, mt: 0.3 }}>
                                CMS Platform
                            </Typography>
                        </Box>
                    )}
                </Box>
                {!collapsed && (
                    <IconButton size="small" onClick={() => setCollapsed(true)}
                        sx={{ color: S.textSec, ml: 0.5, flexShrink: 0, '&:hover': { color: S.text, bgcolor: S.bgHover } }}>
                        <LucideIcons.PanelLeftClose size={15} />
                    </IconButton>
                )}
                {collapsed && (
                    <IconButton size="small" onClick={() => setCollapsed(false)}
                        sx={{ color: S.textSec, '&:hover': { color: S.text, bgcolor: S.bgHover } }}>
                        <LucideIcons.PanelLeftOpen size={15} />
                    </IconButton>
                )}
            </Box>

            {/* ══ Tenant ══ */}
            {!collapsed && (
                <Box sx={{ px: 1.5, py: 1, borderBottom: `1px solid ${S.border}`, flexShrink: 0 }}>
                    <TenantSwitcher />
                </Box>
            )}

            {/* ══ Search ══ */}
            {!collapsed && (
                <Box sx={{ px: 1.5, py: 1.2, flexShrink: 0 }}>
                    <TextField
                        inputRef={searchRef}
                        placeholder="Search…  ⌘K"
                        value={searchTerm}
                        onChange={e => setSearchTerm(e.target.value)}
                        size="small" fullWidth
                        InputProps={{
                            startAdornment: <InputAdornment position="start"><LucideIcons.Search size={13} color={S.textSec} /></InputAdornment>,
                            endAdornment: searchTerm ? (
                                <InputAdornment position="end">
                                    <IconButton size="small" onClick={() => setSearchTerm('')} sx={{ color: S.textSec, p: 0.3 }}>
                                        <LucideIcons.X size={12} />
                                    </IconButton>
                                </InputAdornment>
                            ) : null,
                        }}
                        sx={{
                            '& .MuiInputBase-root': {
                                bgcolor: 'rgba(255,255,255,0.05)', borderRadius: '8px', height: 34,
                                '& fieldset': { border: `1px solid ${S.border}` },
                                '&:hover fieldset': { border: `1px solid rgba(255,255,255,0.12)` },
                                '&.Mui-focused fieldset': { border: '1px solid rgba(99,102,241,0.5)' },
                            },
                            '& .MuiInputBase-input': {
                                color: S.text, fontSize: '0.82rem', py: 0.5,
                                '&::placeholder': { color: S.textSec, opacity: 1 },
                            },
                        }}
                    />
                </Box>
            )}

            {/* ══ Navigation ══ */}
            <List sx={{
                flex: 1, overflowY: 'auto', overflowX: 'hidden', px: collapsed ? 0.5 : 1, py: 0.5,
                '&::-webkit-scrollbar': { width: 3 },
                '&::-webkit-scrollbar-track': { background: 'transparent' },
                '&::-webkit-scrollbar-thumb': { background: 'rgba(255,255,255,0.08)', borderRadius: 4 },
            }}>
                {filteredCategories.map((category, catIdx) => {
                    const items = filterItems(groupedMenus[category]);
                    if (items.length === 0) return null;
                    const meta = catMeta[category] || catMeta.GENERAL;
                    const colors = catColors[category] || catColors.GENERAL;
                    const CatIcon = LucideIcons[meta.icon] || LucideIcons.Folder;
                    const isOpen = openGroups[category] ?? false;
                    const hasActive = items.some(m => location.pathname === m.path);

                    return (
                        <React.Fragment key={category}>
                            {collapsed ? (
                                <Tooltip title={category} placement="right" arrow>
                                    <Box sx={{ display: 'flex', justifyContent: 'center', py: 0.8, mt: catIdx > 0 ? 0.5 : 0 }}>
                                        <Box sx={{ width: 4, height: 4, borderRadius: '50%', bgcolor: hasActive ? colors.color : 'rgba(255,255,255,0.15)' }} />
                                    </Box>
                                </Tooltip>
                            ) : (
                                <ListItemButton dense onClick={() => toggleGroup(category)}
                                    sx={{
                                        borderRadius: '8px', py: 0.5, px: 1, mt: catIdx > 0 ? 1 : 0, mb: 0.3, minHeight: 30,
                                        '&:hover': { bgcolor: S.bgHover },
                                    }}>
                                    <ListItemIcon sx={{ minWidth: 24, color: hasActive ? colors.color : S.textMuted }}>
                                        <CatIcon size={12} />
                                    </ListItemIcon>
                                    <ListItemText primary={category}
                                        primaryTypographyProps={{
                                            fontSize: '0.65rem', fontWeight: 700, letterSpacing: '0.09em', textTransform: 'uppercase',
                                            color: hasActive ? colors.color : S.textSec,
                                        }} />
                                    {isOpen ? <LucideIcons.ChevronDown size={11} style={{ color: S.textMuted, flexShrink: 0 }} />
                                           : <LucideIcons.ChevronRight size={11} style={{ color: S.textMuted, flexShrink: 0 }} />}
                                </ListItemButton>
                            )}

                            <Collapse in={collapsed || isOpen} timeout={160} unmountOnExit>
                                <Box sx={{ display: 'flex', flexDirection: 'column', gap: '2px', mb: 0.5 }}>
                                    {items.map(menu => {
                                        const MIcon = LucideIcons[menu.iconKey] || LucideIcons.Circle;
                                        const active = location.pathname === menu.path;
                                        const btn = (
                                            <ListItemButton
                                                onClick={() => { navigate(menu.path); setSearchTerm(''); }}
                                                selected={active}
                                                sx={{
                                                    borderRadius: '8px', py: 0.75, px: collapsed ? 0 : 1.2,
                                                    minHeight: 36, justifyContent: collapsed ? 'center' : 'flex-start',
                                                    position: 'relative', transition: 'background 0.15s',
                                                    '&.Mui-selected': {
                                                        bgcolor: colors.bg,
                                                        '& .MuiListItemIcon-root': { color: colors.color },
                                                        '& .MuiListItemText-primary': { color: colors.color, fontWeight: 600 },
                                                        '&::before': !collapsed ? {
                                                            content: '""', position: 'absolute', left: 0,
                                                            top: '18%', height: '64%', width: 3, borderRadius: '0 3px 3px 0',
                                                            bgcolor: colors.color,
                                                        } : {},
                                                        '&:hover': { bgcolor: colors.bg },
                                                    },
                                                    '&:hover': { bgcolor: S.bgHover },
                                                }}
                                            >
                                                <ListItemIcon sx={{ minWidth: collapsed ? 0 : 30, color: active ? colors.color : 'rgba(255,255,255,0.4)', justifyContent: 'center', transition: 'color 0.15s' }}>
                                                    <MIcon size={16} />
                                                </ListItemIcon>
                                                {!collapsed && (
                                                    <ListItemText primary={menu.menuName}
                                                        primaryTypographyProps={{ fontSize: '0.83rem', fontWeight: active ? 600 : 400, color: active ? colors.color : S.text, noWrap: true }} />
                                                )}
                                            </ListItemButton>
                                        );
                                        return (
                                            <ListItem key={menu.menuId || menu.path} disablePadding>
                                                {collapsed ? <Tooltip title={menu.menuName} placement="right" arrow>{btn}</Tooltip> : btn}
                                            </ListItem>
                                        );
                                    })}
                                </Box>
                            </Collapse>
                        </React.Fragment>
                    );
                })}

                {searchTerm && filteredCategories.length === 0 && !collapsed && (
                    <Box sx={{ textAlign: 'center', py: 5, color: S.textSec }}>
                        <LucideIcons.SearchX size={26} style={{ opacity: 0.4, marginBottom: 8 }} />
                        <Typography sx={{ fontSize: '0.78rem', color: S.textSec }}>No results for "{searchTerm}"</Typography>
                    </Box>
                )}
            </List>

            {/* ══ Footer ══ */}
            <Box sx={{ borderTop: `1px solid ${S.border}`, p: collapsed ? 0.8 : 1.5, flexShrink: 0, bgcolor: 'rgba(0,0,0,0.15)' }}>
                {!collapsed && (
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.2, mb: 1.2, px: 0.3 }}>
                        <Avatar sx={{
                            width: 32, height: 32, fontSize: '0.78rem', fontWeight: 700,
                            background: 'linear-gradient(135deg,#3b82f6,#8b5cf6)', color: '#fff', flexShrink: 0,
                        }}>
                            {(username || 'U')[0].toUpperCase()}
                        </Avatar>
                        <Box sx={{ overflow: 'hidden', flex: 1 }}>
                            <Typography noWrap sx={{ fontSize: '0.85rem', fontWeight: 600, color: S.text, lineHeight: 1.2 }}>
                                {username || 'User'}
                            </Typography>
                            <Typography noWrap sx={{ fontSize: '0.7rem', color: S.textSec, lineHeight: 1.2, mt: 0.2 }}>
                                {activeTenant?.bankName || 'Administrator'}
                            </Typography>
                        </Box>
                    </Box>
                )}

                {collapsed ? (
                    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 0.5 }}>
                        <Tooltip title={username || 'User'} placement="right" arrow>
                            <Avatar sx={{
                                width: 30, height: 30, fontSize: '0.75rem', fontWeight: 700,
                                background: 'linear-gradient(135deg,#3b82f6,#8b5cf6)', color: '#fff', mb: 0.5,
                            }}>
                                {(username || 'U')[0].toUpperCase()}
                            </Avatar>
                        </Tooltip>
                        <Tooltip title="Change Password" placement="right" arrow>
                            <IconButton size="small" onClick={() => navigate('/change-password')}
                                sx={{ color: S.textSec, '&:hover': { color: '#60a5fa', bgcolor: 'rgba(59,130,246,0.1)' } }}>
                                <LucideIcons.KeyRound size={15} />
                            </IconButton>
                        </Tooltip>
                        <Tooltip title="Sign Out" placement="right" arrow>
                            <IconButton size="small" onClick={handleLogout}
                                sx={{ color: '#f87171', '&:hover': { bgcolor: 'rgba(248,113,113,0.1)' } }}>
                                <LucideIcons.LogOut size={15} />
                            </IconButton>
                        </Tooltip>
                    </Box>
                ) : (
                    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                        <Box sx={{ display: 'flex', gap: 0.5 }}>
                            <ListItemButton dense onClick={() => navigate('/change-password')}
                                sx={{ borderRadius: '8px', py: 0.7, flex: 1, color: S.textSec, '&:hover': { bgcolor: S.bgHover, color: S.text } }}>
                                <ListItemIcon sx={{ minWidth: 26, color: 'inherit' }}><LucideIcons.KeyRound size={14} /></ListItemIcon>
                                <ListItemText primary="Password" primaryTypographyProps={{ fontSize: '0.82rem' }} />
                            </ListItemButton>
                            <Box sx={{ flexShrink: 0, display: 'flex', alignItems: 'center' }}>
                                <ThemeToggle />
                            </Box>
                        </Box>
                        <ListItemButton dense onClick={handleLogout}
                            sx={{ borderRadius: '8px', py: 0.7, color: '#f87171', '&:hover': { bgcolor: 'rgba(248,113,113,0.08)', color: '#fca5a5' } }}>
                            <ListItemIcon sx={{ minWidth: 26, color: 'inherit' }}><LucideIcons.LogOut size={14} /></ListItemIcon>
                            <ListItemText primary="Sign Out" primaryTypographyProps={{ fontSize: '0.82rem', fontWeight: 500 }} />
                        </ListItemButton>
                    </Box>
                )}
            </Box>
        </Box>
    );

    const effectiveW = isMobile ? 0 : w;

    return (
        <Box sx={{ display: 'flex' }}>
            {isMobile ? (
                <Drawer variant="temporary" open={mobileOpen} onClose={() => setMobileOpen(false)}
                    ModalProps={{ keepMounted: true }}
                    sx={{ '& .MuiDrawer-paper': { width: DRAWER_WIDTH, boxSizing: 'border-box', borderRight: 'none' } }}>
                    {drawerContent}
                </Drawer>
            ) : (
                <Drawer variant="permanent" sx={{
                    width: w, flexShrink: 0,
                    '& .MuiDrawer-paper': {
                        width: w, boxSizing: 'border-box', borderRight: 'none',
                        transition: 'width 0.22s cubic-bezier(0.4,0,0.2,1)', overflowX: 'hidden',
                    },
                }}>
                    {drawerContent}
                </Drawer>
            )}

            <Box component="main" sx={{
                flexGrow: 1, bgcolor: 'var(--bg)', color: 'var(--text)',
                minHeight: '100vh', width: `calc(100% - ${effectiveW}px)`,
                transition: 'width 0.22s cubic-bezier(0.4,0,0.2,1)',
            }}>
                {isMobile && (
                    <Box sx={{
                        display: 'flex', alignItems: 'center', gap: 1.5, px: 2, py: 1.3,
                        borderBottom: '1px solid var(--border)', bgcolor: 'var(--bg-card)',
                    }}>
                        <IconButton onClick={() => setMobileOpen(true)} sx={{ color: 'var(--text)' }}>
                            <LucideIcons.Menu size={20} />
                        </IconButton>
                        <Typography fontWeight={700} sx={{ fontSize: '0.95rem', color: 'var(--text)' }}>Acquira</Typography>
                    </Box>
                )}
                <div key={`tenant-${activeTenantId}-${tenantVersion}`} className="page-transition">
                    <Outlet />
                </div>
            </Box>
        </Box>
    );
};

export default Layout;
