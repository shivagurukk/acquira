import React, { useState, useEffect } from 'react';
import {
    Box,
    Paper,
    Typography,
    Avatar,
    Chip,
    IconButton,
    Stack,
    Tooltip,
    ThemeProvider,
    createTheme,
    CssBaseline,
    InputBase
} from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import {
    TrendingUp,
    TrendingDown,
    MoreHorizontal,
    Search,
    Calendar,
    ChevronDown,
    Filter
} from 'lucide-react';
import BusinessFilters from '../../components/BusinessFilters';

// --- Dark Theme Configuration ---
const darkTheme = createTheme({
    palette: {
        mode: 'dark',
        background: {
            default: '#0F172A', // Slate 900
            paper: '#111827',   // Gray 900
        },
        text: {
            primary: '#F8FAFC', // Slate 50
            secondary: '#94A3B8', // Slate 400
        },
        primary: {
            main: '#3B82F6', // Blue 500
        },
        success: {
            main: '#22C55E', // Green 500
        },
        error: {
            main: '#EF4444', // Red 500
        },
        warning: {
            main: '#F59E0B', // Amber 500
        },
        divider: '#1F2937', // Gray 800
    },
    typography: {
        fontFamily: "'Inter', sans-serif",
    },
    components: {
        MuiPaper: {
            styleOverrides: {
                root: {
                    backgroundImage: 'none',
                }
            }
        },
        MuiChip: {
            styleOverrides: {
                root: {
                    fontWeight: 600,
                }
            }
        }
    }
});

// --- Sparkline Component ---
const Sparkline = ({ data, color }) => {
    if (!data || data.length === 0) return null;
    const height = 24;
    const width = 80;
    const max = Math.max(...data, 1);
    const min = 0;

    const points = data.map((val, idx) => {
        const x = (idx / (data.length - 1)) * width;
        const y = height - ((val - min) / (max - min)) * height;
        return `${x},${y}`;
    }).join(' ');

    return (
        <svg width={width} height={height} style={{ overflow: 'visible' }}>
            <polyline
                points={points}
                fill="none"
                stroke={color}
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
            />
        </svg>
    );
};

