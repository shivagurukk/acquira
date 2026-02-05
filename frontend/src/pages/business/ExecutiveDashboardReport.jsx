import React, { useState, useEffect } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
    PieChart, Pie, Cell
} from 'recharts';
import { Calendar, Layers } from 'lucide-react';
import {
    Grid,
    Card,
    CardContent,
    Typography,
    Box,
    FormControl,
    InputLabel,
    Select,
    MenuItem,
    TextField,
    Paper
} from '@mui/material';
import ReportHeader from '../../components/ReportHeader';

const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#8884d8', '#82ca9d'];

const ExecutiveDashboardReport = () => {
    const [loading, setLoading] = useState(false);
    const [asOfDate, setAsOfDate] = useState(new Date().toISOString().split('T')[0]);
    const [dataset, setDataset] = useState('SID_Data_2026');
    const [availableDatasets, setAvailableDatasets] = useState([]);
    const [showFilters, setShowFilters] = useState(true);

    const [data, setData] = useState({
        kpis: {
            ytdSid: 0, ytdMid: 0, mtdSid: 0, wtdSid: 0, mtdMsfUsd: 0
        },
        charts: {
            ytdByAgent: [],
            ytdByProgram: [],
            mtdVolumeSplit: [],
            mtdSidByProgram: []
        }
    });

    useEffect(() => {
        // Fetch Datasets
        fetch('/api/dashboard/v2/datasets')
            .then(res => res.json())
            .then(sets => {
                setAvailableDatasets(sets);
                if (sets.length > 0 && !dataset) setDataset(sets[0]);
            })
            .catch(err => console.error(err));
    }, []);

    useEffect(() => {
        fetchDashboardData();
    }, [asOfDate, dataset]);

    const fetchDashboardData = async () => {
        if (!dataset) return;
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const res = await fetch(`/api/dashboard/v2/data?dataset=${dataset}&asOfDate=${asOfDate}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) {
                const result = await res.json();
                setData(result);
            }
        } catch (error) {
            console.error("Failed to fetch dashboard data", error);
        } finally {
            setLoading(false);
        }
    };

    const formatCurrency = (val) => {
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val);
    };

    return (
        <Box sx={{ p: 4, bgcolor: 'background.default', minHeight: '100vh', overflowY: 'auto' }}>
            {/* Header */}
            <ReportHeader
                title="Executive Dashboard 2.0"
                subtitle="SID Acquisition & Performance Report"
                // No CSV export for dashboard charts currently, or could implement later
                onRunReport={fetchDashboardData}
                filters={{ hideDatePresets: true }}
                onFilterChange={() => { }}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                loading={loading}
            />

            {/* Filter Panel */}
            {showFilters && (
                <Paper sx={{ p: 3, mb: 4, borderRadius: 3 }}>
                    <Grid container spacing={3} alignItems="center">
                        <Grid item xs={12} md={4}>
                            <FormControl fullWidth size="small">
                                <InputLabel id="dataset-select-label">Data Sheet</InputLabel>
                                <Select
                                    labelId="dataset-select-label"
                                    value={dataset}
                                    label="Data Sheet"
                                    onChange={e => setDataset(e.target.value)}
                                    startAdornment={<Layers size={16} style={{ marginRight: 8, opacity: 0.5 }} />}
                                >
                                    {availableDatasets.map(ds => (
                                        <MenuItem key={ds} value={ds}>{ds}</MenuItem>
                                    ))}
                                </Select>
                            </FormControl>
                        </Grid>
                        <Grid item xs={12} md={4}>
                            <TextField
                                label="As of Date"
                                type="date"
                                fullWidth
                                size="small"
                                value={asOfDate}
                                onChange={e => setAsOfDate(e.target.value)}
                                InputLabelProps={{ shrink: true }}
                            />
                        </Grid>
                    </Grid>
                </Paper>
            )}

            {/* Charts Grid (2x2) */}
            <Grid container spacing={3} sx={{ mb: 4 }}>

                {/* Chart 1: Number of SID YTD by Introducing Agent */}
                <Grid item xs={12} md={6}>
                    <Paper sx={{ p: 3, height: 400, borderRadius: 3 }}>
                        <Typography variant="subtitle1" fontWeight="bold" sx={{ mb: 2, borderBottom: 1, borderColor: 'divider', pb: 1 }}>
                            Number of SID YTD by Introducing Agent
                        </Typography>
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart
                                layout="vertical"
                                data={data.charts.ytdByAgent}
                                margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
                            >
                                <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                                <XAxis type="number" />
                                <YAxis dataKey="agent" type="category" width={100} tick={{ fontSize: 11 }} />
                                <Tooltip />
                                <Bar dataKey="count" fill="#3B82F6" radius={[0, 4, 4, 0]} label={{ position: 'right', fill: '#64748b', fontSize: 11 }} />
                            </BarChart>
                        </ResponsiveContainer>
                    </Paper>
                </Grid>

                {/* Chart 2: Number of SID YTD by Merchant Referral Program */}
                <Grid item xs={12} md={6}>
                    <Paper sx={{ p: 3, height: 400, borderRadius: 3 }}>
                        <Typography variant="subtitle1" fontWeight="bold" sx={{ mb: 2, borderBottom: 1, borderColor: 'divider', pb: 1 }}>
                            Number of SID YTD by Program
                        </Typography>
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart
                                data={data.charts.ytdByProgram}
                                margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
                            >
                                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                                <XAxis dataKey="program" tick={{ fontSize: 11 }} />
                                <YAxis />
                                <Tooltip />
                                <Bar dataKey="count" fill="#10B981" radius={[4, 4, 0, 0]} label={{ position: 'top', fill: '#64748b', fontSize: 11 }} />
                            </BarChart>
                        </ResponsiveContainer>
                    </Paper>
                </Grid>

                {/* Chart 3: MTD Volume USD Split by Program */}
                <Grid item xs={12} md={6}>
                    <Paper sx={{ p: 3, height: 400, borderRadius: 3 }}>
                        <Typography variant="subtitle1" fontWeight="bold" sx={{ mb: 2, borderBottom: 1, borderColor: 'divider', pb: 1 }}>
                            MTD Volume USD Split by Program
                        </Typography>
                        <ResponsiveContainer width="100%" height="100%">
                            <PieChart>
                                <Pie
                                    data={data.charts.mtdVolumeSplit}
                                    cx="50%"
                                    cy="50%"
                                    labelLine={false}
                                    label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                                    outerRadius={120}
                                    fill="#8884d8"
                                    dataKey="value"
                                >
                                    {data.charts.mtdVolumeSplit.map((entry, index) => (
                                        <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                                    ))}
                                </Pie>
                                <Tooltip formatter={(value) => formatCurrency(value)} />
                                <Legend />
                            </PieChart>
                        </ResponsiveContainer>
                    </Paper>
                </Grid>

                {/* Chart 4: MTD SID Count by Program */}
                <Grid item xs={12} md={6}>
                    <Paper sx={{ p: 3, height: 400, borderRadius: 3 }}>
                        <Typography variant="subtitle1" fontWeight="bold" sx={{ mb: 2, borderBottom: 1, borderColor: 'divider', pb: 1 }}>
                            Number of SID for the Month by Program
                        </Typography>
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart
                                data={data.charts.mtdSidByProgram}
                                margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
                            >
                                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                                <XAxis dataKey="program" tick={{ fontSize: 11 }} />
                                <YAxis />
                                <Tooltip />
                                <Bar dataKey="count" fill="#8B5CF6" radius={[4, 4, 0, 0]} label={{ position: 'top', fill: '#64748b', fontSize: 11 }} />
                            </BarChart>
                        </ResponsiveContainer>
                    </Paper>
                </Grid>
            </Grid>

            {/* KPI Tiles (Bottom Row) */}
            <Grid container spacing={2}>
                <Grid item xs={12} sm={6} md={4} lg={2}>
                    <KpiTile label="As of Date" value={asOfDate} sublabel="Selection" color="info" />
                </Grid>
                <Grid item xs={12} sm={6} md={4} lg={2}>
                    <KpiTile label="YTD SID" value={data.kpis.ytdSid.toLocaleString()} sublabel="Stores Created" color="primary" />
                </Grid>
                <Grid item xs={12} sm={6} md={4} lg={2}>
                    <KpiTile label="YTD MID" value={data.kpis.ytdMid.toLocaleString()} sublabel="Merchants Created" color="secondary" />
                </Grid>
                <Grid item xs={12} sm={6} md={4} lg={2}>
                    <KpiTile label="MTD SID Created" value={data.kpis.mtdSid.toLocaleString()} sublabel="This Month" color="success" />
                </Grid>
                <Grid item xs={12} sm={6} md={4} lg={2}>
                    <KpiTile label="WTD SID Created" value={data.kpis.wtdSid.toLocaleString()} sublabel="This Week" color="warning" />
                </Grid>
                <Grid item xs={12} sm={6} md={4} lg={2}>
                    <KpiTile label="Sum of MTD MSF" value={formatCurrency(data.kpis.mtdMsfUsd)} sublabel="USD Revenue" color="success" />
                </Grid>
            </Grid>
        </Box>
    );
};

const KpiTile = ({ label, value, sublabel, color = 'primary' }) => {
    // Mapping our custom 'color' props to MUI severity/palette colors if needed
    // or just use 'primary', 'secondary', 'error', 'warning', 'info', 'success'

    return (
        <Card elevation={0} sx={{ height: '100%', border: '1px solid', borderColor: `${color}.light`, bgcolor: `${color}.lighter` }}>
            <CardContent>
                <Typography variant="overline" color="text.secondary" fontWeight="bold">
                    {label}
                </Typography>
                <Typography variant="h4" fontWeight="bold" color={`${color}.main`} sx={{ my: 1 }} noWrap title={value}>
                    {value}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                    {sublabel}
                </Typography>
            </CardContent>
        </Card>
    );
};

export default ExecutiveDashboardReport;
