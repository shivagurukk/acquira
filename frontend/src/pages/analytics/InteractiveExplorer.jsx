import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  CreditCard, Radio, Wallet, Globe2, Tag, Calendar, RefreshCw, Filter,
  X, DollarSign, Hash, Percent, Layers, Users
} from 'lucide-react';
import { BarChart, Bar, XAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import api from '../../api/axios';

/*
 * Interactive Explorer — click-to-cross-filter.
 * Click any bar in any widget; every other widget + the timeline + totals
 * re-filter to that selection. Active filters show as dismissable chips.
 * Backed by /api/cross-filter (reads the pre-aggregated sum_daily_insight cross-tab).
 */

const CARD = { background: '#fff', borderRadius: 14, padding: 18, boxShadow: '0 1px 4px rgba(0,0,0,.06)', border: '1px solid #eef0f4' };
const fmt = (v) => v == null ? '0' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 0 });
const fmtM = (v) => { const n = Number(v || 0); if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M'; if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K'; return n.toFixed(0); };

const DIMS = [
  { key: 'scheme', label: 'Card Scheme', icon: CreditCard, color: '#1E3A8A' },
  { key: 'channel', label: 'Channel', icon: Radio, color: '#2563eb' },
  { key: 'cardType', label: 'Card Type', icon: Wallet, color: '#0891b2' },
  { key: 'destination', label: 'Destination', icon: Globe2, color: '#7c3aed' },
  { key: 'mcc', label: 'Top MCC', icon: Tag, color: '#db2777' },
];
const DIM_LABEL = Object.fromEntries(DIMS.map(d => [d.key, d.label]));

const METRICS = [
  { key: 'txns', label: 'Transactions', icon: Hash },
  { key: 'volume', label: 'Amount', icon: DollarSign },
  { key: 'msf', label: 'MSF', icon: Percent },
];

const PRESETS = [
  { label: 'All Time', value: '' }, { label: 'MTD', value: 'MTD' }, { label: 'QTD', value: 'QTD' },
  { label: 'YTD', value: 'YTD' }, { label: 'Last Month', value: 'LAST_MONTH' },
];
const isoLocal = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
function periodToRange(p) {
  const t = new Date(), y = t.getFullYear(), m = t.getMonth();
  switch (p) {
    case 'MTD': return { from: isoLocal(new Date(y, m, 1)), to: isoLocal(t) };
    case 'QTD': return { from: isoLocal(new Date(y, Math.floor(m / 3) * 3, 1)), to: isoLocal(t) };
    case 'YTD': return { from: isoLocal(new Date(y, 0, 1)), to: isoLocal(t) };
    case 'LAST_MONTH': return { from: isoLocal(new Date(y, m - 1, 1)), to: isoLocal(new Date(y, m, 0)) };
    default: return { from: '', to: '' };
  }
}

