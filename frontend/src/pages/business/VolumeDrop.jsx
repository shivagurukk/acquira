import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Paper } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { TrendingDown, RefreshCw, Download, Filter, AlertTriangle } from 'lucide-react';
import BusinessFilters from '../../components/BusinessFilters';
import SkeletonLoader from '../../components/SkeletonLoader';
import useDataBounds from '../../hooks/useDataBounds';
import { exportToCSV } from '../../utils/exportUtils';
import { createFmt, formatNumber } from '../../utils/formatters';
import { premiumDataGridStyles, premiumTableWrapper } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

/* ════════════════════════════════════════════════════════════════════
   Volume Drop — per-merchant month-over-month comparison, the screen
   version of the hand-built "Jul vs Aug MTD" Excel: MID, merchant, RM,
   lead, last month full volume, current MTD, projected month-end and
   the drop/gain each implies. Same navy masthead + command deck as the
   Industry Analytics / Card Type pages.

   Backend: one conditional-aggregation pass over sum_daily_merchant
   (VolumeDropController). Both projections arrive per row and the deck
   toggles between them:
     linear = MTD ÷ elapsed days × days in month   (the Excel formula)
     pace   = MTD ÷ (last month's share done by the same day) — corrects
              for the weekday/weekend mix; falls back to linear.

   As-of anchors on the LATEST LOADED data date, never on "today".
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

/* Month string helpers — LOCAL parts, never toISOString() (UTC day shift). */
const monthEnd = (ym) => {
    const m = /^(\d{4})-(\d{2})$/.exec(ym || '');
    if (!m) return null;
    const d = new Date(Number(m[1]), Number(m[2]), 0); // day 0 of next month
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const monthLabel = (ym) => {
    const m = /^(\d{4})-(\d{2})$/.exec(ym || '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, 1)
        .toLocaleDateString('en-US', { month: 'long', year: 'numeric' }) : '—';
};

const MODES = [
    { key: 'linear', label: 'Linear', hint: 'MTD ÷ elapsed days × days in month' },
    { key: 'pace', label: 'Pace', hint: "MTD ÷ last month's share done by the same day" },
];
const VIEWS = [
    { key: 'ALL', label: 'All' },
    { key: 'DECLINING', label: 'Declining' },
    { key: 'STOPPED', label: 'Stopped' },
    { key: 'NEW', label: 'New' },
];

const EMPTY_LISTS = {
    schemeList: [], destinationList: [], channelList: [], mccList: [],
    cardTypeList: [], midList: [], sidList: [], partnerList: [], rmList: [],
    teamLeaderList: [], industryList: [], sectorList: [], terminalTypeList: [],
    merchantName: '',
};

const STATUS_STYLE = {
    STOPPED:   { bg: 'var(--danger-bg, #fef2f2)',  border: 'var(--danger-border, #fecaca)',  text: 'var(--danger-text, #991b1b)' },
    DECLINING: { bg: 'var(--warning-bg, #fffbeb)', border: 'var(--warning-border, #fde68a)', text: 'var(--warning-text, #92400e)' },
    GROWING:   { bg: 'var(--success-bg, #f0fdf4)', border: 'var(--success-border, #bbf7d0)', text: 'var(--success-text, #166534)' },
    STABLE:    { bg: 'var(--bg-hover, #f8fafc)',   border: 'var(--border)',                  text: 'var(--text-secondary)' },
    NEW:       { bg: 'var(--info-bg, #eff6ff)',    border: 'var(--info-border, #bfdbfe)',    text: 'var(--info-text, #1e40af)' },
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
    <div style={{ padding: '15px 20px', minWidth: 0 }}>
        <div className="vd-eyebrow">{label}</div>
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

const VolumeDrop = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const { latest: sharedLatest } = useDataBounds(tenantVersion);

    const [showFilters, setShowFilters] = useState(false);
    const [mode, setMode] = useState('linear');
    const [view, setView] = useState('ALL');
    const [month, setMonth] = useState(''); // '' = latest loaded month
    const [filters, setFilters] = useState({ startDate: '', endDate: '', ...EMPTY_LISTS });
    const [filterVersion, setFilterVersion] = useState(0);
    const initRef = useRef(false);

    /* Bounds from THIS page's backing table (sum_daily_merchant), shared
       bounds only as last-resort fallback. */
    const [pageBounds, setPageBounds] = useState({ earliest: null, latest: null, loaded: false });
    useEffect(() => {
        let cancelled = false;
        setPageBounds({ earliest: null, latest: null, loaded: false });
        (async () => {
            try {
                const res = await api.get('/business/volume-drop/bounds');
                if (!cancelled) setPageBounds({
                    earliest: res.data?.earliest || null,
                    latest: res.data?.latest || null, loaded: true,
                });
            } catch (e) {
                console.error('Failed to load volume-drop data bounds', e);
                if (!cancelled) setPageBounds({ earliest: null, latest: null, loaded: true });
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    const anchor = pageBounds.latest || sharedLatest;
    const anchorMonth = anchor ? String(anchor).slice(0, 7) : '';
    const earliestMonth = pageBounds.earliest ? String(pageBounds.earliest).slice(0, 7) : undefined;

    /* First fetch waits for bounds; default as-of = latest loaded date. */
    useEffect(() => {
        if (!pageBounds.loaded || initRef.current) return;
        initRef.current = true;
        setMonth(anchorMonth);
        setFilterVersion(v => v + 1);
    }, [pageBounds.loaded, anchorMonth]);
    // Tenant switch: re-anchor on the new tenant's bounds.
    useEffect(() => { initRef.current = false; }, [tenantVersion]);

    const [rowsData, setRowsData] = useState(null);
    const [rowsLoading, setRowsLoading] = useState(true);
    const [rowsError, setRowsError] = useState(false);

    const fetchRows = useCallback(async (f, m) => {
        setRowsLoading(true); setRowsError(false);
        try {
            // endDate = end of the picked month; the backend clamps it to the
            // latest loaded date, so the current month becomes a true MTD.
            const body = { ...f, startDate: null, endDate: m ? monthEnd(m) : null };
            const res = await api.post('/business/volume-drop/rows', body);
            setRowsData(res.data);
        } catch (e) {
            console.error('Failed to load volume drop rows', e);
            setRowsError(true); setRowsData(null);
        } finally { setRowsLoading(false); }
    }, []);

    /* filterVersion is the only trigger — one fetch per page open. */
    useEffect(() => {
        if (!filterVersion) return;
        fetchRows(filters, month);
    }, [filterVersion, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

    const run = useCallback(() => setFilterVersion(v => v + 1), []);
    const pickMonth = (ym) => { setMonth(ym); setFilterVersion(v => v + 1); };
    const handleAdvancedFilterChange = useCallback((next) => setFilters(next), []);

    const activeFilterCount = useMemo(() =>
        ['schemeList', 'destinationList', 'channelList', 'mccList', 'cardTypeList', 'midList', 'sidList',
            'partnerList', 'rmList', 'teamLeaderList', 'industryList', 'sectorList', 'terminalTypeList']
            .reduce((a, k) => a + (filters[k]?.length || 0), 0)
        + (filters.merchantName ? 1 : 0), [filters]);

    /* ── Derived: fold the selected projection mode into flat row fields ── */
    const totals = rowsData?.totals || null;
    const projKey = mode === 'pace' ? 'projectedPace' : 'projectedLinear';
    const dropKey = mode === 'pace' ? 'dropPace' : 'dropLinear';
    const pctKey = mode === 'pace' ? 'dropPctPace' : 'dropPctLinear';

    const allRows = useMemo(() => (rowsData?.rows || []).map((r, i) => {
        const pct = r[pctKey];
        const status = r.isNew ? 'NEW'
            : r.isStopped ? 'STOPPED'
            : pct == null ? 'STABLE'
            : pct <= -5 ? 'DECLINING'
            : pct >= 5 ? 'GROWING' : 'STABLE';
        return {
            id: r.mid || i,
            mid: r.mid, merchantName: r.merchantName,
            rmName: r.rmName, rmEmail: r.rmEmail, leadName: r.leadName,
            lastMonthVolume: num(r.lastMonthVolume),
            mtdVolume: num(r.mtdVolume),
            projected: num(r[projKey]),
            drop: num(r[dropKey]),
            dropPct: pct == null ? null : Number(pct),
            status,
        };
    }), [rowsData, projKey, dropKey, pctKey]);

    const gridRows = useMemo(() => {
        if (view === 'DECLINING') return allRows.filter(r => r.status === 'DECLINING' || r.status === 'STOPPED');
        if (view === 'STOPPED') return allRows.filter(r => r.status === 'STOPPED');
        if (view === 'NEW') return allRows.filter(r => r.status === 'NEW');
        return allRows;
    }, [allRows, view]);

    const decliningCount = useMemo(() =>
        allRows.filter(r => r.status === 'DECLINING' || r.status === 'STOPPED').length, [allRows]);

    const asOfLabel = rowsData
        ? `as of ${rowsData.asOf} · day ${rowsData.elapsedDays} of ${rowsData.daysInMonth}`
        : '';
    const isCompleteMonth = rowsData && rowsData.elapsedDays === rowsData.daysInMonth;

    const tDrop = totals ? num(totals[dropKey]) : 0;
    const tPct = totals ? totals[pctKey] : null;

    /* ── Grid columns — the Excel layout, plus RM email + status ── */
    const moneyCell = (v, style) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : <span className="vd-num" style={style}>{fmt.money(v)}</span>;

    const gridColumns = useMemo(() => [
        {
            field: 'mid', headerName: 'MID', flex: 0.75, minWidth: 110,
            renderCell: (p) => <span className="vd-num" style={{ color: 'var(--text-secondary)' }}>{p.value || '—'}</span>,
        },
        {
            field: 'merchantName', headerName: 'Merchant', flex: 1.5, minWidth: 190,
            renderCell: (p) => (
                <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.value || '—'}</span>
            ),
        },
        {
            field: 'rmName', headerName: 'RM', flex: 0.9, minWidth: 120,
            renderCell: (p) => p.value
                ? <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.value}</span>
                : <span style={{ color: 'var(--text-muted)' }}></span>,
        },
        {
            field: 'rmEmail', headerName: 'RM Email', flex: 1.1, minWidth: 150,
            renderCell: (p) => p.value
                ? <span style={{ fontSize: 12, color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.value}</span>
                : <span style={{ color: 'var(--text-muted)' }}>—</span>,
        },
        {
            field: 'leadName', headerName: 'Lead', flex: 0.9, minWidth: 120,
            renderCell: (p) => p.value
                ? <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.value}</span>
                : <span style={{ color: 'var(--text-muted)' }}>—</span>,
        },
        {
            field: 'lastMonthVolume', headerName: `${monthLabel(rowsData?.lastMonth)} (full)`, flex: 1, minWidth: 125,
            align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'mtdVolume',
            headerName: isCompleteMonth ? `${monthLabel(rowsData?.currentMonth)} (full)`
                : `MTD (${rowsData?.elapsedDays ?? '—'}d)`,
            flex: 1, minWidth: 115, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 600 }),
        },
        {
            field: 'projected', headerName: 'Projected', flex: 1, minWidth: 120,
            align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 700 }),
        },
        {
            field: 'drop', headerName: 'Drop / Gain', flex: 1, minWidth: 125,
            align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => p.value == null
                ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                : (
                    <span className="vd-num" style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4, fontWeight: 700,
                        color: num(p.value) >= 0 ? 'var(--success-text)' : 'var(--danger-text)',
                    }}>
                        <span aria-hidden="true" style={{ fontSize: 10 }}>{num(p.value) >= 0 ? '▲' : '▼'}</span>
                        {fmt.money(p.value)}
                    </span>
                ),
        },
        {
            field: 'dropPct', headerName: 'Drop %', flex: 0.7, minWidth: 90,
            align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => p.value == null
                ? <span style={{ color: 'var(--text-muted)', fontSize: 11 }}>new</span>
                : (
                    <span className="vd-num" style={{ fontWeight: 700,
                        color: num(p.value) >= 0 ? 'var(--success-text)' : 'var(--danger-text)' }}>
                        {num(p.value) >= 0 ? '+' : ''}{Number(p.value).toFixed(1)}%
                    </span>
                ),
        },
        {
            field: 'status', headerName: 'Status', flex: 0.75, minWidth: 105,
            renderCell: (p) => {
                const s = STATUS_STYLE[p.value] || STATUS_STYLE.STABLE;
                return (
                    <span style={{
                        padding: '3px 9px', fontSize: 10.5, fontWeight: 700, letterSpacing: '0.06em',
                        borderRadius: 999, background: s.bg, border: `1px solid ${s.border}`, color: s.text,
                    }}>
                        {p.value}
                    </span>
                );
            },
        },
    ], [fmt, rowsData, isCompleteMonth]);

    const exportRows = useCallback(() => {
        exportToCSV(gridRows.map(r => ({
            mid: r.mid, merchant: r.merchantName,
            rm: r.rmName, rm_email: r.rmEmail, lead: r.leadName,
            [`${rowsData?.lastMonth || 'last_month'}_full`]: r.lastMonthVolume,
            [`${rowsData?.currentMonth || 'this_month'}_mtd_${rowsData?.elapsedDays ?? ''}d`]: r.mtdVolume,
            [`projected_${mode}`]: r.projected,
            drop: r.drop, drop_pct: r.dropPct, status: r.status,
        })), 'volume_drop');
    }, [gridRows, rowsData, mode]);

    return (
        <div style={{
            padding: 'var(--space-page, 20px)', background: 'var(--bg)',
            minHeight: '100vh', width: '100%', boxSizing: 'border-box',
        }}>
            <style>{`
                .vd-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }
                .vd-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .vd-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); }

                /* ── Navy masthead + command deck (house executive header) ── */
                .vd-panel.vd-hdrblock { background: var(--table-head-bg,
                        linear-gradient(135deg, #24386B 0%, #16264A 55%, #0A1426 100%));
                    border-color: transparent; overflow: visible; margin-bottom: 12px; }
                .vd-hdrblock > :first-child { border-radius: calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px) 0 0; }
                .vd-hdrblock > :last-child { border-radius: 0 0 calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px); }
                .vd-mast { padding: 20px 24px 18px; display: flex; justify-content: space-between;
                    align-items: flex-end; gap: 18px; flex-wrap: wrap; }
                .vd-mast-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.18em; text-transform: uppercase;
                    color: var(--table-head-muted, #93A3C6); }
                .vd-mast h1 { margin: 8px 0 0; font-size: 26px; font-weight: 700;
                    letter-spacing: -0.025em; line-height: 1.08;
                    color: var(--table-head-text, #EEF3FC); }
                .vd-mast-sub { margin: 6px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .vd-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.22);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .vd-mast-btn:hover { background: rgba(255,255,255,0.14); }
                .vd-mast-btn:disabled { opacity: 0.5; cursor: default; }
                .vd-cmdbar { display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
                    padding: 10px 18px; background: rgba(0,0,0,0.20);
                    border-top: 1px solid rgba(255,255,255,0.13); }
                .vd-chip { padding: 7px 13px; font-size: 12px; font-weight: 600; cursor: pointer;
                    color: rgba(238,243,252,0.72); background: transparent; border: 0;
                    border-radius: var(--radius-sm); transition: background .12s ease, color .12s ease; }
                .vd-chip:hover { background: rgba(255,255,255,0.08); color: #EEF3FC; }
                .vd-chip-on { background: rgba(255,255,255,0.14); color: #EEF3FC;
                    box-shadow: inset 0 -2px 0 var(--chart-4, #7191CE); }
                .vd-month { padding: 6px 9px; font-size: 12px; font-family: var(--font-mono);
                    color: #EEF3FC; background: rgba(255,255,255,0.07);
                    border: 1px solid rgba(255,255,255,0.22); border-radius: var(--radius-sm);
                    outline: none; color-scheme: dark; }
                .vd-cmddiv { width: 1px; height: 18px; margin: 0 8px;
                    background: rgba(255,255,255,0.18); }

                .vd-focus:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
                .vd-cmdbar .vd-focus:focus-visible { outline-color: #EEF3FC; outline-offset: -2px; }
                .vd-cell { border-right: 1px solid var(--border-light, var(--border)); }
                @media (prefers-reduced-motion: reduce) {
                    .vd-chip, .vd-mast-btn { transition: none; }
                }
            `}</style>

            {/* ── Masthead + command deck ── */}
            <section className="vd-panel vd-hdrblock">
                <div className="vd-mast">
                    <div>
                        <div className="vd-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <TrendingDown size={11} /> Business · Retention
                        </div>
                        <h1>Volume Drop</h1>
                        <p className="vd-mast-sub">
                            {monthLabel(rowsData?.lastMonth)} full month vs {monthLabel(rowsData?.currentMonth)} to-date,
                            projected to month-end{asOfLabel ? ` · ${asOfLabel}` : ''} · {currencyCode || currencySymbol || ''}
                        </p>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                        <button className="vd-focus vd-mast-btn" onClick={() => setShowFilters(s => !s)}>
                            <Filter size={13} /> Filters{activeFilterCount > 0 ? ` (${activeFilterCount})` : ''}
                        </button>
                        <button className="vd-focus vd-mast-btn" onClick={exportRows} disabled={!gridRows.length}>
                            <Download size={13} /> Export
                        </button>
                        <button className="vd-focus vd-mast-btn" onClick={run} title="Refresh" aria-label="Refresh"
                            style={{ padding: '9px 11px' }}>
                            <RefreshCw size={14} className={rowsLoading ? 'animate-spin' : ''} />
                        </button>
                    </div>
                </div>
                <div className="vd-cmdbar">
                    <span className="vd-mast-eyebrow" style={{ marginRight: 10 }}>Month</span>
                    <input type="month" className="vd-focus vd-month" value={month}
                        min={earliestMonth} max={anchorMonth || undefined}
                        onChange={e => pickMonth(e.target.value)} aria-label="Comparison month" />
                    <span className="vd-cmddiv" aria-hidden="true" />
                    <span className="vd-mast-eyebrow" style={{ marginRight: 10 }}>Projection</span>
                    {MODES.map(m => (
                        <button key={m.key} title={m.hint}
                            className={`vd-focus vd-chip${mode === m.key ? ' vd-chip-on' : ''}`}
                            onClick={() => setMode(m.key)}>
                            {m.label}
                        </button>
                    ))}
                    <span className="vd-cmddiv" aria-hidden="true" />
                    <span className="vd-mast-eyebrow" style={{ marginRight: 10 }}>Show</span>
                    {VIEWS.map(v => (
                        <button key={v.key}
                            className={`vd-focus vd-chip${view === v.key ? ' vd-chip-on' : ''}`}
                            onClick={() => setView(v.key)}>
                            {v.label}
                        </button>
                    ))}
                </div>
            </section>

            <BusinessFilters
                filters={filters}
                onChange={handleAdvancedFilterChange}
                onApply={run}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            {/* ── Totals band ── */}
            {rowsError ? (
                <div style={{ marginBottom: 12 }}>
                    <SectionError message="The volume drop report did not load." onRetry={run} />
                </div>
            ) : rowsLoading ? (
                <div style={{ marginBottom: 12 }}><SkeletonLoader variant="kpi-row" count={6} /></div>
            ) : totals && (
                <section className="vd-panel" style={{ marginBottom: 12, overflow: 'hidden' }}>
                    <div style={{
                        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(165px, 1fr))',
                    }}>
                        <div className="vd-cell">
                            <Metric label={`${monthLabel(rowsData?.lastMonth)} (full)`}
                                value={fmt.currency(num(totals.lastMonthVolume))}
                                sub={`${formatNumber(num(totals.merchantCount))} merchants active`} />
                        </div>
                        <div className="vd-cell">
                            <Metric
                                label={isCompleteMonth ? `${monthLabel(rowsData?.currentMonth)} (full)`
                                    : `${monthLabel(rowsData?.currentMonth)} MTD`}
                                value={fmt.currency(num(totals.mtdVolume))}
                                sub={isCompleteMonth ? 'complete month' : `first ${rowsData.elapsedDays} of ${rowsData.daysInMonth} days`} />
                        </div>
                        <div className="vd-cell">
                            <Metric label={`Projected (${mode})`}
                                value={fmt.currency(num(totals[projKey]))}
                                sub={isCompleteMonth ? 'equals actual — month complete'
                                    : (mode === 'pace' ? "paced on last month's daily pattern" : 'straight daily average')} />
                        </div>
                        <div className="vd-cell">
                            <Metric label="Drop / Gain" value={fmt.currency(tDrop)}
                                tone={tDrop >= 0 ? 'success' : 'danger'}
                                sub={tPct == null ? '—' : `${tPct >= 0 ? '+' : ''}${Number(tPct).toFixed(1)}% vs last month`} />
                        </div>
                        <div className="vd-cell">
                            <Metric label="Declining" value={formatNumber(decliningCount)}
                                tone={decliningCount > 0 ? 'danger' : undefined}
                                sub={`incl. ${formatNumber(num(totals.stoppedCount))} stopped entirely`} />
                        </div>
                        <div>
                            <Metric label="New this month" value={formatNumber(num(totals.newCount))}
                                sub="no volume last month" />
                        </div>
                    </div>
                </section>
            )}

            {/* ── Merchant table — the Excel layout ── */}
            {rowsError ? (
                <SectionError message="The merchant table did not load." onRetry={run} />
            ) : (
                <Paper sx={premiumTableWrapper}>
                    <div style={{ padding: '16px 20px 13px', borderBottom: '1px solid var(--border-light, var(--border))',
                        display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
                        <div>
                            <div className="vd-eyebrow">Merchant drop report</div>
                            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                Drop = projected month-end − last month (negative = decline) · worst first ·
                                {mode === 'pace' ? " pace projection follows last month's daily pattern"
                                    : ' linear projection (MTD ÷ elapsed days × days in month)'}
                            </div>
                        </div>
                        {view !== 'ALL' && (
                            <span style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                {formatNumber(gridRows.length)} of {formatNumber(allRows.length)} merchants shown
                            </span>
                        )}
                    </div>
                    <DataGrid rows={gridRows} columns={gridColumns} loading={rowsLoading}
                        rowHeight={46} disableRowSelectionOnClick
                        initialState={{
                            sorting: { sortModel: [{ field: 'drop', sort: 'asc' }] },
                            pagination: { paginationModel: { pageSize: 25 } },
                        }}
                        pageSizeOptions={[25, 50, 100]}
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

export default VolumeDrop;
