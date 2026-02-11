import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Loader from '../components/Loader';
import { Lock, User, ArrowRight, ShieldCheck } from 'lucide-react';
import './Login.css';
import { motion, AnimatePresence } from 'framer-motion';

import ParticlesBackground from '../components/ParticlesBackground';

const LoginPage = () => {
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [credentials, setCredentials] = useState({ username: 'admin', password: 'password' });
    const [showTenantModal, setShowTenantModal] = useState(false);
    const [allowedTenants, setAllowedTenants] = useState([]);

    const handleChange = (e) => {
        setCredentials({ ...credentials, [e.target.name]: e.target.value });
    }

    const handleLogin = async (e) => {
        e.preventDefault();
        setLoading(true);
        setError(null);

        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(credentials)
            });

            if (response.ok) {
                const data = await response.json();
                localStorage.setItem('token', data.jwt);
                localStorage.setItem('refreshToken', data.refreshToken);
                localStorage.setItem('roles', JSON.stringify(data.roles));
                // Initial storage (might be updated if multiple tenants)
                localStorage.setItem('allowedTenants', JSON.stringify(data.allowedTenants));
                localStorage.setItem('defaultTenantId', data.defaultTenantId);
                localStorage.setItem('menus', JSON.stringify(data.menus));

                if (data.allowedTenants && data.allowedTenants.length > 1) {
                    setAllowedTenants(data.allowedTenants);
                    setShowTenantModal(true);
                    return;
                }

                navigate('/dashboard');
            } else {
                setError("Invalid credentials.");
            }
        } catch (err) {
            setError("Unable to connect to server.");
        } finally {
            setLoading(false);
        }
    };

    const handleSelectTenant = async (tenant) => {
        try {
            // Update context
            localStorage.setItem('defaultTenantId', tenant.tenantId);

            // Switch context on backend to get correct menus
            const token = localStorage.getItem('token');
            const res = await fetch('/api/auth/switch-context', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify({ tenantId: tenant.tenantId })
            });

            if (res.ok) {
                const data = await res.json();
                localStorage.setItem('menus', JSON.stringify(data.menus));
            }

            navigate('/dashboard');
        } catch (e) {
            console.error(e);
            // Fallback to dashboard even if switch fails (cached menus might work or be wrong)
            navigate('/dashboard');
        }
    };

    return (
        <div className="login-container">
            <ParticlesBackground />

            <div className="login-content">
                <div className="login-box">
                    <div className="brand-text">
                        <h1>Acquira.</h1>
                        <p>Enterprise Payment Intelligence</p>
                    </div>

                    {!showTenantModal ? (
                        <form onSubmit={handleLogin}>
                            {error && <div style={{ background: '#fee2e2', color: '#dc2626', padding: '10px', borderRadius: '6px', marginBottom: '20px', fontSize: '0.9rem' }}>{error}</div>}

                            <div className="input-group">
                                <div className="input-wrapper">
                                    <User className="icon" size={18} />
                                    <input
                                        name="username"
                                        type="text"
                                        placeholder="Username"
                                        value={credentials.username}
                                        onChange={handleChange}
                                    />
                                </div>
                            </div>

                            <div className="input-group">
                                <div className="input-wrapper">
                                    <Lock className="icon" size={18} />
                                    <input
                                        name="password"
                                        type="password"
                                        placeholder="Password"
                                        value={credentials.password}
                                        onChange={handleChange}
                                    />
                                </div>
                            </div>

                            <button type="submit" className="login-btn" disabled={loading}>
                                {loading ? 'Authenticating...' : (
                                    <>Sign In <ArrowRight size={18} /></>
                                )}
                            </button>
                        </form>
                    ) : (
                        <AnimatePresence>
                            <motion.div
                                initial={{ opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                className="tenant-selection"
                            >
                                <h3 style={{ fontSize: '1.1rem', fontWeight: '600', marginBottom: '15px', color: '#334155' }}>Select Organization</h3>
                                <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', maxHeight: '300px', overflowY: 'auto' }}>
                                    {allowedTenants.map(tenant => (
                                        <button
                                            key={tenant.tenantId}
                                            onClick={() => handleSelectTenant(tenant)}
                                            style={{
                                                padding: '12px',
                                                borderRadius: '8px',
                                                border: '1px solid #e2e8f0',
                                                background: 'white',
                                                textAlign: 'left',
                                                cursor: 'pointer',
                                                display: 'flex',
                                                justifyContent: 'space-between',
                                                alignItems: 'center',
                                                transition: 'all 0.2s',
                                                color: '#1e293b'
                                            }}
                                            onMouseOver={e => e.currentTarget.style.borderColor = '#3b82f6'}
                                            onMouseOut={e => e.currentTarget.style.borderColor = '#e2e8f0'}
                                        >
                                            <span style={{ fontWeight: '500' }}>{tenant.bankName}</span>
                                            <ArrowRight size={16} color="#94a3b8" />
                                        </button>
                                    ))}
                                </div>
                            </motion.div>
                        </AnimatePresence>
                    )}
                </div>
            </div>
        </div>
    );
};

export default LoginPage;
