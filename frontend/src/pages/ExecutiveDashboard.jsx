import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { DollarSign, Activity, TrendingUp } from 'lucide-react';
import {
  ResponsiveContainer, AreaChart, Area, XAxis, YAxis, Tooltip, CartesianGrid,
  PieChart, Pie, Cell, Sector
} from 'recharts';
import api from '../api/axios';
import { useAuth } from '../contexts/AuthContext';
import { formatCurrency, formatCompactCurrency, getDefaultCurrency, resolveDecimals } from '../utils/formatters';

/* ── Design tokens (theme-aware, dark-mode safe) ─────────── */
const T = {
  brand: 'var(--primary)',
  text: 'var(--text)',
  textSec: 'var(--text-secondary)',
  textMut: 'var(--text-muted)',
  card: 'var(--bg-card)',
  bg: 'var(--bg)',
  subtle: 'var(--bg-subtle)',
  border: 'var(--border)',
  borderLight: 'var(--border-light)',
  up: 'var(--success)', down: 'var(--danger)',
};
const SERIES = {
  volume:  { key: 'volume',  label: 'Volume',       color: 'var(--chart-2)', icon: DollarSign },
  revenue: { key: 'revenue', label: 'Net Margin',   color: 'var(--chart-1)', icon: TrendingUp },
  txns:    { key: 'txns',    label: 'Transactions', color: 'var(--chart-3)', icon: Activity },
};
// Categorical series walk the teal ramp, then the projected/attention tokens.
const SCHEME_COLORS = [
  'var(--chart-1)', 'var(--chart-2)', 'var(--chart-3)', 'var(--chart-4)',
  'var(--chart-5)', 'var(--projected)', 'var(--attention)', 'var(--muted)',
];
// Every numeral on this page: mono face + tabular figures.
const NUMS = { fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums' };

/* ── Formatting helpers ──────────────────────────────────── */
const n = (v) => (v == null || isNaN(Number(v)) ? 0 : Number(v));
// Money always goes through the central formatter so the tenant's currency AND
// its decimal precision (3 for BHD, 2 for AED/EGP) apply — this page used to
// pin currency to AED and round money to whole units.
const fmtMoney = (v, compact = false) =>
  (compact ? formatCompactCurrency(n(v)) : formatCurrency(n(v)));
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

/* ── CSV export (client-side; no backend call) ───────────── */
const csvCell = (v) => {
  const s = v == null ? '' : String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};
const buildCsv = ({ kpiRows, trendRows, merchantRows, range }) => {
  const lines = [];
  lines.push(`Executive Dashboard Export,${new Date().toISOString().slice(0, 10)}`);
  lines.push(`Currency,${getDefaultCurrency() || 'UNKNOWN'}`);
  lines.push('');
  lines.push('KPI,Value');
  kpiRows.forEach(([k, v]) => lines.push(`${csvCell(k)},${csvCell(v)}`));
  lines.push('');
  lines.push(`Daily Trend (last ${range} days)`);
  lines.push('Date,Volume,Net Margin,Transactions,MSF,Interchange,VAT');
  trendRows.forEach(t => lines.push([t.full, t.volume, t.revenue, t.txns, t.msf, t.interchange, t.vat].map(csvCell).join(',')));
  lines.push('');
  lines.push('Top Merchants (MTD)');
  lines.push('Rank,Merchant,Volume,Transactions');
  merchantRows.forEach((m, i) => lines.push([i + 1, m.name, m.value, m.txns].map(csvCell).join(',')));
  return lines.join('\n');
};
const downloadCsv = (csv, filename) => {
  const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click();
  document.body.removeChild(a); URL.revokeObjectURL(url);
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
  // Currency/precision come from the shared formatters (AuthContext pushes the
  // active tenant's baseCurrency + currencyDecimals into them); currencyCode is
  // read only so this page re-renders on a tenant switch.
  const { currencyCode, currencyDecimals, tenantVersion } = useAuth();
  const [data, setData] = useState(null);
  const [schemes, setSchemes] = useState([]);
  const [topMerchants, setTopMerchants] = useState([]);
  const [cardMix, setCardMix] = useState({ credit: 0, debitPrepaid: 0 });
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
        .slice(0, 10));
      // Credit vs Debit/Prepaid split — aggregate the day's split across all
      // fetched merchant rows. These columns come from sum_daily_merchant (daily
      // grain) via the merchant-summaries endpoint.
      const mix = content.reduce((a, m) => ({
        credit: a.credit + n(m.creditVolume),
        debitPrepaid: a.debitPrepaid + n(m.debitPrepaidVolume),
      }), { credit: 0, debitPrepaid: 0 });
      setCardMix(mix);
    }
  }, []);

  useEffect(() => { (async () => { setLoading(true); try { await loadAll(); } finally { setLoading(false); } })(); }, [loadAll, tenantVersion]);

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
  const mtdRevenue = n(mtd.totalRevenue);
  const mtdMargin = n(mtd.totalVolume) > 0 ? (mtdRevenue / n(mtd.totalVolume)) * 100 : 0;
  const mtdTxns = n(mtd.totalTxns);
  const mtdVolLastYear = n(data?.mtdVolumeLastYear);
  const yoyPct = mtdVolLastYear > 0 ? ((n(mtd.totalVolume) - mtdVolLastYear) / mtdVolLastYear) * 100 : null;
  const dormantMerchants = n(data?.dormantMerchants);

  const spark = (key) => series.map(s => ({ x: s.full, y: s[key] }));

  const exportCsv = () => {
    const csv = buildCsv({
      kpiRows: [
        ["Today's Volume", n(daily.totalVolume)],
        ["Today's Revenue", n(daily.totalRevenue)],
        ['MTD Volume', n(mtd.totalVolume)],
        ['MTD Net Margin', mtdRevenue],
        ['MTD Transactions', mtdTxns],
        // Money → tenant precision (3dp for BHD); the % rows below stay at 2dp.
        ['MTD Avg Ticket', avgTicket.toFixed(resolveDecimals(currencyDecimals, currencyCode))],
        ['MTD Margin %', mtdMargin.toFixed(2)],
        ['YoY Volume %', yoyPct == null ? '' : yoyPct.toFixed(2)],
        ['Active Merchants', n(data?.activeMerchants)],
        ['Dormant Merchants', dormantMerchants],
      ],
      trendRows: series,
      merchantRows: topMerchants,
      range,
    });
    downloadCsv(csv, `executive-dashboard_${new Date().toISOString().slice(0, 10)}.csv`);
  };

  if (loading) return <DashboardSkeleton />;

  const activeMeta = SERIES[metric];
  const latestDate = trends.length ? trends[trends.length - 1].full : daily.date;

  return (
    <div style={{ padding: 'var(--space-page, 28px)', color: T.text, minHeight: '100vh', background: T.bg }}>
      <style>{`
        .ex-card{transition:background-color .15s ease}
        .ex-card:hover{background:${T.card}}
        .ex-seg{cursor:pointer;transition:background-color .15s}
        .ex-btn{display:inline-flex;align-items:center;gap:7px;padding:6px 12px;border-radius:4px;border:1px solid ${T.border};background:${T.card};color:${T.text};cursor:pointer;font-size:13px;font-weight:500;transition:background-color .15s}
        .ex-btn:hover{background:var(--bg-hover)}
        .ex-btn:focus-visible{outline:2px solid ${T.brand};outline-offset:2px}
        .ex-skel{background:${T.subtle};animation:exsh 1.4s ease infinite;border-radius:4px}
        @keyframes exsh{0%,100%{opacity:1}50%{opacity:.55}}
        @keyframes spin{to{transform:rotate(360deg)}}
        @media (prefers-reduced-motion: reduce){.ex-card,.ex-btn,.ex-seg{transition:none}}
        @media (max-width:1280px){.ex-strip{grid-template-columns:repeat(3,1fr) !important}.ex-strip>div{border-bottom:1px solid ${T.borderLight}}}
        @media (max-width:1024px){.ex-main{grid-template-columns:1fr !important}.ex-heroes{grid-template-columns:1fr !important}}
        @media (max-width:640px){.ex-strip{grid-template-columns:1fr 1fr !important}}
      `}</style>

      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', flexWrap: 'wrap', gap: 14, marginBottom: 20, paddingBottom: 18, borderBottom: `1px solid ${T.border}` }}>
        <div>
          <div style={{ fontFamily: 'var(--font-mono)', fontSize: 10, letterSpacing: '.08em', textTransform: 'uppercase', color: T.textMut }}>Executive Overview</div>
          <h1 style={{ fontSize: 20, fontWeight: 600, margin: '4px 0 0', letterSpacing: '-.01em' }}>Performance at a glance</h1>
          <p style={{ fontSize: 13, color: T.textSec, margin: '4px 0 0', ...NUMS }}>
            {latestDate ? `Latest data: ${latestDate}` : 'No data loaded yet'} · {range}-day view
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <Segmented value={range} onChange={setRange} options={[{ v: 7, l: '7D' }, { v: 30, l: '30D' }]} />
          {/* Text-only actions — icons live in the sidebar rail only. */}
          <button className="ex-btn" onClick={exportCsv} aria-label="Export CSV">
            Export
          </button>
          <button className="ex-btn" onClick={refresh} aria-label="Refresh" disabled={refreshing}>
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      {/* Hero KPIs — the three numbers a CEO checks first */}
      <div className="ex-heroes" style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16, marginBottom: 16 }}>
        <HeroKpi title="Today's Volume" value={fmtMoney(daily.totalVolume, true)}
          color="var(--chart-2)" delta={metric === 'volume' ? delta : null}
          spark={spark('volume')} />
        <HeroKpi title="Today's Net Margin" value={fmtMoney(daily.totalRevenue, true)}
          color="var(--chart-1)" delta={metric === 'revenue' ? delta : null}
          spark={spark('revenue')} />
        <HeroKpi title="MTD Volume" value={fmtMoney(mtd.totalVolume, true)}
          color="var(--chart-3)" sub={`${fmtNum(mtd.totalTxns, true)} transactions`}
          spark={spark('volume')} />
      </div>

      {/* Secondary metrics — one quiet strip, hairline-divided */}
      <div className="ex-card" style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: 4, marginBottom: 16, overflow: 'hidden' }}>
        <div className="ex-strip" style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)' }}>
          <StatCell label="MTD Net Margin" value={fmtMoney(mtdRevenue, true)} sub={`${mtdMargin.toFixed(1)}% margin`} />
          <StatCell label="MTD Transactions" value={fmtNum(mtdTxns, true)} sub="this month" countTo={mtdTxns} integer />
          <StatCell label="Avg Ticket" value={fmtMoney(avgTicket)} sub="per transaction" />
          <StatCell label="YoY Volume"
            value={yoyPct == null ? '—' : `${yoyPct >= 0 ? '+' : ''}${yoyPct.toFixed(1)}%`}
            sub={yoyPct == null ? 'no prior-year data' : 'vs last year MTD'}
            tone={yoyPct == null ? null : (yoyPct >= 0 ? 'up' : 'down')} />
          <StatCell label="Active Merchants" value={fmtNum(data?.activeMerchants)} sub="processing" countTo={n(data?.activeMerchants)} integer />
          <StatCell label="Dormant Merchants" value={fmtNum(dormantMerchants)} sub="no recent activity" countTo={dormantMerchants} integer last />
        </div>
      </div>

      {/* Main grid: trend + scheme donut */}
      <div className="ex-main" style={{ display: 'grid', gridTemplateColumns: '1.7fr 1fr', gap: 16, marginBottom: 16 }}>
        {/* Trend chart */}
        <Panel>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8, flexWrap: 'wrap', gap: 10 }}>
            <div>
              <h3 className="section-title" style={{ margin: 0, fontSize: 13, fontWeight: 600, letterSpacing: '0.02em', color: T.textMut }}>{activeMeta.label} trend</h3>
              <p style={{ margin: '2px 0 0', fontSize: 12, color: T.textMut }}>Last {range} days</p>
            </div>
            <Segmented value={metric} onChange={setMetric}
              options={Object.values(SERIES).map(s => ({ v: s.key, l: s.label }))} small />
          </div>
          <div style={{ height: 300 }}>
            {series.length === 0 ? <EmptyChart label="No trend data for this period" /> : (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={series} margin={{ top: 10, right: 8, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke={T.borderLight} vertical={false} />
                  <XAxis dataKey="full" tickFormatter={shortDate} tick={{ fontSize: 11, fill: T.textMut, fontFamily: 'var(--font-mono)' }} tickLine={false} axisLine={{ stroke: T.border }} minTickGap={24} />
                  <YAxis tickFormatter={(v) => metric === 'txns' ? fmtNum(v, true) : fmtMoney(v, true)} tick={{ fontSize: 11, fill: T.textMut, fontFamily: 'var(--font-mono)' }} tickLine={false} axisLine={false} width={56} />
                  <Tooltip content={<TrendTip metric={metric} />} />
                  <Area type="monotone" dataKey={metric} stroke={activeMeta.color} strokeWidth={2} fill={activeMeta.color} fillOpacity={0.08} activeDot={{ r: 4 }} animationDuration={150} />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </Panel>

        {/* Scheme donut */}
        <Panel>
          <h3 className="section-title" style={{ margin: 0, fontSize: 13, fontWeight: 600, letterSpacing: '0.02em', color: T.textMut }}>Card scheme mix</h3>
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
                      <span style={{ marginLeft: 'auto', color: T.textSec, ...NUMS }}>{fmtMoney(s.value, true)}</span>
                      <span style={{ color: T.textMut, width: 44, textAlign: 'right', ...NUMS }}>{((s.value / total) * 100).toFixed(1)}%</span>
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
          <h3 className="section-title" style={{ margin: 0, fontSize: 13, fontWeight: 600, letterSpacing: '0.02em', color: T.textMut }}>Top 10 merchants</h3>
          <p style={{ margin: '2px 0 10px', fontSize: 12, color: T.textMut }}>By month-to-date volume</p>
          {topMerchants.length === 0 ? <EmptyChart label="No merchant data" /> : (
            <TopMerchantList merchants={topMerchants} />
          )}
        </Panel>

        <Panel>
          <h3 className="section-title" style={{ margin: 0, fontSize: 13, fontWeight: 600, letterSpacing: '0.02em', color: T.textMut }}>Revenue composition</h3>
          <p style={{ margin: '2px 0 10px', fontSize: 12, color: T.textMut }}>{range}-day fees</p>
          <RevenueComposition series={series} />
        </Panel>
      </div>

      {/* Card mix row */}
      <div style={{ marginTop: 16 }}>
        <Panel>
          <h3 className="section-title" style={{ margin: 0, fontSize: 13, fontWeight: 600, letterSpacing: '0.02em', color: T.textMut }}>Credit vs Debit / Prepaid</h3>
          <p style={{ margin: '2px 0 14px', fontSize: 12, color: T.textMut }}>Volume split, today</p>
          <CardMixChart mix={cardMix} />
        </Panel>
      </div>
    </div>
  );
};

/* ── Sub-components ──────────────────────────────────────── */
const Panel = ({ children }) => (
  <div className="ex-card" style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: 4, padding: 20, boxShadow: 'none' }}>
    {children}
  </div>
);

