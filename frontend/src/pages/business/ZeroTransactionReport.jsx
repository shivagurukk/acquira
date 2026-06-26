import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Chip, Stack, TextField, Collapse } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip as ReTooltip, ResponsiveContainer, Cell
} from 'recharts';
import { AlertTriangle, Clock, Users, TrendingDown, XCircle, Building2 } from 'lucide-react';
import api from '../../api/axios';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const RANGE_TYPES = [
    { key: 'LAST_7', label: 'Last 7 Days' },
    { key: 'LAST_30', label: 'Last 30 Days' },
    { key: 'NEVER', label: 'Since Onboarding' },
];

const STATUS = {
    never: { color: '#64748b', bg: '#f1f5f9', fg: '#475569' },
    in30:  { color: '#ef4444', bg: '#fee2e2', fg: '#991b1b' },
    in7:   { color: '#f59e0b', bg: '#fef3c7', fg: '#92400e' },
};
const BUCKET_COLORS = {
    '≤14d': '#3b82f6', '15–30d': '#f59e0b', '31–60d': '#fb923c',
    '61–90d': '#ef4444', '90d+': '#b91c1c', 'Never': '#64748b',
};

const isNever = (s) => s === 'Never Transacted';
const isIn30 = (s) => s === 'Inactive 30+';

