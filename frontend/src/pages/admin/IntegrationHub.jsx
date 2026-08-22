import { useState, useEffect, useCallback } from 'react';
import {
  Cable, Database, FileCode, Clock, ScrollText, Plus, Edit2, Trash2,
  CheckCircle, XCircle, RefreshCw, Play, Pause, Zap,
  Activity, Server, TestTube, RotateCcw, Eye,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Stack, Row, Card, Button, Badge, StatusBadge, Alert, Tabs, DataTable, Modal,
  FormField, FormGrid, Input, Textarea, Select, Checkbox, useConfirm,
} from '../../components/ui';

/**
 * Admin > Data integration hub.
 *
 * Five concerns, one tab each:
 *  1. Overview      — rollup counters, 24h success rate, most recent runs.
 *  2. Connections   — external Oracle / PostgreSQL / SQL Server credentials.
 *  3. Report configs— the SQL statements pulled through those connections.
 *  4. Schedules     — cron bindings plus an ad-hoc "run now".
 *  5. Run history   — paged execution log with retry.
 *
 * Credential handling: the server never returns a connection password. Editing
 * an existing connection blanks the field, and a blank field is submitted as
 * PASSWORD_SENTINEL so the backend keeps the stored ciphertext. The field is
 * always type="password" with no reveal affordance, and credentials are never
 * written to the console.
 */

// The server replies with this sentinel in place of a stored password, and
// accepts it back to mean "leave the existing ciphertext alone".
const PASSWORD_SENTINEL = '__UNCHANGED__';

const REPORT_TYPE_TONES = { MERCHANT: 'brand', TRANSACTION: 'success' };
const DB_TYPE_TONES = { ORACLE: 'warning', POSTGRES: 'info', MSSQL: 'brand' };
// StatusBadge maps RETRYING to neutral, which loses the "still working" signal,
// so run status keeps an explicit tone map.
const RUN_STATUS_TONES = { SUCCESS: 'success', FAILED: 'danger', RUNNING: 'info', RETRYING: 'warning' };

const toneBg = (tone) => (tone === 'brand' ? 'var(--brand-50)' : `var(--${tone}-bg)`);
const toneFg = (tone) => `var(--${tone})`;

// Spring's CronExpression (used by DynamicSchedulerService) is 6-field and does NOT
// accept Quartz's '?' token — day-of-week/day-of-month use '*' or names. Quartz-style
// crons here would be saved but silently never fire.
const FREQ_OPTIONS = [
  { label: 'Every hour', value: 'HOURLY', cron: '0 0 * * * *' },
  { label: 'Daily at 2 AM', value: 'DAILY', cron: '0 0 2 * * *' },
  { label: 'Daily at 6 AM', value: 'DAILY_6AM', cron: '0 0 6 * * *' },
  { label: 'Daily at 10 PM', value: 'DAILY_10PM', cron: '0 0 22 * * *' },
  { label: 'Weekly (Sun 3 AM)', value: 'WEEKLY', cron: '0 0 3 * * SUN' },
  { label: 'Monthly (1st, 2 AM)', value: 'MONTHLY', cron: '0 0 2 1 * *' },
  { label: 'Custom', value: 'CUSTOM', cron: '' },
];

// Africa/Cairo is required for the Egypt tenant: Egypt observes DST and the
// Gulf zones do not, so a Gulf substitute schedules jobs an hour off for half
// the year.
const TIMEZONES = ['UTC', 'Asia/Bahrain', 'Asia/Dubai', 'Asia/Riyadh', 'Africa/Cairo', 'Asia/Kolkata', 'Europe/London', 'America/New_York'];

const emptyStateStyle = {
  padding: 'var(--space-3xl)',
  textAlign: 'center',
  color: 'var(--text-secondary)',
  fontSize: '0.85rem',
};

const fmtDuration = (ms) => (ms ? `${(ms / 1000).toFixed(1)}s` : '—');
const fmtDateTime = (v) => (v ? new Date(v).toLocaleString() : '—');

/** Renders an ad-hoc result set (dynamic columns) returned by a SQL validation. */
const PreviewTable = ({ result, maxHeight = 240 }) => {
  const columns = (result.columns || []).map((c) => ({
    key: c,
    header: c,
    render: (row) => (row[c] != null ? String(row[c]) : '—'),
  }));
  return (
    <div style={{ maxHeight, overflow: 'auto' }}>
      <DataTable
        columns={columns}
        rows={result.preview || []}
        rowKey={(_row, i) => i}
        compact
        stickyHeader
        empty={<div style={emptyStateStyle}>Query returned no rows.</div>}
      />
    </div>
  );
};

