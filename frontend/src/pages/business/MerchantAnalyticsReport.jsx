import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { BarChart2, DollarSign, Hash, Layers, Store } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'decimal', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const MerchantAnalyticsReport = () => {
    const [loading, setLoading] = useState(false);
    const [data, setData] = useState([]);
    const [totalRows, setTotalRows] = useState(0);
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 25 });
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState(() => {
        const now = new Date();
        const fmt = (d) => d.toISOString().split('T')[0];
        return { datePreset: 'MONTH', startDate: fmt(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmt(now) };
    });

    useEffect(() => { fetchReport(); }, [paginationModel]); // eslint-disable-line react-hooks/exhaustive-deps

    const fetchReport = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const body = { ...filters };
            if (body.datePreset && body.datePreset !== 'CUSTOM' && (!body.startDate || !body.endDate)) {
                const now = new Date();
                const fmt = (d) => d.toISOString().split('T')[0];
                if (body.datePreset === 'MONTH') { body.startDate = fmt(new Date(now.getFullYear(), now.getMonth(), 1)); body.endDate = fmt(now); }
                else if (body.datePreset === 'YEAR') { body.startDate = fmt(new Date(now.getFullYear(), 0, 1)); body.endDate = fmt(now); }
            }
            delete body.datePreset;
            const res = await fetch(`/api/business/merchant-analytics?page=${paginationModel.page}&size=${paginationModel.pageSize}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) },
                body: JSON.stringify(body)
            });
            if (res.ok) {
                const result = await res.json();
                setData(result.content || []);
                setTotalRows(result.totalElements || 0);
            }
        } catch (err) { console.error(err); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalMsf = data.reduce((s, d) => s + (d.msf || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        return [
            { title: 'Total Records', value: formatNumber(totalRows), icon: Layers, color: '#6366f1', subtitle: `Page ${paginationModel.page + 1}` },
            { title: 'Page Volume', value: `AED ${formatCompact(totalVol)}`, icon: DollarSign, color: '#3b82f6' },
            { title: 'Page MSF', value: `AED ${formatCompact(totalMsf)}`, icon: BarChart2, color: '#10b981' },
            { title: 'Page Trnx Count', value: formatCompact(totalCount), icon: Hash, color: '#f59e0b' },
        ];
    }, [data, totalRows, paginationModel.page]);

    const columns = [
        { field: 'sid', headerName: 'SID', width: 120 },
        { field: 'terminalType', headerName: 'Terminal', width: 120 },
        { field: 'mid', headerName: 'MID', width: 150 },
        {
            field: 'merchantName', headerName: 'NAME', width: 200,
            renderCell: (params) => <Typography variant="body2" fontWeight={600} color="#0f172a">{params.value}</Typography>
        },
        { field: 'volume', headerName: 'Volume', width: 130, type: 'number', valueFormatter: (value) => formatCurrency(value) },
        { field: 'count', headerName: 'Trnx Count', width: 120, type: 'number', valueFormatter: (value) => formatNumber(value) },
        { field: 'msf', headerName: 'MSF', width: 120, type: 'number', valueFormatter: (value) => formatCurrency(value) },
        { field: 'interchange', headerName: 'Interchange', width: 120, type: 'number', valueFormatter: (value) => formatCurrency(value) },
        { field: 'mcc', headerName: 'MCC', width: 90 },
        { field: 'industry', headerName: 'Industry', width: 150 },
        { field: 'legalName', headerName: 'Legal Name', width: 220 },
        { field: 'dccOptin', headerName: 'DCC Opt-In', width: 120, type: 'number', valueFormatter: (value) => formatCurrency(value) },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Analytics Report" subtitle="Detailed performance metrics with server-side pagination"
                icon={BarChart2}
                onExport={() => exportToCSV(data, 'merchant_analytics')}
                onRunReport={fetchReport} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchReport} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />
            <Paper sx={premiumTableWrapper}>
                <DataGrid
                    rows={data} columns={columns}
                    getRowId={(row) => `${row.mid}-${row.sid}`}
                    rowCount={totalRows} loading={loading}
                    paginationModel={paginationModel}
                    paginationMode="server"
                    onPaginationModelChange={setPaginationModel}
                    pageSizeOptions={[25, 50, 100]}
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

// Keep ErrorBoundary wrapper
class ErrorBoundary extends React.Component {
    constructor(props) { super(props); this.state = { hasError: false, error: null }; }
    static getDerivedStateFromError(error) { return { hasError: true }; }
    componentDidCatch(error, errorInfo) { this.setState({ error }); console.error("Uncaught error:", error, errorInfo); }
    render() {
        if (this.state.hasError) {
            return (
                <Box p={4}>
                    <Typography variant="h4" color="error" gutterBottom>Something went wrong</Typography>
                    <Paper sx={{ p: 3, bgcolor: '#FFF1F2', color: '#BE123C' }}>
                        <Typography variant="h6" fontFamily="monospace">{this.state.error?.toString()}</Typography>
                    </Paper>
                </Box>
            );
        }
        return this.props.children;
    }
}

export default function WrappedMerchantAnalyticsReport() {
    return <ErrorBoundary><MerchantAnalyticsReport /></ErrorBoundary>;
}
