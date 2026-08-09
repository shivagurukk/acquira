import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip,
    ResponsiveContainer, Cell,
} from 'recharts';
import { TrendingUp, TrendingDown, BarChart3, DollarSign, Hash, Percent, Globe2, Users, Receipt, Gauge } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import SkeletonLoader from '../../components/SkeletonLoader';
import { exportToCSV } from '../../utils/exportUtils';
import { formatMsf } from '../../utils/formatters';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer, chartTooltipStyle } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const TrendPill = ({ val }) => {
    if (!val || val === 0) return <Typography variant="caption" color="text.secondary">-</Typography>;
    const isPositive = val > 0;
    return (
        <Chip
            icon={isPositive ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
            label={`${Math.abs(val).toFixed(1)}%`}
            size="small"
            sx={{
                height: 24, bgcolor: isPositive ? 'var(--success-bg, rgba(16, 185, 129, 0.08))' : 'var(--danger-bg, rgba(239, 68, 68, 0.08))',
                color: isPositive ? 'var(--success, #10b981)' : 'var(--danger, #ef4444)', fontWeight: 700, border: 'none',
                fontVariantNumeric: 'tabular-nums',
                '& .MuiChip-icon': { color: 'inherit' }
            }}
        />
    );
};

// Compute date range from preset
// IMPORTANT: format using LOCAL date components, not toISOString() which converts
// to UTC. In timezones east of UTC (e.g. IST = UTC+5:30), new Date(yr, mo, 1)
// is local midnight which translates to the PREVIOUS day in UTC.
const fmt = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

const computeDateRange = (preset) => {
    const now = new Date();
    switch (preset) {
        case 'TODAY': return { startDate: fmt(now), endDate: fmt(now) };
        case 'MONTH': return { startDate: fmt(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmt(now) };
        case 'LAST_MONTH': return {
            startDate: fmt(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
            endDate: fmt(new Date(now.getFullYear(), now.getMonth(), 0))
        };
        case 'YEAR': return { startDate: fmt(new Date(now.getFullYear(), 0, 1)), endDate: fmt(now) };
        case 'PY': return {
            startDate: fmt(new Date(now.getFullYear() - 1, 0, 1)),
            endDate: fmt(new Date(now.getFullYear() - 1, 11, 31))
        };
        default: return {};
    }
};

/* ─── Trend Chart ─────────────────────────────────────────────────
   The anchor the page was missing: a monthly time-series of Volume (bars)
   with MSF revenue overlaid as a line. Bars are re-coloured per-point so
   negative MSF months read correctly, and the whole thing is theme-driven
   via CSS vars so it re-skins with dark mode. Volume here is cardholder
   currency (total_volume) — matched to the table so the two reconcile. */
const VolumeRevenueChart = ({ data, currencySymbol, formatCurrency }) => {
    const brand = 'var(--accent-indigo, #6366f1)';
    const revenue = 'var(--success, #10b981)';
    const axisColor = 'var(--text-muted, #94a3b8)';
    const gridColor = 'var(--border-light, #eef2f7)';

    const CustomTooltip = ({ active, payload, label }) => {
        if (!active || !payload || !payload.length) return null;
        const row = payload[0].payload;
        return (
            <div style={chartTooltipStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6, color: 'var(--text, #0f172a)' }}>{row.monthLong}</div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18 }}>
                    <span style={{ color: 'var(--text-secondary, #64748b)' }}>Volume</span>
                    <span style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(row.volume)}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                    <span style={{ color: 'var(--text-secondary, #64748b)' }}>MSF revenue</span>
                    <span style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: row.msf < 0 ? 'var(--danger, #ef4444)' : 'var(--success, #10b981)' }}>
                        {formatMsf(row.msf, currencySymbol)}
                    </span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                    <span style={{ color: 'var(--text-secondary, #64748b)' }}>Transactions</span>
                    <span style={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{formatNumber(row.count)}</span>
                </div>
            </div>
        );
    };

    return (
        <Paper sx={{
            p: '20px 22px 12px', borderRadius: 'var(--radius-lg, 14px)',
            bgcolor: 'var(--bg-card, #fff)', border: '1px solid var(--border, #e5e7eb)',
            boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(15,23,42,0.04))',
        }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', mb: 2 }}>
                <Box>
                    <Typography sx={{ fontSize: '0.95rem', fontWeight: 700, color: 'var(--text, #0f172a)', letterSpacing: '-0.01em' }}>
                        Volume & revenue trend
                    </Typography>
                    <Typography sx={{ fontSize: '0.75rem', color: 'var(--text-secondary, #64748b)' }}>
                        Monthly volume with MSF revenue overlay
                    </Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
                    <LegendDot color={brand} label="Volume" />
                    <LegendDot color={revenue} label="MSF revenue" />
                </Box>
            </Box>
            <ResponsiveContainer width="100%" height={280}>
                <ComposedChart data={data} margin={{ top: 8, right: 8, left: 8, bottom: 4 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke={gridColor} vertical={false} />
                    <XAxis dataKey="monthShort" tick={{ fontSize: 11, fill: axisColor }}
                        axisLine={{ stroke: gridColor }} tickLine={false} />
                    <YAxis yAxisId="vol" tick={{ fontSize: 11, fill: axisColor }} axisLine={false} tickLine={false}
                        tickFormatter={(v) => `${currencySymbol} ${formatCompact(v)}`} width={70} />
                    <YAxis yAxisId="msf" orientation="right" tick={{ fontSize: 11, fill: axisColor }} axisLine={false} tickLine={false}
                        tickFormatter={(v) => formatCompact(v)} width={50} />
                    <Tooltip content={<CustomTooltip />} cursor={{ fill: 'var(--bg-hover, rgba(148,163,184,0.08))' }} />
                    <Bar yAxisId="vol" dataKey="volume" radius={[4, 4, 0, 0]} maxBarSize={44}>
                        {data.map((entry, i) => (
                            <Cell key={i} fill={brand} fillOpacity={0.85} />
                        ))}
                    </Bar>
                    <Line yAxisId="msf" type="monotone" dataKey="msf" stroke={revenue} strokeWidth={2}
                        dot={{ r: 3, fill: revenue, strokeWidth: 0 }} activeDot={{ r: 5 }} />
                </ComposedChart>
            </ResponsiveContainer>
        </Paper>
    );
};

const LegendDot = ({ color, label }) => (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: color }} />
        <Typography sx={{ fontSize: '0.72rem', fontWeight: 600, color: 'var(--text-secondary, #64748b)' }}>{label}</Typography>
    </Box>
);

