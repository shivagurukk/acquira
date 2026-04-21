import React, { useState, useEffect, useCallback } from 'react';
import {
    Box, Typography, Paper, Grid, TextField, Button, Chip,
    Card, CardContent, useTheme
} from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import {
    Search, Download, RefreshCw, Shield,
    CheckCircle, XCircle, Filter
} from 'lucide-react';
import api from '../../api/axios';

/* ────────── helpers ────────── */

/** Safely parse any date value Jackson might send (ISO string, array, epoch) */
const safeParseDateValue = (value) => {
    if (!value) return null;
    // ISO string: "2026-02-24T10:30:15"
    if (typeof value === 'string') {
        const d = new Date(value);
        return isNaN(d.getTime()) ? null : d;
    }
    // Jackson array: [2026,2,24,10,30,15,123456789]
    if (Array.isArray(value)) {
        const [y, mo, d, h = 0, m = 0, s = 0] = value;
        const date = new Date(y, mo - 1, d, h, m, s);
        return isNaN(date.getTime()) ? null : date;
    }
    // Epoch millis
    if (typeof value === 'number') {
        const d = new Date(value);
        return isNaN(d.getTime()) ? null : d;
    }
    return null;
};

const formatTimestamp = (value) => {
    const d = safeParseDateValue(value);
    if (!d) return '—';
    const mo = d.toLocaleString('en-US', { month: 'short' });
    const day = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const ss = String(d.getSeconds()).padStart(2, '0');
    return `${mo} ${day}, ${hh}:${mm}:${ss}`;
};

const categoryColorMap = {
    AUTH: 'primary',
    ADMIN: 'error',
    DATA: 'success',
    EXPORT: 'info',
    BATCH: 'warning',
    AI: 'secondary',
    BUSINESS: 'default',
};

/* ────────── component ────────── */

