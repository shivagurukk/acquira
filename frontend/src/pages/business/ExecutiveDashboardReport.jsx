import React, { useState, useEffect, useMemo } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
    PieChart, Pie, Cell
} from 'recharts';
import { Layers, BarChart3, Store, CreditCard, DollarSign, TrendingUp, Inbox, AlertTriangle } from 'lucide-react';
import { Grid, Box, Paper, Typography, Stack, FormControl, InputLabel, Select, MenuItem, TextField, Collapse, Alert } from '@mui/material';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import KpiCards from '../../components/KpiCards';
import SkeletonLoader from '../../components/SkeletonLoader';
import { pageContainer } from '../../theme/dataGridStyles';
import { formatCurrency } from '../../utils/formatters';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

const COLORS = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#3b82f6', '#14b8a6', '#f97316'];

const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

// ─── Empty-state shown inside a chart card when a query returns no rows ──
// Without this, an empty chart renders as blank axes — indistinguishable
// from a styling glitch. This makes "no data" an explicit, readable state.
const ChartEmpty = () => (
    <Box sx={{
        height: '100%', display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center', gap: 1.2,
    }}>
        <Box sx={{
            width: 48, height: 48, borderRadius: '14px',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            bgcolor: 'var(--bg-subtle, #f3f4f6)',
        }}>
            <Inbox size={22} color="var(--text-muted)" />
        </Box>
        <Typography variant="caption" color="var(--text-muted)" fontWeight={600}>
            No data for this period
        </Typography>
    </Box>
);

const BarValueLabel = ({ x, y, width, height, value, vertical }) => {
    if (value == null) return null;
    const text = formatNumber(value);
    if (vertical) {
        return (
            <text x={x + width / 2} y={y - 6} textAnchor="middle"
                fontSize={12} fontWeight={700} fill="var(--text, #334155)">{text}</text>
        );
    }
    return (
        <text x={x + width + 8} y={y + height / 2} dominantBaseline="middle"
            fontSize={12} fontWeight={700} fill="var(--text, #334155)">{text}</text>
    );
};

