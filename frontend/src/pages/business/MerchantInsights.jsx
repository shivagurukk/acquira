import React, { useState, useEffect } from 'react';
import { Download, ChevronRight, ChevronLeft, CreditCard, LayoutGrid, Users, Award, PieChart } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { Box, Typography, Stack, Paper, IconButton, Tabs, Tab } from '@mui/material';

// ─── Dark Theme Palette ──────────────────────────────────────────────
const DARK = {
    bg: '#0B1630', card: '#0F2347', border: '#1F3B6D', accent: '#FF5A5F',
    textPrimary: '#FFFFFF', textSecondary: '#B9C6DD', textMuted: '#64789A',
    barPrimary: '#0B1630', barSecondary: '#7CB4FF',
};

// ─── Custom Tooltip ──────────────────────────────────────────────────
const DarkTooltip = ({ active, payload, label }) => {
    if (!active || !payload || !payload.length) return null;
    return (
        <Box sx={{ bgcolor: DARK.card, borderRadius: '8px', px: 2, py: 1.5, border: `1px solid ${DARK.border}`, boxShadow: '0 8px 24px rgba(0,0,0,0.4)' }}>
            <Typography variant="caption" color={DARK.textSecondary} fontWeight={600}>{label}</Typography>
            {payload.map((p, i) => (
                <Typography key={i} variant="body2" fontWeight={700} sx={{ color: p.color || '#fff', mt: 0.5 }}>
                    {p.name}: {typeof p.value === 'number' ? p.value.toLocaleString() : p.value}
                </Typography>
            ))}
        </Box>
    );
};

// ─── KPI Card (Dark Theme) ──────────────────────────────────────────
const DarkKpiCard = ({ title, value, growth, trend, icon: Icon }) => {
    const isUp = trend === 'UP';
    const isFlat = trend === 'FLAT';
    const trendColor = isFlat ? DARK.textMuted : isUp ? '#10b981' : '#ef4444';

    return (
        <Paper sx={{
            flex: 1, minWidth: 200, p: 2.5, borderRadius: '4px',
            bgcolor: DARK.card, border: `1px solid ${DARK.border}`, borderTop: `3px solid ${DARK.border}`,
            position: 'relative', overflow: 'hidden',
            transition: 'all 0.2s ease',
            '&:hover': { borderTopColor: DARK.accent },
        }}>
            {Icon && (
                <Box sx={{ position: 'absolute', top: 20, right: 20, opacity: 0.2 }}>
                    <Icon size={32} color={DARK.textSecondary} />
                </Box>
            )}
            <Typography variant="caption" fontWeight={700} color={DARK.textSecondary}
                sx={{ textTransform: 'uppercase', letterSpacing: '0.08em', fontSize: '0.65rem' }}>
                {title}
            </Typography>
            <Typography variant="h4" fontWeight={800} color={DARK.textPrimary} sx={{ mt: 1, mb: 1.5, letterSpacing: '-0.02em' }}>
                {value}
            </Typography>
            {growth !== null && growth !== undefined && (
                <Stack direction="row" alignItems="center" spacing={0.5}>
                    <Typography variant="body2" fontWeight={700} color={trendColor}>
                        {isFlat ? '—' : `${isUp ? '+' : ''}${Number(growth).toFixed(1)}%`}
                    </Typography>
                    <Typography variant="caption" color={DARK.textMuted} sx={{ fontSize: '0.6rem', textTransform: 'uppercase' }}>
                        MoM Growth
                    </Typography>
                </Stack>
            )}
        </Paper>
    );
};

