import React, { useState, useEffect, useMemo, useCallback } from 'react';
import api from '../api/axios';
import {
    RefreshCw, TrendingUp, TrendingDown, Receipt, Wallet,
    Percent, BarChart3, CalendarRange, ArrowDownRight, Layers, Globe,
    Sigma, Scale, Zap, Award, AlertTriangle, Download, X, SlidersHorizontal,
} from 'lucide-react';
import {
    Bar, Line, XAxis, YAxis, CartesianGrid,
    Tooltip as ReTooltip, ResponsiveContainer, ReferenceLine, Cell,
    BarChart, ComposedChart, Legend,
} from 'recharts';
import EmptyState from '../components/EmptyState';
import SkeletonLoader from '../components/SkeletonLoader';
import { useAuth } from '../contexts/AuthContext';
import { useTheme } from '../contexts/ThemeContext';
import { createFmt, formatMsf, resolveDecimals } from '../utils/formatters';

/* ════════════════════════════════════════════════════════════════════
   CEO Landing Dashboard — MTD (weeks 1–5) / YTD (month-wise).
   Redesign v2:
   • 4 primary hero tiles (Volume, Net Margin, Net Margin %, Transactions)
     each with an inline sparkline of the period's bucket shape.
   • Secondary metric rail with derived KPIs — all computed client-side
     from the existing /business/ceo-summary payload (no backend change):
       Effective MSF rate  = msf / volume  (blended take rate, %)
       Total fees          = interchange + scheme + ecom
       Fee rates           = each fee / volume, %  (same basis as MSF rate,
                             so cost lines compare directly against take rate)
     NOTE: the `netRevenue` field name is the /business/ceo-summary payload
     key and stays as-is; only the user-facing label reads "Net Margin".
   • Client-side bucket-range filter (From–To week/month) — totals,
     charts, insights, and table recompute over the selected window;
     prior-period delta chips are suppressed while filtered because the
     backend baseline no longer matches the visible window.
   • CSV export of the visible range (KPIs + bucket rows).
   • Insight strip: best & worst bucket by margin, momentum (last two
     complete buckets).
   • Two charts: Volume bars with a Net Margin % line overlay (dual y-axes,
     money left / % right) in a single combined panel, plus MSF composition
     (stacked: net margin / interchange / scheme / PG fee = MSF).
   • Breakdown table with inline margin bars, best/worst tint.
   Data: sum_daily_bank weekly buckets + sum_monthly_bank month rows,
   settlement currency. Tenant currency via createFmt(currencySymbol).
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

/* ─── Chart palette ───
   Each mode is stepped for the surface it renders on (--bg-card: #FFFFFF light,
   #1E293B dark) and validated as a set: lightness band, chroma floor, adjacent
   colour-vision-deficiency separation, and contrast. The MSF-composition keys
   are listed in stacking order — validation covers *adjacent* pairs only, so
   reordering the stack invalidates it. On the light surface schemeFee and
   ecomFee fall below 3:1 against white; the breakdown table below the charts
   carries those values in text, which is what makes that legal. */
/* Primary series follows the NEXUS teal identity. Cyan-leaning (#0891B2)
   rather than pure teal so it stays clearly separated from the green
   scheme-fee series where the two sit adjacent in the stacked bars. */
const CHART_COLORS = {
    light: { volume: '#0891B2', marginPct: '#1baf7a',
        netRevenue: '#0891B2', interchange: '#eb6834', schemeFee: '#1baf7a', ecomFee: '#eda100' },
    dark: { volume: '#22D3EE', marginPct: '#199e70',
        netRevenue: '#22D3EE', interchange: '#d95926', schemeFee: '#199e70', ecomFee: '#c98500' },
};

/* Both panels of the small-multiple chart must share these so the volume bars
   and the margin line sit on the same x positions. */
const PANEL_MARGIN = { top: 8, right: 12, left: 0, bottom: 0 };
const PANEL_Y_WIDTH = 56;

const deltaPct = (cur, prev) => {
    const c = num(cur), p = num(prev);
    if (!p) return undefined; // no prior-period baseline to compare against
    return ((c - p) / Math.abs(p)) * 100;
};

/* Exact (uncompacted) value for tooltips/titles.
   With a symbol it is MONEY and renders at the tenant's decimal precision
   (3dp for BHD, 2dp for AED/EGP) — it used to be pinned at 2dp, which hid a
   third of a Bahraini fils figure. Without a symbol it is a count. */
const fullNum = (v, sym = '') => {
    if (!sym) return Number(v || 0).toLocaleString('en-US', { maximumFractionDigits: 0 });
    const d = resolveDecimals();
    return sym + ' ' + Number(v || 0).toLocaleString('en-US',
        { minimumFractionDigits: d, maximumFractionDigits: d });
};

const safeDiv = (a, b) => (num(b) === 0 ? 0 : num(a) / num(b));

/* Cost line as a rate on volume — same basis as the blended MSF take rate,
   so the fee columns are directly comparable against it. Scheme/ECOM land in
   the hundredths of a percent, so 2dp would flatten them to 0.05 vs 0.10;
   3dp is the coarsest precision that keeps the columns readable. A bucket
   with no volume has no rate — an em dash, not a misleading 0.000%. */
const ratePct = (fee, volume) =>
    (num(volume) === 0 ? '—' : (safeDiv(fee, volume) * 100).toFixed(3) + '%');
const ratePctTitle = (fee, volume) =>
    (num(volume) === 0 ? 'No volume in this period' : (safeDiv(fee, volume) * 100).toFixed(4) + '% of volume');

/* ─── Delta chip ─── */
const DeltaChip = ({ pct, compareLabel, invert, suffix = '%' }) => {
    if (pct === null) return null; // explicitly suppressed (e.g. filtered view)
    if (pct === undefined) {
        // no prior-period baseline to compare against — neutral dash, not hidden
        return (
            <span title={compareLabel || 'No prior-period data'} style={{
                display: 'inline-flex', alignItems: 'center', gap: 4,
                fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)',
                background: 'var(--bg-subtle, rgba(148,163,184,0.14))',
                borderRadius: 999, padding: '2px 8px', whiteSpace: 'nowrap',
            }}>
                —
            </span>
        );
    }
    const good = invert ? pct <= 0 : pct >= 0;    // for costs, down is good
    const color = good ? '#059669' : '#dc2626';
    const bg = good ? 'rgba(5,150,105,0.10)' : 'rgba(220,38,38,0.10)';
    const Icon = pct >= 0 ? TrendingUp : TrendingDown;
    return (
        <span title={compareLabel} style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            fontSize: 12, fontWeight: 600, color, background: bg,
            borderRadius: 999, padding: '2px 8px', whiteSpace: 'nowrap',
        }}>
            <Icon size={12} />
            {(pct >= 0 ? '+' : '') + pct.toFixed(1)}{suffix}
        </span>
    );
};

