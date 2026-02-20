import React, { useState, useEffect, useMemo } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
    PieChart, Pie, Cell
} from 'recharts';
import { Layers, BarChart3, Store, CreditCard, DollarSign, TrendingUp } from 'lucide-react';
import { Grid, Box, Paper, Typography, Stack, FormControl, InputLabel, Select, MenuItem, TextField, Collapse } from '@mui/material';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';
import { pageContainer } from '../../theme/dataGridStyles';

const COLORS = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#3b82f6', '#14b8a6', '#f97316'];

const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

// ─── Premium Chart Card ──────────────────────────────────────────────
const ChartCard = ({ title, children }) => (
    <Paper sx={{
        p: 3, height: 400, borderRadius: '14px', border: '1px solid #e2e8f0',
        bgcolor: 'white', boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
        transition: 'all 0.2s ease',
        '&:hover': { boxShadow: '0 4px 16px rgba(0,0,0,0.06)', borderColor: '#cbd5e1' },
        display: 'flex', flexDirection: 'column',
    }}>
        <Typography variant="subtitle2" fontWeight={800} color="#0f172a"
            sx={{ mb: 2, pb: 1.5, borderBottom: '1px solid #f1f5f9', letterSpacing: '-0.01em' }}>
            {title}
        </Typography>
        <Box sx={{ flex: 1, minHeight: 0 }}>
            {children}
        </Box>
    </Paper>
);

// ─── Custom Tooltip ──────────────────────────────────────────────────
const CustomTooltip = ({ active, payload, label, isCurrency }) => {
    if (!active || !payload || !payload.length) return null;
    return (
        <Box sx={{ bgcolor: '#0f172a', borderRadius: '8px', px: 2, py: 1.5, boxShadow: '0 8px 24px rgba(0,0,0,0.2)' }}>
            <Typography variant="caption" color="#94a3b8" fontWeight={600}>{label}</Typography>
            {payload.map((p, i) => (
                <Typography key={i} variant="body2" fontWeight={700} sx={{ color: p.color || '#fff', mt: 0.5 }}>
                    {isCurrency ? formatCurrency(p.value) : formatNumber(p.value)}
                </Typography>
            ))}
        </Box>
    );
};

