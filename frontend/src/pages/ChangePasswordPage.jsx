import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useTheme } from '../contexts/ThemeContext';
import { Lock, Eye, EyeOff, Check, X, Shield, ArrowLeft, Zap } from 'lucide-react';
import api from '../api/axios';

const ChangePasswordPage = ({ embedded = false }) => {
    const navigate = useNavigate();
    const { mustChangePassword, clearMustChangePassword } = useAuth();
    const { theme } = useTheme();
    const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' });
    const [showCurrent, setShowCurrent] = useState(false);
    const [showNew, setShowNew] = useState(false);
    const [showConfirm, setShowConfirm] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);
    const [success, setSuccess] = useState(false);

    const handleChange = (e) => { setForm({ ...form, [e.target.name]: e.target.value }); setError(null); };

    const checks = [
        { label: 'At least 8 characters', valid: form.newPassword.length >= 8 },
        { label: 'Uppercase letter', valid: /[A-Z]/.test(form.newPassword) },
        { label: 'Lowercase letter', valid: /[a-z]/.test(form.newPassword) },
        { label: 'Number', valid: /[0-9]/.test(form.newPassword) },
        { label: 'Special character', valid: /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(form.newPassword) },
    ];
    const allValid = checks.every(c => c.valid);
    const passwordsMatch = form.newPassword && form.confirmPassword && form.newPassword === form.confirmPassword;
    const strengthPercent = (checks.filter(c => c.valid).length / checks.length) * 100;
    const strengthColor = strengthPercent >= 100 ? '#10b981' : strengthPercent >= 60 ? '#f59e0b' : '#ef4444';
    const strengthLabel = strengthPercent >= 100 ? 'Strong' : strengthPercent >= 60 ? 'Medium' : 'Weak';

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!allValid) { setError('Password does not meet all requirements'); return; }
        if (!passwordsMatch) { setError('Passwords do not match'); return; }
        setLoading(true); setError(null);
        try {
            await api.post('/users/change-password', { currentPassword: form.currentPassword, newPassword: form.newPassword });
            setSuccess(true);
            clearMustChangePassword();
            // Standalone (force-change) flow redirects to the app; embedded in the
            // Settings hub we stay put and just show the success state, then reset
            // the form so the panel remains usable.
            if (embedded) {
                setTimeout(() => { setSuccess(false); setForm({ currentPassword: '', newPassword: '', confirmPassword: '' }); }, 2500);
            } else {
                setTimeout(() => navigate('/dashboard'), 1500);
            }
        } catch (err) { setError(err.response?.data?.error || 'Failed to change password'); }
        finally { setLoading(false); }
    };

    // Only an EXPLICIT light theme renders the light card. Any other value —
    // including an undefined theme when this page is reached outside the themed
    // layout (the force-change redirect right after login) — falls back to the
    // dark treatment, so the page NEVER renders as a blank white background.
    const isDark = theme?.mode !== 'light';

    const s = {
        // The page background is ALWAYS a defined deep slate/indigo gradient
        // (mirrors the LoginPage aesthetic) in BOTH modes. Previously the light
        // gradient (#eef2ff -> #f8fafc -> #eff6ff) was so pale it read as a white
        // screen; a fixed branded backdrop fixes that and keeps the two auth
        // screens visually consistent. The CARD stays theme-adaptive on top.
        cardBg: isDark ? '#1e293b' : '#ffffff',
        cardBorder: isDark ? '1px solid #334155' : '1px solid #e2e8f0',
        cardShadow: isDark
            ? '0 24px 70px rgba(0,0,0,0.5)'
            : '0 24px 70px rgba(15,23,42,0.35)',   // stronger so the white card pops on the dark backdrop
        text: isDark ? '#f1f5f9' : '#0f172a',
        textSec: isDark ? '#94a3b8' : '#64748b',
        inputBg: isDark ? '#0f172a' : '#f8fafc',
        inputBdr: isDark ? '#334155' : '#e2e8f0',
        inputFocusBdr: '#3b82f6',
        checkBg: isDark ? 'rgba(15,23,42,0.6)' : '#f8fafc',
    };

    const inputStyle = (focused) => ({
        width: '100%', padding: '12px 42px 12px 40px', borderRadius: 12,
        border: `1.5px solid ${focused || s.inputBdr}`, fontSize: '0.9rem',
        outline: 'none', boxSizing: 'border-box', background: s.inputBg, color: s.text,
        transition: 'border-color 0.2s, box-shadow 0.2s', fontFamily: 'inherit',
    });

    // The inner card is shared between the full-screen standalone page and the
    // embedded Settings-hub panel. When embedded it uses theme CSS-variable
    // tokens so it blends into the panel instead of forcing the dark auth card,
    // and the success state stays put instead of announcing a redirect.
    const card = (
        <div style={{
            background: embedded ? 'var(--bg-card)' : s.cardBg,
            borderRadius: 16, padding: '36px 32px',
            border: embedded ? '1px solid var(--border)' : s.cardBorder,
            boxShadow: embedded ? 'none' : s.cardShadow,
            color: embedded ? 'var(--text)' : undefined,
        }}>
            {/* Header */}
            <div style={{ textAlign: 'center', marginBottom: 28 }}>
                <div style={{
                    width: 52, height: 52, borderRadius: 14,
                    background: isDark ? 'rgba(59,130,246,0.15)' : '#eff6ff',
                    display: 'inline-flex', alignItems: 'center', justifyContent: 'center', marginBottom: 12
                }}>
                    <Shield size={26} color="#3b82f6" />
                </div>
                <h1 style={{ fontSize: '1.3rem', fontWeight: 700, color: embedded ? 'var(--text)' : s.text, margin: '0 0 6px' }}>
                    {mustChangePassword ? 'Set New Password' : 'Change Password'}
                </h1>
                <p style={{ fontSize: '0.82rem', color: embedded ? 'var(--text-secondary)' : s.textSec, margin: 0 }}>
                    {mustChangePassword
                        ? 'Your administrator requires a password change before continuing.'
                        : 'Update your password to keep your account secure.'}
                </p>
            </div>

            {success ? (
                <div style={{
                    display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'center',
                    background: isDark ? 'rgba(16,185,129,0.12)' : '#f0fdf4', color: '#10b981',
                    padding: 18, borderRadius: 12, fontSize: '0.95rem', fontWeight: 500,
                    border: '1px solid rgba(16,185,129,0.2)'
                }}>
                    <Check size={22} /> {embedded ? 'Password changed successfully.' : 'Password changed! Redirecting...'}
                </div>
            ) : (
                <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
                    {error && (
                        <div style={{
                            background: isDark ? 'rgba(239,68,68,0.1)' : '#fef2f2', color: '#ef4444',
                            padding: '10px 14px', borderRadius: 10, fontSize: '0.83rem',
                            border: '1px solid rgba(239,68,68,0.2)', display: 'flex', alignItems: 'center', gap: 8
                        }}>
                            <X size={14} /> {error}
                        </div>
                    )}

                    {/* Current Password */}
                    <div>
                        <label style={{ fontSize: '0.78rem', fontWeight: 600, color: embedded ? 'var(--text-secondary)' : s.textSec, display: 'block', marginBottom: 6 }}>Current Password</label>
                        <div style={{ position: 'relative' }}>
                            <Lock size={16} style={{ position: 'absolute', left: 13, top: '50%', transform: 'translateY(-50%)', color: '#94a3b8' }} />
                            <input name="currentPassword" type={showCurrent ? 'text' : 'password'} value={form.currentPassword}
                                autoComplete="current-password"
                                onChange={handleChange} style={inputStyle()} placeholder="Enter current password" required autoFocus={!embedded} />
                            <button type="button" onClick={() => setShowCurrent(!showCurrent)}
                                style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8', padding: 4 }}>
                                {showCurrent ? <EyeOff size={16} /> : <Eye size={16} />}
                            </button>
                        </div>
                    </div>

                    {/* New Password */}
                    <div>
                        <label style={{ fontSize: '0.78rem', fontWeight: 600, color: embedded ? 'var(--text-secondary)' : s.textSec, display: 'block', marginBottom: 6 }}>New Password</label>
                        <div style={{ position: 'relative' }}>
                            <Lock size={16} style={{ position: 'absolute', left: 13, top: '50%', transform: 'translateY(-50%)', color: '#94a3b8' }} />
                            <input name="newPassword" type={showNew ? 'text' : 'password'} value={form.newPassword}
                                autoComplete="new-password"
                                onChange={handleChange} style={inputStyle()} placeholder="Create a strong password" required />
                            <button type="button" onClick={() => setShowNew(!showNew)}
                                style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8', padding: 4 }}>
                                {showNew ? <EyeOff size={16} /> : <Eye size={16} />}
                            </button>
                        </div>
                    </div>

                    {/* Strength Meter + Checks */}
                    {form.newPassword && (
                        <div style={{ background: s.checkBg, borderRadius: 10, padding: '12px 14px', border: isDark ? '1px solid #1e293b' : 'none' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                                <div style={{ flex: 1, height: 4, background: isDark ? '#1e293b' : '#e2e8f0', borderRadius: 2, overflow: 'hidden' }}>
                                    <div style={{ width: `${strengthPercent}%`, height: '100%', background: strengthColor, borderRadius: 2, transition: 'width 0.3s, background 0.3s' }} />
                                </div>
                                <span style={{ fontSize: '0.7rem', fontWeight: 600, color: strengthColor, minWidth: 50 }}>
                                    <Zap size={10} style={{ verticalAlign: 'middle', marginRight: 2 }} />
                                    {strengthLabel}
                                </span>
                            </div>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '4px 12px' }}>
                                {checks.map((c, i) => (
                                    <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 5, color: c.valid ? '#10b981' : '#94a3b8', fontSize: '0.75rem' }}>
                                        {c.valid ? <Check size={12} /> : <X size={12} />} {c.label}
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Confirm Password */}
                    <div>
                        <label style={{ fontSize: '0.78rem', fontWeight: 600, color: embedded ? 'var(--text-secondary)' : s.textSec, display: 'block', marginBottom: 6 }}>Confirm Password</label>
                        <div style={{ position: 'relative' }}>
                            <Lock size={16} style={{ position: 'absolute', left: 13, top: '50%', transform: 'translateY(-50%)', color: '#94a3b8' }} />
                            <input name="confirmPassword" type={showConfirm ? 'text' : 'password'} value={form.confirmPassword}
                                autoComplete="new-password"
                                onChange={handleChange} required placeholder="Re-enter new password"
                                style={{
                                    ...inputStyle(),
                                    borderColor: form.confirmPassword ? (passwordsMatch ? '#10b981' : '#ef4444') : s.inputBdr,
                                }} />
                            <button type="button" onClick={() => setShowConfirm(!showConfirm)}
                                style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8', padding: 4 }}>
                                {showConfirm ? <EyeOff size={16} /> : <Eye size={16} />}
                            </button>
                        </div>
                        {form.confirmPassword && !passwordsMatch && (
                            <span style={{ color: '#ef4444', fontSize: '0.75rem', marginTop: 4, display: 'block' }}>Passwords do not match</span>
                        )}
                        {form.confirmPassword && passwordsMatch && (
                            <span style={{ color: '#10b981', fontSize: '0.75rem', marginTop: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
                                <Check size={12} /> Passwords match
                            </span>
                        )}
                    </div>

                    <button type="submit" disabled={loading || !allValid || !passwordsMatch}
                        style={{
                            padding: 13, borderRadius: 12,
                            background: (allValid && passwordsMatch) ? 'linear-gradient(135deg, #3b82f6, #6366f1)' : (isDark ? '#334155' : '#e2e8f0'),
                            color: (allValid && passwordsMatch) ? 'white' : '#94a3b8',
                            fontSize: '0.9rem', fontWeight: 600, border: 'none', cursor: 'pointer', marginTop: 4,
                            opacity: loading ? 0.6 : 1, transition: 'all 0.3s',
                            boxShadow: (allValid && passwordsMatch) ? '0 8px 24px rgba(59,130,246,0.25)' : 'none',
                            fontFamily: 'inherit',
                        }}>
                        {loading ? 'Changing...' : 'Update Password'}
                    </button>
                </form>
            )}
        </div>
    );

    // Embedded (Settings hub): render just the card in normal page flow.
    if (embedded) {
        return (
            <div style={{ padding: 40, maxWidth: 480 }}>
                {card}
            </div>
        );
    }

    // Standalone (force-change / direct route): full-screen branded backdrop.
    return (
        <div style={{
            position: 'relative', minHeight: '100vh', display: 'flex',
            alignItems: 'center', justifyContent: 'center', padding: 20, overflow: 'hidden',
            background: 'linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #0f172a 100%)',
        }}>
            {/* Soft branded glows so the deep backdrop has depth (no blank fill). */}
            <div style={{
                position: 'absolute', top: '-15%', left: '-10%', width: 480, height: 480,
                borderRadius: '50%', background: 'radial-gradient(circle, rgba(59,130,246,0.22), transparent 70%)',
                filter: 'blur(20px)', pointerEvents: 'none',
            }} />
            <div style={{
                position: 'absolute', bottom: '-20%', right: '-10%', width: 520, height: 520,
                borderRadius: '50%', background: 'radial-gradient(circle, rgba(99,102,241,0.20), transparent 70%)',
                filter: 'blur(20px)', pointerEvents: 'none',
            }} />

            <div style={{ position: 'relative', width: '100%', maxWidth: 440 }}>
                {/* Back button */}
                {!mustChangePassword && (
                    <button onClick={() => navigate(-1)} style={{
                        display: 'flex', alignItems: 'center', gap: 6, background: 'none', border: 'none',
                        color: '#cbd5e1', cursor: 'pointer', fontSize: '0.85rem', padding: '8px 0', marginBottom: 12
                    }}>
                        <ArrowLeft size={16} /> Back
                    </button>
                )}
                {card}
            </div>
        </div>
    );
};

export default ChangePasswordPage;
