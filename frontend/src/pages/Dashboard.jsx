import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
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
import ChartGradients from '../components/ChartGradients';
import MarginGlossaryHint from '../components/MarginGlossary';
import { useAuth } from '../contexts/AuthContext';
import {
    createFmt, formatMsf, resolveDecimals,
    isUsdDisplay, convertForDisplay, displayCurrencyCode, usdRateInfo,
} from '../utils/formatters';
import {
    SERIES, GRID_PROPS, AXIS_PROPS, LEGEND_PROPS, ANIM, gradientId,
} from '../theme/chartPalette';

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
     money left / % right) in a single combined panel, plus Cost & Margin Mix
     (stacked: interchange / scheme / PG fee costs at the base, net margin
     capping the bar = MSF).
   • Breakdown table with inline margin bars, best/worst tint.
   Data: sum_daily_bank weekly buckets + sum_monthly_bank month rows,
   settlement currency. Tenant currency via createFmt(currencySymbol).
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

/* ─── Chart palette ───
   Colours come from theme/chartPalette (the shared blue system) as CSS custom
   properties, so both schemes are handled by the stylesheet and no mode flag
   is needed here. The Cost & Margin Mix stack gives every segment its own
   hue (validated as a categorical set), so identity survives any stacking
   order — but keep the costs at the base and margin on top: the green cap
   is the "what we keep" read the panel is built around. */
const C = {
    volume:      SERIES.volume,
    marginPct:   SERIES.marginPct,
    netRevenue:  SERIES.netRevenue,
    interchange: SERIES.interchange,
    schemeFee:   SERIES.schemeFee,
    ecomFee:     SERIES.ecomFee,
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
    // Executive display-currency toggle: convert + relabel when USD is active.
    const d = isUsdDisplay(sym) ? 2 : resolveDecimals();
    return displayCurrencyCode(sym) + ' ' + convertForDisplay(v, sym).toLocaleString('en-US',
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
                background: 'var(--bg-subtle)', fontFamily: 'var(--font-mono)',
                borderRadius: 999, padding: '2px 9px', whiteSpace: 'nowrap',
                border: '1px solid var(--border-light)',
            }}>
                —
            </span>
        );
    }
    const good = invert ? pct <= 0 : pct >= 0;    // for costs, down is good
    const color = good ? 'var(--success-text)' : 'var(--danger-text)';
    const bg = good ? 'var(--success-bg)' : 'var(--danger-bg)';
    const Icon = pct >= 0 ? TrendingUp : TrendingDown;
    return (
        <span title={compareLabel} style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            fontSize: 12, fontWeight: 600, color, background: bg,
            fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
            border: `1px solid color-mix(in srgb, ${color} 26%, transparent)`,
            borderRadius: 999, padding: '2px 9px', whiteSpace: 'nowrap',
        }}>
            <Icon size={12} className={pct >= 0 ? 'dx-arrow-up' : 'dx-arrow-down'} />
            {(pct >= 0 ? '+' : '') + pct.toFixed(1)}{suffix}
        </span>
    );
};

/* ─── Inline sparkline — the period's shape inside the hero tile ─── */
const Sparkline = ({ points, color, sparkId, height = 34, width = 120 }) => {
    if (!points || points.length < 2) return <div style={{ height }} />;
    const vals = points.map(num);
    const min = Math.min(...vals, 0);
    const max = Math.max(...vals);
    const range = max - min || 1;
    const stepX = width / (vals.length - 1);
    const y = (v) => height - 3 - ((v - min) / range) * (height - 6);
    const line = vals.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * stepX).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    const area = `${line} L${width},${height} L0,${height} Z`;
    // The colour is a `var(--token)` string, so it cannot go in the id —
    // the caller supplies a stable key instead.
    const gid = `sp-${sparkId}`;
    const lastX = (vals.length - 1) * stepX;
    const lastY = y(vals[vals.length - 1]);
    return (
        <svg width={width} height={height} style={{ display: 'block', overflow: 'visible' }} aria-hidden="true">
            <defs>
                <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity="0.30" />
                    <stop offset="100%" stopColor={color} stopOpacity="0" />
                </linearGradient>
            </defs>
            <path d={area} fill={`url(#${gid})`} />
            <path d={line} fill="none" stroke={color} strokeWidth="1.8"
                strokeLinecap="round" strokeLinejoin="round" />
            {/* Halo + dot on the latest point — the "live" read. */}
            <circle cx={lastX} cy={lastY} r="5" fill={color} opacity="0.18" />
            <circle cx={lastX} cy={lastY} r="2.6" fill={color} />
        </svg>
    );
};

/* ─── Count-up — figures settle into place on load instead of snapping.
   Skipped entirely under prefers-reduced-motion. ─── */
const useCountUp = (target, duration = 900) => {
    const [value, setValue] = useState(target);
    const fromRef = useRef(target);
    useEffect(() => {
        const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
        const to = Number(target) || 0;
        if (reduce || !Number.isFinite(to)) { setValue(to); fromRef.current = to; return undefined; }
        const from = Number(fromRef.current) || 0;
        if (from === to) return undefined;
        let raf;
        const start = performance.now();
        const tick = (now) => {
            const t = Math.min((now - start) / duration, 1);
            const eased = 1 - Math.pow(1 - t, 3);
            setValue(from + (to - from) * eased);
            if (t < 1) raf = requestAnimationFrame(tick);
            else fromRef.current = to;
        };
        raf = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(raf);
    }, [target, duration]);
    return value;
};

