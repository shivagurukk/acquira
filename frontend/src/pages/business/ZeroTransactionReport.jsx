import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Chip, Stack, TextField, Collapse } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Area, AreaChart,
    Tooltip as ReTooltip, ResponsiveContainer, Cell
} from 'recharts';
import { AlertTriangle, Clock, Users, TrendingDown, XCircle, Building2, MonitorSmartphone, Activity } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';
import { createFmt } from '../../utils/formatters';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer, chartTooltipStyle } from '../../theme/dataGridStyles';

// ─── Local design tokens ─────────────────────────────────────────
// Every colour routes through a CSS variable with a light-mode fallback so the
// report adapts cleanly under html.dark + ThemeContext. Status/severity hues
// keep their meaning across themes; the dark stylesheet can override --ztx-*.
const T = {
    card:     'var(--bg-card, #ffffff)',
    subtle:   'var(--bg-subtle, #f8fafc)',
    hover:    'var(--bg-hover, #f8fafc)',
    border:   'var(--border, #e2e8f0)',
    borderLt: 'var(--border-light, #eef2f7)',
    text:     'var(--text, #1e293b)',
    textSec:  'var(--text-secondary, #475569)',
    textMut:  'var(--text-muted, #94a3b8)',
    strong:   'var(--text, #334155)',
    indigo:   'var(--accent-indigo, #6366f1)',
    axis:     'var(--text-muted, #94a3b8)',
    grid:     'var(--border-light, #eef2f7)',
};

const RANGE_TYPES = [
    { key: 'LAST_7', label: 'Last 7 Days' },
    { key: 'LAST_30', label: 'Last 30 Days' },
    { key: 'NEVER', label: 'Since Onboarding' },
];

// Severity status → colour + subtle bg + readable fg, all routed through vars.
const STATUS = {
    never: { color: 'var(--ztx-never, #64748b)',  bg: 'var(--ztx-never-bg, #f1f5f9)', fg: 'var(--ztx-never-fg, #475569)' },
    in30:  { color: 'var(--danger, #ef4444)',      bg: 'var(--danger-chip, #fee2e2)',  fg: 'var(--danger-text, #991b1b)' },
    in7:   { color: 'var(--warning, #f59e0b)',     bg: 'var(--warning-chip, #fef3c7)', fg: 'var(--warning-text, #92400e)' },
};
const BUCKET_COLORS = {
    '≤14d': 'var(--chart-5)', '15–30d': 'var(--chart-4)', '31–60d': 'var(--chart-3)',
    '61–90d': 'var(--chart-2)', '90d+': 'var(--chart-1)', 'Never': 'var(--ztx-never, #64748b)',
};

// ─── Terminal / POS estate health ────────────────────────────────
// Estate composition reads as a decay gradient: healthy → recoverable →
// lost → never started. Utilization keeps the same direction (fewest
// transacting days = worst) so both bars are read the same way.
const ESTATE_COLORS = {
    'Active': 'var(--success, #10b981)',
    'Idle 7–30d': 'var(--warning, #f59e0b)',
    'Dormant 30d+': 'var(--danger, #ef4444)',
    'Never': 'var(--ztx-never, #64748b)',
};
const UTIL_COLORS = {
    '0 days': 'var(--danger, #ef4444)',
    '1–5 days': 'var(--warning, #f59e0b)',
    '6–15 days': 'var(--chart-3)',
    '16–25 days': 'var(--chart-2)',
    '26–30 days': 'var(--success, #10b981)',
};

const isNever = (s) => s === 'Never Transacted';
const isIn30 = (s) => s === 'Inactive 30+';

