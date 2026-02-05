import React, { useState, useEffect } from 'react';
import BusinessFilters from '../../components/BusinessFilters';
import StandardReportHeader from '../../components/StandardReportHeader';
import { exportToCSV } from '../../utils/exportUtils';
import { DataGrid } from '@mui/x-data-grid';
import { Box, Chip, Typography } from '@mui/material';

// Styled DataGrid wrappers could be added here or via sx props

const AttritionReport = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(true);

    // Default to current month
    const today = new Date();
    const firstDay = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().split('T')[0];
    const lastDay = today.toISOString().split('T')[0];

    const [filters, setFilters] = useState({
        startDate: firstDay, endDate: lastDay,
        openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [],
        rmList: [], teamLeaderList: [], sectorList: [],
        destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '',
        datePreset: 'MONTH' // Default preset match
    });

    useEffect(() => {
        fetchData();
    }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/attrition-report', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify(filters)
            });

            if (res.ok) {
                const result = await res.json();
                // Ensure unique IDs for DataGrid
                const rows = result.map((r, i) => ({ id: r.mid || i, ...r }));
                setData(rows);
            }
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    const handleApply = () => {
        fetchData();
    };

    const handleFilterChange = (key, val) => {
        if (typeof key === 'object') {
            setFilters(prev => ({ ...prev, ...key }));
        } else {
            setFilters(prev => ({ ...prev, [key]: val }));
        }
    };

    // Date Logic
    const selectedYear = filters.endDate ? new Date(filters.endDate).getFullYear() : new Date().getFullYear();
    const prevYear = selectedYear - 1;

    // --- Columns Definition ---
    const currencyFormatter = (value) => {
        if (value == null) return '-';
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', notation: 'compact' }).format(value);
    };

    const pctFormatter = (value) => {
        if (value == null) return '-';
        return `${value.toFixed(1)}%`;
    };

    const columns = [
        {
            field: 'mid',
            headerName: 'MID',
            width: 140,
            renderCell: (params) => (
                <Box sx={{ display: 'flex', alignItems: 'center', height: '100%' }}>
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', color: '#64748b' }}>
                        {params.value}
                    </Typography>
                </Box>
            )
        },
        {
            field: 'merchant_info',
            headerName: 'MERCHANT NAME',
            width: 250,
            renderCell: (params) => (
                <Box sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '100%' }}>
                    <Typography variant="body2" sx={{ fontWeight: 600, color: '#0f172a' }}>{params.row.name}</Typography>
                </Box>
            )
        },
        // MTD
        {
            field: 'mtd_prev',
            headerName: `${prevYear} Vol`,
            width: 120,
            type: 'number',
            renderCell: (params) => <Typography variant="body2" sx={{ color: '#475569' }}>{currencyFormatter(params.value)}</Typography>
        },
        {
            field: 'mtd_current',
            headerName: `${selectedYear} Vol`,
            width: 120,
            type: 'number',
            renderCell: (params) => <Typography variant="body2" fontWeight="600" sx={{ color: '#0f172a' }}>{currencyFormatter(params.value)}</Typography>
        },
        {
            field: 'mtd_pct',
            headerName: '% Change',
            width: 120,
            type: 'number',
            renderCell: (params) => (
                <Typography
                    variant="body2"
                    sx={{
                        fontWeight: 'bold',
                        color: params.value < 0 ? '#ef4444' : params.value > 0 ? '#10b981' : '#cbd5e1'
                    }}
                >
                    {pctFormatter(params.value)}
                </Typography>
            )
        },
        // YTD
        {
            field: 'ytd_prev',
            headerName: `${prevYear} Vol`,
            width: 120,
            type: 'number',
            renderCell: (params) => <Typography variant="body2" sx={{ color: '#475569' }}>{currencyFormatter(params.value)}</Typography>
        },
        {
            field: 'ytd_current',
            headerName: `${selectedYear} Vol`,
            width: 120,
            type: 'number',
            renderCell: (params) => <Typography variant="body2" fontWeight="600" sx={{ color: '#0f172a' }}>{currencyFormatter(params.value)}</Typography>
        },
        {
            field: 'ytd_pct',
            headerName: '% Change',
            width: 120,
            type: 'number',
            renderCell: (params) => (
                <Typography
                    variant="body2"
                    sx={{
                        fontWeight: 'bold',
                        color: params.value < 0 ? '#ef4444' : params.value > 0 ? '#10b981' : '#cbd5e1'
                    }}
                >
                    {pctFormatter(params.value)}
                </Typography>
            )
        }
    ];

    const columnGroupingModel = [
        {
            groupId: 'mtd_group',
            headerName: `MTD Comparison (${prevYear} vs ${selectedYear})`,
            headerClassName: 'mtd-header-group',
            children: [{ field: 'mtd_prev' }, { field: 'mtd_current' }, { field: 'mtd_pct' }],
        },
        {
            groupId: 'ytd_group',
            headerName: `YTD Comparison (${prevYear} vs ${selectedYear})`,
            headerClassName: 'ytd-header-group',
            children: [{ field: 'ytd_prev' }, { field: 'ytd_current' }, { field: 'ytd_pct' }],
        }
    ];

    return (
        <Box className="page-container" sx={{ height: '100vh', display: 'flex', flexDirection: 'column', p: 3, bgcolor: '#f8fafc' }}>

            <StandardReportHeader
                title="Attrition Report (YoY)"
                subtitle="Year-over-Year Volume Comparison Performance"
                onExport={() => exportToCSV(data, 'attrition_report')}
                onRefresh={fetchData}
                onFilterChange={handleFilterChange}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                filters={filters}
            />

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={handleApply}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            <Box sx={{
                flex: 1,
                bgcolor: 'white',
                borderRadius: 2,
                boxShadow: '0 1px 3px 0 rgba(0, 0, 0, 0.1)',
                '& .time-header-group': { fontWeight: 'bold' },
                '& .mtd-header-group': { bgcolor: '#eff6ff', color: '#1e40af', fontWeight: 'bold' },
                '& .ytd-header-group': { bgcolor: '#f8fafc', color: '#334155', fontWeight: 'bold' }
            }}>
                <DataGrid
                    rows={data}
                    columns={columns}
                    columnGroupingModel={columnGroupingModel}
                    loading={loading}
                    disableRowSelectionOnClick
                    rowHeight={60}
                    initialState={{
                        pagination: { paginationModel: { pageSize: 25 } },
                    }}
                    pageSizeOptions={[25, 50, 100]}
                    experimentalFeatures={{ columnGrouping: true }}
                    sx={{
                        border: 'none',
                        '& .MuiDataGrid-columnHeaders': {
                            bgcolor: '#f8fafc',
                            fontSize: '0.75rem',
                            textTransform: 'uppercase'
                        },
                        '& .MuiDataGrid-virtualScroller': {
                            bgcolor: 'white'
                        }
                    }}
                />
            </Box>
        </Box>
    );
};

export default AttritionReport;
