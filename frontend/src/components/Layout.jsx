import React, { useMemo, useState, useCallback, useEffect, useRef } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useMediaQuery } from '@mui/material';
import * as LucideIcons from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import { useTheme } from '../contexts/ThemeContext';
import { prefetchRoute, prefetchCommonRoutes } from '../routePrefetch';
import TenantSwitcher from './TenantSwitcher';
import NotificationBell from './NotificationBell';
import ShortcutsPanel from './ShortcutsPanel';
import './sidebar.css';

// ── Constants ──────────────────────────────────────────────────────
const DRAWER_W    = 240;
const COLLAPSE_W  = 64;
const RECENT_KEY  = 'acquira_recent_pages';
const RECENT_MAX  = 5;

// ── Category grouping ──────────────────────────────────────────────
// "Quiet ledger" sidebar: one accent, no per-category colour. Categories
// keep their identity through order and grouping into two zones:
//   Analytics — daily-use reporting, open by default.
//   Manage    — administrative and setup areas, collapsed by default.
const CAT_ORDER = [
    'EXECUTIVE','BUSINESS','SALES','FINANCE',
    'MERCHANT MGT','OPERATIONS','ADMINISTRATION','DATA INTEGRATION','GENERAL',
];
const ANALYTICS_CATS = new Set(['EXECUTIVE','BUSINESS','SALES','FINANCE']);

