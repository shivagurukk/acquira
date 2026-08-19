import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Paper } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    ComposedChart, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
    ResponsiveContainer,
} from 'recharts';
import { CreditCard, RefreshCw, Download, Filter, AlertTriangle } from 'lucide-react';
import BusinessFilters from '../../components/BusinessFilters';
import SkeletonLoader from '../../components/SkeletonLoader';
import useDataBounds from '../../hooks/useDataBounds';
import { exportToCSV } from '../../utils/exportUtils';
import { createFmt, formatCompactCurrency, formatNumber } from '../../utils/formatters';
import { premiumDataGridStyles, premiumTableWrapper } from '../../theme/dataGridStyles';
import { GRID_PROPS, AXIS_PROPS, TOOLTIP_PROPS, prefersReducedMotion } from '../../theme/chartPalette';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

/* ════════════════════════════════════════════════════════════════════
   Card Type Dashboard — what plastic the volume rides on: CREDIT vs
   DEBIT vs PREPAID (vs anything else the feed carries), told as one
   proportion and its consequences. Replica of the Destination Dashboard
   with card_type as the split dimension — same navy masthead + command
   deck, hairline-divided metric band, and a signature SPLIT RIBBON that
   every panel below echoes. Unlike destination the split is N-valued,
   so every chart stacks one series per card type present in the data.

   Backend is sum_daily_full only (settlement currency + real fee stack),
   so there is no mixed-currency caveat on this page — basis is always
   SETTLEMENT.

   Date presets are anchored on the LATEST LOADED data date (data-bounds),
   never on "today".
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

/* Series identity for the whole page — each card type keeps one hue
   everywhere (tiles, ribbon, trend, breakdown, grid). Legends say the
   colour in words as well, so the mapping is never guessed.
     CREDIT     steel blue   #5E82D2 → #33518F
     DEBIT      copper       #D9924E → #9C5E1F
     PREPAID    green        #5FAF87 → #2F6B4C
     COMMERCIAL violet       #9B85D6 → #5F4A9C
     (other)    slate grey   #94A3B8 → #475569                      */
const TYPE_STYLES = {
    CREDIT:     { name: 'blue',   top: '#5E82D2', bottom: '#33518F' },
    DEBIT:      { name: 'copper', top: '#D9924E', bottom: '#9C5E1F' },
    PREPAID:    { name: 'green',  top: '#5FAF87', bottom: '#2F6B4C' },
    COMMERCIAL: { name: 'violet', top: '#9B85D6', bottom: '#5F4A9C' },
    OTHER:      { name: 'grey',   top: '#94A3B8', bottom: '#475569' },
};
const typeStyle = (t) => TYPE_STYLES[t] || TYPE_STYLES.OTHER;
const typeGrad = (t) => {
    const s = typeStyle(t);
    return `linear-gradient(135deg, ${s.top} 0%, ${s.bottom} 100%)`;
};
/* SVG gradient ids — V for vertical bars (trend), H for horizontal
   (breakdown). Unknown types share the OTHER pair. */
const gradId = (t, dir) => `ctGrad${TYPE_STYLES[t] ? t : 'OTHER'}${dir}`;
const ChartDefs = () => (
    <defs>
        {Object.entries(TYPE_STYLES).map(([key, s]) => (
            <React.Fragment key={key}>
                <linearGradient id={`ctGrad${key}V`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={s.top} />
                    <stop offset="100%" stopColor={s.bottom} />
                </linearGradient>
                <linearGradient id={`ctGrad${key}H`} x1="0" y1="0" x2="1" y2="0">
                    <stop offset="0%" stopColor={s.bottom} />
                    <stop offset="100%" stopColor={s.top} />
                </linearGradient>
            </React.Fragment>
        ))}
    </defs>
);

/* Stable stacking order: the three canonical types first, then whatever
   else the tenant's feed carries, alphabetically, UNSPECIFIED last. */
const CANONICAL_ORDER = ['CREDIT', 'DEBIT', 'PREPAID', 'COMMERCIAL'];
const orderTypes = (types) => {
    const set = Array.from(new Set(types));
    const canonical = CANONICAL_ORDER.filter(t => set.includes(t));
    const rest = set.filter(t => !CANONICAL_ORDER.includes(t) && t !== 'UNSPECIFIED').sort();
    return [...canonical, ...rest, ...(set.includes('UNSPECIFIED') ? ['UNSPECIFIED'] : [])];
};
const typeLabel = (t) => (t === 'UNSPECIFIED' ? 'Unspecified'
    : String(t || '').charAt(0) + String(t || '').slice(1).toLowerCase());

/* LOCAL date components, never toISOString() (shifts a day off-UTC). */
const fmtDate = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};
const parseDay = (iso) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
};

