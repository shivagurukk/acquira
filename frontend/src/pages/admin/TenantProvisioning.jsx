import { useState, useEffect, useCallback } from 'react';
import { FileCode, ScrollText, Layers, Play, Plus, Server } from 'lucide-react';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Stack, Card, Button, Badge, StatusBadge, Alert, Tabs, DataTable, Modal,
  FormField, FormGrid, Input, Textarea, Select, Switch, useConfirm,
} from '../../components/ui';

/**
 * Admin > Tenant Provisioning (SUPER_ADMIN only).
 *
 * Two concerns:
 *  1. Provision Scripts — CRUD over tenant_provision_script (the scripts that
 *     run automatically when a tenant is created), a per-tenant "Run now"
 *     action, and the execution log.
 *  2. Migration Registry — read-only catalog of schema migrations with a
 *     prod-applied toggle (files remain the landing mechanism; this is the
 *     visibility layer).
 */

const PLACEHOLDER_HINT =
  'Placeholders ${TENANT_ID} ${INSTITUTION_ID} ${BANK_SHORT_CODE} ${BASE_CURRENCY} ${BANK_NAME} are substituted from the tenant row at run time. Scripts must be idempotent (ON CONFLICT / IF NOT EXISTS).';

const emptyForm = {
  scriptId: null,
  scriptName: '',
  scriptOrder: 100,
  description: '',
  scriptSql: '',
  isActive: true,
  continueOnError: false,
};

