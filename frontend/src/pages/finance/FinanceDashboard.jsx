import React, { useState, useEffect, useMemo, useCallback } from 'react';
import axios from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { DollarSign, TrendingUp, Percent, CreditCard, Activity } from 'lucide-react';
import { Box, Paper, Typography, Grid, Stack } from '@mui/material';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { pageContainer } from '../../theme/dataGridStyles';

const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

// ─── Premium Chart Card ──────────────────────────────────────────────
const ChartCard = ({ title, children }) => (
    <Paper sx={{
        p: 3, height: 380, borderRadius: '14px', border: '1px solid var(--border)',
        bgcolor: 'var(--bg-card)', boxShadow: 'var(--shadow-card)',
        transition: 'all 0.2s ease',
        '&:hover': { boxShadow: 'var(--shadow-hover)', borderColor: 'var(--text-muted)' },
        display: 'flex', flexDirection: 'column',
    }}>
        <Typography variant="subtitle2" fontWeight={800} color="var(--text)"
            sx={{ mb: 2, pb: 1.5, borderBottom: '1px solid var(--border-light)', letterSpacing: '-0.01em' }}>
            {title}
        </Typography>
        <Box sx={{ flex: 1, minHeight: 0 }}>
            {children}
        </Box>
    </Paper>
);

// ─── Custom Tooltip ──────────────────────────────────────────────────
const CustomTooltip = ({ active, payload, label, formatter }) => {
    if (!active || !payload || !payload.length) return null;
    return (
        <Box sx={{ bgcolor: '#0f172a', borderRadius: '8px', px: 2, py: 1.5, boxShadow: '0 8px 24px rgba(0,0,0,0.2)' }}>
            <Typography variant="caption" color="#94a3b8" fontWeight={600}>{label}</Typography>
            {payload.map((p, i) => (
                <Stack key={i} direction="row" spacing={1} alignItems="center" sx={{ mt: 0.5 }}>
                    <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: p.color }} />
                    <Typography variant="body2" fontWeight={700} color="white">
                        {p.name}: {formatter ? formatter(p.value) : p.value}
                    </Typography>
                </Stack>
            ))}
        </Box>
    );
};

