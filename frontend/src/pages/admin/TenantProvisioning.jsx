import { useState, useEffect, useCallback } from 'react';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';

/**
 * Admin > Tenant Provisioning (SUPER_ADMIN only).
 *
 * Two tabs:
 *  1. Provision Scripts — CRUD over tenant_provision_script (the scripts that
 *     run automatically when a tenant is created), a per-tenant "Run now"
 *     action, and the execution log.
 *  2. Migration Registry — read-only catalog of schema migrations with a
 *     prod-applied toggle (files remain the landing mechanism; this is the
 *     visibility layer).
 */

const PLACEHOLDER_HINT =
  'Placeholders: ${TENANT_ID} ${INSTITUTION_ID} ${BANK_SHORT_CODE} ${BASE_CURRENCY} ${BANK_NAME} — substituted from the tenant row at run time. Scripts must be idempotent (ON CONFLICT / IF NOT EXISTS).';

const emptyForm = {
  scriptId: null,
  scriptName: '',
  scriptOrder: 100,
  description: '',
  scriptSql: '',
  isActive: true,
  continueOnError: false,
};

const statusColor = (s) =>
  s === 'SUCCESS' ? 'var(--status-success, #16a34a)'
  : s === 'FAILED' ? 'var(--status-error, #dc2626)'
  : 'var(--text-secondary, #64748b)';

