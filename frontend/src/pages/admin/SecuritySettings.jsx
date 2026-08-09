import { useState, useEffect, useCallback } from 'react';
import {
    Shield, Lock, KeyRound, Clock, Save, RefreshCw,
    CheckCircle, Smartphone, Globe, Users, ShieldAlert,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Card, Button, Badge, DataTable, Tabs,
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
const ENFORCED = new Set(['password', 'lockout', 'sessions']);

const PENDING_HINT = 'Stored now. Takes effect once the backend enforcement hook is wired up.';

const SecuritySettings = () => {
    const { tenantVersion } = useAuth();
    const confirm = useConfirm();

    const [policy, setPolicy] = useState(POLICY_DEFAULTS);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [lockedUsers, setLockedUsers] = useState([]);
    const [lockedLoading, setLockedLoading] = useState(true);
    const [activeTab, setActiveTab] = useState('password');

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
            showToast('Security policy saved', 'success');
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
            showToast('User unlocked', 'success');
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

    /* Warning pill for groups whose settings are stored but not yet enforced. */
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

    const toggle = (label, key, hint) => (
        <Switch
            key={key}
            checked={!!policy[key]}
            onChange={e => updatePolicy(key, e.target.checked)}
            label={label}
            title={hint}
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

    const tabs = [
        { key: 'password', label: 'Password', icon: KeyRound },
        { key: 'lockout', label: 'Sign-in defense', icon: Lock },
        { key: 'sessions', label: 'Sessions', icon: Clock },
        { key: 'mfa', label: 'MFA', icon: Smartphone },
        { key: 'network', label: 'Network', icon: Globe },
        { key: 'api', label: 'API and audit', icon: Shield },
        { key: 'locked', label: 'Locked accounts', icon: ShieldAlert, count: lockedUsers.length },
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
                    <Button icon={RefreshCw} onClick={() => setPolicy(POLICY_DEFAULTS)}>Reset to defaults</Button>
                    <Button variant="primary" icon={Save} loading={saving} onClick={savePolicy}>
                        Save policy
                    </Button>
                </>
            }
        >
            <Stack gap="md">
                {/* ── Policy tabs — one group at a time ────────────────────── */}
                <Tabs tabs={tabs} active={activeTab} onChange={setActiveTab} />

                {activeTab === 'password' && (
                    <Card pad title="Password policy" subtitle="Composition and rotation rules" actions={pendingBadge('password')}>
                        <Stack gap="md">
                            <FormGrid cols={2}>
                                {numField('Minimum length', 'minLength', 'Between 6 and 32 characters.', 6, { max: 32 })}
                                {numField('Password history count', 'passwordHistoryCount', 'Block reuse of the last N passwords.')}
                                {numField('Password expiry (days)', 'passwordExpiryDays', '0 means the password never expires.')}
                                {numField('Minimum password age (hours)', 'minPasswordAgeHours', 'Stops rapid cycling to defeat history.')}
                            </FormGrid>
                            <FormGrid cols={2}>
                                {toggle('Require uppercase (A-Z)', 'requireUppercase')}
                                {toggle('Require lowercase (a-z)', 'requireLowercase')}
                                {toggle('Require digit (0-9)', 'requireDigit')}
                                {toggle('Require special character (!@#$%)', 'requireSpecialChar')}
                                {toggle('Block breached passwords', 'blockBreachedPasswords')}
                                {toggle('Block user info in password', 'blockUserInfoInPassword')}
                                {toggle('Force change on first login', 'forceChangeOnFirstLogin')}
                            </FormGrid>
                        </Stack>
                    </Card>
                )}

                {activeTab === 'lockout' && (
                    <Card pad title="Sign-in defense" subtitle="Brute-force lockout and rate limiting" actions={pendingBadge('lockout')}>
                        <FormGrid cols={2}>
                            {numField('Max failed attempts', 'maxFailedAttempts', 'Lock the account after this many failures.', 1)}
                            {numField('Lockout duration (minutes)', 'lockoutDurationMinutes', 'Auto-unlock after this many minutes.', 1)}
                            {numField('Rate limit (requests per minute)', 'rateLimitPerMinute', 'Per IP and username login throttle.', 1)}
                            {numField('CAPTCHA after N failures', 'captchaAfterFailures', '0 disables the CAPTCHA challenge.')}
                        </FormGrid>
                    </Card>
                )}

                {activeTab === 'sessions' && (
                    <Card pad title="Sessions and tokens" subtitle="JWT lifetimes and concurrency" actions={pendingBadge('sessions')}>
                        <Stack gap="md">
                            <FormGrid cols={2}>
                                {numField('Session idle timeout (minutes)', 'sessionTimeoutMinutes', 'Auto-logout after inactivity.', 5)}
                                {numField('Access token TTL (minutes)', 'accessTokenMinutes', 'Short-lived bearer token lifetime.', 1)}
                                {numField('Refresh token TTL (days)', 'refreshTokenDays', 'Rotation window for refresh tokens.', 1)}
                                {numField('Max concurrent sessions per user', 'maxConcurrentSessions', '0 means unlimited.')}
                            </FormGrid>
                            <div>
                                <Button variant="danger" icon={Users} onClick={revokeAllSessions}>
                                    Revoke all active sessions
                                </Button>
                                <p className="ui-field__hint" style={{ marginTop: 6 }}>
                                    Signs out every user immediately, including you.
                                </p>
                            </div>
                        </Stack>
                    </Card>
                )}

                {activeTab === 'mfa' && (
                    <Card pad title="Multi-factor authentication" subtitle="TOTP and authenticator apps" actions={pendingBadge('mfa')}>
                        <Stack gap="md">
                            <FormGrid cols={2}>
                                {toggle('Require MFA for admins', 'requireMfaForAdmins')}
                                {toggle('Require MFA for all users', 'requireMfaForAll')}
                            </FormGrid>
                            <FormGrid cols={2}>
                                {numField('Enrollment grace period (days)', 'mfaGraceDays', 'Days a new user can sign in before MFA is mandatory.')}
                                {numField('Trusted device duration (days)', 'trustedDeviceDays', 'Skip MFA on a remembered device for N days.')}
                            </FormGrid>
                        </Stack>
                    </Card>
                )}

                {activeTab === 'network' && (
                    <Card pad title="Network and access" subtitle="Where and when logins are allowed" actions={pendingBadge('network')}>
                        <Stack gap="md">
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
                )}

                {activeTab === 'api' && (
                    <Card pad title="API keys and audit" subtitle="External access and retention" actions={pendingBadge('api')}>
                        <Stack gap="md">
                            <FormGrid cols={2}>
                                {numField('API key expiry (days)', 'apiKeyExpiryDays', '0 means keys never expire, which is not recommended.')}
                                {numField('Audit log retention (days)', 'auditRetentionDays', 'Older audit entries are purged.', 1)}
                            </FormGrid>
                            {toggle('Mask PII (card numbers, emails) in the UI', 'maskPiiInUi')}
                        </Stack>
                    </Card>
                )}

                {activeTab === 'locked' && (
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
                                    No locked accounts. Lockouts from failed sign-ins appear here.
                                </div>
                            }
                        />
                    </Card>
                )}
            </Stack>
        </Page>
    );
};

export default SecuritySettings;
