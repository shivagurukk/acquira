import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Box, Paper, Typography, Dialog, DialogTitle, DialogContent, DialogActions, Button } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { BarChart2, DollarSign, Hash, Layers, AlertTriangle } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useToast } from '../../contexts/ToastContext';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

const formatCurrency = (val) =>
    new Intl.NumberFormat('en-US', { style: 'decimal', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) =>
    new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const fmtLocal = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

const computeDateRange = (preset) => {
    const now = new Date();
    switch (preset) {
        case 'TODAY':      return { startDate: fmtLocal(now), endDate: fmtLocal(now) };
        case 'MONTH':      return { startDate: fmtLocal(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmtLocal(now) };
        case 'LAST_MONTH': return { startDate: fmtLocal(new Date(now.getFullYear(), now.getMonth() - 1, 1)), endDate: fmtLocal(new Date(now.getFullYear(), now.getMonth(), 0)) };
        case 'YEAR':       return { startDate: fmtLocal(new Date(now.getFullYear(), 0, 1)), endDate: fmtLocal(now) };
        case 'PY':         return { startDate: fmtLocal(new Date(now.getFullYear() - 1, 0, 1)), endDate: fmtLocal(new Date(now.getFullYear() - 1, 11, 31)) };
        default:           return {};
    }
};

const MerchantAnalyticsReport = () => {
    const { show: toast } = useToast();
    const { currencyCode } = useAuth();
    const [loading, setLoading] = useState(false);
    const [exportLoading, setExportLoading] = useState(false);
    const [data, setData] = useState([]);
    const [totalRows, setTotalRows] = useState(0);
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 25 });
    const [showFilters, setShowFilters] = useState(false);
    const [showExportDialog, setShowExportDialog] = useState(false);

    const [filters, setFilters] = useState(() => ({
        datePreset: 'YEAR',
        ...computeDateRange('YEAR'),
    }));

    // ── useRef so filter-panel Apply always sees the latest filters ──
    const filtersRef = useRef(filters);
    filtersRef.current = filters;
    const paginationRef = useRef(paginationModel);
    paginationRef.current = paginationModel;

    // ── Fetch ──────────────────────────────────────────────────────
    const fetchReport = useCallback(async (overrideFilters, overridePagination) => {
        setLoading(true);
        try {
            const active = overrideFilters || filtersRef.current;
            const pg = overridePagination || paginationRef.current;
            const body = { ...active };
            delete body.datePreset;
            const res = await api.post(
                `/business/merchant-analytics?page=${pg.page}&size=${pg.pageSize}`,
                body
            );
            setData(res.data.content || []);
            setTotalRows(res.data.totalElements || 0);
        } catch (err) {
            console.error(err);
            toast('Failed to load data. Please try again.', 'error');
        } finally {
            setLoading(false);
        }
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    useEffect(() => {
        fetchReport(undefined, paginationModel);
    }, [paginationModel]); // eslint-disable-line react-hooks/exhaustive-deps

    // ── Full export (all rows, not just current page) ──────────────
    const handleExport = useCallback(() => {
        if (totalRows > data.length) {
            setShowExportDialog(true);
        } else {
            exportToCSV(data, 'merchant_analytics');
            toast(`Exported ${data.length} rows.`, 'success');
        }
    }, [data, totalRows, toast]);

    const handleExportAll = useCallback(async () => {
        setShowExportDialog(false);
        setExportLoading(true);
        toast('Fetching all rows for export…', 'info');
        try {
            const body = { ...filtersRef.current };
            delete body.datePreset;
            const res = await api.post('/business/merchant-analytics?page=0&size=10000', body);
            exportToCSV(res.data.content || [], 'merchant_analytics_full');
            toast(`Exported ${(res.data.content || []).length} rows successfully.`, 'success');
        } catch (err) {
            toast('Export failed: ' + err.message, 'error');
        } finally {
            setExportLoading(false);
        }
    }, [toast]);

    // ── Filter handling ────────────────────────────────────────────
    const handleFilterChange = useCallback((keyOrObj) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
    }, []);

    // ── KPI cards ─────────────────────────────────────────────────
    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol      = data.reduce((s, d) => s + (Number(d.volume)      || 0), 0);
        const totalMsf      = data.reduce((s, d) => s + (Number(d.msf)         || 0), 0);
        const totalCount    = data.reduce((s, d) => s + (Number(d.count)        || 0), 0);
        const totalInterchg = data.reduce((s, d) => s + (Number(d.interchange)  || 0), 0);
        return [
            { title: 'Total Records',   value: formatNumber(totalRows),                          icon: Layers,     color: '#6366f1', subtitle: `Page ${paginationModel.page + 1} of ${Math.ceil(totalRows / paginationModel.pageSize)}` },
            { title: 'Page Volume',     value: `${currencyCode} ${formatCompact(totalVol)}`,     icon: DollarSign, color: '#3b82f6' },
            { title: 'Page MSF',        value: `${currencyCode} ${formatCompact(totalMsf)}`,     icon: BarChart2,  color: '#10b981' },
            { title: 'Page Trnx Count', value: formatCompact(totalCount),                         icon: Hash,       color: '#f59e0b' },
        ];
    }, [data, totalRows, paginationModel]);

    // ── Columns ───────────────────────────────────────────────────
    const columns = [
        { field: 'sid',          headerName: 'SID',         width: 140 },
        { field: 'terminalType', headerName: 'Terminal',    width: 120 },
        { field: 'mid',          headerName: 'MID',         width: 160 },
        { field: 'merchantName', headerName: 'Name',        flex: 1, minWidth: 180,
            renderCell: (p) => <Typography variant="body2" fontWeight={600} color="#0f172a">{p.value}</Typography> },
        { field: 'volume',       headerName: 'Volume',      width: 140, type: 'number', valueFormatter: (v) => formatCurrency(v) },
        { field: 'count',        headerName: 'Trnx Count',  width: 120, type: 'number', valueFormatter: (v) => formatNumber(v) },
        { field: 'msf',          headerName: 'MSF',         width: 130, type: 'number', valueFormatter: (v) => formatCurrency(v) },
        { field: 'interchange',  headerName: 'Interchange', width: 130, type: 'number', valueFormatter: (v) => formatCurrency(v) },
        { field: 'mcc',          headerName: 'MCC',         width: 90 },
        { field: 'industry',     headerName: 'Industry',    width: 160 },
        { field: 'legalName',    headerName: 'Legal Name',  width: 200 },
        { field: 'dccOptin',     headerName: 'DCC Opt-In',  width: 130, type: 'number', valueFormatter: (v) => formatCurrency(v) },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Analytics Report"
                subtitle={`Detailed performance metrics · ${formatNumber(totalRows)} total records`}
                icon={BarChart2}
                onExport={handleExport}
                onRunReport={() => fetchReport()}
                onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={() => fetchReport()}
                loading={loading || exportLoading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(v => !v)}
                filters={filters}
            />

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={() => fetchReport()}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            <KpiCards cards={kpis} />

            <Paper sx={premiumTableWrapper}>
                <DataGrid
                    rows={data}
                    columns={columns}
                    getRowId={(row, i) => row.merchantId ?? `${row.sid ?? 'r'}-${i}`}
                    rowCount={totalRows}
                    loading={loading}
                    paginationModel={paginationModel}
                    paginationMode="server"
                    onPaginationModelChange={setPaginationModel}
                    pageSizeOptions={[25, 50, 100]}
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>

            {/* Export confirmation dialog */}
            <Dialog open={showExportDialog} onClose={() => setShowExportDialog(false)} maxWidth="xs" fullWidth>
                <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <AlertTriangle size={18} color="#f59e0b" />
                    Export confirmation
                </DialogTitle>
                <DialogContent>
                    <Typography variant="body2" color="text.secondary">
                        You're viewing <strong>page {paginationModel.page + 1}</strong> ({data.length} rows).
                        The full dataset has <strong>{formatNumber(totalRows)} rows</strong>.
                    </Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                        Export this page only, or fetch and export all {formatNumber(totalRows)} rows?
                    </Typography>
                </DialogContent>
                <DialogActions>
                    <Button onClick={() => { setShowExportDialog(false); exportToCSV(data, 'merchant_analytics_page'); toast(`Exported ${data.length} rows (current page).`, 'success'); }} size="small">
                        This page ({data.length} rows)
                    </Button>
                    <Button onClick={handleExportAll} variant="contained" size="small" disableElevation>
                        Export all {formatNumber(totalRows)} rows
                    </Button>
                </DialogActions>
            </Dialog>
        </Box>
    );
};

class ErrorBoundary extends React.Component {
    constructor(props) { super(props); this.state = { hasError: false, error: null }; }
    static getDerivedStateFromError(error) { return { hasError: true, error }; }
    componentDidCatch(error, info) { console.error('MerchantAnalyticsReport error:', error, info); }
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