/* Hero KPI — mono number, muted section title, sparkline underneath.
   No icon chip: icons are a sidebar-only affordance. Delta pairs colour
   with a glyph so meaning survives greyscale. */
const HeroKpi = ({ title, value, color, delta, sub, spark }) => (
  <div className="ex-card" style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: 4, padding: '18px 20px 12px', boxShadow: 'none', overflow: 'hidden' }}>
    <div style={{ minWidth: 0 }}>
      <p style={{ color: T.textMut, fontSize: 13, fontWeight: 600, margin: 0, letterSpacing: '.02em' }}>{title}</p>
      <h3 style={{ fontSize: 28, fontWeight: 500, color: T.text, margin: '6px 0 0', ...NUMS }}>{value}</h3>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6, minHeight: 18 }}>
        {delta != null && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 12, fontWeight: 500, color: delta >= 0 ? T.up : T.down, ...NUMS }}>
            {delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toFixed(1)}%
          </span>
        )}
        {delta != null && <span style={{ fontSize: 11, color: T.textMut }}>vs prev day</span>}
        {sub && <span style={{ fontSize: 11.5, color: T.textMut, ...NUMS }}>{sub}</span>}
      </div>
    </div>
    {spark && spark.length > 1 && (
      <div style={{ height: 38, marginTop: 8, marginLeft: -20, marginRight: -20, marginBottom: -12 }}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={spark} margin={{ top: 2, right: 0, left: 0, bottom: 0 }}>
            <Area type="monotone" dataKey="y" stroke={color} strokeWidth={1.5} fill={color} fillOpacity={0.08} isAnimationActive={false} dot={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    )}
  </div>
);

