import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import api from '../../api/axios';
import { Box, Paper, Typography, Avatar, Stack, Tooltip, MenuItem, Select, FormControl, Chip, Card, CardContent, Autocomplete, TextField, Button } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { TrendingUp, TrendingDown, Calendar, Users, DollarSign, Activity } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import SkeletonLoader from '../../components/SkeletonLoader';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

// formatCurrency is now built from the tenant's currency via useAuth + createFmt (see inside component)
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const SmoothSparkline = ({ data, color }) => {
    if (!data || data.length < 2) return null;
    const height = 32, width = 100;
    const max = Math.max(...data, 1);
    const min = Math.min(...data, 0);
    const range = max - min || 1;
    const points = data.map((val, idx) => ({
        x: (idx / (data.length - 1)) * width,
        y: height - ((val - min) / range) * height,
    }));
    const pathD = points.reduce((acc, point, i, a) => {
        if (i === 0) return `M ${point.x},${point.y}`;
        const prev = a[i - 1];
        const cp1x = prev.x + (point.x - prev.x) / 2;
        const cp2x = prev.x + (point.x - prev.x) / 2;
        return `${acc} C ${cp1x},${prev.y} ${cp2x},${point.y} ${point.x},${point.y}`;
    }, '');
    const fillPath = `${pathD} L ${width},${height} L 0,${height} Z`;
    const gradientId = `grad-${Math.random().toString(36).substr(2, 9)}`;
    return (
        <svg width={width} height={height} style={{ overflow: 'visible' }}>
            <defs>
                <linearGradient id={gradientId} x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity={0.3} />
                    <stop offset="100%" stopColor={color} stopOpacity={0.0} />
                </linearGradient>
            </defs>
            <path d={fillPath} fill={`url(#${gradientId})`} />
            <path d={pathD} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" />
        </svg>
    );
};

