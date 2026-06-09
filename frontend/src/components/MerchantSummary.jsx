import React, { useState, useEffect } from 'react';
import {
    Box,
    Paper,
    Typography,
    FormControl,
    InputLabel,
    Select,
    MenuItem,
    Grid,
    Button
} from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { Download, LayoutGrid, RotateCcw, Calendar } from 'lucide-react';
import { formatCurrency } from '../utils/formatters';

const MerchantSummary = () => {
    const [summaries, setSummaries] = useState([]);
    const [loading, setLoading] = useState(false);

    // Date Filters
    const [year, setYear] = useState(new Date().getFullYear());
    const [month, setMonth] = useState(new Date().getMonth() + 1);
    const [day, setDay] = useState(new Date().getDate());

    // Pagination
    const [paginationModel, setPaginationModel] = useState({
        page: 0,
        pageSize: 20,
    });
    const [totalElements, setTotalElements] = useState(0);

    const years = Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - i);
    const months = Array.from({ length: 12 }, (_, i) => i + 1);
    const days = Array.from({ length: 31 }, (_, i) => i + 1);

    useEffect(() => {
        fetchSummaries();
    }, [paginationModel, year, month, day]);

    const fetchSummaries = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const params = new URLSearchParams();
            params.append('year', year);
            params.append('month', month);
            params.append('day', day);
            params.append('page', paginationModel.page);
            params.append('size', paginationModel.pageSize);

            const res = await fetch(`/api/analytics/merchant-summaries?${params.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });

            if (res.ok) {
                const data = await res.json();
                // DataGrid expects 'id' property. If not present, we need to map it or use getRowId
                // Assuming backend returns a unique ID or we use a combination
                const rowsWithId = data.content.map((row, index) => ({
                    ...row,
                    id: row.merchantId || row.mid || index // Fallback ID
                }));
                setSummaries(rowsWithId);
                setTotalElements(data.totalElements);
            }
        } catch (error) { console.error("Failed to fetch merchant summaries", error); }
        finally { setLoading(false); }
    };

    const handleExport = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const params = new URLSearchParams();
            params.append('year', year);
            params.append('month', month);
            params.append('day', day);

            // Using existing fetch logic, could be improved with snackbar notification
            const res = await fetch(`/api/analytics/merchant-summaries/export?${params.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });

            if (res.ok) {
                const blob = await res.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `Merchant_Summary_${year}-${month}-${day}.csv`;
                document.body.appendChild(a);
                a.click();
                a.remove();
            } else { console.error("Export request failed"); }
        } catch (error) { console.error("Export failed", error); }
    };

    const columns = [
        {
            field: 'merchantName',
            headerName: 'Merchant',
            flex: 1.5,
            minWidth: 200,
            renderCell: (params) => (
                <Box>
                    <Typography variant="subtitle2" fontWeight="bold" noWrap>
                        {params.value}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                        {params.row.mid}
                    </Typography>
                </Box>
            )
        },
        { field: 'salesUserId', headerName: 'Sales User', flex: 1, minWidth: 150 },
        {
            field: 'creditVolume',
            headerName: 'Credit Vol',
            type: 'number',
            flex: 1,
            minWidth: 120,
            align: 'right',
            headerAlign: 'right',
            renderCell: (params) => formatCurrency(params.value)
        },
        {
            field: 'debitVolume', // mapped from debitPrepaidVolume in JSX? No, wait. 
            // In original JSX: `s.debitPrepaidVolume`
            headerName: 'Debit Vol',
            type: 'number',
            flex: 1,
            align: 'right',
            headerAlign: 'right',
            valueGetter: (value, row) => row.debitPrepaidVolume, // v6 syntax: (value, row)
            renderCell: (params) => formatCurrency(params.value)
        },
        {
            field: 'dailyVolume',
            headerName: 'Daily Total',
            type: 'number',
            flex: 1,
            minWidth: 130,
            align: 'right',
            headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{
                    bgcolor: 'background.paper',
                    border: '1px solid',
                    borderColor: 'divider',
                    borderRadius: 1,
                    px: 1,
                    py: 0.5,
                    fontWeight: 'bold',
                    display: 'inline-block'
                }}>
                    {formatCurrency(params.value)}
                </Box>
            )
        },
        { field: 'dailyCount', headerName: 'Count', type: 'number', width: 90, align: 'right', headerAlign: 'right' },
        {
            field: 'mtdVolume',
            headerName: 'MTD Volume',
            type: 'number',
            flex: 1,
            align: 'right',
            headerAlign: 'right',
            renderCell: (params) => (
                <Typography color="success.main" fontWeight="bold" variant="body2">
                    {formatCurrency(params.value)}
                </Typography>
            )
        },
        {
            field: 'ytdVolume',
            headerName: 'YTD Volume',
            type: 'number',
            flex: 1,
            align: 'right',
            headerAlign: 'right',
            renderCell: (params) => (
                <Typography color="warning.main" fontWeight="bold" variant="body2">
                    {formatCurrency(params.value)}
                </Typography>
            )
        }
    ];

    return (
        <Box sx={{ p: 4, bgcolor: 'background.default', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>

            {/* Header */}
            <Paper sx={{ p: 2, mb: 3, borderRadius: 3, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 2 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                    <Box sx={{ bgcolor: 'secondary.main', p: 1, borderRadius: 2, color: 'white', display: 'flex' }}>
                        <LayoutGrid size={24} />
                    </Box>
                    <Box>
                        <Typography variant="h6" fontWeight="bold">Merchant Performance</Typography>
                        <Typography variant="caption" color="text.secondary">Daily & Monthly Volume Analytics</Typography>
                    </Box>
                </Box>
                <Box sx={{ display: 'flex', gap: 2 }}>
                    <Button variant="text" startIcon={<RotateCcw size={16} />} onClick={fetchSummaries} color="inherit">
                        Refresh
                    </Button>
                    <Button variant="contained" startIcon={<Download size={16} />} onClick={handleExport} color="secondary" disableElevation>
                        Export CSV
                    </Button>
                </Box>
            </Paper>

            {/* Filters */}
            <Paper sx={{ p: 3, mb: 3, borderRadius: 3 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                    <Calendar size={18} className="text-indigo-500" />
                    <Typography variant="subtitle2" fontWeight="bold" color="primary">REFERENCE DATE SELECTION</Typography>
                </Box>
                <Grid container spacing={2} alignItems="center">
                    <Grid item>
                        <FormControl size="small" sx={{ minWidth: 120 }}>
                            <InputLabel>Year</InputLabel>
                            <Select value={year} label="Year" onChange={(e) => setYear(e.target.value)}>
                                {years.map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                            </Select>
                        </FormControl>
                    </Grid>
                    <Grid item>
                        <FormControl size="small" sx={{ minWidth: 150 }}>
                            <InputLabel>Month</InputLabel>
                            <Select value={month} label="Month" onChange={(e) => setMonth(e.target.value)}>
                                {months.map(m => <MenuItem key={m} value={m}>{new Date(0, m - 1).toLocaleString('default', { month: 'long' })}</MenuItem>)}
                            </Select>
                        </FormControl>
                    </Grid>
                    <Grid item>
                        <FormControl size="small" sx={{ minWidth: 100 }}>
                            <InputLabel>Day</InputLabel>
                            <Select value={day} label="Day" onChange={(e) => setDay(e.target.value)}>
                                {days.map(d => <MenuItem key={d} value={d}>{d}</MenuItem>)}
                            </Select>
                        </FormControl>
                    </Grid>
                    <Grid item xs>
                        <Typography variant="body2" color="text.secondary" align="right">
                            Viewing snapshot for <b>{new Date(year, month - 1, day).toDateString()}</b>
                        </Typography>
                    </Grid>
                </Grid>
            </Paper>

            {/* DataGrid */}
            <Paper sx={{ flex: 1, width: '100%', borderRadius: 3, overflow: 'hidden', minHeight: 500 }}>
                <DataGrid
                    rows={summaries}
                    columns={columns}
                    rowCount={totalElements}
                    loading={loading}
                    pageSizeOptions={[20, 50, 100]}
                    paginationModel={paginationModel}
                    paginationMode="server"
                    onPaginationModelChange={setPaginationModel}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{
                        toolbar: {
                            showQuickFilter: true,
                            quickFilterProps: { debounceMs: 500 },
                        },
                    }}
                    sx={{
                        border: 'none',
                        '& .MuiDataGrid-cell:focus': { outline: 'none' },
                    }}
                />
            </Paper>
        </Box>
    );
};

export default MerchantSummary;