/* Stat strip cell — hairline-divided, tabular numerals */
const StatCell = ({ label, value, sub, tone, countTo, integer, last }) => {
  const animated = useCountUp(integer ? n(countTo) : 0);
  const display = integer ? fmtNum(Math.round(animated)) : value;
  const toneColor = tone === 'up' ? T.up : tone === 'down' ? T.down : T.text;
  return (
    <div style={{ padding: '16px 20px', borderRight: last ? 'none' : `1px solid ${T.borderLight}` }}>
      <p style={{ color: T.textMut, fontSize: 12, fontWeight: 600, margin: 0, letterSpacing: '.02em', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{label}</p>
      <div style={{ fontSize: 16, fontWeight: 500, color: toneColor, margin: '5px 0 0', ...NUMS }}>{display}</div>
      {sub && <div style={{ fontSize: 11, color: T.textMut, marginTop: 3 }}>{sub}</div>}
    </div>
  );
};

const Segmented = ({ value, onChange, options, small }) => (
  <div style={{ display: 'inline-flex', background: T.subtle, border: `1px solid ${T.border}`, borderRadius: 4, padding: 2, gap: 2 }}>
    {options.map(o => (
      <button key={o.v} onClick={() => onChange(o.v)}
        style={{ border: 'none', cursor: 'pointer', borderRadius: 4, padding: small ? '4px 10px' : '6px 12px', fontSize: small ? 12 : 13, fontWeight: 500,
          background: value === o.v ? 'var(--wash)' : 'transparent', color: value === o.v ? T.brand : T.textSec,
          transition: 'background-color .15s, color .15s' }}>
        {o.l}
      </button>
    ))}
  </div>
);

const ActiveSlice = (props) => {
  const { cx, cy, innerRadius, outerRadius, startAngle, endAngle, fill, payload, percent } = props;
  return (
    <g>
      <text x={cx} y={cy - 6} textAnchor="middle" fill={T.text} style={{ fontSize: 14, fontWeight: 600 }}>{payload.name}</text>
      <text x={cx} y={cy + 14} textAnchor="middle" fill={T.textSec} style={{ fontSize: 12 }}>{(percent * 100).toFixed(1)}%</text>
      <Sector cx={cx} cy={cy} innerRadius={innerRadius} outerRadius={outerRadius + 5} startAngle={startAngle} endAngle={endAngle} fill={fill} />
    </g>
  );
};

const TrendTip = ({ active, payload, label, metric }) => {
  if (!active || !payload?.length) return null;
  const v = payload[0].value;
  return (
    <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: 4, padding: '8px 12px', boxShadow: 'var(--shadow-pop)' }}>
      <div style={{ fontSize: 11, color: T.textMut, marginBottom: 2, ...NUMS }}>{label}</div>
      <div style={{ fontSize: 13, fontWeight: 500, ...NUMS }}>{metric === 'txns' ? fmtNum(v) : fmtMoney(v)}</div>
    </div>
  );
};

