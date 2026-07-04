import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, FormControl, InputLabel, Select, MenuItem, Grid, Button } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { Download, LayoutGrid, RotateCcw, Calendar, Inbox } from 'lucide-react';
import { formatCurrency } from '../utils/formatters';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../theme/dataGridStyles';
import { useAuth } from '../contexts/AuthContext';

// ─── Local design tokens ─────────────────────────────────────────
// Every colour routes through a CSS variable with a light-mode fallback so the
// page adapts cleanly under html.dark + ThemeContext. Replaces the old MUI
// palette references (secondary.main / success.main / warning.main) that did
// not follow the app's dark theme.
const T = {
    card:     'var(--bg-card, #ffffff)',
    subtle:   'var(--bg-subtle, #f8fafc)',
    border:   'var(--border, #e2e8f0)',
    borderLt: 'var(--border-light, #eef2f7)',
    text:     'var(--text, #0f172a)',
    textSec:  'var(--text-secondary, #64748b)',
    textMut:  'var(--text-muted, #94a3b8)',
    brand:    'var(--brand, #2563eb)',
    success:  'var(--success-text, #166534)',
    warning:  'var(--warning-text, #92400e)',
    radiusLg: 'var(--radius-lg, 14px)',
    radiusMd: 'var(--radius-md, 10px)',
};

