import React from 'react';
import { Navigate, useLocation, Outlet } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import api, { isBackendUnreachable } from '../api/axios';

// ==========================================
// ProtectedRoute — Guards routes with:
//   1. Authentication check (token exists)
//   2. One-time session validation
//   3. Force password change redirect
//
// Usage:
//   <ProtectedRoute>              → Any authenticated user
//   <ProtectedRoute><Layout /></ProtectedRoute>  → Layout wrapper
// ==========================================
const ProtectedRoute = ({ children }) => {
    const auth = useAuth();
    const { token } = auth;
    const location = useLocation();
    // null = still checking, true = valid, false = rejected (go to login),
    // 'unreachable' = server never answered (session may still be fine).
    const [isValid, setIsValid] = React.useState(null);
    const [attempt, setAttempt] = React.useState(0);

    React.useEffect(() => {
        let cancelled = false;
        // WATCHDOG: this screen must never be able to hang. The validation call
        // has its own timeout and the refresh interceptor now has one too, but a
        // guard here costs nothing and means no future change to either can
        // strand the user on "Validating Session..." with no way out but a
        // manual reload (which is what happened before 2026-09-04).
        const watchdog = setTimeout(() => {
            if (!cancelled) setIsValid((v) => (v === null ? 'unreachable' : v));
        }, 20000);

        const validateSession = async () => {
            if (!token) {
                setIsValid(false);
                return;
            }
            try {
                await api.get('/auth/session');
                if (!cancelled) setIsValid(true);
            } catch (e) {
                if (cancelled) return;
                // Only a real rejection ends the session. A network error or a
                // dead gateway means we simply do not know — bouncing to /login
                // would throw away a good session and land the user on a screen
                // that cannot log them in either, since the backend is down.
                setIsValid(isBackendUnreachable(e) ? 'unreachable' : false);
            }
        };
        validateSession();
        return () => { cancelled = true; clearTimeout(watchdog); };
    }, [token, attempt]);

    // No token at all — redirect to login
    if (!token) {
        return <Navigate to="/login" replace state={{ from: location }} />;
    }

    // Still validating session
    if (isValid === null) {
        return (
            <div style={{
                display: 'flex', justifyContent: 'center', alignItems: 'center',
                height: 'var(--vh100, 100vh)', color: 'var(--text-secondary, #64748b)', fontSize: '0.9rem',
                background: 'var(--bg, #F9FAFB)',
            }}>
                Validating Session...
            </div>
        );
    }

    // Server unreachable — say so and offer a retry. Deliberately NOT a
    // redirect to /login: the session is probably fine and the login screen
    // could not authenticate anyone while the backend is down anyway.
    if (isValid === 'unreachable') {
        return (
            <div style={{
                display: 'flex', flexDirection: 'column', gap: 16,
                justifyContent: 'center', alignItems: 'center', textAlign: 'center',
                height: 'var(--vh100, 100vh)', padding: 24,
                background: 'var(--bg, #F9FAFB)', color: 'var(--text, #111827)',
            }}>
                <div style={{ fontSize: '1.05rem', fontWeight: 600 }}>Cannot reach the server</div>
                <div style={{ fontSize: '0.9rem', color: 'var(--text-secondary, #64748b)', maxWidth: 420, lineHeight: 1.5 }}>
                    Your session has not been signed out — the server is not responding.
                    This usually clears on its own during a restart or deploy.
                </div>
                <div style={{ display: 'flex', gap: 12 }}>
                    <button
                        onClick={() => { setIsValid(null); setAttempt((n) => n + 1); }}
                        style={{
                            padding: '10px 20px', background: 'var(--primary, #2563eb)', color: '#fff',
                            border: 'none', borderRadius: 8, fontSize: 14, fontWeight: 600, cursor: 'pointer',
                        }}
                    >
                        Retry
                    </button>
                    <button
                        onClick={() => { window.location.href = '/login'; }}
                        style={{
                            padding: '10px 20px', background: 'transparent',
                            color: 'var(--text-secondary, #64748b)',
                            border: '1px solid var(--border, #D1D5DB)', borderRadius: 8,
                            fontSize: 14, fontWeight: 600, cursor: 'pointer',
                        }}
                    >
                        Sign in again
                    </button>
                </div>
            </div>
        );
    }

    // Session invalid — redirect to login
    if (isValid === false) {
        return <Navigate to="/login" replace state={{ from: location }} />;
    }

    // Force password change — redirect to change-password page
    if (auth.mustChangePassword && location.pathname !== '/change-password') {
        return <Navigate to="/change-password" replace />;
    }

    return children || <Outlet />;
};

// ==========================================
// RoleGuard — Lightweight role check (NO session validation).
// Only checks if user has the required role.
// Must be used INSIDE a ProtectedRoute (session already validated).
//
// Usage:
//   <RoleGuard requiredRoles={['ROLE_SUPER_ADMIN', 'ROLE_ADMIN']}>
//     <UserManagement />
//   </RoleGuard>
// ==========================================
export const RoleGuard = ({ children, requiredRoles }) => {
    const { userRole } = useAuth();

    if (requiredRoles && requiredRoles.length > 0) {
        const hasRequiredRole = requiredRoles.includes(userRole);
        if (!hasRequiredRole) {
            return <Navigate to="/dashboard" replace />;
        }
    }

    return children;
};

export default ProtectedRoute;