/* ─── Inline sparkline — the period's shape inside the hero tile ─── */
const Sparkline = ({ points, color, height = 34, width = 120 }) => {
    if (!points || points.length < 2) return <div style={{ height }} />;
    const vals = points.map(num);
    const min = Math.min(...vals, 0);
    const max = Math.max(...vals);
    const range = max - min || 1;
    const stepX = width / (vals.length - 1);
    const y = (v) => height - 3 - ((v - min) / range) * (height - 6);
    const line = vals.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * stepX).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    const area = `${line} L${width},${height} L0,${height} Z`;
    const gid = `sp-${color.replace('#', '')}`;
    return (
        <svg width={width} height={height} style={{ display: 'block', overflow: 'visible' }} aria-hidden="true">
            <defs>
                <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity="0.22" />
                    <stop offset="100%" stopColor={color} stopOpacity="0" />
                </linearGradient>
            </defs>
            <path d={area} fill={`url(#${gid})`} />
            <path d={line} fill="none" stroke={color} strokeWidth="1.8"
                strokeLinecap="round" strokeLinejoin="round" />
            <circle cx={(vals.length - 1) * stepX} cy={y(vals[vals.length - 1])} r="2.6" fill={color} />
        </svg>
    );
};

/* ─── Primary hero tile — big value + sparkline ─── */
const HeroTile = ({ label, value, fullValue, deltaPct: dp, deltaSuffix, compareLabel, invertDelta,
    icon: Icon, accent, spark, sub }) => (
    <div className="hero-tile" style={{
        position: 'relative', overflow: 'hidden',
        background: 'var(--bg-card)', border: '1px solid var(--border)',
        borderRadius: 16, padding: '20px 20px 14px', minWidth: 0,
        display: 'flex', flexDirection: 'column', gap: 8,
        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))',
        transition: 'box-shadow 0.18s ease, transform 0.18s ease',
    }}>
        <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 3,
            background: accent, opacity: 0.9 }} />
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 11.5, fontWeight: 700, letterSpacing: '0.07em',
                textTransform: 'uppercase', color: 'var(--text-secondary)' }}>{label}</span>
            <span style={{ display: 'inline-flex', padding: 6, borderRadius: 9,
                background: accent + '15' }}>
                <Icon size={15} style={{ color: accent }} />
            </span>
        </div>
        <div title={fullValue} style={{
            fontSize: 30, fontWeight: 750, color: 'var(--text)',
            fontVariantNumeric: 'tabular-nums', lineHeight: 1.05, letterSpacing: '-0.02em',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>{value}</div>
        <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 10 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minHeight: 38, justifyContent: 'flex-end' }}>
                <DeltaChip pct={dp} compareLabel={compareLabel} invert={invertDelta} suffix={deltaSuffix} />
                {sub && <span style={{ fontSize: 11.5, color: 'var(--text-muted, var(--text-secondary))' }}>{sub}</span>}
            </div>
            <Sparkline points={spark} color={accent} />
        </div>
    </div>
);

/* ─── Secondary metric cell (hairline rail) ─── */
const RailMetric = ({ label, value, fullValue, sub, subTitle, deltaPct: dp, compareLabel, invertDelta, icon: Icon, hint }) => (
    <div style={{ padding: '14px 18px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
        <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '0.06em',
            textTransform: 'uppercase', color: 'var(--text-secondary)',
            display: 'inline-flex', alignItems: 'center', gap: 5 }} title={hint}>
            <Icon size={12} style={{ opacity: 0.65 }} />{label}
        </span>
        <span title={fullValue} style={{ fontSize: 17.5, fontWeight: 700, color: 'var(--text)',
            fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.01em',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{value}</span>
        {sub && (
            <span title={subTitle} style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--text-secondary)',
                fontVariantNumeric: 'tabular-nums', marginTop: -3,
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{sub}</span>
        )}
        <div style={{ minHeight: 18 }}>
            <DeltaChip pct={dp} compareLabel={compareLabel} invert={invertDelta} />
        </div>
    </div>
);

/* ─── Insight pill ─── */
const InsightPill = ({ icon: Icon, tone, title, value }) => {
    const tones = {
        good: { c: '#059669', bg: 'rgba(5,150,105,0.08)' },
        bad: { c: '#dc2626', bg: 'rgba(220,38,38,0.07)' },
        info: { c: 'var(--brand, #0D9488)', bg: 'rgba(13,148,136,0.08)' },
    };
    const t = tones[tone] || tones.info;
    return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
            borderRadius: 12, background: t.bg, minWidth: 0 }}>
            <Icon size={16} style={{ color: t.c, flexShrink: 0 }} />
            <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '0.05em',
                    textTransform: 'uppercase', color: 'var(--text-secondary)' }}>{title}</div>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text)',
                    fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
                    overflow: 'hidden', textOverflow: 'ellipsis' }}>{value}</div>
            </div>
        </div>
    );
};