// ─── Overview tab ────────────────────────────────────────────
const OverviewTab = () => {
  const { tenantVersion } = useAuth();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try { const res = await api.get('/admin/integration/overview'); setData(res.data); }
    catch { showToast('Could not load the integration overview', 'error'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, [load, tenantVersion]);

  if (!loading && !data) {
    return (
      <Alert tone="info" title="No data available">
        Configure connections and reports to get started.
      </Alert>
    );
  }

  const stats = [
    { icon: Database, label: 'Connections', value: data?.totalConnections, tone: 'info' },
    { icon: FileCode, label: 'Reports', value: data?.totalReports, tone: 'brand' },
    { icon: Clock, label: 'Active schedules', value: data?.activeSchedules, tone: 'warning' },
    { icon: Activity, label: 'Runs (24h)', value: data?.runsToday, tone: 'info' },
    { icon: CheckCircle, label: 'Success', value: data?.successToday, tone: 'success' },
    { icon: XCircle, label: 'Failed', value: data?.failedToday, tone: 'danger' },
  ];

  const rate = data?.successRate ?? 0;
  const rateTone = rate >= 80 ? 'success' : rate >= 50 ? 'warning' : 'danger';

  const runColumns = [
    { key: 'report', header: 'Report', sortable: true, sortValue: (r) => r.report?.name, render: (r) => r.report?.name || '—' },
    {
      key: 'reportType',
      header: 'Type',
      sortValue: (r) => r.report?.reportType,
      render: (r) => (
        <Badge tone={REPORT_TYPE_TONES[r.report?.reportType] || 'neutral'}>{r.report?.reportType || '—'}</Badge>
      ),
    },
    { key: 'triggerType', header: 'Trigger', muted: true },
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      render: (r) => <Badge tone={RUN_STATUS_TONES[r.status] || 'neutral'} dot>{r.status}</Badge>,
    },
    { key: 'rows', header: 'Rows', numeric: true, render: (r) => `${r.rowsProcessed ?? 0}/${r.rowsFetched ?? 0}` },
    { key: 'durationMs', header: 'Duration', numeric: true, align: 'right', muted: true, render: (r) => fmtDuration(r.durationMs) },
    { key: 'startTime', header: 'Time', sortable: true, nowrap: true, muted: true, render: (r) => fmtDateTime(r.startTime) },
  ];

  return (
    <Stack gap="sm">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 'var(--space-lg)' }}>
        {stats.map((s) => (
          <Card key={s.label} pad>
            <div className="ui-row" style={{ gap: 14, flexWrap: 'nowrap' }}>
              <div
                style={{
                  width: 42, height: 42, flexShrink: 0, borderRadius: 'var(--radius-md)',
                  background: toneBg(s.tone), color: toneFg(s.tone),
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}
              >
                <s.icon size={20} strokeWidth={1.9} />
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: '1.45rem', fontWeight: 700, lineHeight: 1.1, color: 'var(--text)' }}>
                  {loading ? '—' : (s.value ?? 0)}
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>{s.label}</div>
              </div>
            </div>
          </Card>
        ))}
      </div>

      <Card pad>
        <div className="ui-row ui-row--between" style={{ marginBottom: 8 }}>
          <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text)' }}>Success rate (24h)</span>
          <span style={{ fontSize: '0.85rem', fontWeight: 700, color: toneFg(rateTone) }}>{rate}%</span>
        </div>
        <div
          style={{ height: 8, borderRadius: 4, background: 'var(--bg-subtle)', overflow: 'hidden' }}
          role="progressbar"
          aria-valuenow={rate}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Success rate over the last 24 hours"
        >
          <div style={{ height: '100%', width: `${rate}%`, background: toneFg(rateTone), transition: 'width .5s' }} />
        </div>
      </Card>

      <Card title="Recent runs">
        <DataTable
          columns={runColumns}
          rows={data?.recentRuns || []}
          rowKey={(r) => r.id}
          loading={loading}
          empty={<div style={emptyStateStyle}>No runs yet.</div>}
        />
      </Card>
    </Stack>
  );
};

// ─── Connections tab ─────────────────────────────────────────
const emptyConnection = {
  name: '', dbType: 'POSTGRES', host: '', port: 5432, dbName: '',
  username: '', encryptedPassword: '', timeoutSeconds: 30, maxRetries: 3, isActive: true,
};

