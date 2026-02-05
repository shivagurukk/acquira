import React from 'react';
import { Navigate, useLocation } from 'react-router-dom';

const ProtectedRoute = ({ children }) => {
    const token = localStorage.getItem('token');
    const location = useLocation();

    const [isValid, setIsValid] = React.useState(null);

    React.useEffect(() => {
        const validateSession = async () => {
            if (!token) {
                setIsValid(false);
                return;
            }
            try {
                const res = await fetch('/api/auth/session', {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (res.ok) {
                    setIsValid(true);
                } else {
                    localStorage.removeItem('token');
                    setIsValid(false);
                }
            } catch (e) {
                // If network error (e.g. server down), maybe don't force logout immediately? 
                // But user asked for restart handling -> usually means server up but session gone.
                // If 403/401 it will be in res.ok logic. 
                // If fetch fails completely, it might be safer to let them stay or show error.
                // Sticking to invalid => logout for now.
                setIsValid(false);
            }
        };
        validateSession();
    }, [token]);

    if (!token) {
        return <Navigate to="/login" replace state={{ from: location }} />;
    }

    if (isValid === null) {
        // Loading state while checking
        return <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}>Validating Session...</div>;
    }

    if (isValid === false) {
        return <Navigate to="/login" replace state={{ from: location }} />;
    }

    return children;
};

export default ProtectedRoute;