// --- Main Component ---
const DailyMerchantDashboard = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({
        year: new Date().getFullYear(),
        month: new Date().getMonth() + 1,
    });

    useEffect(() => {
        fetchDashboardData();
    }, [filters.year, filters.month]);

    const fetchDashboardData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const res = await fetch(`/api/business/daily-merchant-dashboard?month=${filters.month}&year=${filters.year}`, {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (res.ok) {
                const result = await res.json();
                const rows = result.map((r, i) => ({
                    id: r.merchantId || i,
                    ...r,
                    // Mock sparkline data (random visual)
                    sparklineData: Array.from({ length: 15 }, () => Math.floor(Math.random() * 1000) + 100)
                }));
                setData(rows);
            }
        } catch (error) {
            console.error("Failed to fetch data", error);
        } finally {
            setLoading(false);
        }
    };

    const daysInMonth = new Date(filters.year, filters.month, 0).getDate();

    // Helper to format currency
    const formatCurrency = (val) => new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: 'AED',
        maximumFractionDigits: 0
    }).format(val || 0);

    // Helpers
    const compactNumber = (val) => new Intl.NumberFormat('en-US', {
        notation: "compact",
        maximumFractionDigits: 1
    }).format(val);

    // --- Dynamic Heatmap Columns ---
    const dayColumns = Array.from({ length: daysInMonth }, (_, i) => i + 1).map(day => ({
        field: `day_${day}`,
        headerName: `${day}`,
        width: 36,
        align: 'center',
        headerAlign: 'center',
        renderCell: (params) => {
            const val = params.row.dailyVolumes ? params.row.dailyVolumes[day] : 0;
            // Opacity based on value intensity (mock logic: max ~10k)
            const opacity = Math.min(val / 5000, 1);

            return (
                <Tooltip title={val ? `AED ${val.toLocaleString()}` : 'No Volume'} arrow>
                    <Box sx={{
                        width: '100%',
                        height: '100%',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        bgcolor: val > 0 ? `rgba(59, 130, 246, ${Math.max(opacity, 0.1)})` : 'transparent', // Blue Heatmap
                        borderRadius: 0.5,
                        m: 0.5
                    }}>
                        {val > 0 && (
                            <Typography variant="caption" sx={{ fontSize: '0.65rem', color: opacity > 0.6 ? 'white' : '#94A3B8', fontWeight: 600 }}>
                                {compactNumber(val)}
                            </Typography>
                        )}
                    </Box>
                </Tooltip>
            );
        }
    }));

    const columns = [
        {
            field: 'merchantName',
            headerName: 'MERCHANT',
            width: 240,
            pinned: 'left',
            renderCell: (params) => (
                <Stack direction="row" spacing={1.5} alignItems="center" height="100%">
                    <Avatar
                        sx={{
                            width: 32,
                            height: 32,
                            bgcolor: '#374151', // Gray 700
                            color: '#F8FAFC',
                            fontWeight: 700,
                            fontSize: '0.85rem',
                            border: '1px solid #4B5563'
                        }}
                    >
                        {params.value.charAt(0)}
                    </Avatar>
                    <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" fontWeight="600" color="text.primary" noWrap>
                            {params.value}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" fontFamily="monospace">
                            {params.row.mid}
                        </Typography>
                    </Box>
                </Stack>
            )
        },
        {
            field: 'status',
            headerName: 'STATUS',
            width: 100,
            renderCell: (params) => {
                const status = params.row.stabilityLabel || 'Stable';
                const map = {
                    'Stable': { color: '#22C55E', bg: 'rgba(34, 197, 94, 0.1)', border: 'rgba(34, 197, 94, 0.2)' },
                    'Risk': { color: '#EF4444', bg: 'rgba(239, 68, 68, 0.1)', border: 'rgba(239, 68, 68, 0.2)' },
                    'Watch': { color: '#F59E0B', bg: 'rgba(245, 158, 11, 0.1)', border: 'rgba(245, 158, 11, 0.2)' }
                };
                const s = map[status] || map['Stable'];

                return (
                    <Box sx={{
                        bgcolor: s.bg,
                        color: s.color,
                        border: `1px solid ${s.border}`,
                        px: 1,
                        py: 0.25,
                        borderRadius: 10,
                        fontSize: '0.7rem',
                        fontWeight: 700,
                        display: 'inline-block'
                    }}>
                        {status.toUpperCase()}
                    </Box>
                );
            }
        },
        {
            field: 'todayVol',
            headerName: 'TODAY', // Sticky header name for today
            width: 120,
            align: 'right', // Align right for numbers
            headerAlign: 'right', // Align header right
            renderCell: (params) => (
                <Stack alignItems="flex-end">
                    <Typography fontWeight="700" fontSize="0.9rem" color="white">
                        {formatCurrency(params.value)}
                    </Typography>
                    {params.row.trendPct !== undefined && (
                        <Stack direction="row" alignItems="center" spacing={0.5}>
                            {params.row.trendPct >= 0 ?
                                <TrendingUp size={12} color="#22C55E" /> :
                                <TrendingDown size={12} color="#EF4444" />
                            }
                            <Typography
                                variant="caption"
                                fontWeight="600"
                                color={params.row.trendPct >= 0 ? '#22C55E' : '#EF4444'}
                            >
                                {Math.abs(params.row.trendPct).toFixed(0)}%
                            </Typography>
                        </Stack>
                    )}
                </Stack>
            )
        },
        {
            field: 'trend',
            headerName: 'TREND',
            width: 100,
            renderCell: (params) => (
                <Sparkline
                    data={params.row.sparklineData}
                    color={params.row.trendPct >= 0 ? '#22C55E' : '#EF4444'}
                />
            )
        },
        {
            field: 'totalVolume',
            headerName: 'TOTAL',
            width: 120,
            align: 'right',
            headerAlign: 'right',
            renderCell: (params) => (
                <Typography fontWeight="700" color="white">
                    {formatCurrency(params.value)}
                </Typography>
            )
        },
        ...dayColumns,
        {
            field: 'actions',
            headerName: '',
            width: 50,
            renderCell: () => <IconButton size="small" sx={{ color: '#64748B' }}><MoreHorizontal size={16} /></IconButton>
        }
    ];

    return (
        <ThemeProvider theme={darkTheme}>
            <CssBaseline />
            <Box sx={{
                minHeight: '100vh',
                bgcolor: 'background.default',
                color: 'text.primary',
                p: 3,
                display: 'flex',
                flexDirection: 'column',
                gap: 3
            }}>
                {/* Header Section */}
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Box>
                        <Stack direction="row" alignItems="center" spacing={2}>
                            <Typography variant="h5" fontWeight="700" letterSpacing="-0.02em">
                                Merchant Analytics
                            </Typography>
                            <Chip label="Live" size="small" color="success" sx={{ height: 20, fontSize: '0.7rem', fontWeight: 700 }} />
                        </Stack>
                        <Typography variant="body2" color="text.secondary" mt={0.5}>
                            Performance across {data.length} merchants for {new Date(0, filters.month - 1).toLocaleString('default', { month: 'long' })} {filters.year}
                        </Typography>
                    </Box>
                    <Stack direction="row" spacing={2} alignItems="center">
                        {/* Search */}
                        <Paper sx={{
                            p: '2px 4px',
                            display: 'flex',
                            alignItems: 'center',
                            bgcolor: '#1F2937',
                            border: '1px solid #374151',
                            borderRadius: 2,
                            width: 240
                        }}>
                            <IconButton sx={{ p: '10px', color: '#94A3B8' }} aria-label="search">
                                <Search size={18} />
                            </IconButton>
                            <InputBase
                                sx={{ ml: 1, flex: 1, color: 'white', fontSize: '0.9rem' }}
                                placeholder="Search..."
                            />
                        </Paper>

                        {/* Date Toggle */}
                        <Box sx={{ bgcolor: '#1F2937', borderRadius: 2, border: '1px solid #374151', p: 0.5 }}>
                            {['Today', '7D', '30D'].map((d) => (
                                <Box
                                    key={d}
                                    component="span"
                                    sx={{
                                        px: 2,
                                        py: 0.5,
                                        borderRadius: 1.5,
                                        cursor: 'pointer',
                                        fontSize: '0.85rem',
                                        fontWeight: 600,
                                        bgcolor: d === '30D' ? '#374151' : 'transparent',
                                        color: d === '30D' ? 'white' : '#94A3B8',
                                        '&:hover': { color: 'white' }
                                    }}
                                >
                                    {d}
                                </Box>
                            ))}
                        </Box>
                    </Stack>
                </Stack>

                {/* Main Table Card */}
                <Paper sx={{
                    bgcolor: '#111827',
                    borderRadius: 3,
                    border: '1px solid #374151',
                    overflow: 'hidden',
                    height: 'calc(100vh - 160px)'
                }}>
                    <DataGrid
                        rows={data}
                        columns={columns}
                        loading={loading}
                        disableRowSelectionOnClick
                        getRowClassName={(params) => `row-status-${params.row.stabilityLabel || 'Stable'}`}
                        // Styling
                        sx={{
                            border: 'none',
                            color: '#F8FAFC', // Default text color

                            // Headers
                            '& .MuiDataGrid-columnHeaders': {
                                bgcolor: '#111827',
                                borderBottom: '1px solid #374151',
                                minHeight: '50px !important',
                            },
                            '& .MuiDataGrid-columnHeader': {
                                bgcolor: '#111827', // Match bg
                                color: '#94A3B8', // Muted text for headers
                                fontWeight: 600,
                                textTransform: 'uppercase',
                                fontSize: '0.75rem',
                                letterSpacing: '0.05em',
                            },
                            '& .MuiDataGrid-columnHeaderTitle': {
                                fontWeight: 700,
                                color: '#F8FAFC'
                            },

                            // Rows & Cells
                            '& .MuiDataGrid-row': {
                                borderBottom: '1px solid #1F2937', // Darker divider
                                '&:hover': {
                                    backgroundColor: '#1F2937 !important', // Hover highlight
                                },
                            },
                            '& .MuiDataGrid-cell': {
                                borderBottom: 'none',
                                display: 'flex',
                                alignItems: 'center',
                            },

                            // Sticky Column Styling
                            '& .MuiDataGrid-columnHeader--pinnedLeft': {
                                bgcolor: '#111827', // Ensure opaque bg for sticky
                                boxShadow: '2px 0 5px rgba(0,0,0,0.2)' // Shadow separator
                            },
                            '& .MuiDataGrid-cell--pinnedLeft': {
                                bgcolor: '#111827', // Match row bg
                                backgroundImage: 'linear-gradient(to right, #111827, #111827)', // Hack to cover transparent
                                boxShadow: '2px 0 5px rgba(0,0,0,0.2)'
                            },

                            // Scrollbars
                            '& ::-webkit-scrollbar': { width: 8, height: 8 },
                            '& ::-webkit-scrollbar-track': { background: '#111827' },
                            '& ::-webkit-scrollbar-thumb': { background: '#374151', borderRadius: 4 },
                            '& ::-webkit-scrollbar-thumb:hover': { background: '#4B5563' },

                            // Footer
                            '& .MuiDataGrid-footerContainer': {
                                borderTop: '1px solid #374151',
                                bgcolor: '#111827'
                            }
                        }}
                    />
                </Paper>

                <BusinessFilters
                    filters={filters}
                    onChange={(newFilters) => setFilters(prev => ({ ...prev, ...newFilters }))}
                    onApply={fetchDashboardData}
                    isOpen={showFilters}
                    onClose={() => setShowFilters(false)}
                />
            </Box>
        </ThemeProvider>
    );
};

export default DailyMerchantDashboard;
