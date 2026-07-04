import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
    Box, Typography, Paper, Grid, TextField, Button, Switch,
    FormControlLabel, Divider, Alert, Snackbar, Slider, Chip,
    Table, TableHead, TableRow, TableCell, TableBody,
    IconButton, Tooltip, useTheme, Dialog, DialogTitle, DialogContent,
    DialogActions
} from '@mui/material';
import {
    Shield, Lock, Key, Clock, AlertTriangle, Save, RefreshCw,
    CheckCircle, Smartphone, Globe, Network, FileText, KeyRound,
    Timer, Users, Ban
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';

const POLICY_DEFAULTS = {
    // password
    minLength: 8, maxLength: 128, requireUppercase: true, requireLowercase: true,
    requireDigit: true, requireSpecialChar: true, passwordHistoryCount: 5,
    passwordExpiryDays: 90, forceChangeOnFirstLogin: true,
    blockBreachedPasswords: true, blockUserInfoInPassword: true, minPasswordAgeHours: 0,
    // lockout + rate limit
    maxFailedAttempts: 5, lockoutDurationMinutes: 15, rateLimitPerMinute: 10, captchaAfterFailures: 0,
    // sessions + tokens
    sessionTimeoutMinutes: 30, accessTokenMinutes: 30, refreshTokenDays: 7, maxConcurrentSessions: 0,
    // mfa
    requireMfaForAdmins: false, requireMfaForAll: false, mfaGraceDays: 7, trustedDeviceDays: 30,
    // network
    ipAllowlistEnabled: false, ipAllowlist: '', loginBusinessHoursOnly: false,
    // api + audit
    apiKeyExpiryDays: 0, auditRetentionDays: 365, maskPiiInUi: true,
};

/* Which groups are actually enforced by the backend today vs. stored-only */
const ENFORCED = new Set(['password', 'lockout', 'session']);

const SecuritySettings = () => {
    const { tenantVersion } = useAuth();
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';
    const primary = theme.palette.primary.main;

    const [policy, setPolicy] = useState(POLICY_DEFAULTS);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [snack, setSnack] = useState({ open: false, msg: '', severity: 'success' });
    const [lockedUsers, setLockedUsers] = useState([]);
    const [unlockDialog, setUnlockDialog] = useState(null);
    const [revokeAllDialog, setRevokeAllDialog] = useState(false);

    const loadSettings = useCallback(async () => {
        setLoading(true);
        try {
            const res = await api.get('/admin/settings');
            const settings = res.data || [];
            const merged = { ...POLICY_DEFAULTS };
            settings.forEach(s => {
                const key = s.key || s.settingKey;
                const val = s.value || s.settingValue;
                if (key && key.startsWith('security.')) {
                    const prop = key.replace('security.', '').replace(/[-_](\w)/g, (_, c) => c.toUpperCase());
                    if (prop in merged) {
                        // type-aware parse keyed off the default's type (prevents '' -> 0 etc.)
                        const def = merged[prop];
                        if (typeof def === 'boolean') merged[prop] = val === 'true' || val === true;
                        else if (typeof def === 'number') merged[prop] = isNaN(Number(val)) ? def : Number(val);
                        else merged[prop] = val ?? def;
                    }
                }
            });
            setPolicy(merged);
        } catch { /* keep defaults */ }
        setLoading(false);
    }, []);

    const loadLockedUsers = useCallback(async () => {
        try {
            const res = await api.get('/admin/security/locked-users');
            setLockedUsers(res.data || []);
        } catch { setLockedUsers([]); }
    }, []);

    useEffect(() => { loadSettings(); loadLockedUsers(); }, [loadSettings, loadLockedUsers, tenantVersion]);

    const savePolicy = async () => {
        setSaving(true);
        try {
            const entries = Object.entries(policy).map(([k, v]) => ({
                settingKey: `security.${k.replace(/[A-Z]/g, m => '_' + m.toLowerCase())}`,
                settingValue: String(v)
            }));
            await Promise.all(entries.map(e => api.put('/admin/settings', e)));
            setSnack({ open: true, msg: 'Security policy saved successfully', severity: 'success' });
        } catch (err) {
            setSnack({ open: true, msg: 'Failed to save: ' + (err.response?.data?.error || err.message), severity: 'error' });
        }
        setSaving(false);
    };

    const unlockUser = async (userId) => {
        try {
            await api.post(`/admin/security/unlock-user/${userId}`);
            setSnack({ open: true, msg: 'User unlocked successfully', severity: 'success' });
            setUnlockDialog(null);
            loadLockedUsers();
        } catch {
            setSnack({ open: true, msg: 'Failed to unlock user', severity: 'error' });
        }
    };

    const revokeAllSessions = async () => {
        try {
            await api.post('/admin/security/revoke-all-sessions');
            setSnack({ open: true, msg: 'All sessions revoked. Users must sign in again.', severity: 'success' });
        } catch {
            setSnack({ open: true, msg: 'Revoke-all endpoint not available yet (needs backend hook).', severity: 'warning' });
        }
        setRevokeAllDialog(false);
    };

    const updatePolicy = (key, value) => setPolicy(p => ({ ...p, [key]: value }));

    // ── Security posture score (0-100) from the active policy ──
    const score = useMemo(() => {
        let s = 0;
        if (policy.minLength >= 12) s += 14; else if (policy.minLength >= 8) s += 8;
        if (policy.requireUppercase) s += 4;
        if (policy.requireLowercase) s += 4;
        if (policy.requireDigit) s += 4;
        if (policy.requireSpecialChar) s += 7;
        if (policy.passwordHistoryCount >= 5) s += 5;
        if (policy.passwordExpiryDays > 0 && policy.passwordExpiryDays <= 90) s += 4;
        if (policy.blockBreachedPasswords) s += 9;
        if (policy.blockUserInfoInPassword) s += 3;
        if (policy.maxFailedAttempts > 0 && policy.maxFailedAttempts <= 5) s += 5;
        if (policy.lockoutDurationMinutes >= 15) s += 3;
        if (policy.captchaAfterFailures > 0) s += 3;
        if (policy.sessionTimeoutMinutes > 0 && policy.sessionTimeoutMinutes <= 30) s += 4;
        if (policy.maxConcurrentSessions > 0) s += 2;
        if (policy.requireMfaForAdmins) s += 7;
        if (policy.requireMfaForAll) s += 9;
        if (policy.ipAllowlistEnabled) s += 5;
        if (policy.maskPiiInUi) s += 3;
        if (policy.apiKeyExpiryDays > 0) s += 3;
        return Math.min(100, s);
    }, [policy]);

    const grade = score >= 85 ? { label: 'A', text: 'Hardened', color: '#10B981' }
        : score >= 70 ? { label: 'B', text: 'Strong', color: '#3B82F6' }
        : score >= 55 ? { label: 'C', text: 'Adequate', color: '#F59E0B' }
        : { label: 'D', text: 'Needs work', color: '#EF4444' };

    const cardSx = {
        p: 0, borderRadius: 3, overflow: 'hidden',
        border: `1px solid ${isDark ? '#2a2a40' : '#E5E7EB'}`,
        bgcolor: isDark ? '#1a1a2e' : '#fff',
        transition: 'box-shadow .2s ease, transform .2s ease',
        '&:hover': { boxShadow: isDark ? '0 8px 24px rgba(0,0,0,0.4)' : '0 8px 24px rgba(15,23,42,0.08)' },
    };

    // section header with accent strip + optional "enforcement pending" chip
    const SectionHead = ({ icon: Icon, title, color, groupKey, subtitle }) => (
        <Box sx={{ p: 2.5, pb: 1.5, position: 'relative' }}>
            <Box sx={{ position: 'absolute', top: 0, left: 0, right: 0, height: 3, background: `linear-gradient(90deg, ${color}, ${color}55)` }} />
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                <Box sx={{ width: 38, height: 38, borderRadius: 2, display: 'flex', alignItems: 'center', justifyContent: 'center', bgcolor: color + '1f', color }}>
                    <Icon size={19} />
                </Box>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography variant="subtitle1" fontWeight={700}>{title}</Typography>
                    {subtitle && <Typography variant="caption" color="text.secondary">{subtitle}</Typography>}
                </Box>
                {groupKey && !ENFORCED.has(groupKey) && (
                    <Tooltip title="Stored now; takes effect once the backend enforcement hook is wired up.">
                        <Chip size="small" label="Enforcement pending" sx={{ bgcolor: '#F59E0B22', color: '#B45309', fontWeight: 600, fontSize: 10 }} />
                    </Tooltip>
                )}
            </Box>
        </Box>
    );

    const numField = (label, key, help, min = 0, props = {}) => (
        <TextField fullWidth type="number" size="small" label={label} helperText={help}
            value={policy[key]} onChange={e => updatePolicy(key, Math.max(min, Number(e.target.value)))}
            sx={{ mb: 2 }} {...props} />
    );

    const toggle = (label, key) => (
        <FormControlLabel control={<Switch checked={!!policy[key]} onChange={e => updatePolicy(key, e.target.checked)} />} label={label} />
    );

    if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><Typography>Loading security settings...</Typography></Box>;

    return (
        <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1280, mx: 'auto' }}>
            {/* ═══ Hero: posture score + grade ═══ */}
            <Paper sx={{
                p: 3, mb: 3, borderRadius: 3, border: `1px solid ${isDark ? '#2a2a40' : '#E5E7EB'}`,
                background: isDark
                    ? `linear-gradient(135deg, #1a1a2e, #16213e)`
                    : `linear-gradient(135deg, #ffffff, ${grade.color}0d)`,
                display: 'flex', alignItems: 'center', gap: 3, flexWrap: 'wrap',
            }}>
                {/* score ring */}
                <Box sx={{ position: 'relative', width: 96, height: 96, flexShrink: 0 }}>
                    <svg width="96" height="96" viewBox="0 0 96 96">
                        <circle cx="48" cy="48" r="42" fill="none" stroke={isDark ? '#2a2a40' : '#EEF2F7'} strokeWidth="8" />
                        <circle cx="48" cy="48" r="42" fill="none" stroke={grade.color} strokeWidth="8" strokeLinecap="round"
                            strokeDasharray={`${(score / 100) * 2 * Math.PI * 42} ${2 * Math.PI * 42}`}
                            transform="rotate(-90 48 48)" style={{ transition: 'stroke-dasharray .8s ease' }} />
                    </svg>
                    <Box sx={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
                        <Typography variant="h4" fontWeight={800} sx={{ lineHeight: 1, color: grade.color }}>{grade.label}</Typography>
                        <Typography variant="caption" color="text.secondary" fontWeight={600}>{score}/100</Typography>
                    </Box>
                </Box>
                <Box sx={{ flex: 1, minWidth: 200 }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                        <Shield size={24} color={primary} />
                        <Typography variant="h5" fontWeight={700}>Security Settings</Typography>
                    </Box>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                        Posture: <strong style={{ color: grade.color }}>{grade.text}</strong> — tune the policies below, then save.
                    </Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                    <Button variant="outlined" startIcon={<RefreshCw size={16} />} onClick={() => setPolicy(POLICY_DEFAULTS)}>Reset</Button>
                    <Button variant="contained" startIcon={<Save size={16} />} onClick={savePolicy} disabled={saving} sx={{ minWidth: 160 }}>
                        {saving ? 'Saving...' : 'Save Policy'}
                    </Button>
                </Box>
            </Paper>

            <Grid container spacing={3}>
                {/* Password Policy */}
                <Grid item xs={12} md={6}>
                    <Paper sx={cardSx}>
                        <SectionHead icon={Key} title="Password Policy" color="#3B82F6" groupKey="password" subtitle="Composition & rotation rules" />
                        <Box sx={{ p: 2.5, pt: 1 }}>
                            <Typography variant="body2" color="text.secondary" gutterBottom>Minimum Length: <strong>{policy.minLength}</strong></Typography>
                            <Slider value={policy.minLength} min={6} max={32} step={1}
                                onChange={(_, v) => updatePolicy('minLength', v)}
                                marks={[{ value: 8, label: '8' }, { value: 16, label: '16' }, { value: 32, label: '32' }]} sx={{ mt: 1, mb: 1 }} />
                            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.25 }}>
                                {toggle('Require uppercase (A-Z)', 'requireUppercase')}
                                {toggle('Require lowercase (a-z)', 'requireLowercase')}
                                {toggle('Require digit (0-9)', 'requireDigit')}
                                {toggle('Require special character (!@#$%)', 'requireSpecialChar')}
                                {toggle('Block common & breached passwords', 'blockBreachedPasswords')}
                                {toggle('Block username / email inside password', 'blockUserInfoInPassword')}
                            </Box>
                            <Divider sx={{ my: 2 }} />
                            {numField('Password History Count', 'passwordHistoryCount', 'Block reuse of last N passwords')}
                            {numField('Password Expiry (days)', 'passwordExpiryDays', '0 = never expires')}
                            {numField('Minimum Password Age (hours)', 'minPasswordAgeHours', 'Stops rapid cycling to defeat history')}
                            {toggle('Force password change on first login', 'forceChangeOnFirstLogin')}
                        </Box>
                    </Paper>
                </Grid>

                {/* Account Lockout & Rate Limiting */}
                <Grid item xs={12} md={6}>
                    <Paper sx={cardSx}>
                        <SectionHead icon={Lock} title="Lockout & Rate Limiting" color="#EF4444" groupKey="lockout" subtitle="Brute-force defenses" />
                        <Box sx={{ p: 2.5, pt: 1 }}>
                            {numField('Max Failed Attempts', 'maxFailedAttempts', 'Lock account after this many failures', 1)}
                            {numField('Lockout Duration (minutes)', 'lockoutDurationMinutes', 'Auto-unlock after this many minutes', 1)}
                            {numField('Rate Limit (requests / minute)', 'rateLimitPerMinute', 'Per (IP, username) login throttle', 1)}
                            {numField('CAPTCHA After N Failures', 'captchaAfterFailures', '0 = disabled')}
                            <Divider sx={{ my: 2 }} />
                            <Typography variant="subtitle2" fontWeight={600} sx={{ mb: 1 }}>Currently Locked Users ({lockedUsers.length})</Typography>
                            {lockedUsers.length === 0 ? (
                                <Alert severity="success" variant="outlined" sx={{ py: 0.5 }}>No locked accounts</Alert>
                            ) : (
                                <Box sx={{ maxHeight: 200, overflow: 'auto' }}>
                                    <Table size="small">
                                        <TableHead><TableRow><TableCell>Username</TableCell><TableCell>Locked Until</TableCell><TableCell align="right">Action</TableCell></TableRow></TableHead>
                                        <TableBody>
                                            {lockedUsers.map(u => (
                                                <TableRow key={u.id}>
                                                    <TableCell>{u.username}</TableCell>
                                                    <TableCell><Chip size="small" label={u.lockedUntil ? new Date(u.lockedUntil).toLocaleString() : 'Indefinite'} color="error" variant="outlined" /></TableCell>
                                                    <TableCell align="right">
                                                        <Tooltip title="Unlock User">
                                                            <IconButton size="small" onClick={() => setUnlockDialog(u)} sx={{ color: theme.palette.success.main }}>
                                                                <CheckCircle size={16} />
                                                            </IconButton>
                                                        </Tooltip>
                                                    </TableCell>
                                                </TableRow>
                                            ))}
                                        </TableBody>
                                    </Table>
                                </Box>
                            )}
                        </Box>
                    </Paper>
                </Grid>

                {/* Sessions & Tokens */}
                <Grid item xs={12} md={6}>
                    <Paper sx={cardSx}>
                        <SectionHead icon={Timer} title="Sessions & Tokens" color="#8B5CF6" groupKey="session" subtitle="JWT lifetimes & concurrency" />
                        <Box sx={{ p: 2.5, pt: 1 }}>
                            {numField('Session Idle Timeout (minutes)', 'sessionTimeoutMinutes', 'Auto-logout after inactivity', 5)}
                            {numField('Access Token TTL (minutes)', 'accessTokenMinutes', 'Short-lived bearer token lifetime', 1)}
                            {numField('Refresh Token TTL (days)', 'refreshTokenDays', 'Rotation window for refresh tokens', 1)}
                            {numField('Max Concurrent Sessions / user', 'maxConcurrentSessions', '0 = unlimited')}
                            <Divider sx={{ my: 2 }} />
                            <Button fullWidth variant="outlined" color="error" startIcon={<Users size={16} />} onClick={() => setRevokeAllDialog(true)}>
                                Revoke all active sessions
                            </Button>
                        </Box>
                    </Paper>
                </Grid>

                {/* Multi-Factor Authentication */}
                <Grid item xs={12} md={6}>
                    <Paper sx={cardSx}>
                        <SectionHead icon={Smartphone} title="Multi-Factor Authentication" color="#10B981" groupKey="mfa" subtitle="TOTP / authenticator app" />
                        <Box sx={{ p: 2.5, pt: 1 }}>
                            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.25, mb: 1 }}>
                                {toggle('Require MFA for admins', 'requireMfaForAdmins')}
                                {toggle('Require MFA for all users', 'requireMfaForAll')}
                            </Box>
                            <Divider sx={{ my: 2 }} />
                            {numField('Enrollment Grace Period (days)', 'mfaGraceDays', 'Days a new user can sign in before MFA is mandatory')}
                            {numField('Trusted Device Duration (days)', 'trustedDeviceDays', 'Skip MFA on a remembered device for N days')}
                        </Box>
                    </Paper>
                </Grid>

                {/* Network & Access */}
                <Grid item xs={12} md={6}>
                    <Paper sx={cardSx}>
                        <SectionHead icon={Network} title="Network & Access" color="#06B6D4" groupKey="network" subtitle="Where & when logins are allowed" />
                        <Box sx={{ p: 2.5, pt: 1 }}>
                            {toggle('Enable IP allowlist', 'ipAllowlistEnabled')}
                            <TextField fullWidth multiline minRows={2} size="small" label="Allowed IPs / CIDR ranges"
                                placeholder="203.0.113.0/24, 198.51.100.42"
                                helperText="Comma or newline separated. Empty = all IPs (when allowlist disabled)."
                                value={policy.ipAllowlist} onChange={e => updatePolicy('ipAllowlist', e.target.value)}
                                disabled={!policy.ipAllowlistEnabled} sx={{ my: 2 }} />
                            <Divider sx={{ my: 1 }} />
                            {toggle('Restrict logins to business hours', 'loginBusinessHoursOnly')}
                        </Box>
                    </Paper>
                </Grid>

                {/* API Keys & Audit */}
                <Grid item xs={12} md={6}>
                    <Paper sx={cardSx}>
                        <SectionHead icon={KeyRound} title="API Keys & Audit" color="#F59E0B" groupKey="api" subtitle="External access & retention" />
                        <Box sx={{ p: 2.5, pt: 1 }}>
                            {numField('API Key Expiry (days)', 'apiKeyExpiryDays', '0 = keys never expire (not recommended)')}
                            {numField('Audit Log Retention (days)', 'auditRetentionDays', 'Older audit entries are purged', 1)}
                            <Divider sx={{ my: 2 }} />
                            {toggle('Mask PII (card numbers, emails) in the UI', 'maskPiiInUi')}
                        </Box>
                    </Paper>
                </Grid>

                {/* Live Preview */}
                <Grid item xs={12}>
                    <Paper sx={{ ...cardSx, bgcolor: isDark ? '#16162a' : '#F9FAFB' }}>
                        <SectionHead icon={FileText} title="Active Policy Preview" color="#64748B" subtitle="What end-users will experience" />
                        <Box sx={{ p: 2.5, pt: 1, display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                            <Chip icon={<CheckCircle size={14} />} label={`Min ${policy.minLength} chars`} color="primary" variant="outlined" size="small" />
                            {policy.requireUppercase && <Chip icon={<CheckCircle size={14} />} label="Uppercase" color="primary" variant="outlined" size="small" />}
                            {policy.requireLowercase && <Chip icon={<CheckCircle size={14} />} label="Lowercase" color="primary" variant="outlined" size="small" />}
                            {policy.requireDigit && <Chip icon={<CheckCircle size={14} />} label="Digit" color="primary" variant="outlined" size="small" />}
                            {policy.requireSpecialChar && <Chip icon={<CheckCircle size={14} />} label="Special char" color="primary" variant="outlined" size="small" />}
                            {policy.blockBreachedPasswords && <Chip icon={<Ban size={14} />} label="No breached passwords" color="success" variant="outlined" size="small" />}
                            {policy.passwordHistoryCount > 0 && <Chip icon={<AlertTriangle size={14} />} label={`No reuse of last ${policy.passwordHistoryCount}`} color="warning" variant="outlined" size="small" />}
                            {policy.passwordExpiryDays > 0 && <Chip icon={<Clock size={14} />} label={`Expires every ${policy.passwordExpiryDays}d`} color="warning" variant="outlined" size="small" />}
                            <Chip icon={<Lock size={14} />} label={`Lock after ${policy.maxFailedAttempts} fails / ${policy.lockoutDurationMinutes}min`} color="error" variant="outlined" size="small" />
                            <Chip icon={<Timer size={14} />} label={`Idle ${policy.sessionTimeoutMinutes}min · token ${policy.accessTokenMinutes}min`} color="secondary" variant="outlined" size="small" />
                            {(policy.requireMfaForAll || policy.requireMfaForAdmins) && <Chip icon={<Smartphone size={14} />} label={policy.requireMfaForAll ? 'MFA: all users' : 'MFA: admins'} color="success" variant="outlined" size="small" />}
                            {policy.ipAllowlistEnabled && <Chip icon={<Globe size={14} />} label="IP allowlist on" color="info" variant="outlined" size="small" />}
                            {policy.maskPiiInUi && <Chip icon={<Shield size={14} />} label="PII masked" color="default" variant="outlined" size="small" />}
                        </Box>
                    </Paper>
                </Grid>
            </Grid>

            {/* Unlock dialog */}
            <Dialog open={!!unlockDialog} onClose={() => setUnlockDialog(null)}>
                <DialogTitle>Unlock User Account</DialogTitle>
                <DialogContent><Typography>Unlock <strong>{unlockDialog?.username}</strong>? This resets their failed login counter.</Typography></DialogContent>
                <DialogActions>
                    <Button onClick={() => setUnlockDialog(null)}>Cancel</Button>
                    <Button variant="contained" color="success" onClick={() => unlockUser(unlockDialog?.id)}>Unlock</Button>
                </DialogActions>
            </Dialog>

            {/* Revoke-all dialog */}
            <Dialog open={revokeAllDialog} onClose={() => setRevokeAllDialog(false)}>
                <DialogTitle>Revoke All Sessions</DialogTitle>
                <DialogContent><Typography>This signs out <strong>every user</strong> immediately, including you. They'll need to log in again. Continue?</Typography></DialogContent>
                <DialogActions>
                    <Button onClick={() => setRevokeAllDialog(false)}>Cancel</Button>
                    <Button variant="contained" color="error" onClick={revokeAllSessions}>Revoke All</Button>
                </DialogActions>
            </Dialog>

            <Snackbar open={snack.open} autoHideDuration={4000} onClose={() => setSnack(s => ({ ...s, open: false }))}>
                <Alert severity={snack.severity} variant="filled">{snack.msg}</Alert>
            </Snackbar>
        </Box>
    );
};

export default SecuritySettings;