const StatCard = ({ title, value, icon: Icon, color, subtitle }) => (
    <Card sx={{ flex: 1, minWidth: 200, borderRadius: 3, boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', border: '1px solid rgba(0,0,0,0.05)' }}>
        <CardContent sx={{ p: 3, '&:last-child': { pb: 3 } }}>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                <Box>
                    <Typography variant="overline" fontWeight="700" color="text.secondary" letterSpacing={1}>{title}</Typography>
                    <Typography variant="h4" fontWeight="800" sx={{ mt: 1, mb: 1, color: '#1e293b' }}>{value}</Typography>
                    {subtitle && <Typography variant="body2" fontWeight="600" color={color}>{subtitle}</Typography>}
                </Box>
                <Avatar sx={{ bgcolor: `${color}15`, color: color, width: 48, height: 48, borderRadius: 2 }}><Icon size={24} /></Avatar>
            </Stack>
        </CardContent>
    </Card>
);

const DailyMerchantDashboard = () => {
    const { currencySymbol } = useAuth();
    const formatCurrency = useMemo(() => createFmt(currencySymbol).currency, [currencySymbol]);
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [filterOptions, setFilterOptions] = useState({ sids: [], mids: [] });
    // Default to the CURRENT calendar month. The user can switch to last month
    // via a quick button, or pick any month/year. If the chosen month has no
    // data we surface a "jump to latest available" button instead of silently
    // walking backwards (which used to land on a random old month like Apr 2025).
    const _now = new Date();
    const [filters, setFilters] = useState({
        year: _now.getFullYear(),
        month: _now.getMonth() + 1,
        // BusinessFilters drawer fields. Inline midList/sidList still take
        // precedence; the drawer's are merged in the request body.
        sidList: [],
        midList: [],
        partnerList: [], rmList: [], teamLeaderList: [], mccList: [],
        merchantName: '',
        // Card-level filters — the backend silently ignores these for this
        // dashboard (see DailyMerchantDashboardController), but the drawer still
        // shows them for visual consistency with other screens.
        schemeList: [], cardTypeList: [], destinationList: [], channelList: [],
        industryList: [], sectorList: [], terminalTypeList: [],
        startDate: '', endDate: '',
    });
    const [showFilters, setShowFilters] = useState(false);
    // The latest month that actually has data (from /data-bounds), held as
    // { year, month } so we can offer a "jump to latest" button when the
    // selected month is empty. Null until the bounds call returns.
    const [latestAvailable, setLatestAvailable] = useState(null);

    // Discover the latest month that has data — used ONLY to offer a jump button
    // when the current selection is empty. It no longer changes the default month.
    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const res = await api.get('/business/data-bounds');
                if (!cancelled && res.data?.latest) {
                    const [y, m] = res.data.latest.split('-');
                    setLatestAvailable({ year: Number(y), month: Number(m) });
                }
            } catch (e) {
                console.error('data-bounds fetch failed (non-fatal)', e);
            }
        })();
        return () => { cancelled = true; };
    }, []);

    useEffect(() => { fetchFilterOptions(); }, []);
    useEffect(() => {
        fetchDashboardData();
    }, [filters.year, filters.month, filters.sidList, filters.midList,
        filters.partnerList, filters.rmList, filters.teamLeaderList, filters.mccList,
        filters.merchantName]);

    const fetchFilterOptions = async () => {
        try {
            const res = await api.get('/business/filter-options');
            setFilterOptions({
                sids: (res.data.sids || []).map(s => String(s)),
                mids: (res.data.mids || []).map(s => String(s)),
            });
        } catch (e) { console.error(e); }
    };

    const fetchDashboardData = async () => {
        setLoading(true);
        try {
            // POST to the filtered endpoint so we can send the full drawer filter
            // shape. Year/month stay as query params; everything else goes in body.
            const body = {
                ...filters,
                year: undefined, month: undefined,
                startDate: null, endDate: null,
            };
            const res = await api.post(
                `/business/daily-merchant-dashboard-filtered?year=${filters.year}&month=${filters.month}`,
                body
            );
            const result = res.data;
            // FIX: removed Math.random fake sparkline fallback. If backend doesn't
            // provide sparklineData, leave it undefined and let the chart render
            // empty rather than show meaningless random numbers.
            setData(result.map((r, i) => ({
                id: r.merchantId || i, ...r,
                sparklineData: r.sparklineData || []
            })));
        } catch (error) { console.error("Failed to fetch data", error); }
        finally { setLoading(false); }
    };

    const daysInMonth = new Date(filters.year, filters.month, 0).getDate();
    const monthName = new Date(0, filters.month - 1).toLocaleString('default', { month: 'long' });

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.totalVolume || d.totalMtd || 0), 0);
        const totalToday = data.reduce((s, d) => s + (d.todayVol || d.todayVolume || 0), 0);
        const growing = data.filter(d => (d.trendPct || 0) >= 0).length;
        return [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: '#6366f1' },
            { title: 'Month Volume', value: formatCurrency(totalVol), icon: DollarSign, color: '#3b82f6' },
            { title: 'Today Volume', value: formatCurrency(totalToday), icon: Activity, color: '#10b981' },
            { title: 'Performance', value: `${growing}/${data.length}`, icon: TrendingUp, color: '#f59e0b', subtitle: `${data.length > 0 ? ((growing / data.length) * 100).toFixed(0) : 0}% Growing` },
        ];
    }, [data]);

    // Quick-select helpers for the month bar.
    const _today = new Date();
    const thisMonth = { year: _today.getFullYear(), month: _today.getMonth() + 1 };
    const _lm = new Date(_today.getFullYear(), _today.getMonth() - 1, 1);
    const lastMonth = { year: _lm.getFullYear(), month: _lm.getMonth() + 1 };
    const isSelected = (sel) => filters.year === sel.year && filters.month === sel.month;
    const selectMonth = (sel) => setFilters(prev => ({ ...prev, year: sel.year, month: sel.month }));

    const quickBtnSx = (active) => ({
        height: 40, px: 2, borderRadius: 2, textTransform: 'none', fontWeight: 700,
        fontSize: '0.8rem', boxShadow: '0 1px 2px rgba(0,0,0,0.05)',
        bgcolor: active ? '#6366f1' : 'white',
        color: active ? 'white' : '#475569',
        border: '1px solid', borderColor: active ? '#6366f1' : '#e2e8f0',
        '&:hover': { bgcolor: active ? '#4f46e5' : '#f8fafc' },
    });

    const extraControls = (
        <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
            <Stack direction="row" spacing={1} alignItems="center">
                <Button disableElevation variant="contained" sx={quickBtnSx(isSelected(thisMonth))}
                    onClick={() => selectMonth(thisMonth)}>This Month</Button>
                <Button disableElevation variant="contained" sx={quickBtnSx(isSelected(lastMonth))}
                    onClick={() => selectMonth(lastMonth)}>Last Month</Button>
            </Stack>
            <FormControl size="small" variant="outlined">
                <Select value={filters.month} onChange={(e) => setFilters(prev => ({ ...prev, month: Number(e.target.value) }))}
                    sx={{ borderRadius: 2, height: 40, bgcolor: 'white', fontWeight: 600, '& .MuiOutlinedInput-notchedOutline': { borderColor: 'transparent' }, boxShadow: '0 1px 2px rgba(0,0,0,0.05)' }}>
                    {Array.from({ length: 12 }, (_, i) => <MenuItem key={i + 1} value={i + 1}>{new Date(0, i).toLocaleString('default', { month: 'long' })}</MenuItem>)}
                </Select>
            </FormControl>
            <FormControl size="small" variant="outlined">
                <Select value={filters.year} onChange={(e) => setFilters(prev => ({ ...prev, year: Number(e.target.value) }))}
                    sx={{ borderRadius: 2, height: 40, bgcolor: 'white', fontWeight: 600, '& .MuiOutlinedInput-notchedOutline': { borderColor: 'transparent' }, boxShadow: '0 1px 2px rgba(0,0,0,0.05)' }}>
                    {[2024, 2025, 2026].map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                </Select>
            </FormControl>
            <Autocomplete
                multiple freeSolo size="small"
                options={filterOptions.sids} value={filters.sidList}
                onChange={(e, val) => setFilters(prev => ({ ...prev, sidList: val }))}
                renderInput={(params) => <TextField {...params} label="SID" placeholder={filters.sidList.length ? '' : 'All'} sx={{ minWidth: 180 }} />}
                renderTags={(value, getTagProps) =>
                    value.map((option, index) => <Chip {...getTagProps({ index })} key={option} label={option} size="small" sx={{ bgcolor: '#6366f1', color: 'white', fontWeight: 600, '& .MuiChip-deleteIcon': { color: 'white', opacity: 0.7 } }} />)
                }
            />
            <Autocomplete
                multiple freeSolo size="small"
                options={filterOptions.mids} value={filters.midList}
                onChange={(e, val) => setFilters(prev => ({ ...prev, midList: val }))}
                renderInput={(params) => <TextField {...params} label="MID" placeholder={filters.midList.length ? '' : 'All'} sx={{ minWidth: 180 }} />}
                renderTags={(value, getTagProps) =>
                    value.map((option, index) => <Chip {...getTagProps({ index })} key={option} label={option} size="small" sx={{ bgcolor: '#10b981', color: 'white', fontWeight: 600, '& .MuiChip-deleteIcon': { color: 'white', opacity: 0.7 } }} />)
                }
            />
        </Stack>
    );

    const dayColumns = Array.from({ length: daysInMonth }, (_, i) => i + 1).map(day => ({
        field: `day_${day}`, headerName: `${day}`, width: 48, align: 'center', headerAlign: 'center',
        renderCell: (params) => {
            const val = params.row.dailyVolumes ? params.row.dailyVolumes[day] : 0;
            const opacity = Math.min(val / 5000, 1);
            return (
                <Tooltip title={val ? `Day ${day}: ${formatCurrency(val)}` : 'No Volume'} arrow>
                    <Box sx={{
                        width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center',
                        bgcolor: val > 0 ? `rgba(59, 130, 246, ${Math.max(opacity, 0.1)})` : 'transparent',
                        borderRadius: 1.5, transition: 'all 0.2s', '&:hover': { transform: 'scale(1.1)', boxShadow: 2 }
                    }}>
                        {val > 0 && <Typography variant="caption" sx={{ fontSize: '0.6rem', color: opacity > 0.6 ? 'white' : '#1e40af', fontWeight: 800 }}>{formatCompact(val)}</Typography>}
                    </Box>
                </Tooltip>
            );
        }
    }));

    const columns = [
        {
            field: 'merchantName', headerName: 'MERCHANT', width: 240,
            renderCell: (params) => (
                <Stack direction="row" spacing={2} alignItems="center" height="100%">
                    <Avatar sx={{ width: 36, height: 36, bgcolor: 'white', color: '#6366f1', fontWeight: 800, fontSize: '0.9rem', border: '1px solid #e0e7ff', boxShadow: '0 2px 4px rgba(99, 102, 241, 0.1)' }}>
                        {params.value?.charAt(0) || '?'}
                    </Avatar>
                    <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" fontWeight="700" color="#1e293b" noWrap>{params.value}</Typography>
                        <Typography variant="caption" color="#64748b" fontFamily="monospace" sx={{ fontSize: '0.7rem', display: 'block', mt: 0.2 }}>
                            {params.row.mid}
                        </Typography>
                    </Box>
                </Stack>
            )
        },
        {
            field: 'sid', headerName: 'SID', width: 130,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: '"Roboto Mono", monospace', fontSize: '12px', color: '#475569', bgcolor: '#f1f5f9', px: 1, py: 0.5, borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                    {params.value || '-'}
                </Typography>
            )
        },
        {
            field: 'mid', headerName: 'MID', width: 130,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: '"Roboto Mono", monospace', fontSize: '12px', color: '#475569', bgcolor: '#f1f5f9', px: 1, py: 0.5, borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                    {params.value || '-'}
                </Typography>
            )
        },
        {
            field: 'status', headerName: 'STATUS', width: 100, align: 'center', headerAlign: 'center',
            renderCell: (params) => {
                const status = params.row.uiStatus || params.row.stabilityLabel || 'Stable';
                const colors = { 'Stable': { color: 'success', label: 'Stable' }, 'Risk': { color: 'error', label: 'At Risk' }, 'Watch': { color: 'warning', label: 'Watch' } };
                const config = colors[status] || colors['Stable'];
                return <Chip label={config.label} size="small" color={config.color} sx={{ fontWeight: 700, fontSize: '0.7rem', height: 24 }} />;
            }
        },
        {
            field: 'todayVol', headerName: 'TODAY', width: 130, align: 'right', headerAlign: 'right',
            valueGetter: (value, row) => row.todayVol ?? row.todayVolume ?? 0,
            renderCell: (params) => (
                <Stack alignItems="flex-end" justifyContent="center" height="100%">
                    <Typography fontWeight="800" fontSize="0.9rem" color="#1e293b">{formatCurrency(params.value)}</Typography>
                    {params.row.trendPct !== undefined && params.row.trendPct !== 0 && (
                        <Chip
                            icon={params.row.trendPct >= 0 ? <TrendingUp size={12} /> : <TrendingDown size={12} />}
                            label={`${Math.abs(params.row.trendPct).toFixed(0)}%`} size="small"
                            sx={{ height: 20, fontSize: '0.65rem', fontWeight: 700,
                                bgcolor: params.row.trendPct >= 0 ? 'rgba(16, 185, 129, 0.1)' : 'rgba(239, 68, 68, 0.1)',
                                color: params.row.trendPct >= 0 ? '#059669' : '#dc2626', '& .MuiChip-icon': { color: 'inherit' } }} />
                    )}
                </Stack>
            )
        },
        {
            field: 'trend', headerName: 'TREND (7D)', width: 140,
            renderCell: (params) => (
                <Box sx={{ width: '100%', height: '100%', display: 'flex', alignItems: 'center', px: 1 }}>
                    <SmoothSparkline data={params.row.sparklineData} color={params.row.trendPct >= 0 ? '#10b981' : '#f43f5e'} />
                </Box>
            )
        },
        {
            field: 'totalVolume', headerName: 'MONTH TOTAL', width: 130, align: 'right', headerAlign: 'right',
            valueGetter: (value, row) => row.totalVolume ?? row.totalMtd ?? 0,
            renderCell: (params) => <Typography fontWeight="800" color="#3b82f6" fontSize="0.95rem">{formatCurrency(params.value)}</Typography>
        },
        ...dayColumns,
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Daily Merchant Dashboard"
                subtitle={`Tracking performance across ${data.length} merchants for ${monthName} ${filters.year}`}
                icon={Calendar}
                onExport={() => exportToCSV(data, 'daily_merchant_dashboard')}
                onRunReport={fetchDashboardData} loading={loading} hideDatePresets
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(s => !s)}
                filters={filters}
                onFilterChange={(patch) => setFilters(prev => ({ ...prev, ...patch }))}
            >
                {extraControls}
            </PremiumReportHeader>

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={fetchDashboardData}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            {loading ? (
                <Box mb={4}><SkeletonLoader variant="kpi-row" count={4} /></Box>
            ) : (
                <Stack direction="row" spacing={3} mb={4}>
                    {kpis.map((kpi, idx) => <StatCard key={idx} {...kpi} />)}
                </Stack>
            )}

            {!loading && data.length === 0 && (
                <Paper sx={{ p: 3, mb: 3, borderRadius: 3, border: '1px solid #fde68a', bgcolor: '#fffbeb',
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap' }}>
                    <Stack direction="row" spacing={1.5} alignItems="center">
                        <Calendar size={20} color="#d97706" />
                        <Typography variant="body2" fontWeight={600} color="#92400e">
                            No data for {monthName} {filters.year}.
                            {latestAvailable && ` Latest available data is ${new Date(0, latestAvailable.month - 1).toLocaleString('default', { month: 'long' })} ${latestAvailable.year}.`}
                        </Typography>
                    </Stack>
                    {latestAvailable && !(latestAvailable.year === filters.year && latestAvailable.month === filters.month) && (
                        <Button disableElevation variant="contained"
                            sx={{ textTransform: 'none', fontWeight: 700, borderRadius: 2, bgcolor: '#d97706', '&:hover': { bgcolor: '#b45309' } }}
                            onClick={() => selectMonth(latestAvailable)}>
                            Jump to {new Date(0, latestAvailable.month - 1).toLocaleString('default', { month: 'short' })} {latestAvailable.year}
                        </Button>
                    )}
                </Paper>
            )}

            <Paper sx={{ ...premiumTableWrapper, borderRadius: 3, border: 'none', boxShadow: '0 10px 15px -3px rgba(0,0,0,0.05)' }}>
                <DataGrid
                    rows={data} columns={columns} loading={loading} disableRowSelectionOnClick
                    rowHeight={64} columnHeaderHeight={50}
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 }, printOptions: { disableToolbarButton: true } } }}
                    sx={{ ...premiumDataGridStyles,
                        '& .MuiDataGrid-columnHeaders': { bgcolor: '#f8fafc', borderBottom: '1px solid #e2e8f0', fontSize: '0.75rem' },
                        '& .MuiDataGrid-row': { '&:hover': { bgcolor: '#ffffff !important', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)' }, transition: 'box-shadow 0.2s' }
                    }}
                />
            </Paper>
        </Box>
    );
};

export default DailyMerchantDashboard;
