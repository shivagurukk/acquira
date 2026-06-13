import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { Lock, User, ArrowRight, Building2, Loader2, Globe, Send } from 'lucide-react';
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

    const [ssoConfig, setSsoConfig] = useState(null);
    const [ssoLoading, setSsoLoading] = useState(false);
    const [ssoStatus, setSsoStatus] = useState(null);
    const [ssoUserInfo, setSsoUserInfo] = useState(null);
    const [requestForm, setRequestForm] = useState({ message: '', tenantId: '' });
    const [availableTenants, setAvailableTenants] = useState([]);

    const [showForgotPw, setShowForgotPw] = useState(false);
    const [forgotEmail, setForgotEmail] = useState('');
    const [forgotMsg, setForgotMsg] = useState(null);
    const [forgotLoading, setForgotLoading] = useState(false);

    useEffect(() => {
        fetch('/api/sso/microsoft/config')
            .then(r => r.json())
            .then(data => setSsoConfig(data))
            .catch(() => setSsoConfig({ enabled: false }));
    }, []);

    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        const code = params.get('code');
        const state = params.get('state');
        if (code) {
            handleSsoCallback(code, state);
            window.history.replaceState({}, '', window.location.pathname);
        }
    }, []);

    // Show a notice when the user was redirected here by the inactivity
    // auto-logout (AuthContext sets ?expired=1 on session timeout).
    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        if (params.get('expired') === '1') {
            setError('Your session timed out due to inactivity. Please sign in again.');
            window.history.replaceState({}, '', window.location.pathname);
        }
    }, []);

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
                login(data);
                if (data.mustChangePassword) { navigate('/change-password'); return; }
                if (data.allowedTenants?.length > 1) { setAllowedTenants(data.allowedTenants); setShowTenantModal(true); return; }
                navigate('/dashboard');
            } else {
                const errData = await response.json().catch(() => ({}));
                setError(errData.error || 'Invalid credentials.');
            }
        } catch (err) { setError('Unable to connect to server.'); }
        finally { setLoading(false); }
    };

    const handleSsoLogin = () => {
        if (ssoConfig?.authUrl) window.location.href = ssoConfig.authUrl;
    };

    const handleSsoCallback = async (code, state) => {
        setSsoLoading(true); setError(null);
        try {
            const response = await fetch('/api/sso/microsoft/callback', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ code, state })
            });
            const data = await response.json();
            if (data.status === 'authenticated') {
                login(data);
                if (data.allowedTenants?.length > 1) { setAllowedTenants(data.allowedTenants); setShowTenantModal(true); }
                else { navigate('/dashboard'); }
            } else if (data.status === 'pending') { setSsoStatus('pending'); }
            else if (data.status === 'rejected') { setSsoStatus('rejected'); }
            else if (data.status === 'not_registered') {
                setSsoStatus('not_registered');
                setSsoUserInfo({ email: data.email, displayName: data.displayName, ssoId: data.ssoId });
                setAvailableTenants(data.availableTenants || []);
            } else if (data.error) { setError(data.error); }
        } catch (err) { setError('SSO authentication failed.'); }
        finally { setSsoLoading(false); }
    };

    const handleRequestAccess = async () => {
        try {
            const response = await fetch('/api/sso/request-access', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ...ssoUserInfo, ...requestForm })
            });
            const data = await response.json();
            if (data.status === 'request_submitted') setSsoStatus('request_submitted');
            else setError(data.error || 'Failed to submit request');
        } catch (err) { setError('Failed to submit access request.'); }
    };

    const handleSelectTenant = async (tenant) => {
        setSwitchingTenant(tenant.tenantId);
        try { await switchTenant(tenant.tenantId); navigate('/dashboard'); }
        catch (e) { navigate('/dashboard'); }
        finally { setSwitchingTenant(null); }
    };

    const resetSsoState = () => {
        setSsoStatus(null); setSsoUserInfo(null); setError(null);
        setRequestForm({ message: '', tenantId: '' });
    };

    const handleForgotPassword = async (e) => {
        e.preventDefault();
        if (!forgotEmail.trim()) return;
        setForgotLoading(true); setForgotMsg(null); setError(null);
        try {
            const res = await fetch('/api/auth/forgot-password', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: forgotEmail.trim() })
            });
            const data = await res.json();
            setForgotMsg(data.message || 'If that email is registered, a reset link has been sent.');
        } catch { setForgotMsg('If that email is registered, a reset link has been sent.'); }
        finally { setForgotLoading(false); }
    };

    const StatusCard = ({ icon: StatusIcon, iconBg, iconColor, title, description }) => (
        <div style={{ textAlign: 'center', padding: '28px 20px' }}>
            <div style={{
                width: 52, height: 52, borderRadius: '50%', background: iconBg,
                display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 16px',
            }}>
                <StatusIcon size={22} color={iconColor} />
            </div>
            <h3 style={{ color: '#fff', fontSize: 16, fontWeight: 600, marginBottom: 6 }}>{title}</h3>
            <p style={{ color: '#94a3b8', fontSize: 13, lineHeight: 1.6 }}>{description}</p>
            <button onClick={resetSsoState} className="login-back-btn" style={{ marginTop: 16 }}>← Back to login</button>
        </div>
    );

    return (
        <div className="login-page">
            <div className="gradient-mesh">
                <div className="blob blob-1" /><div className="blob blob-2" />
                <div className="blob blob-3" />
            </div>
            <div className="floating-shapes">
                <div className="shape shape-1" /><div className="shape shape-2" />
                <div className="shape shape-3" /><div className="shape shape-4" />
                <div className="shape shape-5" /><div className="shape shape-6" />
            </div>

            <div className="login-content">
                <div className="login-glass-card">
                    <div className="login-logo-section">
                        <div className="login-logo-icon">A</div>
                        <h1 className="login-logo-text">Acquira</h1>
                        <p className="login-logo-subtext">Enterprise Payment Intelligence</p>
                        <span className="login-version-badge">v2.0 · Secure</span>
                    </div>

                    <AnimatePresence mode="wait">
                        {ssoLoading && (
                            <motion.div key="sso-loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                                style={{ textAlign: 'center', padding: '40px 20px' }}>
                                <Loader2 size={28} className="spin-icon" style={{ color: '#3b82f6', marginBottom: 16 }} />
                                <p style={{ color: '#94a3b8', fontSize: 14 }}>Authenticating with Microsoft...</p>
                            </motion.div>
                        )}

                        {!ssoLoading && ssoStatus === 'pending' && (
                            <motion.div key="sso-pending" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                                <StatusCard icon={Loader2} iconBg="rgba(217,119,6,0.15)" iconColor="#fbbf24"
                                    title="Access Pending" description="Your access request is pending admin approval." />
                            </motion.div>
                        )}
                        {!ssoLoading && ssoStatus === 'rejected' && (
                            <motion.div key="sso-rejected" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                                <StatusCard icon={Lock} iconBg="rgba(220,38,38,0.15)" iconColor="#ef4444"
                                    title="Access Denied" description="Your request was not approved. Contact your administrator." />
                            </motion.div>
                        )}
                        {!ssoLoading && ssoStatus === 'request_submitted' && (
                            <motion.div key="sso-submitted" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                                <StatusCard icon={Send} iconBg="rgba(5,150,105,0.15)" iconColor="#34d399"
                                    title="Request Submitted" description="Your access request has been submitted. An admin will review it shortly." />
                            </motion.div>
                        )}

                        {!ssoLoading && ssoStatus === 'not_registered' && (
                            <motion.div key="sso-request" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                className="login-form" style={{ gap: 12 }}>
                                <div style={{ background: 'rgba(37,99,235,0.1)', padding: '12px 16px', borderRadius: 12, border: '1px solid rgba(37,99,235,0.2)', marginBottom: 4 }}>
                                    <div style={{ color: '#93c5fd', fontSize: 13, fontWeight: 600, marginBottom: 2 }}>No account found</div>
                                    <div style={{ color: '#94a3b8', fontSize: 12 }}>{ssoUserInfo?.email}</div>
                                    <div style={{ color: '#64748b', fontSize: 11, marginTop: 4 }}>Request access from your admin below.</div>
                                </div>
                                <div className="login-input-group">
                                    <label className="login-input-label">Organization</label>
                                    <div className="login-input-wrapper">
                                        <select value={requestForm.tenantId} onChange={e => setRequestForm({ ...requestForm, tenantId: e.target.value })}
                                            style={{ width: '100%', padding: '12px 14px', background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)', borderRadius: 12, color: '#f1f5f9', fontSize: 14, outline: 'none', appearance: 'none', fontFamily: 'inherit' }}>
                                            <option value="" style={{ background: '#1e293b' }}>Select organization...</option>
                                            {availableTenants.map(t => (<option key={t.tenantId} value={t.tenantId} style={{ background: '#1e293b' }}>{t.bankName}</option>))}
                                        </select>
                                    </div>
                                </div>
                                <div className="login-input-group">
                                    <label className="login-input-label">Message (optional)</label>
                                    <div className="login-input-wrapper">
                                        <input name="message" type="text" placeholder="Why do you need access?"
                                            value={requestForm.message} onChange={e => setRequestForm({ ...requestForm, message: e.target.value })}
                                            style={{ paddingLeft: 14 }} />
                                    </div>
                                </div>
                                {error && <div className="login-error-banner">{error}</div>}
                                <button type="button" onClick={handleRequestAccess} className="login-submit-btn" style={{ marginTop: 4 }}>
                                    <span><Send size={16} /> Request Access</span>
                                </button>
                                <button onClick={resetSsoState} className="login-back-btn">← Back to login</button>
                            </motion.div>
                        )}

                        {!ssoLoading && !ssoStatus && !showTenantModal && !showForgotPw && (
                            <motion.form key="login-form" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                onSubmit={handleLogin} className="login-form">
                                {error && <div className="login-error-banner">{error}</div>}
                                <div className="login-input-group">
                                    <label className="login-input-label">Username</label>
                                    <div className="login-input-wrapper">
                                        <input name="username" type="text" placeholder="Enter your username"
                                            value={credentials.username} onChange={handleChange} autoFocus autoComplete="username" />
                                        <User size={17} className="input-icon" />
                                    </div>
                                </div>
                                <div className="login-input-group">
                                    <label className="login-input-label">Password</label>
                                    <div className="login-input-wrapper">
                                        <input name="password" type="password" placeholder="Enter your password"
                                            value={credentials.password} onChange={handleChange} autoComplete="current-password" />
                                        <Lock size={17} className="input-icon" />
                                    </div>
                                </div>
                                <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: -4, marginBottom: 2 }}>
                                    <button type="button" onClick={() => { setShowForgotPw(true); setError(null); setForgotMsg(null); setForgotEmail(''); }}
                                        style={{ background: 'none', border: 'none', color: '#64748b', fontSize: 13, cursor: 'pointer', padding: 0, fontWeight: 500 }}
                                        onMouseOver={e => e.currentTarget.style.color = '#93c5fd'}
                                        onMouseOut={e => e.currentTarget.style.color = '#64748b'}>Forgot password?</button>
                                </div>
                                <button type="submit" disabled={loading} className="login-submit-btn">
                                    <span>
                                        {loading ? (<><Loader2 size={17} className="spin-icon" /> Signing in...</>)
                                            : (<>Sign In <ArrowRight size={17} /></>)}
                                    </span>
                                </button>
                                {ssoConfig?.enabled && (
                                    <>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '4px 0' }}>
                                            <div style={{ flex: 1, height: 1, background: 'rgba(255,255,255,0.08)' }} />
                                            <span style={{ color: '#64748b', fontSize: 12, fontWeight: 500 }}>or</span>
                                            <div style={{ flex: 1, height: 1, background: 'rgba(255,255,255,0.08)' }} />
                                        </div>
                                        <button type="button" onClick={handleSsoLogin}
                                            style={{
                                                width: '100%', padding: '12px 16px', borderRadius: 12,
                                                background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.1)',
                                                color: '#e2e8f0', fontSize: 14, fontWeight: 600, cursor: 'pointer',
                                                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
                                                transition: 'all .2s', fontFamily: 'inherit',
                                            }}
                                            onMouseOver={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.08)'; e.currentTarget.style.borderColor = 'rgba(37,99,235,0.4)'; }}
                                            onMouseOut={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.05)'; e.currentTarget.style.borderColor = 'rgba(255,255,255,0.1)'; }}>
                                            <svg width="20" height="20" viewBox="0 0 21 21"><rect x="1" y="1" width="9" height="9" fill="#f25022" /><rect x="11" y="1" width="9" height="9" fill="#7fba00" /><rect x="1" y="11" width="9" height="9" fill="#00a4ef" /><rect x="11" y="11" width="9" height="9" fill="#ffb900" /></svg>
                                            Sign in with Microsoft
                                        </button>
                                    </>
                                )}
                            </motion.form>
                        )}

                        {!ssoLoading && showForgotPw && !ssoStatus && !showTenantModal && (
                            <motion.form key="forgot-pw" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                onSubmit={handleForgotPassword} className="login-form">
                                <h3 style={{ color: '#fff', fontSize: 16, fontWeight: 600, textAlign: 'center', marginBottom: 4 }}>Reset Password</h3>
                                <p style={{ color: '#94a3b8', fontSize: 13, textAlign: 'center', marginBottom: 12, lineHeight: 1.6 }}>Enter your email and we'll send a reset link.</p>
                                {forgotMsg && <div style={{ background: 'rgba(5,150,105,0.12)', border: '1px solid rgba(5,150,105,0.2)', padding: '10px 14px', borderRadius: 12, color: '#6ee7b7', fontSize: 13, fontWeight: 500 }}>{forgotMsg}</div>}
                                {error && <div className="login-error-banner">{error}</div>}
                                <div className="login-input-group">
                                    <label className="login-input-label">Email Address</label>
                                    <div className="login-input-wrapper">
                                        <input name="email" type="email" placeholder="Enter your email" value={forgotEmail}
                                            onChange={e => setForgotEmail(e.target.value)} autoFocus style={{ paddingLeft: 14 }} />
                                    </div>
                                </div>
                                <button type="submit" disabled={forgotLoading || !forgotEmail.trim()} className="login-submit-btn"
                                    style={{ opacity: (forgotLoading || !forgotEmail.trim()) ? 0.5 : 1 }}>
                                    <span>{forgotLoading ? (<><Loader2 size={17} className="spin-icon" /> Sending...</>) : 'Send Reset Link'}</span>
                                </button>
                                <button type="button" onClick={() => { setShowForgotPw(false); setError(null); setForgotMsg(null); }} className="login-back-btn">← Back to login</button>
                            </motion.form>
                        )}

                        {!ssoLoading && !ssoStatus && showTenantModal && (
                            <motion.div key="tenant-select" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                className="tenant-section">
                                <div className="tenant-header">
                                    <div className="tenant-header-icon"><Building2 size={20} color="#3b82f6" /></div>
                                    <div>
                                        <h3 className="tenant-title">Select Organization</h3>
                                        <p className="tenant-subtitle">You have access to {allowedTenants.length} organizations</p>
                                    </div>
                                </div>
                                <div className="tenant-list">
                                    {allowedTenants.map(tenant => {
                                        const isSwitching = switchingTenant === tenant.tenantId;
                                        return (
                                            <button key={tenant.tenantId} onClick={() => handleSelectTenant(tenant)}
                                                disabled={!!switchingTenant} className={`tenant-card ${isSwitching ? 'active' : ''}`}>
                                                <div className="tenant-card-left">
                                                    <div className="tenant-card-icon">
                                                        {isSwitching ? <Loader2 size={18} color="#3b82f6" className="spin-icon" /> : <Building2 size={18} color="#94a3b8" />}
                                                    </div>
                                                    <div>
                                                        <span className="tenant-card-name">{tenant.bankName}</span>
                                                        <span className="tenant-card-meta">
                                                            {tenant.bankShortCode}{tenant.country ? ` · ${tenant.country}` : ''}{tenant.baseCurrency ? ` · ${tenant.baseCurrency}` : ''}
                                                        </span>
                                                    </div>
                                                </div>
                                                <ArrowRight size={16} color="#64748b" />
                                            </button>
                                        );
                                    })}
                                </div>
                                <button onClick={() => { setShowTenantModal(false); setError(null); }} className="login-back-btn">← Back to login</button>
                            </motion.div>
                        )}
                    </AnimatePresence>
                </div>
                <div className="login-footer">
                    © {new Date().getFullYear()} Acquira · Secure Enterprise Platform · 256-bit Encryption
                </div>
            </div>
        </div>
    );
};

export default LoginPage;