/* Presets are computed from the anchor (latest loaded date), so "Last 30 days"
   always means the last 30 days THAT EXIST. */
const PRESETS = [
    { key: 'D7',   label: 'Last 7 days' },
    { key: 'D30',  label: 'Last 30 days' },
    { key: 'MTD',  label: 'This month' },
    { key: 'LM',   label: 'Previous month' },
    { key: 'YTD',  label: 'This year' },
    { key: 'PY',   label: 'Last year' },
    { key: 'CUSTOM', label: 'Custom' },
];
const computeRange = (preset, anchorISO) => {
    const a = parseDay(anchorISO) || new Date();
    const shift = (days) => { const d = new Date(a); d.setDate(d.getDate() - days); return d; };
    switch (preset) {
        case 'D7':  return { startDate: fmtDate(shift(6)),  endDate: fmtDate(a) };
        case 'D30': return { startDate: fmtDate(shift(29)), endDate: fmtDate(a) };
        case 'MTD': return { startDate: fmtDate(new Date(a.getFullYear(), a.getMonth(), 1)), endDate: fmtDate(a) };
        case 'LM':  return {
            startDate: fmtDate(new Date(a.getFullYear(), a.getMonth() - 1, 1)),
            endDate: fmtDate(new Date(a.getFullYear(), a.getMonth(), 0)),
        };
        case 'YTD': return { startDate: fmtDate(new Date(a.getFullYear(), 0, 1)), endDate: fmtDate(a) };
        case 'PY':  return {
            startDate: fmtDate(new Date(a.getFullYear() - 1, 0, 1)),
            endDate: fmtDate(new Date(a.getFullYear() - 1, 11, 31)),
        };
        default: return {};
    }
};

/* Chart motion: a deliberate rise from the baseline, staggered per series;
   off entirely under prefers-reduced-motion. */
const CHART_ANIM = (delayMs = 0) => ({
    isAnimationActive: !prefersReducedMotion(),
    animationDuration: 900,
    animationEasing: 'ease-out',
    animationBegin: delayMs,
});

const BREAKDOWN_TABS = [
    { key: 'scheme',      label: 'Scheme' },
    { key: 'destination', label: 'Destination' },
    { key: 'channel',     label: 'Channel' },
    { key: 'mcc',         label: 'MCC' },
];

const EMPTY_LISTS = {
    schemeList: [], destinationList: [], channelList: [], mccList: [],
    midList: [], sidList: [], partnerList: [], rmList: [], teamLeaderList: [],
    industryList: [], sectorList: [], terminalTypeList: [],
    // cardTypeList intentionally absent — card type IS this page's split.
    merchantName: '',
};

/* Inline error state with retry — sections never render silent zeros. */
const SectionError = ({ message, onRetry }) => (
    <div style={{
        padding: '16px 20px', borderRadius: 'var(--radius-lg)',
        border: '1px solid var(--danger-border, #fecaca)', background: 'var(--danger-bg, #fef2f2)',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14, flexWrap: 'wrap',
    }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10,
            fontSize: 13, fontWeight: 600, color: 'var(--danger-text, #991b1b)' }}>
            <AlertTriangle size={16} /> {message}
        </span>
        <button onClick={onRetry} style={{
            display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer',
            padding: '6px 13px', fontSize: 12, fontWeight: 700, borderRadius: 'var(--radius-sm)',
            color: 'var(--danger-text, #991b1b)', background: 'transparent',
            border: '1px solid var(--danger-border, #fecaca)',
        }}>
            <RefreshCw size={13} /> Retry
        </button>
    </div>
);

