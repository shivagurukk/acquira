import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Paper } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    ComposedChart, BarChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip,
    ResponsiveContainer,
} from 'recharts';
import { Globe2, RefreshCw, Download, Filter, AlertTriangle } from 'lucide-react';
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
   Destination Dashboard — where the volume comes from: DOMESTIC vs
   INTERNATIONAL, told as one proportion and its consequences.

   Register matches the Executive Daily Merchant page: navy masthead +
   command deck, hairline-divided metric band, and a signature SPLIT
   RIBBON (steel = domestic, copper = international) that every panel
   below echoes — trend bars, breakdown bars, per-merchant share bars.

   Date presets are anchored on the LATEST LOADED data date (data-bounds),
   never on "today" — the old page defaulted to a 30-day window past the
   data and opened on a wall of zeros.
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

/* Series identity for the whole page — BLUE is domestic, GREY is international,
   each drawn as a soft gradient of its own hue so the bars read with depth:
     Domestic       steel blue   #5E82D2 → #33518F
     International  slate grey   #94A3B8 → #475569
   Legends say the colour in words as well, so the mapping is never guessed. */
const C_DOM  = 'var(--cat-1, #3F63B0)';
const C_INTL = 'var(--chart-alt, #64748B)';
const C_LINE = 'var(--chart-line, #24386B)';
const GRAD_DOM  = 'linear-gradient(135deg, #5E82D2 0%, #33518F 100%)';
const GRAD_INTL = 'linear-gradient(135deg, #94A3B8 0%, #475569 100%)';

/* SVG gradient stops for the recharts bars — V for vertical bars (trend),
   H for the horizontal breakdown bars. */
const ChartDefs = () => (
    <defs>
        <linearGradient id="ddGradDomV" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#5E82D2" />
            <stop offset="100%" stopColor="#33518F" />
        </linearGradient>
        <linearGradient id="ddGradIntlV" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#94A3B8" />
            <stop offset="100%" stopColor="#475569" />
        </linearGradient>
        <linearGradient id="ddGradDomH" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#33518F" />
            <stop offset="100%" stopColor="#5E82D2" />
        </linearGradient>
        <linearGradient id="ddGradIntlH" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#475569" />
            <stop offset="100%" stopColor="#94A3B8" />
        </linearGradient>
    </defs>
);

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

/* Chart motion: a deliberate rise from the baseline, staggered when two
   series share a chart; off entirely under prefers-reduced-motion. */
const CHART_ANIM = (delayMs = 0) => ({
    isAnimationActive: !prefersReducedMotion(),
    animationDuration: 900,
    animationEasing: 'ease-out',
    animationBegin: delayMs,
});

const BREAKDOWN_TABS = [
    { key: 'scheme',   label: 'Scheme' },
    { key: 'cardType', label: 'Card type' },
    { key: 'channel',  label: 'Channel' },
    { key: 'mcc',      label: 'MCC' },
];

