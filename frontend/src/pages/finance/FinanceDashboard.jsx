import React, { useState, useEffect, useMemo, useCallback } from 'react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { formatCurrency, formatCompactCurrency } from '../../utils/formatters';
import { CATEGORICAL } from '../../theme/chartPalette';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import {
  DollarSign, TrendingUp, Percent, CreditCard, Activity, Calendar, RefreshCw,
  Download, Store, Tag, Layers, Radio, AlertTriangle, ArrowDownRight, Hash,
} from 'lucide-react';

/*
 * Finance Dashboard — interactive cockpit.
 * Period selector drives every panel. Revenue bridge (MSF → costs → net),
 * metric-switchable trend, a clickable Profitability Explorer (switch dimension,
 * re-rank, paginate, export), and a risk watchlist. All from existing /finance endpoints.
 */

const CARD = { background: 'var(--bg-card)', borderRadius: 14, padding: 18, boxShadow: 'var(--shadow-card)', border: '1px solid var(--border)' };
const input = { padding: '6px 8px', borderRadius: 7, border: '1px solid #e2e8f0', fontSize: 12.5, color: 'var(--text)', fontFamily: 'inherit' };

// Counts only — money compaction goes through formatCompactCurrency so it
// carries the tenant's currency and decimal precision.
const num = (v) => Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 0 });

const PRESETS = [
  { label: 'Today', value: 'TODAY' }, { label: 'MTD', value: 'MTD' }, { label: 'QTD', value: 'QTD' },
  { label: 'YTD', value: 'YTD' }, { label: 'Last Month', value: 'LAST_MONTH' },
];
const isoLocal = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
function periodToRange(p) {
  const t = new Date(), y = t.getFullYear(), m = t.getMonth();
  switch (p) {
    case 'TODAY': return { from: isoLocal(t), to: isoLocal(t) };
    case 'MTD': return { from: isoLocal(new Date(y, m, 1)), to: isoLocal(t) };
    case 'QTD': return { from: isoLocal(new Date(y, Math.floor(m / 3) * 3, 1)), to: isoLocal(t) };
    case 'YTD': return { from: isoLocal(new Date(y, 0, 1)), to: isoLocal(t) };
    case 'LAST_MONTH': return { from: isoLocal(new Date(y, m - 1, 1)), to: isoLocal(new Date(y, m, 0)) };
    default: return { from: '', to: '' };
  }
}

const GROUPS = [
  { key: 'merchant', label: 'Merchant', icon: Store, color: CATEGORICAL[0] },
  { key: 'scheme', label: 'Card Scheme', icon: CreditCard, color: CATEGORICAL[1] },
  { key: 'channel', label: 'Channel', icon: Radio, color: CATEGORICAL[2] },
  { key: 'mcc', label: 'MCC', icon: Tag, color: CATEGORICAL[3] },
];
const PROF_METRICS = [
  { key: 'totalVolume', label: 'Volume', kind: 'cur' },
  { key: 'totalNetRevenue', label: 'Net Margin', kind: 'cur' },
  { key: 'totalTxns', label: 'Transactions', kind: 'int' },
  { key: 'marginPct', label: 'Margin %', kind: 'pct' },
];
const TREND_METRICS = [
  { key: 'netRevenue', label: 'Net Margin', color: 'var(--chart-2)' },
  { key: 'msf', label: 'MSF', color: 'var(--chart-4)' },
  { key: 'interchange', label: 'Interchange', color: 'var(--chart-1)' },
  { key: 'marginPct', label: 'Margin %', color: 'var(--chart-alt)', pct: true },
];

const rowName = (r) => r.name || r.merchantName || (r.key != null ? String(r.key) : '—');
const marginOf = (r) => {
  const v = Number(r.totalVolume || 0);
  const n = Number(r.totalNetRevenue != null ? r.totalNetRevenue : r.netRevenue || 0);
  return v > 0 ? (n / v) * 100 : 0;
};
const valOf = (r, key) => key === 'marginPct' ? marginOf(r) : Number(r[key] || 0);

