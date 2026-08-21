import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, ToggleButton, ToggleButtonGroup, Chip, Stack, Tooltip as MuiTooltip } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { TrendingUp, Target, DollarSign, Hash, CalendarClock, Info, Gauge, AlertTriangle, TrendingDown, Users, Award } from 'lucide-react';
import {
    ComposedChart, Line, Area, XAxis, YAxis, CartesianGrid, Tooltip as ReTooltip,
    ResponsiveContainer, ReferenceLine, Legend
} from 'recharts';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import api from '../../api/axios';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import BenchmarkRail from '../../components/BenchmarkRail';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer, chartTooltipStyle } from '../../theme/dataGridStyles';
import { useDataBounds } from '../../hooks/useDataBounds';

// ─── Local design tokens (mirror AttritionReport/RetentionReport) ──────────
const T = {
    card:    'var(--bg-card, #ffffff)',
    subtle:  'var(--bg-subtle, #f8fafc)',
    border:  'var(--border, #e2e8f0)',
    text:    'var(--text, #0f172a)',
    textSec: 'var(--text-secondary, #475569)',
    textMut: 'var(--text-muted, #94a3b8)',
    axis:    'var(--text-muted, #94a3b8)',
    grid:    'var(--border-light, #eef2f7)',
};

// Metric registry — maps the backend metricType to label + how to format it.
const METRICS = {
    VOLUME:      { label: 'Volume',       kind: 'currency', icon: TrendingUp, color: 'var(--chart-2)' },
    NET_REVENUE: { label: 'Net Margin',  kind: 'currency', icon: DollarSign, color: 'var(--chart-1)' },
    MSF:         { label: 'MSF Revenue',  kind: 'currency', icon: DollarSign, color: 'var(--chart-3)' },
    TXNS:        { label: 'Transactions', kind: 'count',    icon: Hash,       color: 'var(--chart-4)' },
};
const METRIC_ORDER = ['VOLUME', 'NET_REVENUE', 'MSF', 'TXNS'];

// Target risk status → colour + label. Mirrors riskStatus() in ForecastController.
const RISK_META = {
    LIKELY_TO_EXCEED: { label: 'Likely to Exceed', color: 'var(--success)',    bg: 'var(--success-bg)' },
    ON_TRACK:         { label: 'On Track',         color: 'var(--success)',    bg: 'var(--success-bg)' },
    AT_RISK:          { label: 'At Risk',          color: 'var(--warning)',    bg: 'var(--warning-bg)' },
    BEHIND:           { label: 'Behind',           color: 'var(--danger)',     bg: 'var(--danger-bg)' },
    NO_TARGET:        { label: 'No Target',        color: 'var(--text-muted)', bg: 'var(--bg-subtle)' },
};

