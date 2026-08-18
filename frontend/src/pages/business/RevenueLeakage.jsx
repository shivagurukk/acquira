import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Chip, Stack, Button, Tooltip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { ShieldAlert, TrendingDown, DollarSign, AlertTriangle, Users, Check, X, RotateCcw, Download } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { formatCompactCurrency } from '../../utils/formatters';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

// ── Display config ────────────────────────────────────────────
const TYPE_META = {
    VOLUME_DROP:          { label: 'Volume Drop',  bg: '#fff7ed', color: '#9a3412' },
    MSF_RATE_DROP:        { label: 'MSF Rate Drop', bg: '#f5f3ff', color: '#5b21b6' },
    ZERO_MSF:             { label: 'Zero MSF',      bg: '#fef2f2', color: '#991b1b' },
    DORMANT_REVENUE_LOSS: { label: 'Dormant',       bg: '#f1f5f9', color: '#334155' },
};
const SEV_META = {
    CRITICAL: { bg: '#fee2e2', color: '#991b1b' },
    HIGH:     { bg: '#ffedd5', color: '#9a3412' },
    MEDIUM:   { bg: '#fef3c7', color: '#92400e' },
    LOW:      { bg: '#f1f5f9', color: '#475569' },
};
const STATUS_TABS = ['OPEN', 'RESOLVED', 'ALL'];

// Money — carries the tenant currency and precision (was a bare number).
const fmtMoney = (v) => formatCompactCurrency(v);

