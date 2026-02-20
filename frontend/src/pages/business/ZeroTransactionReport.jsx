import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, Chip, Stack, TextField, Collapse } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { AlertTriangle, Clock, Users, TrendingDown, XCircle } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const RANGE_TYPES = [
    { key: 'LAST_7', label: 'Last 7 Days' },
    { key: 'LAST_30', label: 'Last 30 Days' },
    { key: 'NEVER', label: 'Since Onboarding' },
];

const ZeroTransactionReport = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [rangeType, setRangeType] = useState('LAST_30');

    // Text inputs for comma-separated filters
    const [merchantName, setMerchantName] = useState('');
    const [aggregatorInput, setAggregatorInput] = useState('');
    const [midInput, setMidInput] = useState('');
    const [sidInput, setSidInput] = useState('');
    const [tidInput, setTidInput] = useState('');

    useEffect(() => { fetchData(); }, [rangeType]);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const payload = {
                merchantName,
                partnerList: aggregatorInput ? aggregatorInput.split(',').map(s => s.trim()) : [],
                midList: midInput ? midInput.split(',').map(s => s.trim()) : [],
                sidList: sidInput ? sidInput.split(',').map(s => s.trim()) : [],
                tidList: tidInput ? tidInput.split(',').map(s => s.trim()) : [],
            };
            const res = await fetch(`/api/reports/zero-txn/list?rangeType=${rangeType}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`,
                    ...(tenantId ? { 'X-Tenant-Id': tenantId } : {})
                },
                body: JSON.stringify(payload)
            });
            if (res.ok) {
                const result = await res.json();
                setData(result.map((r, i) => ({ id: `${r.mid}-${r.sid}-${r.terminalId}-${i}`, ...r })));
            }
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const neverTransacted = data.filter(d => d.status === 'Never Transacted').length;
        const inactive30 = data.filter(d => d.status === 'Inactive 30+').length;
        const inactive7 = data.filter(d => d.status !== 'Never Transacted' && d.status !== 'Inactive 30+').length;
        return [
            { title: 'Total Inactive', value: data.length.toLocaleString(), icon: Users, color: '#6366f1' },
            { title: 'Never Transacted', value: neverTransacted.toLocaleString(), icon: XCircle, color: '#ef4444' },
            { title: 'Inactive 30+ Days', value: inactive30.toLocaleString(), icon: TrendingDown, color: '#f59e0b' },
            { title: 'Inactive 7–30 Days', value: inactive7.toLocaleString(), icon: Clock, color: '#3b82f6' },
        ];
    }, [data]);

    const getStatusChip = (status) => {
        if (status === 'Never Transacted') return <Chip label="Never Transacted" size="small" sx={{ fontWeight: 700, bgcolor: '#f1f5f9', color: '#475569', fontSize: '11px' }} />;
        if (status === 'Inactive 30+') return <Chip label="Inactive 30+" size="small" sx={{ fontWeight: 700, bgcolor: '#fee2e2', color: '#991b1b', fontSize: '11px' }} />;
        return <Chip label="Inactive 7-30" size="small" sx={{ fontWeight: 700, bgcolor: '#fef3c7', color: '#92400e', fontSize: '11px' }} />;
    };

    const columns = [
        {
            field: 'entityName', headerName: 'ENTITY NAME', flex: 1, minWidth: 160,
            renderCell: (p) => <Typography variant="body2" fontWeight={700} color="#1e293b">{p.value || '—'}</Typography>
        },
        {
            field: 'aggregatorName', headerName: 'AGGREGATOR', flex: 1, minWidth: 140,
            renderCell: (p) => <Typography variant="body2" color="#475569">{p.value || '—'}</Typography>
        },
        {
            field: 'aggregatorCode', headerName: 'AGG CODE', width: 100,
            renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', color: '#64748b' }}>{p.value || '—'}</Typography>
        },
        {
            field: 'mid', headerName: 'MID', width: 130,
            renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', color: '#475569', bgcolor: '#f1f5f9', px: 1, py: 0.3, borderRadius: '4px', border: '1px solid #e2e8f0' }}>{p.value}</Typography>
        },
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', flex: 1.2, minWidth: 160,
            renderCell: (p) => <Typography variant="body2" fontWeight={600} color="#334155">{p.value}</Typography>
        },
        {
            field: 'sid', headerName: 'SID', width: 100,
            renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', color: '#64748b' }}>{p.value}</Typography>
        },
        {
            field: 'storeName', headerName: 'STORE', flex: 1, minWidth: 130,
            renderCell: (p) => <Typography variant="body2" color="#475569">{p.value || '—'}</Typography>
        },
        {
            field: 'terminalId', headerName: 'TID', width: 110,
            renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', fontWeight: 700, color: '#334155', bgcolor: '#f1f5f9', px: 1, py: 0.3, borderRadius: '4px' }}>{p.value}</Typography>
        },
        { field: 'status', headerName: 'STATUS', width: 150, renderCell: (p) => getStatusChip(p.value) },
        {
            field: 'lastTransactionDate', headerName: 'LAST TXN', width: 120, align: 'right', headerAlign: 'right',
            renderCell: (p) => <Typography variant="body2" color="#64748b" sx={{ fontVariantNumeric: 'tabular-nums' }}>{p.value || <em style={{ color: '#cbd5e1' }}>Never</em>}</Typography>
        },
        {
            field: 'daysInactive', headerName: 'INACTIVE DAYS', type: 'number', width: 120, align: 'right', headerAlign: 'right',
            renderCell: (p) => <Typography variant="body2" fontWeight={600} color={p.value > 30 ? '#ef4444' : '#475569'} sx={{ fontVariantNumeric: 'tabular-nums' }}>{p.value > -1 ? p.value : '—'}</Typography>
        },
    ];

    const filterInputSx = {
        '& .MuiOutlinedInput-root': { borderRadius: '8px', fontSize: '13px', bgcolor: '#f8fafc' },
        '& .MuiInputLabel-root': { fontSize: '12px', fontWeight: 600 },
    };

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Zero Transaction Report" subtitle="Identify inactive merchants and potential churn risks"
                icon={AlertTriangle}
                onExport={() => exportToCSV(data, 'zero_transaction_report')}
                onRunReport={fetchData}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                hideDatePresets
            >
                {/* Inline range type selector */}
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

            {/* Advanced Filters Panel */}
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

            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={data} columns={columns} loading={loading} rowHeight={55}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
                    pageSizeOptions={[25, 50, 100]}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default ZeroTransactionReport;
