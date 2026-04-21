import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, Stack, Button, Autocomplete, TextField, CircularProgress, Grid, Divider, Chip } from '@mui/material';
import { Search, TrendingUp, DollarSign, Hash, Calendar, PieChart, BarChart3, ArrowRight } from 'lucide-react';
import { merchantApi } from '../../api/merchants';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, PieChart as RPieChart, Pie, Cell, LineChart, Line } from 'recharts';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import Loader from '../../components/Loader';
import { premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const COLORS = ['#4361ee', '#00b37e', '#ff9f1c', '#ef476f'];

const MerchantComparison = () => {
    const [selectedMerchants, setSelectedMerchants] = useState([]);
    const [options, setOptions] = useState([]); // For autocomplete
    const [loadingOptions, setLoadingOptions] = useState(false);

    // Default date range: Last 30 days
    const [dateRange, setDateRange] = useState({
        startDate: new Date(new Date().setDate(new Date().getDate() - 30)).toISOString().split('T')[0],
        endDate: new Date().toISOString().split('T')[0]
    });

    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(false);

    // Initial load of merchants for dropdown
    useEffect(() => {
        const loadInitialMerchants = async () => {
            try {
                const res = await merchantApi.search('');
                // Ensure unique merchants by ID
                const unique = Array.from(new Map(res.map(m => [m.merchantId, m])).values());
                setOptions(unique);
            } catch (e) { console.error("Failed to load merchants", e); }
        };
        loadInitialMerchants();
    }, []);

    const handleSearch = async (query) => {
        if (!query) return;
        setLoadingOptions(true);
        try {
            const res = await merchantApi.search(query);
            // Merge with existing selected to avoid losing selection
            const unique = Array.from(new Map([...options, ...res].map(m => [m.merchantId, m])).values());
            setOptions(unique);
        } catch (e) {
            console.error("Search failed", e);
        } finally {
            setLoadingOptions(false);
        }
    };

    const runComparison = async () => {
        if (selectedMerchants.length < 2) return;
        setLoading(true);
        try {
            const ids = selectedMerchants.map(m => m.merchantId);
            const res = await merchantApi.compare(ids, dateRange.startDate, dateRange.endDate);
            setData(res);
        } catch (e) {
            console.error("Comparison failed", e);
        } finally {
            setLoading(false);
        }
    };

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', minimumFractionDigits: 0 }).format(val || 0);
    const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

    // Helper to get delta color/icon
    const getDelta = (kpi, mid) => {
        if (!data || !data.comparison || !data.comparison.leaders) return null;
        const leaderId = data.comparison.leaders[kpi];
        const isLeader = leaderId === mid;
        return { isLeader, delta: data.comparison.deltas[kpi] };
    };

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Comparison"
                subtitle="Side-by-side performance analysis"
                icon={BarChart3}
                hideDatePresets
            />

            {/* Controls */}
            <Paper sx={{ p: 2, mb: 3, borderRadius: '12px', border: '1px solid rgba(0,0,0,0.06)' }} elevation={0}>
                <Grid container spacing={2} alignItems="center">
                    <Grid item xs={12} md={6}>
                        <Autocomplete
                            multiple
                            options={options}
                            getOptionLabel={(option) => option.mid ? `${option.mid} — ${option.name}` : option.name}
                            isOptionEqualToValue={(option, value) => option.merchantId === value.merchantId}
                            filterSelectedOptions
                            loading={loadingOptions}
                            value={selectedMerchants}
                            onChange={(e, val) => {
                                if (val.length <= 3) setSelectedMerchants(val);
                            }}
                            onInputChange={(e, val, reason) => { if (reason === 'input') handleSearch(val); }}
                            renderInput={(params) => (
                                <TextField
                                    {...params}
                                    label="Select Merchants (Max 3)"
                                    placeholder="Search by Name or MID"
                                    size="small"
                                />
                            )}
                            renderOption={(props, option) => (
                                <li {...props} key={option.merchantId}>
                                    <Box>
                                        <Typography variant="body2" fontWeight="600">{option.name}</Typography>
                                        <Typography variant="caption" color="text.secondary">
                                            MID: <strong>{option.mid || 'N/A'}</strong>{option.city ? ` | ${option.city}` : ''} | {option.status}
                                        </Typography>
                                    </Box>
                                </li>
                            )}
                            renderTags={(tagValue, getTagProps) =>
                                tagValue.map((option, index) => (
                                    <Chip
                                        {...getTagProps({ index })}
                                        key={option.merchantId}
                                        label={option.mid ? `${option.mid} — ${option.name}` : option.name}
                                        size="small"
                                        variant="outlined"
                                        sx={{ fontWeight: 600 }}
                                    />
                                ))
                            }
                        />
                    </Grid>
                    <Grid item xs={12} md={4}>
                        <Stack direction="row" spacing={1} alignItems="center">
                            <TextField
                                type="date"
                                label="From"
                                size="small"
                                value={dateRange.startDate}
                                onChange={(e) => setDateRange(prev => ({ ...prev, startDate: e.target.value }))}
                                sx={{ width: 150 }}
                            />
                            <ArrowRight size={16} color="#94a3b8" />
                            <TextField
                                type="date"
                                label="To"
                                size="small"
                                value={dateRange.endDate}
                                onChange={(e) => setDateRange(prev => ({ ...prev, endDate: e.target.value }))}
                                sx={{ width: 150 }}
                            />
                        </Stack>
                    </Grid>
                    <Grid item xs={12} md={2}>
                        <Button
                            variant="contained"
                            fullWidth
                            onClick={runComparison}
                            disabled={selectedMerchants.length < 2 || loading}
                            sx={{ height: 40, fontWeight: 'bold' }}
                        >
                            {loading ? 'Analyzing...' : 'Compare'}
                        </Button>
                    </Grid>
                </Grid>
            </Paper>

            {/* Comparison Content */}
            {data && data.merchants && (
                <Grid container spacing={3}>
                    {/* KPI Cards Side-by-Side */}
                    {data.merchants.map((m, idx) => (
                        <Grid item xs={12} md={12 / data.merchants.length} key={m.merchantId}>
                            <Paper sx={{ p: 3, height: '100%', borderRadius: '16px', borderTop: `4px solid ${COLORS[idx]}`, position: 'relative', overflow: 'hidden' }} elevation={0}>
                                <Box sx={{ mb: 3 }}>
                                    <Typography variant="h6" fontWeight="bold">{m.name}</Typography>
                                    <Stack direction="row" spacing={1} sx={{ mt: 0.5 }}>
                                        <Chip label={m.status} size="small" color={m.status === 'ACTIVE' ? 'success' : 'error'} variant="outlined" />
                                        <Chip label={m.mid} size="small" variant="outlined" />
                                        <Chip label={m.city} size="small" variant="outlined" />
                                    </Stack>
                                </Box>

                                <Stack spacing={2}>
                                    {[
                                        { l: 'Total Volume', v: formatCurrency(m.totalVolume), k: 'totalVolume', i: DollarSign },
                                        { l: 'Transaction Count', v: formatNumber(m.totalTxns), k: 'totalTxns', i: Hash },
                                        { l: 'Avg Ticket Size', v: formatCurrency(m.avgTxnValue), k: 'avgTxnValue', i: TrendingUp },
                                        { l: 'Total Margin', v: formatCurrency(m.totalMargin), k: 'totalMargin', i: PieChart },
                                    ].map((item) => {
                                        const meta = getDelta(item.k, m.merchantId);
                                        return (
                                            <Paper key={item.k} sx={{ p: 2, bgcolor: meta?.isLeader ? `${COLORS[idx]}10` : '#f8fafc', borderRadius: '12px', border: meta?.isLeader ? `1px solid ${COLORS[idx]}40` : '1px solid #e2e8f0' }} elevation={0}>
                                                <Stack direction="row" justifyContent="space-between" alignItems="center">
                                                    <Box>
                                                        <Typography variant="caption" color="text.secondary" fontWeight="600" textTransform="uppercase">{item.l}</Typography>
                                                        <Typography variant="h6" fontWeight="800" color="text.primary">{item.v}</Typography>
                                                    </Box>
                                                    <Box sx={{ textAlign: 'right' }}>
                                                        {meta?.isLeader && meta.delta > 0 && (
                                                            <Chip label={`+${meta.delta.toFixed(1)}%`} size="small" sx={{ bgcolor: COLORS[idx], color: 'white', fontWeight: 'bold', height: 20, fontSize: '0.7rem' }} />
                                                        )}
                                                    </Box>
                                                </Stack>
                                            </Paper>
                                        );
                                    })}
                                </Stack>

                                <Box sx={{ mt: 3, pt: 2, borderTop: '1px dashed #e2e8f0' }}>
                                    <Typography variant="subtitle2" fontWeight="bold" gutterBottom>Health Metrics</Typography>
                                    <Stack direction="row" justifyContent="space-between">
                                        <Box>
                                            <Typography variant="caption" color="text.secondary">Volatility</Typography>
                                            <Typography variant="body2" fontWeight="600">{m.volatilityIndex?.toFixed(2) || '-'}</Typography>
                                        </Box>
                                        <Box>
                                            <Typography variant="caption" color="text.secondary">Stability</Typography>
                                            <Typography variant="body2" fontWeight="600">{m.stabilityLabel || '-'}</Typography>
                                        </Box>
                                        <Box>
                                            <Typography variant="caption" color="text.secondary">DCC Opt-In</Typography>
                                            <Typography variant="body2" fontWeight="600">{m.dccOptinRate?.toFixed(1)}%</Typography>
                                        </Box>
                                    </Stack>
                                </Box>
                            </Paper>
                        </Grid>
                    ))}

                    {/* Chart Section */}
                    <Grid item xs={12}>
                        <Paper sx={{ p: 3, borderRadius: '16px' }} elevation={0}>
                            <Typography variant="h6" fontWeight="bold" gutterBottom>Volume Trend Comparison</Typography>
                            <Box sx={{ height: 350, width: '100%' }}>
                                <ResponsiveContainer>
                                    <LineChart>
                                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                                        <XAxis dataKey="month" allowDuplicatedCategory={false} tick={{ fontSize: 12 }} />
                                        <YAxis tickFormatter={(val) => `${val / 1000}k`} tick={{ fontSize: 12 }} />
                                        <Tooltip formatter={(value) => formatCurrency(value)} />
                                        <Legend />
                                        {data.merchants.map((m, idx) => (
                                            <Line
                                                key={m.merchantId}
                                                data={m.monthlyTrend}
                                                dataKey="volume"
                                                name={m.name}
                                                stroke={COLORS[idx]}
                                                strokeWidth={3}
                                                dot={{ r: 4 }}
                                                activeDot={{ r: 6 }}
                                            />
                                        ))}
                                    </LineChart>
                                </ResponsiveContainer>
                            </Box>
                        </Paper>
                    </Grid>

                    <Grid item xs={12} md={6}>
                        <Paper sx={{ p: 3, borderRadius: '16px', height: '100%' }} elevation={0}>
                            <Typography variant="h6" fontWeight="bold" gutterBottom>Card Scheme Mix</Typography>
                            <Box sx={{ height: 300, width: '100%' }}>
                                <ResponsiveContainer>
                                    <BarChart data={data.merchants.map(m => {
                                        const schemes = {};
                                        m.cardSchemeBreakdown.forEach(item => schemes[item.name] = item.volume);
                                        return { name: m.name.split(' ')[0], ...schemes };
                                    })}>
                                        <CartesianGrid strokeDasharray="3 3" vertical={false} />
                                        <XAxis dataKey="name" />
                                        <YAxis tickFormatter={(val) => `${val / 1000}k`} />
                                        <Tooltip formatter={(val) => formatCurrency(val)} />
                                        <Legend />
                                        <Bar dataKey="VISA" stackId="a" fill="#1a237e" />
                                        <Bar dataKey="MASTERCARD" stackId="a" fill="#ef6c00" />
                                        <Bar dataKey="MADA" stackId="a" fill="#00695c" />
                                    </BarChart>
                                </ResponsiveContainer>
                            </Box>
                        </Paper>
                    </Grid>

                    <Grid item xs={12} md={6}>
                        <Paper sx={{ p: 3, borderRadius: '16px', height: '100%' }} elevation={0}>
                            <Typography variant="h6" fontWeight="bold" gutterBottom>Card Type Mix (Credit vs Debit)</Typography>
                            <Box sx={{ height: 300, width: '100%' }}>
                                <ResponsiveContainer>
                                    <BarChart layout="vertical" data={data.merchants.map(m => {
                                        const types = {};
                                        // Normalize keys to CREDIT / DEBIT (case sensitive?)
                                        m.cardTypeBreakdown.forEach(item => types[item.name] = item.volume);
                                        return { name: m.name.split(' ')[0], ...types };
                                    })}>
                                        <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                                        <XAxis type="number" tickFormatter={(val) => `${val / 1000}k`} />
                                        <YAxis dataKey="name" type="category" width={80} />
                                        <Tooltip formatter={(val) => formatCurrency(val)} />
                                        <Legend />
                                        <Bar dataKey="CREDIT" stackId="a" fill="#d81b60" />
                                        <Bar dataKey="DEBIT" stackId="a" fill="#43a047" />
                                        <Bar dataKey="PREPAID" stackId="a" fill="#8e24aa" />
                                    </BarChart>
                                </ResponsiveContainer>
                            </Box>
                        </Paper>
                    </Grid>

                </Grid>
            )}

            {!data && !loading && selectedMerchants.length < 2 && (
                <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '400px', opacity: 0.6 }}>
                    <BarChart3 size={64} style={{ marginBottom: 16 }} />
                    <Typography variant="h6">Select at least 2 merchants to compare</Typography>
                </Box>
            )}

            {loading && (
                <Box sx={{ display: 'flex', justifyContent: 'center', p: 10 }}>
                    <Loader />
                </Box>
            )}
        </Box>
    );
};

export default MerchantComparison;
