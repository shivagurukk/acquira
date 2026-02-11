import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, Stack, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { DollarSign, Store, CreditCard, Hash, Users, TrendingUp } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', minimumFractionDigits: 2 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const MerchantFinancialSummary = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({ datePreset: 'MONTH' });

    useEffect(() => { fetchReport(); }, []);

    const fetchReport = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/merchant-financial-summary', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                body: JSON.stringify(filters)
            });
            if (res.ok) setData(await res.json());
        } catch (error) { console.error("Failed to load report", error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const rows = useMemo(() => {
        if (!data.length) return [];
        const maxVol = Math.max(...data.map(d => d.volume || 0));
        return data.map((d, i) => ({ id: d.mid || d.sid || i, ...d, maxVol }));
    }, [data]);

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalMsf = data.reduce((s, d) => s + (d.msf || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        const topVols = data.slice().sort((a, b) => (b.volume || 0) - (a.volume || 0)).slice(0, 10).map(d => d.volume || 0);
        return [
            { title: 'Total Merchants', value: formatNumber(data.length), icon: Users, color: '#6366f1' },
            { title: 'Total Volume', value: `AED ${formatCompact(totalVol)}`, icon: TrendingUp, color: '#3b82f6', sparkData: topVols },
            { title: 'Total MSF', value: `AED ${formatCompact(totalMsf)}`, icon: DollarSign, color: '#10b981' },
            { title: 'Total Transactions', value: formatCompact(totalCount), icon: Hash, color: '#f59e0b' },
        ];
    }, [data]);

    const columns = [
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', flex: 1.5, minWidth: 200,
            renderCell: (params) => <Typography variant="body2" fontWeight="700" color="#1e293b">{params.value}</Typography>
        },
        {
            field: 'mid', headerName: 'MID', flex: 1, minWidth: 140,
            renderCell: (params) => (
                <Stack direction="row" spacing={1} alignItems="center">
                    <CreditCard size={14} color="#94a3b8" />
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600, color: '#475569' }}>{params.value}</Typography>
                </Stack>
            )
        },
        {
            field: 'sid', headerName: 'SID', flex: 0.8, minWidth: 100,
            renderCell: (params) => (
                <Stack direction="row" spacing={1} alignItems="center">
                    <Store size={14} color="#94a3b8" />
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', color: '#64748b' }}>{params.value}</Typography>
                </Stack>
            )
        },
        {
            field: 'count', headerName: 'COUNT', type: 'number', flex: 0.8, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Chip label={formatNumber(params.value)} size="small" variant="outlined" sx={{ fontWeight: 600, borderColor: '#e2e8f0', bgcolor: '#f8fafc' }} />
        },
        {
            field: 'volume', headerName: 'VOLUME (AED)', flex: 1.5, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center' }}>
                    <Typography variant="body2" fontWeight="700" color="#0f172a">{formatCurrency(params.value)}</Typography>
                    <Box sx={{ width: '80%', height: 4, bgcolor: '#f1f5f9', borderRadius: 2, mt: 0.5, overflow: 'hidden' }}>
                        <Box sx={{ width: `${(params.value / params.row.maxVol) * 100}%`, height: '100%', bgcolor: '#6366f1', borderRadius: 2 }} />
                    </Box>
                </Box>
            )
        },
        {
            field: 'msf', headerName: 'MSF (AED)', flex: 1.2, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color="#334155">{formatCurrency(params.value)}</Typography>
        },
        {
            field: 'opt_in_volume', headerName: 'OPT-IN (AED)', flex: 1.2, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="500" color="#64748b">{formatCurrency(params.value)}</Typography>
        }
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Financial Summary" subtitle="Business Universe — per-merchant breakdown"
                icon={DollarSign}
                onExport={() => exportToCSV(data, 'merchant_financial_summary')}
                onRunReport={fetchReport} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchReport} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />
            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={rows} columns={columns} loading={loading} rowHeight={60}
                    disableRowSelectionOnClick slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default MerchantFinancialSummary;
