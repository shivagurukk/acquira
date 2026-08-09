import React, { useState, useEffect, useMemo, useRef } from 'react';
import { Box, Paper, Typography, FormControl, InputLabel, Select, MenuItem, Grid, Button } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { Download, LayoutGrid, RotateCcw, Calendar, Inbox, AlertTriangle } from 'lucide-react';
import { formatCurrency } from '../utils/formatters';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../theme/dataGridStyles';
import { useAuth } from '../contexts/AuthContext';
import api from '../api/axios';

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
    // A failed request used to render exactly like an empty result set —
    // a 500/network drop looked like "no merchants traded that day".
    const [error, setError] = useState(null);

    // Date filters
    const [year, setYear] = useState(new Date().getFullYear());
    const [month, setMonth] = useState(new Date().getMonth() + 1);
    const [day, setDay] = useState(new Date().getDate());

    // Pagination (server-side)
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 20 });
    const [totalElements, setTotalElements] = useState(0);

    // Discard out-of-order responses (year→month rapid changes raced).
    const reqIdRef = useRef(0);

    const years = Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - i);
    const months = Array.from({ length: 12 }, (_, i) => i + 1);
    // Day list clamped to the selected month — "Feb 30" used to be selectable,
    // which made the backend's LocalDate.of() throw and the grid silently blank.
    const daysInMonth = new Date(year, month, 0).getDate();
    const days = Array.from({ length: daysInMonth }, (_, i) => i + 1);

    // If the current day no longer exists in the newly selected month
    // (e.g. Jan 31 → February), snap to the month's last day.
    useEffect(() => {
        if (day > daysInMonth) setDay(daysInMonth);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [daysInMonth]);

    // A date/tenant change resets to page 0 — staying on page 12 of a shorter
    // result set rendered an empty grid captioned "Page 12".
    useEffect(() => {
        setPaginationModel(p => (p.page === 0 ? p : { ...p, page: 0 }));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [year, month, day, tenantVersion]);

    useEffect(() => {
        if (day > daysInMonth) return; // wait for the clamp effect to settle
        fetchSummaries();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [paginationModel, year, month, day, tenantVersion]);

    const fetchSummaries = async () => {
        const reqId = ++reqIdRef.current;
        setLoading(true);
        setError(null);
        try {
            // api/axios owns auth: Authorization + X-Tenant-Id headers and the
            // 401 → refresh-token → retry flow. The old raw fetch bypassed all
            // of that, so an expired token showed a permanently empty grid.
            const res = await api.get('/analytics/merchant-summaries', {
                params: { year, month, day, page: paginationModel.page, size: paginationModel.pageSize },
            });
            if (reqId !== reqIdRef.current) return;
            const data = res.data || {};
            const rowsWithId = (data.content || []).map((row, index) => ({
                ...row,
                // mid is the natural id; the fallback is a string so it can never
                // collide with a real numeric MID on the same page.
                id: row.mid ?? `row-${index}`
            }));
            setSummaries(rowsWithId);
            setTotalElements(data.totalElements || 0);
        } catch (e) {
            if (reqId !== reqIdRef.current) return;
            console.error("Failed to fetch merchant summaries", e);
            setSummaries([]);
            setTotalElements(0);
            setError(e?.response?.data?.error || e?.response?.statusText || 'Could not load merchant summaries.');
        } finally {
            if (reqId === reqIdRef.current) setLoading(false);
        }
    };

    const handleExport = async () => {
        try {
            const res = await api.get('/analytics/merchant-summaries/export', {
                params: { year, month, day },
                responseType: 'blob',
            });
            const url = window.URL.createObjectURL(new Blob([res.data]));
            const a = document.createElement('a');
            a.href = url;
            a.download = `Merchant_Summary_${year}-${month}-${day}.csv`;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(url);
        } catch (e) {
            console.error("Export failed", e);
            setError('Export failed. Please try again.');
        }
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

            {/* Request failure — distinct from a genuinely empty day */}
            {error && (
                <Paper elevation={0} role="alert" sx={{
                    p: 1.5, px: 2, borderRadius: T.radiusMd, display: 'flex', alignItems: 'center', gap: 1.25,
                    border: '1px solid var(--danger-border, #fecaca)', bgcolor: 'var(--danger-bg, #fef2f2)',
                    color: 'var(--danger-text, #991b1b)',
                }}>
                    <AlertTriangle size={15} />
                    <Typography sx={{ fontSize: '0.82rem', fontWeight: 600, color: 'inherit' }}>{error}</Typography>
                    <Button size="small" onClick={fetchSummaries}
                        sx={{ ml: 'auto', textTransform: 'none', fontWeight: 700, color: T.brand }}>Retry</Button>
                </Paper>
            )}

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
                                <Typography variant="body2" fontWeight={600} color={T.text}>
                                    {error ? 'Could not load merchant summaries' : 'No merchant summaries'}
                                </Typography>
                                <Typography variant="caption" color={T.textMut}>
                                    {error ? 'See the error above and retry.' : `No data for ${new Date(year, month - 1, day).toDateString()}. Try another reference date.`}
                                </Typography>
                            </Box>
                        ),
                    }}
                    // Pagination is server-side but the toolbar quick filter and
                    // column sorting were CLIENT-side — they silently operated on
                    // the 20 loaded rows while claiming the full count. Disabled
                    // until the endpoint supports server search/sort. The
                    // toolbar's own CSV button is also disabled: it exported the
                    // current page only, sitting next to the real Export CSV.
                    disableColumnSorting
                    slotProps={{ toolbar: {
                        showQuickFilter: false,
                        csvOptions: { disableToolbarButton: true },
                        printOptions: { disableToolbarButton: true },
                    } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default MerchantSummary;