const RevenueLeakage = () => {
    const { tenantVersion } = useAuth();
    const [rows, setRows] = useState([]);
    const [summary, setSummary] = useState(null);
    const [status, setStatus] = useState('OPEN');
    const [loading, setLoading] = useState(true);
    const [errorMsg, setErrorMsg] = useState(null);

    const fetchAll = useCallback(async () => {
        setLoading(true);
        setErrorMsg(null);
        try {
            const [flagsRes, sumRes] = await Promise.all([
                api.get(`/leakage/flags?status=${status}&limit=1000`),
                api.get('/leakage/summary'),
            ]);
            setRows(flagsRes.data.map((r) => ({ id: r.id, ...r })));
            setSummary(sumRes.data);
        } catch (e) {
            setErrorMsg(`Failed to load revenue-leakage data: ${e.message}`);
            setRows([]);
        } finally {
            setLoading(false);
        }
    }, [status]);

    useEffect(() => { fetchAll(); }, [fetchAll, tenantVersion]);

    // "Run report" runs detection on the server, then reloads.
    const runDetection = async () => {
        setLoading(true);
        try {
            await api.post('/leakage/run');
        } catch (e) {
            console.error('Detection run failed', e);
        }
        await fetchAll();
    };

    const act = async (id, action) => {
        try {
            await api.post(`/leakage/flags/${id}/${action}`);
            // Optimistic: drop the row if the current tab no longer matches it.
            setRows((prev) => prev.filter((r) => r.id !== id || status === 'ALL'));
            fetchAll();
        } catch (e) { console.error(`Action ${action} failed`, e); }
    };

    const kpis = useMemo(() => {
        const s = summary || {};
        return [
            { title: 'Open Flags', value: Number(s.openCount || 0).toLocaleString(), icon: ShieldAlert, color: '#ef4444' },
            { title: 'Est. Monthly at Risk', value: fmtMoney(s.totalEstImpact), icon: DollarSign, color: '#f59e0b' },
            { title: 'Critical + High', value: Number(s.highCount || 0).toLocaleString(), icon: AlertTriangle, color: '#dc2626' },
            { title: 'Merchants Affected', value: Number(s.merchantsAffected || 0).toLocaleString(), icon: Users, color: 'var(--projected)' },
        ];
    }, [summary]);

    // Curated CSV export — friendly headers, selected columns, zero dependency.
    const handleExportCsv = () => {
        const mapped = rows.map((r) => ({
            'Merchant': r.merchantName,
            'Merchant ID': r.merchantId,
            'Type': r.checkType,
            'Severity': r.severity,
            'Detail': r.details,
            'Change %': r.deltaPct,
            'Est. Monthly Impact': r.estMonthlyImpact,
            'As Of': r.businessDate ? String(r.businessDate).slice(0, 10) : '',
            'Status': r.status,
        }));
        exportToCSV(mapped, 'revenue_leakage');
    };

    const columns = [
        {
            field: 'merchantName', headerName: 'MERCHANT', flex: 1.4, minWidth: 200,
            renderCell: (p) => (
                <Box sx={{ py: 0.5 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600, color: '#0f172a', lineHeight: 1.2 }}>
                        {p.value || '—'}
                    </Typography>
                    <Typography variant="caption" sx={{ fontFamily: '"Roboto Mono", monospace', color: '#94a3b8' }}>
                        #{p.row.merchantId}
                    </Typography>
                </Box>
            ),
        },
        {
            field: 'checkType', headerName: 'TYPE', width: 150,
            renderCell: (p) => {
                const m = TYPE_META[p.value] || { label: p.value, bg: '#f1f5f9', color: '#475569' };
                return <Chip label={m.label} size="small" sx={{ fontWeight: 700, fontSize: 11, bgcolor: m.bg, color: m.color }} />;
            },
        },
        {
            field: 'severity', headerName: 'SEVERITY', width: 120, align: 'center', headerAlign: 'center',
            renderCell: (p) => {
                const m = SEV_META[p.value] || SEV_META.LOW;
                return <Chip label={p.value} size="small" sx={{ fontWeight: 700, fontSize: 11, bgcolor: m.bg, color: m.color }} />;
            },
        },
        {
            field: 'details', headerName: 'DETAIL', flex: 2, minWidth: 280,
            renderCell: (p) => (
                <Typography variant="body2" sx={{ color: '#475569', fontSize: 13, whiteSpace: 'normal', lineHeight: 1.4, py: 0.5 }}>
                    {p.value}
                </Typography>
            ),
        },
        {
            field: 'deltaPct', headerName: 'CHANGE', width: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => {
                const v = Number(p.value || 0);
                return (
                    <Stack direction="row" spacing={0.3} alignItems="center" justifyContent="flex-end" sx={{ width: '100%' }}>
                        <TrendingDown size={13} color="#dc2626" />
                        <Typography variant="body2" sx={{ fontWeight: 700, color: '#dc2626', fontVariantNumeric: 'tabular-nums' }}>
                            {v.toFixed(0)}%
                        </Typography>
                    </Stack>
                );
            },
        },
        {
            field: 'estMonthlyImpact', headerName: 'EST. / MO', width: 120, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (p) => (
                <Typography variant="body2" sx={{ fontWeight: 700, color: '#b45309', fontVariantNumeric: 'tabular-nums' }}>
                    {fmtMoney(p.value)}
                </Typography>
            ),
        },
        {
            field: 'businessDate', headerName: 'AS OF', width: 110,
            valueFormatter: (v) => (v ? String(v).slice(0, 10) : '—'),
        },
        {
            field: 'actions', headerName: '', width: 110, sortable: false, filterable: false,
            renderCell: (p) => (
                <Stack direction="row" spacing={0.5}>
                    {p.row.status === 'OPEN' ? (
                        <>
                            <Tooltip title="Resolve">
                                <Button size="small" variant="outlined" color="success" sx={{ minWidth: 32, px: 0 }}
                                    onClick={() => act(p.row.id, 'resolve')}>
                                    <Check size={15} />
                                </Button>
                            </Tooltip>
                            <Tooltip title="Ignore">
                                <Button size="small" variant="outlined" color="inherit" sx={{ minWidth: 32, px: 0 }}
                                    onClick={() => act(p.row.id, 'ignore')}>
                                    <X size={15} />
                                </Button>
                            </Tooltip>
                        </>
                    ) : (
                        <Tooltip title="Reopen">
                            <Button size="small" variant="outlined" sx={{ minWidth: 32, px: 0 }}
                                onClick={() => act(p.row.id, 'reopen')}>
                                <RotateCcw size={15} />
                            </Button>
                        </Tooltip>
                    )}
                </Stack>
            ),
        },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Revenue Leakage" subtitle="Volume drops, pricing erosion and dormant-merchant revenue at risk"
                icon={ShieldAlert}
                onExport={handleExportCsv}
                onRunReport={runDetection}
                loading={loading}
                hideDatePresets
            />
            <KpiCards cards={kpis} />

            {/* Toolbar: status tabs + CSV export */}
            <Paper elevation={0} sx={{ p: 1, mb: 2, borderRadius: 2, border: '1px solid #e5e7eb', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
                <Stack direction="row" spacing={0.5} sx={{ bgcolor: '#f3f4f6', borderRadius: 2, p: 0.5 }}>
                    {STATUS_TABS.map((s) => (
                        <Box key={s} onClick={() => setStatus(s)} sx={{
                            px: 1.5, py: 0.5, borderRadius: 1.5, cursor: 'pointer', fontSize: 12, fontWeight: 600,
                            bgcolor: status === s ? '#fff' : 'transparent',
                            color: status === s ? 'var(--primary)' : '#6b7280',
                            boxShadow: status === s ? '0 1px 3px rgba(0,0,0,.1)' : 'none',
                        }}>
                            {s === 'ALL' ? 'All' : s.charAt(0) + s.slice(1).toLowerCase()}
                        </Box>
                    ))}
                </Stack>
                <Button size="small" variant="outlined" startIcon={<Download size={16} />}
                    onClick={handleExportCsv} disabled={!rows.length}>
                    Export CSV
                </Button>
            </Paper>

            {errorMsg && (
                <Paper elevation={0} sx={{ p: 2, mb: 2, borderRadius: 2, bgcolor: '#fef2f2', border: '1px solid #fecaca' }}>
                    <Typography variant="body2" fontWeight="600" color="#991b1b">Could not load revenue-leakage flags</Typography>
                    <Typography variant="caption" color="#7f1d1d">{errorMsg}</Typography>
                </Paper>
            )}

            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={rows} columns={columns} loading={loading} rowHeight={64}
                    getRowHeight={() => 'auto'}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    initialState={{
                        pagination: { paginationModel: { pageSize: 25 } },
                        sorting: { sortModel: [{ field: 'estMonthlyImpact', sort: 'desc' }] },
                    }}
                    pageSizeOptions={[25, 50, 100]}
                    sx={{ ...premiumDataGridStyles, '& .MuiDataGrid-cell': { alignItems: 'flex-start', py: 1 } }}
                />
            </Paper>
        </Box>
    );
};

export default RevenueLeakage;
