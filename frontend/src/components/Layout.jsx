import React, { useMemo, useState, useCallback, useEffect, useRef } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useMediaQuery } from '@mui/material';
import * as LucideIcons from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import { useTheme } from '../contexts/ThemeContext';
import TenantSwitcher from './TenantSwitcher';
import NotificationBell from './NotificationBell';
import ShortcutsPanel from './ShortcutsPanel';

// ── Constants ──────────────────────────────────────────────────────
const DRAWER_W    = 240;
const COLLAPSE_W  = 56;
const RECENT_KEY  = 'acquira_recent_pages';
const RECENT_MAX  = 5;

// ── Dark sidebar design tokens ──────────────────────────────────────
const S = {
    bg:        '#0d1526',
    bgHov:     'rgba(255,255,255,0.04)',
    bgActive:  'rgba(59,130,246,0.12)',
    border:    'rgba(255,255,255,0.07)',
    text:      'rgba(255,255,255,0.82)',
    textSec:   'rgba(255,255,255,0.38)',
    textMuted: 'rgba(255,255,255,0.18)',
    accent:    '#3b82f6',
};

// ── Category meta: icon + accent colour ────────────────────────────
const CAT_META = {
    'EXECUTIVE':          { icon: 'LayoutDashboard', color: '#60a5fa', bg: 'rgba(96,165,250,0.12)'   },
    'BUSINESS':           { icon: 'BarChart3',       color: '#60a5fa', bg: 'rgba(96,165,250,0.12)'   },
    'SALES':              { icon: 'TrendingUp',      color: '#34d399', bg: 'rgba(52,211,153,0.12)'   },
    'FINANCE':            { icon: 'Wallet',          color: '#fbbf24', bg: 'rgba(251,191,36,0.12)'   },
    'MERCHANT MGT':       { icon: 'Store',           color: '#60a5fa', bg: 'rgba(96,165,250,0.12)'   },
    'OPERATIONS':         { icon: 'Settings2',       color: '#94a3b8', bg: 'rgba(148,163,184,0.1)'   },
    'ADMINISTRATION':     { icon: 'ShieldCheck',     color: '#f87171', bg: 'rgba(248,113,113,0.12)'  },
    'DATA INTEGRATION':   { icon: 'Database',        color: '#a78bfa', bg: 'rgba(167,139,250,0.12)'  },
    'GENERAL':            { icon: 'Grid3x3',         color: '#94a3b8', bg: 'rgba(148,163,184,0.1)'   },
};
const CAT_ORDER = [
    'EXECUTIVE','BUSINESS','SALES','FINANCE',
    'MERCHANT MGT','OPERATIONS','ADMINISTRATION','DATA INTEGRATION','GENERAL',
];
const PRIMARY_CATS = new Set(['EXECUTIVE','BUSINESS','SALES','FINANCE','MERCHANT MGT']);

// ── Recent pages helpers ────────────────────────────────────────────
function loadRecent() {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); }
    catch { return []; }
}
function saveRecent(list) {
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)); }
    catch { /* ignore */ }
}
function pushRecent(item) {
    const prev = loadRecent().filter(r => r.path !== item.path);
    const next = [item, ...prev].slice(0, RECENT_MAX);
    saveRecent(next);
    return next;
}

// ── Small components ───────────────────────────────────────────────
const Divider = () => (
    <div style={{ height: 1, background: S.border, margin: '6px 10px' }} />
);

const SectionLabel = ({ label }) => (
    <div style={{ padding: '6px 12px 2px', fontSize: 10, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: S.textMuted, userSelect: 'none' }}>
        {label}
    </div>
);

