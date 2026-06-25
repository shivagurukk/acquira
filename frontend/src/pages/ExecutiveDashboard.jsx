import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import {
  DollarSign, CreditCard, Activity, TrendingUp, TrendingDown, Users,
  RefreshCw, ArrowUpRight, ArrowDownRight, Receipt
} from 'lucide-react';
import {
  ResponsiveContainer, AreaChart, Area, XAxis, YAxis, Tooltip, CartesianGrid,
  PieChart, Pie, Cell, BarChart, Bar, Sector
} from 'recharts';
import { motion } from 'framer-motion';
import api from '../api/axios';
import { useAuth } from '../contexts/AuthContext';

/* ── Design tokens (theme-aware, dark-mode safe) ─────────── */
const T = {
  brand: 'var(--brand, #4f46e5)',
  text: 'var(--text, #0f172a)',
  textSec: 'var(--text-secondary, #64748b)',
  textMut: 'var(--text-muted, #94a3b8)',
  card: 'var(--bg-card, #ffffff)',
  bg: 'var(--bg, #f1f5f9)',
  subtle: 'var(--bg-subtle, #f8fafc)',
  border: 'var(--border, #e8edf3)',
  up: '#10b981', down: '#ef4444',
};
let CCY = 'AED'; // overridden per-tenant from AuthContext at render time
const SERIES = {
  volume:  { key: 'volume',  label: 'Volume',       color: '#4f46e5', icon: DollarSign },
  revenue: { key: 'revenue', label: 'Net Revenue',  color: '#10b981', icon: TrendingUp },
  txns:    { key: 'txns',    label: 'Transactions', color: '#f59e0b', icon: Activity },
};
const SCHEME_COLORS = ['#4f46e5', '#10b981', '#f59e0b', '#ef4444', '#06b6d4', '#8b5cf6', '#ec4899', '#64748b'];

/* ── Formatting helpers ──────────────────────────────────── */
const n = (v) => (v == null || isNaN(Number(v)) ? 0 : Number(v));
const fmtMoney = (v, compact = false) => new Intl.NumberFormat('en-US', {
  style: 'currency', currency: CCY, notation: compact ? 'compact' : 'standard',
  maximumFractionDigits: compact ? 1 : 0,
}).format(n(v));
const fmtNum = (v, compact = false) => new Intl.NumberFormat('en-US', {
  notation: compact ? 'compact' : 'standard', maximumFractionDigits: compact ? 1 : 0,
}).format(n(v));

/* normalize a SumDailyBank row regardless of camel/snake serialization */
const normTrend = (r) => ({
  full: r.businessDate || r.business_date || r.date || '',
  volume: n(r.totalVolume ?? r.total_volume),
  revenue: n(r.totalNetRevenue ?? r.total_net_revenue ?? r.totalRevenue),
  txns: n(r.totalTxns ?? r.total_txns),
  msf: n(r.totalMsf ?? r.total_msf),
  interchange: n(r.totalInterchange ?? r.total_interchange),
  vat: n(r.totalVat ?? r.total_vat),
});
const shortDate = (iso) => {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d) ? String(iso).slice(5) : `${d.getMonth() + 1}/${d.getDate()}`;
};

/* Merchant label cleanup.
   A real merchant name contains letters. When dim_merchant.name is blank or
   purely numeric (placeholder merchants auto-created from a transaction-first
   upload) or Excel scientific notation like "4.00E+14", we fall back to the MID
   and de-scientific-notation it so the chart shows a clean identifier instead
   of an ugly "4.0000000E+14". */
const sciToPlain = (s) => {
  if (s == null) return '';
  const t = String(s).trim();
  if (/^[+-]?\d+(\.\d+)?[eE][+-]?\d+$/.test(t)) {
    const num = Number(t);
    if (Number.isFinite(num)) {
      try { return BigInt(Math.round(num)).toString(); } catch { return num.toFixed(0); }
    }
  }
  return t;
};
const merchantLabel = (m) => {
  const name = (m.merchantName || '').trim();
  if (/[a-zA-Z]/.test(name)) return name;        // a real, human-readable name
  const mid = sciToPlain(m.mid);
  if (mid) return `MID ${mid}`;                   // numeric/blank name -> show the MID
  const cleaned = sciToPlain(name);
  return cleaned || '—';
};

