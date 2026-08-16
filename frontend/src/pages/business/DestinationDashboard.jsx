import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Stack, Button } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import {
    ComposedChart, BarChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip,
    ResponsiveContainer, Legend,
} from 'recharts';
import { Globe2, Home, Percent, RefreshCw, AlertTriangle } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import SkeletonLoader from '../../components/SkeletonLoader';
import { exportToCSV } from '../../utils/exportUtils';
import { createFmt, formatCompactCurrency, formatNumber } from '../../utils/formatters';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer, chartTooltipStyle } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

// ─── Local design tokens ─────────────────────────────────────────
// Every colour routes through a CSS variable with a light-mode fallback,
// matching DailyMerchantDashboard / VolumeRevenueSummary so the page re-skins
// under html.dark + ThemeContext instead of staying hardcoded.
const T = {
    card:     'var(--bg-card, #ffffff)',
    subtle:   'var(--bg-subtle, #f8fafc)',
    hover:    'var(--bg-hover, #f8fafc)',
    border:   'var(--border, #e2e8f0)',
    borderLt: 'var(--border-light, #eef2f7)',
    text:     'var(--text, #0f172a)',
    textSec:  'var(--text-secondary, #475569)',
    textMut:  'var(--text-muted, #94a3b8)',
    brand:    'var(--brand, #2563eb)',
    success:  'var(--success, #059669)',
    danger:   'var(--danger, #dc2626)',
    warning:  'var(--warning, #d97706)',
    // Two series colours — same palette conventions as the VolumeRevenueSummary
    // chart (indigo primary series + a second house accent).
    domestic: 'var(--accent-indigo, #6366f1)',
    intl:     'var(--accent-cyan, #06b6d4)',
};

// Compute date range from preset — LOCAL date components, not toISOString()
// (which shifts a day west of UTC; see VolumeRevenueSummary for the war story).
const fmtDate = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

const last30Days = () => {
    const now = new Date();
    const start = new Date(now);
    start.setDate(start.getDate() - 29);
    return { startDate: fmtDate(start), endDate: fmtDate(now) };
};

const computeDateRange = (preset) => {
    const now = new Date();
    switch (preset) {
        case 'TODAY': return { startDate: fmtDate(now), endDate: fmtDate(now) };
        case 'MONTH': return { startDate: fmtDate(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmtDate(now) };
        case 'LAST_MONTH': return {
            startDate: fmtDate(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
            endDate: fmtDate(new Date(now.getFullYear(), now.getMonth(), 0))
        };
        case 'YEAR': return { startDate: fmtDate(new Date(now.getFullYear(), 0, 1)), endDate: fmtDate(now) };
        case 'PY': return {
            startDate: fmtDate(new Date(now.getFullYear() - 1, 0, 1)),
            endDate: fmtDate(new Date(now.getFullYear() - 1, 11, 31))
        };
        default: return {};
    }
};

const BREAKDOWN_TABS = [
    { key: 'scheme',   label: 'Scheme' },
    { key: 'cardType', label: 'Card type' },
    { key: 'channel',  label: 'Channel' },
    { key: 'mcc',      label: 'MCC' },
];

// Inline error state with retry — sections must NEVER render silent zeros.
const SectionError = ({ message, onRetry }) => (
    <Paper sx={{
        p: 2.5, borderRadius: 'var(--radius-lg, 14px)', border: '1px solid var(--danger-border, #fecaca)',
        bgcolor: 'var(--danger-bg, #fef2f2)', display: 'flex', alignItems: 'center',
        justifyContent: 'space-between', gap: 2, flexWrap: 'wrap',
    }}>
        <Stack direction="row" spacing={1.5} alignItems="center">
            <AlertTriangle size={18} color="var(--danger, #dc2626)" />
            <Typography variant="body2" fontWeight={600} color="var(--danger-text, #991b1b)">
                {message}
            </Typography>
        </Stack>
        <Button disableElevation variant="outlined" size="small" startIcon={<RefreshCw size={14} />}
            onClick={onRetry}
            sx={{ textTransform: 'none', fontWeight: 700, borderRadius: 2, color: T.danger, borderColor: 'var(--danger-border, #fecaca)' }}>
            Retry
        </Button>
    </Paper>
);

const LegendDot = ({ color, label }) => (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: color }} />
        <Typography sx={{ fontSize: '0.72rem', fontWeight: 600, color: T.textSec }}>{label}</Typography>
    </Box>
);

