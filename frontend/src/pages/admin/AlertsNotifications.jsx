import { useState, useEffect, useCallback } from 'react';
import {
    Bell, Plus, Trash2, Edit3, Play, AlertTriangle,
    TrendingDown, TrendingUp, Zap, Clock, Activity, XCircle,
    ListChecks, History,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Card, Button, Badge, StatusBadge, Tabs, DataTable, Modal,
    FormField, FormGrid, Input, Textarea, Select, Switch, useConfirm,
} from '../../components/ui';

const METRIC_OPTIONS = [
    { value: 'daily_volume_drop', label: 'Daily Volume Drop %', icon: TrendingDown },
    { value: 'zero_txn_days', label: 'Zero Transaction Days', icon: XCircle },
    { value: 'refund_ratio', label: 'Refund Ratio %', icon: AlertTriangle },
    { value: 'msf_below_target', label: 'MSF Below Target', icon: TrendingDown },
    { value: 'volume_spike', label: 'Volume Spike %', icon: TrendingUp },
    { value: 'new_merchant_inactive', label: 'New Merchant Inactive', icon: Clock },
    { value: 'chargeback_ratio', label: 'Chargeback Ratio %', icon: Zap },
    { value: 'terminal_inactive', label: 'Terminal Inactive Days', icon: Activity },
];

const BLANK_RULE = {
    name: '', metric: 'daily_volume_drop', operator: '>', threshold: 50,
    severity: 'WARNING', recipients: '', isActive: true, description: '',
    checkFrequency: 'DAILY', scope: 'ALL_MERCHANTS'
};

const demoRules = [
    { id: 1, name: 'Volume Drop Alert', metric: 'daily_volume_drop', operator: '>', threshold: 50, severity: 'CRITICAL', recipients: 'rm@bank.com', isActive: true, description: 'Alert when merchant daily volume drops more than 50% vs 30-day average', checkFrequency: 'DAILY', scope: 'ALL_MERCHANTS' },
    { id: 2, name: 'Zero Transaction Warning', metric: 'zero_txn_days', operator: '>=', threshold: 3, severity: 'WARNING', recipients: 'operations@bank.com', isActive: true, description: 'Flag merchants with no transactions for 3+ consecutive days', checkFrequency: 'DAILY', scope: 'ALL_MERCHANTS' },
    { id: 3, name: 'High Refund Ratio', metric: 'refund_ratio', operator: '>', threshold: 10, severity: 'WARNING', recipients: 'risk@bank.com', isActive: false, description: 'Alert when refund ratio exceeds 10%', checkFrequency: 'DAILY', scope: 'ALL_MERCHANTS' },
];
const demoHistory = [
    { id: 1, triggeredAt: new Date().toISOString(), ruleName: 'Volume Drop Alert', severity: 'CRITICAL', merchantName: 'Coffee Shop LLC', message: 'Volume dropped 65% vs 30-day avg', acknowledged: false },
    { id: 2, triggeredAt: new Date(Date.now() - 86400000).toISOString(), ruleName: 'Zero Transaction Warning', severity: 'WARNING', merchantName: 'Tech Store Inc', message: '4 consecutive zero-txn days', acknowledged: true },
];

const OPERATORS = ['>', '<', '>=', '<=', '='];
const SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'];
const FREQUENCIES = ['HOURLY', 'DAILY', 'WEEKLY'];

const severityTone = (s) => (s === 'CRITICAL' ? 'danger' : s === 'WARNING' ? 'warning' : 'info');
const getMetricInfo = (metric) => METRIC_OPTIONS.find(m => m.value === metric) || METRIC_OPTIONS[0];