const ChartCard = ({ title, subtitle, accent = '#6366f1', empty, children }) => (
    <Paper sx={{
        position: 'relative', overflow: 'hidden',
        p: '18px 22px', height: 360, borderRadius: '16px',
        border: '1px solid var(--border)',
        bgcolor: 'var(--bg-card)', boxShadow: 'var(--shadow-card)',
        transition: 'transform 0.18s ease, box-shadow 0.18s ease',
        '&:hover': { boxShadow: 'var(--shadow-hover)', transform: 'translateY(-2px)' },
        display: 'flex', flexDirection: 'column',
    }}>
        <Box sx={{
            position: 'absolute', left: 0, top: 0, bottom: 0, width: 3,
            background: `linear-gradient(${accent}, ${accent}55)`,
        }} />
        <Box sx={{ mb: 1.5 }}>
            <Typography sx={{
                fontSize: '0.92rem', fontWeight: 700, color: 'var(--text)',
                letterSpacing: '-0.01em', lineHeight: 1.3,
            }}>
                {title}
            </Typography>
            {subtitle && (
                <Typography sx={{ fontSize: '0.72rem', color: 'var(--text-muted)', mt: 0.2 }}>
                    {subtitle}
                </Typography>
            )}
        </Box>
        <Box sx={{ flex: 1, minHeight: 0, borderTop: '1px solid var(--border-light)', pt: 1.5 }}>
            {empty ? <ChartEmpty /> : children}
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
    const { tenantVersion } = useAuth();
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
        api.get('/dashboard/v2/datasets')
            .then(res => { const sets = res.data; setAvailableDatasets(sets); if (sets.length > 0 && !dataset) setDataset(sets[0]); })
            .catch(err => console.error('Failed to load datasets', err));
    }, [tenantVersion]);

    useEffect(() => { fetchDashboardData(); }, [asOfDate, dataset, tenantVersion]);

    const fetchDashboardData = async () => {
        if (!dataset) return;
        setLoading(true);
        try {
            const res = await api.get(`/dashboard/v2/data?dataset=${dataset}&asOfDate=${asOfDate}`);
            setData(res.data);
        } catch (error) { console.error('Failed to fetch dashboard data', error); }
        finally { setLoading(false); }
    };

    const kpis = useMemo(() => [
        { title: 'YTD SID', value: formatNumber(data.kpis.ytdSid), subtitle: 'Stores Created', icon: Store, color: '#6366f1' },
        { title: 'YTD MID', value: formatNumber(data.kpis.ytdMid), subtitle: 'Merchants Created', icon: CreditCard, color: '#3b82f6' },
        { title: 'MTD SID', value: formatNumber(data.kpis.mtdSid), subtitle: 'This Month', icon: TrendingUp, color: '#10b981' },
        { title: 'WTD SID', value: formatNumber(data.kpis.wtdSid), subtitle: 'This Week', icon: BarChart3, color: '#f59e0b' },
        { title: 'MTD MSF Revenue', value: formatCurrency(data.kpis.mtdMsfUsd), subtitle: 'Net Revenue', icon: DollarSign, color: '#8b5cf6' },
    ], [data.kpis]);

    // Diagnostic: every KPI is zero AND every chart is empty. This almost always
    // means the merchant master file's date columns didn't parse (so
    // dim_store.created_date is NULL and the date-windowed KPI queries match
    // nothing) rather than a genuine "no activity" period. Surfacing it as a
    // banner saves the user guessing whether the dashboard is broken.
    const allEmpty = useMemo(() => {
        const k = data.kpis || {};
        const c = data.charts || {};
        const kpisZero = !k.ytdSid && !k.ytdMid && !k.mtdSid && !k.wtdSid && !k.mtdMsfUsd;
        const chartsEmpty = !(c.ytdByAgent?.length) && !(c.ytdByProgram?.length)
            && !(c.mtdVolumeSplit?.length) && !(c.mtdSidByProgram?.length);
        return kpisZero && chartsEmpty;
    }, [data]);

    const filterInputSx = {
        '& .MuiOutlinedInput-root': { borderRadius: '8px', fontSize: '13px', bgcolor: 'var(--bg-subtle)' },
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
                <Paper sx={{ p: 3, mb: 3, borderRadius: '14px', border: '1px solid var(--border)', bgcolor: 'var(--bg-card)' }}>
                    <Typography variant="caption" fontWeight={700} color="var(--text-muted)"
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
            {loading ? <SkeletonLoader variant="kpi-row" count={5} /> : <KpiCards cards={kpis} />}

            {/* Diagnostic banner — all KPIs zero and all charts empty. */}
            {!loading && allEmpty && (
                <Alert
                    severity="info"
                    icon={<AlertTriangle size={18} />}
                    sx={{ mb: 2.5, borderRadius: '12px', alignItems: 'center' }}
                >
                    No results for <strong>{asOfDate}</strong>. If you have already
                    uploaded a merchant file, this usually means the file's date
                    columns (e.g. "MerchantStore CreatedDate") were empty or in an
                    unrecognized format, or the records fall outside the selected
                    period. Try a different "As of Date", or re-upload with dates in
                    YYYY-MM-DD format.
                </Alert>
            )}

            {/* Charts Grid (2×2) */}
            {loading ? (
                <Grid container spacing={2.5} sx={{ mb: 3 }}>
                    {[0, 1, 2, 3].map(i => (
                        <Grid item xs={12} md={6} key={i}>
                            <SkeletonLoader variant="chart" height={360} />
                        </Grid>
                    ))}
                </Grid>
            ) : (
            <Grid container spacing={2.5} sx={{ mb: 3 }}>
                <Grid item xs={12} md={6}>
                    <ChartCard title="SID YTD by Introducing Agent"
                        subtitle="Stores acquired per agent, year to date" accent="#6366f1"
                        empty={!data.charts.ytdByAgent?.length}>
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart layout="vertical" data={data.charts.ytdByAgent} margin={{ top: 8, right: 56, left: 8, bottom: 5 }}>
                                <defs>
                                    <linearGradient id="barAgent" x1="0" y1="0" x2="1" y2="0">
                                        <stop offset="0%" stopColor="#818cf8" />
                                        <stop offset="100%" stopColor="#6366f1" />
                                    </linearGradient>
                                </defs>
                                <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="var(--border-light, #f1f5f9)" />
                                <XAxis type="number" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                                <YAxis dataKey="agent" type="category" width={110}
                                    tick={{ fontSize: 11, fill: '#64748b', fontWeight: 600 }} axisLine={false} tickLine={false} />
                                <Tooltip content={<CustomTooltip />} cursor={{ fill: 'rgba(99,102,241,0.06)' }} />
                                <Bar dataKey="count" fill="url(#barAgent)" radius={[0, 6, 6, 0]}
                                    barSize={26} maxBarSize={32} label={<BarValueLabel />} />
                            </BarChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>

                <Grid item xs={12} md={6}>
                    <ChartCard title="SID YTD by Program"
                        subtitle="Stores acquired per program, year to date" accent="#10b981"
                        empty={!data.charts.ytdByProgram?.length}>
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart data={data.charts.ytdByProgram} margin={{ top: 24, right: 16, left: 4, bottom: 5 }}>
                                <defs>
                                    <linearGradient id="barProg" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="0%" stopColor="#34d399" />
                                        <stop offset="100%" stopColor="#10b981" />
                                    </linearGradient>
                                </defs>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border-light, #f1f5f9)" />
                                <XAxis dataKey="program" tick={{ fontSize: 11, fill: '#64748b', fontWeight: 600 }} axisLine={false} tickLine={false} />
                                <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                                <Tooltip content={<CustomTooltip />} cursor={{ fill: 'rgba(16,185,129,0.06)' }} />
                                <Bar dataKey="count" fill="url(#barProg)" radius={[6, 6, 0, 0]}
                                    barSize={48} maxBarSize={64} label={<BarValueLabel vertical />} />
                            </BarChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>

                <Grid item xs={12} md={6}>
                    <ChartCard title="MTD Volume Split by Program"
                        subtitle="Share of month-to-date processing volume" accent="#f59e0b"
                        empty={!data.charts.mtdVolumeSplit?.length}>
                        <ResponsiveContainer width="100%" height="100%">
                            <PieChart>
                                <Pie data={data.charts.mtdVolumeSplit} cx="50%" cy="50%"
                                    labelLine={false}
                                    label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                                    outerRadius={100} innerRadius={56} dataKey="value"
                                    stroke="var(--bg-card, #fff)" strokeWidth={3} paddingAngle={3}
                                >
                                    {data.charts.mtdVolumeSplit.map((_, index) => (
                                        <Cell key={index} fill={COLORS[index % COLORS.length]} />
                                    ))}
                                </Pie>
                                <Tooltip content={<CustomTooltip isCurrency />} />
                                <Legend wrapperStyle={{ fontSize: 11, fontWeight: 600 }} iconType="circle" />
                            </PieChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>

                <Grid item xs={12} md={6}>
                    <ChartCard title="SID for the Month by Program"
                        subtitle="Stores acquired this month, per program" accent="#8b5cf6"
                        empty={!data.charts.mtdSidByProgram?.length}>
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart data={data.charts.mtdSidByProgram} margin={{ top: 24, right: 16, left: 4, bottom: 5 }}>
                                <defs>
                                    <linearGradient id="barMtd" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="0%" stopColor="#a78bfa" />
                                        <stop offset="100%" stopColor="#8b5cf6" />
                                    </linearGradient>
                                </defs>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border-light, #f1f5f9)" />
                                <XAxis dataKey="program" tick={{ fontSize: 11, fill: '#64748b', fontWeight: 600 }} axisLine={false} tickLine={false} />
                                <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                                <Tooltip content={<CustomTooltip />} cursor={{ fill: 'rgba(139,92,246,0.06)' }} />
                                <Bar dataKey="count" fill="url(#barMtd)" radius={[6, 6, 0, 0]}
                                    barSize={48} maxBarSize={64} label={<BarValueLabel vertical />} />
                            </BarChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>
            </Grid>
            )}
        </Box>
    );
};

export default ExecutiveDashboardReport;