export default function FinanceDashboard() {
  // No 'AED' default and no whole-unit rounding: money renders in the tenant's
  // currency at the tenant's precision (3dp for BHD).
  const { currencyCode, formatCurrency: fmtCurr, tenantVersion } = useAuth() || {};
  const cur = useCallback((v) => (fmtCurr ? fmtCurr(v) : formatCurrency(v)), [fmtCurr]);
  const curC = useCallback((v) => formatCompactCurrency(v), [currencyCode]);

  const [period, setPeriod] = useState('YTD');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const range = useMemo(() => period === 'CUSTOM' ? { from: customFrom, to: customTo } : periodToRange(period), [period, customFrom, customTo]);

  const [kpis, setKpis] = useState(null);
  const [trends, setTrends] = useState([]);
  const [trendMetric, setTrendMetric] = useState('netRevenue');
  const [loading, setLoading] = useState(true);

  const [groupBy, setGroupBy] = useState('merchant');
  const [profMetric, setProfMetric] = useState('totalVolume');
  const [profRows, setProfRows] = useState([]);
  const [profLoading, setProfLoading] = useState(true);
  const [profPage, setProfPage] = useState(0);

  const [riskTab, setRiskTab] = useState('loss');
  const [riskRows, setRiskRows] = useState([]);
  const [riskLoading, setRiskLoading] = useState(true);

  // ── KPIs + trend ──
  const loadTop = useCallback(async () => {
    if (!range.from || !range.to) return;
    setLoading(true);
    const days = (new Date(range.to) - new Date(range.from)) / 86400000;
    const mode = days > 45 ? 'YTD' : 'MTD';
    try {
      const [k, t] = await Promise.all([
        api.get('/finance/dashboard/kpis', { params: { from: range.from, to: range.to } }),
        api.get(`/finance/dashboard/trends/${mode}`, { params: { from: range.from, to: range.to } }),
      ]);
      setKpis(k.data); setTrends(t.data || []);
    } catch (e) { console.error('finance kpis/trends', e); }
    finally { setLoading(false); }
  }, [range]);

  const loadProf = useCallback(async () => {
    if (!range.from || !range.to) return;
    setProfLoading(true); setProfPage(0);
    try {
      const r = await api.get('/finance/profitability', { params: { groupBy, from: range.from, to: range.to, page: 0, size: 200 } });
      setProfRows(r.data?.content || []);
    } catch (e) { console.error('finance profitability', e); setProfRows([]); }
    finally { setProfLoading(false); }
  }, [groupBy, range]);

  const loadRisk = useCallback(async () => {
    if (!range.from || !range.to) return;
    setRiskLoading(true);
    const url = riskTab === 'loss' ? '/finance/loss-making-merchants' : '/finance/high-volume-low-margin';
    try {
      const r = await api.get(url, { params: { from: range.from, to: range.to, page: 0, size: 50 } });
      setRiskRows(r.data?.content || []);
    } catch (e) { console.error('finance risk', e); setRiskRows([]); }
    finally { setRiskLoading(false); }
  }, [riskTab, range]);

  useEffect(() => { loadTop(); }, [loadTop, tenantVersion]);
  useEffect(() => { loadProf(); }, [loadProf, tenantVersion]);
  useEffect(() => { loadRisk(); }, [loadRisk, tenantVersion]);

  const exportCsv = async () => {
    try {
      const r = await api.get('/finance/export/profitability', { params: { groupBy, from: range.from, to: range.to }, responseType: 'blob' });
      const url = URL.createObjectURL(new Blob([r.data], { type: 'text/csv' }));
      const a = document.createElement('a');
      a.href = url; a.download = `profitability_${groupBy}_${range.from}_${range.to}.csv`;
      document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
    } catch (e) { console.error('export', e); }
  };

  // Revenue bridge from range cost analysis
  const bridge = useMemo(() => {
    const msf = Number(kpis?.msfRevenue || 0);
    const ic = Number(kpis?.interchangeFees || 0);
    const sc = Number(kpis?.schemeFees || 0);
    const ec = Number(kpis?.ecomFees || 0);
    const net = msf - ic - sc - ec;
    return { msf, ic, sc, ec, net, marginPct: Number(kpis?.marginPct || 0) };
  }, [kpis]);

  const sortedProf = useMemo(() => {
    const arr = [...profRows];
    arr.sort((a, b) => valOf(b, profMetric) - valOf(a, profMetric));
    return arr;
  }, [profRows, profMetric]);
  const topProf = sortedProf.slice(0, 12);
  const profMax = Math.max(1, ...topProf.map(r => Math.abs(valOf(r, profMetric))));
  const pageRows = sortedProf.slice(profPage * 10, profPage * 10 + 10);
  const profPages = Math.ceil(sortedProf.length / 10);
  const activeGroup = GROUPS.find(g => g.key === groupBy);
  const fmtMetric = (r, key) => {
    const m = PROF_METRICS.find(x => x.key === key);
    const v = valOf(r, key);
    return m.kind === 'cur' ? cur(v) : m.kind === 'pct' ? v.toFixed(2) + '%' : num(v);
  };

  const sortedRisk = useMemo(() => {
    const arr = [...riskRows];
    arr.sort((a, b) => Number(b.totalVolume || 0) - Number(a.totalVolume || 0));
    return arr;
  }, [riskRows]);

  const tm = TREND_METRICS.find(m => m.key === trendMetric);

  return (
    <div style={{ padding: 24, fontFamily: 'Inter, sans-serif', maxWidth: 1320, margin: '0 auto' }}>
      <style>{`@keyframes acqspin{to{transform:rotate(360deg)}} .acq-spin{animation:acqspin .8s linear infinite}`}</style>

      {/* header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ display: 'inline-flex', width: 38, height: 38, borderRadius: 10, alignItems: 'center', justifyContent: 'center', background: '#1E3A8A14', color: 'var(--chart-1)' }}><DollarSign size={20} /></span>
          <div>
            <h2 style={{ margin: 0, fontSize: 20, color: 'var(--text)' }}>Finance Dashboard</h2>
            <div style={{ fontSize: 12.5, color: 'var(--chart-axis)' }}>Profitability, cost structure & revenue performance</div>
          </div>
        </div>
        <button onClick={loadTop} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px', borderRadius: 8, background: 'var(--bg-card)', color: 'var(--text)', border: '1px solid #e2e8f0', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
          <RefreshCw size={14} className={loading ? 'acq-spin' : ''} /> Refresh
        </button>
      </div>

      {/* date controls */}
      <div style={{ ...CARD, padding: 14, marginBottom: 14, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600, color: 'var(--text-secondary)' }}><Calendar size={15} /> Period</span>
        {PRESETS.map(p => (
          <button key={p.value} onClick={() => setPeriod(p.value)}
            style={{ padding: '6px 12px', borderRadius: 7, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid',
              borderColor: period === p.value ? 'var(--chart-1)' : 'var(--border)', background: period === p.value ? 'var(--chart-1)' : 'var(--bg-card)', color: period === p.value ? 'var(--bg-card)' : 'var(--text-secondary)' }}>
            {p.label}
          </button>
        ))}
        <input type="date" value={customFrom} max={customTo || undefined} onChange={e => { setCustomFrom(e.target.value); if (e.target.value && customTo) setPeriod('CUSTOM'); }} style={input} />
        <span style={{ color: 'var(--border)' }}>→</span>
        <input type="date" value={customTo} min={customFrom || undefined} onChange={e => { setCustomTo(e.target.value); if (customFrom && e.target.value) setPeriod('CUSTOM'); }} style={input} />
        <span style={{ marginLeft: 'auto', fontSize: 11.5, color: 'var(--chart-axis)' }}>{range.from} – {range.to}</span>
      </div>

      {/* hero KPIs — selected period cost analysis */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12, marginBottom: 14 }}>
        <Kpi label="Net Margin" value={cur(bridge.net)} sub="MSF − costs" icon={TrendingUp} color="var(--success)" />
        <Kpi label="MSF Revenue" value={cur(bridge.msf)} sub="Gross fees" icon={DollarSign} color="var(--chart-2)" />
        <Kpi label="Interchange" value={cur(bridge.ic)} sub="Network cost" icon={CreditCard} color="var(--warning)" />
        <Kpi label="Scheme Fees" value={cur(bridge.sc)} sub="Card scheme" icon={Activity} color="var(--danger)" />
        <Kpi label="ECOM Fees" value={cur(bridge.ec)} sub="Flat e-com fee" icon={CreditCard} color="var(--chart-alt)" />
        <Kpi label="Margin %" value={`${Number(bridge.marginPct).toFixed(2)}%`} sub="Net / Volume" icon={Percent} color="var(--warning)" />
      </div>

      {/* revenue bridge + fixed buckets */}
      <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 14, marginBottom: 14 }}>
        <div style={CARD}>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text)', marginBottom: 12 }}>Revenue Bridge · MSF → Net</div>
          <RevenueBridge msf={bridge.msf} ic={bridge.ic} sc={bridge.sc} ec={bridge.ec} net={bridge.net} cur={cur} />
        </div>
        <div style={CARD}>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text)', marginBottom: 12 }}>At a glance</div>
          <Bucket label="Net Margin" today={kpis?.dailyNetRevenue} mtd={kpis?.mtdNetRevenue} ytd={kpis?.ytdNetRevenue} cur={cur} />
          <div style={{ height: 10 }} />
          <Bucket label="Volume" today={kpis?.dailyVolume} mtd={kpis?.mtdVolume} ytd={kpis?.ytdVolume} cur={cur} />
        </div>
      </div>

      {/* trend */}
      <div style={{ ...CARD, marginBottom: 14 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10, flexWrap: 'wrap', gap: 8 }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text)' }}>Trend</div>
          <div style={{ display: 'flex', gap: 4, background: 'var(--chart-grid)', padding: 3, borderRadius: 9 }}>
            {TREND_METRICS.map(m => {
              const on = trendMetric === m.key;
              return <button key={m.key} onClick={() => setTrendMetric(m.key)}
                style={{ padding: '5px 11px', borderRadius: 7, fontSize: 12, fontWeight: 600, cursor: 'pointer', border: 'none', background: on ? 'var(--bg-card)' : 'transparent', color: on ? m.color : 'var(--text-secondary)', boxShadow: on ? '0 1px 3px rgba(0,0,0,.08)' : 'none' }}>{m.label}</button>;
            })}
          </div>
        </div>
        <div style={{ width: '100%', height: 230 }}>
          {trends.length > 0 ? (
            <ResponsiveContainer>
              <AreaChart data={trends} margin={{ top: 6, right: 8, left: 4, bottom: 0 }}>
                <defs>
                  <linearGradient id="tg" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={tm.color} stopOpacity={0.35} />
                    <stop offset="100%" stopColor={tm.color} stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--chart-grid)" />
                <XAxis dataKey="key" tick={{ fontSize: 10.5, fill: 'var(--chart-axis)' }} tickFormatter={v => String(v).length > 7 ? String(v).slice(5) : v} axisLine={false} tickLine={false} minTickGap={20} />
                <YAxis tick={{ fontSize: 10.5, fill: 'var(--chart-axis)' }} axisLine={false} tickLine={false} width={54}
                  tickFormatter={v => tm.pct ? `${v}%` : curC(v)} />
                <Tooltip contentStyle={{ fontSize: 12, borderRadius: 8, border: '1px solid #e2e8f0' }}
                  formatter={v => [tm.pct ? `${Number(v).toFixed(2)}%` : cur(v), tm.label]} />
                <Area type="monotone" dataKey={trendMetric} stroke={tm.color} strokeWidth={2.5} fill="url(#tg)" dot={false} name={tm.label} />
              </AreaChart>
            </ResponsiveContainer>
          ) : <Empty loading={loading} />}
        </div>
      </div>

      {/* Profitability Explorer */}
      <div style={{ ...CARD, marginBottom: 14 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Layers size={16} color="var(--chart-1)" />
            <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text)' }}>Profitability Explorer</span>
          </div>
          <button onClick={exportCsv} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '6px 12px', borderRadius: 7, background: 'var(--bg-card)', border: '1px solid #e2e8f0', color: 'var(--text)', cursor: 'pointer', fontSize: 12, fontWeight: 600 }}>
            <Download size={13} /> Export CSV
          </button>
        </div>

        {/* dimension switch */}
        <div style={{ display: 'flex', gap: 6, marginBottom: 12, flexWrap: 'wrap' }}>
          {GROUPS.map(g => {
            const GIcon = g.icon, on = groupBy === g.key;
            return <button key={g.key} onClick={() => setGroupBy(g.key)}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 13px', borderRadius: 8, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid',
                borderColor: on ? g.color : 'var(--border)', background: on ? g.color : 'var(--bg-card)', color: on ? 'var(--bg-card)' : 'var(--text-secondary)' }}>
              <GIcon size={13} /> {g.label}
            </button>;
          })}
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 4, background: 'var(--chart-grid)', padding: 3, borderRadius: 9 }}>
            {PROF_METRICS.map(m => {
              const on = profMetric === m.key;
              return <button key={m.key} onClick={() => { setProfMetric(m.key); setProfPage(0); }}
                style={{ padding: '5px 10px', borderRadius: 7, fontSize: 11.5, fontWeight: 600, cursor: 'pointer', border: 'none', background: on ? 'var(--bg-card)' : 'transparent', color: on ? 'var(--chart-1)' : 'var(--text-secondary)', boxShadow: on ? '0 1px 3px rgba(0,0,0,.08)' : 'none' }}>{m.label}</button>;
            })}
          </div>
        </div>

        {profLoading ? <Empty loading /> : sortedProf.length === 0 ? <Empty /> : (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 18 }}>
            {/* ranked bars */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--chart-axis)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 2 }}>Top by {PROF_METRICS.find(m => m.key === profMetric).label}</div>
              {topProf.map((r, i) => {
                const v = valOf(r, profMetric);
                const pct = Math.max(2, (Math.abs(v) / profMax) * 100);
                const neg = v < 0;
                return (
                  <div key={i}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3 }}>
                      <span style={{ fontSize: 12, color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '62%' }}>{rowName(r)}</span>
                      <span style={{ fontSize: 11.5, fontWeight: 700, color: neg ? 'var(--danger)' : 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{fmtMetric(r, profMetric)}</span>
                    </div>
                    <div style={{ height: 8, background: 'var(--chart-grid)', borderRadius: 5, overflow: 'hidden' }}>
                      <div style={{ width: `${pct}%`, height: '100%', background: neg ? 'var(--danger)' : activeGroup.color, opacity: 0.85, borderRadius: 5, transition: 'width .25s' }} />
                    </div>
                  </div>
                );
              })}
            </div>
            {/* table */}
            <div>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <thead>
                  <tr style={{ color: 'var(--chart-axis)', textAlign: 'right', fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '.03em' }}>
                    <th style={{ textAlign: 'left', padding: '4px 6px', fontWeight: 700 }}>{activeGroup.label}</th>
                    <th style={{ padding: '4px 6px', fontWeight: 700 }}>Txns</th>
                    <th style={{ padding: '4px 6px', fontWeight: 700 }}>Volume</th>
                    <th style={{ padding: '4px 6px', fontWeight: 700 }}>Net Rev</th>
                    <th style={{ padding: '4px 6px', fontWeight: 700 }}>Margin</th>
                  </tr>
                </thead>
                <tbody>
                  {pageRows.map((r, i) => {
                    const mg = marginOf(r);
                    return (
                      <tr key={i} style={{ borderTop: '1px solid #f1f5f9' }}>
                        <td style={{ textAlign: 'left', padding: '6px', color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 150 }}>{rowName(r)}</td>
                        <td style={{ textAlign: 'right', padding: '6px', color: 'var(--text-secondary)', fontVariantNumeric: 'tabular-nums' }}>{num(r.totalTxns)}</td>
                        <td style={{ textAlign: 'right', padding: '6px', color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{curC(r.totalVolume)}</td>
                        <td style={{ textAlign: 'right', padding: '6px', color: Number(r.totalNetRevenue) < 0 ? 'var(--danger)' : 'var(--success)', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{curC(r.totalNetRevenue)}</td>
                        <td style={{ textAlign: 'right', padding: '6px', color: mg < 0 ? 'var(--danger)' : 'var(--text-secondary)', fontVariantNumeric: 'tabular-nums' }}>{mg.toFixed(1)}%</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {profPages > 1 && (
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 10, marginTop: 8, fontSize: 12, color: 'var(--text-secondary)' }}>
                  <button disabled={profPage === 0} onClick={() => setProfPage(p => p - 1)} style={pgBtn(profPage === 0)}>Prev</button>
                  <span>{profPage + 1} / {profPages}</span>
                  <button disabled={profPage >= profPages - 1} onClick={() => setProfPage(p => p + 1)} style={pgBtn(profPage >= profPages - 1)}>Next</button>
                </div>
              )}
              {sortedProf.length >= 200 && <div style={{ fontSize: 10.5, color: 'var(--border)', marginTop: 6 }}>Showing top 200 by current metric.</div>}
            </div>
          </div>
        )}
      </div>

      {/* Risk watchlist */}
      <div style={CARD}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12, flexWrap: 'wrap' }}>
          <AlertTriangle size={16} color="var(--danger)" />
          <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text)' }}>Risk Watchlist</span>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 4, background: 'var(--chart-grid)', padding: 3, borderRadius: 9 }}>
            {[{ k: 'loss', l: 'Loss-Making' }, { k: 'lowmargin', l: 'High Vol · Low Margin' }].map(t => {
              const on = riskTab === t.k;
              return <button key={t.k} onClick={() => setRiskTab(t.k)}
                style={{ padding: '5px 11px', borderRadius: 7, fontSize: 12, fontWeight: 600, cursor: 'pointer', border: 'none', background: on ? 'var(--bg-card)' : 'transparent', color: on ? 'var(--danger)' : 'var(--text-secondary)', boxShadow: on ? '0 1px 3px rgba(0,0,0,.08)' : 'none' }}>{t.l}</button>;
            })}
          </div>
        </div>
        {riskLoading ? <Empty loading /> : sortedRisk.length === 0 ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '14px 0', color: 'var(--success)', fontSize: 13 }}>
            <ArrowDownRight size={15} /> None in this period — nothing flagged.
          </div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
            <thead>
              <tr style={{ color: 'var(--chart-axis)', fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '.03em' }}>
                <th style={{ textAlign: 'left', padding: '4px 6px', fontWeight: 700 }}>Merchant</th>
                <th style={{ textAlign: 'right', padding: '4px 6px', fontWeight: 700 }}>Volume</th>
                <th style={{ textAlign: 'right', padding: '4px 6px', fontWeight: 700 }}>Net Margin</th>
                <th style={{ textAlign: 'right', padding: '4px 6px', fontWeight: 700 }}>Margin</th>
              </tr>
            </thead>
            <tbody>
              {sortedRisk.slice(0, 12).map((r, i) => {
                const mg = marginOf(r);
                const net = Number(r.netRevenue != null ? r.netRevenue : r.totalNetRevenue || 0);
                return (
                  <tr key={i} style={{ borderTop: '1px solid #f1f5f9' }}>
                    <td style={{ textAlign: 'left', padding: '7px 6px', color: 'var(--text)' }}>{rowName(r)}</td>
                    <td style={{ textAlign: 'right', padding: '7px 6px', color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{cur(r.totalVolume)}</td>
                    <td style={{ textAlign: 'right', padding: '7px 6px', color: net < 0 ? 'var(--danger)' : 'var(--success)', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{cur(net)}</td>
                    <td style={{ textAlign: 'right', padding: '7px 6px', color: mg < 0 ? 'var(--danger)' : 'var(--text-secondary)', fontVariantNumeric: 'tabular-nums' }}>{mg.toFixed(2)}%</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      <div style={{ fontSize: 11.5, color: 'var(--border)', marginTop: 12 }}>
        Cost figures (interchange, scheme, net) are from bank-level daily aggregates. “At a glance” tiles are anchored on today / month / year regardless of the selected period.
      </div>
    </div>
  );
}

const pgBtn = (disabled) => ({ padding: '4px 10px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--bg-card)', color: disabled ? 'var(--border)' : 'var(--text)', cursor: disabled ? 'default' : 'pointer', fontSize: 12, fontWeight: 600 });

function Kpi({ label, value, sub, icon: Icon, color }) {
  return (
    <div style={{ ...CARD, padding: 15, display: 'flex', alignItems: 'center', gap: 12 }}>
      <span style={{ display: 'inline-flex', width: 38, height: 38, borderRadius: 9, alignItems: 'center', justifyContent: 'center', background: `color-mix(in srgb, ${color} 12%, transparent)`, color, flexShrink: 0 }}><Icon size={18} /></span>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{value}</div>
        <div style={{ fontSize: 11, color: 'var(--chart-axis)' }}>{label} · {sub}</div>
      </div>
    </div>
  );
}

function Bucket({ label, today, mtd, ytd, cur }) {
  const cell = (t, v) => (
    <div style={{ flex: 1, textAlign: 'center' }}>
      <div style={{ fontSize: 14.5, fontWeight: 700, color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{cur(v)}</div>
      <div style={{ fontSize: 10.5, color: 'var(--chart-axis)', textTransform: 'uppercase', letterSpacing: '.04em' }}>{t}</div>
    </div>
  );
  return (
    <div>
      <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 6 }}>{label}</div>
      <div style={{ display: 'flex', gap: 6, background: 'var(--bg-subtle)', borderRadius: 10, padding: '10px 6px' }}>
        {cell('Today', today)}<Div />{cell('MTD', mtd)}<Div />{cell('YTD', ytd)}
      </div>
    </div>
  );
}
const Div = () => <div style={{ width: 1, background: 'var(--border)' }} />;

function RevenueBridge({ msf, ic, sc, ec, net, cur }) {
  const total = Math.max(1, msf);
  const seg = (val, color, label) => {
    const w = Math.max(0, (val / total) * 100);
    return { w, color, label, val };
  };
  const segs = [seg(net, 'var(--success)', 'Net'), seg(ic, 'var(--warning)', 'Interchange'), seg(sc, 'var(--danger)', 'Scheme'), seg(ec, 'var(--chart-alt)', 'ECOM')];
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
        <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>MSF (gross)</span>
        <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text)' }}>{cur(msf)}</span>
      </div>
      <div style={{ display: 'flex', height: 22, borderRadius: 7, overflow: 'hidden', background: 'var(--chart-grid)', marginBottom: 12 }}>
        {segs.map((s, i) => s.w > 0 && <div key={i} title={`${s.label}: ${cur(s.val)}`} style={{ width: `${s.w}%`, background: s.color }} />)}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
        {segs.map((s, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ width: 9, height: 9, borderRadius: 2, background: s.color }} />
            <span style={{ fontSize: 12, color: 'var(--text-secondary)', flex: 1 }}>{s.label}</span>
            <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{cur(s.val)}</span>
            <span style={{ fontSize: 11, color: 'var(--chart-axis)', width: 44, textAlign: 'right' }}>{((s.val / total) * 100).toFixed(0)}%</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function Empty({ loading }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: 120, color: 'var(--border)', fontSize: 13, gap: 8 }}>
      {loading ? <><span className="acq-spin" style={{ width: 14, height: 14, border: '2px solid #e2e8f0', borderTopColor: 'var(--chart-1)', borderRadius: '50%' }} /> Loading…</> : 'No data in this period.'}
    </div>
  );
}