const ForecastingBenchmarking = () => {
    const { currencySymbol, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);

    const [summary, setSummary] = useState(null);
    const [seasonal, setSeasonal] = useState(null);
    const [trend, setTrend] = useState(null);
    const [trendMetric, setTrendMetric] = useState('VOLUME');
    const [loading, setLoading] = useState(false);

    // Slice 2: risk predictions + benchmarks
    const [churn, setChurn] = useState(null);
    const [margin, setMargin] = useState(null);
    const [peer, setPeer] = useState(null);
    const [rm, setRm] = useState(null);
    const [riskTab, setRiskTab] = useState('churn');   // churn | margin
    const [benchTab, setBenchTab] = useState('rm');    // rm | peer
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({
        startDate: '', endDate: '',
        partnerList: [], mccList: [], industryList: [],
        rmList: [], teamLeaderList: [],
        merchantName: '', midList: [], sidList: [],
        schemeList: [], cardTypeList: [], channelList: [], destinationList: [],
        datePreset: 'MONTH'
    });

    const { boundsLoaded, latest } = useDataBounds(tenantVersion);

    const fmtMeasure = (kind, v) =>
        v == null ? '-' : (kind === 'count' ? Number(v).toLocaleString('en-US') : fmt.currency(Number(v)));
    const pctFmt = (v) => v == null ? '-' : `${Number(v) >= 0 ? '+' : ''}${Number(v).toFixed(1)}%`;

    const fetchAll = async () => {
        setLoading(true);
        try {
            const [s, sea] = await Promise.all([
                api.get('/business/forecast/summary'),
                api.get('/business/forecast/seasonal'),
            ]);
            setSummary(s.data);
            setSeasonal(sea.data);
        } catch (e) { console.error(e); }
        finally { setLoading(false); }
    };

    const fetchTrend = async (metric) => {
        try {
            const res = await api.get('/business/forecast/trend', { params: { metric } });
            setTrend(res.data);
        } catch (e) { console.error(e); }
    };

    // Risk + benchmark endpoints all take the standard filter body.
    const fetchRiskAndBench = async () => {
        try {
            const [c, mg, pb, rb] = await Promise.all([
                api.post('/business/forecast/churn-prediction', filters),
                api.post('/business/forecast/margin-risk', filters),
                api.post('/business/forecast/peer-benchmark', filters),
                api.post('/business/forecast/rm-benchmark', filters),
            ]);
            setChurn(c.data); setMargin(mg.data); setPeer(pb.data); setRm(rb.data);
        } catch (e) { console.error(e); }
    };

    useEffect(() => {
        if (boundsLoaded) { fetchAll(); fetchTrend(trendMetric); fetchRiskAndBench(); }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [boundsLoaded]);

    useEffect(() => {
        if (boundsLoaded) fetchTrend(trendMetric);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [trendMetric]);

    const metricByType = useMemo(() => {
        const m = {};
        (summary?.metrics || []).forEach(x => { m[x.metricType] = x; });
        return m;
    }, [summary]);

    // ── Headline forecast KPIs ──
    const kpis = useMemo(() => {
        if (!summary) return [];
        return METRIC_ORDER.map(mt => {
            const meta = METRICS[mt];
            const row = metricByType[mt];
            if (!row) return null;
            const risk = RISK_META[row.targetRiskStatus] || RISK_META.NO_TARGET;
            const sub = row.forecastAttainmentPct != null
                ? `${Number(row.forecastAttainmentPct).toFixed(0)}% of target · ${risk.label}`
                : `${pctFmt(row.forecastVsLastMonthPct)} vs last month`;
            return {
                title: `Forecast ${meta.label}`,
                value: fmtMeasure(meta.kind, row.forecastMonthEnd),
                icon: meta.icon,
                color: meta.color,
                subtitle: sub,
            };
        }).filter(Boolean);
    }, [summary, metricByType, fmt]);

    const trendMeta = METRICS[trendMetric];
    const trendChartData = useMemo(() => (trend?.series || []).map(p => ({
        date: p.date ? p.date.slice(8) : '', // day-of-month
        actual: p.actual,
        forecast: p.forecast,
        target: p.target != null ? Number(p.target) : undefined,
    })), [trend]);

    // ── Risk band chip metadata ──
    // Churn / margin flags are ATTENTION (brass) until truly negative.
    const CHURN_BAND = {
        LOW:      { label: 'Low',      color: 'var(--success)', bg: 'var(--success-bg)' },
        MEDIUM:   { label: 'Medium',   color: 'var(--warning)', bg: 'var(--warning-bg)' },
        HIGH:     { label: 'High',     color: 'var(--warning)', bg: 'var(--warning-bg)' },
        CRITICAL: { label: 'Critical', color: 'var(--danger)',  bg: 'var(--danger-bg)' },
    };
    const MARGIN_BAND = {
        LOSS_MAKING: { label: 'Loss-Making', color: 'var(--danger)',  bg: 'var(--danger-bg)' },
        LOW_MARGIN:  { label: 'Low Margin',  color: 'var(--warning)', bg: 'var(--warning-bg)' },
    };
    const bandChip = (meta) => (p) => {
        const m = meta[p.value] || { label: p.value, color: T.textSec, bg: T.subtle };
        return <Chip label={m.label} size="small" sx={{ bgcolor: m.bg, color: m.color, fontWeight: 700 }} />;
    };
    // Index vs median — colour always paired with a glyph so meaning
    // survives colourblindness and greyscale printing.
    const idxChip = (p) => {
        const v = Number(p.value);
        const up = v >= 100;
        return <Chip label={`${up ? '▲' : '▼'} ${v.toFixed(0)}`} size="small"
            sx={{ color: up ? 'var(--success)' : 'var(--danger)',
                  bgcolor: up ? 'var(--success-bg)' : 'var(--danger-bg)' }} />;
    };
    const monoCell = (p) => <Typography variant="body2" className="num" sx={{ fontFamily: 'var(--font-mono)', color: T.textSec }}>{p.value}</Typography>;
    const nameCell = (p) => <Typography variant="body2" sx={{ fontWeight: 600, color: T.text }}>{p.value}</Typography>;

    const churnCols = useMemo(() => [
        { field: 'mid', headerName: 'MID', width: 120, renderCell: monoCell },
        { field: 'name', headerName: 'MERCHANT', width: 200, renderCell: nameCell },
        { field: 'riskBand', headerName: 'RISK', width: 110, renderCell: bandChip(CHURN_BAND) },
        { field: 'churnRiskScore', headerName: 'SCORE', width: 90, type: 'number',
            renderCell: (p) => <Typography variant="body2" fontWeight={700} sx={{ color: T.text }}>{Number(p.value).toFixed(0)}</Typography> },
        { field: 'daysSinceLastTxn', headerName: 'IDLE DAYS', width: 100, type: 'number' },
        { field: 'volDeclinePct', headerName: 'VOL Δ%', width: 110, type: 'number',
            renderCell: (p) => <Typography variant="body2" className="num" sx={{ fontWeight: 600, fontFamily: 'var(--font-mono)', color: p.value < 0 ? 'var(--danger)' : 'var(--success)' }}>{Number(p.value) >= 0 ? '▲ +' : '▼ '}{Number(p.value).toFixed(1)}%</Typography> },
        { field: 'msf30', headerName: 'MSF (30d)', width: 130, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textSec }}>{fmt.currency(Number(p.value))}</Typography> },
        { field: 'rm', headerName: 'RM', width: 180, renderCell: (p) => <Typography variant="body2" sx={{ color: T.textMut }} noWrap>{p.value}</Typography> },
        { field: 'predictedDormancyDate', headerName: 'PROJ. DORMANT', width: 140,
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textMut }}>{p.value || '—'}</Typography> },
    ], [fmt]);

    const marginCols = useMemo(() => [
        { field: 'mid', headerName: 'MID', width: 120, renderCell: monoCell },
        { field: 'name', headerName: 'MERCHANT', width: 200, renderCell: nameCell },
        { field: 'riskBand', headerName: 'RISK', width: 130, renderCell: bandChip(MARGIN_BAND) },
        { field: 'forecastMarginPct', headerName: 'MARGIN %', width: 110, type: 'number',
            renderCell: (p) => <Typography variant="body2" fontWeight={700} sx={{ color: Number(p.value) < 0 ? 'var(--danger, #dc2626)' : Number(p.value) < 20 ? 'var(--warning, #d97706)' : T.text }}>{Number(p.value).toFixed(1)}%</Typography> },
        { field: 'netRevenue', headerName: 'NET REV', width: 130, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: Number(p.value) < 0 ? 'var(--danger, #dc2626)' : T.textSec }}>{fmt.currency(Number(p.value))}</Typography> },
        { field: 'msfRateBps', headerName: 'MSF bps', width: 100, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textSec }}>{Number(p.value).toFixed(0)}</Typography> },
        { field: 'volume', headerName: 'VOLUME', width: 130, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textSec }}>{fmt.currency(Number(p.value))}</Typography> },
        { field: 'reasons', headerName: 'REASONS', width: 260, sortable: false,
            renderCell: (p) => (
                <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                    {(p.value || []).map((r, i) => (
                        <Chip key={i} label={r} size="small" sx={{ bgcolor: T.subtle, color: T.textSec, fontWeight: 600, fontSize: '0.65rem', height: 20 }} />
                    ))}
                </Stack>
            ) },
    ], [fmt]);

    const peerCols = useMemo(() => [
        { field: 'mid', headerName: 'MID', width: 120, renderCell: monoCell },
        { field: 'name', headerName: 'MERCHANT', width: 200, renderCell: nameCell },
        { field: 'mcc', headerName: 'MCC', width: 90 },
        { field: 'peerCount', headerName: 'PEERS', width: 80, type: 'number' },
        { field: 'peerIndex', headerName: 'INDEX', width: 90, type: 'number', renderCell: idxChip },
        { field: 'peerPercentile', headerName: 'PCTILE', width: 90, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textSec }}>{Number(p.value).toFixed(0)}%</Typography> },
        { field: 'volume', headerName: 'VOLUME', width: 130, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textSec }}>{fmt.currency(Number(p.value))}</Typography> },
        { field: 'avgTicket', headerName: 'AVG TICKET', width: 120, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textSec }}>{fmt.currency(Number(p.value))}</Typography> },
        { field: 'volumeGapVsPeer', headerName: 'VOL GAP', width: 140, type: 'number',
            renderCell: (p) => <Typography variant="body2" className="num" sx={{ fontWeight: 600, fontFamily: 'var(--font-mono)', color: Number(p.value) >= 0 ? 'var(--success)' : 'var(--danger)' }}>{Number(p.value) >= 0 ? '▲ +' : '▼ '}{fmt.currency(Number(p.value))}</Typography> },
    ], [fmt]);

    const rmCols = useMemo(() => [
        { field: 'rank', headerName: '#', width: 60, type: 'number' },
        { field: 'rm', headerName: 'RELATIONSHIP MANAGER', width: 230, renderCell: nameCell },
        { field: 'benchmarkIndex', headerName: 'INDEX', width: 90, type: 'number', renderCell: idxChip },
        { field: 'peerPercentile', headerName: 'PCTILE', width: 90, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textSec }}>{Number(p.value).toFixed(0)}%</Typography> },
        { field: 'volume', headerName: 'VOLUME', width: 140, type: 'number',
            renderCell: (p) => <Typography variant="body2" fontWeight={600} sx={{ color: T.text }}>{fmt.currency(Number(p.value))}</Typography> },
        { field: 'revenue', headerName: 'REVENUE', width: 130, type: 'number',
            renderCell: (p) => <Typography variant="body2" sx={{ color: T.textSec }}>{fmt.currency(Number(p.value))}</Typography> },
        { field: 'activeMerchants', headerName: 'MERCHANTS', width: 110, type: 'number' },
        { field: 'volumeGapVsMedian', headerName: 'VOL GAP vs MED', width: 160, type: 'number',
            renderCell: (p) => <Typography variant="body2" className="num" sx={{ fontWeight: 600, fontFamily: 'var(--font-mono)', color: Number(p.value) >= 0 ? 'var(--success)' : 'var(--danger)' }}>{Number(p.value) >= 0 ? '▲ +' : '▼ '}{fmt.currency(Number(p.value))}</Typography> },
    ], [fmt]);

    // Portfolio position vs benchmark — median of the percentile_cont
    // percentiles the backend computes per merchant (vs MCC peers) and
    // per RM (vs all RMs). Feeds the BenchmarkRail stat blocks.
    const medianPct = (rows, key) => {
        const vals = (rows || []).map(r => Number(r[key])).filter(v => !Number.isNaN(v)).sort((a, b) => a - b);
        if (!vals.length) return null;
        const mid = Math.floor(vals.length / 2);
        return vals.length % 2 ? vals[mid] : (vals[mid - 1] + vals[mid]) / 2;
    };
    const peerMedianPercentile = useMemo(() => medianPct(peer?.rows, 'peerPercentile'), [peer]);
    const rmMedianPercentile   = useMemo(() => medianPct(rm?.rows, 'peerPercentile'), [rm]);

    const benchStat = (title, value, percentile, benchmarkLabel) => (
        <Box sx={{ p: '20px', border: `1px solid ${T.border}`, borderRadius: '4px', bgcolor: T.card, minWidth: 220, flex: '0 1 260px' }}>
            <Typography className="section-title" sx={{ mb: 0.5 }}>{title}</Typography>
            <Typography className="num" sx={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', fontSize: 20, fontWeight: 500, color: T.text, mb: 1 }}>
                {value}
            </Typography>
            <BenchmarkRail percentile={percentile} benchmarkLabel={benchmarkLabel} />
        </Box>
    );

    const panelSx = { p: '20px', borderRadius: '4px', border: `1px solid ${T.border}`, bgcolor: T.card, boxShadow: 'none' };
    const panelTitle = (t, hint) => (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 1.5 }}>
            <Typography className="section-title" sx={{ fontSize: 13, fontWeight: 600, letterSpacing: '0.02em', color: T.textMut }}>{t}</Typography>
            {hint && (
                <MuiTooltip title={hint} arrow>
                    <Info size={13} style={{ color: 'var(--text-muted, #94a3b8)', cursor: 'help' }} />
                </MuiTooltip>
            )}
        </Box>
    );

    const assumptionText = summary?.assumptions
        ? `${summary.assumptions.formula}  |  Seasonality: ${summary.assumptions.seasonalityFactor}  |  ${summary.assumptions.businessDays}`
        : '';

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Forecasting & Benchmarking"
                subtitle="Month-end projections from current run-rate and historical seasonality — every number explainable"
                icon={Gauge}
                onRunReport={() => { fetchAll(); fetchTrend(trendMetric); fetchRiskAndBench(); }}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                filters={filters}
                onFilterChange={(k, v) => setFilters(prev => (typeof k === 'object' ? { ...prev, ...k } : { ...prev, [k]: v }))}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchRiskAndBench} isOpen={showFilters} onClose={() => setShowFilters(false)} />

            {/* As-of + assumptions strip */}
            {summary && (
                <Paper sx={{ ...panelSx, mb: 2, display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 2, justifyContent: 'space-between' }}>
                    <Stack direction="row" spacing={1.5} alignItems="center">
                        <CalendarClock size={18} style={{ color: 'var(--text-secondary, #475569)' }} />
                        <Typography variant="body2" color={T.textSec}>
                            Forecasting <b>{summary.monthLabel}</b> as of <b>{summary.asOfDate}</b> —{' '}
                            {summary.elapsedBusinessDays} of {summary.totalBusinessDays} days elapsed,
                            {' '}{summary.remainingBusinessDays} remaining
                        </Typography>
                    </Stack>
                    {assumptionText && (
                        <MuiTooltip title={assumptionText} arrow>
                            <Chip icon={<Info size={14} />} label="Forecast assumptions" size="small"
                                sx={{ bgcolor: T.subtle, color: T.textSec, fontWeight: 600, cursor: 'help' }} />
                        </MuiTooltip>
                    )}
                </Paper>
            )}

            <KpiCards cards={kpis} />

            {/* ═══ Actual vs Forecast trend ═══ */}
            <Paper sx={{ ...panelSx, mb: 2 }}>
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
                    {panelTitle('Actual vs Forecast — Cumulative', 'Solid = actual to date. Dashed = run-rate projection for remaining business days, adjusted by the seasonality factor. Flat line = monthly target if set.')}
                    <ToggleButtonGroup size="small" exclusive value={trendMetric}
                        onChange={(e, v) => v && setTrendMetric(v)} aria-label="trend metric">
                        {METRIC_ORDER.map(mt => (
                            <ToggleButton key={mt} value={mt} sx={{ textTransform: 'none', fontWeight: 600 }}>{METRICS[mt].label}</ToggleButton>
                        ))}
                    </ToggleButtonGroup>
                </Box>
                <Box sx={{ height: 320 }}>
                    <ResponsiveContainer width="100%" height="100%">
                        <ComposedChart data={trendChartData} margin={{ top: 10, right: 16, left: 4, bottom: 0 }}>
                            {/* Actual = teal (what happened); forecast + target are
                                projections and take the projected/attention tokens. */}
                            <CartesianGrid strokeDasharray="3 6" stroke={T.grid} vertical={false} />
                            <XAxis dataKey="date" axisLine={false} tickLine={false}
                                tick={{ fontSize: 11, fill: T.axis, fontFamily: 'var(--font-mono)' }} />
                            <YAxis axisLine={false} tickLine={false}
                                tick={{ fontSize: 11, fill: T.axis, fontFamily: 'var(--font-mono)' }} width={54}
                                tickFormatter={(v) => trendMeta.kind === 'count' ? Number(v).toLocaleString() : fmt.currency(v)} />
                            <ReTooltip contentStyle={chartTooltipStyle}
                                formatter={(v, name) => [fmtMeasure(trendMeta.kind, v), name]} />
                            <Legend />
                            <Area type="monotone" dataKey="actual" name="Actual" stroke="var(--chart-2)"
                                strokeWidth={2} fill="var(--chart-2)" fillOpacity={0.08} connectNulls={false} />
                            <Line type="monotone" dataKey="forecast" name="Forecast" stroke="var(--projected)"
                                strokeWidth={2} strokeDasharray="5 4" dot={false} />
                            {trend?.target != null && (
                                <Line type="monotone" dataKey="target" name="Target" stroke="var(--attention)"
                                    strokeWidth={1.5} dot={false} />
                            )}
                        </ComposedChart>
                    </ResponsiveContainer>
                </Box>
            </Paper>

            {/* ═══ Target attainment forecast + Seasonal comparison ═══ */}
            <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1.15fr 1fr' } }}>
                {/* Attainment */}
                <Paper sx={panelSx}>
                    {panelTitle('Forecast Target Attainment', 'Forecast month-end ÷ target. Required daily run rate is what each remaining business day must deliver to still hit target.')}
                    <Stack spacing={1.25}>
                        {METRIC_ORDER.map(mt => {
                            const row = metricByType[mt];
                            if (!row) return null;
                            const meta = METRICS[mt];
                            const risk = RISK_META[row.targetRiskStatus] || RISK_META.NO_TARGET;
                            const hasTarget = row.forecastAttainmentPct != null;
                            const pctVal = hasTarget ? Number(row.forecastAttainmentPct) : 0;
                            return (
                                <Box key={mt} sx={{ p: 1.5, borderRadius: '4px', border: `1px solid ${T.border}`, bgcolor: T.subtle }}>
                                    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: hasTarget ? 1 : 0 }}>
                                        <Typography variant="body2" fontWeight={700} color={T.text}>{meta.label}</Typography>
                                        <Chip label={risk.label} size="small" sx={{ bgcolor: risk.bg, color: risk.color, fontWeight: 700 }} />
                                    </Box>
                                    {hasTarget ? (
                                        <>
                                            <Box sx={{ display: 'flex', height: 4, borderRadius: 0, overflow: 'hidden', bgcolor: 'var(--border)', mb: 0.75 }}>
                                                <Box sx={{ width: `${Math.min(100, pctVal)}%`, bgcolor: risk.color, transition: 'width .5s ease' }} />
                                            </Box>
                                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, justifyContent: 'space-between' }}>
                                                <Typography variant="caption" color={T.textMut}>
                                                    Forecast <b style={{ color: 'var(--text, #0f172a)' }}>{fmtMeasure(meta.kind, row.forecastMonthEnd)}</b> / target {fmtMeasure(meta.kind, row.target)}
                                                </Typography>
                                                <Typography variant="caption" fontWeight={700} sx={{ color: risk.color }}>
                                                    {pctVal.toFixed(1)}%
                                                </Typography>
                                            </Box>
                                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, mt: 0.5 }}>
                                                <Typography variant="caption" color={T.textMut}>
                                                    Need <b style={{ color: 'var(--text, #0f172a)' }}>{fmtMeasure(meta.kind, row.requiredDailyRunRate)}</b>/day
                                                </Typography>
                                                <Typography variant="caption" color={T.textMut}>
                                                    Now <b style={{ color: 'var(--text, #0f172a)' }}>{fmtMeasure(meta.kind, row.currentDailyRunRate)}</b>/day
                                                </Typography>
                                                {Number(row.expectedShortfall) > 0 && (
                                                    <Typography variant="caption" sx={{ color: 'var(--danger, #dc2626)' }}>
                                                        Shortfall {fmtMeasure(meta.kind, row.expectedShortfall)}
                                                    </Typography>
                                                )}
                                                {Number(row.expectedSurplus) > 0 && (
                                                    <Typography variant="caption" sx={{ color: 'var(--success, #059669)' }}>
                                                        Surplus {fmtMeasure(meta.kind, row.expectedSurplus)}
                                                    </Typography>
                                                )}
                                            </Box>
                                        </>
                                    ) : (
                                        <Typography variant="caption" color={T.textMut}>
                                            No target set for {summary?.monthLabel}. Forecast {fmtMeasure(meta.kind, row.forecastMonthEnd)} ({pctFmt(row.forecastVsLastMonthPct)} vs last month).
                                        </Typography>
                                    )}
                                </Box>
                            );
                        })}
                    </Stack>
                </Paper>

                {/* Seasonal comparison */}
                <Paper sx={panelSx}>
                    {panelTitle('Seasonal Comparison (YoY)', 'Current month-to-date vs the same month last year. Seasonality index of 100 = identical to last year; above 100 = running ahead.')}
                    {seasonal && (
                        <Typography variant="caption" color={T.textMut} sx={{ display: 'block', mb: 1.5 }}>
                            {seasonal.currentMonthLabel} MTD vs {seasonal.priorYearMonthLabel}
                        </Typography>
                    )}
                    <Stack spacing={1.25}>
                        {(seasonal?.metrics || []).map(row => {
                            const meta = METRICS[row.metricType];
                            if (!meta) return null;
                            const yoy = Number(row.yoyGrowthPct);
                            const up = yoy >= 0;
                            return (
                                <Box key={row.metricType} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1, p: 1.25, borderRadius: '4px', border: `1px solid ${T.border}` }}>
                                    <Box sx={{ minWidth: 0 }}>
                                        <Typography variant="body2" fontWeight={700} color={T.text}>{meta.label}</Typography>
                                        <Typography variant="caption" color={T.textMut}>
                                            {fmtMeasure(meta.kind, row.currentMTD)} vs {fmtMeasure(meta.kind, row.priorYearSameMonth)}
                                        </Typography>
                                    </Box>
                                    <Stack alignItems="flex-end" spacing={0.25}>
                                        <Chip label={`${up ? '▲' : '▼'} ${pctFmt(yoy)}`} size="small"
                                            sx={{ color: up ? 'var(--success)' : 'var(--danger)',
                                                  bgcolor: up ? 'var(--success-bg)' : 'var(--danger-bg)' }} />
                                        {row.seasonalityIndex != null && (
                                            <Typography variant="caption" color={T.textMut}>idx {Number(row.seasonalityIndex).toFixed(0)}</Typography>
                                        )}
                                    </Stack>
                                </Box>
                            );
                        })}
                    </Stack>
                </Paper>
            </Box>

            {/* ═══ Risk Predictions ═══ */}
            <Paper sx={{ ...panelSx, mt: 2 }}>
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
                    {panelTitle('Risk Predictions', 'Churn risk is a transparent weighted score (volume/txn decline + inactivity + merchant age). Margin risk flags low or negative projected margin over the trailing 30 days.')}
                    <ToggleButtonGroup size="small" exclusive value={riskTab} onChange={(e, v) => v && setRiskTab(v)}>
                        <ToggleButton value="churn" sx={{ textTransform: 'none', fontWeight: 600 }}>
                            <TrendingDown size={15} style={{ marginRight: 6 }} /> Churn Risk
                        </ToggleButton>
                        <ToggleButton value="margin" sx={{ textTransform: 'none', fontWeight: 600 }}>
                            <AlertTriangle size={15} style={{ marginRight: 6 }} /> Margin Risk
                        </ToggleButton>
                    </ToggleButtonGroup>
                </Box>

                {riskTab === 'churn' && (
                    <>
                        {churn?.summary && (
                            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 1.5 }}>
                                <Chip icon={<AlertTriangle size={14} />} label={`${churn.summary.atRiskMerchantCount} at risk (High + Critical)`} size="small" sx={{ bgcolor: 'var(--danger-bg, #fee2e2)', color: 'var(--danger, #dc2626)', fontWeight: 700 }} />
                            <Chip label={`Critical ${churn.summary.criticalRisk}`} size="small" sx={{ bgcolor: 'var(--danger-bg, #fee2e2)', color: 'var(--danger, #dc2626)', fontWeight: 600 }} />
                            <Chip label={`High ${churn.summary.highRisk}`} size="small" sx={{ bgcolor: 'var(--warning-bg, #ffedd5)', color: 'var(--danger, #ea580c)', fontWeight: 600 }} />
                                <Chip label={`Potential MSF loss ${fmt.currency(Number(churn.summary.potentialRevenueLoss))}`} size="small" sx={{ bgcolor: T.subtle, color: T.textSec, fontWeight: 600 }} />
                            </Stack>
                        )}
                        <Box sx={{ ...premiumTableWrapper, height: 460 }}>
                            <DataGrid
                                rows={(churn?.rows || []).map((r, i) => ({ id: r.mid || i, ...r }))}
                                columns={churnCols}
                                loading={loading}
                                disableRowSelectionOnClick rowHeight={40} columnHeaderHeight={40}
                                initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
                                pageSizeOptions={[25, 50, 100]}
                                sx={premiumDataGridStyles}
                            />
                        </Box>
                    </>
                )}

                {riskTab === 'margin' && (
                    <>
                        {margin?.summary && (
                            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 1.5 }}>
                                <Chip label={`Loss-making ${margin.summary.lossMakingCount}`} size="small" sx={{ bgcolor: 'var(--danger-bg, #fee2e2)', color: 'var(--danger, #dc2626)', fontWeight: 700 }} />
                                <Chip label={`Low margin ${margin.summary.lowMarginCount}`} size="small" sx={{ bgcolor: 'var(--warning-bg, #fef3c7)', color: 'var(--warning, #d97706)', fontWeight: 600 }} />
                                <Chip label={`Reprice candidates ${margin.summary.repriceCandidateCount}`} size="small" sx={{ bgcolor: T.subtle, color: T.textSec, fontWeight: 600 }} />
                                <Chip label={`Expected shortfall ${fmt.currency(Number(margin.summary.expectedMarginShortfall))}`} size="small" sx={{ bgcolor: T.subtle, color: T.textSec, fontWeight: 600 }} />
                            </Stack>
                        )}
                        <Box sx={{ ...premiumTableWrapper, height: 460 }}>
                            <DataGrid
                                rows={(margin?.rows || []).map((r, i) => ({ id: r.mid || i, ...r }))}
                                columns={marginCols}
                                loading={loading}
                                disableRowSelectionOnClick rowHeight={40} columnHeaderHeight={40}
                                initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
                                pageSizeOptions={[25, 50, 100]}
                                sx={premiumDataGridStyles}
                            />
                        </Box>
                    </>
                )}
            </Paper>

            {/* ═══ Benchmarks ═══ */}
            <Paper sx={{ ...panelSx, mt: 2 }}>
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
                    {panelTitle('Benchmarks', 'Index = subject metric ÷ peer-group median × 100 (100 = at median). RMs are compared to all RMs in the tenant; merchants to others in the same MCC. Trailing 90 days.')}
                    <ToggleButtonGroup size="small" exclusive value={benchTab} onChange={(e, v) => v && setBenchTab(v)}>
                        <ToggleButton value="rm" sx={{ textTransform: 'none', fontWeight: 600 }}>
                            <Award size={15} style={{ marginRight: 6 }} /> RM Benchmark
                        </ToggleButton>
                        <ToggleButton value="peer" sx={{ textTransform: 'none', fontWeight: 600 }}>
                            <Users size={15} style={{ marginRight: 6 }} /> Peer (MCC)
                        </ToggleButton>
                    </ToggleButtonGroup>
                </Box>

                {benchTab === 'rm' && rm?.rows?.length > 0 && (
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2, mb: 2 }}>
                        {benchStat('Median RM position', rmMedianPercentile != null ? `${Math.round(rmMedianPercentile)}%` : '—', rmMedianPercentile, 'RM benchmark')}
                        {benchStat('Top RM position', rm.rows[0]?.peerPercentile != null ? `${Math.round(rm.rows[0].peerPercentile)}%` : '—', rm.rows[0]?.peerPercentile, 'RM benchmark')}
                    </Box>
                )}
                {benchTab === 'peer' && peer?.rows?.length > 0 && (
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2, mb: 2 }}>
                        {benchStat('Portfolio median vs MCC peers', peerMedianPercentile != null ? `${Math.round(peerMedianPercentile)}%` : '—', peerMedianPercentile, 'peer group')}
                    </Box>
                )}

                {benchTab === 'rm' && (
                    <Box sx={{ ...premiumTableWrapper, height: 460 }}>
                        <DataGrid
                            rows={(rm?.rows || []).map((r, i) => ({ id: r.rm || i, ...r }))}
                            columns={rmCols}
                            loading={loading}
                            disableRowSelectionOnClick rowHeight={40} columnHeaderHeight={40}
                            initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
                            pageSizeOptions={[25, 50, 100]}
                            sx={premiumDataGridStyles}
                        />
                    </Box>
                )}

                {benchTab === 'peer' && (
                    <Box sx={{ ...premiumTableWrapper, height: 460 }}>
                        <DataGrid
                            rows={(peer?.rows || []).map((r, i) => ({ id: r.mid || i, ...r }))}
                            columns={peerCols}
                            loading={loading}
                            disableRowSelectionOnClick rowHeight={40} columnHeaderHeight={40}
                            initialState={{
                                pagination: { paginationModel: { pageSize: 25 } },
                                sorting: { sortModel: [{ field: 'peerIndex', sort: 'desc' }] },
                            }}
                            pageSizeOptions={[25, 50, 100]}
                            sx={premiumDataGridStyles}
                        />
                    </Box>
                )}
            </Paper>
        </Box>
    );
};

export default ForecastingBenchmarking;
