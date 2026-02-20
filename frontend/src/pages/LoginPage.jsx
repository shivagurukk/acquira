import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { Lock, User, ArrowRight, Building2, Loader2 } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import ParticlesBackground from '../components/ParticlesBackground';
import './Login.css';

const LoginPage = () => {
    const navigate = useNavigate();
    const { login, switchTenant } = useAuth();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [credentials, setCredentials] = useState({ username: '', password: '' });
    const [showTenantModal, setShowTenantModal] = useState(false);
    const [allowedTenants, setAllowedTenants] = useState([]);
    const [switchingTenant, setSwitchingTenant] = useState(null);

    const handleChange = (e) => {
        setCredentials({ ...credentials, [e.target.name]: e.target.value });
    };

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
                const authState = login(data);

                if (data.mustChangePassword) {
                    navigate('/change-password');
                    return;
                }

                if (data.allowedTenants && data.allowedTenants.length > 1) {
                    setAllowedTenants(data.allowedTenants);
                    setShowTenantModal(true);
                    return;
                }

                navigate('/dashboard');
            } else {
                const errData = await response.json().catch(() => ({}));
                setError(errData.error || 'Invalid credentials.');
            }
        } catch (err) {
            setError('Unable to connect to server.');
        } finally {
            setLoading(false);
        }
    };

    const handleSelectTenant = async (tenant) => {
        setSwitchingTenant(tenant.tenantId);
        try {
            await switchTenant(tenant.tenantId);
            navigate('/dashboard');
        } catch (e) {
            console.error(e);
            navigate('/dashboard');
        } finally {
            setSwitchingTenant(null);
        }
    };

    return (
        <div className="login-page">
            {/* Layer 1: Animated Gradient Mesh */}
            <div className="gradient-mesh">
                <div className="blob blob-1" />
                <div className="blob blob-2" />
                <div className="blob blob-3" />
                <div className="blob blob-4" />
            </div>

            {/* Layer 2: Floating Geometric Shapes */}
            <div className="floating-shapes">
                <div className="shape shape-1" />
                <div className="shape shape-2" />
                <div className="shape shape-3" />
                <div className="shape shape-4" />
                <div className="shape shape-5" />
                <div className="shape shape-6" />
            </div>

            {/* Layer 3: Particle Network */}
            <ParticlesBackground />

            {/* Layer 4: Login Content */}
            <div className="login-content">
                <div className="login-glass-card">
                    {/* Logo */}
                    <div className="login-logo-section">
                        <div className="login-logo-icon">A</div>
                        <h1 className="login-logo-text">Acquira</h1>
                        <p className="login-logo-subtext">Enterprise Payment Intelligence</p>
                    </div>

                    <AnimatePresence mode="wait">
                        {!showTenantModal ? (
                            <motion.form
                                key="login-form"
                                initial={{ opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                exit={{ opacity: 0, y: -10 }}
                                onSubmit={handleLogin}
                                className="login-form"
                            >
                                {error && (
                                    <div className="login-error-banner">{error}</div>
                                )}

                                <div className="login-input-group">
                                    <label className="login-input-label">Username</label>
                                    <div className="login-input-wrapper">
                                        <input
                                            name="username"
                                            type="text"
                                            placeholder="Enter your username"
                                            value={credentials.username}
                                            onChange={handleChange}
                                            autoFocus
                                        />
                                        <User size={18} className="input-icon" />
                                    </div>
                                </div>

                                <div className="login-input-group">
                                    <label className="login-input-label">Password</label>
                                    <div className="login-input-wrapper">
                                        <input
                                            name="password"
                                            type="password"
                                            placeholder="Enter your password"
                                            value={credentials.password}
                                            onChange={handleChange}
                                        />
                                        <Lock size={18} className="input-icon" />
                                    </div>
                                </div>

                                <button type="submit" disabled={loading} className="login-submit-btn">
                                    <span>
                                        {loading ? (
                                            <><Loader2 size={18} className="spin-icon" /> Authenticating...</>
                                        ) : (
                                            <>Sign In <ArrowRight size={18} /></>
                                        )}
                                    </span>
                                </button>
                            </motion.form>
                        ) : (
                            <motion.div
                                key="tenant-select"
                                initial={{ opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                exit={{ opacity: 0, y: -10 }}
                                className="tenant-section"
                            >
                                <div className="tenant-header">
                                    <div className="tenant-header-icon">
                                        <Building2 size={20} color="#3b82f6" />
                                    </div>
                                    <div>
                                        <h3 className="tenant-title">Select Organization</h3>
                                        <p className="tenant-subtitle">
                                            You have access to {allowedTenants.length} organizations
                                        </p>
                                    </div>
                                </div>

                                <div className="tenant-list">
                                    {allowedTenants.map(tenant => {
                                        const isSwitching = switchingTenant === tenant.tenantId;
                                        return (
                                            <button
                                                key={tenant.tenantId}
                                                onClick={() => handleSelectTenant(tenant)}
                                                disabled={!!switchingTenant}
                                                className={`tenant-card ${isSwitching ? 'active' : ''}`}
                                            >
                                                <div className="tenant-card-left">
                                                    <div className="tenant-card-icon">
                                                        {isSwitching ? (
                                                            <Loader2 size={18} color="#3b82f6" className="spin-icon" />
                                                        ) : (
                                                            <Building2 size={18} color="#94a3b8" />
                                                        )}
                                                    </div>
                                                    <div>
                                                        <span className="tenant-card-name">{tenant.bankName}</span>
                                                        <span className="tenant-card-meta">
                                                            {tenant.bankShortCode}
                                                            {tenant.country ? ` · ${tenant.country}` : ''}
                                                            {tenant.baseCurrency ? ` · ${tenant.baseCurrency}` : ''}
                                                        </span>
                                                    </div>
                                                </div>
                                                <ArrowRight size={16} color="#64748b" />
                                            </button>
                                        );
                                    })}
                                </div>

                                <button
                                    onClick={() => { setShowTenantModal(false); setError(null); }}
                                    className="login-back-btn"
                                >
                                    ← Back to login
                                </button>
                            </motion.div>
                        )}
                    </AnimatePresence>
                </div>

                <div className="login-footer">
                    Powered by Acquira · Secure Enterprise Platform
                </div>
            </div>
        </div>
    );
};

export default LoginPage;