/* ── Animated count-up ───────────────────────────────────── */
const useCountUp = (target, dur = 900) => {
  const [val, setVal] = useState(0);
  const ref = useRef(0);
  useEffect(() => {
    const from = ref.current, to = n(target), t0 = performance.now();
    let raf;
    const tick = (t) => {
      const p = Math.min(1, (t - t0) / dur);
      const eased = 1 - Math.pow(1 - p, 3);
      const cur = from + (to - from) * eased;
      setVal(cur); ref.current = cur;
      if (p < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, dur]);
  return val;
};

const ExecutiveDashboard = () => {
  const { currencyCode } = useAuth();
  CCY = currencyCode || CCY; // use the active tenant's currency everywhere on this page
  const [data, setData] = useState(null);
  const [schemes, setSchemes] = useState([]);
  const [topMerchants, setTopMerchants] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [metric, setMetric] = useState('volume');     // volume | revenue | txns
  const [range, setRange] = useState(30);              // 7 | 30
  const [activeSlice, setActiveSlice] = useState(0);

  const loadAll = useCallback(async () => {
    const today = new Date();
    const start = new Date(today); start.setDate(start.getDate() - 30);
    const iso = (d) => d.toISOString().slice(0, 10);
    const [exec, sch, mer] = await Promise.allSettled([
      api.get('/analytics/executive'),
      api.post('/analytics/scheme-breakdown', { startDate: iso(start), endDate: iso(today) }),
      api.get('/analytics/merchant-summaries', { params: { size: 50, page: 0 } }),
    ]);
    if (exec.status === 'fulfilled') setData(exec.value.data);
    if (sch.status === 'fulfilled') {
      setSchemes((sch.value.data || [])
        .map(s => ({ name: s.card_scheme || 'Unknown', value: n(s.total_volume), txns: n(s.total_txns) }))
        .filter(s => s.value > 0));
    }
    if (mer.status === 'fulfilled') {
      const content = mer.value.data?.content || [];
      setTopMerchants(content
        .map(m => ({ name: merchantLabel(m), value: n(m.mtdVolume), txns: n(m.mtdCount) }))
        .filter(m => m.value > 0)
        .sort((a, b) => b.value - a.value)
        .slice(0, 7));
    }
  }, []);

  useEffect(() => { (async () => { setLoading(true); try { await loadAll(); } finally { setLoading(false); } })(); }, [loadAll]);

  const refresh = async () => { setRefreshing(true); try { await loadAll(); } finally { setRefreshing(false); } };

  const trends = useMemo(() => (data?.trends || []).map(normTrend).filter(t => t.full), [data]);
  const series = useMemo(() => trends.slice(-range), [trends, range]);

  // Real delta: last point vs previous point for the active metric
  const delta = useMemo(() => {
    if (series.length < 2) return null;
    const cur = series[series.length - 1][metric];
    const prev = series[series.length - 2][metric];
    if (!prev) return null;
    return ((cur - prev) / prev) * 100;
  }, [series, metric]);

  const daily = data?.dailySnapshot || {};
  const mtd = data?.mtdSnapshot || {};
  const avgTicket = n(mtd.totalTxns) > 0 ? n(mtd.totalVolume) / n(mtd.totalTxns) : 0;

  const spark = (key) => series.map(s => ({ x: s.full, y: s[key] }));

  if (loading) return <DashboardSkeleton />;

  const activeMeta = SERIES[metric];
  const latestDate = trends.length ? trends[trends.length - 1].full : daily.date;

  return (
    <div style={{ padding: 24, color: T.text, maxWidth: 1500, margin: '0 auto' }}>
      <style>{`
        .ex-card{transition:transform .18s ease, box-shadow .18s ease}
        .ex-card:hover{transform:translateY(-3px);box-shadow:0 12px 30px rgba(15,23,42,.10)}
        .ex-seg{cursor:pointer;transition:all .15s}
        .ex-skel{background:linear-gradient(90deg,${T.subtle} 25%,${T.border} 37%,${T.subtle} 63%);background-size:400% 100%;animation:exsh 1.4s ease infinite;border-radius:10px}
        @keyframes exsh{0%{background-position:100% 50%}100%{background-position:0 50%}}
        @media (max-width:1024px){.ex-main{grid-template-columns:1fr !important}}
        @media (max-width:640px){.ex-kpis{grid-template-columns:1fr 1fr !important}}
      `}</style>

      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', flexWrap: 'wrap', gap: 14, marginBottom: 22 }}>
        <div>
          <div style={{ fontFamily: 'ui-monospace,monospace', fontSize: 11, letterSpacing: '.22em', textTransform: 'uppercase', color: T.textMut }}>Executive Overview</div>
          <h1 style={{ fontSize: 28, fontWeight: 800, margin: '4px 0 0', letterSpacing: '-.02em' }}>Performance at a glance</h1>
          <p style={{ fontSize: 13, color: T.textSec, margin: '4px 0 0' }}>
            {latestDate ? `Latest data: ${latestDate}` : 'No data loaded yet'} · {range}-day view
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Segmented value={range} onChange={setRange} options={[{ v: 7, l: '7D' }, { v: 30, l: '30D' }]} />
          <button onClick={refresh} aria-label="Refresh"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 7, padding: '9px 14px', borderRadius: 10, border: `1px solid ${T.border}`, background: T.card, color: T.text, cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
            <RefreshCw size={15} style={{ animation: refreshing ? 'spin 1s linear infinite' : 'none' }} /> Refresh
          </button>
          <style>{`@keyframes spin{to{transform:rotate(360deg)}}`}</style>
        </div>
      </div>

      {/* KPI cards */}
      <div className="ex-kpis" style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16, marginBottom: 16 }}>
        <KpiCard title="Today's Volume" value={fmtMoney(daily.totalVolume, true)} raw={daily.totalVolume}
          icon={DollarSign} color="#4f46e5" delta={metric === 'volume' ? delta : null} spark={spark('volume')} sparkColor="#4f46e5" />
        <KpiCard title="Today's Revenue" value={fmtMoney(daily.totalRevenue, true)} raw={daily.totalRevenue}
          icon={TrendingUp} color="#10b981" delta={metric === 'revenue' ? delta : null} spark={spark('revenue')} sparkColor="#10b981" />
        <KpiCard title="MTD Volume" value={fmtMoney(mtd.totalVolume, true)} raw={mtd.totalVolume}
          icon={Activity} color="#8b5cf6" sub={`${fmtNum(mtd.totalTxns, true)} txns`} spark={spark('volume')} sparkColor="#8b5cf6" />
        <KpiCard title="Active Merchants" value={fmtNum(data?.activeMerchants)} raw={data?.activeMerchants}
          icon={Users} color="#f59e0b" sub={`Avg ticket ${fmtMoney(avgTicket)}`} integer />
      </div>

      {/* Main grid: trend + scheme donut */}
      <div className="ex-main" style={{ display: 'grid', gridTemplateColumns: '1.7fr 1fr', gap: 16, marginBottom: 16 }}>
        {/* Trend chart */}
        <Panel>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8, flexWrap: 'wrap', gap: 10 }}>
            <div>
              <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700 }}>{activeMeta.label} trend</h3>
              <p style={{ margin: '2px 0 0', fontSize: 12, color: T.textMut }}>Last {range} days</p>
            </div>
            <Segmented value={metric} onChange={setMetric}
              options={Object.values(SERIES).map(s => ({ v: s.key, l: s.label }))} small />
          </div>
          <div style={{ height: 300 }}>
            {series.length === 0 ? <EmptyChart label="No trend data for this period" /> : (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={series} margin={{ top: 10, right: 8, left: 0, bottom: 0 }}>
                  <defs>
                    <linearGradient id="exGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor={activeMeta.color} stopOpacity={0.35} />
                      <stop offset="95%" stopColor={activeMeta.color} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke={T.border} vertical={false} />
                  <XAxis dataKey="full" tickFormatter={shortDate} tick={{ fontSize: 11, fill: T.textMut }} tickLine={false} axisLine={{ stroke: T.border }} minTickGap={24} />
                  <YAxis tickFormatter={(v) => metric === 'txns' ? fmtNum(v, true) : fmtMoney(v, true)} tick={{ fontSize: 11, fill: T.textMut }} tickLine={false} axisLine={false} width={56} />
                  <Tooltip content={<TrendTip metric={metric} />} />
                  <Area type="monotone" dataKey={metric} stroke={activeMeta.color} strokeWidth={2.5} fill="url(#exGrad)" activeDot={{ r: 5 }} animationDuration={700} />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </Panel>

        {/* Scheme donut */}
        <Panel>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700 }}>Card scheme mix</h3>
          <p style={{ margin: '2px 0 10px', fontSize: 12, color: T.textMut }}>Volume share, last 30 days</p>
          {schemes.length === 0 ? <EmptyChart label="No scheme data" /> : (
            <>
              <div style={{ height: 210 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={schemes} dataKey="value" nameKey="name" innerRadius={62} outerRadius={92}
                      paddingAngle={2} activeIndex={activeSlice} activeShape={ActiveSlice}
                      onMouseEnter={(_, i) => setActiveSlice(i)} animationDuration={700}>
                      {schemes.map((s, i) => <Cell key={i} fill={SCHEME_COLORS[i % SCHEME_COLORS.length]} stroke="none" className="ex-seg" />)}
                    </Pie>
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 8 }}>
                {schemes.slice(0, 6).map((s, i) => {
                  const total = schemes.reduce((a, b) => a + b.value, 0) || 1;
                  return (
                    <div key={i} onMouseEnter={() => setActiveSlice(i)}
                      style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, padding: '3px 4px', borderRadius: 6, background: activeSlice === i ? T.subtle : 'transparent', cursor: 'default' }}>
                      <span style={{ width: 10, height: 10, borderRadius: 3, background: SCHEME_COLORS[i % SCHEME_COLORS.length], flexShrink: 0 }} />
                      <span style={{ fontWeight: 600 }}>{s.name}</span>
                      <span style={{ marginLeft: 'auto', color: T.textSec }}>{fmtMoney(s.value, true)}</span>
                      <span style={{ color: T.textMut, width: 44, textAlign: 'right' }}>{((s.value / total) * 100).toFixed(1)}%</span>
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </Panel>
      </div>

      {/* Bottom grid: top merchants + revenue composition */}
      <div className="ex-main" style={{ display: 'grid', gridTemplateColumns: '1.7fr 1fr', gap: 16 }}>
        <Panel>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700 }}>Top merchants</h3>
          <p style={{ margin: '2px 0 10px', fontSize: 12, color: T.textMut }}>By month-to-date volume</p>
          {topMerchants.length === 0 ? <EmptyChart label="No merchant data" /> : (
            <div style={{ height: 280 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={topMerchants} layout="vertical" margin={{ left: 8, right: 16, top: 4, bottom: 4 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke={T.border} horizontal={false} />
                  <XAxis type="number" tickFormatter={(v) => fmtMoney(v, true)} tick={{ fontSize: 11, fill: T.textMut }} axisLine={false} tickLine={false} />
                  <YAxis type="category" dataKey="name" width={140} tick={{ fontSize: 12, fill: T.text }} axisLine={false} tickLine={false}
                    tickFormatter={(s) => s.length > 18 ? s.slice(0, 17) + '…' : s} />
                  <Tooltip content={<MerchTip />} cursor={{ fill: T.subtle }} />
                  <Bar dataKey="value" radius={[0, 6, 6, 0]} animationDuration={700}>
                    {topMerchants.map((_, i) => <Cell key={i} fill={SCHEME_COLORS[i % SCHEME_COLORS.length]} />)}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </Panel>

        <Panel>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700 }}>Revenue composition</h3>
          <p style={{ margin: '2px 0 10px', fontSize: 12, color: T.textMut }}>{range}-day fees</p>
          <RevenueComposition series={series} />
        </Panel>
      </div>
    </div>
  );
};

/* ── Sub-components ──────────────────────────────────────── */
const Panel = ({ children }) => (
  <div className="ex-card" style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: 16, padding: 20, boxShadow: '0 1px 2px rgba(15,23,42,.04)' }}>
    {children}
  </div>
);

const KpiCard = ({ title, value, raw, icon: Icon, color, delta, sub, spark, sparkColor, integer }) => {
  const animated = useCountUp(n(raw));
  const display = integer ? fmtNum(Math.round(animated)) : value; // count-up for integers, formatted string otherwise
  return (
    <motion.div className="ex-card" initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3 }}
      style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: 16, padding: 18, boxShadow: '0 1px 2px rgba(15,23,42,.04)', position: 'relative', overflow: 'hidden' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div style={{ minWidth: 0 }}>
          <p style={{ color: T.textSec, fontSize: 12.5, fontWeight: 600, margin: 0 }}>{title}</p>
          <h3 style={{ fontSize: 25, fontWeight: 800, color: T.text, margin: '6px 0 0', letterSpacing: '-.02em' }}>{display}</h3>
        </div>
        <div style={{ padding: 10, background: `${color}1a`, borderRadius: 12, color, flexShrink: 0 }}><Icon size={20} /></div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, minHeight: 18 }}>
        {delta != null && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: 12, fontWeight: 700, color: delta >= 0 ? T.up : T.down }}>
            {delta >= 0 ? <ArrowUpRight size={14} /> : <ArrowDownRight size={14} />}{Math.abs(delta).toFixed(1)}%
          </span>
        )}
        {delta != null && <span style={{ fontSize: 11, color: T.textMut }}>vs prev day</span>}
        {sub && <span style={{ fontSize: 11.5, color: T.textMut }}>{sub}</span>}
      </div>
      {spark && spark.length > 1 && (
        <div style={{ height: 34, marginTop: 8, marginLeft: -4, marginRight: -4 }}>
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={spark} margin={{ top: 2, right: 0, left: 0, bottom: 0 }}>
              <defs>
                <linearGradient id={`sp-${title}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={sparkColor} stopOpacity={0.3} />
                  <stop offset="100%" stopColor={sparkColor} stopOpacity={0} />
                </linearGradient>
              </defs>
              <Area type="monotone" dataKey="y" stroke={sparkColor} strokeWidth={1.8} fill={`url(#sp-${title})`} isAnimationActive={false} dot={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </motion.div>
  );
};

const Segmented = ({ value, onChange, options, small }) => (
  <div style={{ display: 'inline-flex', background: T.bg, borderRadius: 10, padding: 3, gap: 2 }}>
    {options.map(o => (
      <button key={o.v} onClick={() => onChange(o.v)}
        style={{ border: 'none', cursor: 'pointer', borderRadius: 8, padding: small ? '5px 10px' : '7px 13px', fontSize: small ? 12 : 13, fontWeight: 600,
          background: value === o.v ? T.card : 'transparent', color: value === o.v ? T.brand : T.textSec,
          boxShadow: value === o.v ? '0 1px 2px rgba(15,23,42,.08)' : 'none', transition: 'all .15s' }}>
        {o.l}
      </button>
    ))}
  </div>
);

const ActiveSlice = (props) => {
  const { cx, cy, innerRadius, outerRadius, startAngle, endAngle, fill, payload, percent } = props;
  return (
    <g>
      <text x={cx} y={cy - 6} textAnchor="middle" fill={T.text} style={{ fontSize: 15, fontWeight: 800 }}>{payload.name}</text>
      <text x={cx} y={cy + 14} textAnchor="middle" fill={T.textSec} style={{ fontSize: 12 }}>{(percent * 100).toFixed(1)}%</text>
      <Sector cx={cx} cy={cy} innerRadius={innerRadius} outerRadius={outerRadius + 5} startAngle={startAngle} endAngle={endAngle} fill={fill} />
    </g>
  );
};

const TrendTip = ({ active, payload, label, metric }) => {
  if (!active || !payload?.length) return null;
  const v = payload[0].value;
  return (
    <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: 10, padding: '8px 12px', boxShadow: '0 8px 24px rgba(15,23,42,.12)' }}>
      <div style={{ fontSize: 11, color: T.textMut, marginBottom: 2 }}>{label}</div>
      <div style={{ fontSize: 14, fontWeight: 700 }}>{metric === 'txns' ? fmtNum(v) : fmtMoney(v)}</div>
    </div>
  );
};