const ConnectionsTab = () => {
  const { tenantVersion } = useAuth();
  const confirm = useConfirm();
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);
  const [testing, setTesting] = useState(null);
  const [form, setForm] = useState(emptyConnection);
  const [editId, setEditId] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const load = async () => {
    try { const r = await api.get('/admin/integration/connections'); setConnections(r.data); }
    catch { showToast('Could not load connections', 'error'); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [tenantVersion]);

  const openAdd = () => { setForm(emptyConnection); setEditId(null); setError(null); setModal(true); };
  // On edit, blank the password field (server sends the __UNCHANGED__ sentinel, never the
  // real value). Empty means "keep the stored password"; a typed value replaces it.
  const openEdit = (c) => { setForm({ ...c, encryptedPassword: '' }); setEditId(c.id); setError(null); setModal(true); };

  const save = async (e) => {
    e?.preventDefault();
    setError(null);
    // Omit the password entirely when left blank on edit → backend keeps existing ciphertext.
    const payload = { ...form };
    if (editId && (!payload.encryptedPassword || !payload.encryptedPassword.trim())) {
      payload.encryptedPassword = PASSWORD_SENTINEL;
    }
    setSaving(true);
    try {
      if (editId) { await api.put(`/admin/integration/connections/${editId}`, payload); }
      else { await api.post('/admin/integration/connections', payload); }
      setModal(false); showToast(editId ? 'Connection updated' : 'Connection created', 'success'); load();
    } catch (err) { setError(err.response?.data?.error || 'Save failed'); }
    finally { setSaving(false); }
  };

  const remove = async (c) => {
    const ok = await confirm({
      title: 'Deactivate connection?',
      message: `"${c.name}" (${c.dbType}) will stop serving report pulls. Reports and schedules that use it will fail until it is restored.`,
      confirmLabel: 'Deactivate connection',
      tone: 'danger',
    });
    if (!ok) return;
    try { await api.delete(`/admin/integration/connections/${c.id}`); showToast('Connection deactivated', 'success'); load(); }
    catch { showToast('Failed to deactivate connection', 'error'); }
  };

  const test = async (id) => {
    setTesting(id);
    try {
      const r = await api.post(`/admin/integration/connections/${id}/test`);
      showToast(r.data.message || 'Test complete', r.data.success ? 'success' : 'error');
      load();
    }
    catch { showToast('Test failed', 'error'); }
    finally { setTesting(null); }
  };

  const columns = [
    {
      key: 'name',
      header: 'Connection',
      sortable: true,
      render: (c) => (
        <span className="ui-row" style={{ gap: 8, flexWrap: 'nowrap' }}>
          <Server size={15} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
          <span style={{ fontWeight: 600 }}>{c.name}</span>
          {c.isActive === false && <Badge tone="neutral">Inactive</Badge>}
        </span>
      ),
    },
    {
      key: 'dbType',
      header: 'Type',
      sortable: true,
      render: (c) => <Badge tone={DB_TYPE_TONES[c.dbType] || 'neutral'}>{c.dbType}</Badge>,
    },
    {
      key: 'host',
      header: 'Target',
      mono: true,
      sortable: true,
      render: (c) => `${c.host}:${c.port}/${c.dbName}`,
    },
    { key: 'timeoutSeconds', header: 'Timeout', numeric: true, muted: true, render: (c) => `${c.timeoutSeconds ?? '—'}s` },
    { key: 'maxRetries', header: 'Retries', numeric: true, muted: true },
    {
      key: 'lastTestStatus',
      header: 'Last test',
      sortable: true,
      render: (c) => (c.lastTestStatus ? <StatusBadge status={c.lastTestStatus} /> : <span className="ui-td--muted">Not tested</span>),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (c) => (
        <>
          <Button size="sm" icon={TestTube} loading={testing === c.id} onClick={() => test(c.id)}>Test</Button>
          <Button size="sm" variant="ghost" iconOnly icon={Edit2} onClick={() => openEdit(c)} aria-label={`Edit ${c.name}`} />
          <Button size="sm" variant="danger-ghost" iconOnly icon={Trash2} onClick={() => remove(c)} aria-label={`Deactivate ${c.name}`} />
        </>
      ),
    },
  ];

  return (
    <>
      <Card>
        <DataTable
          columns={columns}
          rows={connections}
          rowKey={(c) => c.id}
          loading={loading}
          defaultSort={{ key: 'name', dir: 'asc' }}
          toolbarLeft={
            <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
              {connections.length} connection{connections.length !== 1 ? 's' : ''}
            </span>
          }
          toolbarRight={<Button variant="primary" icon={Plus} onClick={openAdd}>Add connection</Button>}
          empty={
            <div style={emptyStateStyle}>
              <p style={{ marginBottom: 14 }}>No connections configured. Add your first external database connection.</p>
              <Button variant="subtle" icon={Plus} onClick={openAdd}>Add connection</Button>
            </div>
          }
        />
      </Card>

      <Modal
        as="form"
        onSubmit={save}
        open={modal}
        onClose={() => setModal(false)}
        size="lg"
        title={editId ? 'Edit connection' : 'New connection'}
        subtitle="Credentials are encrypted at rest and never returned to the browser."
        footer={
          <>
            <Button type="button" onClick={() => setModal(false)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={saving}>{editId ? 'Update' : 'Create'}</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          {error && <Alert tone="danger">{error}</Alert>}

          <FormGrid cols={2}>
            <FormField label="Connection name" className="ui-form-grid--span">
              <Input
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="Core banking Oracle"
              />
            </FormField>

            <FormField label="Database type">
              <Select
                value={form.dbType}
                onChange={(e) => setForm({
                  ...form,
                  dbType: e.target.value,
                  port: e.target.value === 'ORACLE' ? 1521 : e.target.value === 'MSSQL' ? 1433 : 5432,
                })}
                options={[
                  { value: 'ORACLE', label: 'Oracle' },
                  { value: 'POSTGRES', label: 'PostgreSQL' },
                  { value: 'MSSQL', label: 'SQL Server' },
                ]}
              />
            </FormField>

            <FormField label="Host">
              <Input value={form.host} onChange={(e) => setForm({ ...form, host: e.target.value })} placeholder="192.168.1.100" />
            </FormField>

            <FormField label="Port">
              <Input type="number" value={form.port} onChange={(e) => setForm({ ...form, port: parseInt(e.target.value) })} />
            </FormField>

            <FormField label="Database or service name">
              <Input value={form.dbName} onChange={(e) => setForm({ ...form, dbName: e.target.value })} />
            </FormField>

            <FormField label="Username">
              <Input value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} autoComplete="off" />
            </FormField>

            <FormField
              label="Password"
              hint={editId ? 'Leave blank to keep the current password.' : undefined}
            >
              <Input
                type="password"
                value={form.encryptedPassword}
                onChange={(e) => setForm({ ...form, encryptedPassword: e.target.value })}
                placeholder={editId ? '••••••••  (unchanged)' : ''}
                autoComplete="new-password"
              />
            </FormField>

            <FormField label="Timeout (seconds)">
              <Input
                type="number"
                value={form.timeoutSeconds}
                onChange={(e) => setForm({ ...form, timeoutSeconds: parseInt(e.target.value) })}
              />
            </FormField>

            <FormField label="Max retries">
              <Input
                type="number"
                value={form.maxRetries}
                onChange={(e) => setForm({ ...form, maxRetries: parseInt(e.target.value) })}
              />
            </FormField>
          </FormGrid>
        </div>
      </Modal>
    </>
  );
};

// ─── Report configs tab ──────────────────────────────────────
const emptyReport = {
  name: '', reportType: 'TRANSACTION', connectionId: '', sqlText: '',
  columnMapping: '', description: '', isActive: true,
};

const ReportsTab = () => {
  const { tenantVersion } = useAuth();
  const confirm = useConfirm();
  const [reports, setReports] = useState([]);
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);
  const [subTab, setSubTab] = useState('ALL');
  const [form, setForm] = useState(emptyReport);
  const [editId, setEditId] = useState(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [validating, setValidating] = useState(null);
  const [approving, setApproving] = useState(null);
  const [preview, setPreview] = useState(null);
  // In-modal dry-run (validate BEFORE saving) — separate state so it doesn't clash
  // with the card-level Validate flow.
  const [modalTesting, setModalTesting] = useState(false);
  const [modalPreview, setModalPreview] = useState(null);

  const load = async () => {
    try {
      const [r, c] = await Promise.all([api.get('/admin/integration/reports'), api.get('/admin/integration/connections')]);
      setReports(r.data); setConnections(c.data);
    } catch { showToast('Could not load report configs', 'error'); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [tenantVersion]);

  const filtered = subTab === 'ALL' ? reports : reports.filter((r) => r.reportType === subTab);

  // Approval is what lets a report's SQL run against the source database, so
  // it is a deliberate, separately-audited action rather than part of the edit
  // form. Super Admin only — the API returns 403 for anyone else.
  const approve = async (r) => {
    const ok = await confirm({
      title: 'Approve this SQL for execution?',
      message: `"${r.name}" will be allowed to run against ${r.connection?.name || 'the source database'} on its schedule. `
          + 'Review the SQL first — it executes with the stored service credentials. '
          + 'Editing the SQL later automatically revokes this approval.',
      confirmLabel: 'Approve',
    });
    if (!ok) return;
    setApproving(r.id);
    try {
      await api.post(`/admin/integration/reports/${r.id}/approve`);
      showToast('Report approved', 'success');
      load();
    } catch (e) {
      showToast(e?.response?.status === 403
        ? 'Only a Super Admin can approve report SQL'
        : 'Could not approve report', 'error');
    } finally { setApproving(null); }
  };

  const revokeApproval = async (r) => {
    const ok = await confirm({
      title: 'Revoke approval?',
      message: `"${r.name}" will stop running until it is approved again. Any scheduled pull will fail with a clear message.`,
      confirmLabel: 'Revoke approval',
      tone: 'danger',
    });
    if (!ok) return;
    setApproving(r.id);
    try {
      await api.delete(`/admin/integration/reports/${r.id}/approve`);
      showToast('Approval revoked', 'success');
      load();
    } catch (e) {
      showToast(e?.response?.status === 403
        ? 'Only a Super Admin can change report approval'
        : 'Could not revoke approval', 'error');
    } finally { setApproving(null); }
  };

  const openAdd = () => {
    setForm({ ...emptyReport, connectionId: connections[0]?.id || '' });
    setEditId(null); setError(null); setPreview(null); setModalPreview(null); setModal(true);
  };
  const openEdit = (r) => {
    setForm({ ...r, connectionId: r.connection?.id || '' });
    setEditId(r.id); setError(null); setPreview(null); setModalPreview(null); setModal(true);
  };

  // Client-side JSON validation for the column mapping before it reaches the server
  // (the server parse fails silently and the mapping just gets ignored otherwise).
  const validateMappingJson = () => {
    const raw = (form.columnMapping || '').trim();
    if (!raw) return true;
    try { const o = JSON.parse(raw); return o && typeof o === 'object' && !Array.isArray(o); }
    catch { return false; }
  };
  const mappingInvalid = !!form.columnMapping?.trim() && !validateMappingJson();

  // Dry-run the current draft query against the selected connection, without saving.
  const testQuery = async () => {
    setModalPreview(null);
    if (!form.connectionId) { setModalPreview({ success: false, error: 'Select a connection first' }); return; }
    if (!form.sqlText?.trim()) { setModalPreview({ success: false, error: 'Enter a SQL query to validate' }); return; }
    setModalTesting(true);
    try {
      const r = await api.post('/admin/integration/reports/validate-adhoc', { connectionId: form.connectionId, sqlText: form.sqlText });
      setModalPreview(r.data);
    } catch (e) { setModalPreview({ success: false, error: e.response?.data?.error || e.message }); }
    finally { setModalTesting(false); }
  };

  const save = async (e) => {
    e?.preventDefault();
    setError(null);
    if (!validateMappingJson()) { setError('Column mapping is not valid JSON (expected {"SQL_COL":"staging_field"}).'); return; }
    setSaving(true);
    try {
      const payload = { ...form };
      if (editId) { await api.put(`/admin/integration/reports/${editId}`, payload); }
      else { await api.post('/admin/integration/reports', payload); }
      setModal(false); showToast(editId ? 'Report updated' : 'Report created', 'success'); load();
    } catch (err) { setError(err.response?.data?.error || 'Save failed'); }
    finally { setSaving(false); }
  };

  const validate = async (id) => {
    setValidating(id);
    try {
      const r = await api.post(`/admin/integration/reports/${id}/validate`);
      setPreview(r.data);
    } catch (e) { setPreview({ success: false, error: e.message }); }
    finally { setValidating(null); }
  };

  const remove = async (r) => {
    const ok = await confirm({
      title: 'Deactivate report config?',
      message: `"${r.name}" will stop being pulled. Schedules bound to it will no longer produce data.`,
      confirmLabel: 'Deactivate report',
      tone: 'danger',
    });
    if (!ok) return;
    try { await api.delete(`/admin/integration/reports/${r.id}`); showToast('Report deactivated', 'success'); load(); }
    catch { showToast('Failed to deactivate report', 'error'); }
  };

  const columns = [
    {
      key: 'name',
      header: 'Report',
      sortable: true,
      render: (r) => (
        <span className="ui-row" style={{ gap: 8 }}>
          <span style={{ fontWeight: 600 }}>{r.name}</span>
          {r.isActive === false && <Badge tone="neutral">Inactive</Badge>}
        </span>
      ),
    },
    {
      key: 'reportType',
      header: 'Type',
      sortable: true,
      render: (r) => <Badge tone={REPORT_TYPE_TONES[r.reportType] || 'neutral'}>{r.reportType}</Badge>,
    },
    {
      key: 'connection',
      header: 'Connection',
      sortable: true,
      sortValue: (r) => r.connection?.name,
      render: (r) => r.connection?.name || '—',
    },
    {
      // A report only runs once its SQL is approved — the backend blocks the
      // pull otherwise, so this is the single most important thing to see here.
      key: 'approvedBy',
      header: 'Approval',
      sortable: true,
      sortValue: (r) => (r.approvedBy ? 1 : 0),
      render: (r) => {
        if (!r.approvedBy) {
          return <Badge tone="warning" title="This report will not run until a Super Admin approves its SQL.">Not approved</Badge>;
        }
        if (r.approvedBy === 'LEGACY-PRE-APPROVAL') {
          return (
            <Badge tone="info" title="Approved automatically during the upgrade. Review and re-approve for a clean audit trail.">
              Legacy
            </Badge>
          );
        }
        return (
          <Badge tone="success" title={`Approved by ${r.approvedBy}${r.approvedAt ? ` on ${new Date(r.approvedAt).toLocaleString()}` : ''}`}>
            Approved
          </Badge>
        );
      },
    },
    { key: 'description', header: 'Description', muted: true, render: (r) => r.description || 'No description' },
    {
      key: 'sqlText',
      header: 'SQL',
      render: (r) => (
        <code
          style={{
            display: 'block', maxWidth: 340, maxHeight: 58, overflow: 'hidden',
            whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
            fontSize: '0.72rem', color: 'var(--text-secondary)',
          }}
        >
          {r.sqlText?.substring(0, 200)}{r.sqlText?.length > 200 ? '…' : ''}
        </code>
      ),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (r) => (
        <>
          {r.approvedBy
            ? <Button size="sm" variant="ghost" loading={approving === r.id} onClick={() => revokeApproval(r)}>Revoke</Button>
            : <Button size="sm" variant="primary" loading={approving === r.id} onClick={() => approve(r)}>Approve</Button>}
          <Button size="sm" icon={TestTube} loading={validating === r.id} onClick={() => validate(r.id)}>Validate</Button>
          <Button size="sm" variant="ghost" iconOnly icon={Edit2} onClick={() => openEdit(r)} aria-label={`Edit ${r.name}`} />
          <Button size="sm" variant="danger-ghost" iconOnly icon={Trash2} onClick={() => remove(r)} aria-label={`Deactivate ${r.name}`} />
        </>
      ),
    },
  ];

  const subTabs = [
    { key: 'ALL', label: 'All reports', count: reports.length },
    { key: 'MERCHANT', label: 'Merchant', count: reports.filter((r) => r.reportType === 'MERCHANT').length },
    { key: 'TRANSACTION', label: 'Transaction', count: reports.filter((r) => r.reportType === 'TRANSACTION').length },
  ];

  return (
    <Stack gap="sm">
      <Row between>
        <Tabs tabs={subTabs} active={subTab} onChange={setSubTab} variant="pills" inline />
        <Button variant="primary" icon={Plus} onClick={openAdd}>Add report</Button>
      </Row>

      <Card>
        <DataTable
          columns={columns}
          rows={filtered}
          rowKey={(r) => r.id}
          loading={loading}
          defaultSort={{ key: 'name', dir: 'asc' }}
          empty={
            <div style={emptyStateStyle}>
              <p style={{ marginBottom: 14 }}>No report configs. Add your first SQL report.</p>
              <Button variant="subtle" icon={Plus} onClick={openAdd}>Add report</Button>
            </div>
          }
        />
      </Card>

      {/* ── Report editor ─────────────────────────────────────── */}
      <Modal
        as="form"
        onSubmit={save}
        open={modal}
        onClose={() => setModal(false)}
        size="lg"
        title={editId ? 'Edit report config' : 'New report config'}
        subtitle="The query runs against the selected connection and lands in staging."
        footer={
          <>
            <Button type="button" onClick={() => setModal(false)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={saving}>{editId ? 'Update' : 'Create'}</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          {error && <Alert tone="danger">{error}</Alert>}

          <FormGrid cols={2}>
            <FormField label="Report name">
              <Input
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
                placeholder="Daily transaction pull"
              />
            </FormField>

            <FormField label="Type">
              <Select
                value={form.reportType}
                onChange={(e) => setForm({ ...form, reportType: e.target.value })}
                options={[
                  { value: 'MERCHANT', label: 'Merchant' },
                  { value: 'TRANSACTION', label: 'Transaction' },
                ]}
              />
            </FormField>

            <FormField label="Connection" className="ui-form-grid--span">
              <Select
                value={form.connectionId}
                onChange={(e) => { setForm({ ...form, connectionId: e.target.value }); setModalPreview(null); }}
                placeholder="Select connection"
                options={connections
                  .filter((c) => c.isActive !== false)
                  .map((c) => ({ value: c.id, label: `${c.name} (${c.dbType})` }))}
              />
            </FormField>

            <div className="ui-form-grid--span">
              <FormField
                label="SQL query"
                hint="Use :year :month :dateFrom :dateTo as parameters. Changing this SQL revokes the report's approval — a Super Admin must approve it again before it will run."
              >
                <Textarea
                  mono
                  rows={8}
                  value={form.sqlText}
                  onChange={(e) => setForm({ ...form, sqlText: e.target.value })}
                  placeholder={form.reportType === 'MERCHANT'
                    ? 'SELECT merchant_id AS mid, merchant_name, status AS merchant_status, ...\nFROM merchants WHERE created_year = :year'
                    : 'SELECT mid, merchant_name, payment_date, txn_currency_amount, card_scheme, ...\nFROM transactions WHERE payment_date BETWEEN :dateFrom AND :dateTo'}
                />
              </FormField>

              <div className="ui-row" style={{ marginTop: 8 }}>
                <Button size="sm" variant="subtle" icon={TestTube} loading={modalTesting} onClick={testQuery}>
                  Test query
                </Button>
                <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)' }}>
                  Runs the draft against the selected connection without saving.
                </span>
              </div>

              {modalPreview && (
                <div style={{ marginTop: 10 }}>
                  {modalPreview.success ? (
                    <>
                      <Alert tone="success" title={`Query OK, ${modalPreview.rowCount} sample row(s)`} />
                      <div style={{ marginTop: 8, border: '1px solid var(--border)', borderRadius: 'var(--radius-md)', overflow: 'hidden' }}>
                        <PreviewTable result={modalPreview} maxHeight={200} />
                      </div>
                    </>
                  ) : (
                    <Alert tone="danger" title="Query failed">
                      <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: '0.72rem', maxHeight: 120, overflow: 'auto' }}>
                        {modalPreview.error}
                      </pre>
                    </Alert>
                  )}
                </div>
              )}
            </div>

            <FormField
              label="Column mapping (JSON)"
              className="ui-form-grid--span"
              hint={'Maps SQL columns to staging fields, for example { "sql_column": "staging_field" }.'}
              error={mappingInvalid ? 'Not valid JSON' : undefined}
            >
              <Textarea
                mono
                rows={3}
                value={form.columnMapping}
                onChange={(e) => setForm({ ...form, columnMapping: e.target.value })}
                placeholder='{"MERCHANT_ID":"mid", "MERCHANT_NAME":"merchant_name", "PAYMENT_DT":"payment_date"}'
              />
            </FormField>

            <FormField label="Description" className="ui-form-grid--span">
              <Input
                value={form.description || ''}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </FormField>
          </FormGrid>
        </div>
      </Modal>

      {/* ── Validation result (row-level Validate) ─────────────── */}
      <Modal
        open={!!preview}
        onClose={() => setPreview(null)}
        size="xl"
        title={preview?.success ? 'SQL validation passed' : 'SQL validation failed'}
        subtitle={
          preview?.success
            ? `Returned ${preview.rowCount} row(s). Columns: ${(preview.columns || []).join(', ')}`
            : undefined
        }
        footer={<Button onClick={() => setPreview(null)}>Close</Button>}
      >
        {preview && (preview.success ? (
          <PreviewTable result={preview} maxHeight={320} />
        ) : (
          <Alert tone="danger" title="The query could not be executed">
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: '0.75rem' }}>{preview.error}</pre>
          </Alert>
        ))}
      </Modal>
    </Stack>
  );
};