function DimWidget({ dim, rows, metric, selectedValues, onToggle }) {
  const Icon = dim.icon;
  const max = Math.max(1, ...rows.map(r => Number(r[metric] || 0)));
  const anySel = selectedValues.length > 0;
  return (
    <div style={{ ...CARD, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 12 }}>
        <span style={{ display: 'inline-flex', width: 24, height: 24, borderRadius: 7, alignItems: 'center', justifyContent: 'center', background: dim.color + '18', color: dim.color }}><Icon size={14} /></span>
        <span style={{ fontSize: 13, fontWeight: 700, color: '#334155' }}>{dim.label}</span>
        {anySel && <span style={{ marginLeft: 'auto', fontSize: 10.5, fontWeight: 700, color: dim.color, background: dim.color + '14', padding: '2px 7px', borderRadius: 10 }}>{selectedValues.length} selected</span>}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7, overflowY: 'auto', maxHeight: 230 }}>
        {rows.length === 0 && <div style={{ fontSize: 12, color: '#cbd5e1', fontStyle: 'italic', padding: '6px 0' }}>No data.</div>}
        {rows.map(r => {
          const v = String(r.value);
          const sel = selectedValues.includes(v);
          const dim2 = anySel && !sel;
          const pct = Math.max(2, (Number(r[metric] || 0) / max) * 100);
          return (
            <div key={v} onClick={() => onToggle(dim.key, v)} title={`${v} — click to ${sel ? 'remove' : 'filter'}`}
              style={{ cursor: 'pointer', opacity: dim2 ? 0.45 : 1, transition: 'opacity .12s' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 3 }}>
                <span style={{ fontSize: 12, fontWeight: sel ? 700 : 500, color: sel ? dim.color : '#334155', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '70%' }}>
                  {sel ? '● ' : ''}{v}
                </span>
                <span style={{ fontSize: 11.5, fontWeight: 700, color: '#0f172a', fontVariantNumeric: 'tabular-nums' }}>
                  {metric === 'txns' ? fmt(r[metric]) : fmtM(r[metric])}
                </span>
              </div>
              <div style={{ height: 8, background: '#f1f5f9', borderRadius: 5, overflow: 'hidden' }}>
                <div style={{ width: `${pct}%`, height: '100%', background: dim.color, opacity: sel ? 1 : 0.7, borderRadius: 5, transition: 'width .25s' }} />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default function InteractiveExplorer() {
  const [period, setPeriod] = useState('');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [metric, setMetric] = useState('txns');
  const [filters, setFilters] = useState({ scheme: [], channel: [], cardType: [], destination: [], mcc: [] });
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  const range = useMemo(() => period === 'CUSTOM' ? { from: customFrom, to: customTo } : periodToRange(period), [period, customFrom, customTo]);

  const fetchData = useCallback(async () => {
    setLoading(true); setErr('');
    try {
      const params = {};
      if (range.from && range.to) { params.dateFrom = range.from; params.dateTo = range.to; }
      if (filters.scheme.length) params.schemes = filters.scheme.join(',');
      if (filters.channel.length) params.channels = filters.channel.join(',');
      if (filters.cardType.length) params.cardTypes = filters.cardType.join(',');
      if (filters.destination.length) params.destinations = filters.destination.join(',');
      if (filters.mcc.length) params.mccs = filters.mcc.join(',');
      const res = await api.get('/cross-filter', { params });
      setData(res.data);
    } catch (e) {
      setErr(e?.response?.data?.error || e.message || 'Failed to load');
    } finally { setLoading(false); }
  }, [range, filters]);

  useEffect(() => { fetchData(); }, [fetchData]);

  const toggle = (dim, value) => {
    setFilters(prev => {
      const cur = prev[dim];
      return { ...prev, [dim]: cur.includes(value) ? cur.filter(x => x !== value) : [...cur, value] };
    });
  };
  const clearOne = (dim, value) => setFilters(prev => ({ ...prev, [dim]: prev[dim].filter(x => x !== value) }));
  const clearAll = () => setFilters({ scheme: [], channel: [], cardType: [], destination: [], mcc: [] });

  const activeChips = useMemo(() => {
    const out = [];
    Object.entries(filters).forEach(([dim, vals]) => vals.forEach(v => out.push({ dim, value: v })));
    return out;
  }, [filters]);

  const totals = data?.totals || {};
  const timeline = (data?.timeline || []).map(t => ({ ...t, label: String(t.date).slice(5) }));
  const metricColor = '#2563eb';

  return (
    <div style={{ padding: 24, fontFamily: 'Inter, sans-serif', maxWidth: 1280, margin: '0 auto' }}>
      <style>{`@keyframes acqspin{to{transform:rotate(360deg)}} .acq-spin{animation:acqspin .8s linear infinite}`}</style>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Layers size={22} color="#1E3A8A" />
          <div>
            <h2 style={{ margin: 0, fontSize: 20, color: '#0f172a' }}>Interactive Explorer</h2>
            <div style={{ fontSize: 12.5, color: '#94a3b8' }}>Click any bar to cross-filter every widget</div>
          </div>
        </div>
        <button onClick={fetchData} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px', borderRadius: 8, background: '#fff', color: '#334155', border: '1px solid #e2e8f0', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      {/* controls */}
      <div style={{ ...CARD, padding: 14, marginBottom: 14, display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600, color: '#64748b' }}><Calendar size={15} /> Range</span>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {PRESETS.map(p => (
            <button key={p.value || 'all'} onClick={() => setPeriod(p.value)}
              style={{ padding: '6px 12px', borderRadius: 7, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid',
                borderColor: period === p.value ? '#1E3A8A' : '#e2e8f0', background: period === p.value ? '#1E3A8A' : '#fff', color: period === p.value ? '#fff' : '#475569' }}>
              {p.label}
            </button>
          ))}
          <input type="date" value={customFrom} max={customTo || undefined} onChange={e => { setCustomFrom(e.target.value); if (e.target.value && customTo) setPeriod('CUSTOM'); }} style={inputStyle} />
          <span style={{ color: '#cbd5e1', alignSelf: 'center' }}>→</span>
          <input type="date" value={customTo} min={customFrom || undefined} onChange={e => { setCustomTo(e.target.value); if (customFrom && e.target.value) setPeriod('CUSTOM'); }} style={inputStyle} />
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 4, background: '#f1f5f9', padding: 3, borderRadius: 9 }}>
          {METRICS.map(m => {
            const MIcon = m.icon; const on = metric === m.key;
            return (
              <button key={m.key} onClick={() => setMetric(m.key)}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '5px 11px', borderRadius: 7, fontSize: 12, fontWeight: 600, cursor: 'pointer', border: 'none',
                  background: on ? '#fff' : 'transparent', color: on ? '#1E3A8A' : '#64748b', boxShadow: on ? '0 1px 3px rgba(0,0,0,.08)' : 'none' }}>
                <MIcon size={12} /> {m.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* active filter chips */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14, flexWrap: 'wrap', minHeight: 30 }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, color: activeChips.length ? '#334155' : '#cbd5e1' }}>
          <Filter size={13} /> Filters
        </span>
        {activeChips.length === 0 && <span style={{ fontSize: 12, color: '#cbd5e1' }}>none — click a bar to start</span>}
        {activeChips.map(c => (
          <span key={c.dim + c.value} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, fontWeight: 600, color: '#1E3A8A', background: '#e0e7ff', borderRadius: 14, padding: '4px 6px 4px 10px' }}>
            <span style={{ opacity: 0.7 }}>{DIM_LABEL[c.dim]}:</span> {c.value}
            <span onClick={() => clearOne(c.dim, c.value)} style={{ cursor: 'pointer', display: 'inline-flex', width: 16, height: 16, borderRadius: '50%', alignItems: 'center', justifyContent: 'center', background: '#c7d2fe' }}><X size={10} /></span>
          </span>
        ))}
        {activeChips.length > 0 && <button onClick={clearAll} style={{ fontSize: 12, color: '#64748b', background: 'none', border: 'none', cursor: 'pointer', textDecoration: 'underline' }}>clear all</button>}
        {loading && <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 6, alignItems: 'center', fontSize: 12, color: '#94a3b8' }}><span className="acq-spin" style={{ width: 12, height: 12, border: '2px solid #e2e8f0', borderTopColor: '#1E3A8A', borderRadius: '50%' }} /> updating…</span>}
      </div>

      {/* totals */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, marginBottom: 14 }}>
        <Kpi label="Transactions" value={fmt(totals.txns)} icon={Hash} color="#1E3A8A" active={metric === 'txns'} />
        <Kpi label="Amount" value={fmtM(totals.volume)} icon={DollarSign} color="#0891b2" active={metric === 'volume'} />
        <Kpi label="MSF" value={fmtM(totals.msf)} icon={Percent} color="#16a34a" active={metric === 'msf'} />
        <Kpi label="Merchants" value={fmt(totals.merchants)} icon={Users} color="#7c3aed" />
      </div>

      {err && <div style={{ ...CARD, color: '#dc2626', fontSize: 13, marginBottom: 14 }}>{err}</div>}

      {/* timeline */}
      <div style={{ ...CARD, marginBottom: 14 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: '#334155', marginBottom: 10 }}>Daily {METRICS.find(m => m.key === metric).label}</div>
        <div style={{ width: '100%', height: 150 }}>
          {timeline.length > 0 ? (
            <ResponsiveContainer>
              <BarChart data={timeline} margin={{ top: 4, right: 4, left: 4, bottom: 0 }}>
                <XAxis dataKey="label" tick={{ fontSize: 10, fill: '#94a3b8' }} interval="preserveStartEnd" minTickGap={24} axisLine={false} tickLine={false} />
                <Tooltip cursor={{ fill: '#f1f5f9' }} contentStyle={{ fontSize: 12, borderRadius: 8, border: '1px solid #e2e8f0' }}
                  formatter={(val) => [metric === 'txns' ? fmt(val) : fmtM(val), METRICS.find(m => m.key === metric).label]} />
                <Bar dataKey={metric} radius={[3, 3, 0, 0]}>
                  {timeline.map((_, i) => <Cell key={i} fill={metricColor} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          ) : <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#cbd5e1', fontSize: 13 }}>No data in range.</div>}
        </div>
      </div>

      {/* dimension widgets */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))', gap: 14 }}>
        {DIMS.map(dim => (
          <DimWidget key={dim.key} dim={dim} rows={data?.dimensions?.[dim.key] || []} metric={metric}
            selectedValues={filters[dim.key]} onToggle={toggle} />
        ))}
      </div>

      <div style={{ fontSize: 11.5, color: '#cbd5e1', marginTop: 12 }}>
        Cross-filtered from the pre-aggregated daily insight cube. Transactions and MSF are exact; “Amount” is cardholder-currency (directional).
      </div>
    </div>
  );
}

const inputStyle = { padding: '6px 8px', borderRadius: 7, border: '1px solid #e2e8f0', fontSize: 12.5, color: '#334155', fontFamily: 'inherit' };

function Kpi({ label, value, icon: Icon, color, active }) {
  return (
    <div style={{ ...CARD, padding: 14, display: 'flex', alignItems: 'center', gap: 12, outline: active ? `2px solid ${color}` : 'none' }}>
      <span style={{ display: 'inline-flex', width: 36, height: 36, borderRadius: 9, alignItems: 'center', justifyContent: 'center', background: color + '14', color }}><Icon size={18} /></span>
      <div>
        <div style={{ fontSize: 19, fontWeight: 700, color: '#0f172a', fontVariantNumeric: 'tabular-nums' }}>{value}</div>
        <div style={{ fontSize: 11.5, color: '#94a3b8' }}>{label}</div>
      </div>
    </div>
  );
}