/* ─── Primary hero tile — big value + sparkline ───
   `raw` + `format` (rather than a pre-rendered string) so the figure can
   count up on load; `sparkId` keys the gradient because `accent` is now a
   CSS custom property, not a hex. */
const HeroTile = ({ label, raw, format, fullValue, deltaPct: dp, deltaSuffix, compareLabel, invertDelta,
    icon: Icon, accent, spark, sparkId, sub, secondary, index = 0 }) => {
    const shown = useCountUp(raw);
    return (
        <div className="dx-card dx-edge dx-rise hero-tile"
            style={{
                position: 'relative', overflow: 'hidden',
                padding: '20px 20px 14px', minWidth: 0,
                display: 'flex', flexDirection: 'column', gap: 8,
                animationDelay: `${index * 70}ms`,
            }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', position: 'relative' }}>
                <span style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.09em',
                    textTransform: 'uppercase', color: 'var(--text-secondary)' }}>{label}</span>
                <span style={{ display: 'inline-flex', padding: 7, borderRadius: 10,
                    background: `color-mix(in srgb, ${accent} 12%, transparent)`,
                    border: `1px solid color-mix(in srgb, ${accent} 22%, transparent)` }}>
                    <Icon size={15} style={{ color: accent }} />
                </span>
            </div>
            <div title={fullValue} style={{
                fontSize: 30, fontWeight: 650, color: 'var(--text)',
                fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
                lineHeight: 1.05, letterSpacing: '-0.02em', position: 'relative',
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            }}>{format(shown)}</div>
            {/* Optional second reading under the headline (e.g. Net Spread beneath
                Net Margin) — keeps the hero at four tiles instead of orphaning a
                fifth on the next row at laptop widths. */}
            {secondary && (
                <div style={{ marginTop: -2, fontSize: 12.5, color: 'var(--text-secondary)',
                    fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {secondary}
                </div>
            )}
            <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 10, position: 'relative' }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minHeight: 38, justifyContent: 'flex-end' }}>
                    <DeltaChip pct={dp} compareLabel={compareLabel} invert={invertDelta} suffix={deltaSuffix} />
                    {sub && <span style={{ fontSize: 11.5, color: 'var(--text-muted)' }}>{sub}</span>}
                </div>
                <Sparkline points={spark} color={accent} sparkId={sparkId} />
            </div>
        </div>
    );
};

/* ─── Secondary metric cell (hairline rail) ─── */
const RailMetric = ({ label, value, fullValue, sub, subTitle, deltaPct: dp, compareLabel, invertDelta, icon: Icon, hint }) => (
    <div style={{ padding: '14px 18px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
        <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '0.06em',
            textTransform: 'uppercase', color: 'var(--text-secondary)',
            display: 'inline-flex', alignItems: 'center', gap: 5 }} title={hint}>
            <Icon size={12} style={{ opacity: 0.65 }} />{label}
        </span>
        <span title={fullValue} style={{ fontSize: 17.5, fontWeight: 600, color: 'var(--text)',
            fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.01em',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{value}</span>
        {sub && (
            <span title={subTitle} style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--text-secondary)',
                fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', marginTop: -3,
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{sub}</span>
        )}
        <div style={{ minHeight: 18 }}>
            <DeltaChip pct={dp} compareLabel={compareLabel} invert={invertDelta} />
        </div>
    </div>
);

/* ─── Insight pill — tinted gradient wash, stronger at the icon end ─── */
const InsightPill = ({ icon: Icon, tone, title, value }) => {
    const tones = {
        good: { c: 'var(--success-text)', g: 'var(--success)' },
        bad: { c: 'var(--danger-text)', g: 'var(--danger)' },
        info: { c: 'var(--primary)', g: 'var(--primary)' },
    };
    const t = tones[tone] || tones.info;
    return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 14px',
            borderRadius: 'var(--radius-lg)', minWidth: 0,
            background: `linear-gradient(105deg,
                color-mix(in srgb, ${t.g} 22%, var(--bg-card)) 0%,
                color-mix(in srgb, ${t.g} 10%, var(--bg-card)) 45%,
                color-mix(in srgb, ${t.g} 3%, var(--bg-card)) 100%)`,
            border: `1px solid color-mix(in srgb, ${t.c} 20%, transparent)` }}>
            <Icon size={16} style={{ color: t.c, flexShrink: 0 }} />
            <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '0.06em',
                    textTransform: 'uppercase', color: 'var(--text-secondary)' }}>{title}</div>
                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text)',
                    fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
                    overflow: 'hidden', textOverflow: 'ellipsis' }}>{value}</div>
            </div>
        </div>
    );
};

/* ─── Recharts tooltips — frosted panel, mono figures ─── */
const GLASS_TOOLTIP = {
    background: 'var(--glass-bg)',
    backdropFilter: 'var(--glass-blur)',
    WebkitBackdropFilter: 'var(--glass-blur)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-md)',
    padding: '11px 14px',
    boxShadow: 'var(--shadow-pop)',
    fontSize: 12.5, color: 'var(--text)', minWidth: 210,
};

