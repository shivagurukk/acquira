import React, { useState, useEffect, useCallback } from 'react';
import {
  Cable, Database, FileCode, Clock, ScrollText, Plus, Edit2, Trash2, X, Check,
  CheckCircle, XCircle, AlertTriangle, Loader2, RefreshCw, Play, Pause, Zap,
  Activity, TrendingUp, Server, Eye, TestTube, RotateCcw, ChevronDown, ChevronUp
} from 'lucide-react';
import api from '../../api/axios';

// ─── Shared Styles ───────────────────────────────────────────
const card = { background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 1px 3px rgba(0,0,0,.08)', border: '1px solid #e5e7eb' };
const badge = (color) => ({
  display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 10px', borderRadius: 12,
  fontSize: 12, fontWeight: 600, background: color + '18', color
});
const btn = (bg = '#2563eb', fg = '#fff') => ({
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 16px', borderRadius: 8,
  background: bg, color: fg, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600
});
const input = { width: '100%', padding: '8px 12px', borderRadius: 8, border: '1px solid #d1d5db', fontSize: 14, outline: 'none', boxSizing: 'border-box' };
const select = { ...input, background: '#fff' };
const label = { display: 'block', fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 4 };
const modalOverlay = {
  position: 'fixed', inset: 0, background: 'rgba(0,0,0,.4)', display: 'flex', alignItems: 'center',
  justifyContent: 'center', zIndex: 1000
};
const modalBox = { background: '#fff', borderRadius: 16, padding: 28, width: '95%', maxWidth: 640, maxHeight: '90vh', overflow: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.2)' };

const STATUS_COLORS = { SUCCESS: '#16a34a', FAILED: '#dc2626', RUNNING: '#2563eb', RETRYING: '#f59e0b' };
const DB_TYPE_COLORS = { ORACLE: '#f97316', POSTGRES: '#3b82f6', MSSQL: '#8b5cf6' };
const REPORT_TYPE_COLORS = { MERCHANT: '#8b5cf6', TRANSACTION: '#059669' };
const FREQ_OPTIONS = [
  { label: 'Every Hour', value: 'HOURLY', cron: '0 0 * * * ?' },
  { label: 'Daily at 2 AM', value: 'DAILY', cron: '0 0 2 * * ?' },
  { label: 'Daily at 6 AM', value: 'DAILY_6AM', cron: '0 0 6 * * ?' },
  { label: 'Daily at 10 PM', value: 'DAILY_10PM', cron: '0 0 22 * * ?' },
  { label: 'Weekly (Sun 3 AM)', value: 'WEEKLY', cron: '0 0 3 ? * SUN' },
  { label: 'Monthly (1st, 2 AM)', value: 'MONTHLY', cron: '0 0 2 1 * ?' },
  { label: 'Custom', value: 'CUSTOM', cron: '' },
];

// ─── Overview Tab ────────────────────────────────────────────
const OverviewTab = () => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try { const res = await api.get('/admin/integration/overview'); setData(res.data); }
    catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, [load]);

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" /></div>;
  if (!data) return <div style={{ padding: 40, textAlign: 'center', color: '#6b7280' }}>No data available. Configure connections and reports to get started.</div>;

  const stats = [
    { icon: Database, label: 'Connections', value: data.totalConnections, color: '#3b82f6' },
    { icon: FileCode, label: 'Reports', value: data.totalReports, color: '#8b5cf6' },
    { icon: Clock, label: 'Active Schedules', value: data.activeSchedules, color: '#f59e0b' },
    { icon: Activity, label: 'Runs (24h)', value: data.runsToday, color: '#06b6d4' },
    { icon: CheckCircle, label: 'Success', value: data.successToday, color: '#16a34a' },
    { icon: XCircle, label: 'Failed', value: data.failedToday, color: '#dc2626' },
  ];

  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 16, marginBottom: 24 }}>
        {stats.map(s => (
          <div key={s.label} style={{ ...card, display: 'flex', alignItems: 'center', gap: 14 }}>
            <div style={{ width: 44, height: 44, borderRadius: 10, background: s.color + '14', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <s.icon size={20} color={s.color} />
            </div>
            <div>
              <div style={{ fontSize: 24, fontWeight: 700, color: '#111' }}>{s.value}</div>
              <div style={{ fontSize: 12, color: '#6b7280' }}>{s.label}</div>
            </div>
          </div>
        ))}
      </div>

      {/* Success Rate Bar */}
      <div style={{ ...card, marginBottom: 24 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
          <span style={{ fontSize: 14, fontWeight: 600 }}>Success Rate (24h)</span>
          <span style={{ fontSize: 14, fontWeight: 700, color: data.successRate >= 80 ? '#16a34a' : data.successRate >= 50 ? '#f59e0b' : '#dc2626' }}>
            {data.successRate}%
          </span>
        </div>
        <div style={{ height: 8, borderRadius: 4, background: '#f3f4f6' }}>
          <div style={{ height: '100%', borderRadius: 4, width: `${data.successRate}%`, background: data.successRate >= 80 ? '#16a34a' : data.successRate >= 50 ? '#f59e0b' : '#dc2626', transition: 'width .5s' }} />
        </div>
      </div>

      {/* Recent Runs */}
      <div style={card}>
        <h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 12 }}>Recent Runs</h3>
        {(!data.recentRuns || data.recentRuns.length === 0) ? (
          <div style={{ padding: 20, textAlign: 'center', color: '#9ca3af' }}>No runs yet</div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid #f3f4f6' }}>
                {['Report', 'Type', 'Trigger', 'Status', 'Rows', 'Duration', 'Time'].map(h =>
                  <th key={h} style={{ textAlign: 'left', padding: '8px 10px', color: '#6b7280', fontWeight: 600 }}>{h}</th>
                )}
              </tr>
            </thead>
            <tbody>
              {data.recentRuns.map(r => (
                <tr key={r.id} style={{ borderBottom: '1px solid #f3f4f6' }}>
                  <td style={{ padding: '10px' }}>{r.report?.name || '—'}</td>
                  <td><span style={badge(REPORT_TYPE_COLORS[r.report?.reportType] || '#6b7280')}>{r.report?.reportType || '—'}</span></td>
                  <td style={{ padding: '10px', color: '#6b7280' }}>{r.triggerType}</td>
                  <td><span style={badge(STATUS_COLORS[r.status] || '#6b7280')}>{r.status}</span></td>
                  <td style={{ padding: '10px' }}>{r.rowsProcessed ?? 0}/{r.rowsFetched ?? 0}</td>
                  <td style={{ padding: '10px', color: '#6b7280' }}>{r.durationMs ? (r.durationMs / 1000).toFixed(1) + 's' : '—'}</td>
                  <td style={{ padding: '10px', color: '#6b7280', fontSize: 12 }}>{r.startTime ? new Date(r.startTime).toLocaleString() : '—'}</td>
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
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);
  const [testing, setTesting] = useState(null);
  const [form, setForm] = useState({ name: '', dbType: 'POSTGRES', host: '', port: 5432, dbName: '', username: '', encryptedPassword: '', timeoutSeconds: 30, maxRetries: 3, isActive: true });
  const [editId, setEditId] = useState(null);
  const [error, setError] = useState(null);

  const load = async () => { try { const r = await api.get('/admin/integration/connections'); setConnections(r.data); } catch (e) { console.error(e); } finally { setLoading(false); } };
  useEffect(() => { load(); }, []);

  const openAdd = () => { setForm({ name: '', dbType: 'POSTGRES', host: '', port: 5432, dbName: '', username: '', encryptedPassword: '', timeoutSeconds: 30, maxRetries: 3, isActive: true }); setEditId(null); setError(null); setModal(true); };
  const openEdit = (c) => { setForm({ ...c }); setEditId(c.id); setError(null); setModal(true); };

  const save = async () => {
    setError(null);
    try {
      if (editId) { await api.put(`/admin/integration/connections/${editId}`, form); }
      else { await api.post('/admin/integration/connections', form); }
      setModal(false); load();
    } catch (e) { setError(e.response?.data?.error || 'Save failed'); }
  };

  const remove = async (id) => { if (window.confirm('Deactivate this connection?')) { await api.delete(`/admin/integration/connections/${id}`); load(); } };

  const test = async (id) => {
    setTesting(id);
    try { const r = await api.post(`/admin/integration/connections/${id}/test`); alert(r.data.message); load(); }
    catch (e) { alert('Test failed'); }
    finally { setTesting(null); }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" /></div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ fontSize: 14, color: '#6b7280' }}>{connections.length} connection{connections.length !== 1 ? 's' : ''}</div>
        <button style={btn()} onClick={openAdd}><Plus size={16} /> Add Connection</button>
      </div>

      {connections.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, color: '#9ca3af' }}>
          <Database size={40} style={{ margin: '0 auto 12px', opacity: .4 }} />
          <div>No connections configured. Add your first external database connection.</div>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {connections.map(c => (
            <div key={c.id} style={{ ...card, display: 'flex', alignItems: 'center', justifyContent: 'space-between', opacity: c.isActive === false ? 0.5 : 1 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <div style={{ width: 42, height: 42, borderRadius: 10, background: (DB_TYPE_COLORS[c.dbType] || '#6b7280') + '14', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <Server size={20} color={DB_TYPE_COLORS[c.dbType] || '#6b7280'} />
                </div>
                <div>
                  <div style={{ fontWeight: 600, fontSize: 14 }}>{c.name}</div>
                  <div style={{ fontSize: 12, color: '#6b7280' }}>{c.dbType} — {c.host}:{c.port}/{c.dbName}</div>
                </div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {c.lastTestStatus && <span style={badge(c.lastTestStatus === 'SUCCESS' ? '#16a34a' : '#dc2626')}>{c.lastTestStatus === 'SUCCESS' ? <CheckCircle size={12} /> : <XCircle size={12} />} {c.lastTestStatus}</span>}
                <button style={btn('#f3f4f6', '#374151')} onClick={() => test(c.id)} disabled={testing === c.id}>
                  {testing === c.id ? <Loader2 size={14} className="spin" /> : <TestTube size={14} />} Test
                </button>
                <button style={btn('#f3f4f6', '#374151')} onClick={() => openEdit(c)}><Edit2 size={14} /></button>
                <button style={btn('#fee2e2', '#dc2626')} onClick={() => remove(c.id)}><Trash2 size={14} /></button>
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
              <h2 style={{ fontSize: 18, fontWeight: 700 }}>{editId ? 'Edit' : 'New'} Connection</h2>
              <button style={{ background: 'none', border: 'none', cursor: 'pointer' }} onClick={() => setModal(false)}><X size={20} /></button>
            </div>
            {error && <div style={{ padding: '8px 14px', background: '#fef2f2', color: '#dc2626', borderRadius: 8, marginBottom: 14, fontSize: 13 }}>{error}</div>}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              <div style={{ gridColumn: 'span 2' }}><label style={label}>Connection Name</label><input style={input} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Core Banking Oracle" /></div>
              <div><label style={label}>Database Type</label>
                <select style={select} value={form.dbType} onChange={e => setForm({ ...form, dbType: e.target.value, port: e.target.value === 'ORACLE' ? 1521 : e.target.value === 'MSSQL' ? 1433 : 5432 })}>
                  <option value="ORACLE">Oracle</option><option value="POSTGRES">PostgreSQL</option><option value="MSSQL">SQL Server</option>
                </select>
              </div>
              <div><label style={label}>Host</label><input style={input} value={form.host} onChange={e => setForm({ ...form, host: e.target.value })} placeholder="192.168.1.100" /></div>
              <div><label style={label}>Port</label><input style={input} type="number" value={form.port} onChange={e => setForm({ ...form, port: parseInt(e.target.value) })} /></div>
              <div><label style={label}>Database / Service Name</label><input style={input} value={form.dbName} onChange={e => setForm({ ...form, dbName: e.target.value })} /></div>
              <div><label style={label}>Username</label><input style={input} value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} /></div>
              <div><label style={label}>Password</label><input style={input} type="password" value={form.encryptedPassword} onChange={e => setForm({ ...form, encryptedPassword: e.target.value })} /></div>
              <div><label style={label}>Timeout (sec)</label><input style={input} type="number" value={form.timeoutSeconds} onChange={e => setForm({ ...form, timeoutSeconds: parseInt(e.target.value) })} /></div>
              <div><label style={label}>Max Retries</label><input style={input} type="number" value={form.maxRetries} onChange={e => setForm({ ...form, maxRetries: parseInt(e.target.value) })} /></div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button style={btn('#f3f4f6', '#374151')} onClick={() => setModal(false)}>Cancel</button>
              <button style={btn()} onClick={save}><Check size={16} /> {editId ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Reports Tab ─────────────────────────────────────────────
const ReportsTab = () => {
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

  const load = async () => {
    try {
      const [r, c] = await Promise.all([api.get('/admin/integration/reports'), api.get('/admin/integration/connections')]);
      setReports(r.data); setConnections(c.data);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, []);

  const filtered = subTab === 'ALL' ? reports : reports.filter(r => r.reportType === subTab);

  const openAdd = () => { setForm({ name: '', reportType: 'TRANSACTION', connectionId: connections[0]?.id || '', sqlText: '', columnMapping: '', description: '', isActive: true }); setEditId(null); setError(null); setPreview(null); setModal(true); };
  const openEdit = (r) => { setForm({ ...r, connectionId: r.connection?.id || '' }); setEditId(r.id); setError(null); setPreview(null); setModal(true); };

  const save = async () => {
    setError(null);
    try {
      const payload = { ...form };
      if (editId) { await api.put(`/admin/integration/reports/${editId}`, payload); }
      else { await api.post('/admin/integration/reports', payload); }
      setModal(false); load();
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

  const remove = async (id) => { if (window.confirm('Deactivate this report?')) { await api.delete(`/admin/integration/reports/${id}`); load(); } };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" /></div>;

  return (
    <div>
      {/* Sub-tabs */}
      <div style={{ display: 'flex', gap: 6, marginBottom: 16 }}>
        {['ALL', 'MERCHANT', 'TRANSACTION'].map(t => (
          <button key={t} onClick={() => setSubTab(t)} style={{
            padding: '6px 16px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
            background: subTab === t ? '#2563eb' : '#f3f4f6', color: subTab === t ? '#fff' : '#374151'
          }}>{t === 'ALL' ? 'All Reports' : t}</button>
        ))}
        <div style={{ flex: 1 }} />
        <button style={btn()} onClick={openAdd}><Plus size={16} /> Add Report</button>
      </div>

      {filtered.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, color: '#9ca3af' }}>
          <FileCode size={40} style={{ margin: '0 auto 12px', opacity: .4 }} />
          <div>No report configs. Add your first SQL report.</div>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {filtered.map(r => (
            <div key={r.id} style={{ ...card, opacity: r.isActive === false ? 0.5 : 1 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
                    <span style={{ fontWeight: 600, fontSize: 14 }}>{r.name}</span>
                    <span style={badge(REPORT_TYPE_COLORS[r.reportType] || '#6b7280')}>{r.reportType}</span>
                  </div>
                  <div style={{ fontSize: 12, color: '#6b7280' }}>Connection: {r.connection?.name || '—'} • {r.description || 'No description'}</div>
                  <div style={{ marginTop: 8, padding: '6px 10px', background: '#f8fafc', borderRadius: 6, fontFamily: 'monospace', fontSize: 12, color: '#475569', maxHeight: 60, overflow: 'hidden', whiteSpace: 'pre-wrap' }}>
                    {r.sqlText?.substring(0, 200)}{r.sqlText?.length > 200 ? '...' : ''}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 6, flexShrink: 0, marginLeft: 16 }}>
                  <button style={btn('#f0fdf4', '#16a34a')} onClick={() => validate(r.id)} disabled={validating === r.id}>
                    {validating === r.id ? <Loader2 size={14} className="spin" /> : <TestTube size={14} />} Validate
                  </button>
                  <button style={btn('#f3f4f6', '#374151')} onClick={() => openEdit(r)}><Edit2 size={14} /></button>
                  <button style={btn('#fee2e2', '#dc2626')} onClick={() => remove(r.id)}><Trash2 size={14} /></button>
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
              <h2 style={{ fontSize: 18, fontWeight: 700 }}>{editId ? 'Edit' : 'New'} Report Config</h2>
              <button style={{ background: 'none', border: 'none', cursor: 'pointer' }} onClick={() => setModal(false)}><X size={20} /></button>
            </div>
            {error && <div style={{ padding: '8px 14px', background: '#fef2f2', color: '#dc2626', borderRadius: 8, marginBottom: 14, fontSize: 13 }}>{error}</div>}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              <div><label style={label}>Report Name</label><input style={input} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Daily Transaction Pull" /></div>
              <div><label style={label}>Type</label>
                <select style={select} value={form.reportType} onChange={e => setForm({ ...form, reportType: e.target.value })}>
                  <option value="MERCHANT">Merchant</option><option value="TRANSACTION">Transaction</option>
                </select>
              </div>
              <div style={{ gridColumn: 'span 2' }}><label style={label}>Connection</label>
                <select style={select} value={form.connectionId} onChange={e => setForm({ ...form, connectionId: e.target.value })}>
                  <option value="">— Select Connection —</option>
                  {connections.filter(c => c.isActive !== false).map(c => <option key={c.id} value={c.id}>{c.name} ({c.dbType})</option>)}
                </select>
              </div>
              <div style={{ gridColumn: 'span 2' }}>
                <label style={label}>SQL Query <span style={{ fontWeight: 400, color: '#9ca3af' }}>— use :year :month :dateFrom :dateTo as parameters</span></label>
                <textarea style={{ ...input, minHeight: 140, fontFamily: 'monospace', fontSize: 13 }} value={form.sqlText} onChange={e => setForm({ ...form, sqlText: e.target.value })}
                  placeholder={form.reportType === 'MERCHANT'
                    ? 'SELECT merchant_id AS mid, merchant_name, status AS merchant_status, ...\nFROM merchants WHERE created_year = :year'
                    : 'SELECT mid, merchant_name, payment_date, txn_currency_amount, card_scheme, ...\nFROM transactions WHERE payment_date BETWEEN :dateFrom AND :dateTo'} />
              </div>
              <div style={{ gridColumn: 'span 2' }}>
                <label style={label}>Column Mapping (JSON) <span style={{ fontWeight: 400, color: '#9ca3af' }}>— {"{ \"sql_column\": \"staging_field\" }"}</span></label>
                <textarea style={{ ...input, minHeight: 60, fontFamily: 'monospace', fontSize: 12 }} value={form.columnMapping} onChange={e => setForm({ ...form, columnMapping: e.target.value })}
                  placeholder='{"MERCHANT_ID":"mid", "MERCHANT_NAME":"merchant_name", "PAYMENT_DT":"payment_date"}' />
              </div>
              <div style={{ gridColumn: 'span 2' }}><label style={label}>Description</label><input style={input} value={form.description || ''} onChange={e => setForm({ ...form, description: e.target.value })} /></div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button style={btn('#f3f4f6', '#374151')} onClick={() => setModal(false)}>Cancel</button>
              <button style={btn()} onClick={save}><Check size={16} /> {editId ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}

      {/* Preview Modal */}
      {preview && (
        <div style={modalOverlay} onClick={() => setPreview(null)}>
          <div style={{ ...modalBox, maxWidth: 800 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
              <h2 style={{ fontSize: 16, fontWeight: 700 }}>{preview.success ? '✓ SQL Validation Passed' : '✗ SQL Validation Failed'}</h2>
              <button style={{ background: 'none', border: 'none', cursor: 'pointer' }} onClick={() => setPreview(null)}><X size={20} /></button>
            </div>
            {preview.success ? (
              <div>
                <div style={{ marginBottom: 8, fontSize: 13, color: '#6b7280' }}>Returned {preview.rowCount} row(s). Columns: {(preview.columns || []).join(', ')}</div>
                <div style={{ overflow: 'auto', maxHeight: 300 }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                    <thead>
                      <tr>{(preview.columns || []).map(c => <th key={c} style={{ padding: '6px 8px', borderBottom: '2px solid #e5e7eb', textAlign: 'left', fontWeight: 600 }}>{c}</th>)}</tr>
                    </thead>
                    <tbody>
                      {(preview.preview || []).map((row, i) => (
                        <tr key={i} style={{ borderBottom: '1px solid #f3f4f6' }}>
                          {(preview.columns || []).map(c => <td key={c} style={{ padding: '6px 8px' }}>{row[c] != null ? String(row[c]) : '—'}</td>)}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <div style={{ padding: 16, background: '#fef2f2', borderRadius: 8, color: '#dc2626', fontSize: 13 }}>{preview.error}</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Schedules Tab ───────────────────────────────────────────
const SchedulesTab = () => {
  const [schedules, setSchedules] = useState([]);
  const [reports, setReports] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(false);
  const [runNowModal, setRunNowModal] = useState(null);
  const [runNowDates, setRunNowDates] = useState({ dateFrom: '', dateTo: '' });
  const [form, setForm] = useState({ reportId: '', frequencyLabel: 'DAILY', cronExpression: '0 0 2 * * ?', timezone: 'UTC', isEnabled: true });
  const [editId, setEditId] = useState(null);

  const load = async () => {
    try {
      const [s, r] = await Promise.all([api.get('/admin/integration/schedules'), api.get('/admin/integration/reports')]);
      setSchedules(s.data); setReports(r.data);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, []);

  const openAdd = () => { setForm({ reportId: reports[0]?.id || '', frequencyLabel: 'DAILY', cronExpression: '0 0 2 * * ?', timezone: 'UTC', isEnabled: true }); setEditId(null); setModal(true); };
  const openEdit = (s) => { setForm({ ...s, reportId: s.report?.id || '' }); setEditId(s.id); setModal(true); };

  const save = async () => {
    try {
      if (editId) { await api.put(`/admin/integration/schedules/${editId}`, form); }
      else { await api.post('/admin/integration/schedules', form); }
      setModal(false); load();
    } catch (e) { alert('Save failed: ' + (e.response?.data?.error || e.message)); }
  };

  const toggle = async (id) => { await api.post(`/admin/integration/schedules/${id}/toggle`); load(); };

  const runNow = async () => {
    try {
      await api.post(`/admin/integration/schedules/${runNowModal.id}/run-now`, runNowDates.dateFrom ? runNowDates : {});
      alert('Pull started!');
      setRunNowModal(null);
      load();
    } catch (e) { alert('Failed: ' + e.message); }
  };

  const remove = async (id) => { if (window.confirm('Delete this schedule?')) { await api.delete(`/admin/integration/schedules/${id}`); load(); } };

  const setFreq = (val) => {
    const opt = FREQ_OPTIONS.find(f => f.value === val);
    setForm({ ...form, frequencyLabel: val, cronExpression: opt?.cron || form.cronExpression });
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" /></div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ fontSize: 14, color: '#6b7280' }}>{schedules.length} schedule{schedules.length !== 1 ? 's' : ''}</div>
        <button style={btn()} onClick={openAdd}><Plus size={16} /> Add Schedule</button>
      </div>

      {schedules.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, color: '#9ca3af' }}>
          <Clock size={40} style={{ margin: '0 auto 12px', opacity: .4 }} />
          <div>No schedules. Create a report config first, then schedule it.</div>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {schedules.map(s => (
            <div key={s.id} style={{ ...card, display: 'flex', alignItems: 'center', justifyContent: 'space-between', opacity: s.isEnabled ? 1 : 0.5 }}>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
                  <span style={{ fontWeight: 600, fontSize: 14 }}>{s.report?.name || 'Unknown Report'}</span>
                  <span style={badge(REPORT_TYPE_COLORS[s.report?.reportType] || '#6b7280')}>{s.report?.reportType}</span>
                  <span style={badge(s.isEnabled ? '#16a34a' : '#9ca3af')}>{s.isEnabled ? 'Active' : 'Paused'}</span>
                </div>
                <div style={{ fontSize: 12, color: '#6b7280' }}>
                  Cron: <code style={{ background: '#f3f4f6', padding: '1px 6px', borderRadius: 4 }}>{s.cronExpression}</code> •
                  Freq: {s.frequencyLabel || 'Custom'} •
                  TZ: {s.timezone || 'UTC'}
                  {s.lastRunAt && <> • Last run: {new Date(s.lastRunAt).toLocaleString()}</>}
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6 }}>
                <button style={btn('#eff6ff', '#2563eb')} onClick={() => { setRunNowDates({ dateFrom: '', dateTo: '' }); setRunNowModal(s); }}><Zap size={14} /> Run Now</button>
                <button style={btn('#f3f4f6', '#374151')} onClick={() => toggle(s.id)}>{s.isEnabled ? <Pause size={14} /> : <Play size={14} />}</button>
                <button style={btn('#f3f4f6', '#374151')} onClick={() => openEdit(s)}><Edit2 size={14} /></button>
                <button style={btn('#fee2e2', '#dc2626')} onClick={() => remove(s.id)}><Trash2 size={14} /></button>
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
              <h2 style={{ fontSize: 18, fontWeight: 700 }}>{editId ? 'Edit' : 'New'} Schedule</h2>
              <button style={{ background: 'none', border: 'none', cursor: 'pointer' }} onClick={() => setModal(false)}><X size={20} /></button>
            </div>
            <div style={{ display: 'grid', gap: 14 }}>
              <div><label style={label}>Report</label>
                <select style={select} value={form.reportId} onChange={e => setForm({ ...form, reportId: e.target.value })}>
                  <option value="">— Select Report —</option>
                  {reports.filter(r => r.isActive !== false).map(r => <option key={r.id} value={r.id}>{r.name} ({r.reportType})</option>)}
                </select>
              </div>
              <div><label style={label}>Frequency</label>
                <select style={select} value={form.frequencyLabel} onChange={e => setFreq(e.target.value)}>
                  {FREQ_OPTIONS.map(f => <option key={f.value} value={f.value}>{f.label}</option>)}
                </select>
              </div>
              <div><label style={label}>Cron Expression</label><input style={{ ...input, fontFamily: 'monospace' }} value={form.cronExpression} onChange={e => setForm({ ...form, cronExpression: e.target.value })} placeholder="0 0 2 * * ?" /></div>
              <div><label style={label}>Timezone</label>
                <select style={select} value={form.timezone} onChange={e => setForm({ ...form, timezone: e.target.value })}>
                  {['UTC', 'Asia/Bahrain', 'Asia/Dubai', 'Asia/Riyadh', 'Asia/Kolkata', 'Europe/London', 'America/New_York'].map(tz => <option key={tz} value={tz}>{tz}</option>)}
                </select>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button style={btn('#f3f4f6', '#374151')} onClick={() => setModal(false)}>Cancel</button>
              <button style={btn()} onClick={save}><Check size={16} /> {editId ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}

      {/* Run Now Modal */}
      {runNowModal && (
        <div style={modalOverlay} onClick={() => setRunNowModal(null)}>
          <div style={{ ...modalBox, maxWidth: 440 }} onClick={e => e.stopPropagation()}>
            <h2 style={{ fontSize: 18, fontWeight: 700, marginBottom: 6 }}>Run Now — {runNowModal.report?.name}</h2>
            <p style={{ fontSize: 13, color: '#6b7280', marginBottom: 16 }}>Trigger an immediate pull. Optionally specify a date range, or leave blank for current month.</p>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              <div><label style={label}>Date From (optional)</label><input style={input} type="date" value={runNowDates.dateFrom} onChange={e => setRunNowDates({ ...runNowDates, dateFrom: e.target.value })} /></div>
              <div><label style={label}>Date To (optional)</label><input style={input} type="date" value={runNowDates.dateTo} onChange={e => setRunNowDates({ ...runNowDates, dateTo: e.target.value })} /></div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button style={btn('#f3f4f6', '#374151')} onClick={() => setRunNowModal(null)}>Cancel</button>
              <button style={btn('#2563eb')} onClick={runNow}><Zap size={16} /> Execute Now</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Run History Tab ─────────────────────────────────────────
const RunHistoryTab = () => {
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

  useEffect(() => { load(); const t = setInterval(load, 15000); return () => clearInterval(t); }, [load]);

  const retry = async (id) => {
    try { await api.post(`/admin/integration/runs/${id}/retry`); alert('Retry started'); load(); }
    catch (e) { alert('Retry failed'); }
  };

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        {['', 'SUCCESS', 'FAILED', 'RUNNING', 'RETRYING'].map(s => (
          <button key={s} onClick={() => { setStatusFilter(s); setPage(0); }} style={{
            padding: '6px 14px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
            background: statusFilter === s ? '#2563eb' : '#f3f4f6', color: statusFilter === s ? '#fff' : '#374151'
          }}>{s || 'All'}</button>
        ))}
        <div style={{ flex: 1 }} />
        <button style={btn('#f3f4f6', '#374151')} onClick={load}><RefreshCw size={14} /> Refresh</button>
      </div>

      {loading && runs.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" /></div>
      ) : runs.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, color: '#9ca3af' }}>No runs found</div>
      ) : (
        <div style={card}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid #f3f4f6' }}>
                {['', 'Report', 'Type', 'Trigger', 'Status', 'Attempt', 'Rows', 'Duration', 'Started', 'Actions'].map(h =>
                  <th key={h} style={{ textAlign: 'left', padding: '8px 8px', color: '#6b7280', fontWeight: 600 }}>{h}</th>
                )}
              </tr>
            </thead>
            <tbody>
              {runs.map(r => (
                <React.Fragment key={r.id}>
                  <tr style={{ borderBottom: '1px solid #f3f4f6', cursor: 'pointer' }} onClick={() => setExpanded(expanded === r.id ? null : r.id)}>
                    <td style={{ padding: '8px', width: 28 }}>{expanded === r.id ? <ChevronUp size={14} /> : <ChevronDown size={14} />}</td>
                    <td style={{ padding: '8px', fontWeight: 500 }}>{r.report?.name || '—'}</td>
                    <td><span style={badge(REPORT_TYPE_COLORS[r.report?.reportType] || '#6b7280')}>{r.report?.reportType || '—'}</span></td>
                    <td style={{ padding: '8px', color: '#6b7280' }}>{r.triggerType}</td>
                    <td><span style={badge(STATUS_COLORS[r.status] || '#6b7280')}>
                      {r.status === 'RUNNING' && <Loader2 size={12} className="spin" />} {r.status}
                    </span></td>
                    <td style={{ padding: '8px' }}>{r.attemptNumber}/{r.maxRetries}</td>
                    <td style={{ padding: '8px' }}>{r.rowsProcessed ?? 0}/{r.rowsFetched ?? 0}</td>
                    <td style={{ padding: '8px', color: '#6b7280' }}>{r.durationMs ? (r.durationMs / 1000).toFixed(1) + 's' : '—'}</td>
                    <td style={{ padding: '8px', fontSize: 12, color: '#6b7280' }}>{r.startTime ? new Date(r.startTime).toLocaleString() : '—'}</td>
                    <td style={{ padding: '8px' }}>
                      {r.status === 'FAILED' && (
                        <button style={btn('#fef3c7', '#d97706')} onClick={(e) => { e.stopPropagation(); retry(r.id); }}>
                          <RotateCcw size={12} /> Retry
                        </button>
                      )}
                    </td>
                  </tr>
                  {expanded === r.id && (
                    <tr><td colSpan={10} style={{ padding: '12px 20px', background: '#f9fafb' }}>
                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, fontSize: 13 }}>
                        <div><strong>Date Range:</strong> {r.dateRangeFrom || 'Default'} → {r.dateRangeTo || 'Default'}</div>
                        <div><strong>End Time:</strong> {r.endTime ? new Date(r.endTime).toLocaleString() : 'In progress'}</div>
                        <div><strong>Rows Failed:</strong> {r.rowsFailed ?? 0}</div>
                        <div><strong>Schedule:</strong> #{r.schedule?.id || '—'}</div>
                      </div>
                      {r.errorMessage && (
                        <div style={{ marginTop: 10, padding: 12, background: '#fef2f2', borderRadius: 8, color: '#dc2626', fontSize: 12, fontFamily: 'monospace', whiteSpace: 'pre-wrap', maxHeight: 120, overflow: 'auto' }}>
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
              <button style={btn('#f3f4f6', '#374151')} disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button>
              <span style={{ padding: '8px 12px', fontSize: 13, color: '#6b7280' }}>Page {page + 1} of {Math.ceil(total / 20)}</span>
              <button style={btn('#f3f4f6', '#374151')} disabled={(page + 1) * 20 >= total} onClick={() => setPage(p => p + 1)}>Next</button>
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
        <h1 style={{ fontSize: 22, fontWeight: 700, color: '#111', marginBottom: 4 }}>
          <Cable size={22} style={{ verticalAlign: 'middle', marginRight: 8, color: '#2563eb' }} />
          Data Integration Hub
        </h1>
        <p style={{ fontSize: 13, color: '#6b7280', margin: 0 }}>
          Configure external database connections, SQL reports, and automated schedules.
          File upload remains available under Operations → Upload Files.
        </p>
      </div>

      {/* Tab Bar */}
      <div style={{ display: 'flex', gap: 2, marginBottom: 24, background: '#f3f4f6', borderRadius: 12, padding: 4 }}>
        {TABS.map(tab => {
          const Icon = tab.icon;
          const active = activeTab === tab.key;
          return (
            <button key={tab.key} onClick={() => setActiveTab(tab.key)} style={{
              flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
              padding: '10px 16px', borderRadius: 10, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
              background: active ? '#fff' : 'transparent', color: active ? '#2563eb' : '#6b7280',
              boxShadow: active ? '0 1px 3px rgba(0,0,0,.1)' : 'none', transition: 'all .2s'
            }}>
              <Icon size={16} /> {tab.label}
            </button>
          );
        })}
      </div>

      {/* Tab Content */}
      {renderTab()}

      {/* Spin animation */}
      <style>{`.spin { animation: spin 1s linear infinite; } @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`}</style>
    </div>
  );
};

export default IntegrationHub;