const MerchTip = ({ active, payload }) => {
  if (!active || !payload?.length) return null;
  const d = payload[0].payload;
  return (
    <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: 10, padding: '8px 12px', boxShadow: '0 8px 24px rgba(15,23,42,.12)' }}>
      <div style={{ fontSize: 12.5, fontWeight: 700, marginBottom: 2 }}>{d.name}</div>
      <div style={{ fontSize: 12, color: T.textSec }}>{fmtMoney(d.value)} · {fmtNum(d.txns)} txns</div>
    </div>
  );
};

const RevenueComposition = ({ series }) => {
  const totals = series.reduce((a, s) => ({ msf: a.msf + s.msf, interchange: a.interchange + s.interchange, vat: a.vat + s.vat }), { msf: 0, interchange: 0, vat: 0 });
  const data = [
    { name: 'MSF', value: totals.msf, color: '#4f46e5' },
    { name: 'Interchange', value: totals.interchange, color: '#06b6d4' },
    { name: 'VAT', value: totals.vat, color: '#f59e0b' },
  ].filter(d => d.value > 0);
  const sum = data.reduce((a, b) => a + b.value, 0);
  if (sum === 0) return <EmptyChart label="No fee data" />;
  return (
    <div>
      <div style={{ display: 'flex', height: 14, borderRadius: 999, overflow: 'hidden', marginBottom: 14 }}>
        {data.map((d, i) => <div key={i} title={`${d.name}: ${fmtMoney(d.value)}`} style={{ width: `${(d.value / sum) * 100}%`, background: d.color }} />)}
      </div>
      {data.map((d, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, padding: '7px 0', borderBottom: i < data.length - 1 ? `1px solid ${T.border}` : 'none' }}>
          <span style={{ width: 10, height: 10, borderRadius: 3, background: d.color }} />
          <span style={{ fontWeight: 600 }}>{d.name}</span>
          <span style={{ marginLeft: 'auto', color: T.textSec }}>{fmtMoney(d.value)}</span>
          <span style={{ color: T.textMut, width: 46, textAlign: 'right' }}>{((d.value / sum) * 100).toFixed(1)}%</span>
        </div>
      ))}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12, padding: '10px 12px', background: T.subtle, borderRadius: 10 }}>
        <Receipt size={16} color={T.textSec} />
        <span style={{ fontSize: 13, color: T.textSec }}>Total fees</span>
        <span style={{ marginLeft: 'auto', fontSize: 15, fontWeight: 800 }}>{fmtMoney(sum)}</span>
      </div>
    </div>
  );
};

const EmptyChart = ({ label }) => (
  <div style={{ height: '100%', minHeight: 180, display: 'flex', alignItems: 'center', justifyContent: 'center', color: T.textMut, fontSize: 13 }}>
    {label}
  </div>
);

const DashboardSkeleton = () => (
  <div style={{ padding: 24, maxWidth: 1500, margin: '0 auto' }}>
    <div className="ex-skel" style={{ width: 280, height: 30, marginBottom: 22 }} />
    <style>{`.ex-skel{background:linear-gradient(90deg,${T.subtle} 25%,${T.border} 37%,${T.subtle} 63%);background-size:400% 100%;animation:exsh 1.4s ease infinite;border-radius:12px}@keyframes exsh{0%{background-position:100% 50%}100%{background-position:0 50%}}`}</style>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 16, marginBottom: 16 }}>
      {[...Array(4)].map((_, i) => <div key={i} className="ex-skel" style={{ height: 130 }} />)}
    </div>
    <div style={{ display: 'grid', gridTemplateColumns: '1.7fr 1fr', gap: 16, marginBottom: 16 }}>
      <div className="ex-skel" style={{ height: 360 }} /><div className="ex-skel" style={{ height: 360 }} />
    </div>
  </div>
);

export default ExecutiveDashboard;
