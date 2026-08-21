import { useState, useEffect, useCallback } from 'react';
import {
    Key, Plus, Trash2, Copy, Code, Activity, Edit2, ShieldAlert,
    RefreshCw, ExternalLink,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Card, Button, Badge, StatusBadge, Alert, Tabs, DataTable, Modal,
    FormField, FormGrid, Input, Select, Checkbox, useConfirm,
} from '../../components/ui';

/**
 * Admin > API management.
 *
 * Three concerns:
 *  1. API keys — CRUD over external keys. The full key is returned once, at
 *     creation, and only a hash is stored; the table shows the prefix only.
 *  2. Documentation — static endpoint registry mirroring ExternalDataApiController
 *     (/api/v1) and ExternalReportApiController (/api/external/reports).
 *  3. Usage — tenant-wide summary plus per-key request detail.
 */

const PERMISSIONS = [
    { key: 'read:transactions', label: 'Read transactions', desc: 'Query raw transaction rows' },
    { key: 'read:merchants', label: 'Read merchants', desc: 'Query merchant/store data' },
    { key: 'read:analytics', label: 'Read analytics', desc: 'Volume and scheme breakdowns' },
    { key: 'read:finance', label: 'Read finance', desc: 'Financial summary (MSF, interchange, VAT)' },
    { key: 'read:reports', label: 'Read reports', desc: 'Download branded PDF statements' },
    { key: 'write:upload', label: 'Upload files', desc: 'Upload transaction/merchant files (reserved)' },
];

// Real endpoint registry — drives the Documentation tab. Mirrors ExternalDataApiController
// (/api/v1) and ExternalReportApiController (/api/external/reports).
const API_DOCS = [
    { group: 'Merchants', scope: 'read:merchants', rows: [
        { method: 'GET', path: '/api/v1/merchants', desc: 'List merchants (+ store count)', params: 'status, search, page, size' },
        { method: 'GET', path: '/api/v1/merchants/{mid}/summary', desc: 'Per-merchant settlement totals', params: 'startDate, endDate' },
    ]},
    { group: 'Transactions', scope: 'read:transactions', rows: [
        { method: 'GET', path: '/api/v1/transactions', desc: 'Raw transactions (date range required, ≤92d)', params: 'startDate*, endDate*, mid, sid, page, size' },
    ]},
    { group: 'Analytics', scope: 'read:analytics', rows: [
        { method: 'GET', path: '/api/v1/analytics/volume', desc: 'Volume trend (day/month/scheme)', params: 'startDate, endDate, groupBy' },
        { method: 'GET', path: '/api/v1/analytics/scheme-breakdown', desc: 'Scheme × card-type breakdown', params: 'startDate, endDate' },
    ]},
    { group: 'Finance', scope: 'read:finance', rows: [
        { method: 'GET', path: '/api/v1/finance/summary', desc: 'MSF, interchange, scheme fee, VAT, net margin', params: 'startDate, endDate' },
    ]},
    { group: 'Reports (PDF)', scope: 'read:reports', rows: [
        { method: 'GET', path: '/api/external/reports/list', desc: 'List statement PDFs for a month', params: 'year, month' },
        { method: 'GET', path: '/api/external/reports/status', desc: 'Report count + total size', params: 'year, month' },
        { method: 'GET', path: '/api/external/reports/download', desc: 'Download one statement', params: 'file*, year, month' },
        { method: 'GET', path: '/api/external/reports/merchant/{mid}', desc: 'Download a merchant statement', params: 'year, month' },
        { method: 'GET', path: '/api/external/reports/download-all', desc: 'ZIP of all statements', params: 'year, month' },
    ]},
];

const EXAMPLE_REQUEST = `curl -H "X-API-Key: aqr_your_key_here" \\
     "https://your-domain/api/v1/finance/summary?startDate=2026-06-01&endDate=2026-06-30"

# Response
{
  "startDate": "2026-06-01",
  "endDate": "2026-06-30",
  "totals": {
    "txns": 128340, "volume": 4820150.50, "msf": 68420.30,
    "interchange": 21030.10, "scheme_fee": 8110.00, "vat": 3421.05,
    "net_revenue": 35859.15
  }
}`;

const MONO = 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';