const VolumeRevenueSummary = () => {
    const { currencySymbol, formatCurrency: fmtCurrency, tenantVersion } = useAuth();
    const formatCurrency = (val) => fmtCurrency(val, { decimals: 2 });
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);

    const initialRange = computeDateRange('YEAR');
    const [filters, setFilters] = useState({ datePreset: 'YEAR', ...initialRange });

    const [filterVersion, setFilterVersion] = useState(0);

    const fetchReport = useCallback(async (filtersToSend) => {
        setLoading(true);
        try {
            const res = await api.post('/business/volume-revenue-summary', filtersToSend);
            setData(res.data);
        } catch (error) { console.error('Failed to load report', error); }
        finally { setLoading(false); }
    }, []);

    // ONE fetch effect: mount, filter apply (filterVersion), and tenant switch
    // (tenantVersion) all funnel through it. This used to be three separate
    // effects — a [filterVersion] one that also runs on mount, an isFirstRun
    // one, and a [tenantVersion] one gated on isFirstRun having just been
    // cleared by the second — so a single page open fired three identical
    // reports, each holding a pooled connection for the full statement timeout.
    useEffect(() => {
        fetchReport(filters);
    }, [filterVersion, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

    const handleFilterChange = useCallback((keyOrObj, val) => {
        setFilters(prev => {
            let next;
            if (typeof keyOrObj === 'object') {
                next = { ...prev, ...keyOrObj };
            } else {
                next = { ...prev, [keyOrObj]: val };
            }
            if (next.datePreset && next.datePreset !== 'CUSTOM' && keyOrObj?.datePreset) {
                const range = computeDateRange(next.datePreset);
                next = { ...next, ...range };
            }
            return next;
        });
        if (typeof keyOrObj === 'object' && keyOrObj.datePreset && keyOrObj.datePreset !== 'CUSTOM') {
            setFilterVersion(v => v + 1);
        }
    }, []);

    const handleRunReport = useCallback(() => {
        setFilterVersion(v => v + 1);
    }, []);

    const handleAdvancedFilterChange = useCallback((newFilters) => {
        setFilters(newFilters);
    }, []);

    // Table rows: newest-first (as returned by the API), with MoM trend + bar scale.
    const rows = useMemo(() => {
        if (!data.length) return [];
        const maxVol = Math.max(...data.map(d => d.volume || 0));
        return data.map((curr, idx) => {
            const prev = data[idx + 1];
            const momVolPct = prev && prev.volume > 0 ? ((curr.volume - prev.volume) / prev.volume) * 100 : 0;
            const dateParts = curr.month.split('-');
            const dateObj = new Date(parseInt(dateParts[0]), parseInt(dateParts[1]) - 1);
            const monthStr = dateObj.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
            return { id: idx, ...curr, monthParams: { str: monthStr, raw: curr.month }, momVol: momVolPct, maxVol };
        });
    }, [data]);

    // Chart data: chronological ASC (API is DESC), with short + long month labels.
    const chartData = useMemo(() => {
        if (!data.length) return [];
        return data.slice().reverse().map((d) => {
            const [y, m] = d.month.split('-');
            const dateObj = new Date(parseInt(y), parseInt(m) - 1);
            return {
                monthShort: dateObj.toLocaleDateString('en-US', { month: 'short' }),
                monthLong: dateObj.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }),
                volume: Number(d.volume) || 0,
                msf: Number(d.msf) || 0,
                count: Number(d.count) || 0,
            };
        });
    }, [data]);

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalMsf = data.reduce((s, d) => s + (d.msf || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        const totalOptIn = data.reduce((s, d) => s + (d.opt_in_volume || 0), 0);
        const totalIntl = data.reduce((s, d) => s + (d.intl_volume || 0), 0);
        const avgVol = totalVol / data.length;
        const avgTicket = totalCount > 0 ? totalVol / totalCount : 0;
        const takeRateBps = totalVol > 0 ? (totalMsf / totalVol) * 10000 : 0;
        const dccOptInRate = totalVol > 0 ? (totalOptIn / totalVol) * 100 : 0;
        const intlSharePct = totalVol > 0 ? (totalIntl / totalVol) * 100 : 0;
        // Active merchants: most recent month's count is more meaningful than
        // a sum (a merchant active every month would otherwise be over-counted).
        const latestActiveMerchants = data[0]?.active_merchants || 0;
        const avgActiveMerchants = data.reduce((s, d) => s + (d.active_merchants || 0), 0) / data.length;

        const sparkVols = data.slice().reverse().map(d => d.volume || 0);
        const sparkMsf = data.slice().reverse().map(d => d.msf || 0);
        const sparkIntl = data.slice().reverse().map(d => d.intl_volume || 0);
        const sparkActive = data.slice().reverse().map(d => d.active_merchants || 0);
        const latest = data[0]; const prev = data[1];
        const volTrend = prev && prev.volume > 0 ? ((latest.volume - prev.volume) / prev.volume) * 100 : 0;
        const msfTrend = prev && prev.msf > 0 ? ((latest.msf - prev.msf) / prev.msf) * 100 : 0;
        const merchantTrend = prev && prev.active_merchants > 0
            ? ((latest.active_merchants - prev.active_merchants) / prev.active_merchants) * 100 : 0;

        return [
            { title: 'Total Volume', value: `${currencySymbol} ${formatCompact(totalVol)}`, icon: BarChart3, color: 'var(--brand-alt, #3b82f6)', trend: volTrend, trendLabel: 'vs prev month', sparkData: sparkVols },
            { title: 'Total MSF Revenue', value: `${currencySymbol} ${formatCompact(totalMsf)}`, icon: DollarSign, color: 'var(--success, #10b981)', trend: msfTrend, trendLabel: 'vs prev month', sparkData: sparkMsf },
            { title: 'Transaction Count', value: formatNumber(totalCount), icon: Hash, color: 'var(--warning, #f59e0b)', trendLabel: 'monthly counts', sparkData: data.slice().reverse().map(d => d.count || 0) },
            { title: 'Effective MSF Rate', value: `${takeRateBps.toFixed(1)} bps`, icon: Gauge, color: 'var(--accent-cyan, #06b6d4)', trendLabel: 'fee revenue / volume' },
            { title: 'Avg Ticket Size', value: `${currencySymbol} ${formatCompact(avgTicket)}`, icon: Receipt, color: 'var(--accent-purple, #8b5cf6)', trendLabel: 'volume / transaction' },
            { title: 'DCC Opt-in Rate', value: `${dccOptInRate.toFixed(1)}%`, icon: Percent, color: 'var(--accent-pink, #ec4899)', trendLabel: `${currencySymbol} ${formatCompact(totalOptIn)} opted in` },
            { title: 'International Volume', value: `${currencySymbol} ${formatCompact(totalIntl)}`, icon: Globe2, color: 'var(--brand-alt, #3b82f6)', trendLabel: `${intlSharePct.toFixed(1)}% of total`, sparkData: sparkIntl },
            { title: 'Active Merchants', value: formatNumber(latestActiveMerchants), icon: Users, color: 'var(--warning, #f59e0b)', trend: merchantTrend, trendLabel: `avg ${formatNumber(Math.round(avgActiveMerchants))}/mo`, sparkData: sparkActive },
        ];
    }, [data]);

    const columns = [
        {
            field: 'monthParams', headerName: 'Month', flex: 1.2, minWidth: 150,
            sortComparator: (v1, v2) => v1.raw.localeCompare(v2.raw),
            renderCell: (params) => <Typography variant="body2" fontWeight="700" color="var(--text, #1e293b)">{params.value.str}</Typography>
        },
        {
            field: 'count', headerName: 'Count', type: 'number', flex: 0.8, align: 'center', headerAlign: 'center',
            renderCell: (params) => <Chip label={formatNumber(params.value)} size="small" variant="outlined" sx={{ fontWeight: 600, borderColor: 'var(--border, #e2e8f0)', bgcolor: 'var(--bg-subtle, #f8fafc)', color: 'var(--text, inherit)', fontVariantNumeric: 'tabular-nums' }} />
        },
        {
            field: 'volume', headerName: 'Volume', flex: 1.5, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center' }}>
                    <Typography variant="body2" fontWeight="700" color="var(--text, #0f172a)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
                    <Box sx={{ width: '80%', height: 4, bgcolor: 'var(--bg-subtle, #f1f5f9)', borderRadius: 2, mt: 0.5, overflow: 'hidden' }}>
                        <Box sx={{ width: `${(params.value / params.row.maxVol) * 100}%`, height: '100%', bgcolor: 'var(--accent-indigo, #6366f1)', borderRadius: 2 }} />
                    </Box>
                </Box>
            )
        },
        { field: 'momVol', headerName: 'Trend', flex: 0.8, align: 'center', headerAlign: 'center', renderCell: (params) => <TrendPill val={params.value} /> },
        {
            field: 'msf', headerName: 'MSF', flex: 1.2, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color={params.value < 0 ? 'var(--danger, #ef4444)' : 'var(--text, #334155)'} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatMsf(params.value, currencySymbol)}</Typography>
        },
        {
            field: 'intl_volume', headerName: 'Intl Volume', flex: 1.1, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="500" color="var(--text-secondary, #64748b)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        },
        {
            field: 'active_merchants', headerName: 'Active Merchants', flex: 1, align: 'center', headerAlign: 'center',
            renderCell: (params) => <Chip label={formatNumber(params.value)} size="small" variant="outlined" sx={{ fontWeight: 600, borderColor: 'var(--border, #e2e8f0)', bgcolor: 'var(--bg-subtle, #f8fafc)', color: 'var(--text, inherit)', fontVariantNumeric: 'tabular-nums' }} />
        },
        {
            field: 'opt_in_volume', headerName: 'Opt-in volume', flex: 1.2, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="500" color="var(--text-secondary, #64748b)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        }
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Volume & Revenue Statement" subtitle="Monthly financial performance overview"
                icon={BarChart3}
                onExport={() => exportToCSV(rows, 'volume_revenue_summary')}
                onRunReport={handleRunReport} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={handleAdvancedFilterChange} onApply={handleRunReport} isOpen={showFilters} onClose={() => setShowFilters(false)} />

            {loading ? <SkeletonLoader variant="kpi-row" count={8} /> : <KpiCards cards={kpis} />}

            {!loading && chartData.length > 0 && (
                <VolumeRevenueChart data={chartData} currencySymbol={currencySymbol} formatCurrency={formatCurrency} />
            )}

            <Paper sx={premiumTableWrapper}>
                <Box sx={{ px: 2.5, pt: 2, pb: 1.5, borderBottom: '1px solid var(--border-light, #f3f4f6)' }}>
                    <Typography sx={{ fontSize: '0.9rem', fontWeight: 700, color: 'var(--text, #0f172a)' }}>
                        Monthly breakdown
                    </Typography>
                    <Typography sx={{ fontSize: '0.75rem', color: 'var(--text-secondary, #64748b)' }}>
                        Volume, revenue and transaction detail by month
                    </Typography>
                </Box>
                <DataGrid rows={rows} columns={columns} loading={loading} rowHeight={60}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default VolumeRevenueSummary;