const AuditLogViewer = () => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';

    const [logs, setLogs] = useState([]);
    const [loading, setLoading] = useState(false);
    const [stats, setStats] = useState({ totalToday: 0, errorRate: 0 });
    const [filters, setFilters] = useState({
        search: '',
        category: '',
        action: '',
        username: '',
        startDate: '',
        endDate: ''
    });
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 20 });
    const [rowCount, setRowCount] = useState(0);

    const categories = ['AUTH', 'ADMIN', 'DATA', 'EXPORT', 'BATCH', 'AI', 'BUSINESS'];

    const fetchLogs = useCallback(async () => {
        setLoading(true);
        try {
            const params = {
                page: paginationModel.page,
                size: paginationModel.pageSize,
            };
            // Only add non-empty filters
            Object.entries(filters).forEach(([key, val]) => {
                if (val) params[key] = val;
            });

            const response = await api.get('/admin/audit-logs', { params });
            const data = response.data;
            setLogs(data.content || []);
            setRowCount(data.totalElements || 0);
        } catch (error) {
            console.error('Failed to fetch audit logs', error);
            setLogs([]);
        } finally {
            setLoading(false);
        }
    }, [paginationModel.page, paginationModel.pageSize, filters]);

    const fetchStats = async () => {
        try {
            const res = await api.get('/admin/audit-logs/stats');
            setStats(res.data || { totalToday: 0, errorRate: 0 });
        } catch (e) {
            console.error('Stats fetch failed', e);
        }
    };

    useEffect(() => {
        fetchLogs();
    }, [fetchLogs]);

    useEffect(() => {
        fetchStats();
    }, []);

    const handleExport = async () => {
        try {
            const params = {};
            Object.entries(filters).forEach(([key, val]) => {
                if (val) params[key] = val;
            });
            const response = await api.get('/admin/audit-logs/export', {
                params,
                responseType: 'blob'
            });
            const now = new Date();
            const ts = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}`;
            const url = window.URL.createObjectURL(new Blob([response.data]));
            const link = document.createElement('a');
            link.href = url;
            link.setAttribute('download', `audit_logs_${ts}.csv`);
            document.body.appendChild(link);
            link.click();
            link.remove();
            window.URL.revokeObjectURL(url);
        } catch (error) {
            console.error('Export failed', error);
        }
    };

    const handleComplianceExport = async () => {
        try {
            const params = {};
            Object.entries(filters).forEach(([key, val]) => {
                if (val) params[key] = val;
            });
            // If no dates set, default to last 30 days
            if (!params.startDate) {
                const d = new Date();
                d.setDate(d.getDate() - 30);
                params.startDate = d.toISOString();
            }
            if (!params.endDate) {
                params.endDate = new Date().toISOString();
            }

            const response = await api.get('/admin/audit-logs/export', { params, responseType: 'blob' });
            const csvText = await response.data.text();
            const lines = csvText.trim().split('\n');
            const totalEvents = lines.length - 1;

            // Build compliance report as formatted text
            const now = new Date();
            const reportTitle = 'COMPLIANCE AUDIT TRAIL REPORT';
            const separator = '='.repeat(60);
            let report = `${separator}\n${reportTitle}\n${separator}\n\n`;
            report += `Generated: ${now.toLocaleString()}\n`;
            report += `Period: ${params.startDate?.split('T')[0] || 'All'} to ${params.endDate?.split('T')[0] || 'All'}\n`;
            report += `Total Events: ${totalEvents}\n`;
            report += `Filters Applied: ${Object.entries(params).filter(([k,v]) => v && k !== 'startDate' && k !== 'endDate').map(([k,v]) => `${k}=${v}`).join(', ') || 'None'}\n\n`;

            // Category breakdown
            const catCounts = {};
            const userCounts = {};
            const statusCounts = { success: 0, error: 0 };
            lines.slice(1).forEach(line => {
                const parts = line.split(',');
                const cat = (parts[2] || '').trim();
                const user = (parts[1] || '').trim();
                const status = parseInt(parts[5] || '0');
                if (cat) catCounts[cat] = (catCounts[cat] || 0) + 1;
                if (user) userCounts[user] = (userCounts[user] || 0) + 1;
                if (status >= 200 && status < 400) statusCounts.success++;
                else if (status >= 400) statusCounts.error++;
            });

            report += `${'-'.repeat(40)}\nSUMMARY BY CATEGORY\n${'-'.repeat(40)}\n`;
            Object.entries(catCounts).sort((a,b) => b[1] - a[1]).forEach(([cat, count]) => {
                report += `  ${cat.padEnd(20)} ${String(count).padStart(6)}\n`;
            });

            report += `\n${'-'.repeat(40)}\nSUMMARY BY USER\n${'-'.repeat(40)}\n`;
            Object.entries(userCounts).sort((a,b) => b[1] - a[1]).forEach(([user, count]) => {
                report += `  ${user.padEnd(20)} ${String(count).padStart(6)}\n`;
            });

            report += `\n${'-'.repeat(40)}\nSTATUS SUMMARY\n${'-'.repeat(40)}\n`;
            report += `  Successful (2xx-3xx)  ${String(statusCounts.success).padStart(6)}\n`;
            report += `  Errors (4xx-5xx)      ${String(statusCounts.error).padStart(6)}\n`;

            report += `\n${separator}\nDETAILED LOG\n${separator}\n\n`;
            report += csvText;

            const blob = new Blob([report], { type: 'text/plain' });
            const url = window.URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            const ts = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`;
            link.setAttribute('download', `compliance_report_${ts}.txt`);
            document.body.appendChild(link);
            link.click();
            link.remove();
            window.URL.revokeObjectURL(url);
        } catch (error) {
            console.error('Compliance export failed', error);
        }
    };

    const handleFilterApply = () => {
        setPaginationModel(prev => ({ ...prev, page: 0 }));
        fetchLogs();
    };

    const handleFilterReset = () => {
        const cleared = { search: '', category: '', action: '', username: '', startDate: '', endDate: '' };
        setFilters(cleared);
        setPaginationModel(prev => ({ ...prev, page: 0 }));
    };

    /* ────────── columns ────────── */

    const columns = [
        {
            field: 'eventTime',
            headerName: 'Timestamp',
            width: 180,
            valueGetter: (value) => formatTimestamp(value),
        },
        { field: 'username', headerName: 'User', width: 120 },
        {
            field: 'category',
            headerName: 'Category',
            width: 120,
            renderCell: (params) => {
                const cat = params.value || 'N/A';
                return (
                    <Chip
                        label={cat}
                        size="small"
                        color={categoryColorMap[cat] || 'default'}
                        variant="outlined"
                    />
                );
            }
        },
        { field: 'actionType', headerName: 'Action', width: 160, flex: 1 },
        { field: 'endpoint', headerName: 'Endpoint', width: 220 },
        {
            field: 'statusCode',
            headerName: 'Status',
            width: 100,
            renderCell: (params) => {
                const code = params.value;
                if (code == null) return '—';
                const ok = code >= 200 && code < 300;
                return (
                    <Box display="flex" alignItems="center" gap={0.5}>
                        {ok
                            ? <CheckCircle size={16} color="green" />
                            : <XCircle size={16} color="red" />
                        }
                        {code}
                    </Box>
                );
            }
        },
        { field: 'ipAddress', headerName: 'IP Address', width: 130 },
        {
            field: 'duration',
            headerName: 'Time (ms)',
            width: 100,
            valueGetter: (value) =>
                value != null ? `${value}ms` : '—',
        }
    ];

    /* ────────── render ────────── */

    return (
        <Box p={3}>
            {/* Header */}
            <Typography variant="h4" gutterBottom sx={{ display: 'flex', alignItems: 'center', gap: 2, fontWeight: 'bold' }}>
                <Shield size={32} color={isDark ? '#90caf9' : '#1976d2'} />
                Audit Logs & Security Trail
            </Typography>

            {/* Stats */}
            <Grid container spacing={3} mb={3}>
                <Grid item xs={12} md={3}>
                    <Card sx={{ bgcolor: isDark ? 'rgba(25,118,210,0.15)' : '#e3f2fd' }}>
                        <CardContent>
                            <Typography color="textSecondary" gutterBottom>Total Events Today</Typography>
                            <Typography variant="h4">{stats.totalToday || 0}</Typography>
                        </CardContent>
                    </Card>
                </Grid>
            </Grid>

            {/* Filters */}
            <Paper sx={{ p: 2, mb: 3 }}>
                <Grid container spacing={2} alignItems="center">
                    <Grid item xs={12} md={3}>
                        <TextField
                            label="Search Logs"
                            fullWidth
                            size="small"
                            value={filters.search}
                            onChange={(e) => setFilters(f => ({ ...f, search: e.target.value }))}
                            InputProps={{
                                startAdornment: <Search size={18} style={{ marginRight: 8, color: '#666' }} />
                            }}
                            onKeyDown={(e) => e.key === 'Enter' && handleFilterApply()}
                        />
                    </Grid>
                    <Grid item xs={6} md={2}>
                        <TextField
                            select
                            label="Category"
                            fullWidth
                            size="small"
                            value={filters.category}
                            onChange={(e) => setFilters(f => ({ ...f, category: e.target.value }))}
                            SelectProps={{ native: true }}
                        >
                            <option value="">All Categories</option>
                            {categories.map(c => <option key={c} value={c}>{c}</option>)}
                        </TextField>
                    </Grid>
                    <Grid item xs={6} md={2}>
                        <TextField
                            label="User"
                            fullWidth
                            size="small"
                            value={filters.username}
                            onChange={(e) => setFilters(f => ({ ...f, username: e.target.value }))}
                            onKeyDown={(e) => e.key === 'Enter' && handleFilterApply()}
                        />
                    </Grid>
                    <Grid item xs={6} md={2}>
                        <TextField
                            type="date"
                            label="From Date"
                            fullWidth
                            size="small"
                            value={filters.startDate ? filters.startDate.split('T')[0] : ''}
                            onChange={(e) => setFilters(f => ({ ...f, startDate: e.target.value ? e.target.value + 'T00:00:00' : '' }))}
                            InputLabelProps={{ shrink: true }}
                        />
                    </Grid>
                    <Grid item xs={6} md={2}>
                        <TextField
                            type="date"
                            label="To Date"
                            fullWidth
                            size="small"
                            value={filters.endDate ? filters.endDate.split('T')[0] : ''}
                            onChange={(e) => setFilters(f => ({ ...f, endDate: e.target.value ? e.target.value + 'T23:59:59' : '' }))}
                            InputLabelProps={{ shrink: true }}
                        />
                    </Grid>
                    <Grid item xs={12} md={5} display="flex" gap={2} justifyContent="flex-end">
                        <Button
                            variant="contained"
                            startIcon={<Filter size={18} />}
                            onClick={handleFilterApply}
                        >
                            Filter
                        </Button>
                        <Button
                            variant="outlined"
                            startIcon={<RefreshCw size={18} />}
                            onClick={handleFilterReset}
                        >
                            Reset
                        </Button>
                        <Button
                            variant="contained"
                            color="success"
                            startIcon={<Download size={18} />}
                            onClick={handleExport}
                        >
                            Export CSV
                        </Button>
                        <Button
                            variant="contained"
                            color="info"
                            startIcon={<Download size={18} />}
                            onClick={handleComplianceExport}
                        >
                            Compliance Report
                        </Button>
                    </Grid>
                </Grid>
            </Paper>

            {/* Data Grid */}
            <Paper sx={{ height: 600, width: '100%' }}>
                <DataGrid
                    rows={logs}
                    getRowId={(row) => row.logId}
                    columns={columns}
                    loading={loading}
                    paginationMode="server"
                    rowCount={rowCount}
                    paginationModel={paginationModel}
                    onPaginationModelChange={setPaginationModel}
                    pageSizeOptions={[20, 50, 100]}
                    disableRowSelectionOnClick
                    sx={{
                        '& .MuiDataGrid-cell': { fontSize: '0.85rem' },
                        '& .MuiDataGrid-columnHeader': { fontWeight: 'bold' },
                    }}
                />
            </Paper>
        </Box>
    );
};

export default AuditLogViewer;