export default function TenantProvisioning() {
  const confirm = useConfirm();
  const [tab, setTab] = useState('scripts');

  const [scripts, setScripts] = useState([]);
  const [logs, setLogs] = useState([]);
  const [registry, setRegistry] = useState([]);
  const [tenants, setTenants] = useState([]);
  const [loading, setLoading] = useState(true);

  const [form, setForm] = useState(emptyForm);
  const [showForm, setShowForm] = useState(false);
  const [saving, setSaving] = useState(false);
  const [runTenantId, setRunTenantId] = useState('');
  const [logTenantFilter, setLogTenantFilter] = useState('');
  const [running, setRunning] = useState(false);

  const loadScripts = useCallback(async () => {
    try {
      const { data } = await api.get('/admin/provision/scripts');
      setScripts(data || []);
    } catch { showToast('Failed to load scripts', 'error'); }
  }, []);

  const loadLogs = useCallback(async (tenantId) => {
    try {
      const { data } = await api.get('/admin/provision/logs', {
        params: tenantId ? { tenantId } : {},
      });
      setLogs(data || []);
    } catch { showToast('Failed to load logs', 'error'); }
  }, []);

  const loadRegistry = useCallback(async () => {
    try {
      const { data } = await api.get('/admin/provision/registry');
      setRegistry(data || []);
    } catch { showToast('Failed to load migration registry', 'error'); }
  }, []);

  const loadTenants = useCallback(async () => {
    try {
      const { data } = await api.get('/banks');
      setTenants(data || []);
    } catch { /* dropdown just stays empty */ }
  }, []);

  useEffect(() => {
    setLoading(true);
    Promise.all([loadScripts(), loadLogs(''), loadRegistry(), loadTenants()])
      .finally(() => setLoading(false));
  }, [loadScripts, loadLogs, loadRegistry, loadTenants]);

  const openCreate = () => { setForm(emptyForm); setShowForm(true); };
  const openEdit = (s) => {
    setForm({
      scriptId: s.script_id,
      scriptName: s.script_name,
      scriptOrder: s.script_order,
      description: s.description || '',
      scriptSql: s.script_sql,
      isActive: !!s.is_active,
      continueOnError: !!s.continue_on_error,
    });
    setShowForm(true);
  };

  const saveScript = async (e) => {
    e?.preventDefault();
    if (!form.scriptName.trim() || !form.scriptSql.trim()) {
      showToast('Name and SQL are required', 'warning');
      return;
    }
    const body = {
      scriptName: form.scriptName,
      scriptOrder: Number(form.scriptOrder) || 100,
      description: form.description,
      scriptSql: form.scriptSql,
      isActive: form.isActive,
      continueOnError: form.continueOnError,
    };
    setSaving(true);
    try {
      if (form.scriptId) {
        await api.put(`/admin/provision/scripts/${form.scriptId}`, body);
        showToast('Script updated', 'success');
      } else {
        await api.post('/admin/provision/scripts', body);
        showToast('Script created', 'success');
      }
      setShowForm(false);
      loadScripts();
    } catch (e) {
      showToast(e?.response?.data?.error || 'Save failed', 'error');
    } finally { setSaving(false); }
  };

  const deleteScript = async (s) => {
    const ok = await confirm({
      title: 'Delete provisioning script?',
      message: `"${s.script_name}" will no longer run for new tenants. Past log entries are kept.`,
      confirmLabel: 'Delete script',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await api.delete(`/admin/provision/scripts/${s.script_id}`);
      showToast('Script deleted', 'success');
      loadScripts();
    } catch { showToast('Delete failed', 'error'); }
  };

  const runNow = async () => {
    if (!runTenantId) { showToast('Pick a tenant first', 'warning'); return; }
    const tenant = tenants.find((t) => String(t.tenantId) === String(runTenantId));
    const ok = await confirm({
      title: 'Run provisioning scripts?',
      message: `All active scripts will run against ${tenant?.bankName || 'this tenant'} in order. Scripts are idempotent, so re-running is safe.`,
      confirmLabel: 'Run now',
      tone: 'warning',
    });
    if (!ok) return;

    setRunning(true);
    try {
      const { data } = await api.post(`/admin/provision/run/${runTenantId}`);
      const results = data?.results || [];
      const failed = results.filter((r) => r.status === 'FAILED').length;
      showToast(
        failed === 0
          ? `Provisioning complete — ${results.length} scripts ran`
          : `Provisioning finished with ${failed} failure(s) — check the log`,
        failed === 0 ? 'success' : 'warning'
      );
      loadLogs(logTenantFilter);
    } catch (e) {
      showToast(e?.response?.data?.error || 'Run failed', 'error');
    } finally { setRunning(false); }
  };

  const toggleProdApplied = async (row) => {
    try {
      await api.put(`/admin/provision/registry/${row.registry_id}/prod-applied`, {
        applied: !row.applied_on_prod,
      });
      loadRegistry();
    } catch { showToast('Update failed', 'error'); }
  };

  const tenantOptions = tenants.map((t) => ({
    value: t.tenantId,
    label: `${t.bankName} (${t.bankShortCode})`,
  }));

  // ── Column definitions ────────────────────────────────────────────────────
  const scriptColumns = [
    { key: 'script_order', header: 'Order', sortable: true, numeric: true, width: 80 },
    { key: 'script_name', header: 'Name', sortable: true, render: (s) => <strong>{s.script_name}</strong> },
    { key: 'description', header: 'Description', muted: true },
    {
      key: 'is_active',
      header: 'Active',
      sortable: true,
      render: (s) => <StatusBadge status={s.is_active ? 'Active' : 'Inactive'} />,
    },
    {
      key: 'continue_on_error',
      header: 'On failure',
      render: (s) => (
        <Badge tone={s.continue_on_error ? 'neutral' : 'warning'}>
          {s.continue_on_error ? 'Continue' : 'Stop chain'}
        </Badge>
      ),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (s) => (
        <>
          <Button size="sm" onClick={() => openEdit(s)}>Edit</Button>
          <Button size="sm" variant="danger-ghost" onClick={() => deleteScript(s)}>Delete</Button>
        </>
      ),
    },
  ];

  const logColumns = [
    {
      key: 'executed_at',
      header: 'When',
      sortable: true,
      nowrap: true,
      render: (l) => (l.executed_at ? new Date(l.executed_at).toLocaleString() : '—'),
    },
    { key: 'bank_name', header: 'Tenant', sortable: true, render: (l) => l.bank_name || l.tenant_id },
    { key: 'script_name', header: 'Script', sortable: true, render: (l) => <strong>{l.script_name}</strong> },
    { key: 'status', header: 'Status', sortable: true, render: (l) => <StatusBadge status={l.status} /> },
    {
      key: 'duration_ms',
      header: 'Duration',
      sortable: true,
      numeric: true,
      align: 'right',
      render: (l) => (l.duration_ms != null ? `${l.duration_ms} ms` : '—'),
    },
    { key: 'executed_by', header: 'By', muted: true },
    {
      key: 'error_message',
      header: 'Error',
      muted: true,
      render: (l) => (
        <span style={{ display: 'block', maxWidth: 340, wordBreak: 'break-word' }}>
          {l.error_message}
        </span>
      ),
    },
  ];

  const registryColumns = [
    { key: 'migration_name', header: 'Migration', sortable: true, mono: true },
    { key: 'description', header: 'Description', muted: true },
    {
      key: 'applied_on_dev',
      header: 'Dev',
      sortable: true,
      render: (r) => (r.applied_on_dev ? <StatusBadge status="Applied" /> : <span className="ui-td--muted">—</span>),
    },
    {
      key: 'applied_on_prod',
      header: 'Prod',
      sortable: true,
      render: (r) => (
        <Switch
          checked={r.applied_on_prod}
          onChange={() => toggleProdApplied(r)}
          label={r.applied_on_prod ? 'Applied' : 'Pending'}
          aria-label={`Mark ${r.migration_name} as applied on production`}
        />
      ),
    },
    {
      key: 'created_at',
      header: 'Registered',
      sortable: true,
      nowrap: true,
      muted: true,
      render: (r) => (r.created_at ? new Date(r.created_at).toLocaleDateString() : '—'),
    },
  ];

  const tabs = [
    { key: 'scripts', label: 'Provision scripts', icon: FileCode, count: scripts.length },
    { key: 'logs', label: 'Execution log', icon: ScrollText, count: logs.length },
    { key: 'registry', label: 'Migration registry', icon: Layers, count: registry.length },
  ];

  return (
    <Page
      title="Tenant provisioning"
      subtitle="Scripts here run automatically when a new tenant is created, in order. They can be re-run per tenant at any time, so every script must be idempotent."
      icon={Server}
    >
      <Tabs tabs={tabs} active={tab} onChange={setTab} />

      {tab === 'scripts' && (
        <Stack gap="sm">
          <div className="ui-row ui-row--between">
            <div className="ui-row">
              <Select
                value={runTenantId}
                onChange={(e) => setRunTenantId(e.target.value)}
                placeholder="Run scripts for tenant…"
                options={tenantOptions}
                style={{ width: 260 }}
                aria-label="Tenant to provision"
              />
              <Button variant="primary" icon={Play} onClick={runNow} loading={running}>
                {running ? 'Running…' : 'Run now'}
              </Button>
            </div>
            <Button variant="primary" icon={Plus} onClick={openCreate}>New script</Button>
          </div>

          <Card>
            <DataTable
              columns={scriptColumns}
              rows={scripts}
              rowKey={(s) => s.script_id}
              loading={loading}
              defaultSort={{ key: 'script_order', dir: 'asc' }}
              empty={
                <div style={{ padding: 'var(--space-3xl)', textAlign: 'center' }}>
                  <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 14 }}>
                    No provisioning scripts defined yet. New tenants will be created with schema defaults only.
                  </p>
                  <Button variant="subtle" icon={Plus} onClick={openCreate}>Add the first script</Button>
                </div>
              }
            />
          </Card>
        </Stack>
      )}

      {tab === 'logs' && (
        <Card>
          <DataTable
            columns={logColumns}
            rows={logs}
            rowKey={(l) => l.log_id}
            loading={loading}
            defaultSort={{ key: 'executed_at', dir: 'desc' }}
            pageSize={15}
            toolbarLeft={
              <Select
                value={logTenantFilter}
                onChange={(e) => { setLogTenantFilter(e.target.value); loadLogs(e.target.value); }}
                placeholder="All tenants"
                options={tenantOptions}
                style={{ width: 240 }}
                aria-label="Filter log by tenant"
              />
            }
            toolbarRight={<Button onClick={() => loadLogs(logTenantFilter)}>Refresh</Button>}
            empty={
              <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                No provisioning runs logged yet.
              </div>
            }
          />
        </Card>
      )}

      {tab === 'registry' && (
        <Stack gap="sm">
          <Alert tone="info">
            Migration files remain the landing mechanism — this registry is the visibility layer.
            Toggling <strong>Prod</strong> records that a migration has been applied; it does not run anything.
          </Alert>
          <Card>
            <DataTable
              columns={registryColumns}
              rows={registry}
              rowKey={(r) => r.registry_id}
              loading={loading}
              empty={
                <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                  Registry is empty.
                </div>
              }
            />
          </Card>
        </Stack>
      )}

      {/* ── Script editor ─────────────────────────────────────────────────── */}
      <Modal
        as="form"
        onSubmit={saveScript}
        open={showForm}
        onClose={() => setShowForm(false)}
        size="lg"
        title={form.scriptId ? 'Edit script' : 'New script'}
        subtitle="Runs inside the tenant's schema during provisioning."
        footer={
          <>
            <Button type="button" onClick={() => setShowForm(false)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={saving}>Save script</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          <FormGrid cols={4}>
            <FormField label="Name" required className="ui-form-grid--span">
              <Input
                value={form.scriptName}
                onChange={(e) => setForm({ ...form, scriptName: e.target.value })}
                placeholder="seed_default_categories"
                required
              />
            </FormField>
            <FormField label="Order" hint="Ascending">
              <Input
                type="number"
                value={form.scriptOrder}
                onChange={(e) => setForm({ ...form, scriptOrder: e.target.value })}
              />
            </FormField>
            <FormField label="On failure">
              <Select
                value={form.continueOnError ? '1' : '0'}
                onChange={(e) => setForm({ ...form, continueOnError: e.target.value === '1' })}
                options={[
                  { value: '0', label: 'Stop the chain' },
                  { value: '1', label: 'Continue to next' },
                ]}
              />
            </FormField>
          </FormGrid>

          <FormField label="Description">
            <Input
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder="What this script sets up"
            />
          </FormField>

          <FormField label="SQL" required hint={PLACEHOLDER_HINT}>
            <Textarea
              mono
              rows={10}
              value={form.scriptSql}
              onChange={(e) => setForm({ ...form, scriptSql: e.target.value })}
              placeholder={'INSERT INTO … VALUES (${TENANT_ID}, …)\nON CONFLICT DO NOTHING;'}
              required
            />
          </FormField>

          <Switch
            checked={form.isActive}
            onChange={(e) => setForm({ ...form, isActive: e.target.checked })}
            label="Active — run this script for new tenants"
          />
        </div>
      </Modal>
    </Page>
  );
}