const MerchantSummary = () => {
    // tenantVersion bumps on every super-admin tenant switch; this grid is
    // tenant-scoped (X-Tenant-Id header) so we re-fetch on a switch, else the
    // previous tenant's rows linger (a cross-tenant leak to the viewer).
    const { currencySymbol, tenantVersion } = useAuth();
    // formatCurrency reads the tenant currency from the shared module default,
    // which AuthContext keeps in sync — currencySymbol is pulled in only so the
    // memo re-runs when the active tenant's currency changes.
    const fmtCurrency = useMemo(() => (v) => formatCurrency(v), [currencySymbol]);

    const [summaries, setSummaries] = useState([]);
    const [loading, setLoading] = useState(false);

    // Date filters
    const [year, setYear] = useState(new Date().getFullYear());
    const [month, setMonth] = useState(new Date().getMonth() + 1);
    const [day, setDay] = useState(new Date().getDate());

    // Pagination (server-side)
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 20 });
    const [totalElements, setTotalElements] = useState(0);

    const years = Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - i);
    const months = Array.from({ length: 12 }, (_, i) => i + 1);
    const days = Array.from({ length: 31 }, (_, i) => i + 1);

    useEffect(() => {
        fetchSummaries();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [paginationModel, year, month, day, tenantVersion]);

    const fetchSummaries = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            // FIX: the whole app scopes on `defaultTenantId` (axios interceptor +
            // every dashboard). This page previously read the legacy `tenantId`
            // key, so after a tenant switch it could query the wrong tenant.
            const tenantId = localStorage.getItem('defaultTenantId') || localStorage.getItem('tenantId');
            const params = new URLSearchParams();
            params.append('year', year);
            params.append('month', month);
            params.append('day', day);
            params.append('page', paginationModel.page);
            params.append('size', paginationModel.pageSize);

            const res = await fetch(`/api/analytics/merchant-summaries?${params.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
            });

            if (res.ok) {
                const data = await res.json();
                const rowsWithId = (data.content || []).map((row, index) => ({
                    ...row,
                    id: row.merchantId || row.mid || index
                }));
                setSummaries(rowsWithId);
                setTotalElements(data.totalElements || 0);
            } else {
                setSummaries([]);
                setTotalElements(0);
            }
        } catch (error) {
            console.error("Failed to fetch merchant summaries", error);
            setSummaries([]);
            setTotalElements(0);
        } finally { setLoading(false); }
    };

    const handleExport = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId') || localStorage.getItem('tenantId');
            const params = new URLSearchParams();
            params.append('year', year);
            params.append('month', month);
            params.append('day', day);

            const res = await fetch(`/api/analytics/merchant-summaries/export?${params.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
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

    const columns = useMemo(() => [
        {
            field: 'merchantName', headerName: 'MERCHANT', flex: 1.5, minWidth: 200,
            renderCell: (params) => (
                <Box sx={{ lineHeight: 1.3 }}>
                    <Typography variant="body2" fontWeight={600} color={T.text} noWrap>{params.value}</Typography>
                    <Typography variant="caption" sx={{ color: T.textMut, fontFamily: 'ui-monospace, monospace' }}>{params.row.mid}</Typography>
                </Box>
            )
        },
        { field: 'salesUserId', headerName: 'SALES USER', flex: 1, minWidth: 150,
            renderCell: (params) => <Typography variant="body2" color={T.textSec}>{params.value || '—'}</Typography> },
        {
            field: 'creditVolume', headerName: 'CREDIT VOL', type: 'number', flex: 1, minWidth: 120, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color={T.textSec} sx={{ fontVariantNumeric: 'tabular-nums' }}>{fmtCurrency(params.value)}</Typography>
        },
        {
            field: 'debitVolume', headerName: 'DEBIT VOL', type: 'number', flex: 1, minWidth: 120, align: 'right', headerAlign: 'right',
            valueGetter: (value, row) => row.debitPrepaidVolume,
            renderCell: (params) => <Typography variant="body2" color={T.textSec} sx={{ fontVariantNumeric: 'tabular-nums' }}>{fmtCurrency(params.value)}</Typography>
        },
        {
            field: 'dailyVolume', headerName: 'DAILY TOTAL', type: 'number', flex: 1, minWidth: 140, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{
                    bgcolor: T.subtle, border: `1px solid ${T.border}`, borderRadius: '6px',
                    px: 1, py: 0.35, fontWeight: 700, fontSize: '0.82rem', color: T.text,
                    display: 'inline-block', fontVariantNumeric: 'tabular-nums'
                }}>
                    {fmtCurrency(params.value)}
                </Box>
            )
        },
        { field: 'dailyCount', headerName: 'COUNT', type: 'number', width: 90, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color={T.textSec} sx={{ fontVariantNumeric: 'tabular-nums' }}>{params.value != null ? Number(params.value).toLocaleString('en-US') : '—'}</Typography> },
        {
            field: 'mtdVolume', headerName: 'MTD VOLUME', type: 'number', flex: 1, minWidth: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight={700} sx={{ color: T.success, fontVariantNumeric: 'tabular-nums' }}>{fmtCurrency(params.value)}</Typography>
        },
        {
            field: 'ytdVolume', headerName: 'YTD VOLUME', type: 'number', flex: 1, minWidth: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight={700} sx={{ color: T.warning, fontVariantNumeric: 'tabular-nums' }}>{fmtCurrency(params.value)}</Typography>
        }
    ], [fmtCurrency]);

    const selectSx = {
        '& .MuiOutlinedInput-notchedOutline': { borderColor: T.border },
        '& .MuiInputBase-root': { bgcolor: T.card, color: T.text, borderRadius: T.radiusMd },
        '& .MuiInputLabel-root': { color: T.textMut },
        '& .MuiSvgIcon-root': { color: T.textMut },
    };

    return (
        <Box sx={pageContainer}>

            {/* Header */}
            <Paper elevation={0} sx={{ p: 2, borderRadius: T.radiusLg, border: `1px solid ${T.border}`, bgcolor: T.card, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 2 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                    <Box sx={{ bgcolor: `color-mix(in srgb, ${T.brand} 12%, transparent)`, p: 1, borderRadius: T.radiusMd, color: T.brand, display: 'flex' }}>
                        <LayoutGrid size={22} />
                    </Box>
                    <Box>
                        <Typography variant="h6" fontWeight={800} color={T.text}>Merchant Performance</Typography>
                        <Typography variant="caption" color={T.textSec}>Daily & monthly volume analytics</Typography>
                    </Box>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                    <Button variant="text" startIcon={<RotateCcw size={16} />} onClick={fetchSummaries}
                        sx={{ textTransform: 'none', fontWeight: 600, color: T.textSec }}>
                        Refresh
                    </Button>
                    <Button variant="contained" startIcon={<Download size={16} />} onClick={handleExport} disableElevation
                        sx={{ textTransform: 'none', fontWeight: 600, bgcolor: T.brand, '&:hover': { bgcolor: 'var(--brand-dark, #1d4ed8)' } }}>
                        Export CSV
                    </Button>
                </Box>
            </Paper>

            {/* Filters */}
            <Paper elevation={0} sx={{ p: 3, borderRadius: T.radiusLg, border: `1px solid ${T.border}`, bgcolor: T.card }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                    <Calendar size={16} color="var(--brand, #2563eb)" />
                    <Typography variant="caption" fontWeight={700} sx={{ color: T.textMut, textTransform: 'uppercase', letterSpacing: '0.05em' }}>Reference Date Selection</Typography>
                </Box>
                <Grid container spacing={2} alignItems="center">
                    <Grid item>
                        <FormControl size="small" sx={{ minWidth: 120, ...selectSx }}>
                            <InputLabel>Year</InputLabel>
                            <Select value={year} label="Year" onChange={(e) => setYear(e.target.value)}>
                                {years.map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                            </Select>
                        </FormControl>
                    </Grid>
                    <Grid item>
                        <FormControl size="small" sx={{ minWidth: 150, ...selectSx }}>
                            <InputLabel>Month</InputLabel>
                            <Select value={month} label="Month" onChange={(e) => setMonth(e.target.value)}>
                                {months.map(m => <MenuItem key={m} value={m}>{new Date(0, m - 1).toLocaleString('default', { month: 'long' })}</MenuItem>)}
                            </Select>
                        </FormControl>
                    </Grid>
                    <Grid item>
                        <FormControl size="small" sx={{ minWidth: 100, ...selectSx }}>
                            <InputLabel>Day</InputLabel>
                            <Select value={day} label="Day" onChange={(e) => setDay(e.target.value)}>
                                {days.map(d => <MenuItem key={d} value={d}>{d}</MenuItem>)}
                            </Select>
                        </FormControl>
                    </Grid>
                    <Grid item xs>
                        <Typography variant="body2" sx={{ color: T.textSec }} align="right">
                            Viewing snapshot for <b style={{ color: 'var(--text, #0f172a)' }}>{new Date(year, month - 1, day).toDateString()}</b>
                        </Typography>
                    </Grid>
                </Grid>
            </Paper>

            {/* DataGrid */}
            <Paper elevation={0} sx={{ ...premiumTableWrapper, minHeight: 500 }}>
                <DataGrid
                    rows={summaries}
                    columns={columns}
                    rowCount={totalElements}
                    loading={loading}
                    rowHeight={56}
                    pageSizeOptions={[20, 50, 100]}
                    paginationModel={paginationModel}
                    paginationMode="server"
                    onPaginationModelChange={setPaginationModel}
                    disableRowSelectionOnClick
                    slots={{
                        toolbar: GridToolbar,
                        noRowsOverlay: () => (
                            <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1.5, py: 6 }}>
                                <Box sx={{ width: 48, height: 48, borderRadius: T.radiusMd, bgcolor: T.subtle, color: T.textMut, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                                    <Inbox size={22} />
                                </Box>
                                <Typography variant="body2" fontWeight={600} color={T.text}>No merchant summaries</Typography>
                                <Typography variant="caption" color={T.textMut}>No data for {new Date(year, month - 1, day).toDateString()}. Try another reference date.</Typography>
                            </Box>
                        ),
                    }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default MerchantSummary;