/* Top 10 merchants — ranked list: rank # + name + inline volume bar + txns + % share.
   Bars are scaled to the leader (max) so the #1 bar fills the track; % share is of
   the group total so the percentages sum to 100% across the visible list. */
const TopMerchantList = ({ merchants }) => {
  const max = merchants.reduce((a, m) => Math.max(a, n(m.value)), 0) || 1;
  const total = merchants.reduce((a, m) => a + n(m.value), 0) || 1;
  return (
    <div style={{ display: 'flex', flexDirection: 'column' }}>
      {merchants.map((m, i) => {
        const color = SCHEME_COLORS[i % SCHEME_COLORS.length];
        const barPct = (n(m.value) / max) * 100;
        const sharePct = (n(m.value) / total) * 100;
        return (
          <div key={i} className="ex-seg" style={{
            display: 'grid',
            gridTemplateColumns: '26px 1fr 92px 52px',
            alignItems: 'center', gap: 10,
            padding: '9px 6px',
            borderBottom: i < merchants.length - 1 ? `1px solid ${T.borderLight}` : 'none',
          }}>
            {/* Rank */}
            <span style={{
              fontSize: 12, fontWeight: 500, textAlign: 'center',
              color: i < 3 ? color : T.textMut, ...NUMS,
            }}>{i + 1}</span>

            {/* Name + inline volume bar */}
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
                title={m.name}>{m.name}</div>
              <div style={{ marginTop: 5, height: 4, background: T.subtle, overflow: 'hidden' }}>
                <div style={{ width: `${barPct}%`, height: '100%', background: color }} />
              </div>
            </div>

            {/* Volume + txns */}
            <div style={{ textAlign: 'right', ...NUMS }}>
              <div style={{ fontSize: 13, fontWeight: 500, color: T.text }}>{fmtMoney(m.value, true)}</div>
              <div style={{ fontSize: 11, color: T.textMut, marginTop: 2 }}>{fmtNum(m.txns, true)} txns</div>
            </div>

            {/* % share */}
            <span style={{ textAlign: 'right', fontSize: 12, fontWeight: 500, color: T.textSec, ...NUMS }}>
              {sharePct.toFixed(1)}%
            </span>
          </div>
        );
      })}
    </div>
  );
};

