import { useState, useEffect, useCallback } from 'react';
import {
    Webhook, Plus, Trash2, Copy, Edit2, RefreshCw, Send, Activity, KeyRound,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Card, Button, Badge, StatusBadge, Alert, Tabs, DataTable, Modal,
    FormField, Input, Checkbox, useConfirm,
} from '../../components/ui';

/**
 * Admin > Webhooks — outbound event notifications, per tenant.
 *
 * An endpoint subscribes to event types; the backend POSTs a signed JSON
 * payload (HMAC-SHA256 over the raw body, X-Acquira-Signature header) with
 * automatic retries. The signing secret is generated server-side and shown
 * exactly once — same contract as API keys.
 */

const MONO = 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';

const emptyForm = { name: '', url: '', events: [] };

const VERIFY_SNIPPET = `# Verify a delivery (Node.js)
const crypto = require('crypto');
const expected = 'sha256=' + crypto
  .createHmac('sha256', WEBHOOK_SECRET)   // the secret shown at creation
  .update(rawRequestBody)                 // exact bytes received
  .digest('hex');
const ok = crypto.timingSafeEqual(
  Buffer.from(expected), Buffer.from(req.headers['x-acquira-signature']));`;

const Webhooks = () => {
    const { tenantVersion } = useAuth();
    const confirm = useConfirm();
    const [endpoints, setEndpoints] = useState([]);
    const [catalog, setCatalog] = useState([]);
    const [loading, setLoading] = useState(true);
    const [dialog, setDialog] = useState(null); // 'create' | 'edit' | null
    const [form, setForm] = useState(emptyForm);
    const [editId, setEditId] = useState(null);
    const [secretReveal, setSecretReveal] = useState(null); // { name, secret }
    const [saving, setSaving] = useState(false);
    const [tab, setTab] = useState('endpoints'); // 'endpoints' | 'deliveries'

    const [deliveries, setDeliveries] = useState([]);
    const [deliveriesLoading, setDeliveriesLoading] = useState(false);
    const [deliveryFilter, setDeliveryFilter] = useState(null); // endpoint_id | null

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const [eps, cat] = await Promise.all([
                api.get('/admin/webhooks'),
                api.get('/admin/webhooks/events'),
            ]);
            setEndpoints(eps.data || []);
            setCatalog(cat.data || []);
        } catch (e) {
            showToast('Could not load webhooks: ' + (e.response?.data?.error || e.message), 'error');
            setEndpoints([]);
        } finally { setLoading(false); }
    }, []);
    useEffect(() => { load(); }, [load, tenantVersion]);

    const loadDeliveries = useCallback(async (endpointId = null) => {
        setDeliveriesLoading(true);
        try {
            const res = await api.get('/admin/webhooks/deliveries', {
                params: endpointId ? { endpointId } : {},
            });
            setDeliveries(res.data || []);
        } catch { setDeliveries([]); }
        finally { setDeliveriesLoading(false); }
    }, []);
    useEffect(() => { if (tab === 'deliveries') loadDeliveries(deliveryFilter); }, [tab, deliveryFilter, loadDeliveries, tenantVersion]);

    const parseEvents = (raw) => {
        try { const v = JSON.parse(raw || '[]'); return Array.isArray(v) ? v : []; }
        catch { return []; }
    };

    const openCreate = () => { setForm(emptyForm); setEditId(null); setDialog('create'); };
    const openEdit = (ep) => {
        setForm({ name: ep.name || '', url: ep.url || '', events: parseEvents(ep.events) });
        setEditId(ep.endpoint_id);
        setDialog('edit');
    };

    const save = async (e) => {
        e?.preventDefault();
        if (!form.name.trim()) { showToast('Name is required', 'error'); return; }
        if (!form.url.trim()) { showToast('URL is required', 'error'); return; }
        if (form.events.length === 0) { showToast('Subscribe to at least one event', 'error'); return; }
        const payload = { name: form.name.trim(), url: form.url.trim(), events: form.events };
        setSaving(true);
        try {
            if (dialog === 'edit') {
                await api.put(`/admin/webhooks/${editId}`, payload);
                showToast('Webhook updated', 'success'); setDialog(null); load();
            } else {
                const res = await api.post('/admin/webhooks', payload);
                setSecretReveal({ name: payload.name, secret: res.data.secret });
                setDialog(null); load();
            }
        } catch (e2) {
            showToast((dialog === 'edit' ? 'Update' : 'Create') + ' failed: ' + (e2.response?.data?.error || e2.message), 'error');
        } finally { setSaving(false); }
    };

    const toggleActive = async (ep) => {
        try {
            await api.put(`/admin/webhooks/${ep.endpoint_id}`, { isActive: !ep.is_active });
            showToast(ep.is_active ? 'Webhook paused' : 'Webhook re-enabled', 'success');
            load();
        } catch (e) { showToast('Update failed: ' + (e.response?.data?.error || e.message), 'error'); }
    };

    const rotateSecret = async (ep) => {
        const ok = await confirm({
            title: 'Rotate the signing secret?',
            message: `Deliveries to "${ep.name}" will be signed with a new secret immediately — the receiver must be updated or its signature checks start failing.`,
            confirmLabel: 'Rotate secret',
            tone: 'danger',
        });
        if (!ok) return;
        try {
            const res = await api.post(`/admin/webhooks/${ep.endpoint_id}/rotate-secret`);
            setSecretReveal({ name: ep.name, secret: res.data.secret });
        } catch (e) { showToast('Rotate failed: ' + (e.response?.data?.error || e.message), 'error'); }
    };

    const remove = async (ep) => {
        const ok = await confirm({
            title: 'Delete this webhook?',
            message: `"${ep.name}" and its delivery history will be removed. This cannot be undone.`,
            confirmLabel: 'Delete webhook',
            tone: 'danger',
        });
        if (!ok) return;
        try { await api.delete(`/admin/webhooks/${ep.endpoint_id}`); showToast('Webhook deleted', 'success'); load(); }
        catch (e) { showToast('Delete failed: ' + (e.response?.data?.error || e.message), 'error'); }
    };

    const fireTest = async (ep) => {
        try {
            const res = await api.post(`/admin/webhooks/${ep.endpoint_id}/test`);
            showToast(res.data?.message || 'Test queued', 'success');
        } catch (e) { showToast('Test failed: ' + (e.response?.data?.error || e.message), 'error'); }
    };

    const redeliver = async (d) => {
        try {
            await api.post(`/admin/webhooks/deliveries/${d.delivery_id}/redeliver`);
            showToast('Delivery re-queued', 'success');
            loadDeliveries(deliveryFilter);
        } catch (e) { showToast('Redeliver failed: ' + (e.response?.data?.error || e.message), 'error'); }
    };

    const copyToClipboard = (text) => { if (!text) return; navigator.clipboard.writeText(text); showToast('Copied to clipboard', 'success'); };

    // ── Columns ───────────────────────────────────────────────────────────────
    const endpointColumns = [
        { key: 'name', header: 'Name', sortable: true, render: ep => <strong>{ep.name}</strong> },
        {
            key: 'url', header: 'URL', mono: true,
            render: ep => <span style={{ fontFamily: MONO, fontSize: 12, wordBreak: 'break-all' }}>{ep.url}</span>,
        },
        {
            key: 'events', header: 'Events',
            render: ep => (
                <span className="ui-row" style={{ gap: 4, maxWidth: 260, flexWrap: 'wrap' }}>
                    {parseEvents(ep.events).map(ev => <Badge key={ev} mono>{ev}</Badge>)}
                </span>
            ),
        },
        {
            key: 'health', header: 'Health', nowrap: true,
            render: ep => {
                if (ep.consecutive_failures > 0) {
                    return <Badge tone="danger">{ep.consecutive_failures} failing</Badge>;
                }
                return ep.last_success_at
                    ? <Badge tone="success">OK · {new Date(ep.last_success_at).toLocaleDateString()}</Badge>
                    : <span className="ui-td--muted">No deliveries yet</span>;
            },
        },
        {
            key: 'is_active', header: 'Status', sortable: true,
            render: ep => <StatusBadge status={ep.is_active ? 'Active' : 'Paused'} />,
        },
        {
            key: '_actions', header: '', align: 'right', nowrap: true,
            render: ep => (
                <>
                    <Button variant="ghost" size="sm" iconOnly icon={Send}
                        onClick={() => fireTest(ep)} aria-label={`Send test to ${ep.name}`} />
                    <Button variant="ghost" size="sm" iconOnly icon={Activity}
                        onClick={() => { setDeliveryFilter(ep.endpoint_id); setTab('deliveries'); }}
                        aria-label={`Deliveries for ${ep.name}`} />
                    <Button variant="ghost" size="sm" iconOnly icon={Edit2}
                        onClick={() => openEdit(ep)} aria-label={`Edit ${ep.name}`} />
                    <Button variant="ghost" size="sm" iconOnly icon={KeyRound}
                        onClick={() => rotateSecret(ep)} aria-label={`Rotate secret for ${ep.name}`} />
                    <Button variant={ep.is_active ? 'ghost' : 'subtle'} size="sm"
                        onClick={() => toggleActive(ep)}>
                        {ep.is_active ? 'Pause' : 'Enable'}
                    </Button>
                    <Button variant="danger-ghost" size="sm" iconOnly icon={Trash2}
                        onClick={() => remove(ep)} aria-label={`Delete ${ep.name}`} />
                </>
            ),
        },
    ];

    const deliveryColumns = [
        { key: 'endpoint_name', header: 'Endpoint', render: d => <strong>{d.endpoint_name}</strong> },
        { key: 'event_type', header: 'Event', render: d => <Badge mono>{d.event_type}</Badge> },
        {
            key: 'status', header: 'Status',
            render: d => {
                const tone = d.status === 'SUCCESS' ? 'success' : d.status === 'PENDING' ? 'info' : 'danger';
                return <Badge tone={tone}>{d.status}</Badge>;
            },
        },
        {
            key: 'response_status', header: 'Response', nowrap: true, muted: true,
            render: d => d.response_status ?? '—',
        },
        { key: 'attempts', header: 'Attempts', align: 'right', numeric: true },
        {
            key: 'created_at', header: 'Queued', nowrap: true, muted: true,
            render: d => (d.created_at ? new Date(d.created_at).toLocaleString() : '—'),
        },
        {
            key: '_actions', header: '', align: 'right', nowrap: true,
            render: d => (
                (d.status === 'DEAD' || d.status === 'FAILED') && (
                    <Button variant="ghost" size="sm" icon={RefreshCw} onClick={() => redeliver(d)}>
                        Redeliver
                    </Button>
                )
            ),
        },
    ];

    const tabs = [
        { key: 'endpoints', label: 'Endpoints', icon: Webhook, count: endpoints.length },
        { key: 'deliveries', label: 'Deliveries', icon: Activity },
    ];

    return (
        <Page
            title="Webhooks"
            subtitle="Push signed event notifications (ingest runs, integration failures, expiring API keys) to your systems."
            icon={Webhook}
            actions={
                <Badge tone="success" dot>
                    {endpoints.filter(ep => ep.is_active).length} active
                </Badge>
            }
        >
            <Tabs tabs={tabs} active={tab} onChange={setTab} />

            {tab === 'endpoints' && (
                <Stack gap="md">
                    <Card>
                        <DataTable
                            columns={endpointColumns}
                            rows={endpoints}
                            rowClassName={ep => !ep.is_active && 'ui-tr--dimmed'}
                            rowKey={ep => ep.endpoint_id}
                            loading={loading}
                            defaultSort={{ key: 'name', dir: 'asc' }}
                            toolbarRight={
                                <Button variant="primary" icon={Plus} onClick={openCreate}>
                                    Add endpoint
                                </Button>
                            }
                            empty={
                                <div style={{ padding: 'var(--space-3xl)', textAlign: 'center' }}>
                                    <Webhook size={30} style={{ color: 'var(--text-muted)' }} />
                                    <p style={{ margin: '10px 0 14px', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                                        No webhook endpoints yet. Add one to get pushed when data lands or a feed fails.
                                    </p>
                                    <Button variant="subtle" icon={Plus} onClick={openCreate}>
                                        Add the first endpoint
                                    </Button>
                                </div>
                            }
                        />
                    </Card>

                    <Card title="Verifying deliveries" pad>
                        <p style={{ margin: '0 0 var(--space-md)', fontSize: '0.82rem', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
                            Every delivery is an HTTPS POST with JSON body and an{' '}
                            <code>X-Acquira-Signature: sha256=…</code> header — the HMAC-SHA256 of the raw
                            body using your endpoint's secret. Reject anything whose signature doesn't match.
                            Retries use the same <code>X-Acquira-Event-Id</code>, so de-duplicate on it.
                            Failed deliveries retry on a backoff up to 6 attempts; an endpoint failing 25
                            times in a row is paused automatically.
                        </p>
                        <pre style={{
                            margin: 0, padding: 'var(--space-lg)', background: 'var(--bg-subtle)',
                            borderRadius: 'var(--radius-md)', fontSize: 12, lineHeight: 1.6,
                            whiteSpace: 'pre-wrap', fontFamily: MONO, color: 'var(--text-secondary)', overflowX: 'auto',
                        }}>
                            {VERIFY_SNIPPET}
                        </pre>
                    </Card>
                </Stack>
            )}

            {tab === 'deliveries' && (
                <Card
                    title="Delivery log"
                    subtitle={deliveryFilter
                        ? `Filtered to one endpoint — ${endpoints.find(e => e.endpoint_id === deliveryFilter)?.name || deliveryFilter}`
                        : 'Most recent deliveries across all endpoints.'}
                    actions={
                        <span className="ui-row" style={{ gap: 8 }}>
                            {deliveryFilter && (
                                <Button size="sm" onClick={() => setDeliveryFilter(null)}>Show all</Button>
                            )}
                            <Button size="sm" icon={RefreshCw} onClick={() => loadDeliveries(deliveryFilter)}>
                                Refresh
                            </Button>
                        </span>
                    }
                >
                    <DataTable
                        columns={deliveryColumns}
                        rows={deliveries}
                        rowKey={d => d.delivery_id}
                        loading={deliveriesLoading}
                        compact
                        empty={
                            <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                                No deliveries yet — use the send-test action on an endpoint.
                            </div>
                        }
                    />
                </Card>
            )}

            {/* ── Create / edit ────────────────────────────────────────────── */}
            <Modal
                as="form"
                onSubmit={save}
                open={dialog === 'create' || dialog === 'edit'}
                onClose={() => setDialog(null)}
                title={dialog === 'edit' ? 'Edit webhook' : 'Add webhook endpoint'}
                subtitle="The receiver gets a signed POST for every subscribed event."
                footer={
                    <>
                        <Button type="button" onClick={() => setDialog(null)}>Cancel</Button>
                        <Button type="submit" variant="primary" loading={saving}>
                            {dialog === 'edit' ? 'Save' : 'Create'}
                        </Button>
                    </>
                }
            >
                <div className="ui-stack ui-stack--sm">
                    <FormField label="Name" required hint="For example 'Core banking notifier' or 'Ops Slack bridge'.">
                        <Input
                            value={form.name}
                            onChange={e => setForm({ ...form, name: e.target.value })}
                            placeholder="Core banking notifier"
                        />
                    </FormField>

                    <FormField label="URL" required hint="HTTPS only. Private/internal addresses are rejected outside dev.">
                        <Input
                            value={form.url}
                            onChange={e => setForm({ ...form, url: e.target.value })}
                            placeholder="https://integrations.yourbank.com/acquira/events"
                            mono
                        />
                    </FormField>

                    <div>
                        <p className="ui-field__label" style={{ marginBottom: 'var(--space-sm)' }}>
                            Subscribed events
                        </p>
                        <div className="ui-stack ui-stack--sm">
                            {catalog.map(ev => (
                                <Checkbox
                                    key={ev.type}
                                    checked={form.events.includes(ev.type)}
                                    onChange={e => setForm(f => ({
                                        ...f,
                                        events: e.target.checked
                                            ? [...f.events, ev.type]
                                            : f.events.filter(x => x !== ev.type),
                                    }))}
                                    label={<code style={{ fontFamily: MONO, fontSize: 12 }}>{ev.type}</code>}
                                    hint={ev.description}
                                />
                            ))}
                        </div>
                    </div>
                </div>
            </Modal>

            {/* ── One-time secret reveal ───────────────────────────────────── */}
            <Modal
                open={!!secretReveal}
                title="Signing secret"
                showClose={false}
                closeOnOverlay={false}
                footer={<Button variant="primary" onClick={() => setSecretReveal(null)}>Done</Button>}
            >
                <div className="ui-stack ui-stack--sm">
                    <Alert tone="warning" title="Copy this secret now">
                        It will not be shown again — it's stored encrypted and used to sign every
                        delivery to “{secretReveal?.name}”.
                    </Alert>
                    <div style={{
                        padding: 'var(--space-lg)', background: 'var(--bg-subtle)',
                        border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
                        fontFamily: MONO, fontSize: 13, wordBreak: 'break-all', lineHeight: 1.6,
                    }}>
                        {secretReveal?.secret}
                    </div>
                    <Button block icon={Copy} onClick={() => copyToClipboard(secretReveal?.secret)}>
                        Copy to clipboard
                    </Button>
                </div>
            </Modal>
        </Page>
    );
};

export default Webhooks;
