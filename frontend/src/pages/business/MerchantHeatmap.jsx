import React, { useState, useEffect } from 'react';
import {
    Box,
    Paper,
    Typography,
    Stack,
    MenuItem,
    Select,
    FormControl,
    InputLabel,
    Avatar
} from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import StandardReportHeader from '../../components/StandardReportHeader';
import { exportToCSV } from '../../utils/exportUtils';

const MerchantHeatmap = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [years, setYears] = useState([new Date().getFullYear()]);
    const [year, setYear] = useState(new Date().getFullYear());
    const [maxVolume, setMaxVolume] = useState(0);

    // Fetch available years
    useEffect(() => {
        const fetchYears = async () => {
            try {
                // Mock or real fetch
                setYears([2024, 2025, 2026]);
            } catch (e) { console.error(e); }
        };
        fetchYears();
    }, []);

    useEffect(() => {
        fetchData();
    }, [year]);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const response = await fetch(`http://localhost:8081/api/analytics/heatmap?year=${year}`, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (response.ok) {
                const result = await response.json();
                processData(result);
            } else {
                // Mock data for dev if API fails
                processData([]);
            }
        } catch (error) {
            console.error("Failed to fetch heatmap data", error);
        } finally {
            setLoading(false);
        }
    };

    const processData = (rawData) => {
        if (!rawData) return;

        let grouped = {};
        let maxVol = 0;

        rawData.forEach(row => {
            const key = row.merchantId;
            if (!grouped[key]) {
                grouped[key] = {
                    id: row.merchantId,
                    merchantName: row.merchantName,
                    merchantId: row.merchantId,
                    volumes: {}, // 1..12
                    total: 0
                };
            }
            grouped[key].volumes[row.month] = row.totalVolume;
            grouped[key].total += row.totalVolume;
            if (row.totalVolume > maxVol) maxVol = row.totalVolume;
        });

        // Flatten for DataGrid
        const rows = Object.values(grouped).map(item => {
            const flattened = {
                id: item.id,
                merchantName: item.merchantName,
                merchantId: item.merchantId,
                total: item.total
            };
            // Add month columns
            for (let i = 1; i <= 12; i++) {
                flattened[`month_${i}`] = item.volumes[i] || 0;
            }
            return flattened;
        });

        setData(rows.sort((a, b) => a.merchantName.localeCompare(b.merchantName)));
        setMaxVolume(maxVol);
    };

    // Refined Green Gradient Colors (Screenshot match)
    const getHeatmapColor = (value) => {
        if (!value || maxVolume === 0) return { bg: '#ffffff', color: '#9ca3af' };

        const pct = value / maxVolume;
        // High = Dark Green (#064e3b), Good = #166534, Med = #22c55e, Low = #86efac
        if (pct > 0.75) return { bg: '#14532d', color: '#ffffff' }; // High - Darkest Green
        if (pct > 0.50) return { bg: '#16a34a', color: '#ffffff' }; // Good - Med Green
        if (pct > 0.35) return { bg: '#22c55e', color: '#ffffff' }; // Med - Light Green
        if (pct > 0.15) return { bg: '#86efac', color: '#064e3b' }; // Low - Pale Green
        return { bg: '#dcfce7', color: '#14532d' }; // Very Low
    };

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', maximumFractionDigits: 0 }).format(val || 0);

    // Columns
    const columns = [
        {
            field: 'merchantId', // Using the field directly
            headerName: 'MID',
            width: 120,
            renderCell: (params) => (
                <Stack justifyContent="center" height="100%">
                    <Typography variant="body2" color="#64748b" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                        {params.value}
                    </Typography>
                </Stack>
            )
        },
        {
            field: 'merchantName',
            headerName: 'MERCHANT NAME',
            width: 240, // Reduced slightly since MID is separate
            renderCell: (params) => (
                <Stack direction="row" spacing={1.5} alignItems="center" sx={{ height: '100%' }}>
                    <Avatar
                        sx={{
                            bgcolor: '#f1f5f9',
                            color: '#475569',
                            fontWeight: 'bold',
                            width: 28,
                            height: 28,
                            fontSize: '0.7rem'
                        }}
                    >
                        {params.value ? params.value.charAt(0) : '?'}
                    </Avatar>
                    <Typography variant="body2" fontWeight="700" color="#0f172a" noWrap title={params.value}>
                        {params.value}
                    </Typography>
                </Stack>
            )
        },
        // Generate Month Columns 1-12
        ...Array.from({ length: 12 }, (_, i) => i + 1).map(month => ({
            field: `month_${month}`,
            headerName: new Date(0, month - 1).toLocaleString('en-US', { month: 'short' }).toUpperCase(),
            width: 95,
            align: 'center',
            headerAlign: 'center',
            renderCell: (params) => {
                const val = params.value;
                const style = getHeatmapColor(val);
                return (
                    <Box sx={{
                        width: '100%',
                        height: '100%', // Full Fill
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        bgcolor: style.bg,
                        color: style.color,
                        fontWeight: '700',
                        fontSize: '0.7rem'
                    }}>
                        {val > 0 ? formatCurrency(val) : ''}
                    </Box>
                );
            }
        })),
        {
            field: 'total',
            headerName: 'TOTAL',
            width: 140,
            align: 'right',
            headerAlign: 'right',
            renderCell: (params) => (
                <Stack justifyContent="center" height="100%">
                    <Typography color="#0f172a" fontWeight="800" variant="body2">
                        {formatCurrency(params.value)}
                    </Typography>
                </Stack>
            )
        }
    ];

    return (
        <Box sx={{ p: 3, bgcolor: '#f8fafc', height: '100vh', display: 'flex', flexDirection: 'column' }}>

            <StandardReportHeader
                title="Merchant Growth Heatmap"
                subtitle={`Annual Performance Visualization - ${year}`}
                onExport={() => exportToCSV(data, `heatmap_${year}`)}
                onRefresh={fetchData}
                loading={loading}
                // We'll hide the standard filter inputs since this page is specialized
                showFilters={false}
            />

            {/* Specialized Heatmap Controls */}
            <Paper elevation={0} sx={{ p: 2, mb: 3, borderRadius: 3, display: 'flex', alignItems: 'center', gap: 3, bgcolor: 'white', border: '1px solid #e2e8f0' }}>
                <FormControl size="small" sx={{ minWidth: 150 }}>
                    <InputLabel id="year-select-label" sx={{ fontSize: '0.85rem' }}>Year Report</InputLabel>
                    <Select
                        labelId="year-select-label"
                        value={year}
                        label="Year Report"
                        onChange={(e) => setYear(Number(e.target.value))}
                        sx={{ borderRadius: 2, fontSize: '0.9rem', fontWeight: 600 }}
                    >
                        {years.map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                    </Select>
                </FormControl>

                {/* Legend */}
                <Stack direction="row" spacing={3} sx={{ ml: 'auto' }} alignItems="center">
                    <Typography variant="caption" fontWeight="bold" color="#64748b">LEGEND:</Typography>
                    {[
                        { color: '#14532d', label: 'High' },
                        { color: '#16a34a', label: 'Good' },
                        { color: '#22c55e', label: 'Med' },
                        { color: '#86efac', label: 'Low' },
                        { color: '#dcfce7', label: 'Crit' }
                    ].map(type => (
                        <Stack key={type.label} direction="row" spacing={1} alignItems="center">
                            <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: type.color }} />
                            <Typography variant="caption" fontWeight="600" color="#64748b">{type.label}</Typography>
                        </Stack>
                    ))}
                </Stack>
            </Paper>

            {/* Heatmap DataGrid */}
            <Paper elevation={0} sx={{ flex: 1, width: '100%', borderRadius: 2, overflow: 'hidden', border: '1px solid #e2e8f0', bgcolor: 'white' }}>
                <DataGrid
                    rows={data}
                    columns={columns}
                    loading={loading}
                    disableRowSelectionOnClick
                    rowHeight={50} // Tighter rows for density
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{
                        toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } },
                    }}
                    sx={{
                        border: 'none',
                        // Header Styling
                        '& .MuiDataGrid-columnHeaders': {
                            bgcolor: 'white',
                            color: '#334155',
                            fontWeight: 800,
                            fontSize: '0.7rem',
                            textTransform: 'uppercase',
                        },
                        '& .MuiDataGrid-columnHeaderTitle': {
                            fontWeight: 800,
                        },
                        // Cell Styling
                        '& .MuiDataGrid-cell': {
                            borderBottom: '1px solid #f1f5f9',
                            padding: 0, // Remove padding for heatmap fill
                        },
                        '& .MuiDataGrid-row:hover': {
                            bgcolor: 'transparent', // Disable Default Hover to not clash with colors? Or allow it?
                        }
                    }}
                />
            </Paper>
        </Box>
    );
};

export default MerchantHeatmap;