const EMPTY_LISTS = {
    schemeList: [], cardTypeList: [], channelList: [], mccList: [],
    midList: [], sidList: [], partnerList: [], rmList: [], teamLeaderList: [],
    industryList: [], sectorList: [], terminalTypeList: [],
    // destinationList intentionally absent — destination IS this page's split.
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
        <div className="dd-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
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

const DestinationDashboard = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const { latest: sharedLatest } = useDataBounds(tenantVersion);

    const [showFilters, setShowFilters] = useState(false);
    const [preset, setPreset] = useState('D30');
    const [filters, setFilters] = useState({ startDate: '', endDate: '', ...EMPTY_LISTS });
    const [filterVersion, setFilterVersion] = useState(0);
    const initRef = useRef(false);

    /* Bounds come from THIS page's own backing table
       (sum_daily_merchant_destination), not the shared /business/data-bounds.
       The shared endpoint is fact_transaction-anchored and can point at a
       range this table has no rows for — worse, when fact is empty it returns
       null and the presets silently fell back to TODAY, so "This year" spanned
       months past the loaded data (the 2-month YTD symptom). The shared value
       is kept only as a last-resort fallback. */
    const [pageBounds, setPageBounds] = useState({ latest: null, loaded: false });
    useEffect(() => {
        let cancelled = false;
        setPageBounds({ latest: null, loaded: false });
        (async () => {
            try {
                const res = await api.get('/business/destination-dashboard/bounds');
                if (!cancelled) setPageBounds({ latest: res.data?.latest || null, loaded: true });
            } catch (e) {
                console.error('Failed to load destination data bounds', e);
                if (!cancelled) setPageBounds({ latest: null, loaded: true });
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    /* The date every preset is measured back from. */
    const latest = pageBounds.latest || sharedLatest;

    /* First fetch waits for those bounds, then anchors the default window on
       the latest date this page can actually render. */
    useEffect(() => {
        if (!pageBounds.loaded || initRef.current) return;
        initRef.current = true;
        setFilters(f => ({ ...f, ...computeRange('D30', latest) }));
        setFilterVersion(v => v + 1);
    }, [pageBounds.loaded, latest]);
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
            const res = await api.post('/business/destination-dashboard/kpis', f);
            setKpiData(res.data);
        } catch (e) {
            console.error('Failed to load destination KPIs', e);
            setKpiError(true); setKpiData(null);
        } finally { setKpiLoading(false); }
    }, []);

    const fetchTrend = useCallback(async (f) => {
        setTrendLoading(true); setTrendError(false);
        try {
            // The trend honours the SELECTED window — YTD shows this year's
            // months only. (It used to force a 12-month default, which pulled
            // last year's months into a YTD view.)
            const res = await api.post('/business/destination-dashboard/trend', f);
            setTrendData(res.data || []);
        } catch (e) {
            console.error('Failed to load destination trend', e);
            setTrendError(true); setTrendData([]);
        } finally { setTrendLoading(false); }
    }, []);

    const fetchBreakdown = useCallback(async (f, dimension) => {
        setBreakdownLoading(true); setBreakdownError(false);
        try {
            const res = await api.post(`/business/destination-dashboard/breakdown/${dimension}`, f);
            setBreakdownData(res.data || []);
        } catch (e) {
            console.error('Failed to load destination breakdown', e);
            setBreakdownError(true); setBreakdownData([]);
        } finally { setBreakdownLoading(false); }
    }, []);

    const fetchMerchants = useCallback(async (f) => {
        setMerchantsLoading(true); setMerchantsError(false);
        try {
            const res = await api.post('/business/destination-dashboard/top-merchants?limit=15', f);
            setMerchantsData(res.data || []);
        } catch (e) {
            console.error('Failed to load destination top merchants', e);
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
            setFilters(f => ({ ...f, ...computeRange(key, latest) }));
            setFilterVersion(v => v + 1);
        }
    };
    const setCustomDate = (key, value) => {
        setPreset('CUSTOM');
        setFilters(f => ({ ...f, [key]: value }));
    };
    const handleAdvancedFilterChange = useCallback((next) => setFilters(next), []);

    const activeFilterCount = useMemo(() =>
        ['schemeList', 'cardTypeList', 'channelList', 'mccList', 'midList', 'sidList',
            'partnerList', 'rmList', 'teamLeaderList', 'industryList', 'sectorList', 'terminalTypeList']
            .reduce((a, k) => a + (filters[k]?.length || 0), 0)
        + (filters.merchantName ? 1 : 0), [filters]);

    /* ── Derived: the split ── */
    const dom = kpiData?.domestic || {};
    const intl = kpiData?.international || {};
    const totalVol = num(dom.volume) + num(intl.volume);
    const intlShare = num(kpiData?.internationalSharePct);
    const priorHasData = kpiData?.priorWindowHasData === true;
    const growth = (v) => {
        if (!priorHasData || v == null) return null;
        const n = Number(v);
        return `${n >= 0 ? '▲' : '▼'} ${Math.abs(n).toFixed(1)}% vs prior`;
    };

    const windowLabel = kpiData?.start && kpiData?.end
        ? `${kpiData.start} → ${kpiData.end}`
        : (filters.startDate && filters.endDate ? `${filters.startDate} → ${filters.endDate}` : '—');

    /* ── Trend chart ── */
    const chartData = useMemo(() => {
        // When the window crosses a year boundary the axis says so ("Aug '25"),
        // so a bar can never be mistaken for the current year's month.
        const years = new Set(trendData.map(d => String(d.month || '').slice(0, 4)));
        const multiYear = years.size > 1;
        return trendData.map(d => {
            const [y, m] = String(d.month || '').split('-');
            const dateObj = new Date(parseInt(y), parseInt(m) - 1);
            const dv = num(d.domVolume), iv = num(d.intlVolume);
            return {
                monthShort: dateObj.toLocaleDateString('en-US', { month: 'short' })
                    + (multiYear ? ` '${String(y).slice(2)}` : ''),
                monthLong: dateObj.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }),
                domVolume: dv, intlVolume: iv,
                domMsf: num(d.domMsf), intlMsf: num(d.intlMsf),
                intlSharePct: dv + iv > 0 ? (iv / (dv + iv)) * 100 : 0,
            };
        });
    }, [trendData]);

    /* If the response covers fewer months than the requested window (data lag
       or a summary-table gap), say so instead of rendering a silently short
       chart — "This year = 2 bars" must read as a data condition, not a bug. */
    const trendShortfall = useMemo(() => {
        if (!trendData.length || !filters.endDate) return null;
        const lastLoaded = trendData.reduce((a, d) => (String(d.month) > a ? String(d.month) : a), '');
        const requested = String(filters.endDate).slice(0, 7);
        return lastLoaded && lastLoaded < requested ? lastLoaded : null;
    }, [trendData, filters.endDate]);

    const TrendTooltip = ({ active, payload }) => {
        if (!active || !payload?.length) return null;
        const row = payload[0].payload;
        const line = (label, value, color) => (
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                <span style={{ color: 'var(--text-secondary)' }}>{label}</span>
                <span className="dd-num" style={{ fontWeight: 700, color: color || 'inherit' }}>{value}</span>
            </div>
        );
        return (
            <div style={TOOLTIP_PROPS.contentStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6 }}>{row.monthLong}</div>
                {line('Domestic volume', fmt.currency(row.domVolume), C_DOM)}
                {line('International volume', fmt.currency(row.intlVolume), C_INTL)}
            </div>
        );
    };

    /* ── Breakdown: stacked share bars, biggest first ── */
    const breakdownChartData = useMemo(() => breakdownData
        .map(d => ({
            name: d.dimensionValue || 'Unknown',
            domVolume: num(d.domVolume), intlVolume: num(d.intlVolume),
            total: num(d.domVolume) + num(d.intlVolume),
        }))
        .sort((a, b) => b.total - a.total), [breakdownData]);

    const BreakdownTooltip = ({ active, payload }) => {
        if (!active || !payload?.length) return null;
        const row = payload[0].payload;
        const share = row.total ? ((row.intlVolume / row.total) * 100).toFixed(1) : '0.0';
        return (
            <div style={TOOLTIP_PROPS.contentStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6 }}>{row.name}</div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18 }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Domestic</span>
                    <span className="dd-num" style={{ fontWeight: 700 }}>{fmt.currency(row.domVolume)}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                    <span style={{ color: 'var(--text-secondary)' }}>International</span>
                    <span className="dd-num" style={{ fontWeight: 700 }}>{fmt.currency(row.intlVolume)}</span>
                </div>
            </div>
        );
    };

    /* ── Top merchants grid ── */
    const merchantRows = useMemo(() => merchantsData.map((m, i) => ({ id: m.mid || i, ...m })), [merchantsData]);
    /* Money cell that treats null as "not available" (the cardholder-basis
       fallback carries no fee columns) — a dash, never a fabricated zero. */
    const moneyCell = (v, style) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : <span className="dd-num" style={style}>{fmt.money(v)}</span>;

    const merchantColumns = useMemo(() => [
        {
            field: 'mid', headerName: 'MID', width: 120,
            renderCell: (p) => <span className="dd-num" style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>{p.value || '—'}</span>,
        },
        {
            field: 'merchantName', headerName: 'Merchant', flex: 1.3, minWidth: 180,
            renderCell: (p) => <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.value}</span>,
        },
        {
            field: 'domVolume', headerName: 'Domestic Vol', flex: 1, minWidth: 115, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'intlVolume', headerName: 'Intl Vol', flex: 1, minWidth: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'domMsf', headerName: 'Domestic MSF', flex: 0.95, minWidth: 115, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'intlMsf', headerName: 'Intl MSF', flex: 0.9, minWidth: 100, align: 'right', headerAlign: 'right', type: 'number',
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
                    <span className="dd-num" style={{
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
                    <span className="dd-num" style={{ fontWeight: 700,
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
                .dd-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }
                .dd-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .dd-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); }

                /* ── Navy masthead + command deck (house executive header) ── */
                .dd-panel.dd-hdrblock { background: var(--table-head-bg,
                        linear-gradient(135deg, #24386B 0%, #16264A 55%, #0A1426 100%));
                    border-color: transparent; overflow: visible; margin-bottom: 12px; }
                .dd-hdrblock > :first-child { border-radius: calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px) 0 0; }
                .dd-hdrblock > :last-child { border-radius: 0 0 calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px); }
                .dd-mast { padding: 20px 24px 18px; display: flex; justify-content: space-between;
                    align-items: flex-end; gap: 18px; flex-wrap: wrap; }
                .dd-mast-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.18em; text-transform: uppercase;
                    color: var(--table-head-muted, #93A3C6); }
                .dd-mast h1 { margin: 8px 0 0; font-size: 26px; font-weight: 700;
                    letter-spacing: -0.025em; line-height: 1.08;
                    color: var(--table-head-text, #EEF3FC); }
                .dd-mast-sub { margin: 6px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .dd-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.22);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .dd-mast-btn:hover { background: rgba(255,255,255,0.14); }
                .dd-mast-btn:disabled { opacity: 0.5; cursor: default; }
                .dd-cmdbar { display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
                    padding: 10px 18px; background: rgba(0,0,0,0.20);
                    border-top: 1px solid rgba(255,255,255,0.13); }
                .dd-preset { padding: 7px 13px; font-size: 12px; font-weight: 600; cursor: pointer;
                    color: rgba(238,243,252,0.72); background: transparent; border: 0;
                    border-radius: var(--radius-sm); transition: background .12s ease, color .12s ease; }
                .dd-preset:hover { background: rgba(255,255,255,0.08); color: #EEF3FC; }
                .dd-preset-on { background: rgba(255,255,255,0.14); color: #EEF3FC;
                    box-shadow: inset 0 -2px 0 var(--chart-4, #7191CE); }
                .dd-date { padding: 6px 9px; font-size: 12px; font-family: var(--font-mono);
                    color: #EEF3FC; background: rgba(255,255,255,0.07);
                    border: 1px solid rgba(255,255,255,0.22); border-radius: var(--radius-sm);
                    outline: none; color-scheme: dark; }

                /* ── Split ribbon: sweeps in from the left on load, and the
                   segment widths glide when the window or filters change. ── */
                @keyframes ddSweep { from { transform: scaleX(0); } to { transform: scaleX(1); } }
                .dd-ribbon { display: flex; height: 12px; border-radius: 999px; overflow: hidden;
                    background: var(--border-light, var(--border));
                    transform-origin: left; animation: ddSweep .8s ease-out; }
                .dd-ribbon > div { transition: width .7s cubic-bezier(0.22, 1, 0.36, 1); }
                .dd-splitbar > div { transition: width .7s cubic-bezier(0.22, 1, 0.36, 1); }
                @media (prefers-reduced-motion: reduce) {
                    .dd-ribbon { animation: none; }
                    .dd-ribbon > div, .dd-splitbar > div { transition: none; }
                }

                .dd-focus:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
                .dd-cmdbar .dd-focus:focus-visible { outline-color: #EEF3FC; outline-offset: -2px; }
                .dd-seg { display: inline-flex; background: var(--bg-card);
                    border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 3px; }
                .dd-seg button { border: 0; cursor: pointer; border-radius: calc(var(--radius-sm) - 2px);
                    padding: 6px 13px; font-size: 12px; font-weight: 600; white-space: nowrap;
                    background: transparent; color: var(--text-secondary);
                    transition: background .12s ease, color .12s ease; }
                .dd-seg button:hover { color: var(--text); background: var(--bg-hover); }
                .dd-seg .dd-seg-on { background: var(--primary); color: #fff; }
                .dd-cell { border-right: 1px solid var(--border-light, var(--border)); }
                @media (prefers-reduced-motion: reduce) {
                    .dd-preset, .dd-mast-btn, .dd-seg button { transition: none; }
                }
            `}</style>

            {/* ── Masthead + command deck ── */}
            <section className="dd-panel dd-hdrblock">
                <div className="dd-mast">
                    <div>
                        <div className="dd-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <Globe2 size={11} /> Business · Destination
                        </div>
                        <h1>Destination Dashboard</h1>
                        <p className="dd-mast-sub">
                            Domestic vs international acquiring · {windowLabel} · {currencyCode || currencySymbol || ''}
                        </p>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                        <button className="dd-focus dd-mast-btn" onClick={() => setShowFilters(s => !s)}>
                            <Filter size={13} /> Filters{activeFilterCount > 0 ? ` (${activeFilterCount})` : ''}
                        </button>
                        <button className="dd-focus dd-mast-btn"
                            onClick={() => exportToCSV(merchantRows, 'destination_top_merchants')}
                            disabled={!merchantRows.length}>
                            <Download size={13} /> Export
                        </button>
                        <button className="dd-focus dd-mast-btn" onClick={run} title="Refresh" aria-label="Refresh"
                            style={{ padding: '9px 11px' }}>
                            <RefreshCw size={14} className={kpiLoading ? 'animate-spin' : ''} />
                        </button>
                    </div>
                </div>
                <div className="dd-cmdbar">
                    <span className="dd-mast-eyebrow" style={{ marginRight: 10 }}>Window</span>
                    {PRESETS.map(p => (
                        <button key={p.key}
                            className={`dd-focus dd-preset${preset === p.key ? ' dd-preset-on' : ''}`}
                            onClick={() => pickPreset(p.key)}>
                            {p.label}
                        </button>
                    ))}
                    {preset === 'CUSTOM' && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, marginLeft: 6 }}>
                            <input type="date" className="dd-date" value={filters.startDate}
                                onChange={e => setCustomDate('startDate', e.target.value)} aria-label="From date" />
                            <span style={{ color: 'rgba(238,243,252,0.5)', fontSize: 11 }}>to</span>
                            <input type="date" className="dd-date" value={filters.endDate}
                                onChange={e => setCustomDate('endDate', e.target.value)} aria-label="To date" />
                            <button className="dd-focus dd-mast-btn" style={{ padding: '6px 13px' }} onClick={run}>
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
                hideDestination
            />

            {/* ── The split: metric band + signature ribbon ── */}
            {kpiError ? (
                <div style={{ marginBottom: 12 }}>
                    <SectionError message="The destination KPIs did not load." onRetry={() => fetchKpis(filters)} />
                </div>
            ) : kpiLoading ? (
                <div style={{ marginBottom: 12 }}><SkeletonLoader variant="kpi-row" count={4} /></div>
            ) : kpiData && (
                <section className="dd-panel" style={{ marginBottom: 12, overflow: 'hidden' }}>
                    <div style={{
                        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                        borderBottom: '1px solid var(--border-light, var(--border))',
                    }}>
                        <div className="dd-cell">
                            <Metric label="Domestic volume (blue)" accent={GRAD_DOM}
                                value={fmt.currency(num(dom.volume))}
                                sub={growth(dom.volumeGrowthPct)
                                    || `${formatNumber(dom.txns)} txns · ${num(dom.effectiveRateBps).toFixed(1)} bps`} />
                        </div>
                        <div className="dd-cell">
                            <Metric label="International volume (grey)" accent={GRAD_INTL}
                                value={fmt.currency(num(intl.volume))}
                                sub={growth(intl.volumeGrowthPct)
                                    || (kpiData.basis !== 'SETTLEMENT'
                                        ? 'summed in cardholder currencies' : `${formatNumber(intl.txns)} txns`)} />
                        </div>
                        <div className="dd-cell">
                            <Metric label="International share"
                                value={`${intlShare.toFixed(1)}%`}
                                sub={`domestic ${num(kpiData.domesticSharePct).toFixed(1)}%`} />
                        </div>
                        <div>
                            <Metric label="DCC opt-in"
                                value={`${num(intl.dccOptInRatePct).toFixed(1)}%`}
                                sub={`missed ${fmt.currency(num(intl.dccMissedVolume))}`}
                                tone={num(intl.dccMissedVolume) > 0 ? 'danger' : undefined} />
                        </div>
                    </div>
                    <div style={{ padding: '15px 20px 16px' }}>
                        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
                            <span className="dd-eyebrow">Where the volume comes from</span>
                            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                {totalVol > 0 ? `${fmt.currency(totalVol)} total in this window`
                                    : 'no volume in this window'}
                            </span>
                        </div>
                        {totalVol > 0 ? (
                            <>
                                <div className="dd-ribbon">
                                    <div title={`Domestic (blue) · ${fmt.currency(num(dom.volume))}`}
                                        style={{ width: `${100 - intlShare}%`, background: GRAD_DOM,
                                            borderRight: '1px solid var(--bg-card)' }} />
                                    <div title={`International (grey) · ${fmt.currency(num(intl.volume))}`}
                                        style={{ width: `${intlShare}%`, background: GRAD_INTL }} />
                                </div>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '5px 20px', marginTop: 9 }}>
                                    {[
                                        ['Domestic', 'blue', dom.volume, GRAD_DOM, 100 - intlShare],
                                        ['International', 'grey', intl.volume, GRAD_INTL, intlShare],
                                    ].map(([lbl, colorName, v, g, pct]) => (
                                        <span key={lbl} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                            fontSize: 11, color: 'var(--text-secondary)' }}>
                                            <span style={{ width: 14, height: 9, borderRadius: 2, background: g }} />
                                            <span style={{ fontWeight: 600, color: 'var(--text)' }}>{lbl}</span>
                                            <span style={{ opacity: 0.65 }}>({colorName})</span>
                                            <span className="dd-num">{fmt.currency(num(v))}</span>
                                            <span style={{ opacity: 0.72 }}>{Number(pct).toFixed(1)}%</span>
                                        </span>
                                    ))}
                                </div>
                            </>
                        ) : (
                            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                                Nothing was processed between {windowLabel}. Pick a window inside the loaded data
                                {latest ? ` (latest: ${latest})` : ''} or clear a filter.
                            </div>
                        )}
                    </div>
                </section>
            )}

            {/* ── Monthly trend ── */}
            <section style={{ marginBottom: 12 }}>
                {trendError ? (
                    <SectionError message="The monthly destination trend did not load." onRetry={() => fetchTrend(filters)} />
                ) : trendLoading ? (
                    <SkeletonLoader variant="chart" />
                ) : (
                    <div className="dd-panel" style={{ padding: '18px 20px 10px' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                            marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
                            <div>
                                <div className="dd-eyebrow">Monthly trend · selected window</div>
                                <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                    Domestic and international volume by month
                                </div>
                                {trendShortfall && (
                                    <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--warning-text, #92400e)', marginTop: 4 }}>
                                        Data loaded through {trendShortfall} — later months in this window have no data yet.
                                    </div>
                                )}
                            </div>
                            <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                                {[
                                    ['Domestic (blue)', GRAD_DOM],
                                    ['International (grey)', GRAD_INTL],
                                ].map(([l, c]) => (
                                    <span key={l} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                        fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                        <span style={{ width: 14, height: 9, borderRadius: 2, background: c }} /> {l}
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
                                    {/* Stacked blue + grey; no share line (removed on the user's
                                        call). Domestic rises first, international lands on top. */}
                                    <Bar dataKey="domVolume" stackId="vol"
                                        fill="url(#ddGradDomV)" maxBarSize={40}
                                        {...CHART_ANIM(0)} />
                                    <Bar dataKey="intlVolume" stackId="vol"
                                        fill="url(#ddGradIntlV)" radius={[3, 3, 0, 0]} maxBarSize={40}
                                        {...CHART_ANIM(250)} />
                                </ComposedChart>
                            </ResponsiveContainer>
                        )}
                    </div>
                )}
            </section>

            {/* ── Breakdown ── */}
            <section className="dd-panel" style={{ padding: '18px 20px 14px', marginBottom: 12 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    marginBottom: 14, flexWrap: 'wrap', gap: 12 }}>
                    <div>
                        <div className="dd-eyebrow">Breakdown</div>
                        <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                            Domestic vs international volume by {tabLabel}
                        </div>
                    </div>
                    <div className="dd-seg" role="tablist">
                        {BREAKDOWN_TABS.map(tab => (
                            <button key={tab.key} role="tab" aria-selected={breakdownTab === tab.key}
                                className={`dd-focus${breakdownTab === tab.key ? ' dd-seg-on' : ''}`}
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
                            <Bar dataKey="domVolume" stackId="split" fill="url(#ddGradDomH)" maxBarSize={16}
                                {...(breakdownChartData.length > 15 ? { isAnimationActive: false } : CHART_ANIM(0))} />
                            <Bar dataKey="intlVolume" stackId="split" fill="url(#ddGradIntlH)"
                                radius={[0, 3, 3, 0]} maxBarSize={16}
                                {...(breakdownChartData.length > 15 ? { isAnimationActive: false } : CHART_ANIM(250))} />
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
                            <div className="dd-eyebrow">Top merchants</div>
                            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                Highest-volume merchants and their domestic / international split
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

export default DestinationDashboard;
