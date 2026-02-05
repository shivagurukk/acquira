import React, { useState, useEffect } from 'react';
import {
    Box,
    Paper,
    Typography,
    Grid,
    Autocomplete,
    TextField,
    Button,
    Chip,
    CircularProgress
} from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { DatePicker } from '@mui/x-date-pickers/DatePicker';
import { AdapterDateFns } from '@mui/x-date-pickers/AdapterDateFns';
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider';
import { Search, RotateCcw, Filter } from 'lucide-react';

import { format } from 'date-fns';

const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'decimal', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

const MultiDateSelector = ({ selectedDates, onAdd, onRemove }) => {
    const [tempDate, setTempDate] = useState(null);

    return (
        <Box>
            <DatePicker
                label="Add Specific Date"
                value={tempDate}
                onChange={(newValue) => {
                    if (newValue) {
                        onAdd(newValue);
                        setTempDate(null);
                    }
                }}
                slotProps={{ textField: { size: 'small', fullWidth: true } }}
            />
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mt: 1 }}>
                {selectedDates.map((date, index) => (
                    <Chip
                        key={index}
                        label={date}
                        onDelete={() => onRemove(date)}
                        size="small"
                        variant="outlined"
                    />
                ))}
            </Box>
        </Box>
    );
};

const MerchantAnalyticsReport = () => {
    // --- State ---
    const [loading, setLoading] = useState(false);
    const [data, setData] = useState([]);
    const [totalRows, setTotalRows] = useState(0);
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 20 });

    // Filter Options State (Loaded from API)
    const [options, setOptions] = useState({
        partners: [],
        rms: [],
        mccs: [],
        schemes: [],
        cardTypes: [],
        destinations: [],
        merchants: [], // Searchable?
        channels: [],
        terminalTypes: [] // New
    });

    // Filters State
    const [filters, setFilters] = useState({
        startDate: null,
        endDate: null,
        yearList: [],
        monthList: [],
        preciseDateList: [],
        destinationList: [],
        schemeList: [],
        mccList: [],
        cardTypeList: [],
        merchantName: '', // Simple search for now, could be multi-select if API supported IDs
        partnerList: [],
        rmList: [],
        channelList: [],
        terminalTypeList: [] // New
    });

    // Static Options
    const years = [2023, 2024, 2025, 2026, 2027];
    const months = [
        { label: 'January', value: '01' },
        { label: 'February', value: '02' },
        { label: 'March', value: '03' },
        { label: 'April', value: '04' },
        { label: 'May', value: '05' },
        { label: 'June', value: '06' },
        { label: 'July', value: '07' },
        { label: 'August', value: '08' },
        { label: 'September', value: '09' },
        { label: 'October', value: '10' },
        { label: 'November', value: '11' },
        { label: 'December', value: '12' },
    ];

    // --- Effects ---
    useEffect(() => {
        fetchOptions();
    }, []);

    useEffect(() => {
        fetchReport();
    }, [paginationModel, filters]); // Reload on filter/page change? Or manual apply?
    // User requested "load instantly ... after choosing filters". Usually implies auto-load or FAST load.
    // Let's debounce or use Apply button if heavy? User said "instant", so auto-refresh might be nice if fast.
    // But for multi-selects, it's better to wait for user to finish selecting.
    // Let's use an "Apply Filters" button effectively via manual trigger or effect on specific changes?
    // Current pattern: Auto-load on effect is easiest for verification. We can add debounce if needed.

    const fetchOptions = async () => {
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/filter-options', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (res.ok) {
                const data = await res.json();
                setOptions(data);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const fetchReport = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const body = {
                ...filters,
                // Transform objects to values if needed (Autocompletes usually give values if configured right)
                monthList: filters.monthList.map(m => m.value || m), // Handle object if selected from list
            };

            const res = await fetch(`/api/business/merchant-analytics?page=${paginationModel.page}&size=${paginationModel.pageSize}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                body: JSON.stringify(body)
            });

            if (res.ok) {
                const result = await res.json();
                setData(result.content);
                setTotalRows(result.totalElements);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const handleFilterChange = (key, value) => {
        setFilters(prev => ({ ...prev, [key]: value }));
    };

    const handleAddSpecificDate = (date) => {
        try {
            const dateStr = format(date, 'yyyy-MM-dd');
            if (!filters.preciseDateList.includes(dateStr)) {
                setFilters(prev => ({ ...prev, preciseDateList: [...prev.preciseDateList, dateStr] }));
            }
        } catch (e) {
            console.error("Invalid date", e);
        }
    };

    const handleRemoveSpecificDate = (dateStr) => {
        setFilters(prev => ({ ...prev, preciseDateList: prev.preciseDateList.filter(d => d !== dateStr) }));
    };

    // --- Columns ---
    const columns = [
        { field: 'sid', headerName: 'SID', width: 120 },
        { field: 'terminalType', headerName: 'Terminal Type', width: 140 }, // New Column
        { field: 'mid', headerName: 'MID', width: 150 },
        { field: 'merchantName', headerName: 'Name', width: 200 }, // DBA
        { field: 'volume', headerName: 'Volume', width: 130, type: 'number', valueFormatter: (value) => formatCurrency(value) },
        { field: 'count', headerName: 'Trnx Count', width: 120, type: 'number', valueFormatter: (value) => formatNumber(value) },
        { field: 'msf', headerName: 'MSF', width: 120, type: 'number', valueFormatter: (value) => formatCurrency(value) },
        { field: 'interchange', headerName: 'Interchange', width: 120, type: 'number', valueFormatter: (value) => formatCurrency(value) },
        { field: 'mcc', headerName: 'MCC', width: 90 },
        { field: 'industry', headerName: 'Industry', width: 150 },
        { field: 'legalName', headerName: 'Merchant', width: 220 }, // Legal Name
        { field: 'dccOptin', headerName: 'Dcc Optin', width: 120, type: 'number', valueFormatter: (value) => formatCurrency(value) },
    ];

    return (
        <LocalizationProvider dateAdapter={AdapterDateFns}>
            <Box sx={{ p: 3, bgcolor: '#F8FAFC', minHeight: '100vh', display: 'flex', flexDirection: 'column', gap: 3 }}>

                {/* Header */}
                <Box>
                    <Typography variant="h4" fontWeight="800" color="#0F172A">Merchant Analytics Report</Typography>
                    <Typography variant="body2" color="#64748B">Detailed performance metrics with advanced filtering</Typography>
                </Box>

                {/* Filters Section */}
                <Paper sx={{ p: 3, borderRadius: '16px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)' }}>
                    <Grid container spacing={2} alignItems="center">
                        <Grid item xs={12}>
                            <Box display="flex" alignItems="center" gap={1} mb={2}>
                                <Filter size={20} color="#64748B" />
                                <Typography variant="h6" fontWeight="600" color="#334155">Filters</Typography>
                            </Box>
                        </Grid>

                        {/* Date Multi Select */}
                        {/* Note: Standard DatePicker isn't multi-select. We can use Autocomplete for precise dates or multiple pickers.
                            User asked for "date should be multiselect". Implementing as Autocomplete string input or just precise date list logic?
                            Better: "Specific Dates" Autocomplete effectively allows picking multiple dates if we implement custom tag rendering? 
                            Or simpler: Just standard Date Range + Year/Month List.
                            Let's assume Year/Month multiselect covers most needs, and provide granular Date Range. 
                            Implementing "Date" as a multi-select of specific dates is rare/clunky. 
                            Let's stick to Year/Month + Date Range, and maybe a "Dates" autocomplete if strictly needed.
                            Actually, let's just do Year and Month as requested. */}


                        <Grid item xs={12} md={3}>
                            <DatePicker
                                label="Start Date"
                                value={filters.startDate}
                                onChange={(newValue) => handleFilterChange('startDate', newValue)}
                                slotProps={{ textField: { size: 'small', fullWidth: true } }}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <DatePicker
                                label="End Date"
                                value={filters.endDate}
                                onChange={(newValue) => handleFilterChange('endDate', newValue)}
                                slotProps={{ textField: { size: 'small', fullWidth: true } }}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <MultiDateSelector
                                selectedDates={filters.preciseDateList}
                                onAdd={handleAddSpecificDate}
                                onRemove={handleRemoveSpecificDate}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <Autocomplete
                                multiple
                                options={years}
                                getOptionLabel={(option) => option.toString()}
                                value={filters.yearList}
                                onChange={(e, v) => handleFilterChange('yearList', v)}
                                renderInput={(params) => <TextField {...params} label="Years" size="small" />}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <Autocomplete
                                multiple
                                options={months} // Objects {label, value}
                                getOptionLabel={(option) => option.label || option}
                                value={filters.monthList}
                                isOptionEqualToValue={(opt, val) => opt.value === val.value || opt.value === val}
                                onChange={(e, v) => handleFilterChange('monthList', v)}
                                renderInput={(params) => <TextField {...params} label="Months" size="small" />}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <Autocomplete
                                multiple
                                options={options.destinations || []}
                                value={filters.destinationList}
                                onChange={(e, v) => handleFilterChange('destinationList', v)}
                                renderInput={(params) => <TextField {...params} label="Destination" size="small" />}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <Autocomplete
                                multiple
                                options={options.schemes || []}
                                value={filters.schemeList}
                                onChange={(e, v) => handleFilterChange('schemeList', v)}
                                renderInput={(params) => <TextField {...params} label="Scheme" size="small" />}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <Autocomplete
                                multiple
                                options={options.mccs || []}
                                value={filters.mccList}
                                onChange={(e, v) => handleFilterChange('mccList', v)}
                                renderInput={(params) => <TextField {...params} label="MCC" size="small" />}
                            />
                        </Grid>

                        {/* Terminal Type Filter (New) */}
                        <Grid item xs={12} md={3}>
                            <Autocomplete
                                multiple
                                options={options.terminalTypes || []}
                                value={filters.terminalTypeList}
                                onChange={(e, v) => handleFilterChange('terminalTypeList', v)}
                                renderInput={(params) => <TextField {...params} label="Terminal Type" size="small" />}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <Autocomplete
                                multiple
                                options={options.cardTypes || []}
                                value={filters.cardTypeList}
                                onChange={(e, v) => handleFilterChange('cardTypeList', v)}
                                renderInput={(params) => <TextField {...params} label="Card Type" size="small" />}
                            />
                        </Grid>

                        <Grid item xs={12} md={3}>
                            <TextField
                                fullWidth
                                label="Merchant Name / MID"
                                size="small"
                                value={filters.merchantName}
                                onChange={(e) => handleFilterChange('merchantName', e.target.value)}
                            />
                        </Grid>

                        <Grid item xs={12} md={3} display="flex" gap={1}>
                            <Button
                                variant="contained"
                                fullWidth
                                startIcon={<Search size={18} />}
                                onClick={fetchReport}
                                sx={{ bgcolor: '#0F172A', '&:hover': { bgcolor: '#334155' } }}
                            >
                                Apply
                            </Button>
                            <Button
                                variant="outlined"
                                onClick={() => setFilters({
                                    startDate: null, endDate: null, yearList: [], monthList: [],
                                    preciseDateList: [], destinationList: [], schemeList: [],
                                    mccList: [], cardTypeList: [], merchantName: '', partnerList: [], rmList: []
                                })}
                            >
                                <RotateCcw size={18} />
                            </Button>
                        </Grid>

                    </Grid>
                </Paper>

                {/* Data Grid */}
                <Paper sx={{ flex: 1, width: '100%', borderRadius: '16px', overflow: 'hidden', border: '1px solid #E2E8F0' }}>
                    <DataGrid
                        rows={data}
                        columns={columns}
                        getRowId={(row) => `${row.mid}-${row.sid}`} // Unique ID
                        rowCount={totalRows}
                        loading={loading}
                        paginationModel={paginationModel}
                        paginationMode="server"
                        onPaginationModelChange={setPaginationModel}
                        slots={{ toolbar: GridToolbar }}
                        sx={{
                            border: 'none',
                            '& .MuiDataGrid-columnHeaders': {
                                bgcolor: '#F8FAFC',
                                color: '#64748B',
                                fontWeight: 700,
                            },
                            '& .MuiDataGrid-virtualScroller': {
                                bgcolor: '#FFFFFF',
                            }
                        }}
                    />
                </Paper>

            </Box>
        </LocalizationProvider>
    );
};

class ErrorBoundary extends React.Component {
    constructor(props) {
        super(props);
        this.state = { hasError: false, error: null, errorInfo: null };
    }

    static getDerivedStateFromError(error) {
        return { hasError: true };
    }

    componentDidCatch(error, errorInfo) {
        this.setState({ error, errorInfo });
        console.error("Uncaught error:", error, errorInfo);
    }

    render() {
        if (this.state.hasError) {
            return (
                <Box p={4}>
                    <Typography variant="h4" color="error" gutterBottom>Something went wrong</Typography>
                    <Paper sx={{ p: 3, bgcolor: '#FFF1F2', color: '#BE123C' }}>
                        <Typography variant="h6" fontFamily="monospace">{this.state.error?.toString()}</Typography>
                        <pre style={{ overflow: 'auto', maxHeight: '400px' }}>
                            {this.state.errorInfo?.componentStack}
                        </pre>
                    </Paper>
                </Box>
            );
        }

        return this.props.children;
    }
}

export default function WrappedMerchantAnalyticsReport() {
    return (
        <ErrorBoundary>
            <MerchantAnalyticsReport />
        </ErrorBoundary>
    );
}