const BucketTooltip = ({ active, payload, label, fmt }) => {
    if (!active || !payload || !payload.length) return null;
    const d = payload[0].payload;
    return (
        <div style={GLASS_TOOLTIP}>
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
                ['DCC (Acquirer)', fmt.currency(d.dccAcquirer)],
                ['Rental', fmt.currency(d.rental)],
                ['Net Spread', fmt.currency(d.netSpread)],
            ].map(([k, v]) => (
                <div key={k} style={{ display: 'flex', justifyContent: 'space-between', gap: 18, padding: '1.5px 0' }}>
                    <span style={{ color: 'var(--text-secondary)' }}>{k}</span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', fontWeight: 500 }}>{v}</span>
                </div>
            ))}
        </div>
    );
};

const CompositionTooltip = ({ active, payload, label, fmt }) => {
    if (!active || !payload || !payload.length) return null;
    return (
        <div style={{ ...GLASS_TOOLTIP, minWidth: 190 }}>
            <div style={{ fontWeight: 700, marginBottom: 6 }}>{label}</div>
            {payload.map((p) => (
                <div key={p.dataKey} style={{ display: 'flex', justifyContent: 'space-between', gap: 16, padding: '1.5px 0', alignItems: 'center' }}>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: 'var(--text-secondary)' }}>
                        <span style={{ width: 8, height: 8, borderRadius: 2, background: p.color, display: 'inline-block' }} />
                        {p.name}
                    </span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', fontWeight: 500 }}>{fmt.currency(p.value)}</span>
                </div>
            ))}
        </div>
    );
};