/* ─── Recharts tooltip ─── */
const BucketTooltip = ({ active, payload, label, fmt }) => {
    if (!active || !payload || !payload.length) return null;
    const d = payload[0].payload;
    return (
        <div style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: 10, padding: '11px 14px',
            boxShadow: 'var(--shadow-md, 0 4px 12px rgba(16,24,40,0.10))',
            fontSize: 12.5, color: 'var(--text)', minWidth: 210,
        }}>
            <div style={{ fontWeight: 700, marginBottom: 7 }}>
                {label}{d.partial ? ' · partial' : ''}
            </div>
            {[
                ['Volume', fmt.currency(d.volume)],
                ['Transactions', num(d.txns).toLocaleString()],
                ['Avg Ticket', fmt.currency(d.avgTicket)],
                ['MSF', fmt.currency(d.msf)],
                ['Interchange', fmt.currency(d.interchange)],
                ['Scheme Fee', fmt.currency(d.schemeFee)],
                ['PG Fee', fmt.currency(d.ecomFee)],
                ['Net Margin', fmt.currency(d.netRevenue)],
                ['Net Margin %', `${num(d.marginPct).toFixed(2)}%`],
            ].map(([k, v]) => (
                <div key={k} style={{ display: 'flex', justifyContent: 'space-between', gap: 18, padding: '1.5px 0' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>{k}</span>
                    <span style={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>{v}</span>
                </div>
            ))}
        </div>
    );
};

const CompositionTooltip = ({ active, payload, label, fmt }) => {
    if (!active || !payload || !payload.length) return null;
    return (
        <div style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: 10, padding: '10px 14px',
            boxShadow: 'var(--shadow-md, 0 4px 12px rgba(16,24,40,0.10))',
            fontSize: 12.5, color: 'var(--text)', minWidth: 190,
        }}>
            <div style={{ fontWeight: 700, marginBottom: 6 }}>{label}</div>
            {payload.map((p) => (
                <div key={p.dataKey} style={{ display: 'flex', justifyContent: 'space-between', gap: 16, padding: '1.5px 0' }}>
                    <span style={{ color: p.color }}>{p.name}</span>
                    <span style={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>{fmt.currency(p.value)}</span>
                </div>
            ))}
        </div>
    );
};

/* ─── Chart card wrapper ─── */
const ChartCard = ({ title, subtitle, children, footer }) => (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
        borderRadius: 16, padding: '18px 18px 8px', minWidth: 0,
        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))' }}>
        <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--text)', marginBottom: 12 }}>
            {title}
            {subtitle && <span style={{ fontWeight: 500, color: 'var(--text-secondary)' }}> · {subtitle}</span>}
        </div>
        {children}
        {footer}
    </div>
);

