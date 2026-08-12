import React, { useMemo, useState, useCallback, useEffect, useRef } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Drawer, Tooltip, useMediaQuery } from '@mui/material';
import * as LucideIcons from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import { useTheme } from '../contexts/ThemeContext';
import { prefetchRoute, prefetchCommonRoutes } from '../routePrefetch';
import TenantSwitcher from './TenantSwitcher';
import NotificationBell from './NotificationBell';
import ShortcutsPanel from './ShortcutsPanel';
import DataFreshness from './DataFreshness';
import './sidebar.css';

// ── Constants ──────────────────────────────────────────────────────
const DRAWER_W    = 232;
const COLLAPSE_W  = 56;
const RECENT_KEY  = 'acquira_recent_pages';
const COLLAPSE_KEY = 'acquira_sb_collapsed';
const RECENT_MAX  = 5;

// Environment tag shown beside the wordmark (PROD / UAT).
const ENV_TAG = import.meta.env.VITE_ENV_LABEL || (import.meta.env.PROD ? 'PROD' : 'UAT');

// ── Category grouping ──────────────────────────────────────────────
// Dark-rail sidebar: groups are what the user is DOING (reporting first,
// administration after), each under a mono eyebrow label. One accent —
// teal — reserved for the active marker.
const CAT_ORDER = [
    'EXECUTIVE','BUSINESS','SALES','FINANCE',
    'MERCHANT MGT','OPERATIONS','ADMINISTRATION','DATA INTEGRATION','GENERAL',
];
const ANALYTICS_CATS = new Set(['EXECUTIVE','BUSINESS','SALES','FINANCE']);

const catLabel = (cat) => cat
    .replace('MGT', 'MANAGEMENT')
    .replace('DATA INTEGRATION', 'DATA');

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
function loadCollapsed() {
    try { return localStorage.getItem(COLLAPSE_KEY) === '1'; }
    catch { return false; }
}

// ── Small components ───────────────────────────────────────────────
// Icons live in the sidebar ONLY (the collapsed rail depends on them):
// one 18px stroke set at 1.5px.
const NavItem = ({ menu, active, muted, onClick }) => {
    const Icon = LucideIcons[menu.iconKey] || LucideIcons.Circle;
    return (
        <button
            className={`sb__item${active ? ' sb__item--active' : ''}${muted ? ' sb__item--muted' : ''}`}
            onClick={onClick}
            onMouseEnter={() => prefetchRoute(menu.path)}
            onFocus={() => prefetchRoute(menu.path)}
            aria-current={active ? 'page' : undefined}
        >
            <Icon size={18} strokeWidth={1.5} />
            <span className="sb__item-label">{menu.menuName}</span>
        </button>
    );
};

