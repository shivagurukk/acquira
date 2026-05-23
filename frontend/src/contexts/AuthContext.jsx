import React, { createContext, useContext, useState, useCallback, useEffect } from 'react';
import api from '../api/axios';
import { clearAuthStorage } from '../utils/authStorage';

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
        let menus = [];
        let tenants = [];
        let roles = [];

        try { menus = JSON.parse(localStorage.getItem('menus') || '[]'); } catch (e) { /* ignore */ }
        try { tenants = JSON.parse(localStorage.getItem('allowedTenants') || '[]'); } catch (e) { /* ignore */ }
        try { roles = JSON.parse(localStorage.getItem('roles') || '[]'); } catch (e) { /* ignore */ }

        return {
            token, refreshToken, username, userRole, roles, tenants, activeTenantId, menus,
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
            isAuthenticated: true,
            mustChangePassword: data.mustChangePassword || false,
            tenantVersion: 0,
        };
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
            const { menus, activeTenantId: confirmedId, groupName, roleInTenant } = res.data;

            const newTenantId = confirmedId || numericTenantId;

            // 1. Update localStorage FIRST so the axios interceptor sends the new X-Tenant-Id
            localStorage.setItem('defaultTenantId', String(newTenantId));
            localStorage.setItem('menus', JSON.stringify(menus || []));

            // 2. Update React state — increment tenantVersion to force re-fetches
            //    NO window.location.reload() — that causes race conditions and login redirects
            setAuth(prev => ({
                ...prev,
                activeTenantId: String(newTenantId),
                menus: menus || prev.menus,
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
        clearAuthStorage();
        setAuth({
            token: null, refreshToken: null, username: '', userRole: '', roles: [],
            tenants: [], activeTenantId: null, menus: [], isAuthenticated: false,
            tenantVersion: 0,
        });
    }, []);

    // ===== HELPERS =====
    const isSuperAdmin = auth.userRole === 'ROLE_SUPER_ADMIN';
    const isAdmin = isSuperAdmin || auth.userRole === 'ROLE_ADMIN';
    const activeTenant = auth.tenants.find(t => String(t.tenantId) === String(auth.activeTenantId));

    // #16: Dynamic currency from active tenant
    const currencyCode = activeTenant?.baseCurrency || 'BHD';
    const currencySymbol = activeTenant?.currencySymbol || currencyCode;

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
