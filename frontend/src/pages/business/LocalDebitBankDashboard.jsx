import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Paper } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    ComposedChart, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
    ResponsiveContainer, Cell,
} from 'recharts';
import { Landmark, RefreshCw, Download, Filter, AlertTriangle, Upload, Trash2, List } from 'lucide-react';
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
   Local Debit Bank Dashboard — which LOCAL issuing banks the tenant's
   DOMESTIC DEBIT volume comes from, resolved from the card BIN via the
   tenant-uploaded BIN → bank list. Same masthead/command-deck language
   as the Destination and Card Type dashboards; the split here is
   N-valued and DATA-DRIVEN (whatever banks the uploaded list names),
   with an always-present 'Other Banks' bucket for unmatched BINs.

   Scope is FIXED: domestic + debit only. Totals here (banks + Other
   Banks) reconcile with the Card Type Dashboard's DOMESTIC × DEBIT
   figures — that is the page's contract.

   Backend is sum_daily_local_debit_bin only (settlement basis), bank
   names joined live from ref_tenant_bin_bank, so a BIN list re-upload
   re-labels all history instantly.

   Date presets are anchored on the LATEST LOADED data date. ═══════ */

const num = (v) => (v == null ? 0 : Number(v));
const OTHER = 'Other Banks';

/* Series colors are assigned by RANK (banks are data, not enum): the
   top banks walk this fixed palette in volume order, everything past it
   plus the unmatched bucket renders slate grey. */
const BANK_PALETTE = [
    { top: '#D9924E', bottom: '#9C5E1F' }, // copper
    { top: '#5E82D2', bottom: '#33518F' }, // steel blue
    { top: '#5FAF87', bottom: '#2F6B4C' }, // green
    { top: '#9B85D6', bottom: '#5F4A9C' }, // violet
    { top: '#D28197', bottom: '#9C4762' }, // rose
    { top: '#5FA8BF', bottom: '#2E6B80' }, // teal
    { top: '#C2A75A', bottom: '#8A7226' }, // brass
    { top: '#8B9DC3', bottom: '#4F6491' }, // slate blue
];
const GREY = { top: '#94A3B8', bottom: '#475569' };

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

const CHART_ANIM = (delayMs = 0) => ({
    isAnimationActive: !prefersReducedMotion(),
    animationDuration: 900,
    animationEasing: 'ease-out',
    animationBegin: delayMs,
});