// ─── Chart Card (Dark Theme) ────────────────────────────────────────
const DarkChartCard = ({ title, children, height = 300 }) => (
    <Paper sx={{ p: 3, borderRadius: '4px', bgcolor: 'white', boxShadow: '0 1px 3px rgba(0,0,0,0.04)' }}>
        <Stack direction="row" alignItems="center" spacing={1.5} sx={{ mb: 3 }}>
            <Box sx={{ p: 0.75, bgcolor: DARK.bg, borderRadius: '4px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <LayoutGrid size={16} color="white" />
            </Box>
            <Typography variant="subtitle2" fontWeight={800} color={DARK.bg}
                sx={{ textTransform: 'uppercase', letterSpacing: '0.04em', fontSize: '0.75rem' }}>
                {title}
            </Typography>
        </Stack>
        <Box sx={{ height }}>
            {children}
        </Box>
    </Paper>
);

const MerchantInsights = () => {
    const [activeTab, setActiveTab] = useState(0);
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [monthOffset, setMonthOffset] = useState(1);

    useEffect(() => { fetchInsights(); }, [monthOffset]);

    const fetchInsights = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const date = new Date();
            date.setMonth(date.getMonth() - monthOffset);
            const year = date.getFullYear();
            const month = date.getMonth() + 1;

            const res = await fetch(`/api/business/insights/overview?year=${year}&month=${month}`, {
                headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
            });
            if (res.ok) setData(await res.json());
        } catch (error) { console.error('Failed to fetch insights', error); }
        finally { setLoading(false); }
    };

    const downloadPdf = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const date = new Date();
            date.setMonth(date.getMonth() - monthOffset);
            const year = date.getFullYear();
            const month = date.getMonth() + 1;

            const response = await fetch(`/api/business/insights/pdf?year=${year}&month=${month}`, {
                headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
            });
            if (response.ok) {
                const blob = await response.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `Merchant_Insight_${year}_${month}.pdf`;
                document.body.appendChild(a);
                a.click();
                a.remove();
            }
        } catch (error) { console.error('Download failed', error); }
    };

    const currentDate = new Date();
    currentDate.setMonth(currentDate.getMonth() - monthOffset);
    const monthLabel = currentDate.toLocaleString('default', { month: 'long', year: 'numeric' });

    if (loading) return (
        <Box sx={{ minHeight: '100vh', bgcolor: DARK.bg, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Typography color={DARK.textPrimary} fontWeight={600}>Loading Insights...</Typography>
        </Box>
    );
    if (!data) return (
        <Box sx={{ minHeight: '100vh', bgcolor: DARK.bg, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Typography color={DARK.textSecondary}>No Data Available</Typography>
        </Box>
    );

    const TABS = [
        { label: 'Business Overview', icon: <LayoutGrid size={16} /> },
        { label: 'Business Achievements', icon: <Award size={16} /> },
        { label: 'Consumer Loyalty', icon: <Users size={16} /> },
        { label: 'Who Are Your Customers?', icon: <PieChart size={16} /> },
    ];

    return (
        <Box sx={{ minHeight: '100vh', bgcolor: DARK.bg }}>
            {/* Header */}
            <Box sx={{
                bgcolor: DARK.bg, borderBottom: `1px solid ${DARK.border}`,
                px: 3, py: 2, display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                position: 'sticky', top: 0, zIndex: 10,
            }}>
                <Stack direction="row" alignItems="center" spacing={2}>
                    <Typography variant="h6" fontWeight={800} color="#22d3ee" sx={{ letterSpacing: '0.1em' }}>
                        MAGNATI
                    </Typography>
                    <Box sx={{ width: 1, height: 24, bgcolor: DARK.border }} />
                    <Typography variant="caption" color={DARK.textMuted}>Payment into Possibilities</Typography>
                </Stack>

                <Stack direction="row" alignItems="center" spacing={2}>
                    {/* Month Navigator */}
                    <Box sx={{
                        display: 'flex', alignItems: 'center',
                        bgcolor: DARK.card, borderRadius: '8px', border: `1px solid ${DARK.border}`, p: '2px',
                    }}>
                        <IconButton size="small" onClick={() => setMonthOffset(m => m + 1)} sx={{ color: DARK.textSecondary }}>
                            <ChevronLeft size={16} />
                        </IconButton>
                        <Typography variant="body2" fontWeight={700} color={DARK.textPrimary}
                            sx={{ px: 2, minWidth: 130, textAlign: 'center', fontSize: '13px' }}>
                            {monthLabel}
                        </Typography>
                        <IconButton size="small" onClick={() => setMonthOffset(m => Math.max(0, m - 1))} sx={{ color: DARK.textSecondary }}>
                            <ChevronRight size={16} />
                        </IconButton>
                    </Box>

                    {/* Download Button */}
                    <Box onClick={downloadPdf}
                        sx={{
                            display: 'flex', alignItems: 'center', gap: 1,
                            px: 2, py: 1, borderRadius: '8px', cursor: 'pointer',
                            bgcolor: DARK.accent, color: 'white', fontSize: '13px', fontWeight: 700,
                            transition: 'all 0.15s', '&:hover': { bgcolor: '#e04950' },
                        }}>
                        <Download size={14} /> DOWNLOAD REPORT
                    </Box>
                </Stack>
            </Box>

            {/* Navigation Tabs */}
            <Box sx={{ bgcolor: `${DARK.card}80`, borderBottom: `1px solid ${DARK.border}` }}>
                <Tabs value={activeTab} onChange={(_, v) => setActiveTab(v)}
                    variant="scrollable" scrollButtons="auto"
                    sx={{
                        '& .MuiTab-root': {
                            color: DARK.textMuted, fontWeight: 700, fontSize: '0.75rem', letterSpacing: '0.06em',
                            textTransform: 'uppercase', minHeight: 56,
                        },
                        '& .Mui-selected': { color: `${DARK.textPrimary} !important` },
                        '& .MuiTabs-indicator': { bgcolor: DARK.accent, height: 3 },
                    }}>
                    {TABS.map((tab, i) => (
                        <Tab key={i} label={tab.label} icon={tab.icon} iconPosition="start" />
                    ))}
                </Tabs>
            </Box>

            {/* Content */}
            <Box sx={{ p: 3, maxWidth: 1600, mx: 'auto' }}>
                {activeTab === 0 && data.overview && (
                    <Stack spacing={3}>
                        {/* KPI Row 1 */}
                        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                            <DarkKpiCard title="SALES (AED)" value={data.overview.sales?.formattedValue} growth={data.overview.sales?.momGrowth} trend={data.overview.sales?.trend} icon={CreditCard} />
                            <DarkKpiCard title="TRANSACTIONS" value={data.overview.transactions?.formattedValue} growth={data.overview.transactions?.momGrowth} trend={data.overview.transactions?.trend} icon={LayoutGrid} />
                            <DarkKpiCard title="CUSTOMERS" value={data.overview.customers?.formattedValue} growth={data.overview.customers?.momGrowth} trend={data.overview.customers?.trend} icon={Users} />
                        </Box>

                        {/* KPI Row 2 */}
                        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                            <DarkKpiCard title="MAX DAILY SALES (AED)" value={data.overview.peakStats?.maxDailySales?.formattedValue} growth={data.overview.peakStats?.maxDailySales?.momGrowth} trend={data.overview.peakStats?.maxDailySales?.trend} />
                            <DarkKpiCard title="MAX NO. OF TXNS IN A DAY" value={data.overview.peakStats?.maxTxnsInDay?.formattedValue} growth={data.overview.peakStats?.maxTxnsInDay?.momGrowth} trend={data.overview.peakStats?.maxTxnsInDay?.trend} />
                            <DarkKpiCard title="HIGHEST TXN VALUE (AED)" value={data.overview.peakStats?.highestTxnValue?.formattedValue} growth={data.overview.peakStats?.highestTxnValue?.momGrowth} trend={data.overview.peakStats?.highestTxnValue?.trend} />
                        </Box>

                        {/* Charts */}
                        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' }, gap: 2.5 }}>
                            <DarkChartCard title="Sales by Day of Week">
                                <ResponsiveContainer width="100%" height="100%">
                                    <BarChart data={data.overview.salesByDayOfWeek}>
                                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                        <XAxis dataKey="label" stroke="#94a3b8" tick={{ fontSize: 12 }} />
                                        <YAxis stroke="#94a3b8" tick={{ fontSize: 12 }} />
                                        <Tooltip content={<DarkTooltip />} />
                                        <Bar dataKey="value" name="Sales" fill={DARK.barPrimary} radius={[4, 4, 0, 0]} barSize={40} />
                                    </BarChart>
                                </ResponsiveContainer>
                            </DarkChartCard>

                            <DarkChartCard title="Transactions by Day of Week">
                                <ResponsiveContainer width="100%" height="100%">
                                    <BarChart data={data.overview.transactionsByDayOfWeek}>
                                        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                        <XAxis dataKey="label" stroke="#94a3b8" tick={{ fontSize: 12 }} />
                                        <YAxis stroke="#94a3b8" tick={{ fontSize: 12 }} />
                                        <Tooltip content={<DarkTooltip />} />
                                        <Bar dataKey="value" name="Transactions" fill={DARK.barSecondary} radius={[4, 4, 0, 0]} barSize={40} />
                                    </BarChart>
                                </ResponsiveContainer>
                            </DarkChartCard>
                        </Box>
                    </Stack>
                )}

                {activeTab === 1 && data.achievements && (
                    <Stack spacing={3}>
                        <DarkChartCard title="Daily Sales & Count" height={350}>
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={data.achievements.dailySalesAndCount}>
                                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                    <XAxis dataKey="label" stroke="#94a3b8" tickFormatter={(v) => v?.slice?.(-2) || v} />
                                    <YAxis yAxisId="left" stroke={DARK.accent} tick={{ fontSize: 11 }} />
                                    <YAxis yAxisId="right" orientation="right" stroke={DARK.barSecondary} tick={{ fontSize: 11 }} />
                                    <Tooltip content={<DarkTooltip />} />
                                    <Bar yAxisId="left" dataKey="value" name="Sales" fill={DARK.accent} radius={[2, 2, 0, 0]} />
                                    <Bar yAxisId="right" dataKey="value2" name="Txn Count" fill={DARK.barSecondary} radius={[2, 2, 0, 0]} />
                                </BarChart>
                            </ResponsiveContainer>
                        </DarkChartCard>

                        <DarkChartCard title="Unique Customers by Day" height={250}>
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={data.achievements.uniqueCustomersByDay}>
                                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                    <XAxis dataKey="label" stroke="#94a3b8" tickFormatter={(v) => v?.slice?.(-2) || v} />
                                    <YAxis stroke="#94a3b8" tick={{ fontSize: 11 }} />
                                    <Tooltip content={<DarkTooltip />} />
                                    <Bar dataKey="value" name="Customers" fill={DARK.barPrimary} stroke={DARK.barSecondary} strokeWidth={1} radius={[2, 2, 0, 0]} />
                                </BarChart>
                            </ResponsiveContainer>
                        </DarkChartCard>
                    </Stack>
                )}
            </Box>
        </Box>
    );
};

export default MerchantInsights;
