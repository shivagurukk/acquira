import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { CreditCard, Hash, DollarSign, TrendingUp } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', minimumFractionDigits: 2 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const DebitPrepaidMetrics = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({ datePreset: 'MONTH' });

    useEffect(() => { fetchData(); }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const res = await fetch('/api/business/debit-prepaid-metrics', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) },
                body: JSON.stringify(filters)
            });
            if (res.ok) {
                const result = await res.json();
                setData(result.map((r, i) => ({ id: r.mid || i, ...r })));
            }
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        const topVols = data.slice().sort((a, b) => (b.volume || 0) - (a.volume || 0)).slice(0, 10).map(d => d.volume || 0);
        return [
            { title: 'Total Merchants', value: formatNumber(data.length), icon: CreditCard, color: '#6366f1' },
            { title: 'Total Volume', value: `AED ${formatCompact(totalVol)}`, icon: DollarSign, color: '#3b82f6', sparkData: topVols },
            { title: 'Total Transactions', value: formatCompact(totalCount), icon: Hash, color: '#10b981' },
            { title: 'Avg per Merchant', value: `AED ${formatCompact(data.length > 0 ? totalVol / data.length : 0)}`, icon: TrendingUp, color: '#f59e0b' },
        ];
    }, [data]);

    const columns = [
        {
            field: 'mid', headerName: 'MID', width: 150,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: '"Roboto Mono", monospace', fontSize: '13px', color: '#475569', bgcolor: '#f1f5f9', px: 1, py: 0.5, borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                    {params.value}
                </Typography>
            )
        },
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', flex: 1, minWidth: 200,
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color="#1e293b">{params.value}</Typography>
        },
        {
            field: 'count', headerName: 'COUNT', type: 'number', width: 120, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color="#64748b" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatNumber(params.value)}</Typography>
        },
        {
            field: 'volume', headerName: 'VOLUME (AED)', type: 'number', flex: 1, minWidth: 180, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="700" color="#0f172a" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Debit & Prepaid Metrics" subtitle="Domestic debit and prepaid performance by merchant"
                icon={CreditCard}
                onExport={() => exportToCSV(data, 'debit_prepaid_metrics')}
                onRunReport={fetchData} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchData} isOpen={showFilters} onClose={() => setShowFilters(false)} />
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

export default DebitPrepaidMetrics;
