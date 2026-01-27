import React, { useMemo } from 'react';
import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import * as LucideIcons from 'lucide-react';

const Layout = () => {
    const navigate = useNavigate();
    const location = useLocation();

    const handleLogout = () => {
        localStorage.clear(); // Clear all
        navigate('/');
    };

    const linkStyle = (path) => ({
        display: 'flex',
        alignItems: 'center',
        gap: '12px',
        padding: '12px 16px',
        borderRadius: '8px',
        color: location.pathname === path ? 'white' : '#94a3b8',
        background: location.pathname === path ? '#1e293b' : 'transparent',
        textDecoration: 'none',
        transition: 'all 0.2s',
        fontSize: '14px'
    });

    const menus = useMemo(() => {
        try {
            const storedMenus = localStorage.getItem('menus');
            const parsed = storedMenus ? JSON.parse(storedMenus) : [];

            // INJECT BUSINESS MENUS FOR DEMO
            const businessMenus = [
                { menuId: 101, menuName: 'Dashboard', path: '/business/dashboard', iconKey: 'LayoutGrid', category: 'BUSINESS UNIVERSE', displayOrder: 1 },
                { menuId: 102, menuName: 'Sales Trends', path: '/business/trends', iconKey: 'TrendingUp', category: 'BUSINESS UNIVERSE', displayOrder: 2 },
                { menuId: 103, menuName: 'Lifecycle', path: '/business/lifecycle', iconKey: 'Users', category: 'BUSINESS UNIVERSE', displayOrder: 3 },
                { menuId: 104, menuName: 'Zero Sales', path: '/business/zero-sales', iconKey: 'AlertCircle', category: 'BUSINESS UNIVERSE', displayOrder: 4 },
                { menuId: 115, menuName: 'Zero Txn Report', path: '/business/zero-transaction', iconKey: 'FileWarning', category: 'BUSINESS UNIVERSE', displayOrder: 5 },
                { menuId: 105, menuName: 'Opportunities', path: '/business/opportunity', iconKey: 'Lightbulb', category: 'BUSINESS UNIVERSE', displayOrder: 6 },
                { menuId: 106, menuName: 'Group Reports', path: '/business/groups', iconKey: 'PieChart', category: 'BUSINESS UNIVERSE', displayOrder: 6 },
                { menuId: 114, menuName: 'Dashboard', path: '/business/executive-dashboard-v2', iconKey: 'Presentation', category: 'BUSINESS UNIVERSE', displayOrder: 0 },
            ];

            // Check if already exist to avoid dupes if we were to actully persist
            const existingPaths = new Set(parsed.map(m => m.path));
            businessMenus.forEach(m => {
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
            if (!groups[category]) groups[category] = [];
            groups[category].push(menu);
        });

        // Sort menus within groups by displayOrder
        Object.keys(groups).forEach(key => {
            groups[key].sort((a, b) => a.displayOrder - b.displayOrder);
        });

        return groups;
    }, [menus]);

    // Categories order if needed (or just Object.keys but that's unstabe)
    // We can prioritize fixed categories: EXECUTIVE, SALES, FINANCE, OPERATIONS, ADMINISTRATION
    const categoryOrder = ['EXECUTIVE', 'SALES', 'FINANCE', 'OPERATIONS', 'ADMINISTRATION'];
    const sortedCategories = Object.keys(groupedMenus).sort((a, b) => {
        const idxA = categoryOrder.indexOf(a);
        const idxB = categoryOrder.indexOf(b);
        if (idxA !== -1 && idxB !== -1) return idxA - idxB;
        if (idxA !== -1) return -1;
        if (idxB !== -1) return 1;
        return a.localeCompare(b);
    });

    return (
        <div style={{ display: 'flex', minHeight: '100vh', background: '#f8fafc' }}>
            {/* Sidebar */}
            <div style={{ width: '260px', background: '#0f172a', color: 'white', display: 'flex', flexDirection: 'column' }}>
                <div style={{ padding: '24px', borderBottom: '1px solid #1e293b' }}>
                    <h2 style={{ fontSize: '20px', fontWeight: 'bold', display: 'flex', alignItems: 'center', gap: '10px' }}>
                        <div style={{ width: '32px', height: '32px', background: '#3b82f6', borderRadius: '8px' }}></div>
                        Acquira
                    </h2>
                </div>

                <nav style={{ flex: 1, padding: '24px 16px', display: 'flex', flexDirection: 'column', gap: '8px', overflowY: 'auto' }}>

                    {sortedCategories.map(category => (
                        <React.Fragment key={category}>
                            <div style={{ color: '#64748b', fontSize: '12px', fontWeight: 'bold', marginTop: '16px', marginBottom: '8px', paddingLeft: '12px' }}>
                                {category}
                            </div>
                            {groupedMenus[category].map(menu => {
                                const IconComponent = (LucideIcons && LucideIcons[menu.iconKey])
                                    ? LucideIcons[menu.iconKey]
                                    : (LucideIcons && LucideIcons.Circle) ? LucideIcons.Circle : 'span';
                                return (
                                    <Link key={menu.menuId} to={menu.path} style={linkStyle(menu.path)}>
                                        <IconComponent size={20} /> {menu.menuName}
                                    </Link>
                                );
                            })}
                        </React.Fragment>
                    ))}

                    {/* Fallback if no menus (e.g. initial load or old user) */}
                    {menus.length === 0 && (
                        <div style={{ padding: '20px', color: '#64748b', fontSize: '14px', textAlign: 'center' }}>
                            Access Restricted or Loading...
                        </div>
                    )}

                </nav>

                <div style={{ padding: '16px', borderTop: '1px solid #1e293b' }}>
                    <button onClick={handleLogout} style={{ width: '100%', display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 16px', borderRadius: '8px', color: '#ef4444', background: 'transparent', border: 'none', cursor: 'pointer', transition: 'background 0.2s' }}>
                        <LucideIcons.LogOut size={20} /> Sign Out
                    </button>
                </div>
            </div>

            {/* Main Content */}
            <div style={{ flex: 1, overflowY: 'auto' }}>
                <Outlet />
            </div>
        </div>
    );
};

export default Layout;
