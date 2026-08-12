import React, { useState, useEffect, useCallback } from 'react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { X, Loader2, DollarSign, TrendingUp, Building2, Users, Target, Percent } from 'lucide-react';
import api from '../api/axios';
import { formatMsf, formatCompactCurrency } from '../utils/formatters';

// ── formatters ───────────────────────────────────────────────
// fmt = counts. fmtM = MONEY: every value it renders (volume, MSF, target) is
// an amount, so it now carries the tenant currency and the tenant's decimals
// instead of a bare number rounded to whole units.
const fmt = (v) => v == null ? '0' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 0 });
const fmtM = (v) => formatCompactCurrency(v);
const fmtPct = (v) => v == null ? '—' : Number(v).toFixed(2) + '%';

const CARD = { background: '#fff', borderRadius: 14, padding: 20, border: '1px solid #eef0f4', boxShadow: '0 1px 4px rgba(0,0,0,.05)' };

/**
 * Reusable sales portfolio panel. Works at every tier of the hierarchy.
 *
 * Props:
 *   level   — 'agent' | 'team' | 'country'
 *   id      — salesUserId (agent) | teamLeadId | countryLeadId
 *   onClose — () => void
 *   onDrill — (childLevel, childId) => void   (optional; country->team->agent)
 */
const SalesPortfolioPanel = ({ level, id, onClose, onDrill }) => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await api.get(`/sales-portfolio/${level}/${encodeURIComponent(id)}`);
      setData(r.data);
    } catch (e) {
      console.error('Failed to load portfolio', e);
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [level, id]);

  useEffect(() => { load(); }, [load]);

  if (loading) {
    return (
      <div style={{ ...CARD, textAlign: 'center', padding: 48, marginBottom: 24, border: '2px solid #3b82f6' }}>
        <Loader2 size={26} className="acq-spin" />
        <style>{`.acq-spin{animation:acqspin 1s linear infinite}@keyframes acqspin{to{transform:rotate(360deg)}}`}</style>
      </div>
    );
  }
  if (!data) return null;

  const title = data.displayName || data.teamLeadName || data.countryLeadName || data.salesUserId || `#${id}`;
  const subtitle = data.salesEmail || data.teamLeadEmail || data.countryLeadEmail || '';
  const trend = (data.monthlyTrend || []).slice().reverse();

  // children differ by tier
  const childRows = level === 'agent' ? (data.merchants || [])
    : level === 'team' ? (data.agents || [])
    : (data.teams || []);

  const stats = [
    { icon: DollarSign, label: 'Volume', value: fmtM(data.totalVolume), color: '#10b981', bg: '#f0fdf4' },
    { icon: TrendingUp, label: 'MSF', value: fmtM(data.totalMsf), color: '#f59e0b', bg: '#fff7ed' },
    { icon: Percent, label: 'MSF Rate', value: fmtPct(data.msfRate), color: '#ec4899', bg: '#fdf2f8' },
    { icon: Building2, label: 'Merchants', value: fmt(data.merchantCount), color: '#3b82f6', bg: '#eff6ff' },
  ];
  if (level !== 'agent') {
    stats.push({ icon: Users, label: level === 'country' ? 'Agents' : 'Agents',
      value: fmt(data.agentCount), color: '#8b5cf6', bg: '#f5f3ff' });
  }
  if (level === 'country') {
    stats.push({ icon: Users, label: 'Teams', value: fmt(data.teamCount), color: '#0ea5e9', bg: '#f0f9ff' });
  }

  return (
    <div style={{ ...CARD, marginBottom: 24, border: '2px solid #3b82f6' }}>
      {/* header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 18 }}>
        <div>
          <div style={{ fontSize: 11, fontWeight: 700, color: '#3b82f6', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 2 }}>
            {level} portfolio
          </div>
          <h3 style={{ fontSize: 18, fontWeight: 800, margin: 0, color: '#0f172a' }}>{title}</h3>
          {subtitle && subtitle !== title && <div style={{ fontSize: 12, color: '#64748b' }}>{subtitle}</div>}
        </div>
        {onClose && (
          <button onClick={onClose} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '6px 12px', borderRadius: 8, background: '#f3f4f6', color: '#374151', border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
            <X size={14} /> Close
          </button>
        )}
      </div>

      {/* stat cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: 12, marginBottom: 18 }}>
        {stats.map((s) => (
          <div key={s.label} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: 12, borderRadius: 10, border: '1px solid #eef0f4' }}>
            <div style={{ width: 36, height: 36, borderRadius: 9, background: s.bg, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <s.icon size={17} color={s.color} />
            </div>
            <div>
              <div style={{ fontSize: 18, fontWeight: 800, color: '#0f172a', lineHeight: 1.1 }}>{s.value}</div>
              <div style={{ fontSize: 11, color: '#6b7280', fontWeight: 500 }}>{s.label}</div>
            </div>
          </div>
        ))}
      </div>

      {/* target attainment */}
      {data.target != null && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 14px', borderRadius: 10, background: '#f8fafc', border: '1px solid #eef0f4', marginBottom: 18 }}>
          <Target size={16} color="#6366f1" />
          <span style={{ fontSize: 13, color: '#374151', fontWeight: 600 }}>Target {fmtM(data.target)}</span>
          <div style={{ flex: 1, height: 8, borderRadius: 4, background: '#e2e8f0', overflow: 'hidden' }}>
            <div style={{ height: '100%', borderRadius: 4, width: `${Math.min(data.attainmentPct || 0, 100)}%`, background: (data.attainmentPct || 0) >= 100 ? '#10b981' : '#3b82f6' }} />
          </div>
          <span style={{ fontSize: 13, fontWeight: 800, color: (data.attainmentPct || 0) >= 100 ? '#10b981' : '#f59e0b' }}>
            {data.attainmentPct != null ? data.attainmentPct + '%' : '—'}
          </span>
        </div>
      )}

      {/* trend */}
      {trend.length > 1 && (
        <div style={{ height: 200, marginBottom: 20 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={trend}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
              <XAxis dataKey="month" tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} width={78} tickFormatter={fmtM} />
              <Tooltip formatter={(v) => fmtM(v)} />
              <Bar dataKey="volume" fill="#3b82f6" radius={[4, 4, 0, 0]} name="Volume" />
              <Bar dataKey="msf" fill="#f59e0b" radius={[4, 4, 0, 0]} name="MSF" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* children table */}
      <ChildrenTable level={level} rows={childRows} onDrill={onDrill} />
    </div>
  );
};

