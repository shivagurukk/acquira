import React, { useState, useEffect, useCallback } from 'react';
import {
    Box, Typography, Paper, Grid, TextField, Button, Switch,
    FormControlLabel, Divider, Alert, Snackbar, Slider, Chip,
    Table, TableHead, TableRow, TableCell, TableBody,
    IconButton, Tooltip, useTheme, Dialog, DialogTitle, DialogContent,
    DialogActions
} from '@mui/material';
import {
    Shield, Lock, Key, Clock, AlertTriangle, Save, RefreshCw,
    CheckCircle, Eye
} from 'lucide-react';
import api from '../../api/axios';

const POLICY_DEFAULTS = {
    minLength: 8, maxLength: 128, requireUppercase: true, requireLowercase: true,
    requireDigit: true, requireSpecialChar: true, passwordHistoryCount: 5,
    maxFailedAttempts: 5, lockoutDurationMinutes: 15, passwordExpiryDays: 90,
    sessionTimeoutMinutes: 30, forceChangeOnFirstLogin: true
};

const SecuritySettings = () => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';

    const [policy, setPolicy] = useState(POLICY_DEFAULTS);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [snack, setSnack] = useState({ open: false, msg: '', severity: 'success' });
    const [lockedUsers, setLockedUsers] = useState([]);
    const [unlockDialog, setUnlockDialog] = useState(null);

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
                        merged[prop] = val === 'true' ? true : val === 'false' ? false : isNaN(val) ? val : Number(val);
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

    useEffect(() => { loadSettings(); loadLockedUsers(); }, [loadSettings, loadLockedUsers]);

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

    const updatePolicy = (key, value) => setPolicy(p => ({ ...p, [key]: value }));

    const cardSx = { p: 3, borderRadius: 2, border: `1px solid ${isDark ? '#333' : '#E5E7EB'}`, bgcolor: isDark ? '#1a1a2e' : '#fff' };

    const strengthPreview = () => {
        let score = 0;
        if (policy.minLength >= 12) score += 2; else if (policy.minLength >= 8) score += 1;
        if (policy.requireUppercase) score++;
        if (policy.requireLowercase) score++;
        if (policy.requireDigit) score++;
        if (policy.requireSpecialChar) score += 2;
        if (policy.passwordHistoryCount >= 5) score++;
        if (policy.passwordExpiryDays > 0 && policy.passwordExpiryDays <= 90) score++;
        if (score >= 8) return { label: 'Strong', color: '#10B981' };
        if (score >= 5) return { label: 'Medium', color: '#F59E0B' };
        return { label: 'Weak', color: '#EF4444' };
    };
    const strength = strengthPreview();

    if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><Typography>Loading security settings...</Typography></Box>;

    return (
        <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1200, mx: 'auto' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3 }}>
                <Shield size={28} color={theme.palette.primary.main} />
                <Typography variant="h5" fontWeight={700}>Security Settings</Typography>
                <Chip label={`Strength: ${strength.label}`} sx={{ ml: 'auto', bgcolor: strength.color + '20', color: strength.color, fontWeight: 600 }} />
            </Box>

            <Grid container spacing={3}>
                {/* Password Policy */}
                <Grid item xs={12} md={6}>
                    <Paper sx={cardSx}>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2.5 }}>
                            <Key size={20} />
                            <Typography variant="h6" fontWeight={600}>Password Policy</Typography>
                        </Box>
                        <Box sx={{ mb: 2 }}>
                            <Typography variant="body2" color="text.secondary" gutterBottom>Minimum Length: {policy.minLength}</Typography>
                            <Slider value={policy.minLength} min={6} max={32} step={1}
                                onChange={(_, v) => updatePolicy('minLength', v)}
                                marks={[{ value: 8, label: '8' }, { value: 16, label: '16' }, { value: 32, label: '32' }]} sx={{ mt: 1 }} />
                        </Box>
                        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                            <FormControlLabel control={<Switch checked={policy.requireUppercase} onChange={e => updatePolicy('requireUppercase', e.target.checked)} />} label="Require uppercase letter (A-Z)" />
                            <FormControlLabel control={<Switch checked={policy.requireLowercase} onChange={e => updatePolicy('requireLowercase', e.target.checked)} />} label="Require lowercase letter (a-z)" />
                            <FormControlLabel control={<Switch checked={policy.requireDigit} onChange={e => updatePolicy('requireDigit', e.target.checked)} />} label="Require digit (0-9)" />
                            <FormControlLabel control={<Switch checked={policy.requireSpecialChar} onChange={e => updatePolicy('requireSpecialChar', e.target.checked)} />} label="Require special character (!@#$%)" />
                        </Box>
                        <Divider sx={{ my: 2 }} />
                        <TextField fullWidth type="number" label="Password History Count" helperText="Block reuse of last N passwords"
                            value={policy.passwordHistoryCount} onChange={e => updatePolicy('passwordHistoryCount', Math.max(0, Number(e.target.value)))} size="small" sx={{ mb: 2 }} />
                        <TextField fullWidth type="number" label="Password Expiry (days)" helperText="0 = never expires"
                            value={policy.passwordExpiryDays} onChange={e => updatePolicy('passwordExpiryDays', Math.max(0, Number(e.target.value)))} size="small" sx={{ mb: 2 }} />
                        <FormControlLabel control={<Switch checked={policy.forceChangeOnFirstLogin} onChange={e => updatePolicy('forceChangeOnFirstLogin', e.target.checked)} />} label="Force password change on first login" />
                    </Paper>
                </Grid>

                {/* Account Lockout */}
                <Grid item xs={12} md={6}>
                    <Paper sx={cardSx}>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2.5 }}>
                            <Lock size={20} />
                            <Typography variant="h6" fontWeight={600}>Account Lockout</Typography>
                        </Box>
                        <TextField fullWidth type="number" label="Max Failed Attempts" helperText="Lock account after this many failures"
                            value={policy.maxFailedAttempts} onChange={e => updatePolicy('maxFailedAttempts', Math.max(1, Number(e.target.value)))} size="small" sx={{ mb: 2.5 }} />
                        <TextField fullWidth type="number" label="Lockout Duration (minutes)" helperText="Auto-unlock after this many minutes"
                            value={policy.lockoutDurationMinutes} onChange={e => updatePolicy('lockoutDurationMinutes', Math.max(1, Number(e.target.value)))} size="small" sx={{ mb: 2.5 }} />
                        <TextField fullWidth type="number" label="Session Timeout (minutes)" helperText="Auto-logout after inactivity"
                            value={policy.sessionTimeoutMinutes} onChange={e => updatePolicy('sessionTimeoutMinutes', Math.max(5, Number(e.target.value)))} size="small" />
                        <Divider sx={{ my: 2.5 }} />
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
                    </Paper>
                </Grid>

                {/* Policy Preview */}
                <Grid item xs={12}>
                    <Paper sx={{ ...cardSx, bgcolor: isDark ? '#1a1a2e' : '#F9FAFB' }}>
                        <Typography variant="subtitle2" fontWeight={600} sx={{ mb: 1 }}>Password Requirements Preview</Typography>
                        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                            <Chip icon={<CheckCircle size={14} />} label={`Min ${policy.minLength} characters`} color="primary" variant="outlined" size="small" />
                            {policy.requireUppercase && <Chip icon={<CheckCircle size={14} />} label="Uppercase (A-Z)" color="primary" variant="outlined" size="small" />}
                            {policy.requireLowercase && <Chip icon={<CheckCircle size={14} />} label="Lowercase (a-z)" color="primary" variant="outlined" size="small" />}
                            {policy.requireDigit && <Chip icon={<CheckCircle size={14} />} label="Digit (0-9)" color="primary" variant="outlined" size="small" />}
                            {policy.requireSpecialChar && <Chip icon={<CheckCircle size={14} />} label="Special char (!@#$)" color="primary" variant="outlined" size="small" />}
                            {policy.passwordHistoryCount > 0 && <Chip icon={<AlertTriangle size={14} />} label={`No reuse of last ${policy.passwordHistoryCount}`} color="warning" variant="outlined" size="small" />}
                            {policy.passwordExpiryDays > 0 && <Chip icon={<Clock size={14} />} label={`Expires every ${policy.passwordExpiryDays} days`} color="warning" variant="outlined" size="small" />}
                            <Chip icon={<Lock size={14} />} label={`Lock after ${policy.maxFailedAttempts} failures for ${policy.lockoutDurationMinutes}min`} color="error" variant="outlined" size="small" />
                        </Box>
                    </Paper>
                </Grid>
            </Grid>

            <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 3, gap: 2 }}>
                <Button variant="outlined" startIcon={<RefreshCw size={16} />} onClick={() => setPolicy(POLICY_DEFAULTS)}>Reset to Defaults</Button>
                <Button variant="contained" startIcon={<Save size={16} />} onClick={savePolicy} disabled={saving} sx={{ minWidth: 180 }}>
                    {saving ? 'Saving...' : 'Save Security Policy'}
                </Button>
            </Box>

            <Dialog open={!!unlockDialog} onClose={() => setUnlockDialog(null)}>
                <DialogTitle>Unlock User Account</DialogTitle>
                <DialogContent><Typography>Unlock <strong>{unlockDialog?.username}</strong>? This resets their failed login counter.</Typography></DialogContent>
                <DialogActions>
                    <Button onClick={() => setUnlockDialog(null)}>Cancel</Button>
                    <Button variant="contained" color="success" onClick={() => unlockUser(unlockDialog?.id)}>Unlock</Button>
                </DialogActions>
            </Dialog>

            <Snackbar open={snack.open} autoHideDuration={4000} onClose={() => setSnack(s => ({ ...s, open: false }))}>
                <Alert severity={snack.severity} variant="filled">{snack.msg}</Alert>
            </Snackbar>
        </Box>
    );
};

export default SecuritySettings;