const ExecutiveDashboardReport = () => {
    const [loading, setLoading] = useState(false);
    const [asOfDate, setAsOfDate] = useState(new Date().toISOString().split('T')[0]);
    const [dataset, setDataset] = useState('SID_Data_2026');
    const [availableDatasets, setAvailableDatasets] = useState([]);
    const [showFilters, setShowFilters] = useState(false);

    const [data, setData] = useState({
        kpis: { ytdSid: 0, ytdMid: 0, mtdSid: 0, wtdSid: 0, mtdMsfUsd: 0 },
        charts: { ytdByAgent: [], ytdByProgram: [], mtdVolumeSplit: [], mtdSidByProgram: [] }
    });

    useEffect(() => {
        fetch('/api/dashboard/v2/datasets')
            .then(res => res.json())
            .then(sets => { setAvailableDatasets(sets); if (sets.length > 0 && !dataset) setDataset(sets[0]); })
            .catch(err => console.error(err));
    }, []);

    useEffect(() => { fetchDashboardData(); }, [asOfDate, dataset]);

    const fetchDashboardData = async () => {
        if (!dataset) return;
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId') || localStorage.getItem('defaultTenantId');
            const res = await fetch(`/api/dashboard/v2/data?dataset=${dataset}&asOfDate=${asOfDate}`, {
                headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
            });
            if (res.ok) setData(await res.json());
        } catch (error) { console.error('Failed to fetch dashboard data', error); }
        finally { setLoading(false); }
    };

    const kpis = useMemo(() => [
        { title: 'YTD SID', value: formatNumber(data.kpis.ytdSid), subtitle: 'Stores Created', icon: Store, color: '#6366f1' },
        { title: 'YTD MID', value: formatNumber(data.kpis.ytdMid), subtitle: 'Merchants Created', icon: CreditCard, color: '#3b82f6' },
        { title: 'MTD SID', value: formatNumber(data.kpis.mtdSid), subtitle: 'This Month', icon: TrendingUp, color: '#10b981' },
        { title: 'WTD SID', value: formatNumber(data.kpis.wtdSid), subtitle: 'This Week', icon: BarChart3, color: '#f59e0b' },
        { title: 'MTD MSF Revenue', value: formatCurrency(data.kpis.mtdMsfUsd), subtitle: 'USD Revenue', icon: DollarSign, color: '#8b5cf6' },
    ], [data.kpis]);

    const filterInputSx = {
        '& .MuiOutlinedInput-root': { borderRadius: '8px', fontSize: '13px', bgcolor: '#f8fafc' },
        '& .MuiInputLabel-root': { fontSize: '12px', fontWeight: 600 },
    };

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Executive Dashboard" subtitle="SID Acquisition & Performance Report"
                icon={BarChart3}
                onRunReport={fetchDashboardData}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                hideDatePresets
            />

            {/* Filter Panel */}
            <Collapse in={showFilters} unmountOnExit>
                <Paper sx={{ p: 3, mb: 3, borderRadius: '14px', border: '1px solid #e2e8f0' }}>
                    <Typography variant="caption" fontWeight={700} color="#94a3b8"
                        sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', mb: 2, display: 'block' }}>
                        Data Source
                    </Typography>
                    <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
                        <FormControl size="small" sx={{ minWidth: 220, ...filterInputSx }}>
                            <InputLabel>Data Sheet</InputLabel>
                            <Select value={dataset} label="Data Sheet"
                                onChange={e => setDataset(e.target.value)}
                                startAdornment={<Layers size={14} style={{ marginRight: 8, opacity: 0.5 }} />}
                            >
                                {availableDatasets.map(ds => (
                                    <MenuItem key={ds} value={ds}>{ds}</MenuItem>
                                ))}
                            </Select>
                        </FormControl>
                        <TextField label="As of Date" type="date" size="small" value={asOfDate}
                            onChange={e => setAsOfDate(e.target.value)}
                            InputLabelProps={{ shrink: true }}
                            sx={{ minWidth: 170, ...filterInputSx }}
                        />
                    </Stack>
                </Paper>
            </Collapse>

            {/* KPI Row */}
            <KpiCards cards={kpis} />

            {/* Charts Grid (2×2) */}
            <Grid container spacing={2.5} sx={{ mb: 3 }}>
                <Grid item xs={12} md={6}>
                    <ChartCard title="Number of SID YTD by Introducing Agent">
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart layout="vertical" data={data.charts.ytdByAgent} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
                                <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#f1f5f9" />
                                <XAxis type="number" tick={{ fontSize: 11, fill: '#94a3b8' }} />
                                <YAxis dataKey="agent" type="category" width={100} tick={{ fontSize: 11, fill: '#64748b' }} />
                                <Tooltip content={<CustomTooltip />} />
                                <Bar dataKey="count" fill="#6366f1" radius={[0, 6, 6, 0]} />
                            </BarChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>

                <Grid item xs={12} md={6}>
                    <ChartCard title="Number of SID YTD by Program">
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart data={data.charts.ytdByProgram} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                <XAxis dataKey="program" tick={{ fontSize: 11, fill: '#64748b' }} />
                                <YAxis tick={{ fill: '#94a3b8' }} />
                                <Tooltip content={<CustomTooltip />} />
                                <Bar dataKey="count" fill="#10b981" radius={[6, 6, 0, 0]} />
                            </BarChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>

                <Grid item xs={12} md={6}>
                    <ChartCard title="MTD Volume USD Split by Program">
                        <ResponsiveContainer width="100%" height="100%">
                            <PieChart>
                                <Pie data={data.charts.mtdVolumeSplit} cx="50%" cy="50%"
                                    labelLine={false}
                                    label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                                    outerRadius={110} innerRadius={50} dataKey="value"
                                    stroke="none" paddingAngle={2}
                                >
                                    {data.charts.mtdVolumeSplit.map((_, index) => (
                                        <Cell key={index} fill={COLORS[index % COLORS.length]} />
                                    ))}
                                </Pie>
                                <Tooltip content={<CustomTooltip isCurrency />} />
                                <Legend wrapperStyle={{ fontSize: 12, fontWeight: 600 }} />
                            </PieChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>

                <Grid item xs={12} md={6}>
                    <ChartCard title="Number of SID for the Month by Program">
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart data={data.charts.mtdSidByProgram} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                <XAxis dataKey="program" tick={{ fontSize: 11, fill: '#64748b' }} />
                                <YAxis tick={{ fill: '#94a3b8' }} />
                                <Tooltip content={<CustomTooltip />} />
                                <Bar dataKey="count" fill="#8b5cf6" radius={[6, 6, 0, 0]} />
                            </BarChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>
            </Grid>
        </Box>
    );
};

export default ExecutiveDashboardReport;
