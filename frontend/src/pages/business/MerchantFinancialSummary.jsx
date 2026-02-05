import React, { useState, useEffect, useMemo } from 'react';
import {
    Box,
    Paper,
    Typography,
    Stack,
    Chip,
    LinearProgress,
    Tooltip,
    CircularProgress
} from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    TrendingUp,
    TrendingDown,
    Minus,
    Store,
    CreditCard
} from 'lucide-react';
import ReportHeader from '../../components/ReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import { exportToCSV } from '../../utils/exportUtils';

// --- STYLED HELPERS ---
const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', minimumFractionDigits: 2 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

const MerchantFinancialSummary = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({ datePreset: 'Custom' });

    useEffect(() => {
        fetchReport();
    }, []);

    const fetchReport = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/merchant-financial-summary', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                body: JSON.stringify(filters)
            });
            if (res.ok) {
                const result = await res.json();
                setData(result);
            }
        } catch (error) {
            console.error("Failed to load report", error);
        } finally {
            setLoading(false);
        }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    // Calculate Derived Metrics (Max Volume for Bar)
    const rows = useMemo(() => {
        if (!data.length) return [];
        const maxVol = Math.max(...data.map(d => d.volume || 0));

        return data.map((d, i) => ({
            id: d.mid || d.sid || i,
            ...d,
            maxVol
        }));
    }, [data]);

    const columns = [
        {
            field: 'merchantName',
            headerName: 'MERCHANT NAME',
            flex: 1.5,
            minWidth: 200,
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="700" color="#1e293b">
                    {params.value}
                </Typography>
            )
        },
        {
            field: 'mid',
            headerName: 'MID',
            flex: 1,
            minWidth: 140,
            renderCell: (params) => (
                <Stack direction="row" spacing={1} alignItems="center">
                    <CreditCard size={14} color="#94a3b8" />
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600, color: '#475569' }}>
                        {params.value}
                    </Typography>
                </Stack>
            )
        },
        {
            field: 'sid',
            headerName: 'SID',
            flex: 0.8,
            minWidth: 100,
            renderCell: (params) => (
                <Stack direction="row" spacing={1} alignItems="center">
                    <Store size={14} color="#94a3b8" />
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', color: '#64748b' }}>
                        {params.value}
                    </Typography>
                </Stack>
            )
        },
        {
            field: 'count',
            headerName: 'COUNT',
            type: 'number',
            flex: 0.8,
            align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Chip label={formatNumber(params.value)} size="small" variant="outlined" sx={{ fontWeight: 600, borderColor: '#e2e8f0', bgcolor: '#f8fafc' }} />
            )
        },
        {
            field: 'volume',
            headerName: 'VOLUME (AED)',
            flex: 1.5,
            align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center' }}>
                    <Typography variant="body2" fontWeight="700" color="#0f172a">
                        {formatCurrency(params.value)}
                    </Typography>
                    {/* Visual Bar relative to Max across all merchants */}
                    <Box sx={{ width: '80%', height: 4, bgcolor: '#f1f5f9', borderRadius: 2, mt: 0.5, overflow: 'hidden' }}>
                        <Box sx={{
                            width: `${(params.value / params.row.maxVol) * 100}%`,
                            height: '100%',
                            bgcolor: '#3b82f6',
                            borderRadius: 2
                        }} />
                    </Box>
                </Box>
            )
        },
        {
            field: 'msf',
            headerName: 'MSF (AED)',
            flex: 1.2,
            align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="600" color="#334155">
                    {formatCurrency(params.value)}
                </Typography>
            )
        },
        {
            field: 'opt_in_volume',
            headerName: 'OPT-IN (AED)',
            flex: 1.2,
            align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="500" color="#64748b">
                    {formatCurrency(params.value)}
                </Typography>
            )
        }
    ];

    return (
        <Box sx={{ p: 3, bgcolor: '#F8FAFC', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>

            <ReportHeader
                title="Merchant Financial Summary"
                subtitle="Business Universe Report"
                onExport={() => exportToCSV(data, 'merchant_financial_summary')}
                onRunReport={fetchReport}
                onFilterChange={handleFilterChange}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                filters={filters}
            />

            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchReport} isOpen={showFilters} onClose={() => setShowFilters(false)} />

            {/* Active Filters Mock */}
            <Stack direction="row" spacing={1} sx={{ mb: 2 }} alignItems="center">
                {(filters.startDate || filters.endDate) && (
                    <Chip label="Date Filter Active" size="small" color="primary" variant="outlined" onDelete={() => setFilters(prev => ({ ...prev, startDate: '', endDate: '' }))} />
                )}
            </Stack>

            <Paper sx={{
                flex: 1,
                width: '100%',
                borderRadius: '12px',
                overflow: 'hidden',
                boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)',
                border: '1px solid #E2E8F0'
            }}>
                <DataGrid
                    rows={rows}
                    columns={columns}
                    loading={loading}
                    rowHeight={60}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{
                        toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } },
                    }}
                    sx={{
                        border: 'none',
                        // --- HEADER STYLING (Professional Blue) ---
                        '& .MuiDataGrid-columnHeaders': {
                            backgroundColor: '#1565C0 !important', // Professional Blue
                            color: '#ffffff !important',
                            borderBottom: 'none'
                        },
                        '& .MuiDataGrid-columnHeader': {
                            backgroundColor: '#1565C0 !important',
                            color: '#ffffff !important',
                        },
                        '& .MuiDataGrid-columnHeaderTitle': {
                            fontWeight: 800,
                            color: '#ffffff !important', // Ensure text is white
                            textTransform: 'uppercase',
                            fontSize: '0.75rem',
                            letterSpacing: '0.05em'
                        },
                        '& .MuiDataGrid-iconSeparator': { color: 'rgba(255,255,255,0.2) !important' },
                        '& .MuiDataGrid-menuIcon': { color: '#ffffff !important' },
                        '& .MuiDataGrid-sortIcon': { color: '#ffffff !important' },

                        // --- CONTENT STYLING ---
                        '& .MuiDataGrid-row': {
                            borderBottom: '1px solid #F1F5F9'
                        },
                        // Zebra Striping
                        '& .MuiDataGrid-row:nth-of-type(even)': {
                            backgroundColor: '#F8FAFC',
                        },
                        '& .MuiDataGrid-row:hover': {
                            backgroundColor: '#F1F5F9 !important'
                        },
                        '& .MuiDataGrid-cell': {
                            borderBottom: 'none',
                            display: 'flex',
                            alignItems: 'center'
                        },
                        '& .MuiDataGrid-withBorderColor': { borderColor: '#E2E8F0' },
                        // Scrollbar styling
                        '& ::-webkit-scrollbar': { width: '8px', height: '8px' },
                        '& ::-webkit-scrollbar-track': { background: '#f1f1f1' },
                        '& ::-webkit-scrollbar-thumb': { background: '#90CAF9', borderRadius: '4px' }, // Light Blue thumb
                    }}
                />
            </Paper>
        </Box>
    );
};

export default MerchantFinancialSummary;
