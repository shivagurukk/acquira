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
    Minus
} from 'lucide-react';
import ReportHeader from '../../components/ReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import { exportToCSV } from '../../utils/exportUtils';

// --- STYLED HELPERS ---
const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', minimumFractionDigits: 2 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

const TrendPill = ({ val }) => {
    if (!val || val === 0) return <Typography variant="caption" color="text.secondary">-</Typography>;
    const isPositive = val > 0;
    return (
        <Chip
            icon={isPositive ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
            label={`${Math.abs(val).toFixed(1)}%`}
            size="small"
            sx={{
                height: 24,
                bgcolor: isPositive ? 'rgba(16, 185, 129, 0.1)' : 'rgba(239, 68, 68, 0.1)',
                color: isPositive ? '#10b981' : '#ef4444',
                fontWeight: 700,
                border: 'none',
                '& .MuiChip-icon': { color: 'inherit' }
            }}
        />
    );
};

const VolumeRevenueSummary = () => {
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
            const res = await fetch('/api/business/volume-revenue-summary', {
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

    // Calculate Derived Metrics for Table
    const rows = useMemo(() => {
        if (!data.length) return [];
        const maxVol = Math.max(...data.map(d => d.volume || 0));

        return data.map((curr, idx) => {
            const prev = data[idx + 1]; // Compare with next row (chronologically previous if sorted DESC)
            const momVolPct = prev && prev.volume > 0 ? ((curr.volume - prev.volume) / prev.volume) * 100 : 0;
            const momRevPct = prev && prev.msf > 0 ? ((curr.msf - prev.msf) / prev.msf) * 100 : 0;

            // Format Date for Display
            const dateParts = curr.month.split('-');
            const dateObj = new Date(parseInt(dateParts[0]), parseInt(dateParts[1]) - 1);
            const monthStr = dateObj.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });

            return {
                id: idx,
                ...curr,
                monthParams: { str: monthStr, raw: curr.month },
                momVol: momVolPct,
                maxVol,
                momRev: momRevPct
            };
        });
    }, [data]);

    const columns = [
        {
            field: 'monthParams',
            headerName: 'MONTH',
            flex: 1.2,
            minWidth: 150,
            sortComparator: (v1, v2) => v1.raw.localeCompare(v2.raw),
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="700" color="#1e293b">
                    {params.value.str}
                </Typography>
            )
        },
        {
            field: 'count',
            headerName: 'COUNT',
            type: 'number',
            flex: 0.8,
            align: 'center', headerAlign: 'center',
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
                    {/* Visual Bar relative to Max for the period */}
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
            field: 'momVol', // Calculated Trend
            headerName: 'TREND',
            flex: 0.8,
            align: 'center', headerAlign: 'center',
            renderCell: (params) => <TrendPill val={params.value} />
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
                title="Volume & Revenue Statement"
                subtitle="Monthly Financial Performance Table"
                onExport={() => exportToCSV(rows, 'volume_revenue_summary')}
                onRunReport={fetchReport}
                onFilterChange={handleFilterChange}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                filters={filters}
            />

            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchReport} isOpen={showFilters} onClose={() => setShowFilters(false)} />

            {/* Mock Active Filters */}
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
                    rowHeight={70} // Spacious rows to accommodate bars
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
                            color: '#ffffff !important',
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

export default VolumeRevenueSummary;
