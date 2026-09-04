import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import {
    Lock, User, ArrowRight, Building2, Loader2, Send, Eye, EyeOff,
    ShieldCheck, AlertCircle,
} from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { AfsMark } from '../components/AfsLogo';
import LoginBackdrop from './LoginBackdrop';
import './Login.css';

const Brandmark = ({ small = false }) => (
    <div className="nx-logo">
        <div className={small ? 'nx-mark nx-mark--sm' : 'nx-mark'}>
            <AfsMark size={small ? 26 : 30} />
        </div>
        <div>
            <p className="nx-wordmark">AFS <span>NEXUS</span></p>
            <p className="nx-tagline">Enterprise Payment Intelligence</p>
        </div>
    </div>
);

const LoginPage = () => {
    const navigate = useNavigate();
    const { login, switchTenant } = useAuth();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [credentials, setCredentials] = useState({ username: '', password: '' });
    const [showTenantModal, setShowTenantModal] = useState(false);
    const [allowedTenants, setAllowedTenants] = useState([]);
    const [switchingTenant, setSwitchingTenant] = useState(null);
    const [loginNotice, setLoginNotice] = useState(null);
    const [showPassword, setShowPassword] = useState(false);

    const [ssoConfig, setSsoConfig] = useState(null);
    const [ssoLoading, setSsoLoading] = useState(false);
    const [ssoStatus, setSsoStatus] = useState(null);
    const [ssoUserInfo, setSsoUserInfo] = useState(null);
    const [requestForm, setRequestForm] = useState({ message: '', tenantId: '' });
    const [availableTenants, setAvailableTenants] = useState([]);

    // ===== Login MFA (email OTP second factor) =====
    // Raised only when the tenant requires MFA. The password was already accepted
    // at this point; mfaTicket names the pending challenge and is the only thing
    // that gets us a session, so its presence is what switches the card to the
    // code step.
    const [mfaTicket, setMfaTicket] = useState(null);
    const [mfaCode, setMfaCode] = useState('');
    const [mfaEmailHint, setMfaEmailHint] = useState('');
    const [mfaTtl, setMfaTtl] = useState(5);
    const [mfaLoading, setMfaLoading] = useState(false);
    const [mfaResendIn, setMfaResendIn] = useState(0);

    // ===== Forgot-password OTP flow =====
    // step: 'email' -> request OTP | 'otp' -> verify code | 'password' -> set new pw
    const [showForgotPw, setShowForgotPw] = useState(false);
    const [fpStep, setFpStep] = useState('email');
    const [forgotEmail, setForgotEmail] = useState('');
    const [forgotMsg, setForgotMsg] = useState(null);
    const [forgotLoading, setForgotLoading] = useState(false);
    const [otpCode, setOtpCode] = useState('');
    const [resetTicket, setResetTicket] = useState(null);
    const [resendIn, setResendIn] = useState(0);
    const [newPw, setNewPw] = useState('');
    const [confirmPw, setConfirmPw] = useState('');
    const [showNewPw, setShowNewPw] = useState(false);

    // Password strength checks (mirror ChangePasswordPage).
    const pwChecks = [
        { label: '8+ characters', valid: newPw.length >= 8 },
        { label: 'Uppercase', valid: /[A-Z]/.test(newPw) },
        { label: 'Lowercase', valid: /[a-z]/.test(newPw) },
        { label: 'Number', valid: /[0-9]/.test(newPw) },
        { label: 'Special char', valid: /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(newPw) },
    ];
    const pwAllValid = pwChecks.every(c => c.valid);
    const pwMatch = newPw && confirmPw && newPw === confirmPw;

    // Resend cooldown ticker.
    useEffect(() => {
        if (resendIn <= 0) return;
        const id = setTimeout(() => setResendIn(resendIn - 1), 1000);
        return () => clearTimeout(id);
    }, [resendIn]);

    // Separate ticker for the MFA step — the two flows never run at once, but
    // sharing one counter would leak a cooldown from one into the other.
    useEffect(() => {
        if (mfaResendIn <= 0) return;
        const id = setTimeout(() => setMfaResendIn(mfaResendIn - 1), 1000);
        return () => clearTimeout(id);
    }, [mfaResendIn]);

    const resetMfaState = () => {
        setMfaTicket(null); setMfaCode(''); setMfaEmailHint('');
        setMfaResendIn(0); setError(null);
    };

    const resetForgotState = () => {
        setShowForgotPw(false); setFpStep('email'); setError(null); setForgotMsg(null);
        setForgotEmail(''); setOtpCode(''); setResetTicket(null); setResendIn(0);
        setNewPw(''); setConfirmPw('');
    };

    useEffect(() => {
        // The SSO config fetch fires on mount. On a cold backend (just restarted or
        // idle behind Nginx) this request can take several seconds, and because the
        // Microsoft button is gated on ssoConfig, a slow response left the page
        // sitting with no visible cue. We now race it against a short timeout: if
        // config doesn't arrive quickly we render the page as SSO-disabled and let
        // the button appear later if the (eventual) response says it's enabled.
        // AbortController cancels the in-flight request when the timeout wins.
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 4000);
        fetch('/api/sso/microsoft/config', { signal: controller.signal })
            .then(r => r.json())
            .then(data => setSsoConfig(data))
            .catch(() => setSsoConfig({ enabled: false }))
            .finally(() => clearTimeout(timer));
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

    // Shared landing for a completed sign-in, whether it came straight from
    // /login or from /login/verify-mfa — both receive the same session payload.
    const completeLogin = (data) => {
        login(data);
        if (data.mustChangePassword) { navigate('/change-password'); return; }
        if (data.allowedTenants?.length > 1) {
            setAllowedTenants(data.allowedTenants);
            setShowTenantModal(true);
            return;
        }
        navigate('/dashboard');
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
                // MFA gate: the password was accepted but no session was issued.
                // Hold here and swap the card to the code step.
                if (data.mfaRequired) {
                    setMfaTicket(data.mfaTicket);
                    setMfaEmailHint(data.emailHint || 'your registered email');
                    setMfaTtl(data.expiresInMinutes || 5);
                    setMfaCode('');
                    setMfaResendIn(30);
                    setLoginNotice(null);
                    return;
                }
                completeLogin(data);
            } else {
                const errData = await response.json().catch(() => ({}));
                // Distinguish a genuine credential rejection from a backend that is
                // down / erroring behind Nginx. A 502/503/504 (bad gateway / service
                // unavailable / gateway timeout) or a 500 means the server — not the
                // password — is the problem, and it must NOT read as "Invalid
                // credentials". Only 401 (and a 400 that carried an explicit message)
                // are true auth failures.
                if (response.status === 502 || response.status === 503 || response.status === 504) {
                    setError('The server is temporarily unavailable. Please try again in a moment.');
                } else if (response.status >= 500) {
                    setError(errData.error || 'A server error occurred. Please try again shortly.');
                } else if (response.status === 401) {
                    setError(errData.error || 'Invalid username or password.');
                } else {
                    // 400 / 403 / other 4xx — surface the backend message if present,
                    // otherwise a neutral fallback (not "invalid credentials", which
                    // would be misleading for e.g. a locked/pending account).
                    setError(errData.error || 'Unable to sign in. Please try again.');
                }
            }
        } catch (err) {
            // fetch() itself rejected — network unreachable, DNS failure, connection
            // refused (backend fully down and nothing answering on the port). This is
            // distinct from the branches above, which handle an HTTP error response.
            setError('Unable to reach the server. Please check your connection and try again.');
        }
        finally { setLoading(false); }
    };

    // Exchange the challenge ticket + emailed code for the real session.
    const handleVerifyMfa = async (e) => {
        if (e) e.preventDefault();
        if (mfaCode.trim().length !== 6) { setError('Enter the 6-digit code.'); return; }
        setMfaLoading(true); setError(null);
        try {
            const res = await fetch('/api/auth/login/verify-mfa', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mfaTicket, otp: mfaCode.trim() })
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok && data.jwt) {
                resetMfaState();
                completeLogin(data);
            } else {
                setError(data.error || 'Invalid or expired verification code.');
                // Only the server can say whether the ticket is finished — a wrong
                // code leaves it live, so the user stays on this step and retries.
                if (data.challengeDead) { setMfaTicket(null); setMfaCode(''); }
            }
        } catch { setError('Unable to reach the server. Please try again.'); }
        finally { setMfaLoading(false); }
    };

    const handleResendMfa = async () => {
        if (mfaResendIn > 0 || !mfaTicket) return;
        setMfaLoading(true); setError(null);
        try {
            const res = await fetch('/api/auth/login/resend-mfa', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mfaTicket })
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok) {
                setMfaCode(''); setMfaResendIn(30);
                setLoginNotice(null);
                setMfaTtl(data.expiresInMinutes || mfaTtl);
            } else {
                setError(data.error || 'Could not resend the code.');
                if (data.challengeDead) { setMfaTicket(null); setMfaCode(''); }
            }
        } catch { setError('Unable to reach the server. Please try again.'); }
        finally { setMfaLoading(false); }
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
        setError(null);
        try {
            // switchTenant never throws — it reports {success, error}. Only
            // navigate on success; a failed switch would land the user on a
            // dashboard whose tenant context was never established.
            const result = await switchTenant(tenant.tenantId);
            if (result?.success) navigate('/dashboard');
            else setError(result?.error || 'Could not switch to that organisation. Please try again.');
        } finally { setSwitchingTenant(null); }
    };

    const resetSsoState = () => {
        setSsoStatus(null); setSsoUserInfo(null); setError(null);
        setRequestForm({ message: '', tenantId: '' });
    };

    // STEP 1 — request an OTP for the email.
    const handleRequestOtp = async (e) => {
        if (e) e.preventDefault();
        if (!forgotEmail.trim()) return;
        setForgotLoading(true); setForgotMsg(null); setError(null);
        try {
            const res = await fetch('/api/auth/forgot-password', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: forgotEmail.trim() })
            });
            const data = await res.json().catch(() => ({}));
            // Always advance — the backend is enumeration-safe and returns generic success.
            setForgotMsg(data.message || 'If that email is registered, a verification code has been sent.');
            setFpStep('otp'); setOtpCode(''); setResendIn(30);
        } catch { setError('Unable to reach the server. Please try again.'); }
        finally { setForgotLoading(false); }
    };

    // STEP 2 — verify the OTP; on success capture the reset ticket.
    const handleVerifyOtp = async (e) => {
        if (e) e.preventDefault();
        if (otpCode.trim().length !== 6) { setError('Enter the 6-digit code.'); return; }
        setForgotLoading(true); setError(null); setForgotMsg(null);
        try {
            const res = await fetch('/api/auth/verify-otp', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email: forgotEmail.trim(), otp: otpCode.trim() })
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok && data.ticket) {
                setResetTicket(data.ticket); setFpStep('password'); setNewPw(''); setConfirmPw('');
            } else {
                setError(data.error || 'Invalid or expired verification code.');
            }
        } catch { setError('Unable to reach the server. Please try again.'); }
        finally { setForgotLoading(false); }
    };

    // STEP 3 — set the new password with the verified ticket.
    const handleSetNewPassword = async (e) => {
        if (e) e.preventDefault();
        if (!pwAllValid) { setError('Password does not meet all requirements.'); return; }
        if (!pwMatch) { setError('Passwords do not match.'); return; }
        setForgotLoading(true); setError(null);
        try {
            const res = await fetch('/api/auth/reset-password', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ ticket: resetTicket, newPassword: newPw })
            });
            const data = await res.json().catch(() => ({}));
            if (res.ok) {
                resetForgotState();
                setCredentials({ username: '', password: '' });
                // Surface success on the login form.
                setLoginNotice(data.message || 'Password reset. Please sign in with your new password.');
            } else {
                setError(data.error || 'Could not reset password. Please try again.');
            }
        } catch { setError('Unable to reach the server. Please try again.'); }
        finally { setForgotLoading(false); }
    };

    const errorId = 'nx-auth-error';
    const describedBy = error ? errorId : undefined;

    const ErrorBanner = () => (
        <div className="login-error-banner" id={errorId} role="alert">
            <AlertCircle size={16} style={{ flex: 'none', marginTop: 1 }} aria-hidden="true" />
            <span>{error}</span>
        </div>
    );

    const StatusCard = ({ icon: StatusIcon, iconBg, iconColor, title, description }) => (
        <div className="nx-status">
            <div className="nx-status__icon" style={{ background: iconBg }}>
                <StatusIcon size={22} color={iconColor} aria-hidden="true" />
            </div>
            <h3 className="nx-status__title">{title}</h3>
            <p className="nx-status__text">{description}</p>
            <button onClick={resetSsoState} className="login-back-btn" style={{ marginTop: 14 }}>← Back to sign in</button>
        </div>
    );

    return (
        <div className="login-page">
            {/* Animated payment-intelligence scene behind the card. */}
            <LoginBackdrop />

            {/* ─────────────── Sign-in card ─────────────── */}
            <main className="nx-auth">
                <div className="nx-auth__inner">
                    <Brandmark />
                    <div className="nx-auth__head">
                        <h2 className="nx-auth__title">Welcome back</h2>
                        <p className="nx-auth__subtitle">Sign in to your payment intelligence workspace</p>
                    </div>

                    <AnimatePresence mode="wait">
                        {ssoLoading && (
                            <motion.div key="sso-loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                                className="nx-status">
                                <Loader2 size={26} className="spin-icon" style={{ color: '#00d4ff', marginBottom: 14 }} />
                                <p className="nx-status__text">Authenticating with Microsoft…</p>
                            </motion.div>
                        )}

                        {!ssoLoading && ssoStatus === 'pending' && (
                            <motion.div key="sso-pending" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                                <StatusCard icon={Loader2} iconBg="rgba(251,191,36,0.12)" iconColor="#fbbf24"
                                    title="Access pending" description="Your access request is waiting for administrator approval." />
                            </motion.div>
                        )}
                        {!ssoLoading && ssoStatus === 'rejected' && (
                            <motion.div key="sso-rejected" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                                <StatusCard icon={Lock} iconBg="rgba(248,113,113,0.12)" iconColor="#f87171"
                                    title="Access denied" description="Your request was not approved. Contact your administrator to continue." />
                            </motion.div>
                        )}
                        {!ssoLoading && ssoStatus === 'request_submitted' && (
                            <motion.div key="sso-submitted" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                                <StatusCard icon={Send} iconBg="rgba(0,212,255,0.12)" iconColor="#00d4ff"
                                    title="Request submitted" description="An administrator will review your access request shortly." />
                            </motion.div>
                        )}

                        {!ssoLoading && ssoStatus === 'not_registered' && (
                            <motion.div key="sso-request" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                className="login-form">
                                <div className="nx-info-box">
                                    <div className="nx-info-box__title">No account found</div>
                                    <div className="nx-info-box__body">{ssoUserInfo?.email}</div>
                                    <div className="nx-info-box__hint">Request access from your administrator below.</div>
                                </div>
                                <div className="login-input-group">
                                    <label className="login-input-label" htmlFor="nx-org">Organisation</label>
                                    <div className="login-input-wrapper">
                                        <select id="nx-org" className="nx-select" value={requestForm.tenantId}
                                            onChange={e => setRequestForm({ ...requestForm, tenantId: e.target.value })}>
                                            <option value="">Select organisation…</option>
                                            {availableTenants.map(t => (<option key={t.tenantId} value={t.tenantId}>{t.bankName}</option>))}
                                        </select>
                                    </div>
                                </div>
                                <div className="login-input-group">
                                    <label className="login-input-label" htmlFor="nx-msg">Message (optional)</label>
                                    <div className="login-input-wrapper">
                                        <input id="nx-msg" name="message" type="text" className="nx-input--plain"
                                            placeholder="Why do you need access?"
                                            value={requestForm.message} onChange={e => setRequestForm({ ...requestForm, message: e.target.value })} />
                                    </div>
                                </div>
                                {error && <ErrorBanner />}
                                <button type="button" onClick={handleRequestAccess} className="login-submit-btn">
                                    <span><Send size={16} aria-hidden="true" /> Request access</span>
                                </button>
                                <button onClick={resetSsoState} className="login-back-btn">← Back to sign in</button>
                            </motion.div>
                        )}

                        {!ssoLoading && !ssoStatus && !showTenantModal && !showForgotPw && mfaTicket && (
                            <motion.form key="mfa-step" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                onSubmit={handleVerifyMfa} className="login-form">
                                <h3 className="nx-step-title">Two-factor verification</h3>
                                <p className="nx-step-text">
                                    We sent a 6-digit code to <strong>{mfaEmailHint}</strong>. It expires in {mfaTtl} minutes.
                                </p>
                                {error && <ErrorBanner />}
                                <div className="login-input-group">
                                    <label className="login-input-label" htmlFor="nx-mfa">Verification code</label>
                                    <div className="login-input-wrapper">
                                        <input id="nx-mfa" name="mfaOtp" type="text" inputMode="numeric" maxLength={6} autoFocus
                                            autoComplete="one-time-code" className="nx-input--plain nx-otp-input"
                                            placeholder="000000" value={mfaCode}
                                            onChange={e => setMfaCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                                            aria-describedby={describedBy} aria-invalid={error ? true : undefined} />
                                    </div>
                                </div>
                                <button type="submit" disabled={mfaLoading || mfaCode.length !== 6} className="login-submit-btn">
                                    <span>
                                        {mfaLoading ? (<><Loader2 size={17} className="spin-icon" aria-hidden="true" /> Verifying…</>)
                                            : (<><ShieldCheck size={17} aria-hidden="true" /> Verify and sign in</>)}
                                    </span>
                                </button>
                                <button type="button" className="nx-link" style={{ alignSelf: 'center' }}
                                    disabled={mfaResendIn > 0 || mfaLoading} onClick={handleResendMfa}>
                                    {mfaResendIn > 0 ? `Resend code in ${mfaResendIn}s` : 'Resend code'}
                                </button>
                                <button type="button" onClick={() => { resetMfaState(); setCredentials({ ...credentials, password: '' }); }}
                                    className="login-back-btn">← Back to sign in</button>
                            </motion.form>
                        )}

                        {!ssoLoading && !ssoStatus && !showTenantModal && !showForgotPw && !mfaTicket && (
                            <motion.form key="login-form" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                onSubmit={handleLogin} className="login-form">
                                {loginNotice && <div className="nx-notice" role="status">{loginNotice}</div>}
                                {error && <ErrorBanner />}
                                <div className="login-input-group">
                                    <label className="login-input-label" htmlFor="nx-username">Username</label>
                                    <div className="login-input-wrapper">
                                        <input id="nx-username" name="username" type="text" placeholder="Enter your username"
                                            value={credentials.username} onChange={handleChange} autoFocus autoComplete="username"
                                            aria-describedby={describedBy} aria-invalid={error ? true : undefined} />
                                        <User size={17} className="input-icon" aria-hidden="true" />
                                    </div>
                                </div>
                                <div className="login-input-group">
                                    <label className="login-input-label" htmlFor="nx-password">Password</label>
                                    <div className="login-input-wrapper">
                                        <input id="nx-password" name="password" type={showPassword ? 'text' : 'password'}
                                            className="nx-input--eye" placeholder="Enter your password"
                                            value={credentials.password} onChange={handleChange} autoComplete="current-password"
                                            aria-describedby={describedBy} aria-invalid={error ? true : undefined} />
                                        <Lock size={17} className="input-icon" aria-hidden="true" />
                                        <button type="button" className="nx-eye" onClick={() => setShowPassword(v => !v)}
                                            aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword}>
                                            {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
                                        </button>
                                    </div>
                                </div>
                                <div className="nx-form-row">
                                    <button type="button" className="nx-link"
                                        onClick={() => { setShowForgotPw(true); setFpStep('email'); setError(null); setForgotMsg(null); setForgotEmail(''); setLoginNotice(null); }}>
                                        Forgot password?
                                    </button>
                                </div>
                                <button type="submit" disabled={loading} className="login-submit-btn">
                                    <span>
                                        {loading ? (<><Loader2 size={17} className="spin-icon" aria-hidden="true" /> Signing in…</>)
                                            : (<><ShieldCheck size={17} aria-hidden="true" /> Sign in securely</>)}
                                    </span>
                                </button>
                                {ssoConfig?.enabled && (
                                    <>
                                        <div className="nx-divider"><span>or</span></div>
                                        <button type="button" onClick={handleSsoLogin} className="nx-sso-btn">
                                            <svg width="18" height="18" viewBox="0 0 21 21" aria-hidden="true"><rect x="1" y="1" width="9" height="9" fill="#f25022" /><rect x="11" y="1" width="9" height="9" fill="#7fba00" /><rect x="1" y="11" width="9" height="9" fill="#00a4ef" /><rect x="11" y="11" width="9" height="9" fill="#ffb900" /></svg>
                                            Sign in with Microsoft
                                        </button>
                                    </>
                                )}
                            </motion.form>
                        )}

                        {!ssoLoading && showForgotPw && !ssoStatus && !showTenantModal && (
                            <motion.div key="forgot-pw" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                className="login-form">
                                {/* Step indicator */}
                                <div className="nx-steps" aria-hidden="true">
                                    {['email', 'otp', 'password'].map((st, i) => {
                                        const order = { email: 0, otp: 1, password: 2 };
                                        return <div key={st} className={`nx-step-bar ${order[fpStep] >= i ? 'is-active' : ''}`} />;
                                    })}
                                </div>

                                {/* STEP 1 — email */}
                                {fpStep === 'email' && (
                                    <form onSubmit={handleRequestOtp} style={{ display: 'contents' }}>
                                        <h3 className="nx-step-title">Reset password</h3>
                                        <p className="nx-step-text">Enter your email and we'll send a 6-digit verification code.</p>
                                        {error && <ErrorBanner />}
                                        <div className="login-input-group">
                                            <label className="login-input-label" htmlFor="nx-email">Email address</label>
                                            <div className="login-input-wrapper">
                                                <input id="nx-email" name="email" type="email" className="nx-input--plain"
                                                    placeholder="you@company.com" value={forgotEmail} autoComplete="email"
                                                    onChange={e => setForgotEmail(e.target.value)} autoFocus
                                                    aria-describedby={describedBy} aria-invalid={error ? true : undefined} />
                                            </div>
                                        </div>
                                        <button type="submit" disabled={forgotLoading || !forgotEmail.trim()} className="login-submit-btn">
                                            <span>{forgotLoading ? (<><Loader2 size={17} className="spin-icon" aria-hidden="true" /> Sending…</>) : 'Send code'}</span>
                                        </button>
                                    </form>
                                )}

                                {/* STEP 2 — OTP */}
                                {fpStep === 'otp' && (
                                    <form onSubmit={handleVerifyOtp} style={{ display: 'contents' }}>
                                        <h3 className="nx-step-title">Enter code</h3>
                                        <p className="nx-step-text">
                                            We sent a 6-digit code to <strong>{forgotEmail}</strong>. It expires in 10 minutes.
                                        </p>
                                        {forgotMsg && <div className="nx-notice" role="status">{forgotMsg}</div>}
                                        {error && <ErrorBanner />}
                                        <div className="login-input-group">
                                            <label className="login-input-label" htmlFor="nx-otp">Verification code</label>
                                            <div className="login-input-wrapper">
                                                <input id="nx-otp" name="otp" type="text" inputMode="numeric" maxLength={6} autoFocus
                                                    autoComplete="one-time-code" className="nx-input--plain nx-otp-input"
                                                    placeholder="000000" value={otpCode}
                                                    onChange={e => setOtpCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                                                    aria-describedby={describedBy} aria-invalid={error ? true : undefined} />
                                            </div>
                                        </div>
                                        <button type="submit" disabled={forgotLoading || otpCode.length !== 6} className="login-submit-btn">
                                            <span>{forgotLoading ? (<><Loader2 size={17} className="spin-icon" aria-hidden="true" /> Verifying…</>) : 'Verify code'}</span>
                                        </button>
                                        <button type="button" className="nx-link" style={{ alignSelf: 'center' }}
                                            disabled={resendIn > 0 || forgotLoading} onClick={handleRequestOtp}>
                                            {resendIn > 0 ? `Resend code in ${resendIn}s` : 'Resend code'}
                                        </button>
                                    </form>
                                )}

                                {/* STEP 3 — new password */}
                                {fpStep === 'password' && (
                                    <form onSubmit={handleSetNewPassword} style={{ display: 'contents' }}>
                                        <h3 className="nx-step-title">Set new password</h3>
                                        <p className="nx-step-text">Choose a strong password you haven't used before.</p>
                                        {error && <ErrorBanner />}
                                        <div className="login-input-group">
                                            <label className="login-input-label" htmlFor="nx-newpw">New password</label>
                                            <div className="login-input-wrapper">
                                                <input id="nx-newpw" name="newPassword" type={showNewPw ? 'text' : 'password'}
                                                    className="nx-input--plain nx-input--eye" placeholder="Create a strong password" autoFocus
                                                    autoComplete="new-password"
                                                    value={newPw} onChange={e => { setNewPw(e.target.value); setError(null); }} />
                                                <button type="button" className="nx-eye" onClick={() => setShowNewPw(v => !v)}
                                                    aria-label={showNewPw ? 'Hide password' : 'Show password'} aria-pressed={showNewPw}>
                                                    {showNewPw ? <EyeOff size={17} /> : <Eye size={17} />}
                                                </button>
                                            </div>
                                        </div>
                                        {newPw && (
                                            <div className="nx-pw-checks">
                                                {pwChecks.map((c, i) => (
                                                    <span key={i} className={`nx-pw-check ${c.valid ? 'is-valid' : ''}`}>
                                                        {c.valid ? '✓' : '•'} {c.label}
                                                    </span>
                                                ))}
                                            </div>
                                        )}
                                        <div className="login-input-group">
                                            <label className="login-input-label" htmlFor="nx-confirmpw">Confirm password</label>
                                            <div className="login-input-wrapper">
                                                <input id="nx-confirmpw" name="confirmPassword" type={showNewPw ? 'text' : 'password'}
                                                    className="nx-input--plain" placeholder="Re-enter new password"
                                                    autoComplete="new-password"
                                                    aria-invalid={confirmPw && !pwMatch ? true : undefined}
                                                    aria-describedby={confirmPw && !pwMatch ? 'nx-pw-mismatch' : undefined}
                                                    value={confirmPw} onChange={e => { setConfirmPw(e.target.value); setError(null); }} />
                                            </div>
                                            {confirmPw && !pwMatch && (
                                                <span className="nx-field-error" id="nx-pw-mismatch">Passwords do not match</span>
                                            )}
                                        </div>
                                        <button type="submit" disabled={forgotLoading || !pwAllValid || !pwMatch} className="login-submit-btn">
                                            <span>{forgotLoading ? (<><Loader2 size={17} className="spin-icon" aria-hidden="true" /> Saving…</>) : 'Reset password'}</span>
                                        </button>
                                    </form>
                                )}

                                <button type="button" onClick={resetForgotState} className="login-back-btn">← Back to sign in</button>
                            </motion.div>
                        )}

                        {!ssoLoading && !ssoStatus && showTenantModal && (
                            <motion.div key="tenant-select" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                                className="tenant-section">
                                <div className="tenant-header">
                                    <div className="tenant-header-icon"><Building2 size={19} aria-hidden="true" /></div>
                                    <div>
                                        <h3 className="tenant-title">Select organisation</h3>
                                        <p className="tenant-subtitle">You have access to {allowedTenants.length} organisations</p>
                                    </div>
                                </div>
                                {error && <ErrorBanner />}
                                <div className="tenant-list">
                                    {allowedTenants.map(tenant => {
                                        const isSwitching = switchingTenant === tenant.tenantId;
                                        return (
                                            <button key={tenant.tenantId} onClick={() => handleSelectTenant(tenant)}
                                                disabled={!!switchingTenant} className={`tenant-card ${isSwitching ? 'active' : ''}`}>
                                                <div className="tenant-card-left">
                                                    <div className="tenant-card-icon">
                                                        {isSwitching ? <Loader2 size={17} className="spin-icon" /> : <Building2 size={17} />}
                                                    </div>
                                                    <div>
                                                        <span className="tenant-card-name">{tenant.bankName}</span>
                                                        <span className="tenant-card-meta">
                                                            {tenant.bankShortCode}{tenant.country ? ` · ${tenant.country}` : ''}{tenant.baseCurrency ? ` · ${tenant.baseCurrency}` : ''}
                                                        </span>
                                                    </div>
                                                </div>
                                                <ArrowRight size={16} color="#8fa3c8" aria-hidden="true" />
                                            </button>
                                        );
                                    })}
                                </div>
                                <button onClick={() => { setShowTenantModal(false); setError(null); }} className="login-back-btn">← Back to sign in</button>
                            </motion.div>
                        )}
                    </AnimatePresence>

                    <div className="nx-auth__foot">
                        <span className="nx-secure">
                            <ShieldCheck size={13} aria-hidden="true" /> Protected enterprise access
                        </span>
                        <p className="login-footer">© {new Date().getFullYear()} AFS Nexus. All rights reserved.</p>
                    </div>
                </div>
            </main>
        </div>
    );
};

export default LoginPage;