const ChildrenTable = ({ level, rows, onDrill }) => {
  const headers = level === 'agent'
    ? ['MID', 'Name', 'Status', 'Volume', 'MSF', 'Rate', 'Txns']
    : level === 'team'
      ? ['Agent', 'Merchants', 'Volume', 'MSF', 'Rate', 'Txns']
      : ['Team Lead', 'Agents', 'Merchants', 'Volume', 'MSF', 'Rate', 'Txns'];

  const childLabel = level === 'agent' ? 'Merchants' : level === 'team' ? 'Agents' : 'Team Leads';
  const drillTo = level === 'country' ? 'team' : level === 'team' ? 'agent' : null;

  return (
    <div>
      <div style={{ fontSize: 13, fontWeight: 700, color: '#374151', marginBottom: 8 }}>{childLabel} ({rows.length})</div>
      <div style={{ maxHeight: 320, overflow: 'auto', border: '1px solid #eef0f4', borderRadius: 8 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr style={{ position: 'sticky', top: 0, background: '#f8fafc', borderBottom: '2px solid #e2e8f0' }}>
              {headers.map((h, i) => (
                <th key={h} style={{ padding: '8px 10px', textAlign: i < (level === 'agent' ? 3 : 1) ? 'left' : 'right', color: '#64748b', fontWeight: 700, fontSize: 10, textTransform: 'uppercase' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr><td colSpan={headers.length} style={{ padding: 20, textAlign: 'center', color: '#94a3b8' }}>No data</td></tr>
            ) : rows.map((r, i) => {
              const vol = Number(r.volume || 0);
              const msf = Number(r.msf || 0);
              const rate = vol > 0 ? (msf / vol * 100) : 0;
              const clickable = drillTo && (r.team_lead_id || r.agent);
              const drillId = level === 'country' ? r.team_lead_id : r.agent;
              return (
                <tr key={i}
                    onClick={() => clickable && onDrill && onDrill(drillTo, drillId)}
                    style={{ borderBottom: '1px solid #f3f4f6', cursor: clickable ? 'pointer' : 'default' }}
                    onMouseOver={e => { if (clickable) e.currentTarget.style.background = '#f8fafc'; }}
                    onMouseOut={e => { e.currentTarget.style.background = ''; }}>
                  {level === 'agent' && <>
                    <td style={{ padding: '7px 10px', fontFamily: 'monospace', fontSize: 11 }}>{r.mid}</td>
                    <td style={{ padding: '7px 10px', fontWeight: 500 }}>{r.name || '—'}</td>
                    <td style={{ padding: '7px 10px' }}>
                      <span style={{ padding: '1px 6px', borderRadius: 8, fontSize: 10, fontWeight: 600, background: r.status === 'Active' || r.status === 'ACTIVE' ? '#dcfce7' : '#fee2e2', color: r.status === 'Active' || r.status === 'ACTIVE' ? '#166534' : '#991b1b' }}>{r.status || '—'}</span>
                    </td>
                  </>}
                  {level === 'team' && <>
                    <td style={{ padding: '7px 10px', fontWeight: 600 }}>
                      {r.displayName || (r.agent && r.agent.includes('@') ? r.agent.split('@')[0] : r.agent)}
                      {r.displayName && r.agent && <div style={{ fontSize: 10, color: '#94a3b8' }}>{r.agent}</div>}
                    </td>
                    <td style={{ padding: '7px 10px', textAlign: 'right' }}>{fmt(r.merchants)}</td>
                  </>}
                  {level === 'country' && <>
                    <td style={{ padding: '7px 10px', fontWeight: 600 }}>{r.team_lead_name}</td>
                    <td style={{ padding: '7px 10px', textAlign: 'right' }}>{fmt(r.agent_count)}</td>
                    <td style={{ padding: '7px 10px', textAlign: 'right' }}>{fmt(r.merchants)}</td>
                  </>}
                  <td style={{ padding: '7px 10px', textAlign: 'right', fontWeight: 700 }}>{fmtM(r.volume)}</td>
                  <td style={{ padding: '7px 10px', textAlign: 'right', color: '#f59e0b', fontWeight: 600 }} title={formatMsf(r.msf)}>{fmtM(r.msf)}</td>
                  <td style={{ padding: '7px 10px', textAlign: 'right', fontWeight: 600, color: rate >= 2 ? '#10b981' : '#6b7280' }}>{fmtPct(rate)}</td>
                  <td style={{ padding: '7px 10px', textAlign: 'right', color: '#6b7280' }}>{fmt(r.txn_count)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default SalesPortfolioPanel;