const emptyForm = { name: '', permissions: ['read:transactions', 'read:merchants'], expiresAt: '', rateLimitPerMinute: 120, allowedIps: '' };

const docColumns = [
    {
        key: 'method',
        header: 'Method',
        width: 90,
        render: d => <Badge tone="info" mono>{d.method}</Badge>,
    },
    { key: 'path', header: 'Endpoint', mono: true, width: 300 },
    { key: 'desc', header: 'Description' },
    { key: 'params', header: 'Parameters', muted: true },
];

const ApiManagement = () => {
    const { tenantVersion } = useAuth();
    const confirm = useConfirm();
    const [keys, setKeys] = useState([]);
    const [loading, setLoading] = useState(true);
    const [dialog, setDialog] = useState(null); // 'create' | 'edit' | null
    const [form, setForm] = useState(emptyForm);
    const [editId, setEditId] = useState(null);
    const [createdKey, setCreatedKey] = useState(null);
    const [saving, setSaving] = useState(false);
    const [tab, setTab] = useState('keys'); // 'keys' | 'docs' | 'usage'

    // Usage state
    const [summary, setSummary] = useState(null);
    const [summaryLoading, setSummaryLoading] = useState(false);
    const [usageDetail, setUsageDetail] = useState(null); // { keyId, data }
    const [usageLoading, setUsageLoading] = useState(false);

    const loadKeys = useCallback(async () => {
        setLoading(true);
        try { const res = await api.get('/admin/api-keys'); setKeys(res.data || []); }
        catch (e) { showToast('Could not load API keys: ' + (e.response?.data?.error || e.message), 'error'); setKeys([]); }
        finally { setLoading(false); }
    }, []);
    useEffect(() => { loadKeys(); }, [loadKeys, tenantVersion]);

    const loadSummary = useCallback(async () => {
        setSummaryLoading(true);
        try { const res = await api.get('/admin/api-keys/usage-summary'); setSummary(res.data); }
        catch { setSummary(null); }
        finally { setSummaryLoading(false); }
    }, []);
    useEffect(() => { if (tab === 'usage') loadSummary(); }, [tab, loadSummary, tenantVersion]);

    const openCreate = () => { setForm(emptyForm); setEditId(null); setDialog('create'); };
    const openEdit = (k) => {
        setForm({
            name: k.name || '',
            permissions: Array.isArray(k.permissions) ? k.permissions : [],
            expiresAt: k.expiresAt ? String(k.expiresAt).substring(0, 10) : '',
            rateLimitPerMinute: k.rateLimitPerMinute || 120,
            allowedIps: k.allowedIps || '',
        });
        setEditId(k.id);
        setDialog('edit');
    };

    const save = async (e) => {
        e?.preventDefault();
        if (!form.name.trim()) { showToast('Name is required', 'error'); return; }
        if (form.permissions.length === 0) { showToast('Select at least one permission', 'error'); return; }
        const payload = {
            name: form.name.trim(),
            permissions: form.permissions,
            expiresAt: form.expiresAt || null,
            rateLimitPerMinute: Number(form.rateLimitPerMinute) || 120,
            allowedIps: form.allowedIps.trim() || null,
        };
        setSaving(true);
        try {
            if (dialog === 'edit') {
                await api.put(`/admin/api-keys/${editId}`, payload);
                showToast('API key updated', 'success'); setDialog(null); loadKeys();
            } else {
                const res = await api.post('/admin/api-keys', payload);
                setCreatedKey(res.data); setDialog(null); loadKeys();
            }
        } catch (e) {
            // No fake-key fallback — surface the real error so the operator knows the key was NOT created.
            showToast((dialog === 'edit' ? 'Update' : 'Create') + ' failed: ' + (e.response?.data?.error || e.message), 'error');
        } finally { setSaving(false); }
    };

    const revokeKey = async (k) => {
        const ok = await confirm({
            title: 'Revoke this API key?',
            message: `Any integration using "${k.name}" stops working immediately and will start receiving 401 responses. This cannot be undone.`,
            confirmLabel: 'Revoke key',
            tone: 'danger',
        });
        if (!ok) return;
        try { await api.delete(`/admin/api-keys/${k.id}`); showToast('API key revoked', 'success'); loadKeys(); }
        catch (e) { showToast('Revoke failed: ' + (e.response?.data?.error || e.message), 'error'); }
    };

    const viewUsage = async (id) => {
        setUsageLoading(true); setUsageDetail({ keyId: id, data: null });
        try { const res = await api.get(`/admin/api-keys/${id}/usage`); setUsageDetail({ keyId: id, data: res.data }); }
        catch (e) { showToast('Could not load usage: ' + (e.response?.data?.error || e.message), 'error'); setUsageDetail(null); }
        finally { setUsageLoading(false); }
    };

    const copyToClipboard = (text) => { if (!text) return; navigator.clipboard.writeText(text); showToast('Copied to clipboard', 'success'); };

    const isExpired = (k) => k.expiresAt && new Date(k.expiresAt) < new Date();

    // ── Column definitions ────────────────────────────────────────────────────
    const keyColumns = [
        {
            key: 'name',
            header: 'Name',
            sortable: true,
            render: k => <strong>{k.name}</strong>,
        },
        {
            key: 'keyPrefix',
            header: 'Key',
            nowrap: true,
            render: k => (
                <span className="ui-row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                    <code style={{ fontFamily: MONO, fontSize: 12 }}>{k.keyPrefix || 'aqr_****...'}</code>
                    <Button
                        variant="ghost"
                        size="sm"
                        iconOnly
                        icon={Copy}
                        onClick={() => copyToClipboard(k.keyPrefix)}
                        aria-label={`Copy key prefix for ${k.name}`}
                    />
                </span>
            ),
        },
        {
            key: 'permissions',
            header: 'Permissions',
            render: k => (
                <span className="ui-row" style={{ gap: 4, maxWidth: 220 }}>
                    {(k.permissions || []).map(p => (
                        <Badge key={p} mono>{p.replace('read:', 'R:').replace('write:', 'W:')}</Badge>
                    ))}
                </span>
            ),
        },
        {
            key: 'rateLimitPerMinute',
            header: 'Limits',
            nowrap: true,
            muted: true,
            render: k => `${k.rateLimitPerMinute || 120}/min${k.allowedIps ? ' • IP-locked' : ''}`,
        },
        {
            key: 'expiresAt',
            header: 'Expiry',
            sortable: true,
            nowrap: true,
            render: k => (
                k.expiresAt
                    ? (isExpired(k)
                        ? <Badge tone="danger">Expired</Badge>
                        : <Badge>{new Date(k.expiresAt).toLocaleDateString()}</Badge>)
                    : <span className="ui-td--muted">Never</span>
            ),
        },
        {
            key: 'lastUsed',
            header: 'Last used',
            sortable: true,
            nowrap: true,
            muted: true,
            render: k => (k.lastUsed ? new Date(k.lastUsed).toLocaleDateString() : 'Never'),
        },
        {
            key: 'requestCount',
            header: 'Requests',
            sortable: true,
            numeric: true,
            align: 'right',
            render: k => (k.requestCount || 0).toLocaleString(),
        },
        {
            key: 'isActive',
            header: 'Status',
            sortable: true,
            render: k => <StatusBadge status={k.isActive ? 'Active' : 'Revoked'} />,
        },
        {
            key: '_actions',
            header: '',
            align: 'right',
            nowrap: true,
            render: k => (
                <>
                    <Button
                        variant="ghost"
                        size="sm"
                        iconOnly
                        icon={Activity}
                        onClick={() => { setTab('usage'); viewUsage(k.id); }}
                        aria-label={`View usage for ${k.name}`}
                    />
                    {k.isActive && (
                        <Button
                            variant="ghost"
                            size="sm"
                            iconOnly
                            icon={Edit2}
                            onClick={() => openEdit(k)}
                            aria-label={`Edit ${k.name}`}
                        />
                    )}
                    {k.isActive && (
                        <Button
                            variant="danger-ghost"
                            size="sm"
                            iconOnly
                            icon={Trash2}
                            onClick={() => revokeKey(k)}
                            aria-label={`Revoke ${k.name}`}
                        />
                    )}
                </>
            ),
        },
    ];

    const topEndpointColumns = [
        { key: 'endpoint', header: 'Endpoint', mono: true },
        {
            key: 'hits',
            header: 'Hits',
            align: 'right',
            numeric: true,
            render: e => Number(e.hits).toLocaleString(),
        },
    ];

    const recentColumns = [
        { key: 'method', header: 'Method', width: 90, render: r => <Badge tone="info" mono>{r.method}</Badge> },
        { key: 'endpoint', header: 'Endpoint', mono: true },
        {
            key: 'status',
            header: 'Status',
            render: r => <Badge tone={r.status >= 400 ? 'danger' : 'success'}>{r.status}</Badge>,
        },
        { key: 'clientIp', header: 'IP', muted: true, mono: true },
        {
            key: 'latencyMs',
            header: 'Latency',
            align: 'right',
            numeric: true,
            render: r => `${r.latencyMs}ms`,
        },
        {
            key: 'createdAt',
            header: 'When',
            nowrap: true,
            muted: true,
            render: r => (r.createdAt ? new Date(r.createdAt).toLocaleString() : '—'),
        },
    ];

    const tabs = [
        { key: 'keys', label: 'API keys', icon: Key, count: keys.length },
        { key: 'docs', label: 'Documentation', icon: Code },
        { key: 'usage', label: 'Usage', icon: Activity },
    ];

    const stats = [
        { label: 'Requests (24h)', value: summary?.requests24h ?? '—' },
        { label: 'Requests (7d)', value: summary?.requests7d ?? '—' },
        { label: 'Errors (7d)', value: summary?.errors7d ?? '—' },
        { label: 'Active keys', value: summary?.activeKeys ?? '—' },
    ];

    return (
        <Page
            title="API management"
            subtitle="External API keys, the endpoint reference they authenticate against, and their usage."
            icon={Code}
            actions={
                <Badge tone="success" dot>
                    {keys.filter(k => k.isActive).length} active
                </Badge>
            }
        >
            <Tabs tabs={tabs} active={tab} onChange={setTab} />

            {/* ── API keys ─────────────────────────────────────────────────── */}
            {tab === 'keys' && (
                <Card>
                    <DataTable
                        columns={keyColumns}
                        rows={keys}
                        rowClassName={k => !k.isActive && 'ui-tr--dimmed'}
                        rowKey={k => k.id}
                        loading={loading}
                        defaultSort={{ key: 'name', dir: 'asc' }}
                        toolbarRight={
                            <Button variant="primary" icon={Plus} onClick={openCreate}>
                                Generate API key
                            </Button>
                        }
                        empty={
                            <div style={{ padding: 'var(--space-3xl)', textAlign: 'center' }}>
                                <Key size={30} style={{ color: 'var(--text-muted)' }} />
                                <p style={{ margin: '10px 0 14px', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                                    No API keys yet.
                                </p>
                                <Button variant="subtle" icon={Plus} onClick={openCreate}>
                                    Generate the first key
                                </Button>
                            </div>
                        }
                    />
                </Card>
            )}

            {/* ── Documentation ────────────────────────────────────────────── */}
            {tab === 'docs' && (
                <Stack gap="md">
                    <Card
                        title="API endpoints"
                        subtitle="All endpoints authenticate with the X-API-Key header. Each is gated by the scope shown, and a key only sees endpoints it was granted."
                        actions={
                            <Button
                                size="sm"
                                icon={ExternalLink}
                                href="/api/v1/openapi.json"
                                target="_blank"
                                rel="noopener"
                            >
                                OpenAPI spec
                            </Button>
                        }
                        pad
                    >
                        <Stack gap="md">
                            <Alert tone="info">
                                Rate limit is per key (default 120/min, configurable). Responses are
                                paginated. <code>/api/v1/transactions</code> requires a date range no
                                wider than 92 days.
                            </Alert>

                            {API_DOCS.map(group => (
                                <div key={group.group}>
                                    <div className="ui-row" style={{ marginBottom: 'var(--space-sm)' }}>
                                        <strong style={{ fontSize: '0.85rem' }}>{group.group}</strong>
                                        <Badge tone="brand" mono>{group.scope}</Badge>
                                    </div>
                                    <DataTable
                                        columns={docColumns}
                                        rows={group.rows}
                                        rowKey={(d, i) => `${group.group}-${i}`}
                                        compact
                                    />
                                </div>
                            ))}
                        </Stack>
                    </Card>

                    <Card title="Example request" pad>
                        <pre
                            style={{
                                margin: 0,
                                padding: 'var(--space-lg)',
                                background: 'var(--bg-subtle)',
                                borderRadius: 'var(--radius-md)',
                                fontSize: 12,
                                lineHeight: 1.6,
                                whiteSpace: 'pre-wrap',
                                fontFamily: MONO,
                                color: 'var(--text-secondary)',
                                overflowX: 'auto',
                            }}
                        >
                            {EXAMPLE_REQUEST}
                        </pre>
                        <p
                            style={{
                                margin: 'var(--space-md) 0 0',
                                fontSize: '0.75rem',
                                color: 'var(--text-secondary)',
                                lineHeight: 1.6,
                            }}
                        >
                            <ShieldAlert size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />
                            Rate-limit headers <code>X-RateLimit-Limit</code> and{' '}
                            <code>X-RateLimit-Remaining</code> are returned on every response, and a{' '}
                            <code>429</code> includes <code>Retry-After</code>.
                        </p>
                    </Card>
                </Stack>
            )}

            {/* ── Usage ────────────────────────────────────────────────────── */}
            {tab === 'usage' && (
                <Stack gap="md">
                    <div className="ui-row ui-row--between">
                        <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                            Tenant-wide totals across all keys.
                        </span>
                        <Button
                            size="sm"
                            icon={RefreshCw}
                            onClick={() => { loadSummary(); if (usageDetail) viewUsage(usageDetail.keyId); }}
                        >
                            Refresh
                        </Button>
                    </div>

                    <div
                        style={{
                            display: 'grid',
                            gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                            gap: 'var(--space-lg)',
                        }}
                    >
                        {stats.map(s => (
                            <Card key={s.label} pad>
                                <p style={{ margin: 0, fontSize: '1.4rem', fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                                    {typeof s.value === 'number' ? s.value.toLocaleString() : s.value}
                                </p>
                                <p style={{ margin: '4px 0 0', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                                    {s.label}
                                </p>
                            </Card>
                        ))}
                    </div>

                    <Card title="Top endpoints (7d)">
                        <DataTable
                            columns={topEndpointColumns}
                            rows={summary?.topEndpoints || []}
                            rowKey={(e, i) => `${e.endpoint}-${i}`}
                            loading={summaryLoading}
                            compact
                            empty={
                                <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                                    No traffic recorded yet.
                                </div>
                            }
                        />
                    </Card>

                    <Card
                        title="Per-key detail"
                        actions={
                            <Select
                                value={usageDetail?.keyId || ''}
                                onChange={e => viewUsage(e.target.value)}
                                placeholder="Select a key…"
                                options={keys.map(k => ({ value: k.id, label: k.name }))}
                                style={{ width: 220 }}
                                aria-label="Key to inspect"
                            />
                        }
                    >
                        {usageDetail?.data && (
                            <div
                                className="ui-row"
                                style={{ gap: 'var(--space-2xl)', padding: 'var(--space-lg) var(--space-card) 0' }}
                            >
                                <span style={{ fontSize: '0.82rem' }}>
                                    <strong>{Number(usageDetail.data.requests24h).toLocaleString()}</strong> reqs / 24h
                                </span>
                                <span style={{ fontSize: '0.82rem' }}>
                                    <strong>{Number(usageDetail.data.requests7d).toLocaleString()}</strong> reqs / 7d
                                </span>
                                <span style={{ fontSize: '0.82rem' }}>
                                    <strong>{Number(usageDetail.data.errors7d).toLocaleString()}</strong> errors / 7d
                                </span>
                                <span style={{ fontSize: '0.82rem' }}>
                                    <strong>{usageDetail.data.avgLatencyMs}</strong> ms avg
                                </span>
                            </div>
                        )}

                        {usageDetail || usageLoading ? (
                            <DataTable
                                columns={recentColumns}
                                rows={usageDetail?.data?.recent || []}
                                rowKey={(r, i) => i}
                                loading={usageLoading}
                                compact
                                empty={
                                    <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                                        No requests yet.
                                    </div>
                                }
                            />
                        ) : (
                            <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                                Select a key to see its recent traffic.
                            </div>
                        )}
                    </Card>
                </Stack>
            )}

            {/* ── Create / edit key ────────────────────────────────────────── */}
            <Modal
                as="form"
                onSubmit={save}
                open={dialog === 'create' || dialog === 'edit'}
                onClose={() => setDialog(null)}
                title={dialog === 'edit' ? 'Edit API key' : 'Generate new API key'}
                subtitle="Scopes decide which endpoints the key can reach."
                footer={
                    <>
                        <Button type="button" onClick={() => setDialog(null)}>Cancel</Button>
                        <Button type="submit" variant="primary" loading={saving}>
                            {dialog === 'edit' ? 'Save' : 'Generate'}
                        </Button>
                    </>
                }
            >
                <div className="ui-stack ui-stack--sm">
                    <FormField label="Key name" required hint="For example 'Mobile app' or 'Partner ETL'.">
                        <Input
                            value={form.name}
                            onChange={e => setForm({ ...form, name: e.target.value })}
                            placeholder="Partner ETL"
                        />
                    </FormField>

                    <FormGrid cols={2}>
                        <FormField label="Expiry (optional)" hint="Blank means the key never expires.">
                            <Input
                                type="date"
                                value={form.expiresAt}
                                onChange={e => setForm({ ...form, expiresAt: e.target.value })}
                            />
                        </FormField>
                        <FormField label="Rate limit (req/min)">
                            <Input
                                type="number"
                                min={1}
                                value={form.rateLimitPerMinute}
                                onChange={e => setForm({ ...form, rateLimitPerMinute: e.target.value })}
                            />
                        </FormField>
                    </FormGrid>

                    <FormField label="IP allowlist (optional)" hint="Comma-separated IPs. Blank allows any source IP.">
                        <Input
                            value={form.allowedIps}
                            onChange={e => setForm({ ...form, allowedIps: e.target.value })}
                            placeholder="10.0.0.1, 10.0.0.2"
                            mono
                        />
                    </FormField>

                    <div>
                        <p className="ui-field__label" style={{ marginBottom: 'var(--space-sm)' }}>
                            Permissions (scopes)
                        </p>
                        <div className="ui-stack ui-stack--sm">
                            {PERMISSIONS.map(p => (
                                <Checkbox
                                    key={p.key}
                                    checked={form.permissions.includes(p.key)}
                                    onChange={e => setForm(f => ({
                                        ...f,
                                        permissions: e.target.checked
                                            ? [...f.permissions, p.key]
                                            : f.permissions.filter(x => x !== p.key),
                                    }))}
                                    label={
                                        <>
                                            {p.label}{' '}
                                            <code style={{ fontFamily: MONO, fontSize: 11, opacity: 0.7 }}>{p.key}</code>
                                        </>
                                    }
                                    hint={p.desc}
                                />
                            ))}
                        </div>
                    </div>
                </div>
            </Modal>

            {/* ── One-time key reveal. No overlay/escape dismissal: the key is ──
                 shown exactly once and only a hash is stored server side.      */}
            <Modal
                open={!!createdKey}
                title="API key created"
                showClose={false}
                closeOnOverlay={false}
                footer={<Button variant="primary" onClick={() => setCreatedKey(null)}>Done</Button>}
            >
                <div className="ui-stack ui-stack--sm">
                    <Alert tone="warning" title="Copy this key now">
                        It will not be shown again. Only a hash is stored.
                    </Alert>
                    <div
                        style={{
                            padding: 'var(--space-lg)',
                            background: 'var(--bg-subtle)',
                            border: '1px solid var(--border)',
                            borderRadius: 'var(--radius-md)',
                            fontFamily: MONO,
                            fontSize: 13,
                            wordBreak: 'break-all',
                            lineHeight: 1.6,
                        }}
                    >
                        {createdKey?.apiKey}
                    </div>
                    <Button block icon={Copy} onClick={() => copyToClipboard(createdKey?.apiKey)}>
                        Copy to clipboard
                    </Button>
                </div>
            </Modal>
        </Page>
    );
};

export default ApiManagement;