/* Micro-label + mono value + one line of context (house metric tile). */
const Metric = ({ label, value, sub, tone, accent, title }) => (
    <div title={title} style={{ padding: '15px 20px', minWidth: 0 }}>
        <div className="ctd-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {accent && <span style={{ width: 8, height: 8, borderRadius: 2, background: accent, flexShrink: 0 }} />}
            {label}
        </div>
        <div style={{
            marginTop: 7, fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
            fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em',
            color: tone === 'danger' ? 'var(--danger-text)'
                : tone === 'success' ? 'var(--success-text)' : 'var(--text)',
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>
            {value}
        </div>
        {sub && (
            <div style={{ marginTop: 3, fontSize: 11, color: 'var(--text-secondary)', whiteSpace: 'nowrap' }}>
                {sub}
            </div>
        )}
    </div>
);

const CardTypeDashboard = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const { latest: sharedLatest } = useDataBounds(tenantVersion);

    const [showFilters, setShowFilters] = useState(false);
    const [preset, setPreset] = useState('D30');
    const [filters, setFilters] = useState({ startDate: '', endDate: '', ...EMPTY_LISTS });
    const [filterVersion, setFilterVersion] = useState(0);
    const initRef = useRef(false);

    /* Bounds come from THIS page's own backing table (sum_daily_full), not the
       shared /business/data-bounds. The shared endpoint is anchored on
       fact_transaction, which can cover a different range than the summary —
       anchoring on it opened the page on a window with zero rows. The shared
       value is kept only as a last-resort fallback. */
    const [pageBounds, setPageBounds] = useState({ latest: null, loaded: false });
    useEffect(() => {
        let cancelled = false;
        setPageBounds({ latest: null, loaded: false });
        (async () => {
            try {
                const res = await api.get('/business/card-type-dashboard/bounds');
                if (!cancelled) setPageBounds({ latest: res.data?.latest || null, loaded: true });
            } catch (e) {
                console.error('Failed to load card-type data bounds', e);
                if (!cancelled) setPageBounds({ latest: null, loaded: true });
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    /* The date every preset is measured back from. */
    const anchor = pageBounds.latest || sharedLatest;

    /* First fetch waits for those bounds, then anchors the default window on the
       latest date this page can actually render. */
    useEffect(() => {
        if (!pageBounds.loaded || initRef.current) return;
        initRef.current = true;
        setFilters(f => ({ ...f, ...computeRange('D30', anchor) }));
        setFilterVersion(v => v + 1);
    }, [pageBounds.loaded, anchor]);
    // Tenant switch: re-anchor on the new tenant's bounds.
    useEffect(() => { initRef.current = false; }, [tenantVersion]);

    // Per-section state so one failing endpoint doesn't blank the page.
    const [kpiData, setKpiData] = useState(null);
    const [kpiLoading, setKpiLoading] = useState(true);
    const [kpiError, setKpiError] = useState(false);
    const [trendData, setTrendData] = useState([]);
    const [trendLoading, setTrendLoading] = useState(true);
    const [trendError, setTrendError] = useState(false);
    const [breakdownTab, setBreakdownTab] = useState('scheme');
    const [breakdownData, setBreakdownData] = useState([]);
    const [breakdownLoading, setBreakdownLoading] = useState(true);
    const [breakdownError, setBreakdownError] = useState(false);
    const [merchantsData, setMerchantsData] = useState([]);
    const [merchantsLoading, setMerchantsLoading] = useState(true);
    const [merchantsError, setMerchantsError] = useState(false);

    const fetchKpis = useCallback(async (f) => {
        setKpiLoading(true); setKpiError(false);
        try {
            const res = await api.post('/business/card-type-dashboard/kpis', f);
            setKpiData(res.data);
        } catch (e) {
            console.error('Failed to load card-type KPIs', e);
            setKpiError(true); setKpiData(null);
        } finally { setKpiLoading(false); }
    }, []);

    const fetchTrend = useCallback(async (f) => {
        setTrendLoading(true); setTrendError(false);
        try {
            // The trend honours the SELECTED window — YTD shows this year's
            // months only.
            const res = await api.post('/business/card-type-dashboard/trend', f);
            setTrendData(res.data || []);
        } catch (e) {
            console.error('Failed to load card-type trend', e);
            setTrendError(true); setTrendData([]);
        } finally { setTrendLoading(false); }
    }, []);

    const fetchBreakdown = useCallback(async (f, dimension) => {
        setBreakdownLoading(true); setBreakdownError(false);
        try {
            const res = await api.post(`/business/card-type-dashboard/breakdown/${dimension}`, f);
            setBreakdownData(res.data || []);
        } catch (e) {
            console.error('Failed to load card-type breakdown', e);
            setBreakdownError(true); setBreakdownData([]);
        } finally { setBreakdownLoading(false); }
    }, []);

    const fetchMerchants = useCallback(async (f) => {
        setMerchantsLoading(true); setMerchantsError(false);
        try {
            const res = await api.post('/business/card-type-dashboard/top-merchants?limit=15', f);
            setMerchantsData(res.data || []);
        } catch (e) {
            console.error('Failed to load card-type top merchants', e);
            setMerchantsError(true); setMerchantsData([]);
        } finally { setMerchantsLoading(false); }
    }, []);

    /* One effect per concern; filterVersion is the only trigger, so a page open
       fires each endpoint exactly once (after bounds land). */
    useEffect(() => {
        if (!filterVersion) return;
        fetchKpis(filters);
        fetchTrend(filters);
        fetchMerchants(filters);
    }, [filterVersion, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps
    useEffect(() => {
        if (!filterVersion) return;
        fetchBreakdown(filters, breakdownTab);
    }, [breakdownTab, filterVersion, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

    const run = useCallback(() => setFilterVersion(v => v + 1), []);
    const pickPreset = (key) => {
        setPreset(key);
        if (key !== 'CUSTOM') {
            setFilters(f => ({ ...f, ...computeRange(key, anchor) }));
            setFilterVersion(v => v + 1);
        }
    };
    const setCustomDate = (key, value) => {
        setPreset('CUSTOM');
        setFilters(f => ({ ...f, [key]: value }));
    };
    const handleAdvancedFilterChange = useCallback((next) => setFilters(next), []);

    const activeFilterCount = useMemo(() =>
        ['schemeList', 'destinationList', 'channelList', 'mccList', 'midList', 'sidList',
            'partnerList', 'rmList', 'teamLeaderList', 'industryList', 'sectorList', 'terminalTypeList']
            .reduce((a, k) => a + (filters[k]?.length || 0), 0)
        + (filters.merchantName ? 1 : 0), [filters]);

    /* ── Derived: the split — backend returns cardTypes sorted by volume ── */
    const ctBlocks = useMemo(() => kpiData?.cardTypes || [], [kpiData]);
    const totalVol = num(kpiData?.totalVolume);
    const priorHasData = kpiData?.priorWindowHasData === true;
    const growth = (v) => {
        if (!priorHasData || v == null) return null;
        const n = Number(v);
        return `${n >= 0 ? '▲' : '▼'} ${Math.abs(n).toFixed(1)}% vs prior`;
    };

    /* Ribbon/legend order: canonical first; both walk the same list. */
    const ribbonBlocks = useMemo(() => {
        const order = orderTypes(ctBlocks.map(b => b.cardType));
        return order.map(t => ctBlocks.find(b => b.cardType === t)).filter(Boolean);
    }, [ctBlocks]);

    const windowLabel = kpiData?.start && kpiData?.end
        ? `${kpiData.start} → ${kpiData.end}`
        : (filters.startDate && filters.endDate ? `${filters.startDate} → ${filters.endDate}` : '—');

    /* ── Trend chart: pivot month × cardType rows into one row per month ── */
    const trendTypes = useMemo(() => orderTypes(trendData.map(d => d.cardType)), [trendData]);
    const chartData = useMemo(() => {
        // When the window crosses a year boundary the axis says so ("Aug '25"),
        // so a bar can never be mistaken for the current year's month.
        const years = new Set(trendData.map(d => String(d.month || '').slice(0, 4)));
        const multiYear = years.size > 1;
        const byMonth = new Map();
        for (const d of trendData) {
            const key = String(d.month || '');
            if (!byMonth.has(key)) {
                const [y, m] = key.split('-');
                const dateObj = new Date(parseInt(y), parseInt(m) - 1);
                byMonth.set(key, {
                    month: key,
                    monthShort: dateObj.toLocaleDateString('en-US', { month: 'short' })
                        + (multiYear ? ` '${String(y).slice(2)}` : ''),
                    monthLong: dateObj.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }),
                });
            }
            byMonth.get(key)[d.cardType] = num(d.volume);
        }
        return Array.from(byMonth.values()).sort((a, b) => a.month.localeCompare(b.month));
    }, [trendData]);

    const TrendTooltip = ({ active, payload }) => {
        if (!active || !payload?.length) return null;
        const row = payload[0].payload;
        return (
            <div style={TOOLTIP_PROPS.contentStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6 }}>{row.monthLong}</div>
                {trendTypes.map(t => (
                    <div key={t} style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                        <span style={{ color: 'var(--text-secondary)' }}>{typeLabel(t)} volume</span>
                        <span className="ctd-num" style={{ fontWeight: 700, color: typeStyle(t).bottom }}>
                            {fmt.currency(num(row[t]))}
                        </span>
                    </div>
                ))}
            </div>
        );
    };

    /* ── Breakdown: pivot dim × cardType into stacked share bars, biggest first ── */
    const breakdownTypes = useMemo(() => orderTypes(breakdownData.map(d => d.cardType)), [breakdownData]);
    const breakdownChartData = useMemo(() => {
        const byDim = new Map();
        for (const d of breakdownData) {
            const key = d.dimensionValue || 'Unknown';
            if (!byDim.has(key)) byDim.set(key, { name: key, total: 0 });
            const row = byDim.get(key);
            row[d.cardType] = (row[d.cardType] || 0) + num(d.volume);
            row.total += num(d.volume);
        }
        return Array.from(byDim.values()).sort((a, b) => b.total - a.total);
    }, [breakdownData]);

    const BreakdownTooltip = ({ active, payload }) => {
        if (!active || !payload?.length) return null;
        const row = payload[0].payload;
        return (
            <div style={TOOLTIP_PROPS.contentStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6 }}>{row.name}</div>
                {breakdownTypes.map(t => (
                    <div key={t} style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                        <span style={{ color: 'var(--text-secondary)' }}>{typeLabel(t)}</span>
                        <span className="ctd-num" style={{ fontWeight: 700 }}>{fmt.currency(num(row[t]))}</span>
                    </div>
                ))}
            </div>
        );
    };

    /* ── Top merchants grid ── */
    const merchantRows = useMemo(() => merchantsData.map((m, i) => ({ id: m.mid || i, ...m })), [merchantsData]);
    const moneyCell = (v, style) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : <span className="ctd-num" style={style}>{fmt.money(v)}</span>;

    const merchantColumns = useMemo(() => [
        {
            field: 'mid', headerName: 'MID', width: 120,
            renderCell: (p) => <span className="ctd-num" style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>{p.value || '—'}</span>,
        },
        {
            field: 'merchantName', headerName: 'Merchant', flex: 1.3, minWidth: 180,
            renderCell: (p) => <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.value}</span>,
        },
        {
            field: 'creditVolume', headerName: 'Credit Vol', flex: 1, minWidth: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'debitVolume', headerName: 'Debit Vol', flex: 1, minWidth: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'prepaidVolume', headerName: 'Prepaid Vol', flex: 1, minWidth: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'otherVolume', headerName: 'Other Vol', flex: 0.9, minWidth: 100, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'totalVolume', headerName: 'Total Vol', flex: 1, minWidth: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 700 }),
        },
        {
            field: 'msf', headerName: 'MSF', flex: 0.9, minWidth: 100, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'icf', headerName: 'Interchange Fee', flex: 0.95, minWidth: 115, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'sf', headerName: 'Scheme Fee', flex: 0.9, minWidth: 105, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'pg', headerName: 'Payment Gateway Fee', flex: 0.95, minWidth: 125, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'netMargin', headerName: 'Net Margin', flex: 1, minWidth: 115, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => p.value == null
                ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                : (
                    <span className="ctd-num" style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4, fontWeight: 700,
                        color: num(p.value) >= 0 ? 'var(--success-text)' : 'var(--danger-text)',
                    }}>
                        <span aria-hidden="true" style={{ fontSize: 10 }}>{num(p.value) >= 0 ? '▲' : '▼'}</span>
                        {fmt.money(p.value)}
                    </span>
                ),
        },
        {
            field: 'marginPct', headerName: 'Margin %', flex: 0.8, minWidth: 95, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => p.value == null
                ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                : (
                    <span className="ctd-num" style={{ fontWeight: 700,
                        color: num(p.value) >= 0 ? 'var(--success-text)' : 'var(--danger-text)' }}>
                        {num(p.value).toFixed(2)}%
                    </span>
                ),
        },
    ], [fmt]);

    const tabLabel = BREAKDOWN_TABS.find(t => t.key === breakdownTab)?.label.toLowerCase();

    return (
        // Plain-CSS equivalent of the shared pageContainer sx (that object uses
        // MUI shorthand keys, which are inert on a plain style prop).
        <div style={{
            padding: 'var(--space-page, 20px)', background: 'var(--bg)',
            minHeight: '100vh', width: '100%', boxSizing: 'border-box',
        }}>
            <style>{`
                .ctd-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }
                .ctd-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .ctd-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); }

                /* ── Navy masthead + command deck (house executive header) ── */
                .ctd-panel.ctd-hdrblock { background: var(--table-head-bg,
                        linear-gradient(135deg, #24386B 0%, #16264A 55%, #0A1426 100%));
                    border-color: transparent; overflow: visible; margin-bottom: 12px; }
                .ctd-hdrblock > :first-child { border-radius: calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px) 0 0; }
                .ctd-hdrblock > :last-child { border-radius: 0 0 calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px); }
                .ctd-mast { padding: 20px 24px 18px; display: flex; justify-content: space-between;
                    align-items: flex-end; gap: 18px; flex-wrap: wrap; }
                .ctd-mast-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.18em; text-transform: uppercase;
                    color: var(--table-head-muted, #93A3C6); }
                .ctd-mast h1 { margin: 8px 0 0; font-size: 26px; font-weight: 700;
                    letter-spacing: -0.025em; line-height: 1.08;
                    color: var(--table-head-text, #EEF3FC); }
                .ctd-mast-sub { margin: 6px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .ctd-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.22);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .ctd-mast-btn:hover { background: rgba(255,255,255,0.14); }
                .ctd-mast-btn:disabled { opacity: 0.5; cursor: default; }
                .ctd-cmdbar { display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
                    padding: 10px 18px; background: rgba(0,0,0,0.20);
                    border-top: 1px solid rgba(255,255,255,0.13); }
                .ctd-preset { padding: 7px 13px; font-size: 12px; font-weight: 600; cursor: pointer;
                    color: rgba(238,243,252,0.72); background: transparent; border: 0;
                    border-radius: var(--radius-sm); transition: background .12s ease, color .12s ease; }
                .ctd-preset:hover { background: rgba(255,255,255,0.08); color: #EEF3FC; }
                .ctd-preset-on { background: rgba(255,255,255,0.14); color: #EEF3FC;
                    box-shadow: inset 0 -2px 0 var(--chart-4, #7191CE); }
                .ctd-date { padding: 6px 9px; font-size: 12px; font-family: var(--font-mono);
                    color: #EEF3FC; background: rgba(255,255,255,0.07);
                    border: 1px solid rgba(255,255,255,0.22); border-radius: var(--radius-sm);
                    outline: none; color-scheme: dark; }

                /* ── Split ribbon: sweeps in from the left on load, and the
                   segment widths glide when the window or filters change. ── */
                @keyframes ctdSweep { from { transform: scaleX(0); } to { transform: scaleX(1); } }
                .ctd-ribbon { display: flex; height: 12px; border-radius: 999px; overflow: hidden;
                    background: var(--border-light, var(--border));
                    transform-origin: left; animation: ctdSweep .8s ease-out; }
                .ctd-ribbon > div { transition: width .7s cubic-bezier(0.22, 1, 0.36, 1); }
                @media (prefers-reduced-motion: reduce) {
                    .ctd-ribbon { animation: none; }
                    .ctd-ribbon > div { transition: none; }
                }

                .ctd-focus:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
                .ctd-cmdbar .ctd-focus:focus-visible { outline-color: #EEF3FC; outline-offset: -2px; }
                .ctd-seg { display: inline-flex; background: var(--bg-card);
                    border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 3px; }
                .ctd-seg button { border: 0; cursor: pointer; border-radius: calc(var(--radius-sm) - 2px);
                    padding: 6px 13px; font-size: 12px; font-weight: 600; white-space: nowrap;
                    background: transparent; color: var(--text-secondary);
                    transition: background .12s ease, color .12s ease; }
                .ctd-seg button:hover { color: var(--text); background: var(--bg-hover); }
                .ctd-seg .ctd-seg-on { background: var(--primary); color: #fff; }
                .ctd-cell { border-right: 1px solid var(--border-light, var(--border)); }
                @media (prefers-reduced-motion: reduce) {
                    .ctd-preset, .ctd-mast-btn, .ctd-seg button { transition: none; }
                }
            `}</style>

            {/* ── Masthead + command deck ── */}
            <section className="ctd-panel ctd-hdrblock">
                <div className="ctd-mast">
                    <div>
                        <div className="ctd-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <CreditCard size={11} /> Business · Card Type
                        </div>
                        <h1>Card Type Dashboard</h1>
                        <p className="ctd-mast-sub">
                            Credit vs debit vs prepaid acquiring · {windowLabel} · {currencyCode || currencySymbol || ''}
                        </p>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                        <button className="ctd-focus ctd-mast-btn" onClick={() => setShowFilters(s => !s)}>
                            <Filter size={13} /> Filters{activeFilterCount > 0 ? ` (${activeFilterCount})` : ''}
                        </button>
                        <button className="ctd-focus ctd-mast-btn"
                            onClick={() => exportToCSV(merchantRows, 'card_type_top_merchants')}
                            disabled={!merchantRows.length}>
                            <Download size={13} /> Export
                        </button>
                        <button className="ctd-focus ctd-mast-btn" onClick={run} title="Refresh" aria-label="Refresh"
                            style={{ padding: '9px 11px' }}>
                            <RefreshCw size={14} className={kpiLoading ? 'animate-spin' : ''} />
                        </button>
                    </div>
                </div>
                <div className="ctd-cmdbar">
                    <span className="ctd-mast-eyebrow" style={{ marginRight: 10 }}>Window</span>
                    {PRESETS.map(p => (
                        <button key={p.key}
                            className={`ctd-focus ctd-preset${preset === p.key ? ' ctd-preset-on' : ''}`}
                            onClick={() => pickPreset(p.key)}>
                            {p.label}
                        </button>
                    ))}
                    {preset === 'CUSTOM' && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, marginLeft: 6 }}>
                            <input type="date" className="ctd-date" value={filters.startDate}
                                onChange={e => setCustomDate('startDate', e.target.value)} aria-label="From date" />
                            <span style={{ color: 'rgba(238,243,252,0.5)', fontSize: 11 }}>to</span>
                            <input type="date" className="ctd-date" value={filters.endDate}
                                onChange={e => setCustomDate('endDate', e.target.value)} aria-label="To date" />
                            <button className="ctd-focus ctd-mast-btn" style={{ padding: '6px 13px' }} onClick={run}>
                                Apply
                            </button>
                        </span>
                    )}
                </div>
            </section>

            <BusinessFilters
                filters={filters}
                onChange={handleAdvancedFilterChange}
                onApply={run}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
                hideCardType
            />

            {/* ── The split: metric band + signature ribbon ── */}
            {kpiError ? (
                <div style={{ marginBottom: 12 }}>
                    <SectionError message="The card-type KPIs did not load." onRetry={() => fetchKpis(filters)} />
                </div>
            ) : kpiLoading ? (
                <div style={{ marginBottom: 12 }}><SkeletonLoader variant="kpi-row" count={4} /></div>
            ) : kpiData && (
                <section className="ctd-panel" style={{ marginBottom: 12, overflow: 'hidden' }}>
                    <div style={{
                        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                        borderBottom: '1px solid var(--border-light, var(--border))',
                    }}>
                        <div className="ctd-cell">
                            <Metric label="Total volume"
                                value={fmt.currency(totalVol)}
                                sub={`${formatNumber(ribbonBlocks.reduce((a, b) => a + num(b.txns), 0))} txns in this window`} />
                        </div>
                        {ribbonBlocks.map((b, i) => (
                            <div key={b.cardType} className={i < ribbonBlocks.length - 1 ? 'ctd-cell' : undefined}>
                                <Metric label={`${typeLabel(b.cardType)} (${typeStyle(b.cardType).name})`}
                                    accent={typeGrad(b.cardType)}
                                    value={fmt.currency(num(b.volume))}
                                    sub={growth(b.volumeGrowthPct)
                                        || `${num(b.sharePct).toFixed(1)}% share · ${num(b.effectiveRateBps).toFixed(1)} bps`} />
                            </div>
                        ))}
                    </div>
                    <div style={{ padding: '15px 20px 16px' }}>
                        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
                            <span className="ctd-eyebrow">What the volume rides on</span>
                            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                {totalVol > 0 ? `${fmt.currency(totalVol)} total in this window`
                                    : 'no volume in this window'}
                            </span>
                        </div>
                        {totalVol > 0 ? (
                            <>
                                <div className="ctd-ribbon">
                                    {ribbonBlocks.map((b, i) => (
                                        <div key={b.cardType}
                                            title={`${typeLabel(b.cardType)} (${typeStyle(b.cardType).name}) · ${fmt.currency(num(b.volume))}`}
                                            style={{ width: `${num(b.sharePct)}%`, background: typeGrad(b.cardType),
                                                borderRight: i < ribbonBlocks.length - 1
                                                    ? '1px solid var(--bg-card)' : undefined }} />
                                    ))}
                                </div>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '5px 20px', marginTop: 9 }}>
                                    {ribbonBlocks.map(b => (
                                        <span key={b.cardType} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                            fontSize: 11, color: 'var(--text-secondary)' }}>
                                            <span style={{ width: 14, height: 9, borderRadius: 2, background: typeGrad(b.cardType) }} />
                                            <span style={{ fontWeight: 600, color: 'var(--text)' }}>{typeLabel(b.cardType)}</span>
                                            <span style={{ opacity: 0.65 }}>({typeStyle(b.cardType).name})</span>
                                            <span className="ctd-num">{fmt.currency(num(b.volume))}</span>
                                            <span style={{ opacity: 0.72 }}>{num(b.sharePct).toFixed(1)}%</span>
                                        </span>
                                    ))}
                                </div>
                            </>
                        ) : (
                            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                                Nothing was processed between {windowLabel}. Pick a window inside the loaded data
                                {anchor ? ` (latest: ${anchor})` : ''} or clear a filter.
                            </div>
                        )}
                    </div>
                </section>
            )}

            {/* ── Monthly trend ── */}
            <section style={{ marginBottom: 12 }}>
                {trendError ? (
                    <SectionError message="The monthly card-type trend did not load." onRetry={() => fetchTrend(filters)} />
                ) : trendLoading ? (
                    <SkeletonLoader variant="chart" />
                ) : (
                    <div className="ctd-panel" style={{ padding: '18px 20px 10px' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                            marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
                            <div>
                                <div className="ctd-eyebrow">Monthly trend · selected window</div>
                                <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                    Volume by card type by month
                                </div>
                            </div>
                            <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                                {trendTypes.map(t => (
                                    <span key={t} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                        fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                        <span style={{ width: 14, height: 9, borderRadius: 2, background: typeGrad(t) }} />
                                        {typeLabel(t)} ({typeStyle(t).name})
                                    </span>
                                ))}
                            </div>
                        </div>
                        {chartData.length === 0 ? (
                            <div style={{ padding: '42px 0', textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>
                                No monthly data for the selected filters.
                            </div>
                        ) : (
                            <ResponsiveContainer width="100%" height={270}>
                                <ComposedChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 4 }}>
                                    <ChartDefs />
                                    <CartesianGrid {...GRID_PROPS} />
                                    <XAxis dataKey="monthShort" {...AXIS_PROPS} />
                                    <YAxis {...AXIS_PROPS}
                                        tickFormatter={(v) => formatCompactCurrency(v)} width={78} />
                                    <Tooltip content={<TrendTooltip />} cursor={TOOLTIP_PROPS.cursor} />
                                    {/* One stacked series per card type present; the last
                                        (topmost) segment gets the rounded cap. */}
                                    {trendTypes.map((t, i) => (
                                        <Bar key={t} dataKey={t} stackId="vol"
                                            fill={`url(#${gradId(t, 'V')})`} maxBarSize={40}
                                            radius={i === trendTypes.length - 1 ? [3, 3, 0, 0] : undefined}
                                            {...CHART_ANIM(i * 200)} />
                                    ))}
                                </ComposedChart>
                            </ResponsiveContainer>
                        )}
                    </div>
                )}
            </section>

            {/* ── Breakdown ── */}
            <section className="ctd-panel" style={{ padding: '18px 20px 14px', marginBottom: 12 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    marginBottom: 14, flexWrap: 'wrap', gap: 12 }}>
                    <div>
                        <div className="ctd-eyebrow">Breakdown</div>
                        <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                            Card-type volume by {tabLabel}
                        </div>
                    </div>
                    <div className="ctd-seg" role="tablist">
                        {BREAKDOWN_TABS.map(tab => (
                            <button key={tab.key} role="tab" aria-selected={breakdownTab === tab.key}
                                className={`ctd-focus${breakdownTab === tab.key ? ' ctd-seg-on' : ''}`}
                                onClick={() => setBreakdownTab(tab.key)}>
                                {tab.label}
                            </button>
                        ))}
                    </div>
                </div>
                {breakdownError ? (
                    <SectionError message={`The ${tabLabel} breakdown did not load.`}
                        onRetry={() => fetchBreakdown(filters, breakdownTab)} />
                ) : breakdownLoading ? (
                    <SkeletonLoader variant="chart" />
                ) : breakdownChartData.length === 0 ? (
                    <div style={{ padding: '36px 0', textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>
                        No {tabLabel} breakdown for the selected filters and window.
                    </div>
                ) : (
                    <ResponsiveContainer width="100%" height={Math.max(180, breakdownChartData.length * 44)}>
                        <BarChart data={breakdownChartData} layout="vertical"
                            margin={{ top: 4, right: 16, left: 8, bottom: 4 }} barGap={2}>
                            <ChartDefs />
                            <CartesianGrid {...GRID_PROPS} vertical horizontal={false} />
                            <XAxis type="number" {...AXIS_PROPS}
                                tickFormatter={(v) => formatCompactCurrency(v)} />
                            <YAxis type="category" dataKey="name" width={112} {...AXIS_PROPS} />
                            <Tooltip content={<BreakdownTooltip />} cursor={TOOLTIP_PROPS.cursor} />
                            {breakdownTypes.map((t, i) => (
                                <Bar key={t} dataKey={t} stackId="split"
                                    fill={`url(#${gradId(t, 'H')})`} maxBarSize={16}
                                    radius={i === breakdownTypes.length - 1 ? [0, 3, 3, 0] : undefined}
                                    {...CHART_ANIM(i * 200)} />
                            ))}
                        </BarChart>
                    </ResponsiveContainer>
                )}
            </section>

            {/* ── Top merchants ── */}
            {merchantsError ? (
                <SectionError message="The top merchants table did not load." onRetry={() => fetchMerchants(filters)} />
            ) : (
                <Paper sx={premiumTableWrapper}>
                    <div style={{ padding: '16px 20px 13px', borderBottom: '1px solid var(--border-light, var(--border))',
                        display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
                        <div>
                            <div className="ctd-eyebrow">Top merchants</div>
                            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                Highest-volume merchants and their credit / debit / prepaid split
                            </div>
                        </div>
                    </div>
                    <DataGrid rows={merchantRows} columns={merchantColumns} loading={merchantsLoading}
                        rowHeight={46} disableRowSelectionOnClick
                        slots={{ toolbar: GridToolbar }}
                        slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 },
                            printOptions: { disableToolbarButton: true } } }}
                        sx={premiumDataGridStyles}
                    />
                </Paper>
            )}
        </div>
    );
};

export default CardTypeDashboard;
