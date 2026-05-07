import React, { useState, useEffect, useMemo } from 'react';
import axios from '../../api/axios';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { DollarSign, TrendingUp, Percent, CreditCard, Activity } from 'lucide-react';
import { Box, Paper, Typography, Grid, Stack } from '@mui/material';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { pageContainer } from '../../theme/dataGridStyles';

const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val || 0);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

// ─── Premium Chart Card ──────────────────────────────────────────────
const ChartCard = ({ title, children }) => (
    <Paper sx={{
        p: 3, height: 380, borderRadius: '14px', border: '1px solid #e2e8f0',
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
    const [kpis, setKpis] = useState(null);
    const [trends, setTrends] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);
    // Default to YEAR rather than MONTH — reduces empty-on-first-load when data
    // lags real time. The user can still pick MONTH/CUSTOM/etc. via the header.
    const [filters, setFilters] = useState({ datePreset: 'YEAR' });

    // Tenant lookup. Reads `defaultTenantId` (the canonical key) first; falls back
    // to legacy `tenantId` for backward compat. We do NOT fall back to literal `1`
    // — that hides real auth/tenancy bugs and would silently leak across tenants
    // in a multi-tenant deployment. The axios instance handles the missing-tenant
    // case via its interceptor.
    const tenantId = localStorage.getItem('defaultTenantId') || localStorage.getItem('tenantId');

    useEffect(() => { fetchData(); }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const queryParams = new URLSearchParams();
            if (filters.startDate) queryParams.append('from', filters.startDate);
            if (filters.endDate) queryParams.append('to', filters.endDate);

            const [kpiRes, trendRes] = await Promise.all([
                axios.get(`/finance/dashboard/kpis?${queryParams.toString()}`, { headers: { 'X-Tenant-Id': tenantId } }),
                axios.get(`/finance/dashboard/trends/MTD?${queryParams.toString()}`, { headers: { 'X-Tenant-Id': tenantId } })
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
            <Typography variant="caption" fontWeight={700} color="#94a3b8"
                sx={{ textTransform: 'uppercase', letterSpacing: '0.06em', mb: 1.5, display: 'block' }}>
                Net Revenue Performance
            </Typography>
            <KpiCards cards={revenueKpis} />

            {/* Volume KPIs */}
            <Typography variant="caption" fontWeight={700} color="#94a3b8"
                sx={{ textTransform: 'uppercase', letterSpacing: '0.06em', mb: 1.5, display: 'block' }}>
                Volume Performance
            </Typography>
            <KpiCards cards={volumeKpis} />

            {/* Cost KPIs */}
            <Typography variant="caption" fontWeight={700} color="#94a3b8"
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
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                <XAxis dataKey="key" stroke="#94a3b8" tick={{ fontSize: 11 }} tickFormatter={(val) => val?.slice?.(-2) || val} />
                                <YAxis stroke="#94a3b8" tick={{ fontSize: 11 }} tickFormatter={(val) => `$${(val / 1000).toFixed(0)}k`} />
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
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                <XAxis dataKey="key" stroke="#94a3b8" tick={{ fontSize: 11 }} tickFormatter={(val) => val?.slice?.(-2) || val} />
                                <YAxis stroke="#94a3b8" tick={{ fontSize: 11 }} domain={[0, 'auto']} tickFormatter={(val) => `${val}%`} />
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
