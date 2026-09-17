import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Paper } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip,
    ResponsiveContainer,
} from 'recharts';
import { Factory, RefreshCw, Download, Filter, AlertTriangle } from 'lucide-react';
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
   Industry Analytics — the acquiring P&L split by INDUSTRY (MCC sector),
   fee waterfall in the house column order:

     MSF → ICF → Net Revenue (MSF − ICF) → Scheme Fee →
     Net Margin (− SF − PG, the batch figure) → Net Spread (+ ancillary)

   The split dimension is the bank's MCC sector sheet (ref_mcc_category),
   the same vocabulary the drawer's Industry dropdown speaks — and the
   attribution is STORE level everywhere: fee lines group on the store's
   MCC (sum_daily_full), and DCC/rental are attributed through each fact
   row's own store_id. FX has no store dimension, so it joins the spread
   on totals only. KPI tiles carry "vs prior window" growth.

   Default window is YTD, anchored on the LATEST LOADED data date
   (this page's own bounds), never on "today".
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

/* Categorical palette — industries are N-valued, so hues cycle in volume
   order. Same gradient language as the Card Type page. */
const PALETTE = [
    { top: '#5E82D2', bottom: '#33518F' }, // steel blue
    { top: '#D9924E', bottom: '#9C5E1F' }, // copper
    { top: '#5FAF87', bottom: '#2F6B4C' }, // green
    { top: '#9B85D6', bottom: '#5F4A9C' }, // violet
    { top: '#D66E85', bottom: '#9C3A52' }, // rose
    { top: '#5BB0C9', bottom: '#2A7288' }, // teal
    { top: '#C9B45B', bottom: '#8A7826' }, // olive gold
    { top: '#94A3B8', bottom: '#475569' }, // slate
];
const indStyle = (i) => PALETTE[i % PALETTE.length];
const indGrad = (i) => {
    const s = indStyle(i);
    return `linear-gradient(135deg, ${s.top} 0%, ${s.bottom} 100%)`;
};

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

/* Presets are computed from the anchor (latest loaded date). YTD is the
   default; "Month before last" completes the requested trio of month picks. */
const PRESETS = [
    { key: 'YTD', label: 'This year' },
    { key: 'MTD', label: 'This month' },
    { key: 'LM',  label: 'Previous month' },
    { key: 'LM2', label: 'Month before last' },
    { key: 'PY',  label: 'Last year' },
    { key: 'CUSTOM', label: 'Custom' },
];
const computeRange = (preset, anchorISO) => {
    const a = parseDay(anchorISO) || new Date();
    switch (preset) {
        case 'MTD': return { startDate: fmtDate(new Date(a.getFullYear(), a.getMonth(), 1)), endDate: fmtDate(a) };
        case 'LM':  return {
            startDate: fmtDate(new Date(a.getFullYear(), a.getMonth() - 1, 1)),
            endDate: fmtDate(new Date(a.getFullYear(), a.getMonth(), 0)),
        };
        case 'LM2': return {
            startDate: fmtDate(new Date(a.getFullYear(), a.getMonth() - 2, 1)),
            endDate: fmtDate(new Date(a.getFullYear(), a.getMonth() - 1, 0)),
        };
        case 'YTD': return { startDate: fmtDate(new Date(a.getFullYear(), 0, 1)), endDate: fmtDate(a) };
        case 'PY':  return {
            startDate: fmtDate(new Date(a.getFullYear() - 1, 0, 1)),
            endDate: fmtDate(new Date(a.getFullYear() - 1, 11, 31)),
        };
        default: return {};
    }
};

const CHART_ANIM = (delayMs = 0) => ({
    isAnimationActive: !prefersReducedMotion(),
    animationDuration: 900,
    animationEasing: 'ease-out',
    animationBegin: delayMs,
});

const EMPTY_LISTS = {
    schemeList: [], destinationList: [], channelList: [], mccList: [],
    cardTypeList: [], midList: [], sidList: [], partnerList: [], rmList: [],
    teamLeaderList: [], industryList: [], sectorList: [], terminalTypeList: [],
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
const Metric = ({ label, value, sub, tone }) => (
    <div style={{ padding: '15px 18px', minWidth: 0 }}>
        <div className="ind-eyebrow">{label}</div>
        <div style={{
            marginTop: 7, fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
            fontSize: 'clamp(16px, 1.3vw, 20px)', fontWeight: 600, letterSpacing: '-0.02em',
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

const IndustryAnalytics = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const { latest: sharedLatest } = useDataBounds(tenantVersion);

    const [showFilters, setShowFilters] = useState(false);
    const [preset, setPreset] = useState('YTD');
    const [filters, setFilters] = useState({ startDate: '', endDate: '', ...EMPTY_LISTS });
    const [filterVersion, setFilterVersion] = useState(0);
    const initRef = useRef(false);

    /* Bounds from THIS page's backing table (sum_daily_full), shared bounds
       only as last-resort fallback — same rationale as the Card Type page. */
    const [pageBounds, setPageBounds] = useState({ latest: null, loaded: false });
    useEffect(() => {
        let cancelled = false;
        setPageBounds({ latest: null, loaded: false });
        (async () => {
            try {
                const res = await api.get('/business/industry-analytics/bounds');
                if (!cancelled) setPageBounds({ latest: res.data?.latest || null, loaded: true });
            } catch (e) {
                console.error('Failed to load industry-analytics data bounds', e);
                if (!cancelled) setPageBounds({ latest: null, loaded: true });
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    const anchor = pageBounds.latest || sharedLatest;

    /* First fetch waits for bounds, then anchors the default YTD window on the
       latest date this page can actually render. */
    useEffect(() => {
        if (!pageBounds.loaded || initRef.current) return;
        initRef.current = true;
        setFilters(f => ({ ...f, ...computeRange('YTD', anchor) }));
        setFilterVersion(v => v + 1);
    }, [pageBounds.loaded, anchor]);
    // Tenant switch: re-anchor on the new tenant's bounds.
    useEffect(() => { initRef.current = false; }, [tenantVersion]);

    // Per-section state so one failing endpoint doesn't blank the page.
    const [rowsData, setRowsData] = useState(null);
    const [rowsLoading, setRowsLoading] = useState(true);
    const [rowsError, setRowsError] = useState(false);
    const [trendData, setTrendData] = useState([]);
    const [trendLoading, setTrendLoading] = useState(true);
    const [trendError, setTrendError] = useState(false);

    const fetchRows = useCallback(async (f) => {
        setRowsLoading(true); setRowsError(false);
        try {
            const res = await api.post('/business/industry-analytics/rows', f);
            setRowsData(res.data);
        } catch (e) {
            console.error('Failed to load industry rows', e);
            setRowsError(true); setRowsData(null);
        } finally { setRowsLoading(false); }
    }, []);

    const fetchTrend = useCallback(async (f) => {
        setTrendLoading(true); setTrendError(false);
        try {
            const res = await api.post('/business/industry-analytics/trend', f);
            setTrendData(res.data || []);
        } catch (e) {
            console.error('Failed to load industry trend', e);
            setTrendError(true); setTrendData([]);
        } finally { setTrendLoading(false); }
    }, []);

    /* filterVersion is the only trigger, so a page open fires each endpoint
       exactly once (after bounds land). */
    useEffect(() => {
        if (!filterVersion) return;
        fetchRows(filters);
        fetchTrend(filters);
    }, [filterVersion, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

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
        ['schemeList', 'destinationList', 'channelList', 'mccList', 'cardTypeList', 'midList', 'sidList',
            'partnerList', 'rmList', 'teamLeaderList', 'industryList', 'sectorList', 'terminalTypeList']
            .reduce((a, k) => a + (filters[k]?.length || 0), 0)
        + (filters.merchantName ? 1 : 0), [filters]);

    /* ── Derived ── */
    const industries = useMemo(() => rowsData?.rows || [], [rowsData]);
    const totals = rowsData?.totals || null;
    const totalVol = num(totals?.volume);
    const pctStr = (v, dp = 2) => (v == null ? '—' : `${Number(v).toFixed(dp)}%`);
    /* "▲ 4.2% vs prior" — only when the equal-length prior window has data. */
    const growthSub = (g, fallback) => {
        if (rowsData?.priorWindowHasData !== true || g == null) return fallback;
        const n = Number(g);
        return `${n >= 0 ? '▲' : '▼'} ${Math.abs(n).toFixed(1)}% vs prior · ${fallback}`;
    };

    const windowLabel = rowsData?.start && rowsData?.end
        ? `${rowsData.start} → ${rowsData.end}`
        : (filters.startDate && filters.endDate ? `${filters.startDate} → ${filters.endDate}` : '—');

    /* Ribbon: industry share of volume, biggest first (backend order). */
    const ribbonBlocks = useMemo(() =>
        industries.filter(r => num(r.volume) > 0).slice(0, 8), [industries]);

    /* ── Trend chart data ── */
    const chartData = useMemo(() => {
        const years = new Set(trendData.map(d => String(d.month || '').slice(0, 4)));
        const multiYear = years.size > 1;
        return trendData.map(d => {
            const [y, m] = String(d.month || '').split('-');
            const dateObj = new Date(parseInt(y), parseInt(m) - 1);
            return {
                month: d.month,
                monthShort: dateObj.toLocaleDateString('en-US', { month: 'short' })
                    + (multiYear ? ` '${String(y).slice(2)}` : ''),
                monthLong: dateObj.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }),
                volume: num(d.volume),
                msf: num(d.msf),
                netMargin: num(d.netMargin),
                netSpread: num(d.netSpread),
            };
        }).sort((a, b) => a.month.localeCompare(b.month));
    }, [trendData]);

    const trendShortfall = useMemo(() => {
        if (!trendData.length || !filters.endDate) return null;
        const lastLoaded = trendData.reduce((a, d) => (String(d.month) > a ? String(d.month) : a), '');
        const requested = String(filters.endDate).slice(0, 7);
        return lastLoaded && lastLoaded < requested ? lastLoaded : null;
    }, [trendData, filters.endDate]);

    const TrendTooltip = ({ active, payload }) => {
        if (!active || !payload?.length) return null;
        const row = payload[0].payload;
        const line = (label, v, color) => (
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                <span style={{ color: 'var(--text-secondary)' }}>{label}</span>
                <span className="ind-num" style={{ fontWeight: 700, color }}>{fmt.currency(v)}</span>
            </div>
        );
        return (
            <div style={TOOLTIP_PROPS.contentStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6 }}>{row.monthLong}</div>
                {line('Volume', row.volume, '#33518F')}
                {line('MSF', row.msf, '#9C5E1F')}
                {line('Net margin', row.netMargin, '#2F6B4C')}
                {line('Net spread', row.netSpread, '#5F4A9C')}
            </div>
        );
    };

    /* ── Industry grid — the requested column set ── */
    const gridRows = useMemo(() => industries.map((r, i) => ({ id: r.industry || i, ...r })), [industries]);
    const moneyCell = (v, style) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : <span className="ind-num" style={style}>{fmt.money(v)}</span>;
    const pctCell = (v, signed = false) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : (
            <span className="ind-num" style={{ fontWeight: 600,
                color: signed ? (num(v) >= 0 ? 'var(--success-text)' : 'var(--danger-text)') : 'var(--text-secondary)' }}>
                {Number(v).toFixed(2)}%
            </span>
        );

    const gridColumns = useMemo(() => [
        {
            field: 'industry', headerName: 'Industry', flex: 1.3, minWidth: 170,
            renderCell: (p) => {
                const idx = industries.findIndex(r => r.industry === p.value);
                return (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontWeight: 600,
                        overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        <span style={{ width: 9, height: 9, borderRadius: 2, flexShrink: 0,
                            background: idx >= 0 ? indGrad(idx) : 'var(--border)' }} />
                        {p.value === 'MIS' ? 'MIS (unmapped MCC)' : p.value}
                    </span>
                );
            },
        },
        {
            field: 'volume', headerName: `Volume (${currencyCode || currencySymbol || ''})`, flex: 1.05, minWidth: 130,
            align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 700 }),
        },
        {
            field: 'txns', headerName: 'Txns', flex: 0.7, minWidth: 85, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => <span className="ind-num" style={{ color: 'var(--text-secondary)' }}>{formatNumber(num(p.value))}</span>,
        },
        {
            field: 'msf', headerName: 'MSF', flex: 0.9, minWidth: 105, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'msfPct', headerName: 'MSF %', flex: 0.65, minWidth: 80, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => pctCell(p.value),
        },
        {
            field: 'icf', headerName: 'ICF', flex: 0.9, minWidth: 105, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'icfPct', headerName: 'ICF %', flex: 0.65, minWidth: 80, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => pctCell(p.value),
        },
        {
            field: 'netRevenue', headerName: 'Net Revenue', flex: 0.95, minWidth: 115, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => p.value == null
                ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                : (
                    <span className="ind-num" style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4, fontWeight: 700,
                        color: num(p.value) >= 0 ? 'var(--success-text)' : 'var(--danger-text)',
                    }}>
                        <span aria-hidden="true" style={{ fontSize: 10 }}>{num(p.value) >= 0 ? '▲' : '▼'}</span>
                        {fmt.money(p.value)}
                    </span>
                ),
        },
        {
            field: 'netRevenuePct', headerName: 'Net Rev %', flex: 0.7, minWidth: 90, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => pctCell(p.value, true),
        },
        {
            field: 'schemeFee', headerName: 'Scheme Fee', flex: 0.9, minWidth: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'schemeFeePct', headerName: 'Scheme Fee %', flex: 0.75, minWidth: 100, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => pctCell(p.value),
        },
        {
            field: 'netMargin', headerName: 'Net Margin', flex: 1, minWidth: 120, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => p.value == null
                ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                : (
                    <span className="ind-num" style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4, fontWeight: 700,
                        color: num(p.value) >= 0 ? 'var(--success-text)' : 'var(--danger-text)',
                    }}>
                        <span aria-hidden="true" style={{ fontSize: 10 }}>{num(p.value) >= 0 ? '▲' : '▼'}</span>
                        {fmt.money(p.value)}
                    </span>
                ),
        },
        {
            field: 'netMarginPct', headerName: 'Net Margin %', flex: 0.8, minWidth: 100, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => pctCell(p.value, true),
        },
        {
            field: 'netSpread', headerName: 'Net Spread', flex: 1, minWidth: 120, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => p.value == null
                ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                : (
                    <span className="ind-num" style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4, fontWeight: 700,
                        color: num(p.value) >= 0 ? 'var(--success-text)' : 'var(--danger-text)',
                    }}>
                        <span aria-hidden="true" style={{ fontSize: 10 }}>{num(p.value) >= 0 ? '▲' : '▼'}</span>
                        {fmt.money(p.value)}
                    </span>
                ),
        },
        {
            field: 'netSpreadPct', headerName: 'Net Spread %', flex: 0.8, minWidth: 100, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => pctCell(p.value, true),
        },
    ], [fmt, industries, currencyCode, currencySymbol]); // eslint-disable-line react-hooks/exhaustive-deps

    const exportRows = useCallback(() => {
        exportToCSV(industries.map(r => ({
            industry: r.industry,
            volume: r.volume, txns: r.txns,
            msf: r.msf, msf_pct: r.msfPct,
            icf: r.icf, icf_pct: r.icfPct,
            net_revenue: r.netRevenue, net_revenue_pct: r.netRevenuePct,
            scheme_fee: r.schemeFee, scheme_fee_pct: r.schemeFeePct,
            net_margin: r.netMargin, net_margin_pct: r.netMarginPct,
            dcc_rental: r.ancillary,
            net_spread: r.netSpread, net_spread_pct: r.netSpreadPct,
        })), 'industry_analytics');
    }, [industries]);

    return (
        <div style={{
            padding: 'var(--space-page, 20px)', background: 'var(--bg)',
            minHeight: '100vh', width: '100%', boxSizing: 'border-box',
        }}>
            <style>{`
                .ind-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }
                .ind-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .ind-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); }

                /* ── Navy masthead + command deck (house executive header) ── */
                .ind-panel.ind-hdrblock { background: var(--table-head-bg,
                        linear-gradient(135deg, #24386B 0%, #16264A 55%, #0A1426 100%));
                    border-color: transparent; overflow: visible; margin-bottom: 12px; }
                .ind-hdrblock > :first-child { border-radius: calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px) 0 0; }
                .ind-hdrblock > :last-child { border-radius: 0 0 calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px); }
                .ind-mast { padding: 20px 24px 18px; display: flex; justify-content: space-between;
                    align-items: flex-end; gap: 18px; flex-wrap: wrap; }
                .ind-mast-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.18em; text-transform: uppercase;
                    color: var(--table-head-muted, #93A3C6); }
                .ind-mast h1 { margin: 8px 0 0; font-size: 26px; font-weight: 700;
                    letter-spacing: -0.025em; line-height: 1.08;
                    color: var(--table-head-text, #EEF3FC); }
                .ind-mast-sub { margin: 6px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .ind-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.22);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .ind-mast-btn:hover { background: rgba(255,255,255,0.14); }
                .ind-mast-btn:disabled { opacity: 0.5; cursor: default; }
                .ind-cmdbar { display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
                    padding: 10px 18px; background: rgba(0,0,0,0.20);
                    border-top: 1px solid rgba(255,255,255,0.13); }
                .ind-preset { padding: 7px 13px; font-size: 12px; font-weight: 600; cursor: pointer;
                    color: rgba(238,243,252,0.72); background: transparent; border: 0;
                    border-radius: var(--radius-sm); transition: background .12s ease, color .12s ease; }
                .ind-preset:hover { background: rgba(255,255,255,0.08); color: #EEF3FC; }
                .ind-preset-on { background: rgba(255,255,255,0.14); color: #EEF3FC;
                    box-shadow: inset 0 -2px 0 var(--chart-4, #7191CE); }
                .ind-date { padding: 6px 9px; font-size: 12px; font-family: var(--font-mono);
                    color: #EEF3FC; background: rgba(255,255,255,0.07);
                    border: 1px solid rgba(255,255,255,0.22); border-radius: var(--radius-sm);
                    outline: none; color-scheme: dark; }

                /* ── Share ribbon — sweeps in on load, widths glide on change ── */
                @keyframes indSweep { from { transform: scaleX(0); } to { transform: scaleX(1); } }
                .ind-ribbon { display: flex; height: 12px; border-radius: 999px; overflow: hidden;
                    background: var(--border-light, var(--border));
                    transform-origin: left; animation: indSweep .8s ease-out; }
                .ind-ribbon > div { transition: width .7s cubic-bezier(0.22, 1, 0.36, 1); }
                @media (prefers-reduced-motion: reduce) {
                    .ind-ribbon { animation: none; }
                    .ind-ribbon > div { transition: none; }
                }

                .ind-focus:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
                .ind-cmdbar .ind-focus:focus-visible { outline-color: #EEF3FC; outline-offset: -2px; }
                .ind-cell { border-right: 1px solid var(--border-light, var(--border)); }
                @media (prefers-reduced-motion: reduce) {
                    .ind-preset, .ind-mast-btn { transition: none; }
                }
            `}</style>

            {/* ── Masthead + command deck ── */}
            <section className="ind-panel ind-hdrblock">
                <div className="ind-mast">
                    <div>
                        <div className="ind-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <Factory size={11} /> Business · Industry
                        </div>
                        <h1>Industry Analytics</h1>
                        <p className="ind-mast-sub">
                            Volume, MSF, ICF, scheme fee, net revenue &amp; net spread by industry
                            · {windowLabel} · {currencyCode || currencySymbol || ''}
                        </p>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                        <button className="ind-focus ind-mast-btn" onClick={() => setShowFilters(s => !s)}>
                            <Filter size={13} /> Filters{activeFilterCount > 0 ? ` (${activeFilterCount})` : ''}
                        </button>
                        <button className="ind-focus ind-mast-btn" onClick={exportRows} disabled={!industries.length}>
                            <Download size={13} /> Export
                        </button>
                        <button className="ind-focus ind-mast-btn" onClick={run} title="Refresh" aria-label="Refresh"
                            style={{ padding: '9px 11px' }}>
                            <RefreshCw size={14} className={rowsLoading ? 'animate-spin' : ''} />
                        </button>
                    </div>
                </div>
                <div className="ind-cmdbar">
                    <span className="ind-mast-eyebrow" style={{ marginRight: 10 }}>Window</span>
                    {PRESETS.map(p => (
                        <button key={p.key}
                            className={`ind-focus ind-preset${preset === p.key ? ' ind-preset-on' : ''}`}
                            onClick={() => pickPreset(p.key)}>
                            {p.label}
                        </button>
                    ))}
                    {preset === 'CUSTOM' && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, marginLeft: 6 }}>
                            <input type="date" className="ind-date" value={filters.startDate}
                                onChange={e => setCustomDate('startDate', e.target.value)} aria-label="From date" />
                            <span style={{ color: 'rgba(238,243,252,0.5)', fontSize: 11 }}>to</span>
                            <input type="date" className="ind-date" value={filters.endDate}
                                onChange={e => setCustomDate('endDate', e.target.value)} aria-label="To date" />
                            <button className="ind-focus ind-mast-btn" style={{ padding: '6px 13px' }} onClick={run}>
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
            />

            {/* ── P&L band + industry share ribbon ── */}
            {rowsError ? (
                <div style={{ marginBottom: 12 }}>
                    <SectionError message="The industry P&L did not load." onRetry={() => fetchRows(filters)} />
                </div>
            ) : rowsLoading ? (
                <div style={{ marginBottom: 12 }}><SkeletonLoader variant="kpi-row" count={6} /></div>
            ) : totals && (
                <section className="ind-panel" style={{ marginBottom: 12, overflow: 'hidden' }}>
                    <div style={{
                        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
                        borderBottom: '1px solid var(--border-light, var(--border))',
                    }}>
                        <div className="ind-cell">
                            <Metric label={`Volume (${currencyCode || currencySymbol || ''})`}
                                value={fmt.currency(totalVol)}
                                sub={growthSub(totals.volumeGrowthPct,
                                    `${formatNumber(num(totals.txns))} txns`)} />
                        </div>
                        <div className="ind-cell">
                            <Metric label="MSF" value={fmt.currency(num(totals.msf))}
                                sub={growthSub(totals.msfGrowthPct, `${pctStr(totals.msfPct)} of volume`)} />
                        </div>
                        <div className="ind-cell">
                            <Metric label="Interchange (ICF)" value={fmt.currency(num(totals.icf))}
                                sub={`${pctStr(totals.icfPct)} of volume`} />
                        </div>
                        <div className="ind-cell">
                            <Metric label="Net revenue" value={fmt.currency(num(totals.netRevenue))}
                                tone={num(totals.netRevenue) >= 0 ? 'success' : 'danger'}
                                sub={`MSF − ICF · ${pctStr(totals.netRevenuePct)}`} />
                        </div>
                        <div className="ind-cell">
                            <Metric label="Scheme fee" value={fmt.currency(num(totals.schemeFee))}
                                sub={`${pctStr(totals.schemeFeePct)} of volume`} />
                        </div>
                        <div className="ind-cell">
                            <Metric label="Net margin" value={fmt.currency(num(totals.netMargin))}
                                tone={num(totals.netMargin) >= 0 ? 'success' : 'danger'}
                                sub={growthSub(totals.netMarginGrowthPct,
                                    `after SF & PG · ${pctStr(totals.netMarginPct)}`)} />
                        </div>
                        <div>
                            <Metric label="Net spread" value={fmt.currency(num(totals.netSpread))}
                                tone={num(totals.netSpread) >= 0 ? 'success' : 'danger'}
                                sub={growthSub(totals.netSpreadGrowthPct,
                                    `${pctStr(totals.netSpreadPct)} of volume`)} />
                        </div>
                    </div>
                    <div style={{ padding: '15px 20px 16px' }}>
                        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
                            <span className="ind-eyebrow">Where the volume comes from</span>
                            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                {totalVol > 0 ? `top industries by processed volume`
                                    : 'no volume in this window'}
                            </span>
                        </div>
                        {totalVol > 0 ? (
                            <>
                                <div className="ind-ribbon">
                                    {ribbonBlocks.map((b, i) => (
                                        <div key={b.industry}
                                            title={`${b.industry} · ${fmt.currency(num(b.volume))}`}
                                            style={{ width: `${num(b.volume) / totalVol * 100}%`, background: indGrad(i),
                                                borderRight: i < ribbonBlocks.length - 1
                                                    ? '1px solid var(--bg-card)' : undefined }} />
                                    ))}
                                </div>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '5px 20px', marginTop: 9 }}>
                                    {ribbonBlocks.map((b, i) => (
                                        <span key={b.industry} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                            fontSize: 11, color: 'var(--text-secondary)' }}>
                                            <span style={{ width: 14, height: 9, borderRadius: 2, background: indGrad(i) }} />
                                            <span style={{ fontWeight: 600, color: 'var(--text)' }}>{b.industry}</span>
                                            <span className="ind-num">{fmt.currency(num(b.volume))}</span>
                                            <span style={{ opacity: 0.72 }}>{(num(b.volume) / totalVol * 100).toFixed(1)}%</span>
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
                        {rowsData?.ancillaryApproximate && (
                            <div style={{ fontSize: 11.5, color: 'var(--warning-text, #92400e)', marginTop: 10 }}>
                                A scheme / channel / card-type / destination / SID filter is active — DCC, rental and FX
                                income have no such dimension, so the net spread's ancillary part stays merchant-level.
                            </div>
                        )}
                    </div>
                </section>
            )}

            {/* ── Monthly trend ── */}
            <section style={{ marginBottom: 12 }}>
                {trendError ? (
                    <SectionError message="The monthly trend did not load." onRetry={() => fetchTrend(filters)} />
                ) : trendLoading ? (
                    <SkeletonLoader variant="chart" />
                ) : (
                    <div className="ind-panel" style={{ padding: '18px 20px 10px' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                            marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
                            <div>
                                <div className="ind-eyebrow">Monthly trend · selected window</div>
                                <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                    Volume (bars, left) vs net margin &amp; net spread (lines, right)
                                </div>
                                {trendShortfall && (
                                    <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--warning-text, #92400e)', marginTop: 4 }}>
                                        Data loaded through {trendShortfall} — later months in this window have no data yet.
                                    </div>
                                )}
                            </div>
                            <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                    fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                    <span style={{ width: 14, height: 9, borderRadius: 2,
                                        background: 'linear-gradient(135deg, #5E82D2 0%, #33518F 100%)' }} />
                                    Volume
                                </span>
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                    fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                    <span style={{ width: 14, height: 3, borderRadius: 2, background: '#2F6B4C' }} />
                                    Net margin
                                </span>
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                    fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                    <span style={{ width: 14, height: 3, borderRadius: 2, background: '#5F4A9C' }} />
                                    Net spread
                                </span>
                            </div>
                        </div>
                        {chartData.length === 0 ? (
                            <div style={{ padding: '42px 0', textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>
                                No monthly data for the selected filters.
                            </div>
                        ) : (
                            <ResponsiveContainer width="100%" height={270}>
                                <ComposedChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 4 }}>
                                    <defs>
                                        <linearGradient id="indVolGrad" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="0%" stopColor="#5E82D2" />
                                            <stop offset="100%" stopColor="#33518F" />
                                        </linearGradient>
                                    </defs>
                                    <CartesianGrid {...GRID_PROPS} />
                                    <XAxis dataKey="monthShort" {...AXIS_PROPS} />
                                    <YAxis yAxisId="vol" {...AXIS_PROPS}
                                        tickFormatter={(v) => formatCompactCurrency(v)} width={78} />
                                    <YAxis yAxisId="rev" orientation="right" {...AXIS_PROPS}
                                        tickFormatter={(v) => formatCompactCurrency(v)} width={72} />
                                    <Tooltip content={<TrendTooltip />} cursor={TOOLTIP_PROPS.cursor} />
                                    <Bar yAxisId="vol" dataKey="volume" fill="url(#indVolGrad)"
                                        maxBarSize={40} radius={[3, 3, 0, 0]} {...CHART_ANIM(0)} />
                                    <Line yAxisId="rev" dataKey="netMargin" stroke="#2F6B4C" strokeWidth={2.5}
                                        dot={{ r: 3, strokeWidth: 0, fill: '#2F6B4C' }} {...CHART_ANIM(250)} />
                                    <Line yAxisId="rev" dataKey="netSpread" stroke="#5F4A9C" strokeWidth={2.5}
                                        dot={{ r: 3, strokeWidth: 0, fill: '#5F4A9C' }} {...CHART_ANIM(450)} />
                                </ComposedChart>
                            </ResponsiveContainer>
                        )}
                    </div>
                )}
            </section>

            {/* ── Industry table — the full requested column set ── */}
            {rowsError ? (
                <SectionError message="The industry table did not load." onRetry={() => fetchRows(filters)} />
            ) : (
                <Paper sx={premiumTableWrapper}>
                    <div style={{ padding: '16px 20px 13px', borderBottom: '1px solid var(--border-light, var(--border))',
                        display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
                        <div>
                            <div className="ind-eyebrow">Industry P&amp;L</div>
                            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                One row per industry (STORE MCC sector) · net revenue = MSF − ICF ·
                                net margin = MSF − ICF − SF − PG · net spread = net margin + DCC + rental
                                (store-attributed{rowsData?.fxEnabled ? '; FX joins totals only' : ''})
                            </div>
                        </div>
                    </div>
                    <DataGrid rows={gridRows} columns={gridColumns} loading={rowsLoading}
                        rowHeight={46} disableRowSelectionOnClick
                        initialState={{ sorting: { sortModel: [{ field: 'volume', sort: 'desc' }] } }}
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

export default IndustryAnalytics;