/* ─── Chart card wrapper ─── */
const ChartCard = ({ title, subtitle, children, footer }) => (
    <div className="dx-card" style={{ padding: '18px 18px 8px', minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            {/* Gradient tick — marks the panel head without a heavy rule. */}
            <span aria-hidden="true" style={{ width: 3, height: 15, borderRadius: 2, background: 'var(--grad-accent)' }} />
            <div style={{ fontSize: 13.5, fontWeight: 700, color: 'var(--text)' }}>
                {title}
                {subtitle && <span style={{ fontWeight: 500, color: 'var(--text-secondary)' }}> · {subtitle}</span>}
            </div>
        </div>
        {children}
        {footer}
    </div>
);

/* Period modes. LAST_YEAR is the complete prior calendar year, month-wise —
   the same bucket shape as YTD, so it reuses the month rendering path. */
const MODES = [
    { key: 'MTD',       label: 'MTD' },
    { key: 'YTD',       label: 'YTD' },
    { key: 'LAST_YEAR', label: 'Last Year' },
];

const Dashboard = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);

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

    const period = mode === 'MTD' ? data?.mtd
        : mode === 'LAST_YEAR' ? data?.lastYear
        : data?.ytd;
    // MTD buckets are weeks; both year modes are months.
    const buckets = mode === 'MTD' ? (data?.mtd?.weeks || []) : (period?.months || []);
    const totals = period?.totals;
    const prev = period?.prev;
    const modeLabel = MODES.find(m => m.key === mode)?.label || mode;
    const compareLabel = mode === 'MTD'
        ? 'vs last month, same days elapsed'
        : mode === 'LAST_YEAR'
            ? 'vs the year before, full year'
            : 'vs last year, same period';

    const chartData = useMemo(() => buckets.map(b => ({
        ...b,
        volume: num(b.volume),
        msf: num(b.msf),
        interchange: num(b.interchange),
        schemeFee: num(b.schemeFee),
        ecomFee: num(b.ecomFee),
        netRevenue: num(b.netRevenue),
        // Net Spread = net margin + DCC acquirer share + rental (server-derived;
        // an older payload without the fields simply reads 0 / = net margin).
        dccAcquirer: num(b.dccAcquirer),
        rental: num(b.rental),
        netSpread: b.netSpread != null ? num(b.netSpread) : num(b.netRevenue) + num(b.dccAcquirer) + num(b.rental),
        spreadPct: num(b.spreadPct),
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
            dccAcquirer: a.dccAcquirer + b.dccAcquirer, rental: a.rental + b.rental,
            netSpread: a.netSpread + b.netSpread,
        }), { txns: 0, volume: 0, msf: 0, interchange: 0, schemeFee: 0, ecomFee: 0, netRevenue: 0,
              dccAcquirer: 0, rental: 0, netSpread: 0 });
        return {
            ...t,
            avgTicket: safeDiv(t.volume, t.txns),
            marginPct: safeDiv(t.netRevenue, t.volume) * 100,
            spreadPct: safeDiv(t.netSpread, t.volume) * 100,
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
        netSpread: viewData.map(b => b.netSpread),
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
        lines.push(['Executive Summary', modeLabel].map(esc).join(','));
        lines.push(['Period', period?.label || ''].map(esc).join(','));
        if (data?.effectiveDate) lines.push(['Through', data.effectiveDate].map(esc).join(','));
        if (isFiltered && viewData.length) lines.push(['Filter', `${viewData[0].label} to ${viewData[viewData.length - 1].label}`].map(esc).join(','));
        lines.push(['Currency', displayCurrencyCode(currencyCode) || currencySymbol || 'UNKNOWN'].map(esc).join(','));
        // When the executive USD toggle is on, money cells below are converted
        // and the file states the indicative rate used.
        const fx = usdRateInfo(currencyCode);
        if (fx) lines.push(['FX Rate', `1 ${fx.base} = ${fx.rate} USD (indicative; as of ${fx.asOf})`].map(esc).join(','));
        lines.push('');
        // Money columns are written at the tenant's precision (3dp for BHD), not
        // a hardcoded 2dp; MSF keeps its reconciliation digits. Percentages below
        // are unaffected (ratios are currency-invariant).
        const dp = isUsdDisplay(currencyCode) ? 2 : resolveDecimals(currencyDecimals, currencyCode);
        const msfDp = Math.max(4, dp);
        const cv = (v) => convertForDisplay(num(v), currencyCode);
        const heads = [mode === 'MTD' ? 'Week' : 'Month', 'Transactions', 'Volume', 'Avg Ticket',
            'MSF', 'Interchange', 'Interchange % Vol', 'Scheme Fee', 'Scheme % Vol',
            'PG Fee', 'Net Margin', 'Net Margin %', 'DCC (Acquirer)', 'Rental', 'Net Spread', 'Net Spread %'];
        lines.push(heads.map(esc).join(','));
        // Rate columns mirror the on-screen table; exported at 4dp because a
        // spreadsheet has no tooltip to fall back on.
        const rate = (fee, volume) => (num(volume) === 0 ? '' : (safeDiv(fee, volume) * 100).toFixed(4));
        const row = (label, b) => [label, num(b.txns), cv(b.volume).toFixed(dp), cv(b.avgTicket).toFixed(dp),
            cv(b.msf).toFixed(msfDp), cv(b.interchange).toFixed(dp), rate(b.interchange, b.volume),
            cv(b.schemeFee).toFixed(dp), rate(b.schemeFee, b.volume),
            cv(b.ecomFee).toFixed(dp),
            cv(b.netRevenue).toFixed(dp), num(b.marginPct).toFixed(2),
            cv(b.dccAcquirer).toFixed(dp), cv(b.rental).toFixed(dp),
            cv(b.netSpread).toFixed(dp), num(b.spreadPct).toFixed(2)]
            .map(esc).join(',');
        viewData.forEach(b => lines.push(row(b.label + (b.partial ? ' (partial)' : ''), b)));
        lines.push(row(`${modeLabel} Total`, t));
        if (derived) {
            lines.push('');
            lines.push(['MSF Rate %', derived.msfRate.toFixed(4)].map(esc).join(','));
            lines.push(['Interchange % of Volume', derived.interchangeRate.toFixed(4)].map(esc).join(','));
            lines.push(['Scheme Fee % of Volume', derived.schemeRate.toFixed(4)].map(esc).join(','));
            lines.push(['Total Fees', cv(derived.fees).toFixed(dp)].map(esc).join(','));
            lines.push(['Total Fees % of Volume', derived.feesRate.toFixed(4)].map(esc).join(','));
        }
        const blob = new Blob(['\ufeff' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `executive_summary_${mode.toLowerCase()}_${data?.effectiveDate || 'export'}.csv`;
        a.click();
        URL.revokeObjectURL(a.href);
    }, [viewTotals, viewData, derived, mode, modeLabel, period, data, currencySymbol, currencyCode, currencyDecimals, isFiltered]);

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
    const headCcy = displayCurrencyCode(currencyCode || currencySymbol) || null;
    const TABLE_HEADS = [
        { label: mode === 'MTD' ? 'Week' : 'Month' },
        { label: 'Transactions' },
        { label: 'Volume', ccy: true },
        { label: 'MSF', ccy: true },
        { label: 'Interchange', ccy: true },
        { label: 'Scheme Fee', ccy: true },
        { label: 'PG Fee', ccy: true },
        { label: 'Net Margin', ccy: true },
        // DCC + rental folded into one column (both are zero on most weeks);
        // the cell's hover carries the split. Net Spread stays last and bold.
        { label: 'Ancillary', ccy: true },
        { label: 'Net Spread', ccy: true },
    ];

    return (
        <div className="exec-lume" style={{ padding: '24px 28px', width: '100%', position: 'relative' }}>
            <style>{`
                .rail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); }
                .rail-grid > div + div { border-left: 1px solid var(--border-light); }
                @media (max-width: 900px) { .rail-grid > div + div { border-left: none; border-top: 1px solid var(--border-light); } }
                .exec-row { transition: background 180ms ease; }
                .exec-row:hover { background: var(--bg-hover); }
                .seg-btn { border: none; cursor: pointer; border-radius: 8; }

                /* ── Executive light pass ──
                   Pure white-light overlays: not a single hue on the page
                   changes, the surfaces are simply *lit*. Two layers:
                   1. a daylight bloom falling from the top of the page, so
                      the canvas brightens toward the headline and KPIs;
                   2. a glass sheen on each card — a crisp lit top edge plus
                      a soft interior glow confined to the upper rim, so the
                      panels read as polished instrument glass, never a veil
                      over the content. Dark mode keeps the same geometry at
                      moonlight intensity. */
                .exec-lume::before {
                    content: ''; position: absolute; inset: 0 0 auto 0; height: 460px;
                    pointer-events: none;
                    background: radial-gradient(72% 100% at 50% 0%,
                        rgba(255,255,255,0.5) 0%, rgba(255,255,255,0.18) 45%, transparent 72%);
                }
                html.dark .exec-lume::before {
                    background: radial-gradient(72% 100% at 50% 0%,
                        rgba(255,255,255,0.05) 0%, transparent 65%);
                }
                .exec-lume .dx-card::after {
                    content: ''; position: absolute; inset: 0; border-radius: inherit;
                    pointer-events: none;
                    box-shadow:
                        inset 0 1px 0 rgba(255,255,255,0.85),
                        inset 0 26px 30px -26px rgba(255,255,255,0.95);
                }
                html.dark .exec-lume .dx-card::after {
                    box-shadow:
                        inset 0 1px 0 rgba(255,255,255,0.14),
                        inset 0 26px 30px -26px rgba(255,255,255,0.10);
                }
            `}</style>

            {/* ── Header ── */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                flexWrap: 'wrap', gap: 14, marginBottom: 20 }}>
                <div>
                    <h1 style={{
                        margin: 0, fontSize: 24, fontWeight: 700,
                        letterSpacing: '-0.02em',
                        // Gradient wordmark — the one place the brand gradient
                        // touches type, so it stays a signature rather than noise.
                        background: 'var(--grad-primary)',
                        WebkitBackgroundClip: 'text', backgroundClip: 'text',
                        WebkitTextFillColor: 'transparent', color: 'var(--text)',
                        width: 'fit-content',
                    }}>
                        Executive Summary
                    </h1>
                    <div style={{ marginTop: 5, fontSize: 12.5, color: 'var(--text-secondary)',
                        display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                        <CalendarRange size={13} />
                        {period?.label || ''}
                        {/* "through <date>" only applies to a period still in
                            progress — Last Year is a closed year. */}
                        {data?.effectiveDate && mode !== 'LAST_YEAR' ? ` · through ${data.effectiveDate}` : ''}
                        <span style={{ color: 'var(--border)' }}>·</span>
                        settlement currency
                        <MarginGlossaryHint compact style={{ marginLeft: 2 }} />
                        {isFiltered && viewData.length > 0 && (
                            <span style={{ marginLeft: 4, fontSize: 11, fontWeight: 700,
                                fontFamily: 'var(--font-mono)',
                                color: 'var(--primary)', background: 'var(--wash)',
                                border: '1px solid color-mix(in srgb, var(--primary) 22%, transparent)',
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
                            background: 'var(--glass-bg)', backdropFilter: 'var(--glass-blur)',
                            WebkitBackdropFilter: 'var(--glass-blur)',
                            border: '1px solid var(--border)', boxShadow: 'var(--shadow-xs)',
                            borderRadius: 'var(--radius-md)', padding: '6px 12px', fontSize: 12.5,
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

                    <div style={{ display: 'inline-flex', background: 'var(--bg-subtle)',
                        border: '1px solid var(--border)', borderRadius: 999, padding: 3 }}>
                        {MODES.map(m => (
                            <button key={m.key} onClick={() => setMode(m.key)} style={{
                                border: 'none', cursor: 'pointer', borderRadius: 999,
                                padding: '6px 16px', fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap',
                                background: mode === m.key ? 'var(--grad-primary)' : 'transparent',
                                color: mode === m.key ? '#fff' : 'var(--text-secondary)',
                                boxShadow: mode === m.key ? '0 2px 8px rgba(164, 78, 31,0.28)' : 'none',
                                transition: 'background 200ms, color 200ms, box-shadow 200ms',
                            }}>{m.label}</button>
                        ))}
                    </div>
                    <button onClick={exportCsv} title="Export CSV (respects filter)" disabled={!hasData} style={{
                        ...GHOST_BTN, padding: '8px 14px', gap: 7,
                        cursor: hasData ? 'pointer' : 'not-allowed',
                        fontSize: 13, fontWeight: 600, opacity: hasData ? 1 : 0.5,
                    }}>
                        <Download size={14} /> Export
                    </button>
                    <button onClick={load} title="Refresh" style={{ ...GHOST_BTN, padding: 9, color: 'var(--text-secondary)' }}>
                        <RefreshCw size={15} />
                    </button>
                </div>
            </div>

            {!hasData ? (
                <EmptyState title={`No ${modeLabel} data`}
                    description="No transactions found for this period yet. Upload data to populate the dashboard." />
            ) : (
                <>
                    {/* ── Primary hero band (4 tiles, sparkline shape) ── */}
                    <div style={{ display: 'grid', gap: 14, marginBottom: 14,
                        gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))' }}>
                        <HeroTile label="Volume" icon={BarChart3} accent="var(--chart-3)" sparkId="volume" index={0}
                            raw={num(vt.volume)} format={(v) => fmt.currency(v)}
                            fullValue={fullNum(vt.volume, currencySymbol)}
                            deltaPct={dpg(vt.volume, prev?.volume)} compareLabel={compareLabel}
                            spark={sparks.volume}
                            sub={`${num(vt.txns).toLocaleString()} transactions`} />
                        <HeroTile label="Net Margin" icon={TrendingUp} accent="var(--chart-3)" sparkId="netrev" index={1}
                            raw={num(vt.netRevenue)} format={(v) => fmt.currency(v)}
                            fullValue={fullNum(vt.netRevenue, currencySymbol)}
                            deltaPct={dpg(vt.netRevenue, prev?.netRevenue)} compareLabel={compareLabel}
                            spark={sparks.netRevenue}
                            secondary={
                                <span title={`Net Spread = net margin + DCC ${fmt.currency(num(vt.dccAcquirer))} + rental ${fmt.currency(num(vt.rental))} · ${num(vt.spreadPct).toFixed(4)}% of volume`}>
                                    <span style={{ color: SERIES.ancillary, fontWeight: 700 }}>Net Spread</span>{' '}
                                    <b style={{ color: 'var(--text)' }}>{fmt.currency(num(vt.netSpread))}</b>
                                    <span style={{ opacity: 0.8 }}> · {num(vt.spreadPct).toFixed(2)}%</span>
                                </span>
                            } />
                        <HeroTile label="Net Margin %" icon={Percent} accent="var(--chart-3)" sparkId="marginpct" index={2}
                            raw={num(vt.marginPct)} format={(v) => `${v.toFixed(2)}%`}
                            fullValue={`${num(vt.marginPct).toFixed(4)}% of volume`}
                            deltaPct={isFiltered ? null
                                : (prev && num(prev.marginPct) !== 0
                                    ? num(vt.marginPct) - num(prev.marginPct) : undefined)}
                            deltaSuffix="" compareLabel={compareLabel}
                            spark={sparks.marginPct}
                            sub="net margin / volume" />
                        <HeroTile label="Transactions" icon={Receipt} accent="var(--chart-3)" sparkId="txns" index={3}
                            raw={num(vt.txns)} format={(v) => fmt.number(Math.round(v))}
                            fullValue={fullNum(vt.txns)}
                            deltaPct={dpg(vt.txns, prev?.txns)} compareLabel={compareLabel}
                            spark={sparks.txns}
                            sub={`avg ticket ${fmt.currency(num(vt.avgTicket))}`} />
                    </div>

                    {/* ── Secondary metric rail (fees + derived KPIs) ── */}
                    {derived && (
                        <div className="rail-grid dx-card dx-rise" style={{
                            marginBottom: 14, overflow: 'hidden', animationDelay: '280ms',
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
                            <RailMetric label="DCC (Acquirer)" icon={Globe}
                                value={fmt.currency(num(vt.dccAcquirer))}
                                fullValue={fullNum(vt.dccAcquirer, currencySymbol)}
                                deltaPct={dpg(vt.dccAcquirer, prev?.dccAcquirer)}
                                compareLabel={compareLabel}
                                hint="Acquirer share of DCC revenue (added to Net Spread)" />
                            <RailMetric label="Rental" icon={Layers}
                                value={fmt.currency(num(vt.rental))}
                                fullValue={fullNum(vt.rental, currencySymbol)}
                                deltaPct={dpg(vt.rental, prev?.rental)}
                                compareLabel={compareLabel}
                                hint="POS / terminal rental income (added to Net Spread)" />
                            <RailMetric label="Net Spread" icon={Sigma}
                                value={fmt.currency(num(vt.netSpread))}
                                fullValue={fullNum(vt.netSpread, currencySymbol)}
                                sub={`${num(vt.spreadPct).toFixed(3)}%`}
                                subTitle={`${num(vt.spreadPct).toFixed(4)}% — net spread / volume`}
                                deltaPct={dpg(vt.netSpread, prev?.netSpread)}
                                compareLabel={compareLabel}
                                hint="Net margin + DCC (acquirer) + rental" />
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
                    <div className="dx-rise" style={{ display: 'grid', gap: 14, marginBottom: 22,
                        animationDelay: '340ms',
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
                                    <ChartGradients series={{ volume: C.volume }} from={0.95} to={0.25} />
                                    <CartesianGrid {...GRID_PROPS} />
                                    <XAxis dataKey="label" {...AXIS_PROPS} />
                                    {/* Money axis — carries the tenant currency. */}
                                    <YAxis yAxisId="vol" tickFormatter={(v) => fmt.currency(v)}
                                        {...AXIS_PROPS} width={PANEL_Y_WIDTH} />
                                    <YAxis yAxisId="pct" orientation="right"
                                        tickFormatter={(v) => `${v.toFixed(1)}%`}
                                        {...AXIS_PROPS} width={48} />
                                    <ReTooltip content={<BucketTooltip fmt={fmt} />}
                                        cursor={{ fill: 'color-mix(in srgb, var(--primary) 7%, transparent)' }} />
                                    <Legend {...LEGEND_PROPS} />
                                    {/* `fill` is what the Legend swatch reads — the per-bar
                                        Cells below paint the actual gradient body. */}
                                    <Bar yAxisId="vol" dataKey="volume" name="Volume" fill={C.volume}
                                        radius={[6, 6, 0, 0]} maxBarSize={28} {...ANIM()}>
                                        {viewData.map((b, i) => (
                                            <Cell key={i} fill={`url(#${gradientId('volume')})`}
                                                fillOpacity={b.partial ? 0.6 : 1} />
                                        ))}
                                    </Bar>
                                    {mode === 'MTD' && runRate && num(runRate.projectedVolume) > 0 && (
                                        <ReferenceLine yAxisId="vol"
                                            y={num(runRate.projectedVolume) / Math.max(viewData.length, 1)}
                                            stroke="var(--chart-axis)" strokeDasharray="5 4"
                                            label={{ value: 'avg pace', position: 'insideTopRight',
                                                fontSize: 10, fill: 'var(--chart-axis)' }} />
                                    )}
                                    <Line yAxisId="pct" type="monotone" dataKey="marginPct" name="Net Margin %"
                                        stroke={C.marginPct} strokeWidth={2.4} {...ANIM()}
                                        dot={{ r: 3.5, strokeWidth: 2, stroke: 'var(--bg-card)', fill: C.marginPct }}
                                        activeDot={{ r: 5.5, strokeWidth: 2, stroke: 'var(--bg-card)' }} />
                                </ComposedChart>
                            </ResponsiveContainer>
                        </ChartCard>

                        <ChartCard title="Cost & Margin Mix"
                            subtitle="Costs at the base — the green cap is what we keep">
                            {/* height matches the two panels + their labels next door */}
                            <ResponsiveContainer width="100%" height={344}>
                                <BarChart data={viewData} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
                                    {/* Gradient bodies for each stack segment; `to` stays high
                                        so no segment fades out mid-stack. */}
                                    <ChartGradients from={0.95} to={0.5} series={{
                                        netRevenue: C.netRevenue,
                                        interchange: C.interchange,
                                        schemeFee: C.schemeFee,
                                        ecomFee: C.ecomFee,
                                    }} />
                                    <CartesianGrid {...GRID_PROPS} />
                                    <XAxis dataKey="label" {...AXIS_PROPS} />
                                    {/* Money axis (Cost & Margin Mix) — carries the tenant currency. */}
                                    <YAxis tickFormatter={(v) => fmt.currency(v)} {...AXIS_PROPS} width={66} />
                                    <ReTooltip content={<CompositionTooltip fmt={fmt} />}
                                        cursor={{ fill: 'color-mix(in srgb, var(--primary) 7%, transparent)' }} />
                                    <Legend {...LEGEND_PROPS} />
                                    {/* stroke is the surface colour: it reads as a 2px gap
                                        between segments, not as an outline. `fill` is what the
                                        Legend swatch reads — the Cells paint the gradients.
                                        Stack order: the three costs rise from the baseline,
                                        margin caps the bar so the green "kept" band is the
                                        first thing the eye lands on. Legend and tooltip
                                        inherit this order. */}
                                    <Bar dataKey="interchange" name="Interchange" stackId="c"
                                        fill={C.interchange} maxBarSize={30}
                                        stroke="var(--bg-card)" strokeWidth={2} {...ANIM()}>
                                        {viewData.map((b, i) => (
                                            <Cell key={i} fill={`url(#${gradientId('interchange')})`} />
                                        ))}
                                    </Bar>
                                    <Bar dataKey="schemeFee" name="Scheme Fee" stackId="c"
                                        fill={C.schemeFee} maxBarSize={30}
                                        stroke="var(--bg-card)" strokeWidth={2} {...ANIM()}>
                                        {viewData.map((b, i) => (
                                            <Cell key={i} fill={`url(#${gradientId('schemeFee')})`} />
                                        ))}
                                    </Bar>
                                    <Bar dataKey="ecomFee" name="PG Fee" stackId="c"
                                        fill={C.ecomFee} maxBarSize={30}
                                        stroke="var(--bg-card)" strokeWidth={2} {...ANIM()}>
                                        {viewData.map((b, i) => (
                                            <Cell key={i} fill={`url(#${gradientId('ecomFee')})`} />
                                        ))}
                                    </Bar>
                                    <Bar dataKey="netRevenue" name="Net Margin" stackId="c"
                                        fill={C.netRevenue} maxBarSize={30} radius={[6, 6, 0, 0]}
                                        stroke="var(--bg-card)" strokeWidth={2} {...ANIM()}>
                                        {viewData.map((b, i) => (
                                            <Cell key={i} fill={`url(#${gradientId('netRevenue')})`} />
                                        ))}
                                    </Bar>
                                </BarChart>
                            </ResponsiveContainer>
                        </ChartCard>
                    </div>

                    {/* ── Breakdown table ── */}
                    <div className="dx-card" style={{ overflow: 'hidden' }}>
                        <div style={{ overflowX: 'auto' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                                <thead>
                                    <tr style={{ borderBottom: '1px solid var(--border)', background: 'var(--bg-subtle)' }}>
                                        {TABLE_HEADS.map((h, i) => (
                                            <th key={h.label} style={{
                                                textAlign: i === 0 ? 'left' : 'right',
                                                padding: '13px 16px', fontSize: 11, fontWeight: 600,
                                                letterSpacing: '0.06em', textTransform: 'uppercase',
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
                                        // Same directional wash as the insight pills: strongest
                                        // at the row label, fading out across the columns.
                                        const rowGrad = (g) => `linear-gradient(100deg,
                                            color-mix(in srgb, ${g} 20%, transparent) 0%,
                                            color-mix(in srgb, ${g} 9%, transparent) 45%,
                                            color-mix(in srgb, ${g} 2%, transparent) 100%)`;
                                        // null, not 'transparent': an inline background beats the
                                        // stylesheet, so painting every ordinary row transparent
                                        // would suppress the global table zebra.
                                        const tint = i === bestIdx ? rowGrad('var(--success)')
                                            : i === worstIdx ? rowGrad('var(--danger)') : null;
                                        return (
                                            <tr key={b.label} className="exec-row" style={{
                                                borderBottom: '1px solid var(--border)',
                                                ...(tint ? { background: tint } : {}),
                                            }}>
                                                <td style={{ padding: '11px 16px', fontWeight: 600, color: 'var(--text)', whiteSpace: 'nowrap' }}>
                                                    {b.label}
                                                    {b.partial && <span style={{
                                                        marginLeft: 8, fontSize: 10.5, fontWeight: 600,
                                                        color: 'var(--warning-text)', background: 'var(--warning-bg)',
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
                                                    color: b.netRevenue >= 0 ? 'var(--text)' : 'var(--danger-text)' }}
                                                    title={`${fullNum(b.netRevenue, currencySymbol)} · ${b.marginPct.toFixed(4)}% of volume`}>
                                                    {fmt.amount(b.netRevenue)}
                                                    <span style={rateInline}>({b.marginPct.toFixed(2)}%)</span>
                                                </td>
                                                <td style={{ ...tdNum, color: SERIES.ancillary }}
                                                    title={`DCC (acquirer) ${fullNum(b.dccAcquirer, currencySymbol)} · Rental ${fullNum(b.rental, currencySymbol)}`}>
                                                    {fmt.amount(b.dccAcquirer + b.rental)}
                                                </td>
                                                <td style={{ ...tdNum, fontWeight: 700,
                                                    color: b.netSpread >= 0 ? 'var(--text)' : 'var(--danger-text)' }}
                                                    title={`${fullNum(b.netSpread, currencySymbol)} · ${b.spreadPct.toFixed(4)}% of volume`}>
                                                    {fmt.amount(b.netSpread)}
                                                    <span style={rateInline}>({b.spreadPct.toFixed(2)}%)</span>
                                                </td>
                                            </tr>
                                        );
                                    })}
                                    <tr style={{ background: 'var(--bg-subtle)',
                                        borderTop: '2px solid var(--border)' }}>
                                        <td style={{ padding: '12px 16px', fontWeight: 700, color: 'var(--text)' }}>
                                            {mode} Total{isFiltered ? ' (filtered)' : ''}
                                        </td>
                                        <td style={tdTotal} title={fullNum(vt.txns)}>{num(vt.txns).toLocaleString()}</td>
                                        <td style={tdTotal} title={fullNum(vt.volume, currencySymbol)}>{fmt.amount(num(vt.volume))}</td>
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
                                        <td style={{ ...tdTotal, color: num(vt.netRevenue) >= 0 ? 'var(--text)' : 'var(--danger-text)' }}
                                            title={`${fullNum(vt.netRevenue, currencySymbol)} · ${num(vt.marginPct).toFixed(4)}% of volume`}>
                                            {fmt.amount(num(vt.netRevenue))}
                                            <span style={rateInline}>({num(vt.marginPct).toFixed(2)}%)</span>
                                        </td>
                                        <td style={{ ...tdTotal, color: SERIES.ancillary }}
                                            title={`DCC (acquirer) ${fullNum(vt.dccAcquirer, currencySymbol)} · Rental ${fullNum(vt.rental, currencySymbol)}`}>
                                            {fmt.amount(num(vt.dccAcquirer) + num(vt.rental))}
                                        </td>
                                        <td style={{ ...tdTotal, color: num(vt.netSpread) >= 0 ? 'var(--text)' : 'var(--danger-text)' }}
                                            title={fullNum(vt.netSpread, currencySymbol)}>
                                            {fmt.amount(num(vt.netSpread))}
                                            <span style={rateInline}>({num(vt.spreadPct).toFixed(2)}%)</span>
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
    fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
};
/* Rate lives INSIDE the fee cell it derives from — inline after the amount so
   rows stay single-height; no separate % columns. */
const rateInline = {
    fontSize: 10.5, fontWeight: 600, color: 'var(--text-secondary)',
    fontVariantNumeric: 'tabular-nums', marginLeft: 6,
};
/* Frosted secondary button — export / refresh in the page header. */
const GHOST_BTN = {
    border: '1px solid var(--border)',
    background: 'var(--glass-bg)',
    backdropFilter: 'var(--glass-blur)',
    WebkitBackdropFilter: 'var(--glass-blur)',
    boxShadow: 'var(--shadow-xs)',
    borderRadius: 'var(--radius-md)',
    cursor: 'pointer',
    color: 'var(--text)',
    display: 'inline-flex', alignItems: 'center',
    transition: 'box-shadow 200ms ease, border-color 200ms ease',
};
const selStyle = {
    border: 'none', background: 'transparent', color: 'var(--text)',
    fontSize: 12.5, fontWeight: 600, cursor: 'pointer', outline: 'none',
    padding: '2px 0',
};
const tdTotal = { ...tdNum, fontWeight: 700 };

export default Dashboard;
