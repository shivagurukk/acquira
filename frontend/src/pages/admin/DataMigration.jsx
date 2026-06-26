import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  DatabaseZap, Play, Eye, Loader2, CheckCircle, XCircle, AlertTriangle,
  ArrowRightLeft, Clock, ChevronDown, ChevronUp, RefreshCw, Table2,
  Columns, Calendar, Hash, Zap, FileText, Pause, Info, Trash2
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext'; // #12: Dynamic tenantId

// ─── Shared Styles (same as IntegrationHub) ──────────────────
const card = { background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 1px 3px rgba(0,0,0,.08)', border: '1px solid #e5e7eb' };
const badge = (color) => ({
  display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 10px', borderRadius: 12,
  fontSize: 12, fontWeight: 600, background: color + '18', color
});
const btn = (bg = '#2563eb', fg = '#fff') => ({
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 16px', borderRadius: 8,
  background: bg, color: fg, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
  transition: 'opacity .15s', whiteSpace: 'nowrap'
});
const inputStyle = { width: '100%', padding: '8px 12px', borderRadius: 8, border: '1px solid #d1d5db', fontSize: 14, outline: 'none', boxSizing: 'border-box' };
const selectStyle = { ...inputStyle, background: '#fff' };
const labelStyle = { display: 'block', fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 4 };

const PHASE_COLORS = {
  IDLE: '#6b7280', INITIALIZING: '#2563eb', CREATING_PARTITIONS: '#8b5cf6',
  CALCULATING_METRICS: '#f59e0b', COMPLETED: '#16a34a', FAILED: '#dc2626'
};
const getPhaseColor = (phase) => {
  if (!phase) return '#6b7280';
  if (phase.startsWith('MIGRATING_')) return '#2563eb';
  if (phase.startsWith('SUMMARIZING_')) return '#8b5cf6';
  if (phase.startsWith('FAILED')) return '#dc2626';
  return PHASE_COLORS[phase] || '#6b7280';
};

// All mappable columns with descriptions
const COLUMN_DEFS = [
  { key: 'mid', label: 'Merchant ID (MID)', required: true, hint: 'Unique merchant identifier' },
  { key: 'payment_date', label: 'Payment Date', required: true, hint: 'Transaction settlement date (TIMESTAMP)' },
  { key: 'txn_currency_amount', label: 'Transaction Amount', required: true, hint: 'Transaction value in txn currency' },
  { key: 'merchant_name', label: 'Merchant Name', required: false, hint: 'Auto-creates merchants if mapped' },
  { key: 'card_number', label: 'Card Number', required: false, hint: 'Masked card PAN for loyalty tracking' },
  { key: 'card_scheme', label: 'Card Scheme', required: false, hint: 'VISA, MASTERCARD, AMEX, etc.' },
  { key: 'card_type', label: 'Card Type', required: false, hint: 'DEBIT, CREDIT, PREPAID' },
  { key: 'dcc', label: 'DCC Flag', required: false, hint: 'Boolean: true/false for DCC opt-in' },
  { key: 'destination', label: 'Destination', required: false, hint: 'DOMESTIC or INTERNATIONAL' },
  { key: 'txn_currency', label: 'Txn Currency', required: false, hint: 'ISO currency code (BHD, USD, etc.)' },
  { key: 'store_base_currency', label: 'Base Currency', required: false, hint: 'Store settlement currency' },
  { key: 'store_base_currency_amount', label: 'Base Currency Amount', required: false, hint: 'Amount in base currency' },
  { key: 'msf', label: 'MSF', required: false, hint: 'Merchant Service Fee' },
  { key: 'interchange_fee', label: 'Interchange Fee', required: false, hint: 'Interchange cost' },
  { key: 'transaction_type', label: 'Transaction Type', required: false, hint: 'SALE, REFUND, etc.' },
  { key: 'transaction_date', label: 'Transaction Date/Time', required: false, hint: 'Actual swipe datetime (for heatmap hours)' },
  { key: 'arn', label: 'ARN', required: false, hint: 'Acquirer Reference Number' },
  { key: 'rrn_number', label: 'RRN', required: false, hint: 'Retrieval Reference Number' },
  { key: 'auth_code', label: 'Auth Code', required: false, hint: 'Authorization code' },
];

// ═══════════════════════════════════════════════════════════════
//  STEP 1: SOURCE TABLE CONFIGURATION
// ═══════════════════════════════════════════════════════════════
const ConfigureStep = ({ config, setConfig, onDryRun, dryRunResult, dryRunLoading }) => {
  const [showOptional, setShowOptional] = useState(false);
  const requiredCols = COLUMN_DEFS.filter(c => c.required);
  const optionalCols = COLUMN_DEFS.filter(c => !c.required);

  const updateMapping = (key, value) => {
    setConfig(prev => ({
      ...prev,
      columnMapping: { ...prev.columnMapping, [key]: value || undefined }
    }));
  };

  // Remove undefined entries
  const cleanMapping = (m) => {
    const cleaned = {};
    Object.entries(m || {}).forEach(([k, v]) => { if (v && v.trim()) cleaned[k] = v.trim(); });
    return cleaned;
  };

  // Source columns from dry-run for autocomplete
  const sourceCols = (dryRunResult?.columns || []).map(c => c.column_name);

  return (
    <div style={{ display: 'grid', gap: 20 }}>
      {/* Source table */}
      <div style={card}>
        <h3 style={{ fontSize: 15, fontWeight: 700, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Table2 size={18} color="#2563eb" /> Source Table
        </h3>
        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', gap: 14 }}>
          <div>
            <label style={labelStyle}>Table Name <span style={{ color: '#dc2626' }}>*</span></label>
            <input style={inputStyle} value={config.sourceTable} onChange={e => setConfig({ ...config, sourceTable: e.target.value })}
              placeholder="legacy_transactions" />
            <div style={{ fontSize: 11, color: '#9ca3af', marginTop: 4 }}>Must be in the same PostgreSQL database</div>
          </div>
          <div>
            <label style={labelStyle}>Start Month <span style={{ color: '#dc2626' }}>*</span></label>
            <input style={inputStyle} type="month" value={config.startMonth} onChange={e => setConfig({ ...config, startMonth: e.target.value })} />
          </div>
          <div>
            <label style={labelStyle}>End Month <span style={{ color: '#dc2626' }}>*</span></label>
            <input style={inputStyle} type="month" value={config.endMonth} onChange={e => setConfig({ ...config, endMonth: e.target.value })} />
          </div>
        </div>
        <div style={{ marginTop: 14 }}>
          <button style={btn('#eff6ff', '#2563eb')} onClick={() => onDryRun(cleanMapping(config.columnMapping))} disabled={dryRunLoading || !config.sourceTable}>
            {dryRunLoading ? <Loader2 size={14} className="spin" /> : <Eye size={14} />}
            Validate & Preview
          </button>
        </div>
      </div>

      {/* Dry-run result */}
      {dryRunResult && (
        <div style={{ ...card, border: dryRunResult.error ? '1px solid #fca5a5' : '1px solid #86efac' }}>
          {dryRunResult.error ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, color: '#dc2626' }}>
              <XCircle size={20} /> <span style={{ fontWeight: 600 }}>{dryRunResult.error}</span>
            </div>
          ) : (
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
                <CheckCircle size={20} color="#16a34a" />
                <span style={{ fontWeight: 600, color: '#16a34a' }}>Table found — {(dryRunResult.totalRows || 0).toLocaleString()} rows</span>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, marginBottom: 16 }}>
                <div style={{ padding: '10px 14px', background: '#f0fdf4', borderRadius: 8 }}>
                  <div style={{ fontSize: 11, color: '#6b7280', textTransform: 'uppercase', fontWeight: 600 }}>Total Rows</div>
                  <div style={{ fontSize: 20, fontWeight: 700 }}>{(dryRunResult.totalRows || 0).toLocaleString()}</div>
                </div>
                <div style={{ padding: '10px 14px', background: '#eff6ff', borderRadius: 8 }}>
                  <div style={{ fontSize: 11, color: '#6b7280', textTransform: 'uppercase', fontWeight: 600 }}>Date Range</div>
                  <div style={{ fontSize: 13, fontWeight: 600 }}>
                    {dryRunResult.dateRange?.min_date ? new Date(dryRunResult.dateRange.min_date).toLocaleDateString() : '—'}
                    {' → '}
                    {dryRunResult.dateRange?.max_date ? new Date(dryRunResult.dateRange.max_date).toLocaleDateString() : '—'}
                  </div>
                </div>
                <div style={{ padding: '10px 14px', background: '#faf5ff', borderRadius: 8 }}>
                  <div style={{ fontSize: 11, color: '#6b7280', textTransform: 'uppercase', fontWeight: 600 }}>Columns</div>
                  <div style={{ fontSize: 20, fontWeight: 700 }}>{(dryRunResult.columns || []).length}</div>
                </div>
              </div>
              {/* Available columns */}
              <div style={{ marginBottom: 12 }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 6 }}>Available Columns:</div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                  {sourceCols.map(c => (
                    <span key={c} style={{ padding: '2px 8px', background: '#f3f4f6', borderRadius: 6, fontSize: 11, fontFamily: 'monospace', color: '#374151' }}>{c}</span>
                  ))}
                </div>
              </div>
              {/* Sample data */}
              {dryRunResult.sampleRows?.length > 0 && (
                <div style={{ overflow: 'auto', maxHeight: 200, borderRadius: 8, border: '1px solid #e5e7eb' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 11 }}>
                    <thead>
                      <tr style={{ background: '#f9fafb' }}>
                        {Object.keys(dryRunResult.sampleRows[0]).map(k => (
                          <th key={k} style={{ padding: '6px 8px', textAlign: 'left', fontWeight: 600, borderBottom: '1px solid #e5e7eb', whiteSpace: 'nowrap' }}>{k}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {dryRunResult.sampleRows.map((row, i) => (
                        <tr key={i} style={{ borderBottom: '1px solid #f3f4f6' }}>
                          {Object.values(row).map((v, j) => (
                            <td key={j} style={{ padding: '4px 8px', maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                              {v !== null && v !== undefined ? String(v) : <span style={{ color: '#d1d5db' }}>NULL</span>}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Column mapping — Required */}
      <div style={card}>
        <h3 style={{ fontSize: 15, fontWeight: 700, marginBottom: 4, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Columns size={18} color="#2563eb" /> Column Mapping
        </h3>
        <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 16, margin: '0 0 16px' }}>
          Map your source table columns to Acquira fields. Only 3 required fields — everything else has safe defaults.
        </p>

        {/* Required columns */}
        <div style={{ marginBottom: 20 }}>
          <div style={{ fontSize: 12, fontWeight: 700, color: '#dc2626', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
            Required Fields
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
            {requiredCols.map(col => (
              <div key={col.key}>
                <label style={labelStyle}>
                  {col.label} <span style={{ color: '#dc2626' }}>*</span>
                </label>
                {sourceCols.length > 0 ? (
                  <select style={selectStyle} value={config.columnMapping[col.key] || ''}
                    onChange={e => updateMapping(col.key, e.target.value)}>
                    <option value="">— Select Column —</option>
                    {sourceCols.map(sc => <option key={sc} value={sc}>{sc}</option>)}
                  </select>
                ) : (
                  <input style={inputStyle} value={config.columnMapping[col.key] || ''}
                    onChange={e => updateMapping(col.key, e.target.value)} placeholder={col.key} />
                )}
                <div style={{ fontSize: 10, color: '#9ca3af', marginTop: 2 }}>{col.hint}</div>
              </div>
            ))}
          </div>
        </div>

        {/* Optional columns */}
        <div>
          <button style={{ ...btn('#f3f4f6', '#374151'), marginBottom: 12 }} onClick={() => setShowOptional(!showOptional)}>
            {showOptional ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            {showOptional ? 'Hide' : 'Show'} Optional Fields ({optionalCols.length})
          </button>

          {showOptional && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12, padding: 16, background: '#f9fafb', borderRadius: 10 }}>
              {optionalCols.map(col => (
                <div key={col.key}>
                  <label style={labelStyle}>{col.label}</label>
                  {sourceCols.length > 0 ? (
                    <select style={selectStyle} value={config.columnMapping[col.key] || ''}
                      onChange={e => updateMapping(col.key, e.target.value)}>
                      <option value="">— Not Mapped (default) —</option>
                      {sourceCols.map(sc => <option key={sc} value={sc}>{sc}</option>)}
                    </select>
                  ) : (
                    <input style={inputStyle} value={config.columnMapping[col.key] || ''}
                      onChange={e => updateMapping(col.key, e.target.value)} placeholder={`Default: see hint`} />
                  )}
                  <div style={{ fontSize: 10, color: '#9ca3af', marginTop: 2 }}>{col.hint}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

// ═══════════════════════════════════════════════════════════════
//  STEP 2: MIGRATION PROGRESS MONITOR
// ═══════════════════════════════════════════════════════════════
const ProgressMonitor = ({ progress, onRefresh }) => {
  if (!progress) return null;

  const pct = progress.totalMonths > 0 ? Math.round((progress.completedMonths / progress.totalMonths) * 100) : 0;
  const isRunning = progress.phase && !['IDLE', 'COMPLETED'].includes(progress.phase) && !progress.phase.startsWith('FAILED');
  const isComplete = progress.phase === 'COMPLETED';
  const isFailed = progress.phase?.startsWith('FAILED');

  const formatTime = (sec) => {
    if (!sec || sec <= 0) return '—';
    if (sec < 60) return `${sec}s`;
    if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`;
    return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`;
  };

  return (
    <div style={{ ...card, border: isComplete ? '2px solid #86efac' : isFailed ? '2px solid #fca5a5' : isRunning ? '2px solid #93c5fd' : '1px solid #e5e7eb' }}>
      {/* Status header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {isRunning && <Loader2 size={24} color="#2563eb" className="spin" />}
          {isComplete && <CheckCircle size={24} color="#16a34a" />}
          {isFailed && <XCircle size={24} color="#dc2626" />}
          {!isRunning && !isComplete && !isFailed && <Clock size={24} color="#6b7280" />}
          <div>
            <div style={{ fontSize: 16, fontWeight: 700, color: getPhaseColor(progress.phase) }}>
              {isComplete ? 'Migration Complete' : isFailed ? 'Migration Failed' : isRunning ? 'Migration In Progress...' : 'Idle'}
            </div>
            <div style={{ fontSize: 12, color: '#6b7280' }}>
              Phase: <span style={badge(getPhaseColor(progress.phase))}>{progress.phase || 'IDLE'}</span>
              {progress.currentMonth && <span style={{ marginLeft: 8 }}>• Month: <strong>{progress.currentMonth}</strong></span>}
            </div>
          </div>
        </div>
        <button style={btn('#f3f4f6', '#374151')} onClick={onRefresh}><RefreshCw size={14} /> Refresh</button>
      </div>

      {/* Progress bar */}
      <div style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
          <span style={{ fontSize: 13, fontWeight: 600 }}>{progress.completedMonths} / {progress.totalMonths} months</span>
          <span style={{ fontSize: 13, fontWeight: 700, color: getPhaseColor(progress.phase) }}>{pct}%</span>
        </div>
        <div style={{ height: 10, borderRadius: 5, background: '#f3f4f6', overflow: 'hidden' }}>
          <div style={{
            height: '100%', borderRadius: 5, width: `${pct}%`,
            background: isComplete ? '#16a34a' : isFailed ? '#dc2626' : 'linear-gradient(90deg, #3b82f6, #8b5cf6)',
            transition: 'width 0.5s ease'
          }} />
        </div>
      </div>

      {/* Stats grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
        <div style={{ padding: '12px 14px', background: '#f0fdf4', borderRadius: 8, textAlign: 'center' }}>
          <div style={{ fontSize: 11, color: '#6b7280', textTransform: 'uppercase', fontWeight: 600 }}>Rows Migrated</div>
          <div style={{ fontSize: 22, fontWeight: 700, color: '#16a34a' }}>{(progress.totalRowsMigrated || 0).toLocaleString()}</div>
        </div>
        <div style={{ padding: '12px 14px', background: '#eff6ff', borderRadius: 8, textAlign: 'center' }}>
          <div style={{ fontSize: 11, color: '#6b7280', textTransform: 'uppercase', fontWeight: 600 }}>Months Done</div>
          <div style={{ fontSize: 22, fontWeight: 700, color: '#2563eb' }}>{progress.completedMonths || 0}</div>
        </div>
        <div style={{ padding: '12px 14px', background: '#faf5ff', borderRadius: 8, textAlign: 'center' }}>
          <div style={{ fontSize: 11, color: '#6b7280', textTransform: 'uppercase', fontWeight: 600 }}>Elapsed</div>
          <div style={{ fontSize: 22, fontWeight: 700, color: '#8b5cf6' }}>{formatTime(progress.elapsedSeconds)}</div>
        </div>
        <div style={{ padding: '12px 14px', background: '#fefce8', borderRadius: 8, textAlign: 'center' }}>
          <div style={{ fontSize: 11, color: '#6b7280', textTransform: 'uppercase', fontWeight: 600 }}>ETA Remaining</div>
          <div style={{ fontSize: 22, fontWeight: 700, color: '#ca8a04' }}>{formatTime(progress.estimatedRemainingSeconds)}</div>
        </div>
      </div>

      {/* Failed message */}
      {isFailed && (
        <div style={{ marginTop: 16, padding: 14, background: '#fef2f2', borderRadius: 8, color: '#dc2626', fontSize: 13, fontFamily: 'monospace' }}>
          {progress.phase.replace('FAILED: ', '')}
        </div>
      )}
    </div>
  );
};

// ═══════════════════════════════════════════════════════════════
//  MAIN COMPONENT
// ═══════════════════════════════════════════════════════════════
const DeleteDayPanel = ({ activeTenantId }) => {
  const [date, setDate] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);

  const reset = () => { setConfirm(false); setResult(null); };

  const handleDelete = async () => {
    setLoading(true);
    setResult(null);
    if (!activeTenantId) {
      setResult({ error: 'No active tenant. Please re-login or pick a tenant.' });
      setLoading(false); setConfirm(false);
      return;
    }
    try {
      const res = await api.post('/admin/migration/delete-day', {
        tenantId: activeTenantId,
        date,
        confirm: true
      });
      setResult(res.data);
    } catch (e) {
      setResult({ error: e.response?.data?.error || e.message || 'Delete failed' });
    } finally { setLoading(false); setConfirm(false); }
  };

  return (
    <div style={{ ...card, marginTop: 20, border: '1px solid #fecaca' }}>
      <h3 style={{ fontSize: 15, fontWeight: 700, marginBottom: 4, display: 'flex', alignItems: 'center', gap: 8, color: '#b91c1c' }}>
        <Trash2 size={18} color="#dc2626" /> Delete a Day
      </h3>
      <p style={{ fontSize: 12, color: '#6b7280', margin: '0 0 16px' }}>
        Super-admin only. Permanently removes <strong>all</strong> transactions (AMS and CMM) for the
        selected date in the current tenant, and clears every summary so dashboards show the day as empty.
        The monthly totals are rebuilt from the remaining days. Re-upload that day's file to restore it.
      </p>

      <div style={{ display: 'flex', gap: 14, alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div>
          <label style={labelStyle}>Date to delete <span style={{ color: '#dc2626' }}>*</span></label>
          <input style={{ ...inputStyle, width: 200 }} type="date" value={date}
            max={new Date().toISOString().slice(0, 10)}
            onChange={e => { setDate(e.target.value); reset(); }} />
        </div>

        {!confirm ? (
          <button
            style={{ ...btn(date ? '#dc2626' : '#d1d5db', '#fff'), opacity: date ? 1 : 0.5 }}
            disabled={!date} onClick={() => setConfirm(true)}>
            <Trash2 size={15} /> Delete This Day
          </button>
        ) : (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span style={{ fontSize: 13, color: '#dc2626', fontWeight: 600 }}>
              Permanently delete {date}?
            </span>
            <button style={btn('#dc2626', '#fff')} onClick={handleDelete} disabled={loading}>
              {loading ? <Loader2 size={14} className="spin" /> : <Trash2 size={14} />}
              Yes, delete
            </button>
            <button style={btn('#f3f4f6', '#374151')} onClick={() => setConfirm(false)}>Cancel</button>
          </div>
        )}
      </div>

      {result && (
        <div style={{ marginTop: 16 }}>
          {result.error ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, color: '#dc2626', fontSize: 13 }}>
              <XCircle size={18} /> <span style={{ fontWeight: 600 }}>{result.error}</span>
            </div>
          ) : (
            <div style={{ padding: 14, background: '#f0fdf4', borderRadius: 8, border: '1px solid #86efac' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, color: '#16a34a' }}>
                <CheckCircle size={18} />
                <span style={{ fontWeight: 600 }}>Deleted {result.date} — dashboards now show this day as empty.</span>
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {Object.entries(result.removed || {}).map(([tbl, count]) => (
                  <span key={tbl} style={{ padding: '2px 8px', background: '#fff', borderRadius: 6, fontSize: 11, fontFamily: 'monospace', color: '#374151', border: '1px solid #e5e7eb' }}>
                    {tbl}: {String(count)}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const DataMigration = () => {
  const { activeTenantId } = useAuth(); // #12: Dynamic tenantId from auth context

  // Dynamic month defaults: roughly 2 years back to last completed month.
  // Was hardcoded '2024-01' → '2025-12' which is in the past as of mid-2026 and
  // would force users to update them every time they came to the screen.
  const _defaultMonths = (() => {
    const now = new Date();
    const lastFull = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const start = new Date(lastFull.getFullYear() - 2, lastFull.getMonth(), 1);
    const ymOf = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    return { start: ymOf(start), end: ymOf(lastFull) };
  })();

  const [config, setConfig] = useState({
    sourceTable: '',
    startMonth: _defaultMonths.start,
    endMonth: _defaultMonths.end,
    columnMapping: { mid: 'mid', payment_date: 'payment_date', txn_currency_amount: 'txn_currency_amount' }
  });
  const [dryRunResult, setDryRunResult] = useState(null);
  const [dryRunLoading, setDryRunLoading] = useState(false);
  const [progress, setProgress] = useState(null);
  const [starting, setStarting] = useState(false);
  const [confirmStart, setConfirmStart] = useState(false);
  const pollRef = useRef(null);

  // Poll progress
  const fetchProgress = useCallback(async () => {
    try {
      const res = await api.get('/admin/migration/progress');
      setProgress(res.data);
      // Stop polling if done
      if (res.data.phase === 'COMPLETED' || res.data.phase?.startsWith('FAILED') || res.data.phase === 'IDLE') {
        if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
      }
    } catch (e) { console.error('Progress fetch error', e); }
  }, []);

  // Initial progress check
  useEffect(() => { fetchProgress(); return () => { if (pollRef.current) clearInterval(pollRef.current); }; }, [fetchProgress]);

  // Dry run
  const handleDryRun = async (cleanedMapping) => {
    setDryRunLoading(true);
    setDryRunResult(null);
    if (!activeTenantId) {
      setDryRunResult({ error: 'No active tenant. Please re-login or pick a tenant.' });
      setDryRunLoading(false);
      return;
    }
    try {
      const res = await api.post('/admin/migration/dry-run', {
        tenantId: activeTenantId,
        sourceTable: config.sourceTable,
        columnMapping: cleanedMapping
      });
      setDryRunResult(res.data);
    } catch (e) {
      setDryRunResult({ error: e.response?.data?.error || e.message || 'Dry run failed' });
    } finally { setDryRunLoading(false); }
  };

  // Start migration
  const handleStart = async () => {
    setStarting(true);
    setConfirmStart(false);
    if (!activeTenantId) {
      alert('No active tenant. Please re-login or pick a tenant.');
      setStarting(false);
      return;
    }
    try {
      // Clean mapping — remove empty values
      const cleaned = {};
      Object.entries(config.columnMapping).forEach(([k, v]) => { if (v && v.trim()) cleaned[k] = v.trim(); });

      await api.post('/admin/migration/start', {
        tenantId: activeTenantId,
        sourceTable: config.sourceTable,
        startMonth: config.startMonth,
        endMonth: config.endMonth,
        columnMapping: cleaned
      });
      // Start polling
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = setInterval(fetchProgress, 5000);
      setTimeout(fetchProgress, 1000); // Quick first fetch
    } catch (e) {
      alert('Failed to start migration: ' + (e.response?.data?.error || e.message));
    } finally { setStarting(false); }
  };

  const isRunning = progress?.phase && !['IDLE', 'COMPLETED'].includes(progress.phase) && !progress.phase?.startsWith('FAILED');
  const canStart = config.sourceTable && config.startMonth && config.endMonth
    && config.columnMapping.mid && config.columnMapping.payment_date && config.columnMapping.txn_currency_amount
    && !isRunning;

  return (
    <div style={{ padding: '0 0 40px' }}>
      {/* Header */}
      <div style={{ marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, color: '#111', marginBottom: 4 }}>
          <DatabaseZap size={22} style={{ verticalAlign: 'middle', marginRight: 8, color: '#2563eb' }} />
          Data Migration
        </h1>
        <p style={{ fontSize: 13, color: '#6b7280', margin: 0 }}>
          Migrate historical transaction data from an existing database table into the Acquira platform.
          Bypasses CSV upload — processes millions of rows via bulk SQL, month by month.
        </p>
      </div>

      {/* Info banner */}
      <div style={{ ...card, background: '#eff6ff', border: '1px solid #bfdbfe', marginBottom: 20, padding: 16, display: 'flex', gap: 12, alignItems: 'flex-start' }}>
        <Info size={18} color="#2563eb" style={{ flexShrink: 0, marginTop: 2 }} />
        <div style={{ fontSize: 13, color: '#1e40af', lineHeight: 1.6 }}>
          <strong>How it works:</strong> Your source table must be in the same PostgreSQL database.
          The migration reads directly via <code style={{ background: '#dbeafe', padding: '1px 4px', borderRadius: 3 }}>INSERT...SELECT</code> —
          no files, no network transfer. For each month it: inserts into <code style={{ background: '#dbeafe', padding: '1px 4px', borderRadius: 3 }}>fact_transaction</code>,
          populates all 13 summary tables, and calculates dashboard metrics. Safe to re-run — existing data for each month is replaced.
        </div>
      </div>

      {/* Progress (if running or completed) */}
      {progress && progress.phase !== 'IDLE' && (
        <div style={{ marginBottom: 20 }}>
          <ProgressMonitor progress={progress} onRefresh={fetchProgress} />
        </div>
      )}

      {/* Configuration */}
      <ConfigureStep
        config={config}
        setConfig={setConfig}
        onDryRun={handleDryRun}
        dryRunResult={dryRunResult}
        dryRunLoading={dryRunLoading}
      />

      {/* Start button */}
      <div style={{ ...card, marginTop: 20, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>Ready to migrate?</div>
          <div style={{ fontSize: 12, color: '#6b7280' }}>
            {config.sourceTable || '(no table)'} • {config.startMonth || '?'} → {config.endMonth || '?'} •
            {' '}{Object.values(config.columnMapping).filter(v => v && v.trim()).length} columns mapped
          </div>
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {isRunning && (
            <span style={badge('#f59e0b')}>
              <Loader2 size={12} className="spin" /> Migration in progress...
            </span>
          )}
          {!confirmStart ? (
            <button style={{ ...btn(canStart ? '#2563eb' : '#d1d5db', '#fff'), opacity: canStart ? 1 : 0.5 }}
              disabled={!canStart} onClick={() => setConfirmStart(true)}>
              <Zap size={16} /> Start Migration
            </button>
          ) : (
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <span style={{ fontSize: 13, color: '#dc2626', fontWeight: 600 }}>Are you sure?</span>
              <button style={btn('#dc2626', '#fff')} onClick={handleStart} disabled={starting}>
                {starting ? <Loader2 size={14} className="spin" /> : <Play size={14} />}
                Yes, Start Now
              </button>
              <button style={btn('#f3f4f6', '#374151')} onClick={() => setConfirmStart(false)}>Cancel</button>
            </div>
          )}
        </div>
      </div>

      {/* Delete a day (super-admin correction tool) */}
      <DeleteDayPanel activeTenantId={activeTenantId} />

      {/* Spin animation */}
      <style>{`.spin { animation: spin 1s linear infinite; } @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`}</style>
    </div>
  );
};

export default DataMigration;