const NavItem = ({ menu, active, color, collapsed, onClick }) => {
    const Icon = LucideIcons[menu.iconKey] || LucideIcons.Circle;
    const btn = (
        <button
            onClick={onClick}
            title={collapsed ? menu.menuName : undefined}
            style={{
                display: 'flex', alignItems: 'center', gap: 9,
                width: '100%', padding: collapsed ? '8px 0' : '7px 10px',
                justifyContent: collapsed ? 'center' : 'flex-start',
                background: active ? S.bgActive : 'transparent',
                border: 'none', borderRadius: 8, cursor: 'pointer',
                position: 'relative', transition: 'background 0.13s',
                outline: 'none',
            }}
            onMouseEnter={e => { if (!active) e.currentTarget.style.background = S.bgHov; }}
            onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent'; }}
        >
            {active && !collapsed && (
                <span style={{ position: 'absolute', left: 0, top: '16%', height: '68%', width: 3, borderRadius: '0 3px 3px 0', background: color }} />
            )}
            <Icon size={15} color={active ? color : 'rgba(255,255,255,0.38)'} strokeWidth={active ? 2 : 1.8} style={{ flexShrink: 0 }} />
            {!collapsed && (
                <span style={{ fontSize: 13, fontWeight: active ? 600 : 400, color: active ? color : S.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {menu.menuName}
                </span>
            )}
        </button>
    );
    return <div style={{ padding: '1px 0' }}>{btn}</div>;
};

// ── Main Layout ────────────────────────────────────────────────────
const Layout = () => {
    const navigate  = useNavigate();
    const location  = useLocation();
    const { menus, logout, username, activeTenant, activeTenantId, tenantVersion } = useAuth();
    const { isDark, toggleTheme } = useTheme();
    const isMobile  = useMediaQuery('(max-width:768px)');

    const [collapsed,  setCollapsed]  = useState(false);
    const [openGroups, setOpenGroups] = useState({});
    const [search,     setSearch]     = useState('');
    const [mobileOpen, setMobileOpen] = useState(false);
    const [recent,     setRecent]     = useState(loadRecent);
    const [showSearch, setShowSearch] = useState(false);
    const searchRef = useRef(null);

    const w = isMobile ? DRAWER_W : (collapsed ? COLLAPSE_W : DRAWER_W);

    // ── Grouped menus ─────────────────────────────────────────────
    const grouped = useMemo(() => {
        const g = {};
        (menus || []).forEach(m => {
            const cat = (m.category || 'GENERAL').replace(' UNIVERSE', '');
            if (!g[cat]) g[cat] = [];
            g[cat].push(m);
        });
        Object.keys(g).forEach(k => g[k].sort((a, b) => (a.displayOrder || 0) - (b.displayOrder || 0)));
        return g;
    }, [menus]);

    const sortedCats = useMemo(() =>
        Object.keys(grouped).sort((a, b) => {
            const iA = CAT_ORDER.indexOf(a), iB = CAT_ORDER.indexOf(b);
            if (iA !== -1 && iB !== -1) return iA - iB;
            return iA !== -1 ? -1 : iB !== -1 ? 1 : a.localeCompare(b);
        }), [grouped]);

    // ── Search filter ─────────────────────────────────────────────
    const q = search.toLowerCase();
    const filteredCats = useMemo(() => {
        if (!q) return sortedCats;
        return sortedCats.filter(cat => grouped[cat]?.some(m => m.menuName.toLowerCase().includes(q)));
    }, [q, sortedCats, grouped]);

    const filterItems = useCallback((items) => {
        if (!q) return items;
        return items.filter(m => m.menuName.toLowerCase().includes(q));
    }, [q]);

    // ── Auto-expand active category ───────────────────────────────
    useEffect(() => {
        sortedCats.forEach(cat => {
            if (grouped[cat]?.some(m => location.pathname === m.path)) {
                setOpenGroups(prev => prev[cat] ? prev : { ...prev, [cat]: true });
            }
        });
    }, [location.pathname]);

    // ── Auto-expand on search ─────────────────────────────────────
    useEffect(() => {
        if (q) {
            const exp = {};
            filteredCats.forEach(c => (exp[c] = true));
            setOpenGroups(prev => ({ ...prev, ...exp }));
        }
    }, [q]);

    // ── Keyboard: Cmd+K → search ──────────────────────────────────
    useEffect(() => {
        const h = (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
                e.preventDefault();
                if (collapsed) setCollapsed(false);
                setShowSearch(true);
                setTimeout(() => searchRef.current?.focus(), 80);
            }
            if (e.key === 'Escape') { setSearch(''); setShowSearch(false); }
        };
        window.addEventListener('keydown', h);
        return () => window.removeEventListener('keydown', h);
    }, [collapsed]);

    // ── Close mobile on navigate ──────────────────────────────────
    useEffect(() => { if (isMobile) setMobileOpen(false); }, [location.pathname]);

    // ── Navigate + track recent ───────────────────────────────────
    const go = useCallback((menu) => {
        navigate(menu.path);
        setSearch('');
        const Icon = LucideIcons[menu.iconKey] || LucideIcons.Circle;
        setRecent(pushRecent({ path: menu.path, name: menu.menuName, iconKey: menu.iconKey }));
    }, [navigate]);

    const handleLogout = () => { logout(); navigate('/login'); };

    const toggleGroup = useCallback((cat) => {
        setOpenGroups(prev => ({ ...prev, [cat]: !prev[cat] }));
    }, []);

    // ── Page title for topbar ─────────────────────────────────────
    const currentMenu = useMemo(() => {
        for (const cat of sortedCats) {
            const found = (grouped[cat] || []).find(m => location.pathname === m.path);
            if (found) return { ...found, category: cat };
        }
        return null;
    }, [location.pathname, grouped, sortedCats]);

    // ── Sidebar content ───────────────────────────────────────────
    const sidebar = (
        <div style={{
            display: 'flex', flexDirection: 'column', height: '100vh',
            width: w, background: S.bg, color: S.text,
            borderRight: `1px solid ${S.border}`,
            transition: 'width 0.22s cubic-bezier(0.4,0,0.2,1)',
            overflow: 'hidden', flexShrink: 0,
            position: isMobile ? 'relative' : 'fixed',
            top: 0, left: 0, zIndex: 200,
        }}>

            {/* ── Brand ── */}
            <div style={{ padding: collapsed ? '14px 0' : '14px', display: 'flex', alignItems: 'center', justifyContent: collapsed ? 'center' : 'space-between', borderBottom: `1px solid ${S.border}`, flexShrink: 0, minHeight: 58 }}>
                {!collapsed && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer', flex: 1, minWidth: 0 }} onClick={() => navigate('/dashboard')}>
                        <div style={{ width: 32, height: 32, borderRadius: 9, background: '#3b82f6', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15, fontWeight: 800, color: '#fff', flexShrink: 0 }}>A</div>
                        <div style={{ minWidth: 0 }}>
                            <div style={{ fontSize: 14, fontWeight: 700, color: '#fff', letterSpacing: '-0.02em', lineHeight: 1.2 }}>Acquira</div>
                            <div style={{ fontSize: 10, color: S.textSec, textTransform: 'uppercase', letterSpacing: '0.07em', marginTop: 1 }}>CMS Platform</div>
                        </div>
                    </div>
                )}
                {collapsed && (
                    <div style={{ width: 32, height: 32, borderRadius: 9, background: '#3b82f6', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15, fontWeight: 800, color: '#fff', cursor: 'pointer' }} onClick={() => navigate('/dashboard')}>A</div>
                )}
                <button
                    onClick={() => setCollapsed(v => !v)}
                    title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
                    style={{ background: 'none', border: 'none', cursor: 'pointer', color: S.textSec, padding: collapsed ? '4px 0 0' : '4px 0 0 8px', display: 'flex', flexShrink: 0 }}
                >
                    {collapsed
                        ? <LucideIcons.PanelLeftOpen size={14} />
                        : <LucideIcons.PanelLeftClose size={14} />}
                </button>
            </div>

            {/* ── Tenant switcher ── */}
            {!collapsed && (
                <div style={{ padding: '8px 10px', borderBottom: `1px solid ${S.border}`, flexShrink: 0 }}>
                    <TenantSwitcher />
                </div>
            )}

            {/* ── Search ── */}
            {!collapsed && (
                <div style={{ padding: '8px 10px', borderBottom: `1px solid ${S.border}`, flexShrink: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'rgba(255,255,255,0.05)', border: `1px solid ${S.border}`, borderRadius: 8, padding: '6px 10px', cursor: 'text' }}
                        onClick={() => { setShowSearch(true); searchRef.current?.focus(); }}>
                        <LucideIcons.Search size={12} color={S.textSec} />
                        <input
                            ref={searchRef}
                            value={search}
                            onChange={e => setSearch(e.target.value)}
                            placeholder="Search…  ⌘K"
                            style={{ background: 'none', border: 'none', outline: 'none', color: S.text, fontSize: 12, flex: 1, caretColor: S.accent }}
                        />
                        {search && (
                            <button onClick={() => setSearch('')} style={{ background: 'none', border: 'none', cursor: 'pointer', color: S.textSec, padding: 0, display: 'flex' }}>
                                <LucideIcons.X size={11} />
                            </button>
                        )}
                    </div>
                </div>
            )}

            {/* ── Navigation ── */}
            <div style={{ flex: 1, overflowY: 'auto', overflowX: 'hidden', padding: collapsed ? '8px 6px' : '8px 8px', scrollbarWidth: 'thin', scrollbarColor: 'rgba(255,255,255,0.08) transparent' }}>

                {/* ── Recent pages ── */}
                {!collapsed && !search && recent.length > 0 && (
                    <>
                        <SectionLabel label="Recent" />
                        {recent.map(r => {
                            const active = location.pathname === r.path;
                            const Icon = LucideIcons[r.iconKey] || LucideIcons.Clock;
                            return (
                                <button key={r.path} onClick={() => navigate(r.path)}
                                    style={{
                                        display: 'flex', alignItems: 'center', gap: 8, width: '100%',
                                        padding: '6px 10px', background: active ? S.bgActive : 'transparent',
                                        border: 'none', borderRadius: 7, cursor: 'pointer', marginBottom: 1,
                                    }}
                                    onMouseEnter={e => { if (!active) e.currentTarget.style.background = S.bgHov; }}
                                    onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent'; }}
                                >
                                    <LucideIcons.Clock size={12} color={S.textMuted} style={{ flexShrink: 0 }} />
                                    <span style={{ fontSize: 12, color: active ? '#93c5fd' : 'rgba(255,255,255,0.5)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.name}</span>
                                </button>
                            );
                        })}
                        <Divider />
                    </>
                )}

                {/* ── Categories ── */}
                {filteredCats.map((cat, catIdx) => {
                    const items = filterItems(grouped[cat] || []);
                    if (!items.length) return null;
                    const meta = CAT_META[cat] || CAT_META.GENERAL;
                    const CatIcon = LucideIcons[meta.icon] || LucideIcons.Folder;
                    const isOpen = openGroups[cat] ?? false;
                    const hasActive = items.some(m => location.pathname === m.path);
                    const isPrimary = PRIMARY_CATS.has(cat);

                    if (collapsed) {
                        // Collapsed: show icon per category with tooltips
                        return (
                            <React.Fragment key={cat}>
                                {catIdx > 0 && !PRIMARY_CATS.has(filteredCats[catIdx - 1]) && isPrimary && <Divider />}
                                <div style={{ marginBottom: 2 }}>
                                    <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 2 }}>
                                        <div style={{ width: 4, height: 4, borderRadius: '50%', background: hasActive ? meta.color : 'transparent', margin: '2px 0' }} />
                                    </div>
                                    {items.map(m => {
                                        const active = location.pathname === m.path;
                                        const MIcon = LucideIcons[m.iconKey] || LucideIcons.Circle;
                                        return (
                                            <div key={m.menuId || m.path} title={m.menuName} style={{ display: 'flex', justifyContent: 'center', marginBottom: 1 }}>
                                                <button onClick={() => go(m)} style={{
                                                    width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                    background: active ? S.bgActive : 'transparent', border: 'none', borderRadius: 8, cursor: 'pointer',
                                                }}
                                                onMouseEnter={e => { if (!active) e.currentTarget.style.background = S.bgHov; }}
                                                onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent'; }}
                                                >
                                                    <MIcon size={16} color={active ? meta.color : 'rgba(255,255,255,0.35)'} />
                                                </button>
                                            </div>
                                        );
                                    })}
                                </div>
                            </React.Fragment>
                        );
                    }

                    // Expanded: category header + collapsible items
                    return (
                        <React.Fragment key={cat}>
                            {catIdx > 0 && !isPrimary && PRIMARY_CATS.has(filteredCats[catIdx - 1]) && <Divider />}
                            <button
                                onClick={() => toggleGroup(cat)}
                                style={{
                                    display: 'flex', alignItems: 'center', gap: 8, width: '100%',
                                    padding: '5px 8px', background: 'transparent', border: 'none',
                                    borderRadius: 8, cursor: 'pointer', marginTop: catIdx > 0 ? 2 : 0,
                                    opacity: isPrimary ? 1 : 0.7,
                                }}
                                onMouseEnter={e => { e.currentTarget.style.background = S.bgHov; }}
                                onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; }}
                            >
                                <div style={{ width: 22, height: 22, borderRadius: 6, background: hasActive ? meta.bg : 'rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                                    <CatIcon size={12} color={hasActive ? meta.color : S.textSec} />
                                </div>
                                <span style={{ flex: 1, fontSize: 12, fontWeight: 600, color: hasActive ? meta.color : S.textSec, textAlign: 'left', letterSpacing: '-0.01em' }}>
                                    {cat.charAt(0) + cat.slice(1).toLowerCase().replace('mgt', 'management').replace('data integration', 'Data integration')}
                                </span>
                                {isOpen
                                    ? <LucideIcons.ChevronDown size={11} color={S.textMuted} />
                                    : <LucideIcons.ChevronRight size={11} color={S.textMuted} />}
                            </button>

                            {isOpen && (
                                <div style={{ paddingLeft: 8, marginBottom: 2 }}>
                                    {items.map(m => (
                                        <NavItem key={m.menuId || m.path}
                                            menu={m}
                                            active={location.pathname === m.path}
                                            color={meta.color}
                                            collapsed={false}
                                            onClick={() => go(m)}
                                        />
                                    ))}
                                </div>
                            )}
                        </React.Fragment>
                    );
                })}

                {/* ── No search results ── */}
                {search && filteredCats.length === 0 && (
                    <div style={{ textAlign: 'center', padding: '40px 16px', color: S.textSec }}>
                        <LucideIcons.SearchX size={24} style={{ opacity: 0.4, marginBottom: 8 }} />
                        <div style={{ fontSize: 12 }}>No results for "{search}"</div>
                    </div>
                )}
            </div>

            {/* ── Footer ── */}
            <div style={{ borderTop: `1px solid ${S.border}`, padding: collapsed ? '10px 6px' : '10px', flexShrink: 0 }}>
                {!collapsed ? (
                    <>
                        {/* User row */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '6px 8px', marginBottom: 4 }}>
                            <div style={{ width: 30, height: 30, borderRadius: '50%', background: '#3b82f6', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, fontWeight: 700, color: '#fff', flexShrink: 0 }}>
                                {(username || 'U')[0].toUpperCase()}
                            </div>
                            <div style={{ overflow: 'hidden', flex: 1 }}>
                                <div style={{ fontSize: 13, fontWeight: 600, color: S.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{username || 'User'}</div>
                                <div style={{ fontSize: 11, color: S.textSec, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', marginTop: 1 }}>{activeTenant?.bankName || 'Administrator'}</div>
                            </div>
                            {/* Theme toggle icon */}
                            <button onClick={toggleTheme} title={isDark ? 'Light mode' : 'Dark mode'}
                                style={{ background: 'none', border: 'none', cursor: 'pointer', color: S.textSec, padding: 4, display: 'flex', borderRadius: 6, flexShrink: 0 }}
                                onMouseEnter={e => { e.currentTarget.style.color = S.text; e.currentTarget.style.background = S.bgHov; }}
                                onMouseLeave={e => { e.currentTarget.style.color = S.textSec; e.currentTarget.style.background = 'none'; }}
                            >
                                {isDark ? <LucideIcons.Sun size={14} /> : <LucideIcons.Moon size={14} />}
                            </button>
                        </div>

                        {/* Action row */}
                        <div style={{ display: 'flex', gap: 4 }}>
                            <button onClick={() => navigate('/change-password')}
                                style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 6, padding: '6px 8px', background: 'none', border: 'none', borderRadius: 7, cursor: 'pointer', color: S.textSec, fontSize: 12 }}
                                onMouseEnter={e => { e.currentTarget.style.background = S.bgHov; e.currentTarget.style.color = S.text; }}
                                onMouseLeave={e => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = S.textSec; }}
                            >
                                <LucideIcons.KeyRound size={13} /> Password
                            </button>
                            <button onClick={handleLogout}
                                style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 6, padding: '6px 8px', background: 'none', border: 'none', borderRadius: 7, cursor: 'pointer', color: 'rgba(248,113,113,0.7)', fontSize: 12 }}
                                onMouseEnter={e => { e.currentTarget.style.background = 'rgba(248,113,113,0.08)'; e.currentTarget.style.color = '#fca5a5'; }}
                                onMouseLeave={e => { e.currentTarget.style.background = 'none'; e.currentTarget.style.color = 'rgba(248,113,113,0.7)'; }}
                            >
                                <LucideIcons.LogOut size={13} /> Sign out
                            </button>
                        </div>
                    </>
                ) : (
                    // Collapsed footer
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                        <div title={username} style={{ width: 30, height: 30, borderRadius: '50%', background: '#3b82f6', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, fontWeight: 700, color: '#fff', cursor: 'pointer' }}>
                            {(username || 'U')[0].toUpperCase()}
                        </div>
                        {[
                            { icon: isDark ? LucideIcons.Sun : LucideIcons.Moon, title: 'Toggle theme',    action: toggleTheme,                       color: S.textSec },
                            { icon: LucideIcons.KeyRound,                        title: 'Change password', action: () => navigate('/change-password'), color: S.textSec },
                            { icon: LucideIcons.LogOut,                          title: 'Sign out',        action: handleLogout,                      color: '#f87171' },
                        ].map(({ icon: Icon, title, action, color }) => (
                            <button key={title} onClick={action} title={title}
                                style={{ width: 32, height: 32, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'none', border: 'none', borderRadius: 7, cursor: 'pointer', color }}
                                onMouseEnter={e => { e.currentTarget.style.background = S.bgHov; }}
                                onMouseLeave={e => { e.currentTarget.style.background = 'none'; }}
                            >
                                <Icon size={14} />
                            </button>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );

    // ── Topbar (shows breadcrumb + tenant + mobile menu) ──────────
    const topbar = (
        <div style={{
            height: 52, background: 'var(--color-background-primary, #fff)',
            borderBottom: '0.5px solid var(--color-border-tertiary, rgba(0,0,0,0.08))',
            display: 'flex', alignItems: 'center', padding: '0 20px', gap: 10,
            position: 'sticky', top: 0, zIndex: 100, flexShrink: 0,
        }}>
            {/* Mobile hamburger */}
            {isMobile && (
                <button onClick={() => setMobileOpen(v => !v)}
                    style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', color: 'var(--color-text-secondary)' }}>
                    <LucideIcons.Menu size={20} />
                </button>
            )}

            {/* Breadcrumb */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, minWidth: 0 }}>
                {currentMenu ? (
                    <>
                        <span style={{ fontSize: 12, color: 'var(--color-text-tertiary)' }}>
                            {currentMenu.category.charAt(0) + currentMenu.category.slice(1).toLowerCase()}
                        </span>
                        <LucideIcons.ChevronRight size={12} color="var(--color-text-tertiary)" />
                        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                            {currentMenu.menuName}
                        </span>
                    </>
                ) : (
                    <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)' }}>Acquira</span>
                )}
            </div>

            {/* Right actions */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
                {/* Tenant pill */}
                {activeTenant?.bankName && (
                    <div style={{
                        display: 'flex', alignItems: 'center', gap: 6,
                        padding: '4px 10px', borderRadius: 7,
                        background: 'var(--color-background-secondary)',
                        border: '0.5px solid var(--color-border-tertiary)',
                        fontSize: 11, fontWeight: 600, color: 'var(--color-text-secondary)',
                    }}>
                        <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#10b981', flexShrink: 0 }} />
                        {activeTenant.bankName}
                    </div>
                )}
                {/* Keyboard shortcut hint */}
                <button
                    title="Keyboard shortcuts (?)"
                    onClick={() => window.dispatchEvent(new KeyboardEvent('keydown', { key: '?' }))}
                    style={{ width: 32, height: 32, borderRadius: 8, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--color-background-secondary)', border: '0.5px solid var(--color-border-tertiary)', cursor: 'pointer', fontSize: 13, fontWeight: 700, color: 'var(--color-text-secondary)' }}
                >?</button>
                {/* Notification bell */}
                <NotificationBell />
            </div>
        </div>
    );

    // ── Render ─────────────────────────────────────────────────────
    return (
        <div style={{ display: 'flex', minHeight: '100vh' }}>
            <ShortcutsPanel navigate={navigate} />
            {/* Desktop sidebar — fixed position */}
            {!isMobile && sidebar}

            {/* Mobile drawer overlay */}
            {isMobile && mobileOpen && (
                <>
                    <div onClick={() => setMobileOpen(false)}
                        style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 199 }} />
                    <div style={{ position: 'fixed', top: 0, left: 0, zIndex: 200 }}>{sidebar}</div>
                </>
            )}

            {/* Main content area — offset by sidebar width */}
            <div style={{
                flex: 1,
                marginLeft: isMobile ? 0 : w,
                transition: 'margin-left 0.22s cubic-bezier(0.4,0,0.2,1)',
                display: 'flex', flexDirection: 'column',
                minHeight: '100vh',
                background: 'var(--color-background-tertiary, #f8fafc)',
                minWidth: 0,
            }}>
                {topbar}
                <div key={`tenant-${activeTenantId}-${tenantVersion}`} style={{ flex: 1 }}>
                    <Outlet />
                </div>
            </div>
        </div>
    );
};

export default Layout;
