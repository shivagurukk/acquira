import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Chip, Stack, TextField, Collapse } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip as ReTooltip, ResponsiveContainer, Cell
} from 'recharts';
import { AlertTriangle, Clock, Users, TrendingDown, XCircle, Building2 } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';
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

const isNever = (s) => s === 'Never Transacted';
const isIn30 = (s) => s === 'Inactive 30+';

const ZeroTransactionReport = () => {
    const { tenantVersion } = useAuth();
    const [rows, setRows] = useState([]);
    const [total, setTotal] = useState(0);
    const [summary, setSummary] = useState(null);
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
        fetchPage(0, p.pageSize, statusFilter);
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [fetchSummary, fetchPage, statusFilter, paginationModel.pageSize]);

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
