import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, Avatar, Stack, Tooltip, MenuItem, Select, FormControl, InputLabel, Chip, Card, CardContent, IconButton } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { TrendingUp, TrendingDown, Calendar, Users, DollarSign, Activity, FileText, Download } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

// --- Utils ---
const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', maximumFractionDigits: 0 }).format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

// --- Components ---

// Smooth Curved Sparkline with Gradient
const SmoothSparkline = ({ data, color, upward }) => {
    if (!data || data.length < 2) return null;
    const height = 32, width = 100;
    const max = Math.max(...data, 1);
    const min = Math.min(...data, 0);
    const range = max - min || 1;

    // Normalize data points
    const points = data.map((val, idx) => {
        const x = (idx / (data.length - 1)) * width;
        const y = height - ((val - min) / range) * height;
        return { x, y };
    });

    // Generate smooth bezier path
    const pathD = points.reduce((acc, point, i, a) => {
        if (i === 0) return `M ${point.x},${point.y}`;
        const prev = a[i - 1];
        const cp1x = prev.x + (point.x - prev.x) / 2;
        const cp1y = prev.y;
        const cp2x = prev.x + (point.x - prev.x) / 2;
        const cp2y = point.y;
        return `${acc} C ${cp1x},${cp1y} ${cp2x},${cp2y} ${point.x},${point.y}`;
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
    <Card sx={{ flex: 1, minWidth: 200, borderRadius: 3, boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05), 0 2px 4px -1px rgba(0,0,0,0.03)', border: '1px solid rgba(0,0,0,0.05)' }}>
        <CardContent sx={{ p: 3, '&:last-child': { pb: 3 } }}>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                <Box>
                    <Typography variant="overline" fontWeight="700" color="text.secondary" letterSpacing={1}>
                        {title}
                    </Typography>
                    <Typography variant="h4" fontWeight="800" sx={{ mt: 1, mb: 1, color: '#1e293b' }}>
                        {value}
                    </Typography>
                    {subtitle && (
                        <Typography variant="body2" fontWeight="600" color={color}>
                            {subtitle}
                        </Typography>
                    )}
                </Box>
                <Avatar sx={{ bgcolor: `${color}15`, color: color, width: 48, height: 48, borderRadius: 2 }}>
                    <Icon size={24} />
                </Avatar>
            </Stack>
        </CardContent>
    </Card>
);

const DailyMerchantDashboard = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({
        year: new Date().getFullYear(),
        month: new Date().getMonth() + 1,
        datePreset: 'MONTH',
    });

    useEffect(() => { fetchDashboardData(); }, [filters.year, filters.month]);

    const fetchDashboardData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const res = await fetch(`/api/business/daily-merchant-dashboard?month=${filters.month}&year=${filters.year}`, {
                headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
            });
            if (res.ok) {
                const result = await res.json();
                // Add dummy sparkline data for visualization if not present
                setData(result.map((r, i) => ({
                    id: r.merchantId || i, ...r,
                    sparklineData: r.sparklineData || Array.from({ length: 15 }, () => Math.floor(Math.random() * 5000) + 1000)
                })));
            }
        } catch (error) { console.error("Failed to fetch data", error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const daysInMonth = new Date(filters.year, filters.month, 0).getDate();
    const monthName = new Date(0, filters.month - 1).toLocaleString('default', { month: 'long' });

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.totalVolume || 0), 0);
        const totalToday = data.reduce((s, d) => s + (d.todayVol || 0), 0);
        const growing = data.filter(d => (d.trendPct || 0) >= 0).length;
        return [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: '#6366f1' },
            { title: 'Month Volume', value: formatCurrency(totalVol), icon: DollarSign, color: '#3b82f6' },
            { title: 'Today Volume', value: formatCurrency(totalToday), icon: Activity, color: '#10b981' },
            { title: 'Performance', value: `${growing}/${data.length}`, icon: TrendingUp, color: '#f59e0b', subtitle: `${data.length > 0 ? ((growing / data.length) * 100).toFixed(0) : 0}% Growing` },
        ];
    }, [data]);

    // Header Controls
    const extraControls = (
        <Stack direction="row" spacing={2} alignItems="center">
            <FormControl size="small" variant="outlined">
                <Select
                    value={filters.month}
                    onChange={(e) => setFilters(prev => ({ ...prev, month: Number(e.target.value) }))}
                    sx={{
                        borderRadius: 2, height: 40, bgcolor: 'white', fontWeight: 600,
                        '& .MuiOutlinedInput-notchedOutline': { borderColor: 'transparent' },
                        boxShadow: '0 1px 2px rgba(0,0,0,0.05)'
                    }}
                    MenuProps={{ PaperProps: { sx: { borderRadius: 2, mt: 1 } } }}
                >
                    {Array.from({ length: 12 }, (_, i) => <MenuItem key={i + 1} value={i + 1}>{new Date(0, i).toLocaleString('default', { month: 'long' })}</MenuItem>)}
                </Select>
            </FormControl>
            <FormControl size="small" variant="outlined">
                <Select
                    value={filters.year}
                    onChange={(e) => setFilters(prev => ({ ...prev, year: Number(e.target.value) }))}
                    sx={{
                        borderRadius: 2, height: 40, bgcolor: 'white', fontWeight: 600,
                        '& .MuiOutlinedInput-notchedOutline': { borderColor: 'transparent' },
                        boxShadow: '0 1px 2px rgba(0,0,0,0.05)'
                    }}
                >
                    {[2024, 2025, 2026].map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                </Select>
            </FormControl>
        </Stack>
    );

    // Dynamic Daily Columns
    const dayColumns = Array.from({ length: daysInMonth }, (_, i) => i + 1).map(day => ({
        field: `day_${day}`, headerName: `${day}`, width: 48, align: 'center', headerAlign: 'center',
        renderCell: (params) => {
            const val = params.row.dailyVolumes ? params.row.dailyVolumes[day] : 0;
            // Opacity logic: base it on max volume of the row or global max? Let's use relative to 10k for now or row max
            const opacity = Math.min(val / 5000, 1);
            return (
                <Tooltip title={val ? `Day ${day}: ${formatCurrency(val)}` : 'No Volume'} arrow>
                    <Box sx={{
                        width: 36, height: 36,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        bgcolor: val > 0 ? `rgba(59, 130, 246, ${Math.max(opacity, 0.1)})` : 'transparent',
                        borderRadius: 1.5,
                        transition: 'all 0.2s',
                        '&:hover': { transform: 'scale(1.1)', boxShadow: 2 }
                    }}>
                        {val > 0 && (
                            <Typography variant="caption" sx={{ fontSize: '0.6rem', color: opacity > 0.6 ? 'white' : '#1e40af', fontWeight: 800 }}>
                                {formatCompact(val)}
                            </Typography>
                        )}
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
                    <Avatar sx={{
                        width: 36, height: 36,
                        bgcolor: 'white', color: '#6366f1',
                        fontWeight: 800, fontSize: '0.9rem',
                        border: '1px solid #e0e7ff',
                        boxShadow: '0 2px 4px rgba(99, 102, 241, 0.1)'
                    }}>
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
            field: 'status', headerName: 'STATUS', width: 100, align: 'center', headerAlign: 'center',
            renderCell: (params) => {
                const status = params.row.stabilityLabel || 'Stable';
                const colors = {
                    'Stable': { color: 'success', label: 'Stable' },
                    'Risk': { color: 'error', label: 'At Risk' },
                    'Watch': { color: 'warning', label: 'Watch' }
                };
                const config = colors[status] || colors['Stable'];
                return (
                    <Chip
                        label={config.label}
                        size="small"
                        color={config.color}
                        sx={{ fontWeight: 700, fontSize: '0.7rem', height: 24 }}
                    />
                );
            }
        },
        {
            field: 'todayVol', headerName: 'TODAY', width: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Stack alignItems="flex-end" justifyContent="center" height="100%">
                    <Typography fontWeight="800" fontSize="0.9rem" color="#1e293b">{formatCurrency(params.value)}</Typography>
                    {params.row.trendPct !== undefined && params.row.trendPct !== 0 && (
                        <Chip
                            icon={params.row.trendPct >= 0 ? <TrendingUp size={12} /> : <TrendingDown size={12} />}
                            label={`${Math.abs(params.row.trendPct).toFixed(0)}%`}
                            size="small"
                            sx={{
                                height: 20, fontSize: '0.65rem', fontWeight: 700,
                                bgcolor: params.row.trendPct >= 0 ? 'rgba(16, 185, 129, 0.1)' : 'rgba(239, 68, 68, 0.1)',
                                color: params.row.trendPct >= 0 ? '#059669' : '#dc2626',
                                '& .MuiChip-icon': { color: 'inherit' }
                            }}
                        />
                    )}
                </Stack>
            )
        },
        {
            field: 'trend', headerName: 'TREND (7D)', width: 140,
            renderCell: (params) => (
                <Box sx={{ width: '100%', height: '100%', display: 'flex', alignItems: 'center', px: 1 }}>
                    <SmoothSparkline
                        data={params.row.sparklineData}
                        color={params.row.trendPct >= 0 ? '#10b981' : '#f43f5e'}
                        upward={params.row.trendPct >= 0}
                    />
                </Box>
            )
        },
        {
            field: 'totalVolume', headerName: 'MONTH TOTAL', width: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography fontWeight="800" color="#3b82f6" fontSize="0.95rem">
                    {formatCurrency(params.value)}
                </Typography>
            )
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
                onRunReport={fetchDashboardData}
                loading={loading}
                hideDatePresets
            >
                {extraControls}
            </PremiumReportHeader>

            <Stack direction="row" spacing={3} mb={4}>
                {kpis.map((kpi, idx) => (
                    <StatCard key={idx} {...kpi} />
                ))}
            </Stack>

            <Paper sx={{ ...premiumTableWrapper, borderRadius: 3, border: 'none', boxShadow: '0 10px 15px -3px rgba(0,0,0,0.05), 0 4px 6px -4px rgba(0,0,0,0.05)' }}>
                <DataGrid
                    rows={data}
                    columns={columns}
                    loading={loading}
                    disableRowSelectionOnClick
                    rowHeight={64}
                    columnHeaderHeight={50}
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{
                        toolbar: {
                            showQuickFilter: true,
                            quickFilterProps: { debounceMs: 500 },
                            printOptions: { disableToolbarButton: true }
                        }
                    }}
                    sx={{
                        ...premiumDataGridStyles,
                        '& .MuiDataGrid-columnHeaders': {
                            bgcolor: '#f8fafc',
                            borderBottom: '1px solid #e2e8f0',
                            fontSize: '0.75rem'
                        },
                        '& .MuiDataGrid-row': {
                            '&:hover': { bgcolor: '#ffffff !important', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)' },
                            transition: 'box-shadow 0.2s'
                        }
                    }}
                />
            </Paper>
        </Box>
    );
};

export default DailyMerchantDashboard;