const catLabel = (cat) => cat.charAt(0) + cat.slice(1).toLowerCase()
    .replace('mgt', 'management')
    .replace('data integration', 'Data integration');

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
            <Icon size={15} strokeWidth={active ? 2 : 1.8} />
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

    const [collapsed,  setCollapsed]  = useState(false);
    const [openGroups, setOpenGroups] = useState({});
    const [search,     setSearch]     = useState('');
    const [mobileOpen, setMobileOpen] = useState(false);
    const [recent,     setRecent]     = useState(loadRecent);
    const [userMenu,   setUserMenu]   = useState(false);
    const searchRef = useRef(null);
    const footerRef = useRef(null);

    const w = isMobile ? DRAWER_W : (collapsed ? COLLAPSE_W : DRAWER_W);

    // ── Idle-prefetch the common landing pages once after first paint ──
    useEffect(() => { prefetchCommonRoutes(); }, []);

    // ── Auto-collapse inside the Settings hub ─────────────────────
    // Settings has its own section nav, so the full app sidebar next to it
    // reads as two sidebars. Collapse to the icon rail while in /settings,
    // and restore whatever the user had when they leave. The ref keeps the
    // user's own preference; manual toggling inside Settings still works.
    const inSettings = location.pathname.startsWith('/settings');
    const preSettingsCollapsed = useRef(null);
    useEffect(() => {
        if (isMobile) return;
        if (inSettings) {
            if (preSettingsCollapsed.current === null) {
                preSettingsCollapsed.current = collapsed;
                setCollapsed(true);
            }
        } else if (preSettingsCollapsed.current !== null) {
            setCollapsed(preSettingsCollapsed.current);
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

    // Analytics groups start open; Manage groups start closed.
    const isGroupOpen = useCallback(
        (cat) => openGroups[cat] ?? ANALYTICS_CATS.has(cat),
        [openGroups]
    );
    const toggleGroup = useCallback((cat) => {
        setOpenGroups(prev => ({ ...prev, [cat]: !(prev[cat] ?? ANALYTICS_CATS.has(cat)) }));
    }, []);

    // ── Auto-expand active category ───────────────────────────────
    useEffect(() => {
        sortedCats.forEach(cat => {
            if (grouped[cat]?.some(m => location.pathname === m.path)) {
                setOpenGroups(prev => (prev[cat] ?? ANALYTICS_CATS.has(cat)) ? prev : { ...prev, [cat]: true });
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
                setTimeout(() => searchRef.current?.focus(), 80);
            }
            if (e.key === 'Escape') { setSearch(''); setUserMenu(false); }
        };
        window.addEventListener('keydown', h);
        return () => window.removeEventListener('keydown', h);
    }, [collapsed]);

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

    const analyticsCats = filteredCats.filter(c => ANALYTICS_CATS.has(c));
    const manageCats    = filteredCats.filter(c => !ANALYTICS_CATS.has(c));

    // ── Expanded group (zone eyebrow rendered by caller) ──────────
    const renderGroup = (cat) => {
        const items = filterItems(grouped[cat] || []);
        if (!items.length) return null;
        const isOpen = isGroupOpen(cat);
        const hasActive = items.some(m => location.pathname === m.path);
        return (
            <React.Fragment key={cat}>
                <button
                    className={`sb__group${hasActive ? ' sb__group--active' : ''}`}
                    onClick={() => toggleGroup(cat)}
                    aria-expanded={isOpen}
                >
                    <span className="sb__group-label">{catLabel(cat)}</span>
                    <LucideIcons.ChevronRight
                        size={12}
                        className={`sb__group-chev${isOpen ? ' sb__group-chev--open' : ''}`}
                    />
                </button>
                {isOpen && (
                    <div className="sb__rail">
                        {items.map(m => (
                            <NavItem key={m.menuId || m.path}
                                menu={m}
                                active={location.pathname === m.path}
                                onClick={() => go(m)}
                            />
                        ))}
                    </div>
                )}
            </React.Fragment>
        );
    };

    // ── Collapsed rail: flat icon list per zone ───────────────────
    const renderCollapsedZone = (cats) => cats.flatMap(cat =>
        (grouped[cat] || []).map(m => {
            const active = location.pathname === m.path;
            const MIcon = LucideIcons[m.iconKey] || LucideIcons.Circle;
            return (
                <button key={m.menuId || m.path}
                    className={`sb__icon-item${active ? ' sb__icon-item--active' : ''}`}
                    title={m.menuName}
                    aria-label={m.menuName}
                    aria-current={active ? 'page' : undefined}
                    onClick={() => go(m)}
                    onMouseEnter={() => prefetchRoute(m.path)}
                >
                    <MIcon size={17} strokeWidth={active ? 2 : 1.7} />
                </button>
            );
        })
    );

    // ── Sidebar ───────────────────────────────────────────────────
    const sidebar = (
        <nav
            aria-label="Main navigation"
            className={`sb${collapsed && !isMobile ? ' sb--collapsed' : ''}`}
            style={{
                width: w,
                position: isMobile ? 'relative' : 'fixed',
                top: 0, left: 0, zIndex: 200,
            }}
        >
            {/* ── Header: brand + collapse ── */}
            <div className="sb__header">
                {(!collapsed || isMobile) ? (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <button className="sb__brand" onClick={() => navigate('/dashboard')} title="Go to dashboard">
                            <div className="sb__logo">NX</div>
                            <div style={{ minWidth: 0, flex: 1 }}>
                                <div className="sb__brand-name">NEXUS</div>
                                <div className="sb__brand-sub">Payment Intelligence</div>
                            </div>
                        </button>
                        {!isMobile && (
                            <button className="sb__collapse-btn" onClick={() => setCollapsed(true)} title="Collapse sidebar" aria-label="Collapse sidebar">
                                <LucideIcons.PanelLeftClose size={14} />
                            </button>
                        )}
                    </div>
                ) : (
                    <>
                        <button className="sb__brand" style={{ width: 'auto', padding: 0 }} onClick={() => navigate('/dashboard')} title="Go to dashboard" aria-label="Go to dashboard">
                            <div className="sb__logo">NX</div>
                        </button>
                        <button className="sb__collapse-btn" onClick={() => setCollapsed(false)} title="Expand sidebar" aria-label="Expand sidebar">
                            <LucideIcons.PanelLeftOpen size={14} />
                        </button>
                    </>
                )}
            </div>

            {/* ── Tenant switcher ── */}
            {(!collapsed || isMobile) && (
                <div style={{ padding: '0 12px 4px', flexShrink: 0 }}>
                    <TenantSwitcher />
                </div>
            )}

            {/* ── Search ── */}
            {(!collapsed || isMobile) && (
                <div className="sb__search" onClick={() => searchRef.current?.focus()}>
                    <LucideIcons.Search size={13} color="var(--sb-text-3)" />
                    <input
                        ref={searchRef}
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        placeholder="Search"
                        aria-label="Search navigation"
                    />
                    {search ? (
                        <button onClick={() => setSearch('')} aria-label="Clear search" style={{ display: 'flex', padding: 2, color: 'var(--sb-text-3)' }}>
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
                                <div className="sb__rail">
                                    {recent.slice(0, 3).map(r => (
                                        <NavItem key={r.path}
                                            menu={{ ...r, menuName: r.name }}
                                            active={location.pathname === r.path}
                                            muted
                                            onClick={() => navigate(r.path)}
                                        />
                                    ))}
                                </div>
                            </>
                        )}

                        {analyticsCats.length > 0 && <div className="sb__zone">Analytics</div>}
                        {analyticsCats.map(renderGroup)}

                        {manageCats.length > 0 && <div className="sb__zone">Manage</div>}
                        {manageCats.map(renderGroup)}

                        {search && filteredCats.length === 0 && (
                            <div className="sb__empty">
                                <LucideIcons.SearchX size={22} style={{ opacity: 0.5, marginBottom: 8 }} />
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

            {/* ── Footer ── */}
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
                            <LucideIcons.MoreHorizontal size={15} color="var(--sb-text-3)" />
                        </button>
                    </>
                ) : (
                    <>
                        <div className="sb__avatar" title={username}>{(username || 'U')[0].toUpperCase()}</div>
                        <button className="sb__icon-item" title={isDark ? 'Light mode' : 'Dark mode'} aria-label="Toggle theme" onClick={toggleTheme}>
                            {isDark ? <LucideIcons.Sun size={15} /> : <LucideIcons.Moon size={15} />}
                        </button>
                        <button className="sb__icon-item" title="Change password" aria-label="Change password" onClick={() => navigate('/change-password')}>
                            <LucideIcons.KeyRound size={15} />
                        </button>
                        <button className="sb__icon-item" title="Sign out" aria-label="Sign out" onClick={handleLogout} style={{ color: 'var(--sb-danger)' }}>
                            <LucideIcons.LogOut size={15} />
                        </button>
                    </>
                )}
            </div>
        </nav>
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
                    <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)' }}>NEXUS</span>
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
