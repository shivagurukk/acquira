import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { Lock, User, ArrowRight, Building2, Loader2 } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
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

                // Login to AuthContext first
                const authState = login(data);

                // If must change password, redirect there
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
            // Fallback — navigate anyway
            navigate('/dashboard');
        } finally {
            setSwitchingTenant(null);
        }
    };

    return (
        <div style={styles.container}>
            {/* Background gradient */}
            <div style={styles.bgGradient} />

            <div style={styles.content}>
                <div style={styles.card}>
                    {/* Logo */}
                    <div style={styles.logoSection}>
                        <div style={styles.logoIcon}>A</div>
                        <h1 style={styles.logoText}>Acquira</h1>
                        <p style={styles.logoSubtext}>Enterprise Payment Intelligence</p>
                    </div>

                    <AnimatePresence mode="wait">
                        {!showTenantModal ? (
                            <motion.form
                                key="login-form"
                                initial={{ opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                exit={{ opacity: 0, y: -10 }}
                                onSubmit={handleLogin}
                                style={styles.form}
                            >
                                {error && (
                                    <div style={styles.errorBanner}>{error}</div>
                                )}

                                <div style={styles.inputGroup}>
                                    <label style={styles.inputLabel}>Username</label>
                                    <div style={styles.inputWrapper}>
                                        <User size={18} style={styles.inputIcon} />
                                        <input
                                            name="username"
                                            type="text"
                                            placeholder="Enter your username"
                                            value={credentials.username}
                                            onChange={handleChange}
                                            style={styles.input}
                                            autoFocus
                                        />
                                    </div>
                                </div>

                                <div style={styles.inputGroup}>
                                    <label style={styles.inputLabel}>Password</label>
                                    <div style={styles.inputWrapper}>
                                        <Lock size={18} style={styles.inputIcon} />
                                        <input
                                            name="password"
                                            type="password"
                                            placeholder="Enter your password"
                                            value={credentials.password}
                                            onChange={handleChange}
                                            style={styles.input}
                                        />
                                    </div>
                                </div>

                                <button type="submit" disabled={loading} style={styles.loginBtn}>
                                    {loading ? (
                                        <><Loader2 size={18} style={{ animation: 'spin 1s linear infinite' }} /> Authenticating...</>
                                    ) : (
                                        <>Sign In <ArrowRight size={18} /></>
                                    )}
                                </button>
                            </motion.form>
                        ) : (
                            <motion.div
                                key="tenant-select"
                                initial={{ opacity: 0, y: 10 }}
                                animate={{ opacity: 1, y: 0 }}
                                exit={{ opacity: 0, y: -10 }}
                                style={styles.tenantSection}
                            >
                                <div style={styles.tenantHeader}>
                                    <Building2 size={20} color="#3b82f6" />
                                    <div>
                                        <h3 style={styles.tenantTitle}>Select Organization</h3>
                                        <p style={styles.tenantSubtitle}>
                                            You have access to {allowedTenants.length} organizations
                                        </p>
                                    </div>
                                </div>

                                <div style={styles.tenantList}>
                                    {allowedTenants.map(tenant => {
                                        const isSwitching = switchingTenant === tenant.tenantId;
                                        return (
                                            <button
                                                key={tenant.tenantId}
                                                onClick={() => handleSelectTenant(tenant)}
                                                disabled={!!switchingTenant}
                                                style={{
                                                    ...styles.tenantCard,
                                                    ...(isSwitching ? styles.tenantCardActive : {}),
                                                }}
                                                onMouseEnter={e => {
                                                    if (!switchingTenant) e.currentTarget.style.borderColor = '#3b82f6';
                                                }}
                                                onMouseLeave={e => {
                                                    if (!switchingTenant) e.currentTarget.style.borderColor = '#e2e8f0';
                                                }}
                                            >
                                                <div style={styles.tenantCardLeft}>
                                                    <div style={{
                                                        ...styles.tenantCardIcon,
                                                        background: isSwitching ? '#3b82f6' : '#f1f5f9',
                                                    }}>
                                                        {isSwitching ? (
                                                            <Loader2 size={18} color="white" style={{ animation: 'spin 1s linear infinite' }} />
                                                        ) : (
                                                            <Building2 size={18} color="#64748b" />
                                                        )}
                                                    </div>
                                                    <div style={styles.tenantCardInfo}>
                                                        <span style={styles.tenantCardName}>{tenant.bankName}</span>
                                                        <span style={styles.tenantCardMeta}>
                                                            {tenant.bankShortCode}
                                                            {tenant.country ? ` · ${tenant.country}` : ''}
                                                            {tenant.baseCurrency ? ` · ${tenant.baseCurrency}` : ''}
                                                        </span>
                                                    </div>
                                                </div>
                                                <ArrowRight size={16} color="#94a3b8" />
                                            </button>
                                        );
                                    })}
                                </div>

                                <button
                                    onClick={() => { setShowTenantModal(false); setError(null); }}
                                    style={styles.backBtn}
                                >
                                    ← Back to login
                                </button>
                            </motion.div>
                        )}
                    </AnimatePresence>
                </div>
            </div>

            <style>{`
                @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
            `}</style>
        </div>
    );
};

// ==========================================
// Styles
// ==========================================
const styles = {
    container: {
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#0f172a',
        position: 'relative',
        overflow: 'hidden',
    },
    bgGradient: {
        position: 'absolute',
        top: '-50%',
        left: '-50%',
        width: '200%',
        height: '200%',
        background: 'radial-gradient(circle at 30% 40%, rgba(59,130,246,0.08) 0%, transparent 50%), radial-gradient(circle at 70% 60%, rgba(99,102,241,0.06) 0%, transparent 50%)',
    },
    content: {
        position: 'relative',
        zIndex: 1,
        width: '100%',
        maxWidth: '420px',
        padding: '20px',
    },
    card: {
        background: 'white',
        borderRadius: '20px',
        padding: '40px 36px',
        boxShadow: '0 25px 60px rgba(0,0,0,0.3)',
    },
    logoSection: {
        textAlign: 'center',
        marginBottom: '32px',
    },
    logoIcon: {
        width: '48px',
        height: '48px',
        borderRadius: '14px',
        background: 'linear-gradient(135deg, #3b82f6, #1d4ed8)',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: 'white',
        fontWeight: 'bold',
        fontSize: '20px',
        marginBottom: '12px',
    },
    logoText: {
        fontSize: '1.5rem',
        fontWeight: 700,
        color: '#0f172a',
        margin: '0 0 4px',
    },
    logoSubtext: {
        fontSize: '0.85rem',
        color: '#94a3b8',
        margin: 0,
    },
    form: {
        display: 'flex',
        flexDirection: 'column',
        gap: '20px',
    },
    errorBanner: {
        background: '#fef2f2',
        color: '#dc2626',
        padding: '12px 16px',
        borderRadius: '10px',
        fontSize: '0.85rem',
        border: '1px solid #fecaca',
    },
    inputGroup: {
        display: 'flex',
        flexDirection: 'column',
        gap: '6px',
    },
    inputLabel: {
        fontSize: '0.82rem',
        fontWeight: 500,
        color: '#334155',
    },
    inputWrapper: {
        position: 'relative',
        display: 'flex',
        alignItems: 'center',
    },
    inputIcon: {
        position: 'absolute',
        left: '14px',
        color: '#94a3b8',
        pointerEvents: 'none',
    },
    input: {
        width: '100%',
        padding: '12px 14px 12px 44px',
        borderRadius: '10px',
        border: '1px solid #e2e8f0',
        fontSize: '0.9rem',
        outline: 'none',
        transition: 'border-color 0.2s',
        boxSizing: 'border-box',
    },
    loginBtn: {
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '8px',
        padding: '13px',
        borderRadius: '10px',
        background: '#0f172a',
        color: 'white',
        fontSize: '0.9rem',
        fontWeight: 600,
        border: 'none',
        cursor: 'pointer',
        transition: 'background 0.2s',
        marginTop: '4px',
    },
    tenantSection: {
        display: 'flex',
        flexDirection: 'column',
        gap: '20px',
    },
    tenantHeader: {
        display: 'flex',
        alignItems: 'center',
        gap: '12px',
    },
    tenantTitle: {
        fontSize: '1.05rem',
        fontWeight: 600,
        color: '#0f172a',
        margin: 0,
    },
    tenantSubtitle: {
        fontSize: '0.8rem',
        color: '#94a3b8',
        margin: 0,
    },
    tenantList: {
        display: 'flex',
        flexDirection: 'column',
        gap: '8px',
        maxHeight: '320px',
        overflowY: 'auto',
    },
    tenantCard: {
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        padding: '14px',
        borderRadius: '12px',
        border: '1px solid #e2e8f0',
        background: 'white',
        cursor: 'pointer',
        transition: 'all 0.2s',
        width: '100%',
        textAlign: 'left',
    },
    tenantCardActive: {
        borderColor: '#3b82f6',
        background: '#f0f7ff',
    },
    tenantCardLeft: {
        display: 'flex',
        alignItems: 'center',
        gap: '12px',
    },
    tenantCardIcon: {
        width: '40px',
        height: '40px',
        borderRadius: '10px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
    },
    tenantCardInfo: {
        display: 'flex',
        flexDirection: 'column',
    },
    tenantCardName: {
        fontWeight: 600,
        fontSize: '0.9rem',
        color: '#0f172a',
    },
    tenantCardMeta: {
        fontSize: '0.75rem',
        color: '#94a3b8',
    },
    backBtn: {
        background: 'transparent',
        border: 'none',
        color: '#64748b',
        cursor: 'pointer',
        fontSize: '0.85rem',
        textAlign: 'center',
        padding: '8px',
    },
};

export default LoginPage;
