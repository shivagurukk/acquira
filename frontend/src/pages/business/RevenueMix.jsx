import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Paper } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import {
    ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip,
    ResponsiveContainer,
} from 'recharts';
import { PieChart, RefreshCw, Download, Filter, AlertTriangle } from 'lucide-react';
import BusinessFilters from '../../components/BusinessFilters';
import SkeletonLoader from '../../components/SkeletonLoader';
import useDataBounds from '../../hooks/useDataBounds';
import { createFmt, formatCompactCurrency, formatNumber } from '../../utils/formatters';
import { premiumDataGridStyles, premiumTableWrapper } from '../../theme/dataGridStyles';
import { GRID_PROPS, AXIS_PROPS, TOOLTIP_PROPS, prefersReducedMotion } from '../../theme/chartPalette';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

/* ════════════════════════════════════════════════════════════════════
   Revenue Mix — the finance team's Destination × Card Type P&L matrix,
   replicating their Excel layout: one row per Local / International ×
   Credit / Debit / Prepaid with #txns, volume and the fee waterfall in
   the sheet's column order (MSF → ICF → Net Revenue → Scheme Fee → Net
   Margin), plus the destination roll-up table and a Total row carrying
   Net Spread.

     net revenue = MSF − ICF                (after-interchange gross)
     net margin  = MSF − ICF − SF − PG      (batch 4-leg figure)
     net spread  = net margin + DCC + rental (+ opt-in FX) — totals only,
                   because rental/DCC income has no destination/card-type
                   dimension.

   COMMERCIAL is commercial credit and reports inside the Credit row
   (backend fold); PREPAID is always its own row.

   Same navy masthead + command deck + full drawer as the other business
   dashboards. Default window is YTD, anchored on the LATEST LOADED data
   date (this page's own bounds), never on "today".
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

/* Series identity: destination owns the hue, card type the shade. */
const DEST_STYLES = {
    DOMESTIC:      { name: 'blue',   top: '#5E82D2', bottom: '#33518F' },
    INTERNATIONAL: { name: 'copper', top: '#D9924E', bottom: '#9C5E1F' },
    UNSPECIFIED:   { name: 'grey',   top: '#94A3B8', bottom: '#475569' },
};
const destStyle = (d) => DEST_STYLES[d] || { name: 'green', top: '#5FAF87', bottom: '#2F6B4C' };
const destGrad = (d) => {
    const s = destStyle(d);
    return `linear-gradient(135deg, ${s.top} 0%, ${s.bottom} 100%)`;
};
const destLabel = (d) => (d === 'DOMESTIC' ? 'Local'
    : d === 'INTERNATIONAL' ? 'International'
    : d === 'UNSPECIFIED' ? 'Unspecified'
    : String(d || '').charAt(0) + String(d || '').slice(1).toLowerCase());