const AlertsNotifications = () => {
    const { tenantVersion } = useAuth();
    const confirm = useConfirm();
    const [rules, setRules] = useState([]);
    const [history, setHistory] = useState([]);
    const [loading, setLoading] = useState(true);
    const [historyLoading, setHistoryLoading] = useState(true);
    const [dialog, setDialog] = useState(null);
    const [editMode, setEditMode] = useState(false);
    const [saving, setSaving] = useState(false);
    const [tab, setTab] = useState('rules');

    const loadRules = useCallback(async () => {
        try { const res = await api.get('/admin/alerts/rules'); setRules(res.data || []); }
        catch { setRules(demoRules); }
        setLoading(false);
    }, []);
    const loadHistory = useCallback(async () => {
        try { const res = await api.get('/admin/alerts/history'); setHistory(res.data || []); }
        catch { setHistory(demoHistory); }
        setHistoryLoading(false);
    }, []);

    useEffect(() => { loadRules(); loadHistory(); }, [loadRules, loadHistory, tenantVersion]);

    const openCreate = () => { setDialog({ ...BLANK_RULE }); setEditMode(false); };
    const openEdit = (rule) => { setDialog({ ...rule }); setEditMode(true); };

    const saveRule = async (e) => {
        e?.preventDefault();
        if (!dialog.name) { showToast('Rule name is required', 'error'); return; }
        setSaving(true);
        try {
            if (editMode && dialog.id) await api.put(`/admin/alerts/rules/${dialog.id}`, dialog);
            else await api.post('/admin/alerts/rules', dialog);
            showToast('Alert rule saved', 'success');
            setDialog(null); loadRules();
        } catch {
            // Demo fallback
            if (editMode) setRules(prev => prev.map(r => r.id === dialog.id ? dialog : r));
            else setRules(prev => [...prev, { ...dialog, id: Date.now() }]);
            showToast('Alert rule saved', 'success');
            setDialog(null);
        } finally { setSaving(false); }
    };

    const toggleRule = async (rule) => {
        try { await api.put(`/admin/alerts/rules/${rule.id}`, { ...rule, isActive: !rule.isActive }); loadRules(); }
        catch { setRules(prev => prev.map(r => r.id === rule.id ? { ...r, isActive: !r.isActive } : r)); }
    };

    const deleteRule = async (rule) => {
        const ok = await confirm({
            title: 'Delete alert rule?',
            message: `"${rule.name}" will stop running. Past alert history is kept.`,
            confirmLabel: 'Delete rule',
            tone: 'danger',
        });
        if (!ok) return;
        try { await api.delete(`/admin/alerts/rules/${rule.id}`); loadRules(); }
        catch { setRules(prev => prev.filter(r => r.id !== rule.id)); }
        showToast('Rule deleted', 'success');
    };

    const activeRules = rules.filter(r => r.isActive).length;
    const criticalAlerts = history.filter(h => h.severity === 'CRITICAL' && !h.acknowledged).length;
    const todayAlerts = history.filter(h => new Date(h.triggeredAt).toDateString() === new Date().toDateString()).length;

    const stats = [
        { label: 'Active rules', value: activeRules, icon: Play, color: 'var(--success)' },
        { label: 'Alerts today', value: todayAlerts, icon: Bell, color: 'var(--info)' },
        { label: 'Unacknowledged', value: criticalAlerts, icon: AlertTriangle, color: 'var(--danger)' },
        { label: 'Total rules', value: rules.length, icon: Zap, color: 'var(--brand)' },
    ];

    const ruleColumns = [
        {
            key: 'name',
            header: 'Rule',
            sortable: true,
            render: r => (
                <div style={{ minWidth: 0 }}>
                    <span style={{ fontWeight: 600 }}>{r.name}</span>
                    {r.description && (
                        <span style={{ display: 'block', fontSize: '0.75rem', color: 'var(--text-secondary)', maxWidth: 360 }}>
                            {r.description}
                        </span>
                    )}
                </div>
            ),
        },
        {
            key: 'metric',
            header: 'Condition',
            sortable: true,
            render: r => {
                const info = getMetricInfo(r.metric);
                const Icon = info.icon;
                return (
                    <span className="ui-row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                        <Icon size={14} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                        <span>{info.label} {r.operator} {r.threshold}</span>
                    </span>
                );
            },
        },
        {
            key: 'severity',
            header: 'Severity',
            sortable: true,
            render: r => <Badge tone={severityTone(r.severity)}>{r.severity}</Badge>,
        },
        {
            key: 'checkFrequency',
            header: 'Frequency',
            sortable: true,
            render: r => <Badge>{r.checkFrequency || 'DAILY'}</Badge>,
        },
        {
            key: 'recipients',
            header: 'Recipients',
            muted: true,
            render: r => (r.recipients ? `${r.recipients.split(',').length} recipients` : '—'),
        },
        {
            key: 'isActive',
            header: 'Active',
            sortable: true,
            render: r => (
                <Switch
                    checked={!!r.isActive}
                    onChange={() => toggleRule(r)}
                    label={r.isActive ? 'Active' : 'Paused'}
                    aria-label={`Toggle ${r.name}`}
                />
            ),
        },
        {
            key: '_actions',
            header: '',
            align: 'right',
            nowrap: true,
            render: r => (
                <>
                    <Button variant="ghost" size="sm" iconOnly icon={Edit3}
                        onClick={() => openEdit(r)} aria-label={`Edit ${r.name}`} />
                    <Button variant="danger-ghost" size="sm" iconOnly icon={Trash2}
                        onClick={() => deleteRule(r)} aria-label={`Delete ${r.name}`} />
                </>
            ),
        },
    ];

    const historyColumns = [
        {
            key: 'triggeredAt',
            header: 'Time',
            sortable: true,
            nowrap: true,
            render: h => (h.triggeredAt ? new Date(h.triggeredAt).toLocaleString() : '—'),
        },
        { key: 'ruleName', header: 'Rule', sortable: true, render: h => <strong>{h.ruleName}</strong> },
        {
            key: 'severity',
            header: 'Severity',
            sortable: true,
            render: h => <Badge tone={severityTone(h.severity)}>{h.severity}</Badge>,
        },
        { key: 'merchantName', header: 'Merchant', sortable: true },
        {
            key: 'message',
            header: 'Details',
            muted: true,
            render: h => (
                <span style={{ display: 'block', maxWidth: 340, wordBreak: 'break-word' }}>{h.message}</span>
            ),
        },
        {
            key: 'acknowledged',
            header: 'Ack',
            sortable: true,
            render: h => <StatusBadge status={h.acknowledged ? 'Yes' : 'No'} />,
        },
    ];

    const tabs = [
        { key: 'rules', label: 'Alert rules', icon: ListChecks, count: rules.length },
        { key: 'history', label: 'Alert history', icon: History, count: history.length },
    ];

    return (
        <Page
            title="Alerts and notifications"
            subtitle="Threshold rules that watch merchant activity and notify recipients when they trip."
            icon={Bell}
            actions={
                <Button variant="primary" icon={Plus} onClick={openCreate}>New rule</Button>
            }
        >
            <Stack gap="md">
                <div style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                    gap: 'var(--space-md)',
                }}>
                    {stats.map(stat => {
                        const Icon = stat.icon;
                        return (
                            <Card key={stat.label} pad>
                                <div className="ui-row" style={{ gap: 12, flexWrap: 'nowrap' }}>
                                    <Icon size={20} style={{ color: stat.color, flexShrink: 0 }} />
                                    <div style={{ minWidth: 0 }}>
                                        <div style={{ fontSize: '1.15rem', fontWeight: 700, lineHeight: 1.2 }}>
                                            {stat.value}
                                        </div>
                                        <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                                            {stat.label}
                                        </div>
                                    </div>
                                </div>
                            </Card>
                        );
                    })}
                </div>

                <Tabs tabs={tabs} active={tab} onChange={setTab} />

                {tab === 'rules' && (
                    <Card>
                        <DataTable
                            columns={ruleColumns}
                            rows={rules}
                            rowKey={r => r.id ?? r.name}
                            loading={loading}
                            defaultSort={{ key: 'name', dir: 'asc' }}
                            empty={
                                <div style={{ padding: 'var(--space-3xl)', textAlign: 'center' }}>
                                    <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 14 }}>
                                        No alert rules configured yet.
                                    </p>
                                    <Button variant="subtle" icon={Plus} onClick={openCreate}>
                                        Create the first rule
                                    </Button>
                                </div>
                            }
                        />
                    </Card>
                )}

                {tab === 'history' && (
                    <Card>
                        <DataTable
                            columns={historyColumns}
                            rows={history}
                            rowKey={(h, i) => h.id ?? i}
                            loading={historyLoading}
                            defaultSort={{ key: 'triggeredAt', dir: 'desc' }}
                            pageSize={10}
                            empty={
                                <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                                    No alerts have been triggered yet.
                                </div>
                            }
                        />
                    </Card>
                )}
            </Stack>

            {/* ── Rule editor ───────────────────────────────────────────────── */}
            <Modal
                as="form"
                onSubmit={saveRule}
                open={!!dialog}
                onClose={() => setDialog(null)}
                title={editMode ? 'Edit alert rule' : 'New alert rule'}
                subtitle="The rule is evaluated on the selected frequency and notifies its recipients when the condition is met."
                footer={
                    <>
                        <Button type="button" onClick={() => setDialog(null)}>Cancel</Button>
                        <Button type="submit" variant="primary" loading={saving}>
                            {editMode ? 'Update rule' : 'Create rule'}
                        </Button>
                    </>
                }
            >
                <div className="ui-stack ui-stack--sm">
                    <FormField label="Rule name" required>
                        <Input
                            value={dialog?.name || ''}
                            onChange={e => setDialog(d => ({ ...d, name: e.target.value }))}
                            placeholder="e.g. Volume drop alert"
                        />
                    </FormField>

                    <FormField label="Description">
                        <Textarea
                            rows={2}
                            value={dialog?.description || ''}
                            onChange={e => setDialog(d => ({ ...d, description: e.target.value }))}
                            placeholder="What this rule watches for"
                        />
                    </FormField>

                    <FormGrid cols={4}>
                        <FormField label="Metric" className="ui-form-grid--span">
                            <Select
                                value={dialog?.metric || 'daily_volume_drop'}
                                onChange={e => setDialog(d => ({ ...d, metric: e.target.value }))}
                                options={METRIC_OPTIONS.map(m => ({ value: m.value, label: m.label }))}
                            />
                        </FormField>
                        <FormField label="Operator">
                            <Select
                                value={dialog?.operator || '>'}
                                onChange={e => setDialog(d => ({ ...d, operator: e.target.value }))}
                                options={OPERATORS}
                            />
                        </FormField>
                        <FormField label="Threshold">
                            <Input
                                type="number"
                                value={dialog?.threshold ?? 0}
                                onChange={e => setDialog(d => ({ ...d, threshold: Number(e.target.value) }))}
                            />
                        </FormField>
                    </FormGrid>

                    <FormGrid cols={2}>
                        <FormField label="Severity">
                            <Select
                                value={dialog?.severity || 'WARNING'}
                                onChange={e => setDialog(d => ({ ...d, severity: e.target.value }))}
                                options={SEVERITIES}
                            />
                        </FormField>
                        <FormField label="Check frequency">
                            <Select
                                value={dialog?.checkFrequency || 'DAILY'}
                                onChange={e => setDialog(d => ({ ...d, checkFrequency: e.target.value }))}
                                options={FREQUENCIES}
                            />
                        </FormField>
                    </FormGrid>

                    <FormField
                        label="Recipients"
                        hint="Comma-separated email addresses notified when the alert triggers."
                    >
                        <Input
                            value={dialog?.recipients || ''}
                            onChange={e => setDialog(d => ({ ...d, recipients: e.target.value }))}
                            placeholder="risk@bank.com, operations@bank.com"
                        />
                    </FormField>

                    <Switch
                        checked={dialog?.isActive ?? true}
                        onChange={e => setDialog(d => ({ ...d, isActive: e.target.checked }))}
                        label="Enable this rule"
                    />
                </div>
            </Modal>
        </Page>
    );
};

export default AlertsNotifications;