const Dashboard = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const { isDark } = useTheme();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const C = CHART_COLORS[isDark ? 'dark' : 'light'];

    const [mode, setMode] = useState('MTD');
    // Bucket-range filter (client-side): indices into the loaded buckets.
    // to === -1 means "through the last bucket".
    const [range, setRange] = useState({ from: 0, to: -1 });
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    const load = useCallback(async () => {
        setLoading(true); setError(null);
        try {
            const res = await api.get('/business/ceo-summary');
            setData(res.data);
        } catch (e) {
            setError(e?.response?.data?.message || 'Failed to load summary');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load, tenantVersion]);
    useEffect(() => { setRange({ from: 0, to: -1 }); }, [mode, data]);

    const period = mode === 'MTD' ? data?.mtd : data?.ytd;
    const buckets = mode === 'MTD' ? (data?.mtd?.weeks || []) : (data?.ytd?.months || []);
    const totals = period?.totals;
    const prev = period?.prev;
    const compareLabel = mode === 'MTD'
        ? 'vs last month, same days elapsed'
        : 'vs last year, same period';

    const chartData = useMemo(() => buckets.map(b => ({
        ...b,
        volume: num(b.volume),
        msf: num(b.msf),
        interchange: num(b.interchange),
        schemeFee: num(b.schemeFee),
        ecomFee: num(b.ecomFee),
        netRevenue: num(b.netRevenue),
        avgTicket: num(b.avgTicket),
        marginPct: num(b.marginPct),
        txns: num(b.txns),
    })), [buckets]);

    /* ── Apply client-side bucket-range filter ── */
    const lastIdx = chartData.length - 1;
    const fromIdx = Math.min(Math.max(range.from, 0), Math.max(lastIdx, 0));
    const toIdx = range.to === -1 ? lastIdx : Math.min(Math.max(range.to, fromIdx), Math.max(lastIdx, 0));
    const isFiltered = chartData.length > 0 && (fromIdx > 0 || toIdx < lastIdx);
    const viewData = useMemo(
        () => chartData.slice(fromIdx, toIdx + 1),
        [chartData, fromIdx, toIdx]);

    const [bestIdx, worstIdx] = useMemo(() => {
        let bi = -1, wi = -1, bv = -Infinity, wv = Infinity;
        viewData.forEach((b, i) => {
            if (b.volume <= 0) return;
            if (b.marginPct > bv) { bv = b.marginPct; bi = i; }
            if (b.marginPct < wv) { wv = b.marginPct; wi = i; }
        });
        return viewData.filter(b => b.volume > 0).length > 1 ? [bi, wi] : [-1, -1];
    }, [viewData]);

    /* ── Totals for the visible range. Unfiltered = backend totals;
       filtered = recomputed from the selected buckets. ── */
    const viewTotals = useMemo(() => {
        if (!isFiltered) return totals;
        const t = viewData.reduce((a, b) => ({
            txns: a.txns + b.txns, volume: a.volume + b.volume, msf: a.msf + b.msf,
            interchange: a.interchange + b.interchange, schemeFee: a.schemeFee + b.schemeFee,
            ecomFee: a.ecomFee + b.ecomFee, netRevenue: a.netRevenue + b.netRevenue,
        }), { txns: 0, volume: 0, msf: 0, interchange: 0, schemeFee: 0, ecomFee: 0, netRevenue: 0 });
        return {
            ...t,
            avgTicket: safeDiv(t.volume, t.txns),
            marginPct: safeDiv(t.netRevenue, t.volume) * 100,
        };
    }, [isFiltered, totals, viewData]);

    // Prior-period deltas only make sense for the full period — suppress
    // the chips while a sub-range filter is active (the baseline no longer
    // matches the visible window).
    const dpg = useCallback((cur, prv) => (isFiltered ? null : deltaPct(cur, prv)), [isFiltered]);

    /* ── Derived KPIs (client-side, from existing payload) ── */
    const derived = useMemo(() => {
        const totals = viewTotals;
        if (!totals) return null;
        const fees = num(totals.interchange) + num(totals.schemeFee) + num(totals.ecomFee);
        const prevFees = prev ? num(prev.interchange) + num(prev.schemeFee) + num(prev.ecomFee) : null;
        const msfRate = safeDiv(totals.msf, totals.volume) * 100;                 // blended take rate %
        const prevMsfRate = prev ? safeDiv(prev.msf, prev.volume) * 100 : null;
        // Each fee as a rate on volume, on the same basis as msfRate, so the
        // cost lines are directly comparable against the blended take rate.
        const interchangeRate = safeDiv(totals.interchange, totals.volume) * 100;
        const schemeRate = safeDiv(totals.schemeFee, totals.volume) * 100;
        const ecomRate = safeDiv(totals.ecomFee, totals.volume) * 100;
        const feesRate = safeDiv(fees, totals.volume) * 100;
        return {
            fees, prevFees,
            msfRate, prevMsfRate,
            interchangeRate, schemeRate, ecomRate, feesRate,
        };
    }, [viewTotals, prev]);

    /* ── Momentum: last two complete (non-partial, volume>0) buckets ── */
    const momentum = useMemo(() => {
        const complete = viewData.filter(b => !b.partial && b.volume > 0);
        if (complete.length < 2) return null;
        const last = complete[complete.length - 1];
        const before = complete[complete.length - 2];
        return { pct: deltaPct(last.volume, before.volume), last: last.label, before: before.label };
    }, [viewData]);

    const sparks = useMemo(() => ({
        volume: viewData.map(b => b.volume),
        netRevenue: viewData.map(b => b.netRevenue),
        marginPct: viewData.map(b => b.marginPct),
        txns: viewData.map(b => b.txns),
    }), [viewData]);

    const runRate = data?.mtd?.runRate;

    /* ── CSV export of the visible range (KPIs + bucket rows) ── */
    const exportCsv = useCallback(() => {
        const t = viewTotals;
        if (!t) return;
        const esc = (v) => {
            const x = v == null ? '' : String(v);
            return /[",\n\r]/.test(x) ? '"' + x.replace(/"/g, '""') + '"' : x;
        };
        const lines = [];
        lines.push(['Executive Summary', mode].map(esc).join(','));
        lines.push(['Period', (mode === 'MTD' ? data?.mtd?.label : data?.ytd?.label) || ''].map(esc).join(','));
        if (data?.effectiveDate) lines.push(['Through', data.effectiveDate].map(esc).join(','));
        if (isFiltered && viewData.length) lines.push(['Filter', `${viewData[0].label} to ${viewData[viewData.length - 1].label}`].map(esc).join(','));
        lines.push(['Currency', currencyCode || currencySymbol || 'UNKNOWN'].map(esc).join(','));
        lines.push('');
        // Money columns are written at the tenant's precision (3dp for BHD), not
        // a hardcoded 2dp; MSF keeps its reconciliation digits. Percentages below
        // are unaffected.
        const dp = resolveDecimals(currencyDecimals, currencyCode);
        const msfDp = Math.max(4, dp);
        const heads = [mode === 'MTD' ? 'Week' : 'Month', 'Transactions', 'Volume', 'Avg Ticket',
            'MSF', 'Interchange', 'Interchange % Vol', 'Scheme Fee', 'Scheme % Vol',
            'PG Fee', 'Net Margin', 'Net Margin %'];
        lines.push(heads.map(esc).join(','));
        // Rate columns mirror the on-screen table; exported at 4dp because a
        // spreadsheet has no tooltip to fall back on.
        const rate = (fee, volume) => (num(volume) === 0 ? '' : (safeDiv(fee, volume) * 100).toFixed(4));
        const row = (label, b) => [label, num(b.txns), num(b.volume).toFixed(dp), num(b.avgTicket).toFixed(dp),
            num(b.msf).toFixed(msfDp), num(b.interchange).toFixed(dp), rate(b.interchange, b.volume),
            num(b.schemeFee).toFixed(dp), rate(b.schemeFee, b.volume),
            num(b.ecomFee).toFixed(dp),
            num(b.netRevenue).toFixed(dp), num(b.marginPct).toFixed(2)]
            .map(esc).join(',');
        viewData.forEach(b => lines.push(row(b.label + (b.partial ? ' (partial)' : ''), b)));
        lines.push(row(`${mode} Total`, t));
        if (derived) {
            lines.push('');
            lines.push(['MSF Rate %', derived.msfRate.toFixed(4)].map(esc).join(','));
            lines.push(['Interchange % of Volume', derived.interchangeRate.toFixed(4)].map(esc).join(','));
            lines.push(['Scheme Fee % of Volume', derived.schemeRate.toFixed(4)].map(esc).join(','));
            lines.push(['Total Fees', derived.fees.toFixed(dp)].map(esc).join(','));
            lines.push(['Total Fees % of Volume', derived.feesRate.toFixed(4)].map(esc).join(','));
        }
        const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `executive_summary_${mode.toLowerCase()}_${data?.effectiveDate || 'export'}.csv`;
        a.click();
        URL.revokeObjectURL(a.href);
    }, [viewTotals, viewData, derived, mode, data, currencySymbol, currencyCode, currencyDecimals, isFiltered]);

    if (loading) return <SkeletonLoader type="dashboard" />;

    if (error) return (
        <div style={{ padding: 32 }}>
            <EmptyState title="Could not load dashboard" description={error}
                action={<button className="btn btn-primary" onClick={load}>Retry</button>} />
        </div>
    );

    const hasData = totals && num(totals.txns) > 0;
    const vt = viewTotals || totals;

    /* Money columns carry the tenant currency in the header (once) instead of
       on every cell — a 13-column table repeating "BHD" 7 times per row is
       noise. `ccy: true` renders the code next to the label; when the tenant
       currency is unknown the suffix is simply omitted (never invented). */
    const headCcy = currencyCode || currencySymbol || null;
    const TABLE_HEADS = [
        { label: mode === 'MTD' ? 'Week' : 'Month' },
        { label: 'Transactions' },
        { label: 'Volume', ccy: true },
        { label: 'Avg Ticket', ccy: true },
        { label: 'MSF', ccy: true },
        { label: 'Interchange', ccy: true },
        { label: 'Scheme Fee', ccy: true },
        { label: 'PG Fee', ccy: true },
        { label: 'Net Margin', ccy: true },
        { label: 'Net Margin %' },
    ];

    const maxAbsMargin = Math.max(...viewData.map(b => Math.abs(b.marginPct)), 0.0001);

    return (
        <div style={{ padding: '24px 28px', width: '100%' }}>
            <style>{`
                .hero-tile:hover { box-shadow: var(--shadow-md, 0 4px 14px rgba(16,24,40,0.09)); transform: translateY(-1px); }
                @media (prefers-reduced-motion: reduce) { .hero-tile { transition: none; } .hero-tile:hover { transform: none; } }
                .rail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); }
                .rail-grid > div + div { border-left: 1px solid var(--border-light, var(--border)); }
                @media (max-width: 900px) { .rail-grid > div + div { border-left: none; border-top: 1px solid var(--border-light, var(--border)); } }
                .exec-row:hover { background: var(--bg-hover, rgba(148,163,184,0.05)); }
            `}</style>

            {/* ── Header ── */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                flexWrap: 'wrap', gap: 14, marginBottom: 20 }}>
                <div>
                    <h1 style={{ margin: 0, fontSize: 22, fontWeight: 750, color: 'var(--text)',
                        letterSpacing: '-0.015em' }}>
                        Executive Summary
                    </h1>
                    <div style={{ marginTop: 5, fontSize: 12.5, color: 'var(--text-secondary)',
                        display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                        <CalendarRange size={13} />
                        {mode === 'MTD' ? (data?.mtd?.label || '') : (data?.ytd?.label || '')}
                        {data?.effectiveDate ? ` · through ${data.effectiveDate}` : ''}
                        <span style={{ color: 'var(--border)' }}>·</span>
                        settlement currency
                        {isFiltered && viewData.length > 0 && (
                            <span style={{ marginLeft: 4, fontSize: 11, fontWeight: 700,
                                color: 'var(--brand, #0D9488)', background: 'rgba(13,148,136,0.10)',
                                borderRadius: 999, padding: '2px 9px' }}>
                                {viewData[0].label} – {viewData[viewData.length - 1].label}
                            </span>
                        )}
                    </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                    {/* Bucket-range filter (client-side) */}
                    {chartData.length > 1 && (
                        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                            background: 'var(--bg-card)', border: '1px solid var(--border)',
                            borderRadius: 10, padding: '5px 10px', fontSize: 12.5,
                            color: 'var(--text-secondary)' }}>
                            <SlidersHorizontal size={13} />
                            <select value={fromIdx} aria-label={`From ${mode === 'MTD' ? 'week' : 'month'}`}
                                onChange={(e) => {
                                    const f = Number(e.target.value);
                                    setRange(r => ({ from: f, to: r.to === -1 ? -1 : Math.max(r.to, f) }));
                                }}
                                style={selStyle}>
                                {chartData.map((b, i) => <option key={b.label} value={i}>{b.label}</option>)}
                            </select>
                            <span>–</span>
                            <select value={toIdx} aria-label={`To ${mode === 'MTD' ? 'week' : 'month'}`}
                                onChange={(e) => {
                                    const t = Number(e.target.value);
                                    setRange(r => ({ from: Math.min(r.from, t), to: t }));
                                }}
                                style={selStyle}>
                                {chartData.map((b, i) => <option key={b.label} value={i}>{b.label}</option>)}
                            </select>
                            {isFiltered && (
                                <button onClick={() => setRange({ from: 0, to: -1 })} title="Clear filter"
                                    style={{ border: 'none', background: 'transparent', cursor: 'pointer',
                                        display: 'flex', padding: 2, color: 'var(--text-secondary)' }}>
                                    <X size={13} />
                                </button>
                            )}
                        </div>
                    )}

                    <div style={{ display: 'inline-flex', background: 'var(--bg-card)',
                        border: '1px solid var(--border)', borderRadius: 10, padding: 3 }}>
                        {['MTD', 'YTD'].map(m => (
                            <button key={m} onClick={() => setMode(m)} style={{
                                border: 'none', cursor: 'pointer', borderRadius: 8,
                                padding: '6px 18px', fontSize: 13, fontWeight: 600,
                                background: mode === m ? 'var(--brand, #0D9488)' : 'transparent',
                                color: mode === m ? '#fff' : 'var(--text-secondary)',
                                transition: 'background 0.15s, color 0.15s',
                            }}>{m}</button>
                        ))}
                    </div>
                    <button onClick={exportCsv} title="Export CSV (respects filter)" disabled={!hasData} style={{
                        border: '1px solid var(--border)', background: 'var(--bg-card)',
                        borderRadius: 10, padding: '7px 14px', cursor: hasData ? 'pointer' : 'not-allowed',
                        color: 'var(--text)', display: 'inline-flex', alignItems: 'center', gap: 7,
                        fontSize: 13, fontWeight: 600, opacity: hasData ? 1 : 0.5,
                    }}>
                        <Download size={14} /> Export
                    </button>
                    <button onClick={load} title="Refresh" style={{
                        border: '1px solid var(--border)', background: 'var(--bg-card)',
                        borderRadius: 10, padding: 8, cursor: 'pointer',
                        color: 'var(--text-secondary)', display: 'flex',
                    }}>
                        <RefreshCw size={15} />
                    </button>
                </div>
            </div>

            {!hasData ? (
                <EmptyState title={`No ${mode} data`}
                    description="No transactions found for this period yet. Upload data to populate the dashboard." />
            ) : (
                <>
                    {/* ── Primary hero band (4 tiles, sparkline shape) ── */}
                    <div style={{ display: 'grid', gap: 14, marginBottom: 14,
                        gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))' }}>
                        <HeroTile label="Volume" icon={BarChart3} accent="#0D9488"
                            value={fmt.currency(num(vt.volume))}
                            fullValue={fullNum(vt.volume, currencySymbol)}
                            deltaPct={dpg(vt.volume, prev?.volume)} compareLabel={compareLabel}
                            spark={sparks.volume}
                            sub={`${num(vt.txns).toLocaleString()} transactions`} />
                        <HeroTile label="Net Margin" icon={TrendingUp} accent="#10b981"
                            value={fmt.currency(num(vt.netRevenue))}
                            fullValue={fullNum(vt.netRevenue, currencySymbol)}
                            deltaPct={dpg(vt.netRevenue, prev?.netRevenue)} compareLabel={compareLabel}
                            spark={sparks.netRevenue} />
                        <HeroTile label="Net Margin %" icon={Percent} accent="#ef4444"
                            value={`${num(vt.marginPct).toFixed(2)}%`}
                            fullValue={`${num(vt.marginPct).toFixed(4)}% of volume`}
                            deltaPct={isFiltered ? null
                                : (prev && num(prev.marginPct) !== 0
                                    ? num(vt.marginPct) - num(prev.marginPct) : undefined)}
                            deltaSuffix="" compareLabel={compareLabel}
                            spark={sparks.marginPct}
                            sub="net margin / volume" />
                        <HeroTile label="Transactions" icon={Receipt} accent="#8b5cf6"
                            value={fmt.number(num(vt.txns))}
                            fullValue={fullNum(vt.txns)}
                            deltaPct={dpg(vt.txns, prev?.txns)} compareLabel={compareLabel}
                            spark={sparks.txns}
                            sub={`avg ticket ${fmt.currency(num(vt.avgTicket))}`} />
                    </div>

                    {/* ── Secondary metric rail (fees + derived KPIs) ── */}
                    {derived && (
                        <div className="rail-grid" style={{
                            background: 'var(--bg-card)', border: '1px solid var(--border)',
                            borderRadius: 16, marginBottom: 14, overflow: 'hidden',
                            boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))',
                        }}>
                            <RailMetric label="MSF" icon={Wallet}
                                value={fmt.currency(num(vt.msf))}
                                fullValue={formatMsf(vt.msf, currencySymbol)}
                                deltaPct={dpg(vt.msf, prev?.msf)} compareLabel={compareLabel}
                                hint="Merchant service fee billed" />
                            <RailMetric label="MSF Rate" icon={Sigma}
                                value={`${derived.msfRate.toFixed(3)}%`}
                                fullValue={`${derived.msfRate.toFixed(4)}% of volume (blended take rate)`}
                                deltaPct={isFiltered ? null
                                    : (derived.prevMsfRate != null && derived.prevMsfRate !== 0
                                        ? derived.msfRate - derived.prevMsfRate : undefined)}
                                compareLabel={`${compareLabel} (pp change)`}
                                hint="Blended take rate: MSF / volume" />
                            <RailMetric label="Interchange" icon={ArrowDownRight}
                                value={fmt.currency(num(vt.interchange))}
                                fullValue={fullNum(vt.interchange, currencySymbol)}
                                sub={`${derived.interchangeRate.toFixed(3)}%`}
                                subTitle={`${derived.interchangeRate.toFixed(4)}% — interchange / volume`}
                                deltaPct={dpg(vt.interchange, prev?.interchange)}
                                compareLabel={`${compareLabel} · lower is better`} invertDelta
                                hint="Paid to issuers" />
                            <RailMetric label="Scheme Fee" icon={Layers}
                                value={fmt.currency(num(vt.schemeFee))}
                                fullValue={fullNum(vt.schemeFee, currencySymbol)}
                                sub={`${derived.schemeRate.toFixed(3)}%`}
                                subTitle={`${derived.schemeRate.toFixed(4)}% — scheme fee / volume`}
                                deltaPct={dpg(vt.schemeFee, prev?.schemeFee)}
                                compareLabel={`${compareLabel} · lower is better`} invertDelta
                                hint="Paid to card schemes" />
                            <RailMetric label="PG Fee" icon={Globe}
                                value={fmt.currency(num(vt.ecomFee))}
                                fullValue={fullNum(vt.ecomFee, currencySymbol)}
                                deltaPct={dpg(vt.ecomFee, prev?.ecomFee)}
                                compareLabel={`${compareLabel} · lower is better`} invertDelta
                                hint="Payment gateway fees" />
                            <RailMetric label="Total Charges" icon={Scale}
                                value={fmt.currency(derived.fees)}
                                fullValue={fullNum(derived.fees, currencySymbol)}
                                sub={`${derived.feesRate.toFixed(3)}%`}
                                subTitle={`${derived.feesRate.toFixed(4)}% — total fees / volume`}
                                deltaPct={isFiltered ? null
                                    : (derived.prevFees ? deltaPct(derived.fees, derived.prevFees) : undefined)}
                                compareLabel={`${compareLabel} · lower is better`} invertDelta
                                hint="Interchange + scheme + PG fee" />
                        </div>
                    )}

                    {/* ── Insight strip ── */}
                    {(bestIdx >= 0 || momentum || (mode === 'MTD' && runRate && num(runRate.elapsedDays) > 0)) && (
                        <div style={{ display: 'grid', gap: 10, marginBottom: 22,
                            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
                            {bestIdx >= 0 && (
                                <InsightPill icon={Award} tone="good" title="Best margin"
                                    value={`${viewData[bestIdx].label} · ${viewData[bestIdx].marginPct.toFixed(2)}%`} />
                            )}
                            {worstIdx >= 0 && worstIdx !== bestIdx && (
                                <InsightPill icon={AlertTriangle} tone="bad" title="Weakest margin"
                                    value={`${viewData[worstIdx].label} · ${viewData[worstIdx].marginPct.toFixed(2)}%`} />
                            )}
                            {momentum && momentum.pct != null && (
                                <InsightPill icon={Zap} tone={momentum.pct >= 0 ? 'good' : 'bad'}
                                    title={`Momentum · ${momentum.before} → ${momentum.last}`}
                                    value={`${momentum.pct >= 0 ? '+' : ''}${momentum.pct.toFixed(1)}% volume`} />
                            )}
                            {mode === 'MTD' && runRate && num(runRate.elapsedDays) > 0 && (
                                <InsightPill icon={CalendarRange} tone="info"
                                    title={`Run-rate · day ${runRate.elapsedDays}/${runRate.daysInMonth}`}
                                    value={`${fmt.currency(num(runRate.projectedVolume))} vol · ${fmt.currency(num(runRate.projectedNetRevenue))} margin`} />
                            )}
                        </div>
                    )}

                    {/* ── Charts row ── */}
                    <div style={{ display: 'grid', gap: 14, marginBottom: 22,
                        gridTemplateColumns: 'repeat(auto-fit, minmax(420px, 1fr))' }}>
                        <ChartCard
                            title={mode === 'MTD' ? 'Week-by-week' : 'Month-by-month'}
                            subtitle="Volume with Net Margin % overlay"
                            footer={mode === 'MTD' && viewData.some(b => b.partial) && (
                                <div style={{ fontSize: 11.5, color: 'var(--text-secondary)', padding: '4px 2px 8px' }}>
                                    Lighter bar = week in progress.
                                </div>
                            )}>
                            {/* Single combined panel: volume bars on the left (money)
                                axis, Net Margin % line on the right (%) axis. */}
                            <ResponsiveContainer width="100%" height={344}>
                                <ComposedChart data={viewData} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                                    <CartesianGrid stroke="var(--border)" vertical={false} />
                                    <XAxis dataKey="label" tick={{ fontSize: 12, fill: 'var(--text-secondary)' }}
                                        axisLine={false} tickLine={false} />
                                    {/* Money axis — carries the tenant currency. */}
                                    <YAxis yAxisId="vol" tickFormatter={(v) => fmt.currency(v)}
                                        tick={{ fontSize: 11, fill: 'var(--text-secondary)' }}
                                        axisLine={false} tickLine={false} width={PANEL_Y_WIDTH} />
                                    <YAxis yAxisId="pct" orientation="right"
                                        tickFormatter={(v) => `${v.toFixed(1)}%`}
                                        tick={{ fontSize: 11, fill: 'var(--text-secondary)' }}
                                        axisLine={false} tickLine={false} width={48} />
                                    <ReTooltip content={<BucketTooltip fmt={fmt} />}
                                        cursor={{ fill: 'var(--border)', fillOpacity: 0.25 }} />
                                    <Legend wrapperStyle={{ fontSize: 11.5 }} iconType="circle" iconSize={8} />
                                    <Bar yAxisId="vol" dataKey="volume" name="Volume"
                                        radius={[4, 4, 0, 0]} maxBarSize={28}>
                                        {viewData.map((b, i) => (
                                            <Cell key={i} fill={C.volume}
                                                fillOpacity={b.partial ? 0.45 : 1} />
                                        ))}
                                    </Bar>
                                    {mode === 'MTD' && runRate && num(runRate.projectedVolume) > 0 && (
                                        <ReferenceLine yAxisId="vol"
                                            y={num(runRate.projectedVolume) / Math.max(viewData.length, 1)}
                                            stroke="var(--text-secondary)" strokeDasharray="5 4"
                                            label={{ value: 'avg pace', position: 'insideTopRight',
                                                fontSize: 10, fill: 'var(--text-secondary)' }} />
                                    )}
                                    <Line yAxisId="pct" type="monotone" dataKey="marginPct" name="Net Margin %"
                                        stroke={C.marginPct} strokeWidth={2}
                                        dot={{ r: 4, strokeWidth: 2, stroke: 'var(--bg-card)', fill: C.marginPct }}
                                        activeDot={{ r: 5, strokeWidth: 2, stroke: 'var(--bg-card)' }} />
                                </ComposedChart>
                            </ResponsiveContainer>
                        </ChartCard>

                        <ChartCard title="MSF composition"
                            subtitle="Where each period's MSF goes">
                            {/* height matches the two panels + their labels next door */}
                            <ResponsiveContainer width="100%" height={344}>
                                <BarChart data={viewData} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                                    <CartesianGrid stroke="var(--border)" vertical={false} />
                                    <XAxis dataKey="label" tick={{ fontSize: 12, fill: 'var(--text-secondary)' }}
                                        axisLine={false} tickLine={false} />
                                    {/* Money axis (MSF composition) — carries the tenant currency. */}
                                    <YAxis tickFormatter={(v) => fmt.currency(v)}
                                        tick={{ fontSize: 11, fill: 'var(--text-secondary)' }}
                                        axisLine={false} tickLine={false} width={66} />
                                    <ReTooltip content={<CompositionTooltip fmt={fmt} />}
                                        cursor={{ fill: 'var(--border)', fillOpacity: 0.25 }} />
                                    <Legend wrapperStyle={{ fontSize: 11.5 }} iconType="circle" iconSize={8} />
                                    {/* stroke is the surface colour: it reads as a 2px gap
                                        between segments, not as an outline. */}
                                    <Bar dataKey="netRevenue" name="Net Margin" stackId="c"
                                        fill={C.netRevenue} maxBarSize={30}
                                        stroke="var(--bg-card)" strokeWidth={2} />
                                    <Bar dataKey="interchange" name="Interchange" stackId="c"
                                        fill={C.interchange} maxBarSize={30}
                                        stroke="var(--bg-card)" strokeWidth={2} />
                                    <Bar dataKey="schemeFee" name="Scheme Fee" stackId="c"
                                        fill={C.schemeFee} maxBarSize={30}
                                        stroke="var(--bg-card)" strokeWidth={2} />
                                    <Bar dataKey="ecomFee" name="PG Fee" stackId="c"
                                        fill={C.ecomFee} maxBarSize={30} radius={[4, 4, 0, 0]}
                                        stroke="var(--bg-card)" strokeWidth={2} />
                                </BarChart>
                            </ResponsiveContainer>
                        </ChartCard>
                    </div>

                    {/* ── Breakdown table ── */}
                    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
                        borderRadius: 16, overflow: 'hidden',
                        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(16,24,40,0.04))' }}>
                        <div style={{ overflowX: 'auto' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                                <thead>
                                    <tr style={{ borderBottom: '1px solid var(--border)' }}>
                                        {TABLE_HEADS.map((h, i) => (
                                            <th key={h.label} style={{
                                                textAlign: i === 0 ? 'left' : 'right',
                                                padding: '12px 16px', fontSize: 11, fontWeight: 600,
                                                letterSpacing: '0.05em', textTransform: 'uppercase',
                                                color: 'var(--text-secondary)', whiteSpace: 'nowrap',
                                            }}>
                                                {h.label}
                                                {h.ccy && headCcy && (
                                                    <span style={{ marginLeft: 5, fontWeight: 500, opacity: 0.75 }}>
                                                        ({headCcy})
                                                    </span>
                                                )}
                                            </th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {viewData.map((b, i) => {
                                        const tint = i === bestIdx ? 'rgba(5,150,105,0.06)'
                                            : i === worstIdx ? 'rgba(220,38,38,0.05)' : 'transparent';
                                        const marginW = Math.min(Math.abs(b.marginPct) / maxAbsMargin, 1) * 100;
                                        return (
                                            <tr key={b.label} className="exec-row" style={{
                                                borderBottom: '1px solid var(--border)', background: tint,
                                            }}>
                                                <td style={{ padding: '11px 16px', fontWeight: 600, color: 'var(--text)', whiteSpace: 'nowrap' }}>
                                                    {b.label}
                                                    {b.partial && <span style={{
                                                        marginLeft: 8, fontSize: 10.5, fontWeight: 600,
                                                        color: '#b45309', background: 'rgba(180,83,9,0.10)',
                                                        borderRadius: 999, padding: '1px 7px',
                                                    }}>partial</span>}
                                                    {mode === 'MTD' && b.from && (
                                                        <span style={{ marginLeft: 8, fontSize: 11, color: 'var(--text-secondary)' }}>
                                                            {fmt.date(b.from)}–{fmt.date(b.to)}
                                                        </span>
                                                    )}
                                                </td>
                                                <td style={tdNum} title={fullNum(b.txns)}>{num(b.txns).toLocaleString()}</td>
                                                <td style={tdNum} title={fullNum(b.volume, currencySymbol)}>{fmt.amount(b.volume)}</td>
                                                <td style={tdNum} title={fullNum(b.avgTicket, currencySymbol)}>{fmt.amount(b.avgTicket)}</td>
                                                <td style={tdNum} title={formatMsf(b.msf, currencySymbol)}>{fmt.amount(b.msf)}</td>
                                                <td style={tdNum} title={`${fullNum(b.interchange, currencySymbol)} · ${ratePctTitle(b.interchange, b.volume)}`}>
                                                    {fmt.amount(b.interchange)}
                                                    <span style={rateInline}>({ratePct(b.interchange, b.volume)})</span>
                                                </td>
                                                <td style={tdNum} title={`${fullNum(b.schemeFee, currencySymbol)} · ${ratePctTitle(b.schemeFee, b.volume)}`}>
                                                    {fmt.amount(b.schemeFee)}
                                                    <span style={rateInline}>({ratePct(b.schemeFee, b.volume)})</span>
                                                </td>
                                                <td style={tdNum} title={fullNum(b.ecomFee, currencySymbol)}>{fmt.amount(b.ecomFee)}</td>
                                                <td style={{ ...tdNum, fontWeight: 600,
                                                    color: b.netRevenue >= 0 ? 'var(--text)' : '#dc2626' }}
                                                    title={fullNum(b.netRevenue, currencySymbol)}>{fmt.amount(b.netRevenue)}</td>
                                                <td style={{ ...tdNum, fontWeight: 700,
                                                    color: b.marginPct >= 0 ? '#059669' : '#dc2626' }}>
                                                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, justifyContent: 'flex-end' }}>
                                                        <span style={{ width: 44, height: 4, borderRadius: 999,
                                                            background: 'var(--bg-subtle, rgba(148,163,184,0.18))',
                                                            overflow: 'hidden', display: 'inline-block' }}>
                                                            <span style={{ display: 'block', height: '100%',
                                                                width: `${marginW}%`, borderRadius: 999,
                                                                background: b.marginPct >= 0 ? '#10b981' : '#ef4444' }} />
                                                        </span>
                                                        {b.marginPct.toFixed(2)}%
                                                    </span>
                                                </td>
                                            </tr>
                                        );
                                    })}
                                    <tr style={{ background: 'var(--bg-hover, rgba(148,163,184,0.06))',
                                        borderTop: '2px solid var(--border)' }}>
                                        <td style={{ padding: '12px 16px', fontWeight: 700, color: 'var(--text)' }}>
                                            {mode} Total{isFiltered ? ' (filtered)' : ''}
                                        </td>
                                        <td style={tdTotal} title={fullNum(vt.txns)}>{num(vt.txns).toLocaleString()}</td>
                                        <td style={tdTotal} title={fullNum(vt.volume, currencySymbol)}>{fmt.amount(num(vt.volume))}</td>
                                        <td style={tdTotal} title={fullNum(vt.avgTicket, currencySymbol)}>{fmt.amount(num(vt.avgTicket))}</td>
                                        <td style={tdTotal} title={formatMsf(vt.msf, currencySymbol)}>{fmt.amount(num(vt.msf))}</td>
                                        <td style={tdTotal} title={`${fullNum(vt.interchange, currencySymbol)} · ${ratePctTitle(vt.interchange, vt.volume)}`}>
                                            {fmt.amount(num(vt.interchange))}
                                            <span style={rateInline}>({ratePct(vt.interchange, vt.volume)})</span>
                                        </td>
                                        <td style={tdTotal} title={`${fullNum(vt.schemeFee, currencySymbol)} · ${ratePctTitle(vt.schemeFee, vt.volume)}`}>
                                            {fmt.amount(num(vt.schemeFee))}
                                            <span style={rateInline}>({ratePct(vt.schemeFee, vt.volume)})</span>
                                        </td>
                                        <td style={tdTotal} title={fullNum(vt.ecomFee, currencySymbol)}>{fmt.amount(num(vt.ecomFee))}</td>
                                        <td style={tdTotal} title={fullNum(vt.netRevenue, currencySymbol)}>{fmt.amount(num(vt.netRevenue))}</td>
                                        <td style={{ ...tdTotal,
                                            color: num(vt.marginPct) >= 0 ? '#059669' : '#dc2626' }}>
                                            {num(vt.marginPct).toFixed(2)}%
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
};

const tdNum = {
    padding: '11px 16px', textAlign: 'right', color: 'var(--text)',
    fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
};
/* Rate lives INSIDE the fee cell it derives from — inline after the amount so
   rows stay single-height; no separate % columns. */
const rateInline = {
    fontSize: 10.5, fontWeight: 600, color: 'var(--text-secondary)',
    fontVariantNumeric: 'tabular-nums', marginLeft: 6,
};
const selStyle = {
    border: 'none', background: 'transparent', color: 'var(--text)',
    fontSize: 12.5, fontWeight: 600, cursor: 'pointer', outline: 'none',
    padding: '2px 0',
};
const tdTotal = { ...tdNum, fontWeight: 700 };

export default Dashboard;
