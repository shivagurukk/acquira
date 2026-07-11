import React, { createContext, useContext, useState, useCallback, useEffect } from 'react';
import api from '../api/axios';
import { invalidateApiCache } from '../api/apiCache';
import { clearAuthStorage } from '../utils/authStorage';
import { setDefaultCurrency, setDefaultLocale } from '../utils/formatters';

const AuthContext = createContext(null);

// ==========================================
// AuthProvider — Single source of truth for:
//   • JWT token
//   • Current user (username, role)
//   • Allowed tenants + active tenant
//   • Menus (per-tenant, from RBAC)
//   • Tenant switching (calls switch-context API)
//   • #16: Currency formatting (dynamic from tenant)
//   • tenantVersion: incremented on switch to trigger re-fetches
// ==========================================
export const AuthProvider = ({ children }) => {
    const [auth, setAuth] = useState(() => {
        const token = localStorage.getItem('token');
        const refreshToken = localStorage.getItem('refreshToken');
        const username = localStorage.getItem('username') || '';
        const userRole = localStorage.getItem('userRole') || 'ROLE_USER';
        const activeTenantId = localStorage.getItem('defaultTenantId') || null;
        const sessionTimeoutMinutes = Number(localStorage.getItem('sessionTimeoutMinutes')) || 30;
        let menus = [];
        let tenants = [];
        let roles = [];

        try { menus = JSON.parse(localStorage.getItem('menus') || '[]'); } catch (e) { /* ignore */ }
        try { tenants = JSON.parse(localStorage.getItem('allowedTenants') || '[]'); } catch (e) { /* ignore */ }
        try { roles = JSON.parse(localStorage.getItem('roles') || '[]'); } catch (e) { /* ignore */ }

        return {
            token, refreshToken, username, userRole, roles, tenants, activeTenantId, menus,
            sessionTimeoutMinutes,
            isAuthenticated: !!token,
            tenantVersion: 0, // Incremented on switch to trigger data re-fetches
        };
    });

    useEffect(() => {
        if (auth.token) {
            localStorage.setItem('token', auth.token);
            localStorage.setItem('refreshToken', auth.refreshToken || '');
            localStorage.setItem('username', auth.username || '');
            localStorage.setItem('userRole', auth.userRole || '');
            localStorage.setItem('defaultTenantId', auth.activeTenantId || '');
            localStorage.setItem('menus', JSON.stringify(auth.menus || []));
            localStorage.setItem('allowedTenants', JSON.stringify(auth.tenants || []));
            localStorage.setItem('roles', JSON.stringify(auth.roles || []));
            localStorage.setItem('sessionTimeoutMinutes', String(auth.sessionTimeoutMinutes || 30));
        }
    }, [auth]);

    const login = useCallback((data) => {
        const newAuth = {
            token: data.jwt,
            refreshToken: data.refreshToken,
            username: data.username || '',
            userRole: data.userRole || 'ROLE_USER',
            roles: data.roles || [],
            tenants: data.allowedTenants || [],
            activeTenantId: data.defaultTenantId,
            menus: data.menus || [],
            sessionTimeoutMinutes: Number(data.sessionTimeoutMinutes) || 30,
            isAuthenticated: true,
            mustChangePassword: data.mustChangePassword || false,
            tenantVersion: 0,
        };
        // Fresh login — drop any cached lists from a previous session/user.
        invalidateApiCache();
        setAuth(newAuth);
        return newAuth;
    }, []);

    const clearMustChangePassword = useCallback(() => {
        setAuth(prev => ({ ...prev, mustChangePassword: false }));
    }, []);

    const switchTenant = useCallback(async (tenantId) => {
        try {
            // Ensure tenantId is sent as a number
            const numericTenantId = Number(tenantId);
            if (isNaN(numericTenantId)) {
                console.error('Invalid tenantId:', tenantId);
                return { success: false };
            }

            const res = await api.post('/auth/switch-context', { tenantId: numericTenantId });
            const { menus, activeTenantId: confirmedId, groupName, roleInTenant, sessionTimeoutMinutes } = res.data;

            const newTenantId = confirmedId || numericTenantId;

            // 1. Update localStorage FIRST so the axios interceptor sends the new X-Tenant-Id
            localStorage.setItem('defaultTenantId', String(newTenantId));
            localStorage.setItem('menus', JSON.stringify(menus || []));

            // Drop cached filter-option/data-bounds lists — they are tenant-scoped
            // and the cache keys off defaultTenantId, but clearing is explicit and
            // avoids any window where a stale key could be read mid-switch.
            invalidateApiCache();

            // 2. Update React state — increment tenantVersion to force re-fetches
            //    NO window.location.reload() — that causes race conditions and login redirects
            setAuth(prev => ({
                ...prev,
                activeTenantId: String(newTenantId),
                menus: menus || prev.menus,
                sessionTimeoutMinutes: Number(sessionTimeoutMinutes) || prev.sessionTimeoutMinutes || 30,
                tenantVersion: (prev.tenantVersion || 0) + 1,
            }));

            return { success: true, menus, groupName };
        } catch (err) {
            console.error('Failed to switch tenant:', err);
            // Don't redirect to login — just show error
            return { success: false, error: err.response?.data?.error || err.message };
        }
    }, []);

    const logout = useCallback(() => {
        invalidateApiCache();
        clearAuthStorage();
        setAuth({
            token: null, refreshToken: null, username: '', userRole: '', roles: [],
            tenants: [], activeTenantId: null, menus: [], isAuthenticated: false,
            tenantVersion: 0,
        });
    }, []);

    // ===== Inactivity auto-logout =====
    // Enforces the admin-configured "Session Timeout (minutes)" from
    // Admin > Security Settings (security.session_timeout_minutes). After N
    // minutes with no user activity, clear auth and hard-redirect to /login.
    // A short-lived access token alone wouldn't force re-login because the
    // 7-day refresh cookie would silently renew it — this timer is the actual
    // enforcement of the timeout. We track a last-activity timestamp and poll
    // every 15s (cheap; no work on each mouse move) rather than resetting a
    // timer on every event.
    useEffect(() => {
        if (!auth.isAuthenticated) return;
        const minutes = Number(auth.sessionTimeoutMinutes) || 30;
        if (minutes <= 0) return; // 0 / unset = disabled
        const timeoutMs = minutes * 60 * 1000;

        let lastActivity = Date.now();
        const markActivity = () => { lastActivity = Date.now(); };
        const events = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart', 'click'];
        events.forEach(e => window.addEventListener(e, markActivity, { passive: true }));

        const intervalId = setInterval(() => {
            if (Date.now() - lastActivity >= timeoutMs) {
                clearInterval(intervalId);
                events.forEach(e => window.removeEventListener(e, markActivity));
                clearAuthStorage();
                // Hard redirect so all in-flight requests/state are dropped.
                window.location.href = '/login?expired=1';
            }
        }, 15000);

        return () => {
            clearInterval(intervalId);
            events.forEach(e => window.removeEventListener(e, markActivity));
        };
    }, [auth.isAuthenticated, auth.sessionTimeoutMinutes]);

    // ===== HELPERS =====
    const isSuperAdmin = auth.userRole === 'ROLE_SUPER_ADMIN';
    const isAdmin = isSuperAdmin || auth.userRole === 'ROLE_ADMIN';
    const activeTenant = auth.tenants.find(t => String(t.tenantId) === String(auth.activeTenantId));

    // #16: Dynamic currency from active tenant
    const currencyCode = activeTenant?.baseCurrency || 'BHD';
    const currencySymbol = activeTenant?.currencySymbol || currencyCode;

    // Keep the shared formatters (utils/formatters.js) in sync with the active
    // tenant so formatCurrency()/createFmt() render in the right currency app-wide.
    useEffect(() => { setDefaultCurrency(currencyCode); }, [currencyCode]);

    // Per-tenant locale (date format + timezone) — same pattern as currency.
    // Fetched from GET /users/me/locale (tenant_setting locale.* keys) whenever
    // the active tenant changes; pushed into the shared formatters so fmt.date /
    // formatDate render in the bank's convention app-wide. Failure is harmless
    // (formatters keep their defaults).
    const [tenantLocale, setTenantLocale] = useState({ dateFormat: 'DD/MM/YYYY', timezone: '' });
    useEffect(() => {
        if (!auth.token || !auth.activeTenantId) return;
        let cancelled = false;
        api.get('/users/me/locale')
            .then(res => {
                if (cancelled || !res?.data) return;
                const loc = { dateFormat: res.data.dateFormat, timezone: res.data.timezone };
                setDefaultLocale(loc);
                setTenantLocale(loc);
            })
            .catch(() => { /* keep defaults */ });
        return () => { cancelled = true; };
    }, [auth.token, auth.activeTenantId, auth.tenantVersion]);

    // #16: Currency formatter — use instead of hardcoded currency symbols
    const formatCurrency = useCallback((value, opts = {}) => {
        if (value == null || isNaN(value)) return currencySymbol + ' 0';
        const num = Number(value);
        const decimals = opts.decimals != null ? opts.decimals : 0;
        const formatted = num.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
        return currencySymbol + ' ' + formatted;
    }, [currencySymbol]);

    const value = {
        ...auth,
        login, switchTenant, logout, clearMustChangePassword,
        isSuperAdmin, isAdmin, activeTenant,
        // #16: Currency
        currencyCode, currencySymbol, formatCurrency,
        // Per-tenant locale (date format + timezone)
        dateFormat: tenantLocale.dateFormat, timezone: tenantLocale.timezone,
    };

    return (
        <AuthContext.Provider value={value}>
            {children}
        </AuthContext.Provider>
    );
};

export const useAuth = () => {
    const context = useContext(AuthContext);
    if (!context) throw new Error('useAuth must be used within an AuthProvider');
    return context;
};

export default AuthContext;