export default function TenantProvisioning() {
  const [tab, setTab] = useState('scripts');

  const [scripts, setScripts] = useState([]);
  const [logs, setLogs] = useState([]);
  const [registry, setRegistry] = useState([]);
  const [tenants, setTenants] = useState([]);
  const [loading, setLoading] = useState(false);

  const [form, setForm] = useState(emptyForm);
  const [showForm, setShowForm] = useState(false);
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

  const saveScript = async () => {
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
    }
  };

  const deleteScript = async (s) => {
    if (!window.confirm(`Delete script "${s.script_name}"? Past log entries are kept.`)) return;
    try {
      await api.delete(`/admin/provision/scripts/${s.script_id}`);
      showToast('Script deleted', 'success');
      loadScripts();
    } catch { showToast('Delete failed', 'error'); }
  };

  const runNow = async () => {
    if (!runTenantId) { showToast('Pick a tenant first', 'warning'); return; }
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

  const inputStyle = {
    width: '100%', padding: '8px 10px', borderRadius: 'var(--radius-md, 8px)',
    border: '1px solid var(--border)', background: 'var(--bg-card)',
    color: 'var(--text)', fontSize: 14, boxSizing: 'border-box',
  };
  const th = {
    textAlign: 'left', padding: '10px 12px', fontSize: 12, fontWeight: 600,
    color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em',
    borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap',
  };
  const td = {
    padding: '10px 12px', fontSize: 13, color: 'var(--text)',
    borderBottom: '1px solid var(--border)', verticalAlign: 'top',
  };
  const btn = {
    padding: '7px 14px', borderRadius: 'var(--radius-md, 8px)', fontSize: 13,
    fontWeight: 600, cursor: 'pointer', border: '1px solid var(--border)',
    background: 'var(--bg-card)', color: 'var(--text)',
  };
  const btnPrimary = { ...btn, background: 'var(--brand)', borderColor: 'var(--brand)', color: '#fff' };
  const card = {
    background: 'var(--bg-card)', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg, 12px)', boxShadow: 'var(--shadow-sm)', overflow: 'hidden',
  };
  const tabBtn = (active) => ({
    ...btn,
    background: active ? 'var(--brand)' : 'var(--bg-card)',
    color: active ? '#fff' : 'var(--text)',
    borderColor: active ? 'var(--brand)' : 'var(--border)',
  });

  return (
    <div style={{ padding: 24, maxWidth: 1280, margin: '0 auto' }}>
      <div style={{ marginBottom: 20 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, color: 'var(--text)', margin: 0 }}>
          Tenant Provisioning
        </h1>
        <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 6 }}>
          Scripts here run automatically when a new tenant is created, in order. They can be re-run
          per tenant at any time (all scripts must be idempotent). The Migration Registry tab tracks
          which schema migrations are applied where.
        </p>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 18 }}>
        <button style={tabBtn(tab === 'scripts')} onClick={() => setTab('scripts')}>Provision Scripts</button>
        <button style={tabBtn(tab === 'logs')} onClick={() => setTab('logs')}>Execution Log</button>
        <button style={tabBtn(tab === 'registry')} onClick={() => setTab('registry')}>Migration Registry</button>
      </div>

      {loading && <div style={{ color: 'var(--text-secondary)', fontSize: 13 }}>Loading…</div>}

      {tab === 'scripts' && !loading && (
        <>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12, flexWrap: 'wrap', gap: 10 }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <select value={runTenantId} onChange={(e) => setRunTenantId(e.target.value)}
                style={{ ...inputStyle, width: 260 }}>
                <option value="">Run scripts for tenant…</option>
                {tenants.map((t) => (
                  <option key={t.tenantId} value={t.tenantId}>
                    {t.bankName} ({t.bankShortCode})
                  </option>
                ))}
              </select>
              <button style={btnPrimary} onClick={runNow} disabled={running}>
                {running ? 'Running…' : 'Run now'}
              </button>
            </div>
            <button style={btnPrimary} onClick={openCreate}>+ New Script</button>
          </div>

          <div style={card}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={th}>Order</th>
                  <th style={th}>Name</th>
                  <th style={th}>Description</th>
                  <th style={th}>Active</th>
                  <th style={th}>On error</th>
                  <th style={th}></th>
                </tr>
              </thead>
              <tbody>
                {scripts.map((s) => (
                  <tr key={s.script_id}>
                    <td style={{ ...td, fontVariantNumeric: 'tabular-nums' }}>{s.script_order}</td>
                    <td style={{ ...td, fontWeight: 600 }}>{s.script_name}</td>
                    <td style={{ ...td, color: 'var(--text-secondary)' }}>{s.description}</td>
                    <td style={td}>{s.is_active ? 'Yes' : 'No'}</td>
                    <td style={td}>{s.continue_on_error ? 'Continue' : 'Stop chain'}</td>
                    <td style={{ ...td, whiteSpace: 'nowrap', textAlign: 'right' }}>
                      <button style={{ ...btn, marginRight: 6 }} onClick={() => openEdit(s)}>Edit</button>
                      <button style={btn} onClick={() => deleteScript(s)}>Delete</button>
                    </td>
                  </tr>
                ))}
                {scripts.length === 0 && (
                  <tr><td style={td} colSpan={6}>No provisioning scripts defined.</td></tr>
                )}
              </tbody>
            </table>
          </div>

          {showForm && (
            <div style={{ ...card, marginTop: 16, padding: 20 }}>
              <h3 style={{ margin: '0 0 12px', fontSize: 16, color: 'var(--text)' }}>
                {form.scriptId ? 'Edit script' : 'New script'}
              </h3>
              <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr 1fr', gap: 12, marginBottom: 12 }}>
                <div>
                  <label style={{ fontSize: 12, color: 'var(--text-secondary)' }}>Name</label>
                  <input style={inputStyle} value={form.scriptName}
                    onChange={(e) => setForm({ ...form, scriptName: e.target.value })} />
                </div>
                <div>
                  <label style={{ fontSize: 12, color: 'var(--text-secondary)' }}>Order</label>
                  <input style={inputStyle} type="number" value={form.scriptOrder}
                    onChange={(e) => setForm({ ...form, scriptOrder: e.target.value })} />
                </div>
                <div>
                  <label style={{ fontSize: 12, color: 'var(--text-secondary)' }}>Active</label>
                  <select style={inputStyle} value={form.isActive ? '1' : '0'}
                    onChange={(e) => setForm({ ...form, isActive: e.target.value === '1' })}>
                    <option value="1">Yes</option>
                    <option value="0">No</option>
                  </select>
                </div>
                <div>
                  <label style={{ fontSize: 12, color: 'var(--text-secondary)' }}>On failure</label>
                  <select style={inputStyle} value={form.continueOnError ? '1' : '0'}
                    onChange={(e) => setForm({ ...form, continueOnError: e.target.value === '1' })}>
                    <option value="0">Stop the chain</option>
                    <option value="1">Continue to next</option>
                  </select>
                </div>
              </div>
              <div style={{ marginBottom: 12 }}>
                <label style={{ fontSize: 12, color: 'var(--text-secondary)' }}>Description</label>
                <input style={inputStyle} value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })} />
              </div>
              <div style={{ marginBottom: 8 }}>
                <label style={{ fontSize: 12, color: 'var(--text-secondary)' }}>SQL</label>
                <textarea style={{ ...inputStyle, minHeight: 160, fontFamily: 'monospace', fontSize: 13 }}
                  value={form.scriptSql}
                  onChange={(e) => setForm({ ...form, scriptSql: e.target.value })} />
              </div>
              <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: '0 0 14px' }}>
                {PLACEHOLDER_HINT}
              </p>
              <div style={{ display: 'flex', gap: 8 }}>
                <button style={btnPrimary} onClick={saveScript}>Save</button>
                <button style={btn} onClick={() => setShowForm(false)}>Cancel</button>
              </div>
            </div>
          )}
        </>
      )}

      {tab === 'logs' && !loading && (
        <>
          <div style={{ marginBottom: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
            <select value={logTenantFilter}
              onChange={(e) => { setLogTenantFilter(e.target.value); loadLogs(e.target.value); }}
              style={{ ...inputStyle, width: 260 }}>
              <option value="">All tenants</option>
              {tenants.map((t) => (
                <option key={t.tenantId} value={t.tenantId}>
                  {t.bankName} ({t.bankShortCode})
                </option>
              ))}
            </select>
            <button style={btn} onClick={() => loadLogs(logTenantFilter)}>Refresh</button>
          </div>
          <div style={card}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th style={th}>When</th>
                  <th style={th}>Tenant</th>
                  <th style={th}>Script</th>
                  <th style={th}>Status</th>
                  <th style={th}>Duration</th>
                  <th style={th}>By</th>
                  <th style={th}>Error</th>
                </tr>
              </thead>
              <tbody>
                {logs.map((l) => (
                  <tr key={l.log_id}>
                    <td style={{ ...td, whiteSpace: 'nowrap' }}>
                      {l.executed_at ? new Date(l.executed_at).toLocaleString() : ''}
                    </td>
                    <td style={td}>{l.bank_name || l.tenant_id}</td>
                    <td style={{ ...td, fontWeight: 600 }}>{l.script_name}</td>
                    <td style={{ ...td, color: statusColor(l.status), fontWeight: 600 }}>{l.status}</td>
                    <td style={{ ...td, fontVariantNumeric: 'tabular-nums' }}>
                      {l.duration_ms != null ? `${l.duration_ms} ms` : ''}
                    </td>
                    <td style={td}>{l.executed_by}</td>
                    <td style={{ ...td, color: 'var(--text-secondary)', maxWidth: 340, wordBreak: 'break-word' }}>
                      {l.error_message}
                    </td>
                  </tr>
                ))}
                {logs.length === 0 && (
                  <tr><td style={td} colSpan={7}>No provisioning runs logged yet.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}

      {tab === 'registry' && !loading && (
        <div style={card}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={th}>Migration</th>
                <th style={th}>Description</th>
                <th style={th}>Dev</th>
                <th style={th}>Prod</th>
                <th style={th}>Registered</th>
              </tr>
            </thead>
            <tbody>
              {registry.map((r) => (
                <tr key={r.registry_id}>
                  <td style={{ ...td, fontFamily: 'monospace', fontSize: 12 }}>{r.migration_name}</td>
                  <td style={{ ...td, color: 'var(--text-secondary)' }}>{r.description}</td>
                  <td style={{ ...td, color: r.applied_on_dev ? 'var(--status-success, #16a34a)' : 'var(--text-secondary)' }}>
                    {r.applied_on_dev ? 'Applied' : '—'}
                  </td>
                  <td style={td}>
                    <button
                      style={{
                        ...btn, padding: '3px 10px', fontSize: 12,
                        color: r.applied_on_prod ? 'var(--status-success, #16a34a)' : 'var(--text-secondary)',
                      }}
                      onClick={() => toggleProdApplied(r)}
                      title="Toggle prod-applied flag"
                    >
                      {r.applied_on_prod ? 'Applied' : 'Pending'}
                    </button>
                  </td>
                  <td style={{ ...td, whiteSpace: 'nowrap', color: 'var(--text-secondary)' }}>
                    {r.created_at ? new Date(r.created_at).toLocaleDateString() : ''}
                  </td>
                </tr>
              ))}
              {registry.length === 0 && (
                <tr><td style={td} colSpan={5}>Registry is empty.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