// ─── Schedules tab ───────────────────────────────────────────
const emptySchedule = {
  reportId: '', frequencyLabel: 'DAILY', cronExpression: '0 0 2 * * *', timezone: 'UTC', isEnabled: true,
  preconditionEnabled: false, preconditionSql: '',
};

const SchedulesTab = () => {
  const { tenantVersion } = useAuth();
  const confirm = useConfirm();
  const [schedules, setSchedules] = useState([]);
  const [reports, setReports] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);
  const [runNowModal, setRunNowModal] = useState(null);
  const [runNowDates, setRunNowDates] = useState({ dateFrom: '', dateTo: '' });
  const [form, setForm] = useState(emptySchedule);
  const [editId, setEditId] = useState(null);
  const [saving, setSaving] = useState(false);
  const [triggering, setTriggering] = useState(false);

  const load = async () => {
    try {
      const [s, r] = await Promise.all([api.get('/admin/integration/schedules'), api.get('/admin/integration/reports')]);
      setSchedules(s.data); setReports(r.data);
    } catch { showToast('Could not load schedules', 'error'); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [tenantVersion]);

  const openAdd = () => { setForm({ ...emptySchedule, reportId: reports[0]?.id || '' }); setEditId(null); setModal(true); };
  const openEdit = (s) => { setForm({ ...s, reportId: s.report?.id || '' }); setEditId(s.id); setModal(true); };

  const save = async (e) => {
    e?.preventDefault();
    setSaving(true);
    try {
      if (editId) { await api.put(`/admin/integration/schedules/${editId}`, form); }
      else { await api.post('/admin/integration/schedules', form); }
      setModal(false); showToast(editId ? 'Schedule updated' : 'Schedule created', 'success'); load();
    } catch (err) { showToast('Save failed: ' + (err.response?.data?.error || err.message), 'error'); }
    finally { setSaving(false); }
  };

  const toggle = async (id) => {
    try { const r = await api.post(`/admin/integration/schedules/${id}/toggle`); showToast(r.data?.enabled ? 'Schedule enabled' : 'Schedule paused', 'success'); load(); }
    catch { showToast('Toggle failed', 'error'); }
  };

  const runNow = async (e) => {
    e?.preventDefault();
    setTriggering(true);
    try {
      // Fix: send whenever EITHER bound is set (was dropping a from-only or to-only range).
      const hasDates = runNowDates.dateFrom || runNowDates.dateTo;
      await api.post(`/admin/integration/schedules/${runNowModal.id}/run-now`, hasDates ? runNowDates : {});
      showToast('Pull started. Track progress in run history.', 'success');
      setRunNowModal(null);
      load();
    } catch (err) { showToast('Failed: ' + (err.response?.data?.error || err.message), 'error'); }
    finally { setTriggering(false); }
  };

  const remove = async (s) => {
    const ok = await confirm({
      title: 'Delete schedule?',
      message: `The schedule for "${s.report?.name || 'this report'}" (${s.cronExpression}) will be removed and will stop firing. Past runs are kept in run history.`,
      confirmLabel: 'Delete schedule',
      tone: 'danger',
    });
    if (!ok) return;
    try { await api.delete(`/admin/integration/schedules/${s.id}`); showToast('Schedule deleted', 'success'); load(); }
    catch { showToast('Delete failed', 'error'); }
  };

  const setFreq = (val) => {
    const opt = FREQ_OPTIONS.find((f) => f.value === val);
    setForm({ ...form, frequencyLabel: val, cronExpression: opt?.cron || form.cronExpression });
  };

  const columns = [
    {
      key: 'report',
      header: 'Report',
      sortable: true,
      sortValue: (s) => s.report?.name,
      render: (s) => <span style={{ fontWeight: 600 }}>{s.report?.name || 'Unknown report'}</span>,
    },
    {
      key: 'reportType',
      header: 'Type',
      sortValue: (s) => s.report?.reportType,
      render: (s) => (
        <Badge tone={REPORT_TYPE_TONES[s.report?.reportType] || 'neutral'}>{s.report?.reportType || '—'}</Badge>
      ),
    },
    {
      key: 'isEnabled',
      header: 'State',
      sortable: true,
      render: (s) => <StatusBadge status={s.isEnabled ? 'Active' : 'Paused'} />,
    },
    { key: 'cronExpression', header: 'Cron', mono: true, nowrap: true },
    {
      key: 'preconditionEnabled',
      header: 'Gate',
      nowrap: true,
      render: (s) => (s.preconditionEnabled
        ? <Badge tone="info" title={s.preconditionSql || ''}>Upstream check</Badge>
        : '—'),
    },
    { key: 'frequencyLabel', header: 'Frequency', muted: true, render: (s) => s.frequencyLabel || 'Custom' },
    { key: 'timezone', header: 'Timezone', muted: true, render: (s) => s.timezone || 'UTC' },
    {
      key: 'lastRunAt',
      header: 'Last run',
      sortable: true,
      nowrap: true,
      muted: true,
      render: (s) => fmtDateTime(s.lastRunAt),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (s) => (
        <>
          <Button
            size="sm"
            variant="subtle"
            icon={Zap}
            onClick={() => { setRunNowDates({ dateFrom: '', dateTo: '' }); setRunNowModal(s); }}
          >
            Run now
          </Button>
          <Button
            size="sm"
            variant="ghost"
            iconOnly
            icon={s.isEnabled ? Pause : Play}
            onClick={() => toggle(s.id)}
            aria-label={s.isEnabled ? 'Pause schedule' : 'Enable schedule'}
          />
          <Button size="sm" variant="ghost" iconOnly icon={Edit2} onClick={() => openEdit(s)} aria-label="Edit schedule" />
          <Button size="sm" variant="danger-ghost" iconOnly icon={Trash2} onClick={() => remove(s)} aria-label="Delete schedule" />
        </>
      ),
    },
  ];

  return (
    <>
      <Card>
        <DataTable
          columns={columns}
          rows={schedules}
          rowKey={(s) => s.id}
          loading={loading}
          toolbarLeft={
            <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
              {schedules.length} schedule{schedules.length !== 1 ? 's' : ''}
            </span>
          }
          toolbarRight={<Button variant="primary" icon={Plus} onClick={openAdd}>Add schedule</Button>}
          empty={
            <div style={emptyStateStyle}>
              <p style={{ marginBottom: 14 }}>No schedules. Create a report config first, then schedule it.</p>
              <Button variant="subtle" icon={Plus} onClick={openAdd}>Add schedule</Button>
            </div>
          }
        />
      </Card>

      {/* ── Schedule editor ───────────────────────────────────── */}
      <Modal
        as="form"
        onSubmit={save}
        open={modal}
        onClose={() => setModal(false)}
        title={editId ? 'Edit schedule' : 'New schedule'}
        subtitle="Cron fires in the selected timezone."
        footer={
          <>
            <Button type="button" onClick={() => setModal(false)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={saving}>{editId ? 'Update' : 'Create'}</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          <FormField label="Report">
            <Select
              value={form.reportId}
              onChange={(e) => setForm({ ...form, reportId: e.target.value })}
              placeholder="Select report"
              options={reports
                .filter((r) => r.isActive !== false)
                .map((r) => ({ value: r.id, label: `${r.name} (${r.reportType})` }))}
            />
          </FormField>

          <FormField label="Frequency">
            <Select
              value={form.frequencyLabel}
              onChange={(e) => setFreq(e.target.value)}
              options={FREQ_OPTIONS.map((f) => ({ value: f.value, label: f.label }))}
            />
          </FormField>

          <FormField
            label="Cron expression"
            hint="6-field Spring cron (sec min hour dom mon dow). The Quartz '?' token is not supported."
          >
            <Input
              mono
              value={form.cronExpression}
              onChange={(e) => setForm({ ...form, cronExpression: e.target.value })}
              placeholder="0 0 2 * * *"
            />
          </FormField>

          <FormField label="Timezone">
            <Select
              value={form.timezone}
              onChange={(e) => setForm({ ...form, timezone: e.target.value })}
              options={TIMEZONES}
            />
          </FormField>

          <Checkbox
            checked={!!form.preconditionEnabled}
            onChange={(e) => setForm({ ...form, preconditionEnabled: e.target.checked })}
            label="Wait for upstream batch to complete"
            hint="Runs a check query on the same connection before each scheduled pull. If it is not ready, the pull is deferred and re-checked on the retry backoff. Run now ignores this."
          />

          {form.preconditionEnabled && (
            <FormField
              label="Check query"
              hint="Single SELECT. Proceeds when the first cell of the first row is true, a non-zero number, or Y / YES / 1 / COMPLETED / SUCCESS / DONE."
            >
              <Textarea
                mono
                rows={3}
                value={form.preconditionSql || ''}
                onChange={(e) => setForm({ ...form, preconditionSql: e.target.value })}
                placeholder="SELECT COUNT(*) FROM batch_control WHERE business_date = CURRENT_DATE AND status = 'COMPLETED'"
              />
            </FormField>
          )}
        </div>
      </Modal>

      {/* ── Run now ───────────────────────────────────────────── */}
      <Modal
        as="form"
        onSubmit={runNow}
        open={!!runNowModal}
        onClose={() => setRunNowModal(null)}
        size="sm"
        title={`Run now: ${runNowModal?.report?.name || ''}`}
        subtitle="Trigger an immediate pull. Leave the dates blank to use the current month."
        footer={
          <>
            <Button type="button" onClick={() => setRunNowModal(null)}>Cancel</Button>
            <Button type="submit" variant="primary" icon={Zap} loading={triggering}>Execute now</Button>
          </>
        }
      >
        <FormGrid cols={2}>
          <FormField label="Date from (optional)">
            <Input
              type="date"
              value={runNowDates.dateFrom}
              onChange={(e) => setRunNowDates({ ...runNowDates, dateFrom: e.target.value })}
            />
          </FormField>
          <FormField label="Date to (optional)">
            <Input
              type="date"
              value={runNowDates.dateTo}
              onChange={(e) => setRunNowDates({ ...runNowDates, dateTo: e.target.value })}
            />
          </FormField>
        </FormGrid>
      </Modal>
    </>
  );
};

// ─── Run history tab ─────────────────────────────────────────
const STATUS_FILTERS = [
  { key: 'ALL', label: 'All' },
  { key: 'SUCCESS', label: 'Success' },
  { key: 'FAILED', label: 'Failed' },
  { key: 'RUNNING', label: 'Running' },
  { key: 'RETRYING', label: 'Retrying' },
];

const RunHistoryTab = () => {
  const { tenantVersion } = useAuth();
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');
  const [detail, setDetail] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page, size: 20 });
      if (statusFilter) params.append('status', statusFilter);
      const r = await api.get('/admin/integration/runs?' + params.toString());
      setRuns(r.data.content || []); setTotal(r.data.totalElements || 0);
    } catch { showToast('Could not load run history', 'error'); }
    finally { setLoading(false); }
  }, [page, statusFilter]);

  useEffect(() => { load(); const t = setInterval(load, 15000); return () => clearInterval(t); }, [load, tenantVersion]);

  const retry = async (id) => {
    try { await api.post(`/admin/integration/runs/${id}/retry`); showToast('Retry started', 'success'); load(); }
    catch { showToast('Retry failed', 'error'); }
  };

  const columns = [
    {
      key: 'report',
      header: 'Report',
      sortable: true,
      sortValue: (r) => r.report?.name,
      render: (r) => <span style={{ fontWeight: 500 }}>{r.report?.name || '—'}</span>,
    },
    {
      key: 'reportType',
      header: 'Type',
      sortValue: (r) => r.report?.reportType,
      render: (r) => (
        <Badge tone={REPORT_TYPE_TONES[r.report?.reportType] || 'neutral'}>{r.report?.reportType || '—'}</Badge>
      ),
    },
    { key: 'triggerType', header: 'Trigger', muted: true },
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      render: (r) => <Badge tone={RUN_STATUS_TONES[r.status] || 'neutral'} dot>{r.status}</Badge>,
    },
    { key: 'attempt', header: 'Attempt', numeric: true, render: (r) => `${r.attemptNumber}/${r.maxRetries}` },
    { key: 'rows', header: 'Rows', numeric: true, render: (r) => `${r.rowsProcessed ?? 0}/${r.rowsFetched ?? 0}` },
    { key: 'durationMs', header: 'Duration', numeric: true, align: 'right', muted: true, render: (r) => fmtDuration(r.durationMs) },
    { key: 'startTime', header: 'Started', sortable: true, nowrap: true, muted: true, render: (r) => fmtDateTime(r.startTime) },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (r) => (
        <>
          {r.status === 'FAILED' && (
            <Button
              size="sm"
              icon={RotateCcw}
              onClick={(e) => { e.stopPropagation(); retry(r.id); }}
            >
              Retry
            </Button>
          )}
          <Button
            size="sm"
            variant="ghost"
            iconOnly
            icon={Eye}
            onClick={(e) => { e.stopPropagation(); setDetail(r); }}
            aria-label="View run details"
          />
        </>
      ),
    },
  ];

  const totalPages = Math.max(1, Math.ceil(total / 20));

  return (
    <Stack gap="sm">
      <Row between>
        <div style={{ marginBottom: 'calc(var(--space-2xl) * -1)' }}>
          <Tabs
            tabs={STATUS_FILTERS}
            active={statusFilter || 'ALL'}
            onChange={(k) => { setStatusFilter(k === 'ALL' ? '' : k); setPage(0); }}
            variant="pills"
          />
        </div>
        <Button icon={RefreshCw} onClick={load}>Refresh</Button>
      </Row>

      <Card
        footer={total > 20 ? (
          <div className="ui-row" style={{ justifyContent: 'center', width: '100%' }}>
            <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</Button>
            <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
              Page {page + 1} of {totalPages}
            </span>
            <Button disabled={(page + 1) * 20 >= total} onClick={() => setPage((p) => p + 1)}>Next</Button>
          </div>
        ) : undefined}
      >
        <DataTable
          columns={columns}
          rows={runs}
          rowKey={(r) => r.id}
          loading={loading && runs.length === 0}
          onRowClick={(r) => setDetail(r)}
          empty={<div style={emptyStateStyle}>No runs found.</div>}
        />
      </Card>

      <Modal
        open={!!detail}
        onClose={() => setDetail(null)}
        size="lg"
        title={`Run detail: ${detail?.report?.name || '—'}`}
        subtitle={detail ? `${detail.triggerType} · started ${fmtDateTime(detail.startTime)}` : undefined}
        footer={<Button onClick={() => setDetail(null)}>Close</Button>}
      >
        {detail && (
          <div className="ui-stack ui-stack--sm">
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 'var(--space-lg)', fontSize: '0.83rem' }}>
              <div>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.72rem', fontWeight: 600 }}>Status</div>
                <Badge tone={RUN_STATUS_TONES[detail.status] || 'neutral'} dot>{detail.status}</Badge>
              </div>
              <div>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.72rem', fontWeight: 600 }}>Date range</div>
                {detail.dateRangeFrom || 'Default'} to {detail.dateRangeTo || 'Default'}
              </div>
              <div>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.72rem', fontWeight: 600 }}>End time</div>
                {detail.endTime ? new Date(detail.endTime).toLocaleString() : 'In progress'}
              </div>
              <div>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.72rem', fontWeight: 600 }}>Rows</div>
                {detail.rowsProcessed ?? 0} processed / {detail.rowsFetched ?? 0} fetched / {detail.rowsFailed ?? 0} failed
              </div>
              <div>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.72rem', fontWeight: 600 }}>Attempt</div>
                {detail.attemptNumber}/{detail.maxRetries}
              </div>
              <div>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.72rem', fontWeight: 600 }}>Schedule</div>
                {detail.schedule?.report?.name || (detail.schedule?.id ? `#${detail.schedule.id}` : 'Manual run')}
              </div>
            </div>

            {detail.errorMessage && (
              <Alert tone="danger" title="Error">
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: '0.72rem', maxHeight: 160, overflow: 'auto' }}>
                  {detail.errorMessage}
                </pre>
              </Alert>
            )}
          </div>
        )}
      </Modal>
    </Stack>
  );
};

// ═══════════════════════════════════════════════════════════════
//  Main component — single page with tabs
// ═══════════════════════════════════════════════════════════════
const TABS = [
  { key: 'overview', label: 'Overview', icon: Cable },
  { key: 'connections', label: 'Connections', icon: Database },
  { key: 'reports', label: 'Report configs', icon: FileCode },
  { key: 'schedules', label: 'Schedules', icon: Clock },
  { key: 'runs', label: 'Run history', icon: ScrollText },
];

const IntegrationHub = ({ defaultTab }) => {
  const [activeTab, setActiveTab] = useState(defaultTab || 'overview');

  const renderTab = () => {
    switch (activeTab) {
      case 'connections': return <ConnectionsTab />;
      case 'reports': return <ReportsTab />;
      case 'schedules': return <SchedulesTab />;
      case 'runs': return <RunHistoryTab />;
      default: return <OverviewTab />;
    }
  };

  return (
    <Page
      title="Data integration hub"
      subtitle="Configure external database connections, SQL reports and automated schedules. File upload remains available under Operations, Upload files."
      icon={Cable}
    >
      <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />
      {renderTab()}
    </Page>
  );
};

export default IntegrationHub;