/* Credit vs Debit/Prepaid volume split — donut + legend.
   Data aggregated client-side from the merchant-summaries rows (sum_daily_merchant
   creditVolume / debitPrepaidVolume columns). */
const CardMixChart = ({ mix }) => {
  const data = [
    { name: 'Credit', value: n(mix.credit), color: 'var(--chart-2)' },
    { name: 'Debit / Prepaid', value: n(mix.debitPrepaid), color: 'var(--chart-4)' },
  ].filter(d => d.value > 0);
  const total = data.reduce((a, b) => a + b.value, 0);
  if (total === 0) return <EmptyChart label="No credit/debit split for today" />;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '200px 1fr', gap: 20, alignItems: 'center' }}>
      <div style={{ height: 200 }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={data} dataKey="value" nameKey="name" innerRadius={58} outerRadius={88} paddingAngle={2} animationDuration={700}>
              {data.map((d, i) => <Cell key={i} fill={d.color} stroke="none" />)}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {data.map((d, i) => (
          <div key={i}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13.5, marginBottom: 5 }}>
              <span style={{ width: 11, height: 11, borderRadius: 3, background: d.color, flexShrink: 0 }} />
              <span style={{ fontWeight: 600 }}>{d.name}</span>
              <span style={{ marginLeft: 'auto', fontWeight: 500, ...NUMS }}>{fmtMoney(d.value, true)}</span>
              <span style={{ color: T.textMut, width: 52, textAlign: 'right', ...NUMS }}>
                {((d.value / total) * 100).toFixed(1)}%
              </span>
            </div>
            <div style={{ height: 4, background: T.subtle, overflow: 'hidden' }}>
              <div style={{ width: `${(d.value / total) * 100}%`, height: '100%', background: d.color }} />
            </div>
          </div>
        ))}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6, padding: '9px 12px', background: T.subtle, borderRadius: 4 }}>
          <span style={{ fontSize: 13, color: T.textSec }}>Total split volume</span>
          <span style={{ marginLeft: 'auto', fontSize: 14, fontWeight: 500, ...NUMS }}>{fmtMoney(total)}</span>
        </div>
      </div>
    </div>
  );
};