const ZeroTransactionReport = () => {
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

    useEffect(() => { runReport(); /* eslint-disable-next-line */ }, [rangeType]);

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

    // Export: pull up to 1000 matching rows for the current view, then CSV.
    const handleExport = async () => {
        try {
            const res = await api.post(
                `/reports/zero-txn/page?rangeType=${rangeType}&status=${statusFilter}&page=0&size=1000`,
                buildPayload());
            exportToCSV(res.data?.content || [], 'zero_transaction_report');
        } catch (e) { console.error(e); }
    };

    const kpis = useMemo(() => {
        if (!summary) return [];
        return [
            { title: 'Total Inactive', value: Number(summary.total || 0).toLocaleString(), icon: Users, color: '#6366f1' },
            { title: 'Never Transacted', value: Number(summary.never || 0).toLocaleString(), icon: XCircle, color: STATUS.never.color },
            { title: 'Inactive 30+ Days', value: Number(summary.in30 || 0).toLocaleString(), icon: TrendingDown, color: STATUS.in30.color },
            { title: 'Inactive 7–30 Days', value: Number(summary.in7 || 0).toLocaleString(), icon: Clock, color: STATUS.in7.color },
        ];
    }, [summary]);

    const statusBreakdown = useMemo(() => {
        if (!summary) return [];
        const t = Number(summary.total) || 1;
        return [
            { label: 'Inactive 30+', count: Number(summary.in30 || 0), color: STATUS.in30.color },
            { label: 'Never Transacted', count: Number(summary.never || 0), color: STATUS.never.color },
            { label: 'Inactive 7–30', count: Number(summary.in7 || 0), color: STATUS.in7.color },
        ].map(s => ({ ...s, pct: (s.count / t) * 100 }));
    }, [summary]);

    const dist = useMemo(() => (summary?.distribution || [])
        .filter(b => Number(b.count) > 0)
        .map(b => ({ label: b.label, count: Number(b.count), color: BUCKET_COLORS[b.label] || '#6366f1' })), [summary]);

    const topAggregators = useMemo(() => (summary?.topAggregators || [])
        .map(a => ({ name: a.name, count: Number(a.count) })), [summary]);
    const maxAgg = topAggregators[0]?.count || 1;

    const getStatusChip = (status) => {
        if (isNever(status)) return <Chip label="Never Transacted" size="small" sx={{ fontWeight: 700, bgcolor: STATUS.never.bg, color: STATUS.never.fg, fontSize: '11px' }} />;
        if (isIn30(status)) return <Chip label="Inactive 30+" size="small" sx={{ fontWeight: 700, bgcolor: STATUS.in30.bg, color: STATUS.in30.fg, fontSize: '11px' }} />;
        return <Chip label="Inactive 7-30" size="small" sx={{ fontWeight: 700, bgcolor: STATUS.in7.bg, color: STATUS.in7.fg, fontSize: '11px' }} />;
    };

    const columns = [
        { field: 'entityName', headerName: 'ENTITY NAME', flex: 1, minWidth: 160, renderCell: (p) => <Typography variant="body2" fontWeight={700} color="#1e293b">{p.value || '—'}</Typography> },
        { field: 'aggregatorName', headerName: 'AGGREGATOR', flex: 1, minWidth: 140, renderCell: (p) => <Typography variant="body2" color="#475569">{p.value || '—'}</Typography> },
        { field: 'aggregatorCode', headerName: 'AGG CODE', width: 100, renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', color: '#64748b' }}>{p.value || '—'}</Typography> },
        { field: 'mid', headerName: 'MID', width: 130, renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', color: '#475569', bgcolor: '#f1f5f9', px: 1, py: 0.3, borderRadius: '4px', border: '1px solid #e2e8f0' }}>{p.value}</Typography> },
        { field: 'merchantName', headerName: 'MERCHANT NAME', flex: 1.2, minWidth: 160, renderCell: (p) => <Typography variant="body2" fontWeight={600} color="#334155">{p.value}</Typography> },
        { field: 'sid', headerName: 'SID', width: 100, renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', color: '#64748b' }}>{p.value}</Typography> },
        { field: 'storeName', headerName: 'STORE', flex: 1, minWidth: 130, renderCell: (p) => <Typography variant="body2" color="#475569">{p.value || '—'}</Typography> },
        { field: 'terminalId', headerName: 'TID', width: 110, renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', fontWeight: 700, color: '#334155', bgcolor: '#f1f5f9', px: 1, py: 0.3, borderRadius: '4px' }}>{p.value}</Typography> },
        { field: 'status', headerName: 'STATUS', width: 150, renderCell: (p) => getStatusChip(p.value) },
        { field: 'lastTransactionDate', headerName: 'LAST TXN', width: 120, align: 'right', headerAlign: 'right', renderCell: (p) => <Typography variant="body2" color="#64748b" sx={{ fontVariantNumeric: 'tabular-nums' }}>{p.value || <em style={{ color: '#cbd5e1' }}>Never</em>}</Typography> },
        { field: 'daysInactive', headerName: 'INACTIVE DAYS', type: 'number', width: 120, align: 'right', headerAlign: 'right', renderCell: (p) => <Typography variant="body2" fontWeight={600} color={p.value > 30 ? '#ef4444' : '#475569'} sx={{ fontVariantNumeric: 'tabular-nums' }}>{p.value > -1 ? p.value : '—'}</Typography> },
    ];

    const filterInputSx = {
        '& .MuiOutlinedInput-root': { borderRadius: '8px', fontSize: '13px', bgcolor: '#f8fafc' },
        '& .MuiInputLabel-root': { fontSize: '12px', fontWeight: 600 },
    };
    const panelSx = { p: 2.5, borderRadius: '14px', border: '1px solid #e2e8f0', bgcolor: '#fff', height: '100%' };
    const panelTitle = (t) => (
        <Typography variant="caption" fontWeight={700} color="#94a3b8" sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', mb: 1.5, display: 'block' }}>{t}</Typography>
    );

    const chips = summary ? [
        { key: 'ALL', label: 'All', count: Number(summary.total || 0), color: '#6366f1' },
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
                <Box sx={{ display: 'flex', alignItems: 'center', gap: '2px', bgcolor: '#f1f5f9', borderRadius: '10px', p: '3px' }}>
                    {RANGE_TYPES.map(r => (
                        <Box key={r.key} onClick={() => setRangeType(r.key)}
                            sx={{
                                px: 1.5, py: 0.6, borderRadius: '8px', fontSize: '12px', fontWeight: 600,
                                cursor: 'pointer', transition: 'all 0.15s ease', userSelect: 'none', whiteSpace: 'nowrap',
                                ...(rangeType === r.key
                                    ? { bgcolor: 'white', color: '#0f172a', boxShadow: '0 1px 3px rgba(0,0,0,0.08)' }
                                    : { bgcolor: 'transparent', color: '#64748b', '&:hover': { color: '#334155', bgcolor: 'rgba(255,255,255,0.5)' } }
                                ),
                            }}
                        >
                            {r.label}
                        </Box>
                    ))}
                </Box>
            </PremiumReportHeader>

            <Collapse in={showFilters} unmountOnExit>
                <Paper sx={{ p: 3, mb: 3, borderRadius: '14px', border: '1px solid #e2e8f0' }}>
                    <Typography variant="caption" fontWeight={700} color="#94a3b8" sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', mb: 2, display: 'block' }}>
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

            {/* ═══ Churn analytics band (accurate, full-set counts) ═══ */}
            {summary && Number(summary.total) > 0 && (
                <Box sx={{
                    display: 'grid', gap: 2, mb: 3, mt: 1,
                    gridTemplateColumns: { xs: '1fr', md: '1.1fr 1fr', lg: '1.2fr 1fr 1fr' },
                }}>
                    <Paper sx={panelSx}>
                        {panelTitle('Status Breakdown')}
                        <Box sx={{ display: 'flex', height: 14, borderRadius: 999, overflow: 'hidden', mb: 2, bgcolor: '#f1f5f9' }}>
                            {statusBreakdown.map(s => s.count > 0 && (
                                <Box key={s.label} title={`${s.label}: ${s.count}`} sx={{ width: `${s.pct}%`, bgcolor: s.color, transition: 'width .5s ease' }} />
                            ))}
                        </Box>
                        <Stack spacing={1}>
                            {statusBreakdown.map(s => (
                                <Box key={s.label} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                                        <Box sx={{ width: 10, height: 10, borderRadius: '3px', bgcolor: s.color }} />
                                        <Typography variant="body2" color="#475569">{s.label}</Typography>
                                    </Box>
                                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                                        <Typography variant="body2" fontWeight={700} color="#1e293b" sx={{ fontVariantNumeric: 'tabular-nums' }}>{s.count.toLocaleString()}</Typography>
                                        <Typography variant="caption" color="#94a3b8" sx={{ width: 42, textAlign: 'right' }}>{s.pct.toFixed(1)}%</Typography>
                                    </Box>
                                </Box>
                            ))}
                        </Stack>
                    </Paper>

                    <Paper sx={panelSx}>
                        {panelTitle('Days Inactive Distribution')}
                        <Box sx={{ height: 160 }}>
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={dist} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                    <CartesianGrid strokeDasharray="3 6" stroke="#eef2f7" vertical={false} />
                                    <XAxis dataKey="label" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: '#94a3b8' }} />
                                    <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: '#94a3b8' }} allowDecimals={false} width={36} />
                                    <ReTooltip cursor={{ fill: '#f8fafc' }} contentStyle={{ borderRadius: 10, border: '1px solid #e2e8f0', fontSize: 12 }} formatter={(v) => [v, 'Terminals']} />
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
                                    sx={{ cursor: 'pointer', '&:hover .agg-name': { color: '#6366f1' } }}>
                                    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 0.5 }}>
                                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
                                            <Building2 size={13} color="#94a3b8" />
                                            <Typography className="agg-name" variant="body2" color="#475569" noWrap sx={{ maxWidth: 160, transition: 'color .15s' }}>{a.name}</Typography>
                                        </Box>
                                        <Typography variant="body2" fontWeight={700} color="#1e293b" sx={{ fontVariantNumeric: 'tabular-nums' }}>{a.count}</Typography>
                                    </Box>
                                    <Box sx={{ height: 6, borderRadius: 999, bgcolor: '#f1f5f9', overflow: 'hidden' }}>
                                        <Box sx={{ width: `${Math.max((a.count / maxAgg) * 100, 3)}%`, height: '100%', borderRadius: 999, background: 'linear-gradient(90deg,#6366f1,#818cf8)' }} />
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
                                border: `1px solid ${active ? c.color : '#e2e8f0'}`,
                                bgcolor: active ? `${c.color}1a` : '#fff',
                                color: active ? c.color : '#64748b',
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
