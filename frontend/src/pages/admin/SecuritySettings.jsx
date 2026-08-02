import { useState, useEffect, useCallback, useMemo } from 'react';
import {
    Shield, Lock, Key, Clock, AlertTriangle, Save, RefreshCw,
    CheckCircle, Smartphone, Globe, Network, FileText, KeyRound,
    Timer, Users, Ban,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Card, Button, Badge, DataTable,
    FormField, FormGrid, Input, Textarea, Switch, useConfirm,
} from '../../components/ui';

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

const PENDING_HINT = 'Stored now. Takes effect once the backend enforcement hook is wired up.';

/* Two-column card layout — collapses to one column on narrow panels. */
const SECTION_GRID = {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))',
    gap: 'var(--space-2xl)',
    alignItems: 'start',
};

const SecuritySettings = () => {
    const { tenantVersion } = useAuth();
    const confirm = useConfirm();

    const [policy, setPolicy] = useState(POLICY_DEFAULTS);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [lockedUsers, setLockedUsers] = useState([]);
    const [lockedLoading, setLockedLoading] = useState(true);

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
        setLockedLoading(true);
        try {
            const res = await api.get('/admin/security/locked-users');
            setLockedUsers(res.data || []);
        } catch { setLockedUsers([]); }
        setLockedLoading(false);
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
            showToast('Security policy saved successfully', 'success');
        } catch (err) {
            showToast('Failed to save: ' + (err.response?.data?.error || err.message), 'error');
        }
        setSaving(false);
    };

    const unlockUser = async (user) => {
        const ok = await confirm({
            title: 'Unlock user account?',
            message: `Unlock ${user.username}? This resets their failed login counter.`,
            confirmLabel: 'Unlock',
            tone: 'warning',
        });
        if (!ok) return;
        try {
            await api.post(`/admin/security/unlock-user/${user.id}`);
            showToast('User unlocked successfully', 'success');
            loadLockedUsers();
        } catch {
            showToast('Failed to unlock user', 'error');
        }
    };

    const revokeAllSessions = async () => {
        const ok = await confirm({
            title: 'Revoke all sessions?',
            message: 'This signs out every user immediately, including you. Everyone will need to log in again.',
            confirmLabel: 'Revoke all',
            tone: 'danger',
        });
        if (!ok) return;
        try {
            await api.post('/admin/security/revoke-all-sessions');
            showToast('All sessions revoked. Users must sign in again.', 'success');
        } catch {
            showToast('Revoke-all endpoint not available yet (needs backend hook).', 'warning');
        }
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

    const grade = score >= 85 ? { label: 'A', text: 'Hardened', color: 'var(--success)' }
        : score >= 70 ? { label: 'B', text: 'Strong', color: 'var(--info)' }
        : score >= 55 ? { label: 'C', text: 'Adequate', color: 'var(--warning)' }
        : { label: 'D', text: 'Needs work', color: 'var(--danger)' };

    /* Warning pill on the cards whose settings are stored but not yet enforced. */
    const pendingBadge = (groupKey) => (ENFORCED.has(groupKey) ? null : (
        <Badge tone="warning" title={PENDING_HINT}>Enforcement pending</Badge>
    ));

    const numField = (label, key, hint, min = 0, extra = {}) => (
        <FormField key={key} label={label} hint={hint}>
            <Input
                type="number"
                min={min}
                value={policy[key]}
                onChange={e => updatePolicy(key, Math.max(min, Number(e.target.value)))}
                {...extra}
            />
        </FormField>
    );

    const toggle = (label, key) => (
        <Switch
            key={key}
            checked={!!policy[key]}
            onChange={e => updatePolicy(key, e.target.checked)}
            label={label}
        />
    );

    const lockedColumns = [
        { key: 'username', header: 'Username', sortable: true, render: u => <strong>{u.username}</strong> },
        {
            key: 'lockedUntil',
            header: 'Locked until',
            sortable: true,
            nowrap: true,
            render: u => (
                <Badge tone="danger">
                    {u.lockedUntil ? new Date(u.lockedUntil).toLocaleString() : 'Indefinite'}
                </Badge>
            ),
        },
        {
            key: '_actions',
            header: '',
            align: 'right',
            nowrap: true,
            render: u => (
                <Button size="sm" icon={CheckCircle} onClick={() => unlockUser(u)}>
                    Unlock
                </Button>
            ),
        },
    ];

    if (loading) {
        return (
            <Page title="Security settings" icon={Shield}>
                <Card pad>
                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', margin: 0 }}>
                        Loading security settings…
                    </p>
                </Card>
            </Page>
        );
    }

    return (
        <Page
            title="Security settings"
            subtitle="Password, lockout, session, MFA and network policy for this tenant."
            icon={Shield}
            actions={
                <>
                    <Button icon={RefreshCw} onClick={() => setPolicy(POLICY_DEFAULTS)}>Reset</Button>
                    <Button variant="primary" icon={Save} loading={saving} onClick={savePolicy}>
                        Save policy
                    </Button>
                </>
            }
        >
            <Stack gap="md">
                {/* ── Posture score ─────────────────────────────────────────── */}
                <Card pad>
                    <div className="ui-row" style={{ gap: 'var(--space-2xl)' }}>
                        <div style={{ position: 'relative', width: 96, height: 96, flexShrink: 0 }}>
                            <svg width="96" height="96" viewBox="0 0 96 96">
                                <circle cx="48" cy="48" r="42" fill="none" stroke="var(--border)" strokeWidth="8" />
                                <circle
                                    cx="48" cy="48" r="42" fill="none"
                                    stroke={grade.color} strokeWidth="8" strokeLinecap="round"
                                    strokeDasharray={`${(score / 100) * 2 * Math.PI * 42} ${2 * Math.PI * 42}`}
                                    transform="rotate(-90 48 48)"
                                    style={{ transition: 'stroke-dasharray .8s ease' }}
                                />
                            </svg>
                            <div style={{
                                position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
                                alignItems: 'center', justifyContent: 'center',
                            }}>
                                <span style={{ fontSize: '1.7rem', fontWeight: 800, lineHeight: 1, color: grade.color }}>
                                    {grade.label}
                                </span>
                                <span style={{ fontSize: '0.72rem', fontWeight: 600, color: 'var(--text-secondary)' }}>
                                    {score}/100
                                </span>
                            </div>
                        </div>
                        <div style={{ flex: 1, minWidth: 200 }}>
                            <h3 className="ui-card__title">Security posture: {grade.text}</h3>
                            <p className="ui-card__subtitle">
                                Scored from the policy below. Adjust the settings, then save to apply them.
                            </p>
                        </div>
                    </div>
                </Card>

                <div style={SECTION_GRID}>
                    {/* ── Password policy ───────────────────────────────────── */}
                    <Card
                        pad
                        title="Password policy"
                        subtitle="Composition and rotation rules"
                        actions={pendingBadge('password')}
                    >
                        <Stack gap="sm">
                            <FormGrid cols={2}>
                                {numField('Minimum length', 'minLength', 'Between 6 and 32 characters.', 6, { max: 32 })}
                                {numField('Password history count', 'passwordHistoryCount', 'Block reuse of the last N passwords.')}
                                {numField('Password expiry (days)', 'passwordExpiryDays', '0 means the password never expires.')}
                                {numField('Minimum password age (hours)', 'minPasswordAgeHours', 'Stops rapid cycling to defeat history.')}
                            </FormGrid>
                            <Stack gap="sm">
                                {toggle('Require uppercase (A-Z)', 'requireUppercase')}
                                {toggle('Require lowercase (a-z)', 'requireLowercase')}
                                {toggle('Require digit (0-9)', 'requireDigit')}
                                {toggle('Require special character (!@#$%)', 'requireSpecialChar')}
                                {toggle('Block common and breached passwords', 'blockBreachedPasswords')}
                                {toggle('Block username or email inside password', 'blockUserInfoInPassword')}
                                {toggle('Force password change on first login', 'forceChangeOnFirstLogin')}
                            </Stack>
                        </Stack>
                    </Card>

                    {/* ── Lockout and rate limiting ─────────────────────────── */}
                    <Card
                        pad
                        title="Lockout and rate limiting"
                        subtitle="Brute-force defences"
                        actions={pendingBadge('lockout')}
                    >
                        <FormGrid cols={2}>
                            {numField('Max failed attempts', 'maxFailedAttempts', 'Lock the account after this many failures.', 1)}
                            {numField('Lockout duration (minutes)', 'lockoutDurationMinutes', 'Auto-unlock after this many minutes.', 1)}
                            {numField('Rate limit (requests per minute)', 'rateLimitPerMinute', 'Per IP and username login throttle.', 1)}
                            {numField('CAPTCHA after N failures', 'captchaAfterFailures', '0 disables the CAPTCHA challenge.')}
                        </FormGrid>
                    </Card>

                    {/* ── Sessions and tokens ───────────────────────────────── */}
                    <Card
                        pad
                        title="Sessions and tokens"
                        subtitle="JWT lifetimes and concurrency"
                        actions={pendingBadge('session')}
                    >
                        <Stack gap="sm">
                            <FormGrid cols={2}>
                                {numField('Session idle timeout (minutes)', 'sessionTimeoutMinutes', 'Auto-logout after inactivity.', 5)}
                                {numField('Access token TTL (minutes)', 'accessTokenMinutes', 'Short-lived bearer token lifetime.', 1)}
                                {numField('Refresh token TTL (days)', 'refreshTokenDays', 'Rotation window for refresh tokens.', 1)}
                                {numField('Max concurrent sessions per user', 'maxConcurrentSessions', '0 means unlimited.')}
                            </FormGrid>
                            <Button variant="danger" icon={Users} block onClick={revokeAllSessions}>
                                Revoke all active sessions
                            </Button>
                        </Stack>
                    </Card>

                    {/* ── Multi-factor authentication ───────────────────────── */}
                    <Card
                        pad
                        title="Multi-factor authentication"
                        subtitle="TOTP and authenticator apps"
                        actions={pendingBadge('mfa')}
                    >
                        <Stack gap="sm">
                            <Stack gap="sm">
                                {toggle('Require MFA for admins', 'requireMfaForAdmins')}
                                {toggle('Require MFA for all users', 'requireMfaForAll')}
                            </Stack>
                            <FormGrid cols={2}>
                                {numField('Enrollment grace period (days)', 'mfaGraceDays', 'Days a new user can sign in before MFA is mandatory.')}
                                {numField('Trusted device duration (days)', 'trustedDeviceDays', 'Skip MFA on a remembered device for N days.')}
                            </FormGrid>
                        </Stack>
                    </Card>

                    {/* ── Network and access ────────────────────────────────── */}
                    <Card
                        pad
                        title="Network and access"
                        subtitle="Where and when logins are allowed"
                        actions={pendingBadge('network')}
                    >
                        <Stack gap="sm">
                            {toggle('Enable IP allowlist', 'ipAllowlistEnabled')}
                            <FormField
                                label="Allowed IPs and CIDR ranges"
                                hint="Comma or newline separated. Empty means all IPs when the allowlist is disabled."
                            >
                                <Textarea
                                    rows={2}
                                    placeholder="203.0.113.0/24, 198.51.100.42"
                                    value={policy.ipAllowlist}
                                    onChange={e => updatePolicy('ipAllowlist', e.target.value)}
                                    disabled={!policy.ipAllowlistEnabled}
                                />
                            </FormField>
                            {toggle('Restrict logins to business hours', 'loginBusinessHoursOnly')}
                        </Stack>
                    </Card>

                    {/* ── API keys and audit ────────────────────────────────── */}
                    <Card
                        pad
                        title="API keys and audit"
                        subtitle="External access and retention"
                        actions={pendingBadge('api')}
                    >
                        <Stack gap="sm">
                            <FormGrid cols={2}>
                                {numField('API key expiry (days)', 'apiKeyExpiryDays', '0 means keys never expire, which is not recommended.')}
                                {numField('Audit log retention (days)', 'auditRetentionDays', 'Older audit entries are purged.', 1)}
                            </FormGrid>
                            {toggle('Mask PII (card numbers, emails) in the UI', 'maskPiiInUi')}
                        </Stack>
                    </Card>
                </div>

                {/* ── Locked accounts ───────────────────────────────────────── */}
                <Card
                    title="Locked accounts"
                    subtitle={`${lockedUsers.length} account(s) currently locked out`}
                    actions={<Button size="sm" icon={RefreshCw} onClick={loadLockedUsers}>Refresh</Button>}
                >
                    <DataTable
                        columns={lockedColumns}
                        rows={lockedUsers}
                        rowKey={u => u.id}
                        loading={lockedLoading}
                        defaultSort={{ key: 'username', dir: 'asc' }}
                        empty={
                            <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                                No locked accounts.
                            </div>
                        }
                    />
                </Card>

                {/* ── Active policy preview ─────────────────────────────────── */}
                <Card pad title="Active policy preview" subtitle="What end-users will experience">
                    <div className="ui-row">
                        <Badge tone="brand" icon={CheckCircle}>Min {policy.minLength} chars</Badge>
                        {policy.requireUppercase && <Badge tone="brand" icon={CheckCircle}>Uppercase</Badge>}
                        {policy.requireLowercase && <Badge tone="brand" icon={CheckCircle}>Lowercase</Badge>}
                        {policy.requireDigit && <Badge tone="brand" icon={CheckCircle}>Digit</Badge>}
                        {policy.requireSpecialChar && <Badge tone="brand" icon={CheckCircle}>Special char</Badge>}
                        {policy.blockBreachedPasswords && <Badge tone="success" icon={Ban}>No breached passwords</Badge>}
                        {policy.passwordHistoryCount > 0 && (
                            <Badge tone="warning" icon={AlertTriangle}>
                                No reuse of last {policy.passwordHistoryCount}
                            </Badge>
                        )}
                        {policy.passwordExpiryDays > 0 && (
                            <Badge tone="warning" icon={Clock}>Expires every {policy.passwordExpiryDays}d</Badge>
                        )}
                        <Badge tone="danger" icon={Lock}>
                            Lock after {policy.maxFailedAttempts} fails / {policy.lockoutDurationMinutes}min
                        </Badge>
                        <Badge tone="info" icon={Timer}>
                            Idle {policy.sessionTimeoutMinutes}min · token {policy.accessTokenMinutes}min
                        </Badge>
                        {(policy.requireMfaForAll || policy.requireMfaForAdmins) && (
                            <Badge tone="success" icon={Smartphone}>
                                {policy.requireMfaForAll ? 'MFA: all users' : 'MFA: admins'}
                            </Badge>
                        )}
                        {policy.ipAllowlistEnabled && <Badge tone="info" icon={Globe}>IP allowlist on</Badge>}
                        {policy.maskPiiInUi && <Badge icon={Shield}>PII masked</Badge>}
                        {policy.apiKeyExpiryDays > 0 && (
                            <Badge tone="warning" icon={KeyRound}>API keys expire in {policy.apiKeyExpiryDays}d</Badge>
                        )}
                        {policy.loginBusinessHoursOnly && (
                            <Badge tone="info" icon={Network}>Business hours only</Badge>
                        )}
                        {policy.captchaAfterFailures > 0 && (
                            <Badge tone="warning" icon={Key}>CAPTCHA after {policy.captchaAfterFailures}</Badge>
                        )}
                        <Badge icon={FileText}>Audit kept {policy.auditRetentionDays}d</Badge>
                    </div>
                </Card>
            </Stack>
        </Page>
    );
};

export default SecuritySettings;