// ── Main Layout ────────────────────────────────────────────────────
const Layout = () => {
    const navigate  = useNavigate();
    const location  = useLocation();
    const { menus, logout, username, activeTenant, activeTenantId, tenantVersion } = useAuth();
    const { isDark, toggleTheme } = useTheme();
    const isMobile  = useMediaQuery('(max-width:768px)');

    const [collapsed,  setCollapsedState] = useState(loadCollapsed);
    const [search,     setSearch]     = useState('');
    const [mobileOpen, setMobileOpen] = useState(false);
    const [recent,     setRecent]     = useState(loadRecent);
    const [userMenu,   setUserMenu]   = useState(false);
    const searchRef = useRef(null);
    const footerRef = useRef(null);

    // Collapse state persists across sessions.
    const setCollapsed = useCallback((next) => {
        setCollapsedState(prev => {
            const v = typeof next === 'function' ? next(prev) : next;
            try { localStorage.setItem(COLLAPSE_KEY, v ? '1' : '0'); } catch { /* ignore */ }
            return v;
        });
    }, []);

    const w = isMobile ? DRAWER_W : (collapsed ? COLLAPSE_W : DRAWER_W);

    // ── Idle-prefetch the common landing pages once after first paint ──
    useEffect(() => { prefetchCommonRoutes(); }, []);

    // ── Auto-collapse inside the Settings hub ─────────────────────
    // Settings has its own section nav, so the full app sidebar next to it
    // reads as two sidebars. Collapse to the icon rail while in /settings,
    // and restore whatever the user had when they leave.
    const inSettings = location.pathname.startsWith('/settings');
    const preSettingsCollapsed = useRef(null);
    useEffect(() => {
        if (isMobile) return;
        if (inSettings) {
            if (preSettingsCollapsed.current === null) {
                preSettingsCollapsed.current = collapsed;
                setCollapsedState(true);
            }
        } else if (preSettingsCollapsed.current !== null) {
            setCollapsedState(preSettingsCollapsed.current);
            preSettingsCollapsed.current = null;
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [inSettings, isMobile]);

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

    // ── Keyboard: Cmd+K → search ──────────────────────────────────
    useEffect(() => {
        const h = (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
                e.preventDefault();
                if (collapsed) setCollapsed(false);
                setTimeout(() => searchRef.current?.focus(), 80);
            }
            if (e.key === 'Escape') { setSearch(''); setUserMenu(false); }
        };
        window.addEventListener('keydown', h);
        return () => window.removeEventListener('keydown', h);
    }, [collapsed, setCollapsed]);

    // ── Close user menu on outside click ──────────────────────────
    useEffect(() => {
        if (!userMenu) return;
        const h = (e) => {
            if (footerRef.current && !footerRef.current.contains(e.target)) setUserMenu(false);
        };
        document.addEventListener('mousedown', h);
        return () => document.removeEventListener('mousedown', h);
    }, [userMenu]);

    // ── Close mobile on navigate ──────────────────────────────────
    useEffect(() => { if (isMobile) setMobileOpen(false); }, [location.pathname]);

    // ── Navigate + track recent ───────────────────────────────────
    const go = useCallback((menu) => {
        navigate(menu.path);
        setSearch('');
        setRecent(pushRecent({ path: menu.path, name: menu.menuName, iconKey: menu.iconKey }));
    }, [navigate]);

    const handleLogout = () => { logout(); navigate('/login'); };

    // ── Page title for topbar ─────────────────────────────────────
    const currentMenu = useMemo(() => {
        for (const cat of sortedCats) {
            const found = (grouped[cat] || []).find(m => location.pathname === m.path);
            if (found) return { ...found, category: cat };
        }
        return null;
    }, [location.pathname, grouped, sortedCats]);

    // ── Expanded group: mono eyebrow + items, no dividers ─────────
    const renderGroup = (cat) => {
        const items = filterItems(grouped[cat] || []);
        if (!items.length) return null;
        return (
            <React.Fragment key={cat}>
                <div className="sb__zone">{catLabel(cat)}</div>
                {items.map(m => (
                    <NavItem key={m.menuId || m.path}
                        menu={m}
                        active={location.pathname === m.path}
                        onClick={() => go(m)}
                    />
                ))}
            </React.Fragment>
        );
    };

    // ── Collapsed rail: icon list, label as tooltip on the right ──
    const renderCollapsedZone = (cats) => cats.flatMap(cat =>
        (grouped[cat] || []).map(m => {
            const active = location.pathname === m.path;
            const MIcon = LucideIcons[m.iconKey] || LucideIcons.Circle;
            return (
                <Tooltip key={m.menuId || m.path} title={m.menuName} placement="right" arrow>
                    <button
                        className={`sb__icon-item${active ? ' sb__icon-item--active' : ''}`}
                        aria-label={m.menuName}
                        aria-current={active ? 'page' : undefined}
                        onClick={() => go(m)}
                        onMouseEnter={() => prefetchRoute(m.path)}
                    >
                        <MIcon size={18} strokeWidth={1.5} />
                    </button>
                </Tooltip>
            );
        })
    );

    const analyticsCats = filteredCats.filter(c => ANALYTICS_CATS.has(c));
    const manageCats    = filteredCats.filter(c => !ANALYTICS_CATS.has(c));

    // ── Sidebar content (lives inside the permanent Drawer paper) ──
    const sidebarContent = (
        <>
            {/* ── Header: wordmark + environment tag, 56px ── */}
            <div className="sb__header">
                <button className="sb__brand" onClick={() => navigate('/dashboard')} title="Go to dashboard" aria-label="Go to dashboard">
                    {(!collapsed || isMobile) ? (
                        <>
                            <span className="sb__brand-name">NEXUS</span>
                            <span className="sb__env">{ENV_TAG}</span>
                        </>
                    ) : (
                        <span className="sb__brand-name">NX</span>
                    )}
                </button>
            </div>

            {/* ── Tenant switcher ── */}
            {(!collapsed || isMobile) && (
                <div style={{ padding: '8px 12px 0', flexShrink: 0 }}>
                    <TenantSwitcher />
                </div>
            )}

            {/* ── Search ── */}
            {(!collapsed || isMobile) && (
                <div className="sb__search" onClick={() => searchRef.current?.focus()}>
                    <LucideIcons.Search size={13} color="var(--rail-eyebrow)" />
                    <input
                        ref={searchRef}
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        placeholder="Search"
                        aria-label="Search navigation"
                    />
                    {search ? (
                        <button onClick={() => setSearch('')} aria-label="Clear search" style={{ display: 'flex', padding: 2, color: 'var(--rail-eyebrow)' }}>
                            <LucideIcons.X size={11} />
                        </button>
                    ) : (
                        <span className="sb__kbd">⌘K</span>
                    )}
                </div>
            )}

            {/* ── Navigation ── */}
            <div className="sb__nav">
                {(!collapsed || isMobile) ? (
                    <>
                        {/* Recent pages */}
                        {!search && recent.length > 0 && (
                            <>
                                <div className="sb__zone">Recent</div>
                                {recent.slice(0, 3).map(r => (
                                    <NavItem key={r.path}
                                        menu={{ ...r, menuName: r.name }}
                                        active={location.pathname === r.path}
                                        muted
                                        onClick={() => navigate(r.path)}
                                    />
                                ))}
                            </>
                        )}

                        {analyticsCats.map(renderGroup)}
                        {manageCats.map(renderGroup)}

                        {search && filteredCats.length === 0 && (
                            <div className="sb__empty">
                                <div>No results for "{search}"</div>
                            </div>
                        )}
                    </>
                ) : (
                    <>
                        {renderCollapsedZone(analyticsCats)}
                        {manageCats.length > 0 && <div className="sb__zone-dot" />}
                        {renderCollapsedZone(manageCats)}
                    </>
                )}
            </div>

            {/* ── Footer: user → data freshness → collapse toggle ── */}
            <div className="sb__footer" ref={footerRef}>
                {(!collapsed || isMobile) ? (
                    <>
                        {userMenu && (
                            <div className="sb__menu" role="menu">
                                <button className="sb__menu-item" role="menuitem" onClick={() => { setUserMenu(false); navigate('/change-password'); }}>
                                    <LucideIcons.KeyRound size={14} /> Change password
                                </button>
                                <button className="sb__menu-item" role="menuitem" onClick={() => { toggleTheme(); }}>
                                    {isDark ? <LucideIcons.Sun size={14} /> : <LucideIcons.Moon size={14} />}
                                    {isDark ? 'Light mode' : 'Dark mode'}
                                </button>
                                <div className="sb__menu-sep" />
                                <button className="sb__menu-item sb__menu-item--danger" role="menuitem" onClick={handleLogout}>
                                    <LucideIcons.LogOut size={14} /> Sign out
                                </button>
                            </div>
                        )}
                        <button
                            className="sb__user"
                            onClick={() => setUserMenu(v => !v)}
                            aria-haspopup="menu"
                            aria-expanded={userMenu}
                        >
                            <div className="sb__avatar">{(username || 'U')[0].toUpperCase()}</div>
                            <div style={{ flex: 1, minWidth: 0 }}>
                                <div className="sb__user-name">{username || 'User'}</div>
                                <div className="sb__user-sub">{activeTenant?.bankName || 'Administrator'}</div>
                            </div>
                            <LucideIcons.MoreHorizontal size={15} color="var(--rail-eyebrow)" />
                        </button>
                        <DataFreshness />
                        {!isMobile && (
                            <button className="sb__collapse" onClick={() => setCollapsed(true)} aria-label="Collapse sidebar">
                                <LucideIcons.PanelLeftClose size={18} strokeWidth={1.5} />
                                Collapse
                            </button>
                        )}
                    </>
                ) : (
                    <>
                        <Tooltip title={username || 'User'} placement="right" arrow>
                            <div className="sb__avatar">{(username || 'U')[0].toUpperCase()}</div>
                        </Tooltip>
                        <Tooltip title={isDark ? 'Light mode' : 'Dark mode'} placement="right" arrow>
                            <button className="sb__icon-item" aria-label="Toggle theme" onClick={toggleTheme}>
                                {isDark ? <LucideIcons.Sun size={18} strokeWidth={1.5} /> : <LucideIcons.Moon size={18} strokeWidth={1.5} />}
                            </button>
                        </Tooltip>
                        <Tooltip title="Sign out" placement="right" arrow>
                            <button className="sb__icon-item" aria-label="Sign out" onClick={handleLogout}>
                                <LucideIcons.LogOut size={18} strokeWidth={1.5} />
                            </button>
                        </Tooltip>
                        <DataFreshness collapsed />
                        <Tooltip title="Expand sidebar" placement="right" arrow>
                            <button className="sb__collapse" onClick={() => setCollapsed(false)} aria-label="Expand sidebar">
                                <LucideIcons.PanelLeftOpen size={18} strokeWidth={1.5} />
                            </button>
                        </Tooltip>
                    </>
                )}
            </div>
        </>
    );

    // ── Topbar (breadcrumb + tenant + mobile menu) ────────────────
    const topbar = (
        <div style={{
            height: 52, background: 'var(--bg-card)',
            borderBottom: '1px solid var(--border)',
            display: 'flex', alignItems: 'center', padding: '0 20px', gap: 10,
            position: 'sticky', top: 0, zIndex: 100, flexShrink: 0,
        }}>
            {/* Mobile hamburger */}
            {isMobile && (
                <button onClick={() => setMobileOpen(v => !v)}
                    style={{ background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', color: 'var(--text-secondary)' }}>
                    <LucideIcons.Menu size={20} />
                </button>
            )}

            {/* Breadcrumb */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, minWidth: 0 }}>
                {currentMenu ? (
                    <>
                        <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                            {currentMenu.category.charAt(0) + currentMenu.category.slice(1).toLowerCase()}
                        </span>
                        <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>/</span>
                        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                            {currentMenu.menuName}
                        </span>
                    </>
                ) : (
                    <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)' }}>NEXUS</span>
                )}
            </div>

            {/* Right actions */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
                {/* Tenant tag */}
                {activeTenant?.bankName && (
                    <div style={{
                        display: 'flex', alignItems: 'center', gap: 6,
                        padding: '3px 8px', borderRadius: 'var(--radius-chip)',
                        background: 'var(--wash)',
                        fontFamily: 'var(--font-mono)', fontSize: 11,
                        textTransform: 'uppercase', letterSpacing: '0.02em',
                        color: 'var(--primary)',
                    }}>
                        {activeTenant.bankName}
                    </div>
                )}
                {/* Keyboard shortcut hint */}
                <button
                    title="Keyboard shortcuts (?)"
                    onClick={() => window.dispatchEvent(new KeyboardEvent('keydown', { key: '?' }))}
                    style={{ width: 28, height: 28, borderRadius: 'var(--radius-sm)', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg-card)', border: '1px solid var(--border)', cursor: 'pointer', fontSize: 13, fontWeight: 600, color: 'var(--text-secondary)' }}
                >?</button>
                {/* Notification bell */}
                <NotificationBell />
            </div>
        </div>
    );

    // ── Render ─────────────────────────────────────────────────────
    return (
        <div style={{ display: 'flex', minHeight: 'var(--vh100, 100vh)' }}>
            <ShortcutsPanel navigate={navigate} />

            {/* Desktop sidebar — permanent Drawer, dark rail */}
            {!isMobile && (
                <Drawer
                    variant="permanent"
                    open
                    sx={{
                        width: w, flexShrink: 0,
                        '& .MuiDrawer-paper': {
                            width: w, border: 'none', overflow: 'hidden',
                            transition: 'width 180ms ease-out',
                        },
                    }}
                    PaperProps={{ className: `sb${collapsed ? ' sb--collapsed' : ''}`, component: 'nav', 'aria-label': 'Main navigation' }}
                >
                    {sidebarContent}
                </Drawer>
            )}

            {/* Mobile drawer overlay */}
            {isMobile && mobileOpen && (
                <>
                    <div onClick={() => setMobileOpen(false)}
                        style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 199 }} />
                    <nav aria-label="Main navigation" className="sb"
                        style={{ position: 'fixed', top: 0, left: 0, zIndex: 200, width: DRAWER_W }}>
                        {sidebarContent}
                    </nav>
                </>
            )}

            {/* Main content area */}
            <div style={{
                flex: 1,
                transition: 'margin-left 180ms ease-out',
                display: 'flex', flexDirection: 'column',
                minHeight: 'var(--vh100, 100vh)',
                background: 'var(--bg)',
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