/* Prepaid is always its own row — canonical order keeps it visible. */
const CT_ORDER = ['CREDIT', 'DEBIT', 'PREPAID'];
const ctRank = (t) => {
    const i = CT_ORDER.indexOf(t);
    if (i >= 0) return i;
    return t === 'UNSPECIFIED' ? 99 : 50;
};
const ctLabel = (t) => (t === 'UNSPECIFIED' ? 'Unspecified'
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

const Metric = ({ label, value, sub, tone }) => (
    <div style={{ padding: '15px 18px', minWidth: 0 }}>
        <div className="rvm-eyebrow">{label}</div>
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

const RevenueMix = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const { latest: sharedLatest } = useDataBounds(tenantVersion);

    const [showFilters, setShowFilters] = useState(false);
    const [preset, setPreset] = useState('YTD');
    /* The matrix split: card type (sheet default) or scheme. */
    const [splitDim, setSplitDim] = useState('cardType');
    const [filters, setFilters] = useState({ startDate: '', endDate: '', ...EMPTY_LISTS });
    const [filterVersion, setFilterVersion] = useState(0);
    const initRef = useRef(false);

    const [pageBounds, setPageBounds] = useState({ latest: null, loaded: false });
    useEffect(() => {
        let cancelled = false;
        setPageBounds({ latest: null, loaded: false });
        (async () => {
            try {
                const res = await api.get('/business/revenue-mix/bounds');
                if (!cancelled) setPageBounds({ latest: res.data?.latest || null, loaded: true });
            } catch (e) {
                console.error('Failed to load revenue-mix data bounds', e);
                if (!cancelled) setPageBounds({ latest: null, loaded: true });
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    const anchor = pageBounds.latest || sharedLatest;

    useEffect(() => {
        if (!pageBounds.loaded || initRef.current) return;
        initRef.current = true;
        setFilters(f => ({ ...f, ...computeRange('YTD', anchor) }));
        setFilterVersion(v => v + 1);
    }, [pageBounds.loaded, anchor]);
    useEffect(() => { initRef.current = false; }, [tenantVersion]);

    const [matrixData, setMatrixData] = useState(null);
    const [matrixLoading, setMatrixLoading] = useState(true);
    const [matrixError, setMatrixError] = useState(false);
    const [trendData, setTrendData] = useState([]);
    const [trendLoading, setTrendLoading] = useState(true);
    const [trendError, setTrendError] = useState(false);

    const fetchMatrix = useCallback(async (f, dim) => {
        setMatrixLoading(true); setMatrixError(false);
        try {
            const res = await api.post(`/business/revenue-mix/matrix/${dim}`, f);
            setMatrixData(res.data);
        } catch (e) {
            console.error('Failed to load revenue-mix matrix', e);
            setMatrixError(true); setMatrixData(null);
        } finally { setMatrixLoading(false); }
    }, []);

    const fetchTrend = useCallback(async (f) => {
        setTrendLoading(true); setTrendError(false);
        try {
            const res = await api.post('/business/revenue-mix/trend', f);
            setTrendData(res.data || []);
        } catch (e) {
            console.error('Failed to load revenue-mix trend', e);
            setTrendError(true); setTrendData([]);
        } finally { setTrendLoading(false); }
    }, []);

    /* The matrix refetches when the split toggles; the trend doesn't care. */
    useEffect(() => {
        if (!filterVersion) return;
        fetchMatrix(filters, splitDim);
    }, [filterVersion, tenantVersion, splitDim]); // eslint-disable-line react-hooks/exhaustive-deps
    useEffect(() => {
        if (!filterVersion) return;
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
    const totals = matrixData?.totals || null;
    const totalVol = num(totals?.volume);
    const pctStr = (v, dp = 2) => (v == null ? '—' : `${Number(v).toFixed(dp)}%`);
    /* "▲ 4.2% vs prior" — only when the equal-length prior window has data. */
    const growthSub = (g, fallback) => {
        if (matrixData?.priorWindowHasData !== true || g == null) return fallback;
        const n = Number(g);
        return `${n >= 0 ? '▲' : '▼'} ${Math.abs(n).toFixed(1)}% vs prior · ${fallback}`;
    };

    /* The dimension the loaded payload was actually split on (follows the
       toggle once the refetch lands). */
    const loadedDim = matrixData?.dimension || 'cardType';
    const splitLabel = loadedDim === 'scheme'
        ? (v) => (v === 'UNSPECIFIED' ? 'Unspecified' : String(v || ''))
        : ctLabel;

    /* Destination order by volume (backend already sorts destinations). */
    const destOrder = useMemo(() =>
        (matrixData?.destinations || []).map(d => d.destination), [matrixData]);

    /* Matrix cells in sheet order: destination (biggest first) → card type
       (Credit, Debit, Prepaid, …; Prepaid always its own row) or, on the
       scheme split, volume order within the destination. */
    const cells = useMemo(() => {
        const list = [...(matrixData?.cells || [])];
        const dim = matrixData?.dimension || 'cardType';
        list.sort((a, b) => {
            const d = destOrder.indexOf(a.destination) - destOrder.indexOf(b.destination);
            if (d !== 0) return d;
            return dim === 'cardType' ? ctRank(a.splitValue) - ctRank(b.splitValue) : 0;
        });
        return list;
    }, [matrixData, destOrder]);

    /* Shade rank inside a destination: card types use the canonical rank so
       Credit/Debit/Prepaid keep stable shades; schemes shade by position. */
    const cellShadeRank = useMemo(() => {
        const map = new Map();
        const perDest = new Map();
        for (const c of cells) {
            const n = perDest.get(c.destination) || 0;
            map.set(`${c.destination}|${c.splitValue}`,
                loadedDim === 'cardType' ? Math.min(ctRank(c.splitValue), 3) : Math.min(n, 3));
            perDest.set(c.destination, n + 1);
        }
        return map;
    }, [cells, loadedDim]);
    const shadeOpacity = (c) => 1 - 0.22 * (cellShadeRank.get(`${c.destination}|${c.splitValue}`) || 0);

    const windowLabel = matrixData?.start && matrixData?.end
        ? `${matrixData.start} → ${matrixData.end}`
        : (filters.startDate && filters.endDate ? `${filters.startDate} → ${filters.endDate}` : '—');

    /* Ribbon: destination × card-type share of volume. */
    const ribbonBlocks = useMemo(() => cells.filter(c => num(c.volume) > 0), [cells]);

    /* ── Trend: pivot month × destination into one row per month ── */
    const trendDests = useMemo(() => {
        const set = Array.from(new Set(trendData.map(d => d.destination)));
        return set.sort((a, b) => destOrder.indexOf(a) - destOrder.indexOf(b));
    }, [trendData, destOrder]);
    const chartData = useMemo(() => {
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
                    netMargin: 0,
                });
            }
            const row = byMonth.get(key);
            row[d.destination] = num(d.volume);
            row.netMargin += num(d.netMargin);
        }
        return Array.from(byMonth.values()).sort((a, b) => a.month.localeCompare(b.month));
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
        return (
            <div style={TOOLTIP_PROPS.contentStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6 }}>{row.monthLong}</div>
                {trendDests.map(d => (
                    <div key={d} style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                        <span style={{ color: 'var(--text-secondary)' }}>{destLabel(d)} volume</span>
                        <span className="rvm-num" style={{ fontWeight: 700, color: destStyle(d).bottom }}>
                            {fmt.currency(num(row[d]))}
                        </span>
                    </div>
                ))}
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Net margin</span>
                    <span className="rvm-num" style={{ fontWeight: 700, color: '#2F6B4C' }}>
                        {fmt.currency(num(row.netMargin))}
                    </span>
                </div>
            </div>
        );
    };

    /* ── Cells ── */
    const moneyCell = (v, style) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : <span className="rvm-num" style={style}>{fmt.money(v)}</span>;
    const pctCell = (v, signed = false) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : (
            <span className="rvm-num" style={{ fontWeight: 600,
                color: signed ? (num(v) >= 0 ? 'var(--success-text)' : 'var(--danger-text)') : 'var(--text-secondary)' }}>
                {Number(v).toFixed(2)}%
            </span>
        );
    const signedMoneyCell = (v) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : (
            <span className="rvm-num" style={{
                display: 'inline-flex', alignItems: 'center', gap: 4, fontWeight: 700,
                color: num(v) >= 0 ? 'var(--success-text)' : 'var(--danger-text)',
            }}>
                <span aria-hidden="true" style={{ fontSize: 10 }}>{num(v) >= 0 ? '▲' : '▼'}</span>
                {fmt.money(v)}
            </span>
        );

    /* Sheet column order: #Txns, Volume, MSF %, MSF, ICF %, ICF,
       Net Revenue %, Net Revenue, SF %, SF, Net Margin %, Net Margin.
       Sorting is disabled so the matrix keeps the sheet's row order. */
    const feeColumns = useMemo(() => [
        {
            field: 'txns', headerName: '#Txns', flex: 0.65, minWidth: 80, align: 'right', headerAlign: 'right',
            sortable: false,
            renderCell: (p) => <span className="rvm-num" style={{ color: 'var(--text-secondary)' }}>{formatNumber(num(p.value))}</span>,
        },
        {
            field: 'volume', headerName: `Volume (${currencyCode || currencySymbol || ''})`, flex: 1, minWidth: 125,
            align: 'right', headerAlign: 'right', sortable: false,
            renderCell: (p) => moneyCell(p.value, { fontWeight: 700 }),
        },
        {
            field: 'msfPct', headerName: 'MSF %', flex: 0.6, minWidth: 75, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => pctCell(p.value),
        },
        {
            field: 'msf', headerName: 'MSF', flex: 0.85, minWidth: 100, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'icfPct', headerName: 'ICF %', flex: 0.6, minWidth: 75, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => pctCell(p.value),
        },
        {
            field: 'icf', headerName: 'ICF', flex: 0.85, minWidth: 100, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'netRevenuePct', headerName: 'Net Rev %', flex: 0.65, minWidth: 85, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => pctCell(p.value, true),
        },
        {
            field: 'netRevenue', headerName: 'Net Revenue', flex: 0.95, minWidth: 115, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => signedMoneyCell(p.value),
        },
        {
            field: 'schemeFeePct', headerName: 'SF %', flex: 0.55, minWidth: 70, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => pctCell(p.value),
        },
        {
            field: 'schemeFee', headerName: 'Scheme Fee', flex: 0.85, minWidth: 105, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'netMarginPct', headerName: 'Net Margin %', flex: 0.7, minWidth: 95, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => pctCell(p.value, true),
        },
        {
            field: 'netMargin', headerName: 'Net Margin', flex: 0.95, minWidth: 115, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => signedMoneyCell(p.value),
        },
    ], [fmt, currencyCode, currencySymbol]); // eslint-disable-line react-hooks/exhaustive-deps

    const matrixRows = useMemo(() => cells.map((c, i) => ({
        id: `${c.destination}|${c.splitValue}|${i}`, ...c,
    })), [cells]);

    const matrixColumns = useMemo(() => [
        {
            field: 'destination', headerName: 'Destination', flex: 0.9, minWidth: 115, sortable: false,
            renderCell: (p) => (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontWeight: 600 }}>
                    <span style={{ width: 9, height: 9, borderRadius: 2, flexShrink: 0, background: destGrad(p.value) }} />
                    {destLabel(p.value)}
                </span>
            ),
        },
        {
            field: 'splitValue', headerName: loadedDim === 'scheme' ? 'Scheme' : 'Card Type',
            flex: 0.8, minWidth: 100, sortable: false,
            renderCell: (p) => <span style={{ fontWeight: 600 }}>{splitLabel(p.value)}</span>,
        },
        ...feeColumns,
    ], [feeColumns, loadedDim]); // eslint-disable-line react-hooks/exhaustive-deps

    /* By-destination roll-up + Total row (the only row with net spread). */
    const destRows = useMemo(() => {
        const rows = (matrixData?.destinations || []).map((d, i) => ({ id: d.destination || i, ...d }));
        if (totals) rows.push({ id: '__TOTAL__', ...totals, destination: 'ALL' });
        return rows;
    }, [matrixData, totals]);

    const destColumns = useMemo(() => [
        {
            field: 'destination', headerName: 'Destination', flex: 1, minWidth: 130, sortable: false,
            renderCell: (p) => p.value === 'ALL'
                ? <span style={{ fontWeight: 800, letterSpacing: '0.02em' }}>TOTAL</span>
                : (
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontWeight: 600 }}>
                        <span style={{ width: 9, height: 9, borderRadius: 2, flexShrink: 0, background: destGrad(p.value) }} />
                        {destLabel(p.value)}
                    </span>
                ),
        },
        ...feeColumns,
        {
            field: 'ancillary', headerName: 'Ancillary', flex: 0.85, minWidth: 100, align: 'right', headerAlign: 'right',
            sortable: false,
            // DCC on the International row; rental (+FX) only on Total.
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'netSpread', headerName: 'Net Spread', flex: 0.95, minWidth: 115, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => signedMoneyCell(p.value),
        },
        {
            field: 'netSpreadPct', headerName: 'Net Spread %', flex: 0.7, minWidth: 95, align: 'right', headerAlign: 'right',
            sortable: false, renderCell: (p) => pctCell(p.value, true),
        },
    ], [feeColumns]); // eslint-disable-line react-hooks/exhaustive-deps

    /* Export mirrors the finance workbook's layout: the Destination × split
       matrix first, a blank line, then the destination roll-up with Total —
       one file, two tables, sheet column order. */
    const exportRows = useCallback(() => {
        const esc = (v) => {
            if (v == null) return '';
            const s = typeof v === 'number' ? String(v) : String(v);
            return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
        };
        const line = (arr) => arr.map(esc).join(',');
        const feeCells = (r) => [r.txns, r.volume, r.msfPct, r.msf, r.icfPct, r.icf,
            r.netRevenuePct, r.netRevenue, r.schemeFeePct, r.schemeFee, r.netMarginPct, r.netMargin];
        const feeHead = ['#Txns', `Volume ${currencyCode || ''}`, 'MSF %', 'MSF', 'ICF %', 'ICF',
            'Net Revenue %', 'Net Revenue', 'SF %', 'SF', 'Net Margin %', 'Net Margin'];
        const splitHead = loadedDim === 'scheme' ? 'Scheme' : 'Card Type';

        const out = [];
        out.push(line(['Destination', splitHead, ...feeHead]));
        for (const c of cells)
            out.push(line([destLabel(c.destination), splitLabel(c.splitValue), ...feeCells(c)]));
        out.push('');
        out.push(line(['Destination', ...feeHead, 'Ancillary', 'Net Spread', 'Net Spread %']));
        for (const d of (matrixData?.destinations || []))
            out.push(line([destLabel(d.destination), ...feeCells(d), d.ancillary, d.netSpread, d.netSpreadPct]));
        if (totals)
            out.push(line(['Total', ...feeCells(totals), totals.ancillary, totals.netSpread, totals.netSpreadPct]));

        const blob = new Blob(['﻿' + out.join('\n')], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `revenue_mix_${matrixData?.start || ''}_${matrixData?.end || ''}.csv`;
        a.click();
        URL.revokeObjectURL(url);
    }, [cells, matrixData, totals, loadedDim, currencyCode]); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <div style={{
            padding: 'var(--space-page, 20px)', background: 'var(--bg)',
            minHeight: '100vh', width: '100%', boxSizing: 'border-box',
        }}>
            <style>{`
                .rvm-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }
                .rvm-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .rvm-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); }

                .rvm-panel.rvm-hdrblock { background: var(--table-head-bg,
                        linear-gradient(135deg, #24386B 0%, #16264A 55%, #0A1426 100%));
                    border-color: transparent; overflow: visible; margin-bottom: 12px; }
                .rvm-hdrblock > :first-child { border-radius: calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px) 0 0; }
                .rvm-hdrblock > :last-child { border-radius: 0 0 calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px); }
                .rvm-mast { padding: 20px 24px 18px; display: flex; justify-content: space-between;
                    align-items: flex-end; gap: 18px; flex-wrap: wrap; }
                .rvm-mast-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.18em; text-transform: uppercase;
                    color: var(--table-head-muted, #93A3C6); }
                .rvm-mast h1 { margin: 8px 0 0; font-size: 26px; font-weight: 700;
                    letter-spacing: -0.025em; line-height: 1.08;
                    color: var(--table-head-text, #EEF3FC); }
                .rvm-mast-sub { margin: 6px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .rvm-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.22);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .rvm-mast-btn:hover { background: rgba(255,255,255,0.14); }
                .rvm-mast-btn:disabled { opacity: 0.5; cursor: default; }
                .rvm-cmdbar { display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
                    padding: 10px 18px; background: rgba(0,0,0,0.20);
                    border-top: 1px solid rgba(255,255,255,0.13); }
                .rvm-preset { padding: 7px 13px; font-size: 12px; font-weight: 600; cursor: pointer;
                    color: rgba(238,243,252,0.72); background: transparent; border: 0;
                    border-radius: var(--radius-sm); transition: background .12s ease, color .12s ease; }
                .rvm-preset:hover { background: rgba(255,255,255,0.08); color: #EEF3FC; }
                .rvm-preset-on { background: rgba(255,255,255,0.14); color: #EEF3FC;
                    box-shadow: inset 0 -2px 0 var(--chart-4, #7191CE); }
                .rvm-date { padding: 6px 9px; font-size: 12px; font-family: var(--font-mono);
                    color: #EEF3FC; background: rgba(255,255,255,0.07);
                    border: 1px solid rgba(255,255,255,0.22); border-radius: var(--radius-sm);
                    outline: none; color-scheme: dark; }

                @keyframes rvmSweep { from { transform: scaleX(0); } to { transform: scaleX(1); } }
                .rvm-ribbon { display: flex; height: 12px; border-radius: 999px; overflow: hidden;
                    background: var(--border-light, var(--border));
                    transform-origin: left; animation: rvmSweep .8s ease-out; }
                .rvm-ribbon > div { transition: width .7s cubic-bezier(0.22, 1, 0.36, 1); }
                @media (prefers-reduced-motion: reduce) {
                    .rvm-ribbon { animation: none; }
                    .rvm-ribbon > div { transition: none; }
                }

                .rvm-seg { display: inline-flex; background: var(--bg-card);
                    border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 3px; }
                .rvm-seg button { border: 0; cursor: pointer; border-radius: calc(var(--radius-sm) - 2px);
                    padding: 6px 13px; font-size: 12px; font-weight: 600; white-space: nowrap;
                    background: transparent; color: var(--text-secondary);
                    transition: background .12s ease, color .12s ease; }
                .rvm-seg button:hover { color: var(--text); background: var(--bg-hover); }
                .rvm-seg .rvm-seg-on { background: var(--primary); color: #fff; }
                .rvm-focus:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
                .rvm-cmdbar .rvm-focus:focus-visible { outline-color: #EEF3FC; outline-offset: -2px; }
                .rvm-cell { border-right: 1px solid var(--border-light, var(--border)); }
                @media (prefers-reduced-motion: reduce) {
                    .rvm-preset, .rvm-mast-btn { transition: none; }
                }
            `}</style>

            {/* ── Masthead + command deck ── */}
            <section className="rvm-panel rvm-hdrblock">
                <div className="rvm-mast">
                    <div>
                        <div className="rvm-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <PieChart size={11} /> Business · Revenue Mix
                        </div>
                        <h1>Revenue Mix</h1>
                        <p className="rvm-mast-sub">
                            Destination × card type P&amp;L — MSF, ICF, net revenue, scheme fee, net margin &amp; net spread
                            · {windowLabel} · {currencyCode || currencySymbol || ''}
                        </p>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                        <button className="rvm-focus rvm-mast-btn" onClick={() => setShowFilters(s => !s)}>
                            <Filter size={13} /> Filters{activeFilterCount > 0 ? ` (${activeFilterCount})` : ''}
                        </button>
                        <button className="rvm-focus rvm-mast-btn" onClick={exportRows} disabled={!cells.length}>
                            <Download size={13} /> Export
                        </button>
                        <button className="rvm-focus rvm-mast-btn" onClick={run} title="Refresh" aria-label="Refresh"
                            style={{ padding: '9px 11px' }}>
                            <RefreshCw size={14} className={matrixLoading ? 'animate-spin' : ''} />
                        </button>
                    </div>
                </div>
                <div className="rvm-cmdbar">
                    <span className="rvm-mast-eyebrow" style={{ marginRight: 10 }}>Window</span>
                    {PRESETS.map(p => (
                        <button key={p.key}
                            className={`rvm-focus rvm-preset${preset === p.key ? ' rvm-preset-on' : ''}`}
                            onClick={() => pickPreset(p.key)}>
                            {p.label}
                        </button>
                    ))}
                    {preset === 'CUSTOM' && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, marginLeft: 6 }}>
                            <input type="date" className="rvm-date" value={filters.startDate}
                                onChange={e => setCustomDate('startDate', e.target.value)} aria-label="From date" />
                            <span style={{ color: 'rgba(238,243,252,0.5)', fontSize: 11 }}>to</span>
                            <input type="date" className="rvm-date" value={filters.endDate}
                                onChange={e => setCustomDate('endDate', e.target.value)} aria-label="To date" />
                            <button className="rvm-focus rvm-mast-btn" style={{ padding: '6px 13px' }} onClick={run}>
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

            {/* ── P&L band + mix ribbon ── */}
            {matrixError ? (
                <div style={{ marginBottom: 12 }}>
                    <SectionError message="The revenue mix did not load." onRetry={() => fetchMatrix(filters)} />
                </div>
            ) : matrixLoading ? (
                <div style={{ marginBottom: 12 }}><SkeletonLoader variant="kpi-row" count={6} /></div>
            ) : totals && (
                <section className="rvm-panel" style={{ marginBottom: 12, overflow: 'hidden' }}>
                    <div style={{
                        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
                        borderBottom: '1px solid var(--border-light, var(--border))',
                    }}>
                        <div className="rvm-cell">
                            <Metric label={`Volume (${currencyCode || currencySymbol || ''})`}
                                value={fmt.currency(totalVol)}
                                sub={growthSub(totals.volumeGrowthPct,
                                    `${formatNumber(num(totals.txns))} txns`)} />
                        </div>
                        <div className="rvm-cell">
                            <Metric label="MSF" value={fmt.currency(num(totals.msf))}
                                sub={growthSub(totals.msfGrowthPct, `${pctStr(totals.msfPct)} of volume`)} />
                        </div>
                        <div className="rvm-cell">
                            <Metric label="Interchange (ICF)" value={fmt.currency(num(totals.icf))}
                                sub={`${pctStr(totals.icfPct)} of volume`} />
                        </div>
                        <div className="rvm-cell">
                            <Metric label="Net revenue" value={fmt.currency(num(totals.netRevenue))}
                                tone={num(totals.netRevenue) >= 0 ? 'success' : 'danger'}
                                sub={`MSF − ICF · ${pctStr(totals.netRevenuePct)}`} />
                        </div>
                        <div className="rvm-cell">
                            <Metric label="Scheme fee" value={fmt.currency(num(totals.schemeFee))}
                                sub={`${pctStr(totals.schemeFeePct)} of volume`} />
                        </div>
                        <div className="rvm-cell">
                            <Metric label="Net margin" value={fmt.currency(num(totals.netMargin))}
                                tone={num(totals.netMargin) >= 0 ? 'success' : 'danger'}
                                sub={growthSub(totals.netMarginGrowthPct,
                                    `after SF & PG · ${pctStr(totals.netMarginPct)}`)} />
                        </div>
                        <div>
                            <Metric label="Net spread" value={fmt.currency(num(totals.netSpread))}
                                tone={num(totals.netSpread) >= 0 ? 'success' : 'danger'}
                                sub={growthSub(totals.netSpreadGrowthPct,
                                    `+ DCC & rental${matrixData?.fxEnabled ? ' & FX' : ''} · ${pctStr(totals.netSpreadPct)}`)} />
                        </div>
                    </div>
                    <div style={{ padding: '15px 20px 16px' }}>
                        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
                            <span className="rvm-eyebrow">The mix</span>
                            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                {totalVol > 0 ? 'destination × card type share of processed volume'
                                    : 'no volume in this window'}
                            </span>
                        </div>
                        {totalVol > 0 ? (
                            <>
                                <div className="rvm-ribbon">
                                    {ribbonBlocks.map((b, i) => (
                                        <div key={`${b.destination}|${b.splitValue}`}
                                            title={`${destLabel(b.destination)} · ${splitLabel(b.splitValue)} · ${fmt.currency(num(b.volume))}`}
                                            style={{ width: `${num(b.volume) / totalVol * 100}%`,
                                                background: destGrad(b.destination),
                                                opacity: shadeOpacity(b),
                                                borderRight: i < ribbonBlocks.length - 1
                                                    ? '1px solid var(--bg-card)' : undefined }} />
                                    ))}
                                </div>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '5px 20px', marginTop: 9 }}>
                                    {ribbonBlocks.map(b => (
                                        <span key={`${b.destination}|${b.splitValue}`}
                                            style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                                fontSize: 11, color: 'var(--text-secondary)' }}>
                                            <span style={{ width: 14, height: 9, borderRadius: 2,
                                                background: destGrad(b.destination),
                                                opacity: shadeOpacity(b) }} />
                                            <span style={{ fontWeight: 600, color: 'var(--text)' }}>
                                                {destLabel(b.destination)} · {splitLabel(b.splitValue)}
                                            </span>
                                            <span className="rvm-num">{fmt.currency(num(b.volume))}</span>
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
                        {matrixData?.ancillaryApproximate && (
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
                    <div className="rvm-panel" style={{ padding: '18px 20px 10px' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                            marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
                            <div>
                                <div className="rvm-eyebrow">Monthly trend · selected window</div>
                                <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                    Volume by destination (stacked bars, left) vs net margin (line, right)
                                </div>
                                {trendShortfall && (
                                    <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--warning-text, #92400e)', marginTop: 4 }}>
                                        Data loaded through {trendShortfall} — later months in this window have no data yet.
                                    </div>
                                )}
                            </div>
                            <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                                {trendDests.map(d => (
                                    <span key={d} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                        fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                        <span style={{ width: 14, height: 9, borderRadius: 2, background: destGrad(d) }} />
                                        {destLabel(d)} ({destStyle(d).name})
                                    </span>
                                ))}
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                    fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                    <span style={{ width: 14, height: 3, borderRadius: 2, background: '#2F6B4C' }} />
                                    Net margin
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
                                        {Object.entries(DEST_STYLES).map(([key, s]) => (
                                            <linearGradient key={key} id={`rvmGrad${key}`} x1="0" y1="0" x2="0" y2="1">
                                                <stop offset="0%" stopColor={s.top} />
                                                <stop offset="100%" stopColor={s.bottom} />
                                            </linearGradient>
                                        ))}
                                    </defs>
                                    <CartesianGrid {...GRID_PROPS} />
                                    <XAxis dataKey="monthShort" {...AXIS_PROPS} />
                                    <YAxis yAxisId="vol" {...AXIS_PROPS}
                                        tickFormatter={(v) => formatCompactCurrency(v)} width={78} />
                                    <YAxis yAxisId="rev" orientation="right" {...AXIS_PROPS}
                                        tickFormatter={(v) => formatCompactCurrency(v)} width={72} />
                                    <Tooltip content={<TrendTooltip />} cursor={TOOLTIP_PROPS.cursor} />
                                    {trendDests.map((d, i) => (
                                        <Bar key={d} yAxisId="vol" dataKey={d} stackId="vol"
                                            fill={DEST_STYLES[d] ? `url(#rvmGrad${d})` : destStyle(d).bottom}
                                            maxBarSize={40}
                                            radius={i === trendDests.length - 1 ? [3, 3, 0, 0] : undefined}
                                            {...CHART_ANIM(i * 200)} />
                                    ))}
                                    <Line yAxisId="rev" dataKey="netMargin" stroke="#2F6B4C" strokeWidth={2.5}
                                        dot={{ r: 3, strokeWidth: 0, fill: '#2F6B4C' }} {...CHART_ANIM(450)} />
                                </ComposedChart>
                            </ResponsiveContainer>
                        )}
                    </div>
                )}
            </section>

            {/* ── The matrix — destination × card type ── */}
            {matrixError ? (
                <SectionError message="The destination × card-type matrix did not load."
                    onRetry={() => fetchMatrix(filters)} />
            ) : (
                <Paper sx={{ ...premiumTableWrapper, mb: '12px' }}>
                    <div style={{ padding: '16px 20px 13px', borderBottom: '1px solid var(--border-light, var(--border))',
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                        <div>
                            <div className="rvm-eyebrow">
                                Destination × {loadedDim === 'scheme' ? 'Scheme' : 'Card Type'}
                            </div>
                            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                The fee waterfall per mix cell — net revenue = MSF − ICF · net margin = MSF − ICF − SF − PG
                                {loadedDim === 'cardType' ? ' · Commercial reports inside Credit · Prepaid is always its own row' : ''}
                            </div>
                        </div>
                        <div className="rvm-seg" role="tablist" aria-label="Matrix split dimension">
                            {[{ key: 'cardType', label: 'Card Type' }, { key: 'scheme', label: 'Scheme' }].map(t => (
                                <button key={t.key} role="tab" aria-selected={splitDim === t.key}
                                    className={`rvm-focus${splitDim === t.key ? ' rvm-seg-on' : ''}`}
                                    onClick={() => setSplitDim(t.key)}>
                                    {t.label}
                                </button>
                            ))}
                        </div>
                    </div>
                    <DataGrid rows={matrixRows} columns={matrixColumns} loading={matrixLoading}
                        rowHeight={44} disableRowSelectionOnClick disableColumnMenu
                        hideFooter
                        sx={premiumDataGridStyles}
                    />
                </Paper>
            )}

            {/* ── By destination + Total (net spread lives here) ── */}
            {!matrixError && (
                <Paper sx={premiumTableWrapper}>
                    <div style={{ padding: '16px 20px 13px', borderBottom: '1px solid var(--border-light, var(--border))' }}>
                        <div className="rvm-eyebrow">By Destination · with Net Spread</div>
                        <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                            Destination roll-up and the Total row · net spread = net margin + ancillary ·
                            DCC is foreign-card income so it sits on the International row; rental
                            {matrixData?.fxEnabled ? ' & FX' : ''} can't be attributed and joins the Total only
                        </div>
                    </div>
                    <DataGrid rows={destRows} columns={destColumns} loading={matrixLoading}
                        rowHeight={44} disableRowSelectionOnClick disableColumnMenu
                        hideFooter
                        getRowClassName={(p) => (p.id === '__TOTAL__' ? 'rvm-total-row' : '')}
                        sx={{
                            ...premiumDataGridStyles,
                            '& .rvm-total-row': {
                                fontWeight: 700,
                                borderTop: '2px solid var(--border)',
                                backgroundColor: 'var(--bg-hover, rgba(148,163,184,0.08))',
                            },
                        }}
                    />
                </Paper>
            )}
        </div>
    );
};

export default RevenueMix;