const DestinationDashboard = () => {
    const { currencySymbol, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const formatCurrency = fmt.currency; // compact money for tiles/tooltips
    const formatMoney = fmt.money;       // exact money for tables

    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({
        datePreset: 'CUSTOM',
        ...last30Days(), // default window = last 30 days, mirrored to the backend default
        schemeList: [], cardTypeList: [], channelList: [], mccList: [],
        midList: [], sidList: [], partnerList: [], rmList: [], teamLeaderList: [],
        industryList: [], sectorList: [], terminalTypeList: [],
        // destinationList intentionally absent — destination IS the split on
        // this page; the backend ignores it and the drawer hides the control.
        merchantName: '',
    });
    const [filterVersion, setFilterVersion] = useState(0);

    // Per-section state so one failing endpoint doesn't blank the whole page.
    const [kpiData, setKpiData] = useState(null);
    const [kpiLoading, setKpiLoading] = useState(true);
    const [kpiError, setKpiError] = useState(false);

    const [trendData, setTrendData] = useState([]);
    const [trendLoading, setTrendLoading] = useState(true);
    const [trendError, setTrendError] = useState(false);

    const [breakdownTab, setBreakdownTab] = useState('scheme');
    const [breakdownData, setBreakdownData] = useState([]);
    const [breakdownLoading, setBreakdownLoading] = useState(true);
    const [breakdownError, setBreakdownError] = useState(false);

    const [merchantsData, setMerchantsData] = useState([]);
    const [merchantsLoading, setMerchantsLoading] = useState(true);
    const [merchantsError, setMerchantsError] = useState(false);

    const fetchKpis = useCallback(async (f) => {
        setKpiLoading(true); setKpiError(false);
        try {
            const res = await api.post('/business/destination-dashboard/kpis', f);
            setKpiData(res.data);
        } catch (e) {
            console.error('Failed to load destination KPIs', e);
            setKpiError(true); setKpiData(null);
        } finally { setKpiLoading(false); }
    }, []);

    const fetchTrend = useCallback(async (f) => {
        setTrendLoading(true); setTrendError(false);
        try {
            // Send NO dates for the trend so the server applies its default
            // 12-month window — the monthly context should stay wider than the
            // 30-day KPI window, and this keeps the choice consistent.
            const res = await api.post('/business/destination-dashboard/trend', { ...f, startDate: null, endDate: null });
            setTrendData(res.data || []);
        } catch (e) {
            console.error('Failed to load destination trend', e);
            setTrendError(true); setTrendData([]);
        } finally { setTrendLoading(false); }
    }, []);

    const fetchBreakdown = useCallback(async (f, dimension) => {
        setBreakdownLoading(true); setBreakdownError(false);
        try {
            const res = await api.post(`/business/destination-dashboard/breakdown/${dimension}`, f);
            setBreakdownData(res.data || []);
        } catch (e) {
            console.error('Failed to load destination breakdown', e);
            setBreakdownError(true); setBreakdownData([]);
        } finally { setBreakdownLoading(false); }
    }, []);

    const fetchMerchants = useCallback(async (f) => {
        setMerchantsLoading(true); setMerchantsError(false);
        try {
            const res = await api.post('/business/destination-dashboard/top-merchants?limit=15', f);
            setMerchantsData(res.data || []);
        } catch (e) {
            console.error('Failed to load destination top merchants', e);
            setMerchantsError(true); setMerchantsData([]);
        } finally { setMerchantsLoading(false); }
    }, []);

    // ONE fetch effect per concern: mount, filter apply (filterVersion) and
    // tenant switch (tenantVersion) all funnel through it — same shape as
    // VolumeRevenueSummary so a page open fires each endpoint exactly once.
    useEffect(() => {
        fetchKpis(filters);
        fetchTrend(filters);
        fetchMerchants(filters);
    }, [filterVersion, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

    // Breakdown also re-fetches on tab change with the current filters.
    useEffect(() => {
        fetchBreakdown(filters, breakdownTab);
    }, [breakdownTab, filterVersion, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

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

    // ── KPI cards ──
    const priorHasData = kpiData?.priorWindowHasData === true;
    const kpis = useMemo(() => {
        if (!kpiData) return [];
        const dom = kpiData.domestic || {};
        const intl = kpiData.international || {};
        return [
            {
                title: 'Domestic Volume', value: formatCurrency(dom.volume),
                icon: Home, color: T.domestic,
                // Suppress trend arrows entirely when the prior window is empty —
                // a growth % against nothing is noise, not signal.
                trend: priorHasData ? dom.volumeGrowthPct : undefined,
                trendLabel: priorHasData ? 'vs prior period' : 'no prior-period data',
                subtitle: `${formatNumber(dom.txns)} txns · ${Number(dom.effectiveRateBps || 0).toFixed(1)} bps`,
            },
            {
                title: 'International Volume', value: formatCurrency(intl.volume),
                icon: Globe2, color: T.intl,
                trend: priorHasData ? intl.volumeGrowthPct : undefined,
                trendLabel: priorHasData ? 'vs prior period' : 'no prior-period data',
                // Mixed-currency caveat only applies on the cardholder-basis
                // fallback path — the settlement pre-aggregate needs no caveat.
                subtitle: kpiData.basis !== 'SETTLEMENT'
                    ? 'International volume is summed in cardholder currencies'
                    : undefined,
            },
            {
                title: 'International Share', value: `${Number(kpiData.internationalSharePct || 0).toFixed(1)}%`,
                icon: Percent, color: 'var(--accent-purple, #8b5cf6)',
                subtitle: `Domestic ${Number(kpiData.domesticSharePct || 0).toFixed(1)}%`,
                trendLabel: 'of total volume',
            },
            {
                title: 'DCC Opt-in Rate', value: `${Number(intl.dccOptInRatePct || 0).toFixed(1)}%`,
                icon: Percent, color: 'var(--accent-pink, #ec4899)',
                subtitle: `Missed ${formatCurrency(intl.dccMissedVolume)}`,
                trendLabel: `${formatCurrency(intl.dccOptInVolume)} opted in`,
            },
        ];
    }, [kpiData, priorHasData, formatCurrency]);

    // ── Trend chart data: stacked dom/intl volume + intl share line ──
    const chartData = useMemo(() => trendData.map(d => {
        const [y, m] = String(d.month || '').split('-');
        const dateObj = new Date(parseInt(y), parseInt(m) - 1);
        const dom = Number(d.domVolume) || 0;
        const intl = Number(d.intlVolume) || 0;
        const total = dom + intl;
        return {
            monthShort: dateObj.toLocaleDateString('en-US', { month: 'short' }),
            monthLong: dateObj.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }),
            domVolume: dom, intlVolume: intl,
            domTxns: Number(d.domTxns) || 0, intlTxns: Number(d.intlTxns) || 0,
            domMsf: Number(d.domMsf) || 0, intlMsf: Number(d.intlMsf) || 0,
            intlSharePct: total > 0 ? (intl / total) * 100 : 0,
        };
    }), [trendData]);

    const TrendTooltip = ({ active, payload }) => {
        if (!active || !payload || !payload.length) return null;
        const row = payload[0].payload;
        const line = (label, value, color) => (
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                <span style={{ color: 'var(--text-secondary, #64748b)' }}>{label}</span>
                <span style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: color || 'inherit' }}>{value}</span>
            </div>
        );
        return (
            <div style={chartTooltipStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6, color: T.text }}>{row.monthLong}</div>
                {line('Domestic volume', formatCurrency(row.domVolume))}
                {line('International volume', formatCurrency(row.intlVolume))}
                {line('Intl share', `${row.intlSharePct.toFixed(1)}%`, T.intl)}
                {line('Domestic MSF', formatCurrency(row.domMsf))}
                {line('International MSF', formatCurrency(row.intlMsf))}
            </div>
        );
    };

    // ── Breakdown chart data (horizontal grouped bars, dom vs intl) ──
    const breakdownChartData = useMemo(() => breakdownData.map(d => ({
        name: d.dimensionValue || 'Unknown',
        domVolume: Number(d.domVolume) || 0,
        intlVolume: Number(d.intlVolume) || 0,
        domTxns: Number(d.domTxns) || 0,
        intlTxns: Number(d.intlTxns) || 0,
    })), [breakdownData]);

    const BreakdownTooltip = ({ active, payload }) => {
        if (!active || !payload || !payload.length) return null;
        const row = payload[0].payload;
        return (
            <div style={chartTooltipStyle}>
                <div style={{ fontWeight: 700, marginBottom: 6, color: T.text }}>{row.name}</div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18 }}>
                    <span style={{ color: 'var(--text-secondary, #64748b)' }}>Domestic</span>
                    <span style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                        {formatCurrency(row.domVolume)} · {formatNumber(row.domTxns)} txns
                    </span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 18, marginTop: 2 }}>
                    <span style={{ color: 'var(--text-secondary, #64748b)' }}>International</span>
                    <span style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                        {formatCurrency(row.intlVolume)} · {formatNumber(row.intlTxns)} txns
                    </span>
                </div>
            </div>
        );
    };

    // ── Top merchants grid ──
    const merchantRows = useMemo(() => merchantsData.map((m, i) => ({ id: m.mid || i, ...m })), [merchantsData]);

    const merchantColumns = [
        {
            field: 'mid', headerName: 'MID', width: 130,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', color: T.textSec }}>
                    {params.value || '-'}
                </Typography>
            )
        },
        {
            field: 'merchantName', headerName: 'Merchant', flex: 1.4, minWidth: 200,
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color={T.text} noWrap>{params.value}</Typography>
        },
        {
            field: 'domVolume', headerName: 'Domestic volume', flex: 1, minWidth: 130, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatMoney(params.value)}</Typography>
        },
        {
            field: 'intlVolume', headerName: 'Intl volume', flex: 1, minWidth: 130, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatMoney(params.value)}</Typography>
        },
        {
            field: 'intlMsf', headerName: 'Intl MSF', flex: 0.9, minWidth: 110, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (params) => <Typography variant="body2" fontWeight="500" color={T.textSec} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatMoney(params.value)}</Typography>
        },
        {
            field: 'totalVolume', headerName: 'Total', flex: 1, minWidth: 130, align: 'right', headerAlign: 'right', type: 'number',
            renderCell: (params) => <Typography variant="body2" fontWeight="700" color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatMoney(params.value)}</Typography>
        },
        {
            field: 'intlSharePct', headerName: 'Intl share %', flex: 1, minWidth: 140, type: 'number', align: 'right', headerAlign: 'right',
            renderCell: (params) => {
                const pct = Math.max(0, Math.min(100, Number(params.value) || 0));
                return (
                    <Box sx={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 1 }}>
                        <Box sx={{ width: 64, height: 5, bgcolor: T.subtle, borderRadius: 3, overflow: 'hidden' }}>
                            <Box sx={{ width: `${pct}%`, height: '100%', bgcolor: T.intl, borderRadius: 3 }} />
                        </Box>
                        <Typography variant="body2" fontWeight="700" color={T.text} sx={{ fontVariantNumeric: 'tabular-nums', minWidth: 44, textAlign: 'right' }}>
                            {pct.toFixed(1)}%
                        </Typography>
                    </Box>
                );
            }
        },
    ];

    const tabBtnSx = (active) => ({
        height: 34, px: 1.75, borderRadius: 2, textTransform: 'none', fontWeight: 700,
        fontSize: '0.78rem', boxShadow: 'none', minWidth: 0,
        bgcolor: active ? T.brand : T.card,
        color: active ? '#fff' : T.textSec,
        border: '1px solid', borderColor: active ? T.brand : T.border,
        '&:hover': { bgcolor: active ? 'var(--brand-dark, #1d4ed8)' : T.subtle },
    });

    const windowLabel = kpiData?.start && kpiData?.end
        ? `${kpiData.start} → ${kpiData.end}`
        : `${filters.startDate} → ${filters.endDate}`;

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Destination Dashboard"
                subtitle={`Domestic vs international split · ${windowLabel}`}
                icon={Globe2}
                onExport={() => exportToCSV(merchantRows, 'destination_top_merchants')}
                onRunReport={handleRunReport} onFilterChange={handleFilterChange}
                loading={kpiLoading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(s => !s)} filters={filters}
            />
            <BusinessFilters
                filters={filters}
                onChange={handleAdvancedFilterChange}
                onApply={handleRunReport}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
                hideDestination
            />

            {/* Shared top banner when the KPI fetch fails */}
            {kpiError && (
                <Box mb={3}>
                    <SectionError message="Failed to load destination KPIs. The rest of the page may be incomplete."
                        onRetry={() => fetchKpis(filters)} />
                </Box>
            )}

            {kpiLoading ? (
                <Box mb={3}><SkeletonLoader variant="kpi-row" count={4} /></Box>
            ) : !kpiError && (
                <Box mb={3}><KpiCards cards={kpis} /></Box>
            )}

            {/* ── Monthly trend: stacked dom/intl volume + intl share line ── */}
            <Box mb={3}>
                {trendError ? (
                    <SectionError message="Failed to load the monthly destination trend."
                        onRetry={() => fetchTrend(filters)} />
                ) : trendLoading ? (
                    <SkeletonLoader variant="chart" />
                ) : (
                    <Paper sx={{
                        p: '20px 22px 12px', borderRadius: 'var(--radius-lg, 14px)',
                        bgcolor: T.card, border: `1px solid ${T.border}`,
                        boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(15,23,42,0.04))',
                    }}>
                        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', mb: 2, flexWrap: 'wrap', gap: 1 }}>
                            <Box>
                                <Typography sx={{ fontSize: '0.95rem', fontWeight: 700, color: T.text, letterSpacing: '-0.01em' }}>
                                    Destination volume trend
                                </Typography>
                                <Typography sx={{ fontSize: '0.75rem', color: T.textSec }}>
                                    Monthly domestic vs international volume with international share overlay (last 12 months)
                                </Typography>
                            </Box>
                            <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
                                <LegendDot color={T.domestic} label="Domestic" />
                                <LegendDot color={T.intl} label="International" />
                                <LegendDot color={T.warning} label="Intl share %" />
                            </Box>
                        </Box>
                        {chartData.length === 0 ? (
                            <Typography sx={{ py: 6, textAlign: 'center', fontSize: '0.85rem', color: T.textMut }}>
                                No trend data for the selected filters.
                            </Typography>
                        ) : (
                            <ResponsiveContainer width="100%" height={280}>
                                <ComposedChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 4 }}>
                                    <CartesianGrid strokeDasharray="3 3" stroke={T.borderLt} vertical={false} />
                                    <XAxis dataKey="monthShort" tick={{ fontSize: 11, fill: T.textMut }}
                                        axisLine={{ stroke: T.borderLt }} tickLine={false} />
                                    <YAxis yAxisId="vol" tick={{ fontSize: 11, fill: T.textMut }} axisLine={false} tickLine={false}
                                        tickFormatter={(v) => formatCompactCurrency(v)} width={82} />
                                    <YAxis yAxisId="share" orientation="right" domain={[0, 100]}
                                        tick={{ fontSize: 11, fill: T.textMut }} axisLine={false} tickLine={false}
                                        tickFormatter={(v) => `${v}%`} width={44} />
                                    <Tooltip content={<TrendTooltip />} cursor={{ fill: 'var(--bg-hover, rgba(148,163,184,0.08))' }} />
                                    <Bar yAxisId="vol" dataKey="domVolume" stackId="vol" fill={T.domestic} fillOpacity={0.85} maxBarSize={44} />
                                    <Bar yAxisId="vol" dataKey="intlVolume" stackId="vol" fill={T.intl} fillOpacity={0.85} radius={[4, 4, 0, 0]} maxBarSize={44} />
                                    <Line yAxisId="share" type="monotone" dataKey="intlSharePct" stroke={T.warning} strokeWidth={2}
                                        dot={{ r: 3, fill: T.warning, strokeWidth: 0 }} activeDot={{ r: 5 }} />
                                </ComposedChart>
                            </ResponsiveContainer>
                        )}
                    </Paper>
                )}
            </Box>

            {/* ── Breakdown: dom vs intl by scheme / card type / channel / MCC ── */}
            <Box mb={3}>
                <Paper sx={{
                    p: '20px 22px 16px', borderRadius: 'var(--radius-lg, 14px)',
                    bgcolor: T.card, border: `1px solid ${T.border}`,
                    boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(15,23,42,0.04))',
                }}>
                    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, flexWrap: 'wrap', gap: 1.5 }}>
                        <Box>
                            <Typography sx={{ fontSize: '0.95rem', fontWeight: 700, color: T.text, letterSpacing: '-0.01em' }}>
                                Destination breakdown
                            </Typography>
                            <Typography sx={{ fontSize: '0.75rem', color: T.textSec }}>
                                Domestic vs international volume by {BREAKDOWN_TABS.find(t => t.key === breakdownTab)?.label.toLowerCase()}
                            </Typography>
                        </Box>
                        <Stack direction="row" spacing={1}>
                            {BREAKDOWN_TABS.map(tab => (
                                <Button key={tab.key} disableElevation variant="contained"
                                    sx={tabBtnSx(breakdownTab === tab.key)}
                                    onClick={() => setBreakdownTab(tab.key)}>
                                    {tab.label}
                                </Button>
                            ))}
                        </Stack>
                    </Box>
                    {breakdownError ? (
                        <SectionError message={`Failed to load the ${BREAKDOWN_TABS.find(t => t.key === breakdownTab)?.label.toLowerCase()} breakdown.`}
                            onRetry={() => fetchBreakdown(filters, breakdownTab)} />
                    ) : breakdownLoading ? (
                        <SkeletonLoader variant="chart" />
                    ) : breakdownChartData.length === 0 ? (
                        <Typography sx={{ py: 6, textAlign: 'center', fontSize: '0.85rem', color: T.textMut }}>
                            No breakdown data for the selected filters.
                        </Typography>
                    ) : (
                        <ResponsiveContainer width="100%" height={Math.max(220, breakdownChartData.length * 52)}>
                            <BarChart data={breakdownChartData} layout="vertical"
                                margin={{ top: 4, right: 16, left: 8, bottom: 4 }} barGap={2}>
                                <CartesianGrid strokeDasharray="3 3" stroke={T.borderLt} horizontal={false} />
                                <XAxis type="number" tick={{ fontSize: 11, fill: T.textMut }} axisLine={false} tickLine={false}
                                    tickFormatter={(v) => formatCompactCurrency(v)} />
                                <YAxis type="category" dataKey="name" width={110}
                                    tick={{ fontSize: 11, fill: T.textSec, fontWeight: 600 }}
                                    axisLine={{ stroke: T.borderLt }} tickLine={false} />
                                <Tooltip content={<BreakdownTooltip />} cursor={{ fill: 'var(--bg-hover, rgba(148,163,184,0.08))' }} />
                                <Legend formatter={(value) => (
                                    <span style={{ fontSize: '0.75rem', fontWeight: 600, color: T.textSec }}>
                                        {value === 'domVolume' ? 'Domestic' : 'International'}
                                    </span>
                                )} />
                                <Bar dataKey="domVolume" fill={T.domestic} fillOpacity={0.85} radius={[0, 4, 4, 0]} maxBarSize={16} />
                                <Bar dataKey="intlVolume" fill={T.intl} fillOpacity={0.85} radius={[0, 4, 4, 0]} maxBarSize={16} />
                            </BarChart>
                        </ResponsiveContainer>
                    )}
                </Paper>
            </Box>

            {/* ── Top merchants by international exposure ── */}
            {merchantsError ? (
                <SectionError message="Failed to load the top merchants table."
                    onRetry={() => fetchMerchants(filters)} />
            ) : (
                <Paper sx={premiumTableWrapper}>
                    <Box sx={{ px: 2.5, pt: 2, pb: 1.5, borderBottom: `1px solid ${T.borderLt}`,
                        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap' }}>
                        <Box>
                            <Typography sx={{ fontSize: '0.9rem', fontWeight: 700, color: T.text }}>
                                Top merchants
                            </Typography>
                            <Typography sx={{ fontSize: '0.75rem', color: T.textSec }}>
                                Highest-volume merchants with their domestic / international split
                            </Typography>
                        </Box>
                        <Button disableElevation variant="outlined" size="small"
                            onClick={() => exportToCSV(merchantRows, 'destination_top_merchants')}
                            sx={{ textTransform: 'none', fontWeight: 700, borderRadius: 2, color: T.textSec, borderColor: T.border }}>
                            Export CSV
                        </Button>
                    </Box>
                    <DataGrid rows={merchantRows} columns={merchantColumns} loading={merchantsLoading}
                        rowHeight={52} disableRowSelectionOnClick
                        slots={{ toolbar: GridToolbar }}
                        slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 }, printOptions: { disableToolbarButton: true } } }}
                        sx={premiumDataGridStyles}
                    />
                </Paper>
            )}
        </Box>
    );
};

export default DestinationDashboard;