const FinanceDashboard = () => {
    const { currencyCode = 'AED', formatCurrency: fmtCurr } = useAuth() || {};
    // Tenant-aware currency formatter (was hardcoded to USD).
    const formatCurrency = useCallback((val) => {
        if (fmtCurr) return fmtCurr(val);
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: currencyCode, maximumFractionDigits: 0 }).format(val || 0);
    }, [fmtCurr, currencyCode]);
    const [kpis, setKpis] = useState(null);
    const [trends, setTrends] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);
    // Default to YEAR rather than MONTH — reduces empty-on-first-load when data
    // lags real time. The user can still pick MONTH/CUSTOM/etc. via the header.
    const [filters, setFilters] = useState({ datePreset: 'YEAR' });

    // tenantId is now injected automatically by the axios interceptor.
    // The manual localStorage lookup + per-call header override is removed.

    useEffect(() => { fetchData(); }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const [kpiRes, trendRes] = await Promise.all([
                axios.post('/finance/dashboard/kpis-filtered', filters),
                axios.post('/finance/dashboard/trends-filtered?mode=MTD', filters),
            ]);
            setKpis(kpiRes.data);
            setTrends(trendRes.data);
        } catch (error) { console.error('Error fetching finance dashboard data', error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    // ─── KPI Cards ───────────────────────────────────────────────────────
    const revenueKpis = useMemo(() => [
        { title: 'Daily Net Revenue', value: formatCurrency(kpis?.dailyNetRevenue), subtitle: 'Today', icon: TrendingUp, color: '#3b82f6' },
        { title: 'MTD Net Revenue', value: formatCurrency(kpis?.mtdNetRevenue), subtitle: 'Month to Date', icon: TrendingUp, color: '#6366f1' },
        { title: 'YTD Net Revenue', value: formatCurrency(kpis?.ytdNetRevenue), subtitle: 'Year to Date', icon: TrendingUp, color: '#8b5cf6' },
    ], [kpis]);

    const volumeKpis = useMemo(() => [
        { title: 'Daily Volume', value: formatCurrency(kpis?.dailyVolume), subtitle: 'Today', icon: Activity, color: '#10b981' },
        { title: 'MTD Volume', value: formatCurrency(kpis?.mtdVolume), subtitle: 'Month to Date', icon: Activity, color: '#14b8a6' },
        { title: 'YTD Volume', value: formatCurrency(kpis?.ytdVolume), subtitle: 'Year to Date', icon: Activity, color: '#06b6d4' },
    ], [kpis]);

    const costKpis = useMemo(() => [
        { title: 'MSF Revenue', value: formatCurrency(kpis?.msfRevenue), subtitle: 'Gross Fees', icon: DollarSign, color: '#3b82f6' },
        { title: 'Interchange Costs', value: formatCurrency(kpis?.interchangeFees), subtitle: 'Network Costs', icon: CreditCard, color: '#f97316' },
        { title: 'Scheme Fees', value: formatCurrency(kpis?.schemeFees), subtitle: 'Card Scheme Fees', icon: Activity, color: '#ef4444' },
        { title: 'Margin %', value: `${kpis?.marginPct || 0}%`, subtitle: 'Net / Volume', icon: Percent, color: '#f59e0b' },
    ], [kpis]);

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Finance Dashboard" subtitle="Financial performance overview and profitability metrics"
                icon={DollarSign}
                onRunReport={fetchData} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchData} isOpen={showFilters} onClose={() => setShowFilters(false)} />

            {/* Revenue KPIs */}
            <Typography variant="caption" fontWeight={700} color="var(--text-muted)"
                sx={{ textTransform: 'uppercase', letterSpacing: '0.06em', mb: 1.5, display: 'block' }}>
                Net Revenue Performance
            </Typography>
            <KpiCards cards={revenueKpis} />

            {/* Volume KPIs */}
            <Typography variant="caption" fontWeight={700} color="var(--text-muted)"
                sx={{ textTransform: 'uppercase', letterSpacing: '0.06em', mb: 1.5, display: 'block' }}>
                Volume Performance
            </Typography>
            <KpiCards cards={volumeKpis} />

            {/* Cost KPIs */}
            <Typography variant="caption" fontWeight={700} color="var(--text-muted)"
                sx={{ textTransform: 'uppercase', letterSpacing: '0.06em', mb: 1.5, display: 'block' }}>
                Cost Analysis (MTD)
            </Typography>
            <KpiCards cards={costKpis} />

            {/* Charts */}
            <Grid container spacing={2.5} sx={{ mb: 3 }}>
                <Grid item xs={12} lg={6}>
                    <ChartCard title="Revenue Trends (MTD)">
                        <ResponsiveContainer width="100%" height="100%">
                            <LineChart data={trends}>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border-light)" />
                                <XAxis dataKey="key" stroke="var(--text-muted)" tick={{ fontSize: 11 }} tickFormatter={(val) => val?.slice?.(-2) || val} />
                                <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11 }} tickFormatter={(val) => `${currencyCode} ${(val / 1000).toFixed(0)}k`} />
                                <Tooltip content={<CustomTooltip formatter={formatCurrency} />} />
                                <Legend wrapperStyle={{ fontSize: 12, fontWeight: 600 }} />
                                <Line type="monotone" dataKey="netRevenue" stroke="#10b981" strokeWidth={3} dot={{ r: 3 }} name="Net Revenue" />
                                <Line type="monotone" dataKey="msf" stroke="#3b82f6" strokeWidth={2} dot={false} name="MSF Rev" />
                                <Line type="monotone" dataKey="interchange" stroke="#f97316" strokeWidth={2} dot={false} name="Interchange" />
                            </LineChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>

                <Grid item xs={12} lg={6}>
                    <ChartCard title="Margin % Trend (MTD)">
                        <ResponsiveContainer width="100%" height="100%">
                            <LineChart data={trends}>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border-light)" />
                                <XAxis dataKey="key" stroke="var(--text-muted)" tick={{ fontSize: 11 }} tickFormatter={(val) => val?.slice?.(-2) || val} />
                                <YAxis stroke="var(--text-muted)" tick={{ fontSize: 11 }} domain={[0, 'auto']} tickFormatter={(val) => `${val}%`} />
                                <Tooltip content={<CustomTooltip formatter={(v) => `${v}%`} />} />
                                <Legend wrapperStyle={{ fontSize: 12, fontWeight: 600 }} />
                                <Line type="step" dataKey="marginPct" stroke="#8b5cf6" strokeWidth={3} dot={{ r: 3 }} name="Margin %" />
                            </LineChart>
                        </ResponsiveContainer>
                    </ChartCard>
                </Grid>
            </Grid>

            {/* Bottom KPI Row */}
            <KpiCards cards={[
                { title: 'Scheme Fees (Est.)', value: formatCurrency(kpis?.schemeFees), icon: Activity, color: '#64748b' },
                { title: 'VAT Collected', value: formatCurrency(kpis?.vat), icon: Activity, color: '#64748b' },
            ]} />
        </Box>
    );
};

export default FinanceDashboard;