const RevenueComposition = ({ series }) => {
  const totals = series.reduce((a, s) => ({ msf: a.msf + s.msf, interchange: a.interchange + s.interchange, vat: a.vat + s.vat }), { msf: 0, interchange: 0, vat: 0 });
  const data = [
    { name: 'MSF', value: totals.msf, color: 'var(--chart-1)' },
    { name: 'Interchange', value: totals.interchange, color: 'var(--chart-3)' },
    { name: 'VAT', value: totals.vat, color: 'var(--chart-5)' },
  ].filter(d => d.value > 0);
  const sum = data.reduce((a, b) => a + b.value, 0);
  if (sum === 0) return <EmptyChart label="No fee data" />;
  return (
    <div>
      <div style={{ display: 'flex', height: 8, overflow: 'hidden', marginBottom: 14 }}>
        {data.map((d, i) => <div key={i} title={`${d.name}: ${fmtMoney(d.value)}`} style={{ width: `${(d.value / sum) * 100}%`, background: d.color }} />)}
      </div>
      {data.map((d, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, padding: '7px 0', borderBottom: i < data.length - 1 ? `1px solid ${T.borderLight}` : 'none' }}>
          <span style={{ width: 10, height: 10, borderRadius: 3, background: d.color }} />
          <span style={{ fontWeight: 600 }}>{d.name}</span>
          <span style={{ marginLeft: 'auto', color: T.textSec, ...NUMS }}>{fmtMoney(d.value)}</span>
          <span style={{ color: T.textMut, width: 46, textAlign: 'right', ...NUMS }}>{((d.value / sum) * 100).toFixed(1)}%</span>
        </div>
      ))}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12, padding: '10px 12px', background: T.subtle, borderRadius: 4 }}>
        <span style={{ fontSize: 13, color: T.textSec }}>Total fees</span>
        <span style={{ marginLeft: 'auto', fontSize: 14, fontWeight: 500, ...NUMS }}>{fmtMoney(sum)}</span>
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
  <div style={{ padding: 'var(--space-page, 28px)' }}>
    <div className="ex-skel" style={{ width: 280, height: 30, marginBottom: 22 }} />
    <style>{`.ex-skel{background:${T.subtle};animation:exsh 1.4s ease infinite;border-radius:4px}@keyframes exsh{0%,100%{opacity:1}50%{opacity:.55}}@media (prefers-reduced-motion: reduce){.ex-skel{animation:none}}`}</style>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 16, marginBottom: 16 }}>
      {[...Array(3)].map((_, i) => <div key={i} className="ex-skel" style={{ height: 150 }} />)}
    </div>
    <div className="ex-skel" style={{ height: 84, marginBottom: 16 }} />
    <div style={{ display: 'grid', gridTemplateColumns: '1.7fr 1fr', gap: 16, marginBottom: 16 }}>
      <div className="ex-skel" style={{ height: 360 }} /><div className="ex-skel" style={{ height: 360 }} />
    </div>
  </div>
);

export default ExecutiveDashboard;
