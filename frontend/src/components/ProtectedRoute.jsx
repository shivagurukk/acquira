import React from 'react';
import { Navigate, useLocation, Outlet } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import api from '../api/axios';

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
    const [isValid, setIsValid] = React.useState(null);

    React.useEffect(() => {
        const validateSession = async () => {
            if (!token) {
                setIsValid(false);
                return;
            }
            try {
                await api.get('/auth/session');
                setIsValid(true);
            } catch (e) {
                setIsValid(false);
            }
        };
        validateSession();
    }, [token]);

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