const ZeroTransactionReport = () => {
    const { tenantVersion, currencySymbol, currencyCode, currencyDecimals } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const [rows, setRows] = useState([]);
    const [total, setTotal] = useState(0);
    const [summary, setSummary] = useState(null);
    const [estate, setEstate] = useState(null);
    const [loadingPage, setLoadingPage] = useState(false);
    const [loadingSummary, setLoadingSummary] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [rangeType, setRangeType] = useState('LAST_30');
    const [statusFilter, setStatusFilter] = useState('ALL');
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 50 });

    const [merchantName, setMerchantName] = useState('');
    const [aggregatorInput, setAggregatorInput] = useState('');
    const [midInput, setMidInput] = useState('');
    const [sidInput, setSidInput] = useState('');
    const [tidInput, setTidInput] = useState('');

    const buildPayload = useCallback(() => ({
        merchantName,
        partnerList: aggregatorInput ? aggregatorInput.split(',').map(s => s.trim()) : [],
        midList: midInput ? midInput.split(',').map(s => s.trim()) : [],
        sidList: sidInput ? sidInput.split(',').map(s => s.trim()) : [],
        tidList: tidInput ? tidInput.split(',').map(s => s.trim()) : [],
    }), [merchantName, aggregatorInput, midInput, sidInput, tidInput]);

    const fetchSummary = useCallback(async () => {
        setLoadingSummary(true);
        try {
            const res = await api.post(`/reports/zero-txn/summary?rangeType=${rangeType}`, buildPayload());
            setSummary(res.data);
        } catch (e) { console.error(e); setSummary(null); }
        finally { setLoadingSummary(false); }
    }, [rangeType, buildPayload]);

    // Estate health is range-independent (fixed 7d / 30d thresholds), so it is
    // fetched alongside the summary but not re-fetched when the range toggle
    // changes — only when the filters actually narrow the estate.
    const fetchEstate = useCallback(async () => {
        try {
            const res = await api.post('/reports/zero-txn/estate', buildPayload());
            setEstate(res.data);
        } catch (e) { console.error(e); setEstate(null); }
    }, [buildPayload]);

    const fetchPage = useCallback(async (page, size, status) => {
        setLoadingPage(true);
        try {
            const res = await api.post(
                `/reports/zero-txn/page?rangeType=${rangeType}&status=${status}&page=${page}&size=${size}`,
                buildPayload());
            const content = res.data?.content || [];
            setRows(content.map((r, i) => ({ id: `${page}-${i}-${r.mid}-${r.sid}-${r.terminalId}`, ...r })));
            setTotal(Number(res.data?.total || 0));
        } catch (e) { console.error(e); setRows([]); setTotal(0); }
        finally { setLoadingPage(false); }
    }, [rangeType, buildPayload]);

    // Full run: summary + first page. Triggered on mount, range change, and Run.
    const runReport = useCallback(() => {
        const p = { page: 0, pageSize: paginationModel.pageSize };
        setPaginationModel(p);
        fetchSummary();
        fetchEstate();
        fetchPage(0, p.pageSize, statusFilter);
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [fetchSummary, fetchEstate, fetchPage, statusFilter, paginationModel.pageSize]);

    useEffect(() => { runReport(); /* eslint-disable-next-line */ }, [rangeType, tenantVersion]);

    const onStatusChange = (key) => {
        setStatusFilter(key);
        const p = { page: 0, pageSize: paginationModel.pageSize };
        setPaginationModel(p);
        fetchPage(0, p.pageSize, key);
    };

    const onPaginationModelChange = (model) => {
        setPaginationModel(model);
        fetchPage(model.page, model.pageSize, statusFilter);
    };

    const filterByAggregator = (name) => {
        setAggregatorInput(name === '— Unassigned —' ? '' : name);
        setShowFilters(true);
        setTimeout(runReport, 0);
    };

    // Export: pull the FULL current view (paged 1000 at a time, hard cap 10k), then CSV.
    const handleExport = async () => {
        try {
            let all = [];
            for (let p = 0; p < 10; p++) {
                const res = await api.post(
                    `/reports/zero-txn/page?rangeType=${rangeType}&status=${statusFilter}&page=${p}&size=1000`,
                    buildPayload());
                const chunk = res.data?.content || [];
                all = all.concat(chunk);
                if (chunk.length < 1000 || all.length >= Number(res.data?.total || 0)) break;
            }
            exportToCSV(all, 'zero_transaction_report');
        } catch (e) { console.error(e); }
    };

    // Counts are now MERCHANT-grain (summary.total / never / in30 / in7), with
    // the terminal-grain figures exposed alongside as *Terminals. A churn report
    // headlines merchants: one merchant with 20 idle terminals is ONE dormant
    // merchant, not twenty. Terminals appear as the card subtitle.
    const n = (v) => Number(v || 0);
    const kpis = useMemo(() => {
        if (!summary) return [];
        const term = (t) => `${n(t).toLocaleString()} terminal${n(t) === 1 ? '' : 's'}`;
        return [
            { title: 'Inactive Merchants', value: n(summary.total).toLocaleString(), icon: Users, color: T.indigo,
              subtitle: `${term(summary.totalTerminals)}${summary.asOf ? ` · as of ${summary.asOf}` : ''}` },
            { title: 'Never Transacted', value: n(summary.never).toLocaleString(), icon: XCircle, color: STATUS.never.color,
              subtitle: term(summary.neverTerminals) },
            { title: 'Dormant 30+ Days', value: n(summary.in30).toLocaleString(), icon: TrendingDown, color: STATUS.in30.color,
              subtitle: term(summary.in30Terminals) },
            { title: 'Dormant 7–30 Days', value: n(summary.in7).toLocaleString(), icon: Clock, color: STATUS.in7.color,
              subtitle: term(summary.in7Terminals) },
        ];
    }, [summary]);

    // When the entire inactive set is "never transacted" (common for a fresh
    // tenant / test data), the 30+ and 7–30 tiles are structurally zero and the
    // analytics band is a single bar — it reads as broken. Detect that so the UI
    // can show a focused callout instead of three dead zero-panels.
    const allNever = summary && n(summary.total) > 0
        && n(summary.never) === n(summary.total)
        && n(summary.in30) === 0 && n(summary.in7) === 0;

    const statusBreakdown = useMemo(() => {
        if (!summary) return [];
        // Chips are now range-independent classifications (never / 30+ / 7–30),
        // so the share denominator is their own sum — not the range-scoped total.
        const parts = [
            { label: 'Inactive 30+', count: Number(summary.in30 || 0), color: STATUS.in30.color },
            { label: 'Never Transacted', count: Number(summary.never || 0), color: STATUS.never.color },
            { label: 'Inactive 7–30', count: Number(summary.in7 || 0), color: STATUS.in7.color },
        ];
        const t = parts.reduce((s, p) => s + p.count, 0) || 1;
        return parts.map(s => ({ ...s, pct: (s.count / t) * 100 }));
    }, [summary]);

    const dist = useMemo(() => (summary?.distribution || [])
        .filter(b => Number(b.count) > 0)
        .map(b => ({ label: b.label, count: Number(b.count), color: BUCKET_COLORS[b.label] || T.indigo })), [summary]);

    const topAggregators = useMemo(() => (summary?.topAggregators || [])
        .map(a => ({ name: a.name, count: Number(a.count), terminals: Number(a.terminals || 0) })), [summary]);
    const maxAgg = topAggregators[0]?.count || 1;

    // ─── Estate health view-model ────────────────────────────────
    // Composition is drawn as one 100%-width decay bar, so shares are taken
    // against the estate size rather than the dormant subset.
    const estateComposition = useMemo(() => {
        if (!estate) return [];
        const t = n(estate.totalTerminals) || 1;
        return (estate.composition || [])
            .map(c => ({ label: c.label, count: n(c.count), pct: (n(c.count) / t) * 100,
                         color: ESTATE_COLORS[c.label] || T.indigo }));
    }, [estate]);

    const estateUtil = useMemo(() => (estate?.utilization || [])
        .map(u => ({ label: u.label, count: n(u.count), color: UTIL_COLORS[u.label] || T.indigo })),
        [estate]);

    // Trend x-labels are dense at 30 points; show day-of-month only.
    const estateTrend = useMemo(() => (estate?.trend || [])
        .map(p => ({ ...p, day: String(p.date).slice(-2), terminals: n(p.terminals), txns: n(p.txns) })),
        [estate]);

    // Direction of travel: mean live terminals in the most recent 7 days vs the
    // 7 days before that. This is the signal a static dormancy count cannot give
    // — an estate can hold a flat dormant count while quietly bleeding terminals.
    const estateDrift = useMemo(() => {
        if (estateTrend.length < 8) return null;
        const tail = estateTrend.slice(-7);
        const prev = estateTrend.slice(-14, -7);
        if (!prev.length) return null;
        const avg = (a) => a.reduce((s, p) => s + p.terminals, 0) / a.length;
        const now = avg(tail), before = avg(prev);
        if (!before) return null;
        return { pct: ((now - before) / before) * 100, now, before };
    }, [estateTrend]);

    const estateKpis = useMemo(() => {
        if (!estate) return [];
        const totalT = n(estate.totalTerminals);
        const pct = (v) => (totalT ? ((n(v) / totalT) * 100).toFixed(1) : '0.0');
        return [
            { title: 'Estate Size', value: totalT.toLocaleString(), icon: MonitorSmartphone, color: T.indigo,
              subtitle: `${n(estate.totalMerchants).toLocaleString()} merchants${estate.asOf ? ` · as of ${estate.asOf}` : ''}` },
            { title: 'Estate Utilization', value: `${n(estate.utilizationPct).toFixed(1)}%`, icon: Activity,
              color: 'var(--success, #10b981)',
              subtitle: `${n(estate.activeTerminals).toLocaleString()} transacting in last 7d` },
            { title: 'Under-Used Terminals', value: n(estate.lowUseTerminals).toLocaleString(), icon: TrendingDown,
              color: 'var(--warning, #f59e0b)',
              subtitle: 'Active but ≤5 transacting days in 30' },
            { title: 'Volume at Risk', value: fmt.currency(estate.volumeAtRisk), icon: AlertTriangle,
              color: STATUS.in7.color,
              subtitle: `${n(estate.idleTerminals).toLocaleString()} idle terminals · ${pct(estate.idleTerminals)}% of estate` },
        ];
    }, [estate, fmt]);

    const getStatusChip = (status) => {
        if (isNever(status)) return <Chip label="Never Transacted" size="small" sx={{ fontWeight: 700, bgcolor: STATUS.never.bg, color: STATUS.never.fg, fontSize: '11px' }} />;
        if (isIn30(status)) return <Chip label="Inactive 30+" size="small" sx={{ fontWeight: 700, bgcolor: STATUS.in30.bg, color: STATUS.in30.fg, fontSize: '11px' }} />;
        return <Chip label="Inactive 7-30" size="small" sx={{ fontWeight: 700, bgcolor: STATUS.in7.bg, color: STATUS.in7.fg, fontSize: '11px' }} />;
    };

    // Rows are terminal-grain, so the same merchant/MID repeats across many rows.
    // To stop 20 terminals of one merchant reading like 20 merchants, MERCHANT +
    // MID are rendered muted/plain (context), while the TERMINAL (TID) — the thing
    // that's actually unique per row — carries the weight. Dropped the redundant
    // AGG CODE column (backend sets it equal to the aggregator name) and the
    // heavy chip-boxes on MID/TID in favour of clean monospace.
    const mono = { fontFamily: 'monospace', fontSize: '12px' };
    const columns = [
        { field: 'merchantName', headerName: 'MERCHANT', flex: 1.2, minWidth: 170, renderCell: (p) => (
            <Box sx={{ minWidth: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '100%' }}>
                <Typography variant="body2" fontWeight={600} color={T.text} noWrap>{p.value || p.row.entityName || '—'}</Typography>
                <Typography variant="caption" sx={{ ...mono, color: T.textMut }}>{p.row.mid}</Typography>
            </Box>
        ) },
        { field: 'aggregatorName', headerName: 'AGGREGATOR', flex: 1, minWidth: 140, renderCell: (p) => <Typography variant="body2" color={T.textSec}>{p.value || '—'}</Typography> },
        { field: 'storeName', headerName: 'STORE', flex: 1, minWidth: 130, renderCell: (p) => (
            <Box sx={{ minWidth: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '100%' }}>
                <Typography variant="body2" color={T.textSec} noWrap>{p.value || '—'}</Typography>
                <Typography variant="caption" sx={{ ...mono, color: T.textMut }}>{p.row.sid}</Typography>
            </Box>
        ) },
        { field: 'terminalId', headerName: 'TERMINAL (TID)', width: 140, renderCell: (p) => <Typography variant="body2" sx={{ ...mono, fontWeight: 700, color: T.strong }}>{p.value}</Typography> },
        { field: 'status', headerName: 'STATUS', width: 150, renderCell: (p) => getStatusChip(p.value) },
        { field: 'lastTransactionDate', headerName: 'LAST TXN', width: 120, align: 'right', headerAlign: 'right', renderCell: (p) => <Typography variant="body2" color={T.textMut} sx={{ fontVariantNumeric: 'tabular-nums' }}>{p.value || <em style={{ color: 'var(--text-muted, #cbd5e1)' }}>Never</em>}</Typography> },
        { field: 'daysInactive', headerName: 'INACTIVE DAYS', type: 'number', width: 120, align: 'right', headerAlign: 'right', renderCell: (p) => <Typography variant="body2" fontWeight={600} color={p.value > 30 ? 'var(--danger, #ef4444)' : T.textSec} sx={{ fontVariantNumeric: 'tabular-nums' }}>{p.value > -1 ? p.value : '—'}</Typography> },
        // [ESTATE HEALTH] Trailing-30d activity per terminal. For a dormant row
        // these are legitimately 0 — that IS the finding. For an "Inactive 7–30"
        // row they show how much life was left before it went quiet, which is
        // what decides whether the terminal is worth a save call or a pull.
        { field: 'activeDays30', headerName: 'ACTIVE DAYS (30)', width: 150, align: 'right', headerAlign: 'right', sortable: false, renderCell: (p) => {
            const d = Number(p.value || 0);
            const c = d === 0 ? 'var(--danger, #ef4444)' : d <= 5 ? 'var(--warning, #f59e0b)' : 'var(--success, #10b981)';
            return (
                <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '100%', gap: 0.5 }}>
                    <Typography variant="body2" fontWeight={600} color={T.textSec} sx={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{d} / 30</Typography>
                    <Box sx={{ height: 4, borderRadius: 999, bgcolor: T.borderLt, overflow: 'hidden' }}>
                        <Box sx={{ width: `${Math.min((d / 30) * 100, 100)}%`, height: '100%', bgcolor: c }} />
                    </Box>
                </Box>
            );
        } },
        { field: 'txns30', headerName: '30D TXNS', type: 'number', width: 110, align: 'right', headerAlign: 'right', renderCell: (p) => <Typography variant="body2" color={T.textSec} sx={{ fontVariantNumeric: 'tabular-nums' }}>{Number(p.value || 0).toLocaleString()}</Typography> },
        { field: 'volume30', headerName: `30D VOLUME${currencyCode ? ` (${currencyCode})` : ''}`, type: 'number', width: 150, align: 'right', headerAlign: 'right', renderCell: (p) => <Typography variant="body2" color={T.textSec} sx={{ fontVariantNumeric: 'tabular-nums' }}>{fmt.amount(p.value)}</Typography> },
    ];

    const filterInputSx = {
        '& .MuiOutlinedInput-root': { borderRadius: '8px', fontSize: '13px', bgcolor: T.subtle },
        '& .MuiInputLabel-root': { fontSize: '12px', fontWeight: 600 },
    };
    const panelSx = { p: 2.5, borderRadius: '14px', border: `1px solid ${T.border}`, bgcolor: T.card, height: '100%' };
    const panelTitle = (t) => (
        <Typography variant="caption" fontWeight={700} color={T.textMut} sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', mb: 1.5, display: 'block' }}>{t}</Typography>
    );

    const chips = summary ? [
        { key: 'ALL', label: 'All', count: Number(summary.total || 0), color: T.indigo },
        { key: 'IN30', label: 'Inactive 30+', count: Number(summary.in30 || 0), color: STATUS.in30.color },
        { key: 'NEVER', label: 'Never', count: Number(summary.never || 0), color: STATUS.never.color },
        { key: 'IN7', label: 'Inactive 7–30', count: Number(summary.in7 || 0), color: STATUS.in7.color },
    ] : [];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Zero Transaction Report" subtitle="Identify inactive merchants and potential churn risks"
                icon={AlertTriangle}
                onExport={handleExport}
                onRunReport={runReport}
                loading={loadingPage || loadingSummary}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                hideDatePresets
            >
                <Box sx={{ display: 'flex', alignItems: 'center', gap: '2px', bgcolor: T.borderLt, borderRadius: '10px', p: '3px' }}>
                    {RANGE_TYPES.map(r => (
                        <Box key={r.key} onClick={() => setRangeType(r.key)}
                            sx={{
                                px: 1.5, py: 0.6, borderRadius: '8px', fontSize: '12px', fontWeight: 600,
                                cursor: 'pointer', transition: 'background-color 0.15s ease, color 0.15s ease, box-shadow 0.15s ease', userSelect: 'none', whiteSpace: 'nowrap',
                                ...(rangeType === r.key
                                    ? { bgcolor: T.card, color: T.text, boxShadow: 'var(--shadow-xs, 0 1px 3px rgba(0,0,0,0.08))' }
                                    : { bgcolor: 'transparent', color: T.textMut, '&:hover': { color: T.strong, bgcolor: T.hover } }
                                ),
                            }}
                        >
                            {r.label}
                        </Box>
                    ))}
                </Box>
            </PremiumReportHeader>

            <Collapse in={showFilters} unmountOnExit>
                <Paper sx={{ p: 3, mb: 3, borderRadius: '14px', border: `1px solid ${T.border}`, bgcolor: T.card }}>
                    <Typography variant="caption" fontWeight={700} color={T.textMut} sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', mb: 2, display: 'block' }}>
                        Search Filters
                    </Typography>
                    <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
                        <TextField label="Entity / Merchant" size="small" value={merchantName} onChange={e => setMerchantName(e.target.value)} sx={{ minWidth: 180, ...filterInputSx }} />
                        <TextField label="Aggregator Name" size="small" value={aggregatorInput} onChange={e => setAggregatorInput(e.target.value)} sx={{ minWidth: 160, ...filterInputSx }} />
                        <TextField label="MID" size="small" value={midInput} onChange={e => setMidInput(e.target.value)} placeholder="Comma-separated" sx={{ minWidth: 140, ...filterInputSx }} />
                        <TextField label="SID" size="small" value={sidInput} onChange={e => setSidInput(e.target.value)} placeholder="Comma-separated" sx={{ minWidth: 140, ...filterInputSx }} />
                        <TextField label="Terminal ID" size="small" value={tidInput} onChange={e => setTidInput(e.target.value)} placeholder="Comma-separated" sx={{ minWidth: 140, ...filterInputSx }} />
                    </Stack>
                </Paper>
            </Collapse>

            <KpiCards cards={kpis} />

            {/* Focused callout when the whole inactive set is "never transacted"
                — the 30+/7–30 panels would all be zero, so show one clear line
                instead of three dead zero-panels + a single-segment bar. */}
            {allNever && (
                <Paper sx={{ ...panelSx, mt: 1, mb: 3, display: 'flex', alignItems: 'center', gap: 1.5 }}>
                    <XCircle size={18} color={STATUS.never.color} />
                    <Typography variant="body2" color={T.textSec}>
                        <Box component="span" sx={{ fontWeight: 700, color: T.text }}>
                            {n(summary.total).toLocaleString()} merchant{n(summary.total) === 1 ? '' : 's'}
                        </Box>
                        {' '}({n(summary.totalTerminals).toLocaleString()} terminal{n(summary.totalTerminals) === 1 ? '' : 's'}) onboarded but never transacted.
                        No dormant-but-previously-active merchants in this range.
                    </Typography>
                </Paper>
            )}

            {/* ═══ Terminal / POS estate health ═══
                The dormancy view above counts what stopped; this band supplies
                the denominator (how big is the estate) and the gradient between
                "working" and "dead" that a zero-transaction cut cannot see. */}
            {estate && n(estate.totalTerminals) > 0 && (
                <Box sx={{ mb: 3 }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5, mt: 1 }}>
                        <MonitorSmartphone size={15} color="var(--text-muted, #94a3b8)" />
                        <Typography variant="caption" fontWeight={700} color={T.textMut}
                            sx={{ textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                            Terminal / POS Estate Health
                        </Typography>
                        <Typography variant="caption" color={T.textMut}>
                            · fixed 7d / 30d thresholds, independent of the range above
                        </Typography>
                    </Box>

                    <KpiCards cards={estateKpis} />

                    <Box sx={{
                        display: 'grid', gap: 2, mt: 1,
                        gridTemplateColumns: { xs: '1fr', md: '1fr 1fr', lg: '1.1fr 1fr 1.3fr' },
                    }}>
                        <Paper sx={panelSx}>
                            {panelTitle('Estate Composition (terminals)')}
                            <Box sx={{ display: 'flex', height: 14, borderRadius: 999, overflow: 'hidden', mb: 2, bgcolor: T.borderLt }}>
                                {estateComposition.map(s => s.count > 0 && (
                                    <Box key={s.label} title={`${s.label}: ${s.count.toLocaleString()}`}
                                        sx={{ width: `${s.pct}%`, bgcolor: s.color, transition: 'width .5s ease' }} />
                                ))}
                            </Box>
                            <Stack spacing={1}>
                                {estateComposition.map(s => (
                                    <Box key={s.label} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                                            <Box sx={{ width: 10, height: 10, borderRadius: '3px', bgcolor: s.color }} />
                                            <Typography variant="body2" color={T.textSec}>{s.label}</Typography>
                                        </Box>
                                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                                            <Typography variant="body2" fontWeight={700} color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{s.count.toLocaleString()}</Typography>
                                            <Typography variant="caption" color={T.textMut} sx={{ width: 42, textAlign: 'right' }}>{s.pct.toFixed(1)}%</Typography>
                                        </Box>
                                    </Box>
                                ))}
                            </Stack>
                        </Paper>

                        <Paper sx={panelSx}>
                            {panelTitle('Utilization — transacting days in last 30')}
                            <Box sx={{ height: 160 }}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <BarChart data={estateUtil} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                        <CartesianGrid strokeDasharray="3 6" stroke={T.grid} vertical={false} />
                                        <XAxis dataKey="label" axisLine={false} tickLine={false} tick={{ fontSize: 10, fill: T.axis }} />
                                        <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: T.axis }} allowDecimals={false} width={36} />
                                        <ReTooltip cursor={{ fill: 'var(--bg-hover, #f8fafc)' }} contentStyle={chartTooltipStyle} formatter={(v) => [v, 'Terminals']} />
                                        <Bar dataKey="count" radius={[5, 5, 0, 0]}>
                                            {estateUtil.map((d, i) => <Cell key={i} fill={d.color} />)}
                                        </Bar>
                                    </BarChart>
                                </ResponsiveContainer>
                            </Box>
                        </Paper>

                        <Paper sx={panelSx}>
                            <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', mb: 1.5 }}>
                                <Typography variant="caption" fontWeight={700} color={T.textMut} sx={{ textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                                    Live Terminals per Day
                                </Typography>
                                {estateDrift && (
                                    <Typography variant="caption" fontWeight={700}
                                        sx={{ color: estateDrift.pct < 0 ? 'var(--danger, #ef4444)' : 'var(--success, #10b981)' }}>
                                        {estateDrift.pct >= 0 ? '▲' : '▼'} {Math.abs(estateDrift.pct).toFixed(1)}% vs prior 7d
                                    </Typography>
                                )}
                            </Box>
                            <Box sx={{ height: 160 }}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <AreaChart data={estateTrend} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                        <defs>
                                            <linearGradient id="ztxEstateFill" x1="0" y1="0" x2="0" y2="1">
                                                <stop offset="0%" stopColor="var(--chart-1)" stopOpacity={0.35} />
                                                <stop offset="100%" stopColor="var(--chart-1)" stopOpacity={0.02} />
                                            </linearGradient>
                                        </defs>
                                        <CartesianGrid strokeDasharray="3 6" stroke={T.grid} vertical={false} />
                                        <XAxis dataKey="day" axisLine={false} tickLine={false} tick={{ fontSize: 10, fill: T.axis }} interval={4} />
                                        <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: T.axis }} allowDecimals={false} width={36} />
                                        <ReTooltip contentStyle={chartTooltipStyle}
                                            labelFormatter={(_, p) => p?.[0]?.payload?.date || ''}
                                            formatter={(v) => [Number(v).toLocaleString(), 'Live terminals']} />
                                        <Area type="monotone" dataKey="terminals" stroke="var(--chart-1)" strokeWidth={2} fill="url(#ztxEstateFill)" />
                                    </AreaChart>
                                </ResponsiveContainer>
                            </Box>
                        </Paper>
                    </Box>
                </Box>
            )}

            {/* ═══ Churn analytics band (accurate, full-set counts) ═══ */}
            {summary && Number(summary.total) > 0 && !allNever && (
                <Box sx={{
                    display: 'grid', gap: 2, mb: 3, mt: 1,
                    gridTemplateColumns: { xs: '1fr', md: '1.1fr 1fr', lg: '1.2fr 1fr 1fr' },
                }}>
                    <Paper sx={panelSx}>
                        {panelTitle('Status Breakdown')}
                        <Box sx={{ display: 'flex', height: 14, borderRadius: 999, overflow: 'hidden', mb: 2, bgcolor: T.borderLt }}>
                            {statusBreakdown.map(s => s.count > 0 && (
                                <Box key={s.label} title={`${s.label}: ${s.count}`} sx={{ width: `${s.pct}%`, bgcolor: s.color, transition: 'width .5s ease' }} />
                            ))}
                        </Box>
                        <Stack spacing={1}>
                            {statusBreakdown.map(s => (
                                <Box key={s.label} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                                        <Box sx={{ width: 10, height: 10, borderRadius: '3px', bgcolor: s.color }} />
                                        <Typography variant="body2" color={T.textSec}>{s.label}</Typography>
                                    </Box>
                                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                                        <Typography variant="body2" fontWeight={700} color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{s.count.toLocaleString()}</Typography>
                                        <Typography variant="caption" color={T.textMut} sx={{ width: 42, textAlign: 'right' }}>{s.pct.toFixed(1)}%</Typography>
                                    </Box>
                                </Box>
                            ))}
                        </Stack>
                    </Paper>

                    <Paper sx={panelSx}>
                        {panelTitle('Dormancy Distribution (terminals)')}
                        <Box sx={{ height: 160 }}>
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={dist} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                    <CartesianGrid strokeDasharray="3 6" stroke={T.grid} vertical={false} />
                                    <XAxis dataKey="label" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: T.axis }} />
                                    <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: T.axis }} allowDecimals={false} width={36} />
                                    <ReTooltip cursor={{ fill: 'var(--bg-hover, #f8fafc)' }} contentStyle={chartTooltipStyle} formatter={(v) => [v, 'Terminals']} />
                                    <Bar dataKey="count" radius={[5, 5, 0, 0]}>
                                        {dist.map((d, i) => <Cell key={i} fill={d.color} />)}
                                    </Bar>
                                </BarChart>
                            </ResponsiveContainer>
                        </Box>
                    </Paper>

                    <Paper sx={panelSx}>
                        {panelTitle('Top Aggregators by Dormancy')}
                        <Stack spacing={1.25}>
                            {topAggregators.map(a => (
                                <Box key={a.name} onClick={() => filterByAggregator(a.name)}
                                    sx={{ cursor: 'pointer', '&:hover .agg-name': { color: T.indigo } }}>
                                    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 0.5 }}>
                                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
                                            <Building2 size={13} color="var(--text-muted, #94a3b8)" />
                                            <Typography className="agg-name" variant="body2" color={T.textSec} noWrap sx={{ maxWidth: 160, transition: 'color .15s' }}>{a.name}</Typography>
                                        </Box>
                                        <Stack direction="row" spacing={0.75} alignItems="baseline">
                                            <Typography variant="body2" fontWeight={700} color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{a.count}</Typography>
                                            <Typography variant="caption" color={T.textMut} sx={{ fontVariantNumeric: 'tabular-nums' }}>· {a.terminals} term</Typography>
                                        </Stack>
                                    </Box>
                                    <Box sx={{ height: 6, borderRadius: 999, bgcolor: T.borderLt, overflow: 'hidden' }}>
                                        <Box sx={{ width: `${Math.max((a.count / maxAgg) * 100, 3)}%`, height: '100%', borderRadius: 999, background: 'var(--ztx-agg-bar, linear-gradient(90deg,var(--projected),var(--projected)))' }} />
                                    </Box>
                                </Box>
                            ))}
                        </Stack>
                    </Paper>
                </Box>
            )}

            {/* Status quick-filter chips (server-side) */}
            <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1, mb: 1.5 }}>
                {chips.map(c => {
                    const active = statusFilter === c.key;
                    return (
                        <Chip key={c.key} label={`${c.label} · ${c.count.toLocaleString()}`} size="small"
                            onClick={() => onStatusChange(c.key)}
                            sx={{
                                fontWeight: 700, fontSize: '11px', cursor: 'pointer', borderRadius: '8px',
                                border: `1px solid ${active ? c.color : T.border}`,
                                bgcolor: active ? `color-mix(in srgb, ${c.color} 12%, transparent)` : T.card,
                                color: active ? c.color : T.textMut,
                            }} />
                    );
                })}
            </Box>

            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={rows} columns={columns} loading={loadingPage} rowHeight={55}
                    disableRowSelectionOnClick
                    paginationMode="server"
                    rowCount={total}
                    paginationModel={paginationModel}
                    onPaginationModelChange={onPaginationModelChange}
                    pageSizeOptions={[25, 50, 100]}
                    slots={{ toolbar: GridToolbar }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default ZeroTransactionReport;