const EMPTY_LISTS = {
    // Only merchant-level filters exist on this page — the backing table has
    // no scheme/channel/mcc/store dims, and destination + card type are the
    // page's fixed scope (domestic debit).
    midList: [], partnerList: [], rmList: [], teamLeaderList: [],
    industryList: [], merchantName: '',
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

const Metric = ({ label, value, sub, accent, title }) => (
    <div title={title} style={{ padding: '15px 20px', minWidth: 0 }}>
        <div className="ldb-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {accent && <span style={{ width: 8, height: 8, borderRadius: 2, background: accent, flexShrink: 0 }} />}
            {label}
        </div>
        <div style={{
            marginTop: 7, fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
            fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em', color: 'var(--text)',
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

const LocalDebitBankDashboard = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const { latest: sharedLatest } = useDataBounds(tenantVersion);

    const [showFilters, setShowFilters] = useState(false);
    const [preset, setPreset] = useState('D30');
    const [filters, setFilters] = useState({ startDate: '', endDate: '', ...EMPTY_LISTS });
    const [filterVersion, setFilterVersion] = useState(0);
    const initRef = useRef(false);

    /* Bounds come from THIS page's own backing table, never the shared
       fact-anchored /business/data-bounds (which can point at a range this
       summary has no rows for). */
    const [pageBounds, setPageBounds] = useState({ latest: null, loaded: false });
    useEffect(() => {
        let cancelled = false;
        setPageBounds({ latest: null, loaded: false });
        (async () => {
            try {
                const res = await api.get('/business/local-debit-bank-dashboard/bounds');
                if (!cancelled) setPageBounds({ latest: res.data?.latest || null, loaded: true });
            } catch (e) {
                console.error('Failed to load local-debit-bank data bounds', e);
                if (!cancelled) setPageBounds({ latest: null, loaded: true });
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    const anchor = pageBounds.latest || sharedLatest;

    useEffect(() => {
        if (!pageBounds.loaded || initRef.current) return;
        initRef.current = true;
        setFilters(f => ({ ...f, ...computeRange('D30', anchor) }));
        setFilterVersion(v => v + 1);
    }, [pageBounds.loaded, anchor]);
    useEffect(() => { initRef.current = false; }, [tenantVersion]);

    // Per-section state so one failing endpoint doesn't blank the page.
    const [kpiData, setKpiData] = useState(null);
    const [kpiLoading, setKpiLoading] = useState(true);
    const [kpiError, setKpiError] = useState(false);
    const [trendData, setTrendData] = useState([]);
    const [trendLoading, setTrendLoading] = useState(true);
    const [trendError, setTrendError] = useState(false);
    const [merchantsData, setMerchantsData] = useState([]);
    const [merchantsLoading, setMerchantsLoading] = useState(true);
    const [merchantsError, setMerchantsError] = useState(false);
    const [merchantBank, setMerchantBank] = useState(''); // '' = all banks
    const [unmatched, setUnmatched] = useState([]);
    const [unmatchedLoading, setUnmatchedLoading] = useState(false);
    const [showUnmatched, setShowUnmatched] = useState(false);

    // BIN list management panel
    const [showBins, setShowBins] = useState(false);
    const [binList, setBinList] = useState([]);
    const [binListLoading, setBinListLoading] = useState(false);
    const [uploadBusy, setUploadBusy] = useState(false);
    const [uploadResult, setUploadResult] = useState(null);
    const fileInputRef = useRef(null);

    const fetchKpis = useCallback(async (f) => {
        setKpiLoading(true); setKpiError(false);
        try {
            const res = await api.post('/business/local-debit-bank-dashboard/kpis', f);
            setKpiData(res.data);
        } catch (e) {
            console.error('Failed to load local-debit-bank KPIs', e);
            setKpiError(true); setKpiData(null);
        } finally { setKpiLoading(false); }
    }, []);

    const fetchTrend = useCallback(async (f) => {
        setTrendLoading(true); setTrendError(false);
        try {
            const res = await api.post('/business/local-debit-bank-dashboard/trend', f);
            setTrendData(res.data || []);
        } catch (e) {
            console.error('Failed to load local-debit-bank trend', e);
            setTrendError(true); setTrendData([]);
        } finally { setTrendLoading(false); }
    }, []);

    const fetchMerchants = useCallback(async (f, bank) => {
        setMerchantsLoading(true); setMerchantsError(false);
        try {
            const qs = bank ? `?limit=25&bank=${encodeURIComponent(bank)}` : '?limit=25';
            const res = await api.post(`/business/local-debit-bank-dashboard/top-merchants${qs}`, f);
            setMerchantsData(res.data || []);
        } catch (e) {
            console.error('Failed to load local-debit-bank top merchants', e);
            setMerchantsError(true); setMerchantsData([]);
        } finally { setMerchantsLoading(false); }
    }, []);

    const fetchUnmatched = useCallback(async (f) => {
        setUnmatchedLoading(true);
        try {
            const res = await api.post('/business/local-debit-bank-dashboard/unmatched-bins?limit=100', f);
            setUnmatched(res.data || []);
        } catch (e) {
            console.error('Failed to load unmatched BINs', e);
            setUnmatched([]);
        } finally { setUnmatchedLoading(false); }
    }, []);

    const fetchBinList = useCallback(async () => {
        setBinListLoading(true);
        try {
            const res = await api.get('/business/local-debit-bank-dashboard/bins');
            setBinList(res.data || []);
        } catch (e) {
            console.error('Failed to load tenant BIN list', e);
            setBinList([]);
        } finally { setBinListLoading(false); }
    }, []);

    useEffect(() => {
        if (!filterVersion) return;
        fetchKpis(filters);
        fetchTrend(filters);
        fetchMerchants(filters, merchantBank);
    }, [filterVersion, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps
    useEffect(() => {
        if (!filterVersion) return;
        fetchMerchants(filters, merchantBank);
    }, [merchantBank]); // eslint-disable-line react-hooks/exhaustive-deps
    useEffect(() => {
        if (showUnmatched && filterVersion) fetchUnmatched(filters);
    }, [showUnmatched, filterVersion]); // eslint-disable-line react-hooks/exhaustive-deps
    useEffect(() => {
        if (showBins) fetchBinList();
    }, [showBins, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

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
        ['midList', 'partnerList', 'rmList', 'teamLeaderList', 'industryList']
            .reduce((a, k) => a + (filters[k]?.length || 0), 0)
        + (filters.merchantName ? 1 : 0), [filters]);

    /* ── The split: banks sorted by volume (backend order), colored by rank ── */
    const bankBlocks = useMemo(() => kpiData?.banks || [], [kpiData]);
    const bankStyle = useMemo(() => {
        const map = {};
        let rank = 0;
        for (const b of bankBlocks) {
            map[b.bank] = b.bank === OTHER ? GREY : (BANK_PALETTE[rank++] || GREY);
        }
        return (bank) => map[bank] || GREY;
    }, [bankBlocks]);
    const bankGrad = (bank) => {
        const s = bankStyle(bank);
        return `linear-gradient(135deg, ${s.top} 0%, ${s.bottom} 100%)`;
    };

    const totalVol = num(kpiData?.totalVolume);
    const totalTxn = num(kpiData?.totalTxns);
    const priorHasData = kpiData?.priorWindowHasData === true;
    const growth = (v) => {
        if (!priorHasData || v == null) return null;
        const n = Number(v);
        return `${n >= 0 ? '▲' : '▼'} ${Math.abs(n).toFixed(1)}% vs prior`;
    };

    const windowLabel = kpiData?.start && kpiData?.end
        ? `${kpiData.start} → ${kpiData.end}`
        : (filters.startDate && filters.endDate ? `${filters.startDate} → ${filters.endDate}` : '—');

    /* Ribbon shows the top banks + Other; ranking chart shows all blocks. */
    const ribbonBlocks = useMemo(() => bankBlocks.filter(b => num(b.volume) > 0), [bankBlocks]);

    /* ── Trend: pivot month × bank into one row per month, series = top banks ── */
    const trendBanks = useMemo(() => {
        const totals = new Map();
        for (const d of trendData) totals.set(d.bank, (totals.get(d.bank) || 0) + num(d.volume));
        const named = Array.from(totals.entries()).filter(([b]) => b !== OTHER)
            .sort((a, b) => b[1] - a[1]).map(([b]) => b).slice(0, 6);
        return totals.has(OTHER) || Array.from(totals.keys()).length > named.length
            ? [...named, OTHER] : named;
    }, [trendData]);
    const chartData = useMemo(() => {
        const years = new Set(trendData.map(d => String(d.period || '').slice(0, 4)));
        const multiYear = years.size > 1;
        const byMonth = new Map();
        for (const d of trendData) {
            const key = String(d.period || '');
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
            const row = byMonth.get(key);
            const series = trendBanks.includes(d.bank) ? d.bank : OTHER;
            row[series] = (row[series] || 0) + num(d.volume);
        }
        return Array.from(byMonth.values()).sort((a, b) => a.month.localeCompare(b.month));
    }, [trendData, trendBanks]);

    const trendShortfall = useMemo(() => {
        if (!trendData.length || !filters.endDate) return null;
        const lastLoaded = trendData.reduce((a, d) => (String(d.period) > a ? String(d.period) : a), '');
        const requested = String(filters.endDate).slice(0, 7);
        return lastLoaded && lastLoaded < requested ? lastLoaded : null;
    }, [trendData, filters.endDate]);

    const TrendTooltip = ({ active, payload }) => {
        if (!active || !payload?.length) return null;
        const row = payload[0].payload;
        return (
            <div style={TOOLTIP_PROPS.contentStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6 }}>{row.monthLong}</div>
                {trendBanks.filter(b => row[b] != null).map(b => (
                    <div key={b} style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                        <span style={{ color: 'var(--text-secondary)' }}>{b}</span>
                        <span className="ldb-num" style={{ fontWeight: 700, color: bankStyle(b).bottom }}>
                            {fmt.currency(num(row[b]))}
                        </span>
                    </div>
                ))}
            </div>
        );
    };

    /* ── Bank ranking chart data ── */
    const rankingData = useMemo(() => bankBlocks.map(b => ({
        name: b.bank, volume: num(b.volume), txns: num(b.txns),
        sharePct: num(b.sharePct), isOther: b.bank === OTHER,
    })), [bankBlocks]);

    const RankingTooltip = ({ active, payload }) => {
        if (!active || !payload?.length) return null;
        const row = payload[0].payload;
        return (
            <div style={TOOLTIP_PROPS.contentStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6 }}>{row.name}</div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18 }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Volume</span>
                    <span className="ldb-num" style={{ fontWeight: 700 }}>{fmt.currency(row.volume)}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Transactions</span>
                    <span className="ldb-num" style={{ fontWeight: 700 }}>{formatNumber(row.txns)}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                    <span style={{ color: 'var(--text-secondary)' }}>Share</span>
                    <span className="ldb-num" style={{ fontWeight: 700 }}>{row.sharePct.toFixed(1)}%</span>
                </div>
            </div>
        );
    };

    /* ── Top merchants grid ── */
    const merchantRows = useMemo(() => merchantsData.map((m, i) => ({ id: m.mid || i, ...m })), [merchantsData]);
    const moneyCell = (v, style) => v == null
        ? <span style={{ color: 'var(--text-muted)' }}>—</span>
        : <span className="ldb-num" style={style}>{fmt.money(v)}</span>;

    const merchantColumns = useMemo(() => [
        {
            field: 'mid', headerName: 'MID', width: 120,
            renderCell: (p) => <span className="ldb-num" style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>{p.value || '—'}</span>,
        },
        {
            field: 'merchantName', headerName: 'Merchant', flex: 1.4, minWidth: 190,
            renderCell: (p) => <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.value}</span>,
        },
        {
            field: 'volume', headerName: 'Debit Volume', flex: 1, minWidth: 120, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { fontWeight: 700 }),
        },
        {
            field: 'txns', headerName: 'Transactions', flex: 0.9, minWidth: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => <span className="ldb-num" style={{ fontWeight: 600 }}>{formatNumber(num(p.value))}</span>,
        },
        {
            field: 'avgTicket', headerName: 'Avg Ticket', flex: 0.8, minWidth: 100, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'msf', headerName: 'MSF', flex: 0.8, minWidth: 95, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => moneyCell(p.value, { color: 'var(--text-secondary)' }),
        },
        {
            field: 'binCount', headerName: 'Distinct BINs', flex: 0.7, minWidth: 100, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => <span className="ldb-num" style={{ color: 'var(--text-secondary)' }}>{formatNumber(num(p.value))}</span>,
        },
    ], [fmt]);

    /* ── BIN upload ── */
    const handleUpload = async (file) => {
        if (!file) return;
        setUploadBusy(true); setUploadResult(null);
        try {
            const form = new FormData();
            form.append('file', file);
            const res = await api.post('/business/local-debit-bank-dashboard/bins?mode=REPLACE', form,
                { headers: { 'Content-Type': 'multipart/form-data' } });
            setUploadResult(res.data);
            fetchBinList();
            run(); // re-run the dashboard — history re-labels instantly
        } catch (e) {
            setUploadResult({ error: e.response?.data?.error || e.message || 'Upload failed' });
        } finally {
            setUploadBusy(false);
            if (fileInputRef.current) fileInputRef.current.value = '';
        }
    };

    const matchedPct = num(kpiData?.matchedVolumePct);

    return (
        <div style={{
            padding: 'var(--space-page, 20px)', background: 'var(--bg)',
            minHeight: '100vh', width: '100%', boxSizing: 'border-box',
        }}>
            <style>{`
                .ldb-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.16em; text-transform: uppercase; color: var(--text-muted); }
                .ldb-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
                .ldb-panel { background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-xl); }

                .ldb-panel.ldb-hdrblock { background: var(--table-head-bg,
                        linear-gradient(135deg, #24386B 0%, #16264A 55%, #0A1426 100%));
                    border-color: transparent; overflow: visible; margin-bottom: 12px; }
                .ldb-hdrblock > :first-child { border-radius: calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px) 0 0; }
                .ldb-hdrblock > :last-child { border-radius: 0 0 calc(var(--radius-xl) - 1px)
                    calc(var(--radius-xl) - 1px); }
                .ldb-mast { padding: 20px 24px 18px; display: flex; justify-content: space-between;
                    align-items: flex-end; gap: 18px; flex-wrap: wrap; }
                .ldb-mast-eyebrow { font-family: var(--font-mono); font-size: 9.5px; font-weight: 600;
                    letter-spacing: 0.18em; text-transform: uppercase;
                    color: var(--table-head-muted, #93A3C6); }
                .ldb-mast h1 { margin: 8px 0 0; font-size: 26px; font-weight: 700;
                    letter-spacing: -0.025em; line-height: 1.08;
                    color: var(--table-head-text, #EEF3FC); }
                .ldb-mast-sub { margin: 6px 0 0; font-size: 12.5px;
                    color: color-mix(in srgb, var(--table-head-text, #EEF3FC) 62%, transparent); }
                .ldb-mast-btn { display: flex; align-items: center; gap: 6px;
                    padding: 9px 15px; font-size: 12.5px; font-weight: 600;
                    color: var(--table-head-text, #EEF3FC);
                    background: rgba(255,255,255,0.07); border: 1px solid rgba(255,255,255,0.22);
                    border-radius: var(--radius-sm); cursor: pointer; transition: background .12s ease; }
                .ldb-mast-btn:hover { background: rgba(255,255,255,0.14); }
                .ldb-mast-btn:disabled { opacity: 0.5; cursor: default; }
                .ldb-cmdbar { display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
                    padding: 10px 18px; background: rgba(0,0,0,0.20);
                    border-top: 1px solid rgba(255,255,255,0.13); }
                .ldb-preset { padding: 7px 13px; font-size: 12px; font-weight: 600; cursor: pointer;
                    color: rgba(238,243,252,0.72); background: transparent; border: 0;
                    border-radius: var(--radius-sm); transition: background .12s ease, color .12s ease; }
                .ldb-preset:hover { background: rgba(255,255,255,0.08); color: #EEF3FC; }
                .ldb-preset-on { background: rgba(255,255,255,0.14); color: #EEF3FC;
                    box-shadow: inset 0 -2px 0 var(--chart-4, #7191CE); }
                .ldb-date { padding: 6px 9px; font-size: 12px; font-family: var(--font-mono);
                    color: #EEF3FC; background: rgba(255,255,255,0.07);
                    border: 1px solid rgba(255,255,255,0.22); border-radius: var(--radius-sm);
                    outline: none; color-scheme: dark; }

                @keyframes ldbSweep { from { transform: scaleX(0); } to { transform: scaleX(1); } }
                .ldb-ribbon { display: flex; height: 12px; border-radius: 999px; overflow: hidden;
                    background: var(--border-light, var(--border));
                    transform-origin: left; animation: ldbSweep .8s ease-out; }
                .ldb-ribbon > div { transition: width .7s cubic-bezier(0.22, 1, 0.36, 1); }
                @media (prefers-reduced-motion: reduce) {
                    .ldb-ribbon { animation: none; }
                    .ldb-ribbon > div { transition: none; }
                }

                .ldb-focus:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
                .ldb-cmdbar .ldb-focus:focus-visible { outline-color: #EEF3FC; outline-offset: -2px; }
                .ldb-cell { border-right: 1px solid var(--border-light, var(--border)); }
                .ldb-chip { display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
                    padding: 6px 12px; font-size: 12px; font-weight: 600; border-radius: 999px;
                    border: 1px solid var(--border); background: var(--bg-card); color: var(--text-secondary);
                    transition: background .12s ease, color .12s ease; }
                .ldb-chip:hover { background: var(--bg-hover); color: var(--text); }
                .ldb-chip-on { background: var(--primary); border-color: var(--primary); color: #fff; }
                @media (prefers-reduced-motion: reduce) {
                    .ldb-preset, .ldb-mast-btn, .ldb-chip { transition: none; }
                }
            `}</style>

            {/* ── Masthead + command deck ── */}
            <section className="ldb-panel ldb-hdrblock">
                <div className="ldb-mast">
                    <div>
                        <div className="ldb-mast-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                            <Landmark size={11} /> Business · Local Debit Banks
                        </div>
                        <h1>Local Debit Bank Dashboard</h1>
                        <p className="ldb-mast-sub">
                            Domestic debit volume by issuing bank (via BIN) · {windowLabel} · {currencyCode || currencySymbol || ''}
                        </p>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 10, flexWrap: 'wrap' }}>
                        <button className="ldb-focus ldb-mast-btn" onClick={() => setShowBins(s => !s)}>
                            <Upload size={13} /> BIN list
                        </button>
                        <button className="ldb-focus ldb-mast-btn" onClick={() => setShowFilters(s => !s)}>
                            <Filter size={13} /> Filters{activeFilterCount > 0 ? ` (${activeFilterCount})` : ''}
                        </button>
                        <button className="ldb-focus ldb-mast-btn"
                            onClick={() => exportToCSV(merchantRows, 'local_debit_bank_top_merchants')}
                            disabled={!merchantRows.length}>
                            <Download size={13} /> Export
                        </button>
                        <button className="ldb-focus ldb-mast-btn" onClick={run} title="Refresh" aria-label="Refresh"
                            style={{ padding: '9px 11px' }}>
                            <RefreshCw size={14} className={kpiLoading ? 'animate-spin' : ''} />
                        </button>
                    </div>
                </div>
                <div className="ldb-cmdbar">
                    <span className="ldb-mast-eyebrow" style={{ marginRight: 10 }}>Window</span>
                    {PRESETS.map(p => (
                        <button key={p.key}
                            className={`ldb-focus ldb-preset${preset === p.key ? ' ldb-preset-on' : ''}`}
                            onClick={() => pickPreset(p.key)}>
                            {p.label}
                        </button>
                    ))}
                    {preset === 'CUSTOM' && (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, marginLeft: 6 }}>
                            <input type="date" className="ldb-date" value={filters.startDate}
                                onChange={e => setCustomDate('startDate', e.target.value)} aria-label="From date" />
                            <span style={{ color: 'rgba(238,243,252,0.5)', fontSize: 11 }}>to</span>
                            <input type="date" className="ldb-date" value={filters.endDate}
                                onChange={e => setCustomDate('endDate', e.target.value)} aria-label="To date" />
                            <button className="ldb-focus ldb-mast-btn" style={{ padding: '6px 13px' }} onClick={run}>
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
                merchantOnly
                hideDestination
                hideCardType
            />

            {/* ── BIN list management panel ── */}
            {showBins && (
                <section className="ldb-panel" style={{ marginBottom: 12, padding: '18px 20px' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                        flexWrap: 'wrap', gap: 12, marginBottom: 12 }}>
                        <div>
                            <div className="ldb-eyebrow">Tenant BIN → bank list</div>
                            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                CSV or Excel, columns <b>BIN</b> + <b>BANK</b>. 6 or 8 digits — 8-digit BINs are
                                stored as their 6-digit prefix. Upload REPLACES the current list; the whole
                                history re-labels instantly (no rebuild needed).
                            </div>
                        </div>
                        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                            <input ref={fileInputRef} type="file" accept=".csv,.xlsx,.xls" style={{ display: 'none' }}
                                onChange={e => handleUpload(e.target.files?.[0])} />
                            <button className="ldb-chip" disabled={uploadBusy}
                                onClick={() => fileInputRef.current?.click()}>
                                <Upload size={13} /> {uploadBusy ? 'Uploading…' : 'Upload file'}
                            </button>
                            <button className="ldb-chip" onClick={fetchBinList} disabled={binListLoading}>
                                <List size={13} /> Refresh
                            </button>
                            <button className="ldb-chip" style={{ color: 'var(--danger-text, #991b1b)' }}
                                onClick={async () => {
                                    if (!window.confirm('Delete the entire BIN list for this tenant?')) return;
                                    await api.delete('/business/local-debit-bank-dashboard/bins');
                                    fetchBinList(); run();
                                }}>
                                <Trash2 size={13} /> Clear all
                            </button>
                        </div>
                    </div>
                    {uploadResult && (
                        <div style={{ marginBottom: 12, padding: '10px 14px', borderRadius: 'var(--radius-md)',
                            fontSize: 12.5,
                            border: `1px solid ${uploadResult.error ? 'var(--danger-border, #fecaca)' : 'var(--border)'}`,
                            background: uploadResult.error ? 'var(--danger-bg, #fef2f2)' : 'var(--bg-hover)' }}>
                            {uploadResult.error ? (
                                <span style={{ color: 'var(--danger-text, #991b1b)', fontWeight: 600 }}>{uploadResult.error}</span>
                            ) : (
                                <>
                                    <b>{uploadResult.loaded}</b> BINs loaded ({uploadResult.distinctBanks} banks)
                                    {num(uploadResult.truncatedFrom8Digits) > 0 && <> · {uploadResult.truncatedFrom8Digits} truncated from 8 digits</>}
                                    {num(uploadResult.rejected) > 0 && <> · <b>{uploadResult.rejected} rejected</b></>}
                                    {uploadResult.prefixCollisions?.length > 0 && (
                                        <div style={{ marginTop: 6, color: 'var(--warning-text, #92400e)' }}>
                                            Prefix collisions (first occurrence kept):{' '}
                                            {uploadResult.prefixCollisions.join(' · ')}
                                        </div>
                                    )}
                                    {uploadResult.rejectSamples?.length > 0 && (
                                        <div style={{ marginTop: 6, color: 'var(--text-secondary)' }}>
                                            {uploadResult.rejectSamples.join(' · ')}
                                        </div>
                                    )}
                                </>
                            )}
                        </div>
                    )}
                    <div style={{ maxHeight: 260, overflowY: 'auto', border: '1px solid var(--border-light, var(--border))',
                        borderRadius: 'var(--radius-md)' }}>
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
                            <thead>
                                <tr style={{ position: 'sticky', top: 0, background: 'var(--bg-card)' }}>
                                    <th style={{ textAlign: 'left', padding: '8px 12px' }} className="ldb-eyebrow">BIN</th>
                                    <th style={{ textAlign: 'left', padding: '8px 12px' }} className="ldb-eyebrow">Bank</th>
                                    <th style={{ textAlign: 'left', padding: '8px 12px' }} className="ldb-eyebrow">Source file</th>
                                    <th style={{ padding: '8px 12px' }} />
                                </tr>
                            </thead>
                            <tbody>
                                {binListLoading ? (
                                    <tr><td colSpan={4} style={{ padding: 16, textAlign: 'center', color: 'var(--text-muted)' }}>Loading…</td></tr>
                                ) : binList.length === 0 ? (
                                    <tr><td colSpan={4} style={{ padding: 16, textAlign: 'center', color: 'var(--text-muted)' }}>
                                        No BINs uploaded yet — every local debit transaction currently shows under "{OTHER}".
                                    </td></tr>
                                ) : binList.map(b => (
                                    <tr key={b.bin} style={{ borderTop: '1px solid var(--border-light, var(--border))' }}>
                                        <td className="ldb-num" style={{ padding: '7px 12px', fontWeight: 600 }}>{b.bin}</td>
                                        <td style={{ padding: '7px 12px' }}>{b.bank_name}</td>
                                        <td style={{ padding: '7px 12px', color: 'var(--text-muted)' }}>{b.source_file || '—'}</td>
                                        <td style={{ padding: '4px 12px', textAlign: 'right' }}>
                                            <button className="ldb-chip" style={{ padding: '3px 9px' }}
                                                onClick={async () => {
                                                    await api.delete(`/business/local-debit-bank-dashboard/bins/${b.bin}`);
                                                    fetchBinList(); run();
                                                }}>
                                                <Trash2 size={11} />
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    <div style={{ marginTop: 8, fontSize: 11.5, color: 'var(--text-muted)' }}>
                        {binList.length > 0 && `${binList.length} BINs · ${new Set(binList.map(b => b.bank_name)).size} banks`}
                    </div>
                </section>
            )}

            {/* ── KPI band + bank share ribbon ── */}
            {kpiError ? (
                <div style={{ marginBottom: 12 }}>
                    <SectionError message="The bank KPIs did not load." onRetry={() => fetchKpis(filters)} />
                </div>
            ) : kpiLoading ? (
                <div style={{ marginBottom: 12 }}><SkeletonLoader variant="kpi-row" count={4} /></div>
            ) : kpiData && (
                <section className="ldb-panel" style={{ marginBottom: 12, overflow: 'hidden' }}>
                    <div style={{
                        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                        borderBottom: '1px solid var(--border-light, var(--border))',
                    }}>
                        <div className="ldb-cell">
                            <Metric label="Local debit volume"
                                value={fmt.currency(totalVol)}
                                sub={growth(kpiData.totalVolumeGrowthPct) || 'domestic · debit only'} />
                        </div>
                        <div className="ldb-cell">
                            <Metric label="Local debit transactions"
                                value={formatNumber(totalTxn)}
                                sub={growth(kpiData.totalTxnsGrowthPct) || 'count in this window'} />
                        </div>
                        <div className="ldb-cell">
                            <Metric label="Avg ticket"
                                value={fmt.money(num(kpiData.avgTicket))}
                                sub="volume ÷ transactions" />
                        </div>
                        <div>
                            <Metric label="Matched to a bank"
                                value={`${matchedPct.toFixed(1)}%`}
                                sub={num(kpiData.unmatchedBinCount) > 0
                                    ? `${formatNumber(num(kpiData.unmatchedBinCount))} BINs unmatched — see worklist`
                                    : 'every BIN matched'}
                                accent={matchedPct >= 90 ? 'var(--success-text, #15803d)' : 'var(--warning-text, #b45309)'} />
                        </div>
                    </div>
                    <div style={{ padding: '15px 20px 16px' }}>
                        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
                            <span className="ldb-eyebrow">Who issued the cards</span>
                            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                {totalVol > 0 ? `${fmt.currency(totalVol)} local debit volume in this window`
                                    : 'no local debit volume in this window'}
                            </span>
                        </div>
                        {totalVol > 0 ? (
                            <>
                                <div className="ldb-ribbon">
                                    {ribbonBlocks.map((b, i) => (
                                        <div key={b.bank}
                                            title={`${b.bank} · ${fmt.currency(num(b.volume))} · ${num(b.sharePct).toFixed(1)}%`}
                                            style={{ width: `${num(b.sharePct)}%`, background: bankGrad(b.bank),
                                                borderRight: i < ribbonBlocks.length - 1
                                                    ? '1px solid var(--bg-card)' : undefined }} />
                                    ))}
                                </div>
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '5px 20px', marginTop: 9 }}>
                                    {ribbonBlocks.slice(0, 10).map(b => (
                                        <span key={b.bank} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                            fontSize: 11, color: 'var(--text-secondary)' }}>
                                            <span style={{ width: 14, height: 9, borderRadius: 2, background: bankGrad(b.bank) }} />
                                            <span style={{ fontWeight: 600, color: 'var(--text)' }}>{b.bank}</span>
                                            <span className="ldb-num">{fmt.currency(num(b.volume))}</span>
                                            <span style={{ opacity: 0.72 }}>{num(b.sharePct).toFixed(1)}%</span>
                                        </span>
                                    ))}
                                    {ribbonBlocks.length > 10 && (
                                        <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                                            +{ribbonBlocks.length - 10} more below
                                        </span>
                                    )}
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

            {/* ── Bank ranking ── */}
            <section className="ldb-panel" style={{ padding: '18px 20px 14px', marginBottom: 12 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                    marginBottom: 14, flexWrap: 'wrap', gap: 12 }}>
                    <div>
                        <div className="ldb-eyebrow">Bank ranking</div>
                        <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                            Local debit volume by issuing bank · "{OTHER}" = BINs not in the uploaded list
                        </div>
                    </div>
                    <button className={`ldb-chip${showUnmatched ? ' ldb-chip-on' : ''}`}
                        onClick={() => setShowUnmatched(s => !s)}>
                        <AlertTriangle size={13} /> Unmatched BIN worklist
                    </button>
                </div>
                {kpiLoading ? (
                    <SkeletonLoader variant="chart" />
                ) : rankingData.length === 0 ? (
                    <div style={{ padding: '36px 0', textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>
                        No local debit volume for the selected filters and window.
                    </div>
                ) : (
                    <ResponsiveContainer width="100%" height={Math.max(180, rankingData.length * 40)}>
                        <BarChart data={rankingData} layout="vertical"
                            margin={{ top: 4, right: 16, left: 8, bottom: 4 }}>
                            <defs>
                                {rankingData.map(r => {
                                    const s = r.isOther ? GREY : bankStyle(r.name);
                                    return (
                                        <linearGradient key={r.name} id={`ldbG${r.name.replace(/[^A-Za-z0-9]/g, '')}`}
                                            x1="0" y1="0" x2="1" y2="0">
                                            <stop offset="0%" stopColor={s.bottom} />
                                            <stop offset="100%" stopColor={s.top} />
                                        </linearGradient>
                                    );
                                })}
                            </defs>
                            <CartesianGrid {...GRID_PROPS} vertical horizontal={false} />
                            <XAxis type="number" {...AXIS_PROPS}
                                tickFormatter={(v) => formatCompactCurrency(v)} />
                            <YAxis type="category" dataKey="name" width={190} {...AXIS_PROPS} />
                            <Tooltip content={<RankingTooltip />} cursor={TOOLTIP_PROPS.cursor} />
                            <Bar dataKey="volume" maxBarSize={18} radius={[0, 3, 3, 0]}
                                {...(rankingData.length > 15 ? { isAnimationActive: false } : CHART_ANIM(0))}>
                                {rankingData.map(r => (
                                    <Cell key={r.name} fill={`url(#ldbG${r.name.replace(/[^A-Za-z0-9]/g, '')})`} />
                                ))}
                            </Bar>
                        </BarChart>
                    </ResponsiveContainer>
                )}
                {showUnmatched && (
                    <div style={{ marginTop: 14, borderTop: '1px solid var(--border-light, var(--border))', paddingTop: 12 }}>
                        <div className="ldb-eyebrow" style={{ marginBottom: 8 }}>
                            Top unmatched BINs by volume — copy these into the next upload
                        </div>
                        {unmatchedLoading ? (
                            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>Loading…</div>
                        ) : unmatched.length === 0 ? (
                            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                                Every local debit BIN in this window is matched to a bank.
                            </div>
                        ) : (
                            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                                {unmatched.map(u => (
                                    <span key={u.bin} className="ldb-num" title={`${formatNumber(num(u.txns))} txns · ${u.merchants} merchants`}
                                        style={{ fontSize: 12, padding: '4px 10px', borderRadius: 999,
                                            border: '1px solid var(--border)', background: 'var(--bg-hover)' }}>
                                        <b>{u.bin}</b> · {formatCompactCurrency(num(u.volume))}
                                    </span>
                                ))}
                            </div>
                        )}
                    </div>
                )}
            </section>

            {/* ── Monthly trend ── */}
            <section style={{ marginBottom: 12 }}>
                {trendError ? (
                    <SectionError message="The monthly bank trend did not load." onRetry={() => fetchTrend(filters)} />
                ) : trendLoading ? (
                    <SkeletonLoader variant="chart" />
                ) : (
                    <div className="ldb-panel" style={{ padding: '18px 20px 10px' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                            marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
                            <div>
                                <div className="ldb-eyebrow">Monthly trend · selected window</div>
                                <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                    Local debit volume by issuing bank by month (top banks; the rest stack into {OTHER})
                                </div>
                                {trendShortfall && (
                                    <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--warning-text, #92400e)', marginTop: 4 }}>
                                        Data loaded through {trendShortfall} — later months in this window have no data yet.
                                    </div>
                                )}
                            </div>
                            <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                                {trendBanks.map(b => (
                                    <span key={b} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                        fontSize: 11, fontWeight: 600, color: 'var(--text-secondary)' }}>
                                        <span style={{ width: 14, height: 9, borderRadius: 2, background: bankGrad(b) }} />
                                        {b}
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
                                    <defs>
                                        {trendBanks.map(b => {
                                            const s = bankStyle(b);
                                            return (
                                                <linearGradient key={b} id={`ldbT${b.replace(/[^A-Za-z0-9]/g, '')}`}
                                                    x1="0" y1="0" x2="0" y2="1">
                                                    <stop offset="0%" stopColor={s.top} />
                                                    <stop offset="100%" stopColor={s.bottom} />
                                                </linearGradient>
                                            );
                                        })}
                                    </defs>
                                    <CartesianGrid {...GRID_PROPS} />
                                    <XAxis dataKey="monthShort" {...AXIS_PROPS} />
                                    <YAxis {...AXIS_PROPS}
                                        tickFormatter={(v) => formatCompactCurrency(v)} width={78} />
                                    <Tooltip content={<TrendTooltip />} cursor={TOOLTIP_PROPS.cursor} />
                                    {trendBanks.map((b, i) => (
                                        <Bar key={b} dataKey={b} stackId="vol"
                                            fill={`url(#ldbT${b.replace(/[^A-Za-z0-9]/g, '')})`} maxBarSize={40}
                                            radius={i === trendBanks.length - 1 ? [3, 3, 0, 0] : undefined}
                                            {...CHART_ANIM(i * 150)} />
                                    ))}
                                </ComposedChart>
                            </ResponsiveContainer>
                        )}
                    </div>
                )}
            </section>

            {/* ── Top merchants (optionally per bank) ── */}
            {merchantsError ? (
                <SectionError message="The top merchants table did not load."
                    onRetry={() => fetchMerchants(filters, merchantBank)} />
            ) : (
                <Paper sx={premiumTableWrapper}>
                    <div style={{ padding: '16px 20px 13px', borderBottom: '1px solid var(--border-light, var(--border))',
                        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
                        <div>
                            <div className="ldb-eyebrow">Top merchants</div>
                            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                                Highest local-debit-volume merchants{merchantBank ? ` · ${merchantBank} cards only` : ' · all banks'}
                            </div>
                        </div>
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                            <button className={`ldb-chip${merchantBank === '' ? ' ldb-chip-on' : ''}`}
                                onClick={() => setMerchantBank('')}>All banks</button>
                            {bankBlocks.slice(0, 8).map(b => (
                                <button key={b.bank}
                                    className={`ldb-chip${merchantBank === b.bank ? ' ldb-chip-on' : ''}`}
                                    onClick={() => setMerchantBank(b.bank)}>
                                    <span style={{ width: 9, height: 9, borderRadius: 2, background: bankGrad(b.bank) }} />
                                    {b.bank}
                                </button>
                            ))}
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

            <div style={{ marginTop: 10, fontSize: 11.5, color: 'var(--text-muted)' }}>
                Scope: DOMESTIC × DEBIT only, settlement basis — totals here (banks + {OTHER}) reconcile with the
                Card Type Dashboard's Domestic Debit figures. Debit rows whose destination could not be mapped are
                excluded on both pages.
            </div>
        </div>
    );
};

export default LocalDebitBankDashboard;
