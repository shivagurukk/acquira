import React, { useState, useEffect, useCallback } from 'react';
import {
  Cable, Database, FileCode, Clock, ScrollText, Plus, Edit2, Trash2, X, Check,
  CheckCircle, XCircle, AlertTriangle, Loader2, RefreshCw, Play, Pause, Zap,
  Activity, TrendingUp, Server, Eye, TestTube, RotateCcw, ChevronDown, ChevronUp
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';

// ─── Injected hover/focus stylesheet (theme-token driven) ────
// Inline style objects can't express :hover / :focus-visible; the app's register
// leans on subtle hover + focus rings, so those live here rather than in JS.
const IH_STYLE_ID = 'acquira-integration-hub';
if (typeof document !== 'undefined' && !document.getElementById(IH_STYLE_ID)) {
  const s = document.createElement('style');
  s.id = IH_STYLE_ID;
  s.textContent = `
    .ih-btn { transition: filter .13s ease, box-shadow .13s ease, background .13s ease; }
    .ih-btn:hover { filter: brightness(0.97); }
    .ih-btn:focus-visible { outline: 2px solid var(--brand, #2563eb); outline-offset: 1px; }
    .ih-card { transition: box-shadow .18s ease, border-color .18s ease; }
    .ih-row:hover { border-color: color-mix(in srgb, var(--brand, #2563eb) 30%, var(--border, #e5e7eb)) !important; box-shadow: var(--shadow-sm); }
    .ih-tab { transition: background .18s ease, color .18s ease, box-shadow .18s ease; }
    .ih-input { transition: border-color .13s ease, box-shadow .13s ease; }
    .ih-input:focus { border-color: var(--brand, #2563eb); box-shadow: 0 0 0 3px var(--brand-ring, rgba(37,99,235,0.18)); }
    .ih-close { background: none; border: none; cursor: pointer; color: var(--text-secondary, #6b7280); border-radius: var(--radius-sm, 6px); display: flex; padding: 4px; }
    .ih-close:hover { background: var(--bg-hover, #f3f4f6); color: var(--text, #111827); }
    .spin { animation: ih-spin 1s linear infinite; }
    @keyframes ih-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
  `;
  document.head.appendChild(s);
}

// ─── Shared Styles (token-driven, theme-adaptive) ────────────
const card = { background: 'var(--bg-card, #fff)', borderRadius: 'var(--radius-lg, 12px)', padding: 24, boxShadow: 'var(--shadow-card, 0 1px 3px rgba(0,0,0,.08))', border: '1px solid var(--border, #e5e7eb)' };
const tint = (color, pct = 12) => `color-mix(in srgb, ${color} ${pct}%, transparent)`;
const badge = (color) => ({
  display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 10px', borderRadius: 999,
  fontSize: 12, fontWeight: 600, background: tint(color, 14), color
});
const btn = (bg = 'var(--brand, #2563eb)', fg = '#fff') => ({
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 16px', borderRadius: 'var(--radius-sm, 8px)',
  background: bg, color: fg, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600
});
const btnGhost = { ...btn('var(--bg-hover, #f3f4f6)', 'var(--text, #374151)'), border: '1px solid var(--border, #e5e7eb)' };
const input = { width: '100%', padding: '8px 12px', borderRadius: 'var(--radius-sm, 8px)', border: '1px solid var(--border, #d1d5db)', fontSize: 14, outline: 'none', boxSizing: 'border-box', background: 'var(--bg-card, #fff)', color: 'var(--text, #111827)' };
const select = { ...input };
const label = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--text-secondary, #374151)', marginBottom: 4 };
const modalOverlay = {
  position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', display: 'flex', alignItems: 'center',
  justifyContent: 'center', zIndex: 1000, backdropFilter: 'blur(2px)'
};
const modalBox = { background: 'var(--bg-card, #fff)', borderRadius: 'var(--radius-lg, 16px)', padding: 28, width: '95%', maxWidth: 640, maxHeight: '90vh', overflow: 'auto', boxShadow: 'var(--shadow-lg, 0 20px 60px rgba(0,0,0,.2))', border: '1px solid var(--border, #e5e7eb)', color: 'var(--text, #111827)' };
const errorBox = { padding: '8px 14px', background: 'var(--danger-bg, #fef2f2)', color: 'var(--danger, #dc2626)', borderRadius: 'var(--radius-sm, 8px)', marginBottom: 14, fontSize: 13, border: '1px solid color-mix(in srgb, var(--danger, #dc2626) 20%, transparent)' };
const muted = { color: 'var(--text-secondary, #6b7280)' };
const faint = { color: 'var(--text-muted, #9ca3af)' };
const codeChip = { background: 'var(--bg-subtle, #f3f4f6)', padding: '1px 6px', borderRadius: 4, fontFamily: 'monospace' };

const STATUS_COLORS = { SUCCESS: '#16a34a', FAILED: '#dc2626', RUNNING: '#2563eb', RETRYING: '#f59e0b' };
const DB_TYPE_COLORS = { ORACLE: '#f97316', POSTGRES: '#3b82f6', MSSQL: '#8b5cf6' };
const REPORT_TYPE_COLORS = { MERCHANT: '#8b5cf6', TRANSACTION: '#059669' };
const PASSWORD_SENTINEL = '__UNCHANGED__';
// Spring's CronExpression (used by DynamicSchedulerService) is 6-field and does NOT
// accept Quartz's '?' token — day-of-week/day-of-month use '*' or names. Quartz-style
// crons here would be saved but silently never fire.
const FREQ_OPTIONS = [
  { label: 'Every Hour', value: 'HOURLY', cron: '0 0 * * * *' },
  { label: 'Daily at 2 AM', value: 'DAILY', cron: '0 0 2 * * *' },
  { label: 'Daily at 6 AM', value: 'DAILY_6AM', cron: '0 0 6 * * *' },
  { label: 'Daily at 10 PM', value: 'DAILY_10PM', cron: '0 0 22 * * *' },
  { label: 'Weekly (Sun 3 AM)', value: 'WEEKLY', cron: '0 0 3 * * SUN' },
  { label: 'Monthly (1st, 2 AM)', value: 'MONTHLY', cron: '0 0 2 1 * *' },
  { label: 'Custom', value: 'CUSTOM', cron: '' },
];

// ─── Overview Tab ────────────────────────────────────────────
const OverviewTab = () => {
  const { tenantVersion } = useAuth();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try { const res = await api.get('/admin/integration/overview'); setData(res.data); }
    catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, [load, tenantVersion]);

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" color="var(--brand, #2563eb)" /></div>;
  if (!data) return <div style={{ padding: 40, textAlign: 'center', ...muted }}>No data available. Configure connections and reports to get started.</div>;

  const stats = [
    { icon: Database, label: 'Connections', value: data.totalConnections, color: '#3b82f6' },
    { icon: FileCode, label: 'Reports', value: data.totalReports, color: '#8b5cf6' },
    { icon: Clock, label: 'Active Schedules', value: data.activeSchedules, color: '#f59e0b' },
    { icon: Activity, label: 'Runs (24h)', value: data.runsToday, color: '#06b6d4' },
    { icon: CheckCircle, label: 'Success', value: data.successToday, color: '#16a34a' },
    { icon: XCircle, label: 'Failed', value: data.failedToday, color: '#dc2626' },
  ];
  const rateColor = data.successRate >= 80 ? '#16a34a' : data.successRate >= 50 ? '#f59e0b' : '#dc2626';

  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 16, marginBottom: 24 }}>
        {stats.map(s => (
          <div key={s.label} className="ih-card" style={{ ...card, display: 'flex', alignItems: 'center', gap: 14 }}>
            <div style={{ width: 44, height: 44, borderRadius: 'var(--radius-md, 10px)', background: tint(s.color, 14), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <s.icon size={20} color={s.color} />
            </div>
            <div>
              <div style={{ fontSize: 24, fontWeight: 700, color: 'var(--text, #111)' }}>{s.value}</div>
              <div style={{ fontSize: 12, ...muted }}>{s.label}</div>
            </div>
          </div>
        ))}
      </div>

      {/* Success Rate Bar */}
      <div style={{ ...card, marginBottom: 24 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
          <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text, #111)' }}>Success Rate (24h)</span>
          <span style={{ fontSize: 14, fontWeight: 700, color: rateColor }}>{data.successRate}%</span>
        </div>
        <div style={{ height: 8, borderRadius: 4, background: 'var(--bg-subtle, #f3f4f6)' }}>
          <div style={{ height: '100%', borderRadius: 4, width: `${data.successRate}%`, background: rateColor, transition: 'width .5s' }} />
        </div>
      </div>

      {/* Recent Runs */}
      <div style={card}>
        <h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 12, color: 'var(--text, #111)' }}>Recent Runs</h3>
        {(!data.recentRuns || data.recentRuns.length === 0) ? (
          <div style={{ padding: 20, textAlign: 'center', ...faint }}>No runs yet</div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid var(--border-light, #f3f4f6)' }}>
                {['Report', 'Type', 'Trigger', 'Status', 'Rows', 'Duration', 'Time'].map(h =>
                  <th key={h} style={{ textAlign: 'left', padding: '8px 10px', ...muted, fontWeight: 600 }}>{h}</th>
                )}
              </tr>
            </thead>
            <tbody>
              {data.recentRuns.map(r => (
                <tr key={r.id} style={{ borderBottom: '1px solid var(--border-light, #f3f4f6)' }}>
                  <td style={{ padding: '10px', color: 'var(--text, #111)' }}>{r.report?.name || '—'}</td>
                  <td><span style={badge(REPORT_TYPE_COLORS[r.report?.reportType] || '#6b7280')}>{r.report?.reportType || '—'}</span></td>
                  <td style={{ padding: '10px', ...muted }}>{r.triggerType}</td>
                  <td><span style={badge(STATUS_COLORS[r.status] || '#6b7280')}>{r.status}</span></td>
                  <td style={{ padding: '10px', color: 'var(--text, #111)' }}>{r.rowsProcessed ?? 0}/{r.rowsFetched ?? 0}</td>
                  <td style={{ padding: '10px', ...muted }}>{r.durationMs ? (r.durationMs / 1000).toFixed(1) + 's' : '—'}</td>
                  <td style={{ padding: '10px', ...muted, fontSize: 12 }}>{r.startTime ? new Date(r.startTime).toLocaleString() : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

// ─── Connections Tab ─────────────────────────────────────────
const ConnectionsTab = () => {
  const { tenantVersion } = useAuth();
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);
  const [testing, setTesting] = useState(null);
  const [form, setForm] = useState({ name: '', dbType: 'POSTGRES', host: '', port: 5432, dbName: '', username: '', encryptedPassword: '', timeoutSeconds: 30, maxRetries: 3, isActive: true });
  const [editId, setEditId] = useState(null);
  const [error, setError] = useState(null);

  const load = async () => { try { const r = await api.get('/admin/integration/connections'); setConnections(r.data); } catch (e) { console.error(e); } finally { setLoading(false); } };
  useEffect(() => { load(); }, [tenantVersion]);

  const openAdd = () => { setForm({ name: '', dbType: 'POSTGRES', host: '', port: 5432, dbName: '', username: '', encryptedPassword: '', timeoutSeconds: 30, maxRetries: 3, isActive: true }); setEditId(null); setError(null); setModal(true); };
  // On edit, blank the password field (server sends the __UNCHANGED__ sentinel, never the
  // real value). Empty means "keep the stored password"; a typed value replaces it.
  const openEdit = (c) => { setForm({ ...c, encryptedPassword: '' }); setEditId(c.id); setError(null); setModal(true); };

  const save = async () => {
    setError(null);
    // Omit the password entirely when left blank on edit → backend keeps existing ciphertext.
    const payload = { ...form };
    if (editId && (!payload.encryptedPassword || !payload.encryptedPassword.trim())) {
      payload.encryptedPassword = PASSWORD_SENTINEL;
    }
    try {
      if (editId) { await api.put(`/admin/integration/connections/${editId}`, payload); }
      else { await api.post('/admin/integration/connections', payload); }
      setModal(false); showToast(editId ? 'Connection updated' : 'Connection created', 'success'); load();
    } catch (e) { setError(e.response?.data?.error || 'Save failed'); }
  };

  const remove = async (id) => {
    if (!window.confirm('Deactivate this connection?')) return;
    try { await api.delete(`/admin/integration/connections/${id}`); showToast('Connection deactivated', 'success'); load(); }
    catch (e) { showToast('Failed to deactivate connection', 'error'); }
  };

  const test = async (id) => {
    setTesting(id);
    try {
      const r = await api.post(`/admin/integration/connections/${id}/test`);
      showToast(r.data.message || 'Test complete', r.data.success ? 'success' : 'error');
      load();
    }
    catch (e) { showToast('Test failed', 'error'); }
    finally { setTesting(null); }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" color="var(--brand, #2563eb)" /></div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ fontSize: 14, ...muted }}>{connections.length} connection{connections.length !== 1 ? 's' : ''}</div>
        <button className="ih-btn" style={btn()} onClick={openAdd}><Plus size={16} /> Add Connection</button>
      </div>

      {connections.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, ...faint }}>
          <Database size={40} style={{ margin: '0 auto 12px', opacity: .4 }} />
          <div>No connections configured. Add your first external database connection.</div>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {connections.map(c => (
            <div key={c.id} className="ih-card ih-row" style={{ ...card, display: 'flex', alignItems: 'center', justifyContent: 'space-between', opacity: c.isActive === false ? 0.5 : 1 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <div style={{ width: 42, height: 42, borderRadius: 'var(--radius-md, 10px)', background: tint(DB_TYPE_COLORS[c.dbType] || '#6b7280', 14), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <Server size={20} color={DB_TYPE_COLORS[c.dbType] || '#6b7280'} />
                </div>
                <div>
                  <div style={{ fontWeight: 600, fontSize: 14, color: 'var(--text, #111)' }}>{c.name}</div>
                  <div style={{ fontSize: 12, ...muted }}>{c.dbType} — {c.host}:{c.port}/{c.dbName}</div>
                </div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {c.lastTestStatus && <span style={badge(c.lastTestStatus === 'SUCCESS' ? '#16a34a' : '#dc2626')}>{c.lastTestStatus === 'SUCCESS' ? <CheckCircle size={12} /> : <XCircle size={12} />} {c.lastTestStatus}</span>}
                <button className="ih-btn" style={btnGhost} onClick={() => test(c.id)} disabled={testing === c.id}>
                  {testing === c.id ? <Loader2 size={14} className="spin" /> : <TestTube size={14} />} Test
                </button>
                <button className="ih-btn" style={btnGhost} onClick={() => openEdit(c)}><Edit2 size={14} /></button>
                <button className="ih-btn" style={btn(tint('#dc2626', 12), '#dc2626')} onClick={() => remove(c.id)}><Trash2 size={14} /></button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Modal */}
      {modal && (
        <div style={modalOverlay} onClick={() => setModal(false)}>
          <div style={modalBox} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
              <h2 style={{ fontSize: 18, fontWeight: 700, color: 'var(--text, #111)' }}>{editId ? 'Edit' : 'New'} Connection</h2>
              <button className="ih-close" onClick={() => setModal(false)}><X size={20} /></button>
            </div>
            {error && <div style={errorBox}>{error}</div>}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              <div style={{ gridColumn: 'span 2' }}><label style={label}>Connection Name</label><input className="ih-input" style={input} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Core Banking Oracle" /></div>
              <div><label style={label}>Database Type</label>
                <select className="ih-input" style={select} value={form.dbType} onChange={e => setForm({ ...form, dbType: e.target.value, port: e.target.value === 'ORACLE' ? 1521 : e.target.value === 'MSSQL' ? 1433 : 5432 })}>
                  <option value="ORACLE">Oracle</option><option value="POSTGRES">PostgreSQL</option><option value="MSSQL">SQL Server</option>
                </select>
              </div>
              <div><label style={label}>Host</label><input className="ih-input" style={input} value={form.host} onChange={e => setForm({ ...form, host: e.target.value })} placeholder="192.168.1.100" /></div>
              <div><label style={label}>Port</label><input className="ih-input" style={input} type="number" value={form.port} onChange={e => setForm({ ...form, port: parseInt(e.target.value) })} /></div>
              <div><label style={label}>Database / Service Name</label><input className="ih-input" style={input} value={form.dbName} onChange={e => setForm({ ...form, dbName: e.target.value })} /></div>
              <div><label style={label}>Username</label><input className="ih-input" style={input} value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} /></div>
              <div><label style={label}>Password {editId && <span style={{ fontWeight: 400, ...faint }}>— leave blank to keep current</span>}</label><input className="ih-input" style={input} type="password" value={form.encryptedPassword} onChange={e => setForm({ ...form, encryptedPassword: e.target.value })} placeholder={editId ? '••••••••  (unchanged)' : ''} /></div>
              <div><label style={label}>Timeout (sec)</label><input className="ih-input" style={input} type="number" value={form.timeoutSeconds} onChange={e => setForm({ ...form, timeoutSeconds: parseInt(e.target.value) })} /></div>
              <div><label style={label}>Max Retries</label><input className="ih-input" style={input} type="number" value={form.maxRetries} onChange={e => setForm({ ...form, maxRetries: parseInt(e.target.value) })} /></div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button className="ih-btn" style={btnGhost} onClick={() => setModal(false)}>Cancel</button>
              <button className="ih-btn" style={btn()} onClick={save}><Check size={16} /> {editId ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Reports Tab ─────────────────────────────────────────────
const ReportsTab = () => {
  const { tenantVersion } = useAuth();
  const [reports, setReports] = useState([]);
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);
  const [subTab, setSubTab] = useState('ALL');
  const [form, setForm] = useState({ name: '', reportType: 'TRANSACTION', connectionId: '', sqlText: '', columnMapping: '', description: '', isActive: true });
  const [editId, setEditId] = useState(null);
  const [error, setError] = useState(null);
  const [validating, setValidating] = useState(null);
  const [preview, setPreview] = useState(null);
  // In-modal dry-run (validate BEFORE saving) — separate state so it doesn't clash
  // with the card-level Validate flow.
  const [modalTesting, setModalTesting] = useState(false);
  const [modalPreview, setModalPreview] = useState(null);

  const load = async () => {
    try {
      const [r, c] = await Promise.all([api.get('/admin/integration/reports'), api.get('/admin/integration/connections')]);
      setReports(r.data); setConnections(c.data);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [tenantVersion]);

  const filtered = subTab === 'ALL' ? reports : reports.filter(r => r.reportType === subTab);

  const openAdd = () => { setForm({ name: '', reportType: 'TRANSACTION', connectionId: connections[0]?.id || '', sqlText: '', columnMapping: '', description: '', isActive: true }); setEditId(null); setError(null); setPreview(null); setModalPreview(null); setModal(true); };
  const openEdit = (r) => { setForm({ ...r, connectionId: r.connection?.id || '' }); setEditId(r.id); setError(null); setPreview(null); setModalPreview(null); setModal(true); };

  // Client-side JSON validation for the column mapping before it reaches the server
  // (the server parse fails silently and the mapping just gets ignored otherwise).
  const validateMappingJson = () => {
    const raw = (form.columnMapping || '').trim();
    if (!raw) return true;
    try { const o = JSON.parse(raw); return o && typeof o === 'object' && !Array.isArray(o); }
    catch { return false; }
  };

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

  const save = async () => {
    setError(null);
    if (!validateMappingJson()) { setError('Column Mapping is not valid JSON (expected {"SQL_COL":"staging_field"}).'); return; }
    try {
      const payload = { ...form };
      if (editId) { await api.put(`/admin/integration/reports/${editId}`, payload); }
      else { await api.post('/admin/integration/reports', payload); }
      setModal(false); showToast(editId ? 'Report updated' : 'Report created', 'success'); load();
    } catch (e) { setError(e.response?.data?.error || 'Save failed'); }
  };

  const validate = async (id) => {
    setValidating(id);
    try {
      const r = await api.post(`/admin/integration/reports/${id}/validate`);
      setPreview(r.data);
    } catch (e) { setPreview({ success: false, error: e.message }); }
    finally { setValidating(null); }
  };

  const remove = async (id) => {
    if (!window.confirm('Deactivate this report?')) return;
    try { await api.delete(`/admin/integration/reports/${id}`); showToast('Report deactivated', 'success'); load(); }
    catch (e) { showToast('Failed to deactivate report', 'error'); }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" color="var(--brand, #2563eb)" /></div>;

  return (
    <div>
      {/* Sub-tabs */}
      <div style={{ display: 'flex', gap: 6, marginBottom: 16 }}>
        {['ALL', 'MERCHANT', 'TRANSACTION'].map(t => (
          <button key={t} className="ih-tab" onClick={() => setSubTab(t)} style={{
            padding: '6px 16px', borderRadius: 'var(--radius-sm, 8px)', border: '1px solid ' + (subTab === t ? 'transparent' : 'var(--border, #e5e7eb)'), cursor: 'pointer', fontSize: 13, fontWeight: 600,
            background: subTab === t ? 'var(--brand, #2563eb)' : 'var(--bg-card, #fff)', color: subTab === t ? '#fff' : 'var(--text-secondary, #374151)'
          }}>{t === 'ALL' ? 'All Reports' : t}</button>
        ))}
        <div style={{ flex: 1 }} />
        <button className="ih-btn" style={btn()} onClick={openAdd}><Plus size={16} /> Add Report</button>
      </div>

      {filtered.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, ...faint }}>
          <FileCode size={40} style={{ margin: '0 auto 12px', opacity: .4 }} />
          <div>No report configs. Add your first SQL report.</div>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {filtered.map(r => (
            <div key={r.id} className="ih-card ih-row" style={{ ...card, opacity: r.isActive === false ? 0.5 : 1 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div style={{ minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
                    <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--text, #111)' }}>{r.name}</span>
                    <span style={badge(REPORT_TYPE_COLORS[r.reportType] || '#6b7280')}>{r.reportType}</span>
                  </div>
                  <div style={{ fontSize: 12, ...muted }}>Connection: {r.connection?.name || '—'} • {r.description || 'No description'}</div>
                  <div style={{ marginTop: 8, padding: '6px 10px', background: 'var(--bg-subtle, #f8fafc)', borderRadius: 6, fontFamily: 'monospace', fontSize: 12, color: 'var(--text-secondary, #475569)', maxHeight: 60, overflow: 'hidden', whiteSpace: 'pre-wrap' }}>
                    {r.sqlText?.substring(0, 200)}{r.sqlText?.length > 200 ? '...' : ''}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 6, flexShrink: 0, marginLeft: 16 }}>
                  <button className="ih-btn" style={btn(tint('#16a34a', 12), '#16a34a')} onClick={() => validate(r.id)} disabled={validating === r.id}>
                    {validating === r.id ? <Loader2 size={14} className="spin" /> : <TestTube size={14} />} Validate
                  </button>
                  <button className="ih-btn" style={btnGhost} onClick={() => openEdit(r)}><Edit2 size={14} /></button>
                  <button className="ih-btn" style={btn(tint('#dc2626', 12), '#dc2626')} onClick={() => remove(r.id)}><Trash2 size={14} /></button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Report Modal */}
      {modal && (
        <div style={modalOverlay} onClick={() => setModal(false)}>
          <div style={{ ...modalBox, maxWidth: 720 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
              <h2 style={{ fontSize: 18, fontWeight: 700, color: 'var(--text, #111)' }}>{editId ? 'Edit' : 'New'} Report Config</h2>
              <button className="ih-close" onClick={() => setModal(false)}><X size={20} /></button>
            </div>
            {error && <div style={errorBox}>{error}</div>}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              <div><label style={label}>Report Name</label><input className="ih-input" style={input} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Daily Transaction Pull" /></div>
              <div><label style={label}>Type</label>
                <select className="ih-input" style={select} value={form.reportType} onChange={e => setForm({ ...form, reportType: e.target.value })}>
                  <option value="MERCHANT">Merchant</option><option value="TRANSACTION">Transaction</option>
                </select>
              </div>
              <div style={{ gridColumn: 'span 2' }}><label style={label}>Connection</label>
                <select className="ih-input" style={select} value={form.connectionId} onChange={e => { setForm({ ...form, connectionId: e.target.value }); setModalPreview(null); }}>
                  <option value="">— Select Connection —</option>
                  {connections.filter(c => c.isActive !== false).map(c => <option key={c.id} value={c.id}>{c.name} ({c.dbType})</option>)}
                </select>
              </div>
              <div style={{ gridColumn: 'span 2' }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
                  <label style={{ ...label, marginBottom: 0 }}>SQL Query <span style={{ fontWeight: 400, ...faint }}>— use :year :month :dateFrom :dateTo as parameters</span></label>
                  <button className="ih-btn" style={{ ...btn(tint('#16a34a', 12), '#16a34a'), padding: '4px 10px', fontSize: 12 }} onClick={testQuery} disabled={modalTesting}>
                    {modalTesting ? <Loader2 size={12} className="spin" /> : <TestTube size={12} />} Test query
                  </button>
                </div>
                <textarea className="ih-input" style={{ ...input, minHeight: 140, fontFamily: 'monospace', fontSize: 13 }} value={form.sqlText} onChange={e => { setForm({ ...form, sqlText: e.target.value }); }}
                  placeholder={form.reportType === 'MERCHANT'
                    ? 'SELECT merchant_id AS mid, merchant_name, status AS merchant_status, ...\nFROM merchants WHERE created_year = :year'
                    : 'SELECT mid, merchant_name, payment_date, txn_currency_amount, card_scheme, ...\nFROM transactions WHERE payment_date BETWEEN :dateFrom AND :dateTo'} />
                {modalPreview && (
                  <div style={{ marginTop: 8, borderRadius: 8, border: `1px solid ${modalPreview.success ? tint('#16a34a', 40) : tint('#dc2626', 40)}`, overflow: 'hidden' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 10px', fontSize: 12, fontWeight: 600, background: modalPreview.success ? tint('#16a34a', 10) : 'var(--danger-bg, #fef2f2)', color: modalPreview.success ? '#16a34a' : 'var(--danger, #dc2626)' }}>
                      {modalPreview.success ? <CheckCircle size={13} /> : <XCircle size={13} />}
                      {modalPreview.success ? `Query OK — ${modalPreview.rowCount} sample row(s)` : 'Query failed'}
                    </div>
                    {modalPreview.success ? (
                      <div style={{ overflow: 'auto', maxHeight: 180 }}>
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11.5 }}>
                          <thead><tr>{(modalPreview.columns || []).map(c => <th key={c} style={{ padding: '5px 8px', borderBottom: '1px solid var(--border, #e5e7eb)', textAlign: 'left', fontWeight: 600, color: 'var(--text, #111)', background: 'var(--bg-subtle, #f8fafc)', position: 'sticky', top: 0 }}>{c}</th>)}</tr></thead>
                          <tbody>
                            {(modalPreview.preview || []).map((row, i) => (
                              <tr key={i} style={{ borderBottom: '1px solid var(--border-light, #f3f4f6)' }}>
                                {(modalPreview.columns || []).map(c => <td key={c} style={{ padding: '5px 8px', color: 'var(--text-secondary, #374151)' }}>{row[c] != null ? String(row[c]) : '—'}</td>)}
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    ) : (
                      <div style={{ padding: '8px 10px', fontSize: 12, fontFamily: 'monospace', color: 'var(--danger, #dc2626)', whiteSpace: 'pre-wrap', maxHeight: 120, overflow: 'auto' }}>{modalPreview.error}</div>
                    )}
                  </div>
                )}
              </div>
              <div style={{ gridColumn: 'span 2' }}>
                <label style={label}>Column Mapping (JSON) <span style={{ fontWeight: 400, ...faint }}>— {"{ \"sql_column\": \"staging_field\" }"}</span></label>
                <textarea className="ih-input" style={{ ...input, minHeight: 60, fontFamily: 'monospace', fontSize: 12 }} value={form.columnMapping} onChange={e => setForm({ ...form, columnMapping: e.target.value })}
                  placeholder='{"MERCHANT_ID":"mid", "MERCHANT_NAME":"merchant_name", "PAYMENT_DT":"payment_date"}' />
                {form.columnMapping?.trim() && !validateMappingJson() && (
                  <div style={{ marginTop: 6, fontSize: 11.5, color: 'var(--danger, #dc2626)', display: 'flex', alignItems: 'center', gap: 4 }}><AlertTriangle size={12} /> Not valid JSON</div>
                )}
              </div>
              <div style={{ gridColumn: 'span 2' }}><label style={label}>Description</label><input className="ih-input" style={input} value={form.description || ''} onChange={e => setForm({ ...form, description: e.target.value })} /></div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button className="ih-btn" style={btnGhost} onClick={() => setModal(false)}>Cancel</button>
              <button className="ih-btn" style={btn()} onClick={save}><Check size={16} /> {editId ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}

      {/* Preview Modal (card-level Validate) */}
      {preview && (
        <div style={modalOverlay} onClick={() => setPreview(null)}>
          <div style={{ ...modalBox, maxWidth: 800 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
              <h2 style={{ fontSize: 16, fontWeight: 700, color: preview.success ? '#16a34a' : 'var(--danger, #dc2626)', display: 'flex', alignItems: 'center', gap: 6 }}>
                {preview.success ? <CheckCircle size={18} /> : <XCircle size={18} />} SQL Validation {preview.success ? 'Passed' : 'Failed'}
              </h2>
              <button className="ih-close" onClick={() => setPreview(null)}><X size={20} /></button>
            </div>
            {preview.success ? (
              <div>
                <div style={{ marginBottom: 8, fontSize: 13, ...muted }}>Returned {preview.rowCount} row(s). Columns: {(preview.columns || []).join(', ')}</div>
                <div style={{ overflow: 'auto', maxHeight: 300 }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                    <thead>
                      <tr>{(preview.columns || []).map(c => <th key={c} style={{ padding: '6px 8px', borderBottom: '2px solid var(--border, #e5e7eb)', textAlign: 'left', fontWeight: 600, color: 'var(--text, #111)' }}>{c}</th>)}</tr>
                    </thead>
                    <tbody>
                      {(preview.preview || []).map((row, i) => (
                        <tr key={i} style={{ borderBottom: '1px solid var(--border-light, #f3f4f6)' }}>
                          {(preview.columns || []).map(c => <td key={c} style={{ padding: '6px 8px', color: 'var(--text-secondary, #374151)' }}>{row[c] != null ? String(row[c]) : '—'}</td>)}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <div style={{ padding: 16, background: 'var(--danger-bg, #fef2f2)', borderRadius: 8, color: 'var(--danger, #dc2626)', fontSize: 13, fontFamily: 'monospace', whiteSpace: 'pre-wrap' }}>{preview.error}</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Schedules Tab ───────────────────────────────────────────
const SchedulesTab = () => {
  const { tenantVersion } = useAuth();
  const [schedules, setSchedules] = useState([]);
  const [reports, setReports] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);
  const [runNowModal, setRunNowModal] = useState(null);
  const [runNowDates, setRunNowDates] = useState({ dateFrom: '', dateTo: '' });
  const [form, setForm] = useState({ reportId: '', frequencyLabel: 'DAILY', cronExpression: '0 0 2 * * *', timezone: 'UTC', isEnabled: true });
  const [editId, setEditId] = useState(null);

  const load = async () => {
    try {
      const [s, r] = await Promise.all([api.get('/admin/integration/schedules'), api.get('/admin/integration/reports')]);
      setSchedules(s.data); setReports(r.data);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [tenantVersion]);

  const openAdd = () => { setForm({ reportId: reports[0]?.id || '', frequencyLabel: 'DAILY', cronExpression: '0 0 2 * * *', timezone: 'UTC', isEnabled: true }); setEditId(null); setModal(true); };
  const openEdit = (s) => { setForm({ ...s, reportId: s.report?.id || '' }); setEditId(s.id); setModal(true); };

  const save = async () => {
    try {
      if (editId) { await api.put(`/admin/integration/schedules/${editId}`, form); }
      else { await api.post('/admin/integration/schedules', form); }
      setModal(false); showToast(editId ? 'Schedule updated' : 'Schedule created', 'success'); load();
    } catch (e) { showToast('Save failed: ' + (e.response?.data?.error || e.message), 'error'); }
  };

  const toggle = async (id) => {
    try { const r = await api.post(`/admin/integration/schedules/${id}/toggle`); showToast(r.data?.enabled ? 'Schedule enabled' : 'Schedule paused', 'success'); load(); }
    catch (e) { showToast('Toggle failed', 'error'); }
  };

  const runNow = async () => {
    try {
      // Fix: send whenever EITHER bound is set (was dropping a from-only or to-only range).
      const hasDates = runNowDates.dateFrom || runNowDates.dateTo;
      await api.post(`/admin/integration/schedules/${runNowModal.id}/run-now`, hasDates ? runNowDates : {});
      showToast('Pull started — track progress in Run History', 'success');
      setRunNowModal(null);
      load();
    } catch (e) { showToast('Failed: ' + (e.response?.data?.error || e.message), 'error'); }
  };

  const remove = async (id) => {
    if (!window.confirm('Delete this schedule?')) return;
    try { await api.delete(`/admin/integration/schedules/${id}`); showToast('Schedule deleted', 'success'); load(); }
    catch (e) { showToast('Delete failed', 'error'); }
  };

  const setFreq = (val) => {
    const opt = FREQ_OPTIONS.find(f => f.value === val);
    setForm({ ...form, frequencyLabel: val, cronExpression: opt?.cron || form.cronExpression });
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" color="var(--brand, #2563eb)" /></div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ fontSize: 14, ...muted }}>{schedules.length} schedule{schedules.length !== 1 ? 's' : ''}</div>
        <button className="ih-btn" style={btn()} onClick={openAdd}><Plus size={16} /> Add Schedule</button>
      </div>

      {schedules.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, ...faint }}>
          <Clock size={40} style={{ margin: '0 auto 12px', opacity: .4 }} />
          <div>No schedules. Create a report config first, then schedule it.</div>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {schedules.map(s => (
            <div key={s.id} className="ih-card ih-row" style={{ ...card, display: 'flex', alignItems: 'center', justifyContent: 'space-between', opacity: s.isEnabled ? 1 : 0.5 }}>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
                  <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--text, #111)' }}>{s.report?.name || 'Unknown Report'}</span>
                  <span style={badge(REPORT_TYPE_COLORS[s.report?.reportType] || '#6b7280')}>{s.report?.reportType}</span>
                  <span style={badge(s.isEnabled ? '#16a34a' : '#9ca3af')}>{s.isEnabled ? 'Active' : 'Paused'}</span>
                </div>
                <div style={{ fontSize: 12, ...muted }}>
                  Cron: <code style={codeChip}>{s.cronExpression}</code> •
                  Freq: {s.frequencyLabel || 'Custom'} •
                  TZ: {s.timezone || 'UTC'}
                  {s.lastRunAt && <> • Last run: {new Date(s.lastRunAt).toLocaleString()}</>}
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6 }}>
                <button className="ih-btn" style={btn(tint('#2563eb', 12), 'var(--brand, #2563eb)')} onClick={() => { setRunNowDates({ dateFrom: '', dateTo: '' }); setRunNowModal(s); }}><Zap size={14} /> Run Now</button>
                <button className="ih-btn" style={btnGhost} onClick={() => toggle(s.id)}>{s.isEnabled ? <Pause size={14} /> : <Play size={14} />}</button>
                <button className="ih-btn" style={btnGhost} onClick={() => openEdit(s)}><Edit2 size={14} /></button>
                <button className="ih-btn" style={btn(tint('#dc2626', 12), '#dc2626')} onClick={() => remove(s.id)}><Trash2 size={14} /></button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Schedule Modal */}
      {modal && (
        <div style={modalOverlay} onClick={() => setModal(false)}>
          <div style={modalBox} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
              <h2 style={{ fontSize: 18, fontWeight: 700, color: 'var(--text, #111)' }}>{editId ? 'Edit' : 'New'} Schedule</h2>
              <button className="ih-close" onClick={() => setModal(false)}><X size={20} /></button>
            </div>
            <div style={{ display: 'grid', gap: 14 }}>
              <div><label style={label}>Report</label>
                <select className="ih-input" style={select} value={form.reportId} onChange={e => setForm({ ...form, reportId: e.target.value })}>
                  <option value="">— Select Report —</option>
                  {reports.filter(r => r.isActive !== false).map(r => <option key={r.id} value={r.id}>{r.name} ({r.reportType})</option>)}
                </select>
              </div>
              <div><label style={label}>Frequency</label>
                <select className="ih-input" style={select} value={form.frequencyLabel} onChange={e => setFreq(e.target.value)}>
                  {FREQ_OPTIONS.map(f => <option key={f.value} value={f.value}>{f.label}</option>)}
                </select>
              </div>
              <div><label style={label}>Cron Expression <span style={{ fontWeight: 400, ...faint }}>— 6-field Spring cron (sec min hour dom mon dow); '?' is not supported</span></label><input className="ih-input" style={{ ...input, fontFamily: 'monospace' }} value={form.cronExpression} onChange={e => setForm({ ...form, cronExpression: e.target.value })} placeholder="0 0 2 * * *" /></div>
              <div><label style={label}>Timezone</label>
                <select className="ih-input" style={select} value={form.timezone} onChange={e => setForm({ ...form, timezone: e.target.value })}>
                  {['UTC', 'Asia/Bahrain', 'Asia/Dubai', 'Asia/Riyadh', 'Asia/Kolkata', 'Europe/London', 'America/New_York'].map(tz => <option key={tz} value={tz}>{tz}</option>)}
                </select>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button className="ih-btn" style={btnGhost} onClick={() => setModal(false)}>Cancel</button>
              <button className="ih-btn" style={btn()} onClick={save}><Check size={16} /> {editId ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}

      {/* Run Now Modal */}
      {runNowModal && (
        <div style={modalOverlay} onClick={() => setRunNowModal(null)}>
          <div style={{ ...modalBox, maxWidth: 440 }} onClick={e => e.stopPropagation()}>
            <h2 style={{ fontSize: 18, fontWeight: 700, marginBottom: 6, color: 'var(--text, #111)' }}>Run Now — {runNowModal.report?.name}</h2>
            <p style={{ fontSize: 13, ...muted, marginBottom: 16 }}>Trigger an immediate pull. Optionally specify a date range, or leave blank for current month.</p>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              <div><label style={label}>Date From (optional)</label><input className="ih-input" style={input} type="date" value={runNowDates.dateFrom} onChange={e => setRunNowDates({ ...runNowDates, dateFrom: e.target.value })} /></div>
              <div><label style={label}>Date To (optional)</label><input className="ih-input" style={input} type="date" value={runNowDates.dateTo} onChange={e => setRunNowDates({ ...runNowDates, dateTo: e.target.value })} /></div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button className="ih-btn" style={btnGhost} onClick={() => setRunNowModal(null)}>Cancel</button>
              <button className="ih-btn" style={btn()} onClick={runNow}><Zap size={16} /> Execute Now</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Run History Tab ─────────────────────────────────────────
const RunHistoryTab = () => {
  const { tenantVersion } = useAuth();
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');
  const [expanded, setExpanded] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page, size: 20 });
      if (statusFilter) params.append('status', statusFilter);
      const r = await api.get('/admin/integration/runs?' + params.toString());
      setRuns(r.data.content || []); setTotal(r.data.totalElements || 0);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, [page, statusFilter]);

  useEffect(() => { load(); const t = setInterval(load, 15000); return () => clearInterval(t); }, [load, tenantVersion]);

  const retry = async (id) => {
    try { await api.post(`/admin/integration/runs/${id}/retry`); showToast('Retry started', 'success'); load(); }
    catch (e) { showToast('Retry failed', 'error'); }
  };

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        {['', 'SUCCESS', 'FAILED', 'RUNNING', 'RETRYING'].map(s => (
          <button key={s} className="ih-tab" onClick={() => { setStatusFilter(s); setPage(0); }} style={{
            padding: '6px 14px', borderRadius: 'var(--radius-sm, 8px)', border: '1px solid ' + (statusFilter === s ? 'transparent' : 'var(--border, #e5e7eb)'), cursor: 'pointer', fontSize: 13, fontWeight: 600,
            background: statusFilter === s ? 'var(--brand, #2563eb)' : 'var(--bg-card, #fff)', color: statusFilter === s ? '#fff' : 'var(--text-secondary, #374151)'
          }}>{s || 'All'}</button>
        ))}
        <div style={{ flex: 1 }} />
        <button className="ih-btn" style={btnGhost} onClick={load}><RefreshCw size={14} /> Refresh</button>
      </div>

      {loading && runs.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" color="var(--brand, #2563eb)" /></div>
      ) : runs.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, ...faint }}>No runs found</div>
      ) : (
        <div style={card}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid var(--border-light, #f3f4f6)' }}>
                {['', 'Report', 'Type', 'Trigger', 'Status', 'Attempt', 'Rows', 'Duration', 'Started', 'Actions'].map(h =>
                  <th key={h} style={{ textAlign: 'left', padding: '8px 8px', ...muted, fontWeight: 600 }}>{h}</th>
                )}
              </tr>
            </thead>
            <tbody>
              {runs.map(r => (
                <React.Fragment key={r.id}>
                  <tr style={{ borderBottom: '1px solid var(--border-light, #f3f4f6)', cursor: 'pointer' }} onClick={() => setExpanded(expanded === r.id ? null : r.id)}>
                    <td style={{ padding: '8px', width: 28, ...muted }}>{expanded === r.id ? <ChevronUp size={14} /> : <ChevronDown size={14} />}</td>
                    <td style={{ padding: '8px', fontWeight: 500, color: 'var(--text, #111)' }}>{r.report?.name || '—'}</td>
                    <td><span style={badge(REPORT_TYPE_COLORS[r.report?.reportType] || '#6b7280')}>{r.report?.reportType || '—'}</span></td>
                    <td style={{ padding: '8px', ...muted }}>{r.triggerType}</td>
                    <td><span style={badge(STATUS_COLORS[r.status] || '#6b7280')}>
                      {r.status === 'RUNNING' && <Loader2 size={12} className="spin" />} {r.status}
                    </span></td>
                    <td style={{ padding: '8px', color: 'var(--text, #111)' }}>{r.attemptNumber}/{r.maxRetries}</td>
                    <td style={{ padding: '8px', color: 'var(--text, #111)' }}>{r.rowsProcessed ?? 0}/{r.rowsFetched ?? 0}</td>
                    <td style={{ padding: '8px', ...muted }}>{r.durationMs ? (r.durationMs / 1000).toFixed(1) + 's' : '—'}</td>
                    <td style={{ padding: '8px', fontSize: 12, ...muted }}>{r.startTime ? new Date(r.startTime).toLocaleString() : '—'}</td>
                    <td style={{ padding: '8px' }}>
                      {r.status === 'FAILED' && (
                        <button className="ih-btn" style={btn(tint('#d97706', 14), '#d97706')} onClick={(e) => { e.stopPropagation(); retry(r.id); }}>
                          <RotateCcw size={12} /> Retry
                        </button>
                      )}
                    </td>
                  </tr>
                  {expanded === r.id && (
                    <tr><td colSpan={10} style={{ padding: '12px 20px', background: 'var(--bg-subtle, #f9fafb)' }}>
                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, fontSize: 13, color: 'var(--text-secondary, #374151)' }}>
                        <div><strong style={{ color: 'var(--text, #111)' }}>Date Range:</strong> {r.dateRangeFrom || 'Default'} → {r.dateRangeTo || 'Default'}</div>
                        <div><strong style={{ color: 'var(--text, #111)' }}>End Time:</strong> {r.endTime ? new Date(r.endTime).toLocaleString() : 'In progress'}</div>
                        <div><strong style={{ color: 'var(--text, #111)' }}>Rows Failed:</strong> {r.rowsFailed ?? 0}</div>
                        <div><strong style={{ color: 'var(--text, #111)' }}>Schedule:</strong> {r.schedule?.report?.name || (r.schedule?.id ? `#${r.schedule.id}` : 'Manual run')}</div>
                      </div>
                      {r.errorMessage && (
                        <div style={{ marginTop: 10, padding: 12, background: 'var(--danger-bg, #fef2f2)', borderRadius: 8, color: 'var(--danger, #dc2626)', fontSize: 12, fontFamily: 'monospace', whiteSpace: 'pre-wrap', maxHeight: 120, overflow: 'auto' }}>
                          {r.errorMessage}
                        </div>
                      )}
                    </td></tr>
                  )}
                </React.Fragment>
              ))}
            </tbody>
          </table>

          {/* Pagination */}
          {total > 20 && (
            <div style={{ display: 'flex', justifyContent: 'center', gap: 8, padding: '16px 0' }}>
              <button className="ih-btn" style={btnGhost} disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button>
              <span style={{ padding: '8px 12px', fontSize: 13, ...muted }}>Page {page + 1} of {Math.ceil(total / 20)}</span>
              <button className="ih-btn" style={btnGhost} disabled={(page + 1) * 20 >= total} onClick={() => setPage(p => p + 1)}>Next</button>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

// ═══════════════════════════════════════════════════════════════
//  MAIN COMPONENT — Single Page with Tabs
// ═══════════════════════════════════════════════════════════════
const TABS = [
  { key: 'overview', label: 'Overview', icon: Cable },
  { key: 'connections', label: 'Connections', icon: Database },
  { key: 'reports', label: 'Report Configs', icon: FileCode },
  { key: 'schedules', label: 'Schedules', icon: Clock },
  { key: 'runs', label: 'Run History', icon: ScrollText },
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
    <div style={{ padding: '0 0 40px' }}>
      {/* Header */}
      <div style={{ marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, color: 'var(--text, #111)', marginBottom: 4 }}>
          <Cable size={22} style={{ verticalAlign: 'middle', marginRight: 8, color: 'var(--brand, #2563eb)' }} />
          Data Integration Hub
        </h1>
        <p style={{ fontSize: 13, ...muted, margin: 0 }}>
          Configure external database connections, SQL reports, and automated schedules.
          File upload remains available under Operations → Upload Files.
        </p>
      </div>

      {/* Tab Bar */}
      <div style={{ display: 'flex', gap: 2, marginBottom: 24, background: 'var(--bg-subtle, #f3f4f6)', borderRadius: 'var(--radius-lg, 12px)', padding: 4 }}>
        {TABS.map(tab => {
          const Icon = tab.icon;
          const active = activeTab === tab.key;
          return (
            <button key={tab.key} className="ih-tab" onClick={() => setActiveTab(tab.key)} style={{
              flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
              padding: '10px 16px', borderRadius: 'var(--radius-md, 10px)', border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
              background: active ? 'var(--bg-card, #fff)' : 'transparent', color: active ? 'var(--brand, #2563eb)' : 'var(--text-secondary, #6b7280)',
              boxShadow: active ? 'var(--shadow-sm, 0 1px 3px rgba(0,0,0,.1))' : 'none'
            }}>
              <Icon size={16} /> {tab.label}
            </button>
          );
        })}
      </div>

      {/* Tab Content */}
      {renderTab()}
    </div>
  );
};

export default IntegrationHub;
