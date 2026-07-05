import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, ToggleButton, ToggleButtonGroup, Chip, Stack, Tooltip } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { Activity, TrendingDown, TrendingUp, Users, DollarSign, AlertTriangle, UserMinus, ShieldAlert, Brain } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as ReTooltip, ResponsiveContainer, Cell } from 'recharts';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import api from '../../api/axios';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer, chartTooltipStyle } from '../../theme/dataGridStyles';
import { useDataBounds } from '../../hooks/useDataBounds';
import DataBoundsBanner from '../../components/DataBoundsBanner';

// ─── Local design tokens ─────────────────────────────────────────
// Every colour routes through a CSS variable with a light-mode fallback so the
// report adapts cleanly under html.dark + ThemeContext. Status hues keep their
// meaning across themes; the dark stylesheet can override the --attr-* vars.
const T = {
    card:     'var(--bg-card, #ffffff)',
    subtle:   'var(--bg-subtle, #f8fafc)',
    hover:    'var(--bg-hover, #f8fafc)',
    border:   'var(--border, #e2e8f0)',
    borderLt: 'var(--border-light, #eef2f7)',
    text:     'var(--text, #0f172a)',
    textSec:  'var(--text-secondary, #475569)',
    textMut:  'var(--text-muted, #94a3b8)',
    textStr:  'var(--text, #1e293b)',
    // chart axis / grid
    axis:     'var(--text-muted, #94a3b8)',
    grid:     'var(--border-light, #eef2f7)',
};

// Metric → key-suffix the backend returns. Volume keeps the original (suffix-less)
// keys for backward compatibility; txns/revenue use the parallel suffixed keys.
const METRICS = {
    volume:  { label: 'Volume',       suffix: '',      kind: 'currency' },
    txns:    { label: 'Transactions', suffix: '_txns', kind: 'count' },
    revenue: { label: 'Revenue (MSF)',suffix: '_msf',  kind: 'currency' },
};

// Attrition status → colour + label. Mirrors classifyAttrition() in the backend.
// Foreground/background both routed through CSS vars so dark mode can retint.
const STATUS_META = {
    CHURNED:   { label: 'Churned',   color: 'var(--attr-churned, #7c3aed)',   bg: 'var(--attr-churned-bg, #f3e8ff)' },
    AT_RISK:   { label: 'At Risk',   color: 'var(--attr-atrisk, #dc2626)',    bg: 'var(--attr-atrisk-bg, #fee2e2)' },
    DECLINING: { label: 'Declining', color: 'var(--attr-declining, #ea580c)', bg: 'var(--attr-declining-bg, #ffedd5)' },
    STABLE:    { label: 'Stable',    color: 'var(--attr-stable, #475569)',    bg: 'var(--attr-stable-bg, #f1f5f9)' },
    GROWING:   { label: 'Growing',   color: 'var(--attr-growing, #059669)',   bg: 'var(--attr-growing-bg, #d1fae5)' },
};
const STATUS_ORDER = ['ALL', 'CHURNED', 'AT_RISK', 'DECLINING', 'STABLE', 'GROWING'];

// Predicted churn-risk band → colour. These are the ML forward-looking scores,
// distinct from the backward-looking attrition STATUS above.
const RISK_META = {
    HIGH:   { label: 'High',   color: 'var(--attr-atrisk, #dc2626)',    bg: 'var(--attr-atrisk-bg, #fee2e2)' },
    MEDIUM: { label: 'Medium', color: 'var(--attr-declining, #ea580c)', bg: 'var(--attr-declining-bg, #ffedd5)' },
    LOW:    { label: 'Low',    color: 'var(--attr-growing, #059669)',   bg: 'var(--attr-growing-bg, #d1fae5)' },
};

// The attrition backend anchors ALL comparison windows on [startDate, endDate]:
// MoM = the same-length window one month earlier, YoY = one year earlier, YTD =
// Jan-1-of-endYear -> endDate. The correct default "current" window is therefore
// the LATEST DATA MONTH, not the full data history.
const firstOfMonth = (isoDate) => (isoDate ? `${String(isoDate).slice(0, 7)}-01` : '');

const AttritionReport = () => {
    const { currencySymbol, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
    const [data, setData] = useState([]);
    const [churnByMid, setChurnByMid] = useState({});
    const [churnAvailable, setChurnAvailable] = useState(false);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [metric, setMetric] = useState('volume');
    const [statusFilter, setStatusFilter] = useState('ALL');

    const [filters, setFilters] = useState({
        startDate: '', endDate: '',
        openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [],
        rmList: [], teamLeaderList: [], sectorList: [],
        destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
        datePreset: 'MONTH'
    });

    const { startDate: boundsStart, endDate: boundsEnd, boundsLoaded, latest } = useDataBounds(tenantVersion);

    // fetchData(seeded) posts an explicit filter object; fetchData() (from the
    // header's Run Report / the drawer's Apply) posts the current filters state.
    // The override path exists because setFilters(...) doesn't commit before a
    // fetch fired in the same effect — posting the seeded object directly
    // guarantees the request body matches what the UI shows.
    const fetchData = async (override) => {
        const body = (override && override.startDate !== undefined) ? override : filters;
        setLoading(true);
        try {
            const res = await api.post('/business/attrition-report', body);
            setData(res.data.map((r, i) => ({ id: r.mid || i, ...r })));
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    // Seed the report window from the data bounds and fetch in ONE step.
    // Two fixes vs the previous version:
    //   1. WINDOW — default to the latest data month (first-of-latest-month ->
    //      latest), not earliest->latest. Seeding the full data history made the
    //      "current" window all-time volume while the column headers claimed
    //      MTD, and made the shifted MoM/YoY windows overlap the current one,
    //      diluting every % change toward 0.
    //   2. RACE — the old code set filters in one effect and called fetchData()
    //      in a second; both ran in the same commit, so the first request went
    //      out with the still-empty initial dates. The backend then defaulted
    //      to the CURRENT calendar month — empty whenever data lags the
    //      calendar — so every merchant rendered as -100% / churned.
    useEffect(() => {
        if (!boundsLoaded) return;
        const seeded = {
            ...filters,
            datePreset: 'CUSTOM',
            startDate: firstOfMonth(boundsEnd) || boundsStart,
            endDate: boundsEnd,
        };
        setFilters(seeded);
        fetchData(seeded);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [boundsLoaded]);

    // Churn-risk scores are precomputed by the batch and independent of the
    // attrition filters, so fetch once per tenant switch (not per report run).
    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const res = await api.get('/business/churn-risk');
                if (cancelled) return;
                const map = {};
                (res.data || []).forEach(c => { if (c.mid != null) map[c.mid] = c; });
                setChurnByMid(map);
                setChurnAvailable(Object.keys(map).length > 0);
            } catch (e) {
                // Churn is additive — never block the page if it's unavailable.
                if (!cancelled) { setChurnByMid({}); setChurnAvailable(false); }
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const selectedYear = filters.endDate ? new Date(filters.endDate).getFullYear() : new Date().getFullYear();
    const prevYear = selectedYear - 1;
    const { suffix, kind } = METRICS[metric];

    // Helpers that read the active-metric value off a row.
    const val = (row, base) => row[`${base}${suffix}`];
    const fmtCount = (v) => v == null ? '-' : Number(v).toLocaleString('en-US');
    const fmtMeasure = (v) => v == null ? '-' : (kind === 'count' ? fmtCount(v) : fmt.currency(v));
    const pctFormatter = (v) => v == null ? '-' : `${v >= 0 ? '+' : ''}${Number(v).toFixed(1)}%`;

    // Merge the predicted churn score onto each attrition row by mid.
    const rows = useMemo(
        () => data.map(r => {
            const c = churnByMid[r.mid];
            return c
                ? { ...r, churnProbability: c.churnProbability, churnBand: c.riskBand, churnReason: c.topReason, churnScoredBy: c.scoredBy }
                : r;
        }),
        [data, churnByMid]
    );

    // Status counts come from the whole portfolio (not the status-filtered view).
    const statusCounts = useMemo(() => {
        const c = { CHURNED: 0, AT_RISK: 0, DECLINING: 0, STABLE: 0, GROWING: 0 };
        data.forEach(d => { if (c[d.status] != null) c[d.status]++; });
        return c;
    }, [data]);

    // High predicted-churn count (forward-looking) — only meaningful if scores exist.
    const highChurnCount = useMemo(
        () => rows.filter(r => r.churnBand === 'HIGH').length,
        [rows]
    );

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalCur = data.reduce((s, d) => s + (Number(val(d, 'ytd_current')) || 0), 0);
        const totalPrev = data.reduce((s, d) => s + (Number(val(d, 'ytd_prev')) || 0), 0);
        const ytdChange = totalPrev > 0 ? ((totalCur - totalPrev) / totalPrev) * 100 : 0;
        const atRisk = statusCounts.CHURNED + statusCounts.AT_RISK;
        const atRiskValue = data.reduce((s, d) =>
            (d.status === 'CHURNED' || d.status === 'AT_RISK') ? s + (Number(val(d, 'ytd_current')) || 0) : s, 0);
        const cards = [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: 'var(--accent-indigo, #6366f1)' },
            { title: 'Churned', value: statusCounts.CHURNED.toString(), icon: UserMinus, color: 'var(--attr-churned, #7c3aed)',
              subtitle: `${data.length ? ((statusCounts.CHURNED / data.length) * 100).toFixed(0) : 0}% of portfolio` },
            { title: 'At Risk', value: atRisk.toString(), icon: AlertTriangle, color: 'var(--attr-atrisk, #dc2626)',
              subtitle: 'churned + steep decline' },
            { title: 'Declining (YTD)', value: statusCounts.DECLINING.toString(), icon: TrendingDown, color: 'var(--attr-declining, #ea580c)' },
            { title: `YTD ${METRICS[metric].label} Change`, value: `${ytdChange >= 0 ? '+' : ''}${ytdChange.toFixed(1)}%`,
              icon: DollarSign, color: ytdChange >= 0 ? 'var(--success, #10b981)' : 'var(--danger, #ef4444)', trend: ytdChange,
              trendLabel: `${prevYear} vs ${selectedYear}` },
        ];
        // Forward-looking ML tile only when scores are present.
        if (churnAvailable) {
            cards.push({ title: 'High Churn Risk', value: highChurnCount.toString(), icon: Brain,
                color: 'var(--attr-atrisk, #dc2626)', subtitle: 'predicted next 30–60 days' });
        } else {
            cards.push({ title: `${METRICS[metric].label} at Risk`, value: fmtMeasure(atRiskValue), icon: ShieldAlert,
                color: 'var(--attr-atrisk, #dc2626)', subtitle: `${atRisk} churned + at-risk` });
        }
        return cards;
    }, [data, metric, statusCounts, selectedYear, prevYear, churnAvailable, highChurnCount]);

    const filteredData = useMemo(
        () => statusFilter === 'ALL' ? rows : rows.filter(d => d.status === statusFilter),
        [rows, statusFilter]
    );

    // ── Churn analytics (all from the rows already returned) ──
    const STATUS_BARS = ['CHURNED', 'AT_RISK', 'DECLINING', 'STABLE', 'GROWING'];
    const analytics = useMemo(() => {
        const total = data.length || 1;
        const breakdown = STATUS_BARS.map(s => ({
            key: s, ...STATUS_META[s], count: statusCounts[s] || 0,
            pct: ((statusCounts[s] || 0) / total) * 100,
        }));
        const buckets = [
            { label: '≤-50%', test: p => p <= -50, color: 'var(--attr-dist-1, #b91c1c)' },
            { label: '-50..-20%', test: p => p > -50 && p <= -20, color: 'var(--attr-dist-2, #ef4444)' },
            { label: '-20..0%', test: p => p > -20 && p < 0, color: 'var(--attr-dist-3, #f59e0b)' },
            { label: '0..+20%', test: p => p >= 0 && p <= 20, color: 'var(--attr-dist-4, #34d399)' },
            { label: '>+20%', test: p => p > 20, color: 'var(--attr-dist-5, #059669)' },
        ];
        const dist = buckets.map(b => ({
            label: b.label, color: b.color,
            count: data.filter(d => { const p = Number(val(d, 'ytd_pct')); return !isNaN(p) && val(d, 'ytd_pct') != null && b.test(p); }).length,
        }));
        const topDeclining = data
            .filter(d => { const p = Number(val(d, 'ytd_pct')); return val(d, 'ytd_pct') != null && !isNaN(p) && p < 0; })
            .sort((a, b) => Number(val(a, 'ytd_pct')) - Number(val(b, 'ytd_pct')))
            .slice(0, 6);
        return { breakdown, dist, topDeclining };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [data, metric, statusCounts]);

    const measureCell = (params) => (
        <Typography variant="body2" sx={{ color: T.textSec }}>{fmtMeasure(params.value)}</Typography>
    );
    const measureCellBold = (params) => (
        <Typography variant="body2" fontWeight="600" sx={{ color: T.text }}>{fmtMeasure(params.value)}</Typography>
    );
    const pctCell = (params) => (
        <Typography variant="body2" sx={{ fontWeight: 'bold', color: params.value < 0 ? 'var(--danger, #ef4444)' : params.value > 0 ? 'var(--success, #10b981)' : T.textMut }}>
            {pctFormatter(params.value)}
        </Typography>
    );
    const statusCell = (params) => {
        const m = STATUS_META[params.value] || { label: params.value, color: T.textSec, bg: T.subtle };
        return <Chip label={m.label} size="small" sx={{ bgcolor: m.bg, color: m.color, fontWeight: 700 }} />;
    };

    // Predicted churn-risk cell: a coloured band chip + probability, with the top
    // driver and model/heuristic source in a tooltip. Sorts by probability.
    const churnCell = (params) => {
        const band = params.row.churnBand;
        if (!band) return <Typography variant="body2" sx={{ color: T.textMut }}>—</Typography>;
        const m = RISK_META[band] || { label: band, color: T.textSec, bg: T.subtle };
        const prob = params.row.churnProbability;
        const pctTxt = prob == null ? '' : `${(Number(prob) * 100).toFixed(0)}%`;
        const src = params.row.churnScoredBy === 'HEURISTIC' ? ' (heuristic)' : '';
        const tip = `${params.row.churnReason || 'Predicted churn risk'}${src}`;
        return (
            <Tooltip title={tip} arrow>
                <Chip label={`${m.label}${pctTxt ? ' · ' + pctTxt : ''}`} size="small"
                    sx={{ bgcolor: m.bg, color: m.color, fontWeight: 700, fontVariantNumeric: 'tabular-nums' }} />
            </Tooltip>
        );
    };

    const columns = useMemo(() => {
        const base = [
            { field: 'mid', headerName: 'MID', width: 130,
                renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', color: T.textSec }}>{p.value}</Typography> },
            { field: 'merchant_info', headerName: 'MERCHANT NAME', width: 230,
                valueGetter: (v, row) => row.name,
                renderCell: (p) => <Typography variant="body2" sx={{ fontWeight: 600, color: T.text }}>{p.row.name}</Typography> },
            { field: 'status', headerName: 'STATUS', width: 130,
                valueGetter: (v, row) => row.status, renderCell: statusCell },
        ];
        // Predicted churn-risk column, inserted right after Status — only when the
        // batch has produced scores for this tenant.
        if (churnAvailable) {
            base.push({
                field: 'churn_risk', headerName: 'CHURN RISK', width: 150, type: 'number',
                valueGetter: (v, row) => (row.churnProbability == null ? -1 : row.churnProbability),
                renderCell: churnCell,
            });
        }
        return [
            ...base,
            // MoM (equal-length window vs one month earlier)
            { field: 'mom_prev_col', headerName: 'Prev Month', width: 120, type: 'number',
                valueGetter: (v, row) => val(row, 'mom_prev'), renderCell: measureCell },
            { field: 'mom_curr_col', headerName: 'Current', width: 120, type: 'number',
                valueGetter: (v, row) => val(row, 'mom_current'), renderCell: measureCellBold },
            { field: 'mom_pct_col', headerName: '% Change', width: 110, type: 'number',
                valueGetter: (v, row) => val(row, 'mom_pct'), renderCell: pctCell },
            // MTD YoY
            { field: 'mtd_prev_col', headerName: `${prevYear}`, width: 120, type: 'number',
                valueGetter: (v, row) => val(row, 'mtd_prev'), renderCell: measureCell },
            { field: 'mtd_curr_col', headerName: `${selectedYear}`, width: 120, type: 'number',
                valueGetter: (v, row) => val(row, 'mtd_current'), renderCell: measureCellBold },
            { field: 'mtd_pct_col', headerName: '% Change', width: 110, type: 'number',
                valueGetter: (v, row) => val(row, 'mtd_pct'), renderCell: pctCell },
            // YTD YoY
            { field: 'ytd_prev_col', headerName: `${prevYear}`, width: 120, type: 'number',
                valueGetter: (v, row) => val(row, 'ytd_prev'), renderCell: measureCell },
            { field: 'ytd_curr_col', headerName: `${selectedYear}`, width: 120, type: 'number',
                valueGetter: (v, row) => val(row, 'ytd_current'), renderCell: measureCellBold },
            { field: 'ytd_pct_col', headerName: '% Change', width: 110, type: 'number',
                valueGetter: (v, row) => val(row, 'ytd_pct'), renderCell: pctCell },
        ];
    }, [metric, selectedYear, prevYear, churnAvailable]);

    const columnGroupingModel = [
        { groupId: 'mom_group', headerName: 'Month-on-Month', headerClassName: 'mom-header-group',
            children: [{ field: 'mom_prev_col' }, { field: 'mom_curr_col' }, { field: 'mom_pct_col' }] },
        { groupId: 'mtd_group', headerName: `MTD (${prevYear} vs ${selectedYear})`, headerClassName: 'mtd-header-group',
            children: [{ field: 'mtd_prev_col' }, { field: 'mtd_curr_col' }, { field: 'mtd_pct_col' }] },
        { groupId: 'ytd_group', headerName: `YTD (${prevYear} vs ${selectedYear})`, headerClassName: 'ytd-header-group',
            children: [{ field: 'ytd_prev_col' }, { field: 'ytd_curr_col' }, { field: 'ytd_pct_col' }] },
    ];

    const panelSx = { p: 2.5, borderRadius: '14px', border: `1px solid ${T.border}`, bgcolor: T.card, height: '100%' };
    const panelTitle = (t) => (
        <Typography variant="caption" fontWeight={700} color={T.textMut} sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', mb: 1.5, display: 'block' }}>{t}</Typography>
    );

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Attrition Report (MoM & YoY)" subtitle="Month-on-month and year-over-year comparison with churn classification"
                icon={Activity}
                onExport={() => exportToCSV(filteredData, 'attrition_report')}
                onRunReport={() => fetchData()} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={() => fetchData()} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <DataBoundsBanner
                latest={latest}
                boundsLoaded={boundsLoaded}
                currentEnd={filters.endDate}
                onJumpToLatest={() => {
                    // Jump to the latest DATA MONTH (the report's natural window),
                    // and post the seeded object directly — no setTimeout race.
                    const seeded = {
                        ...filters,
                        datePreset: 'CUSTOM',
                        startDate: firstOfMonth(boundsEnd) || boundsStart,
                        endDate: boundsEnd,
                    };
                    setFilters(seeded);
                    fetchData(seeded);
                }}
            />
            <KpiCards cards={kpis} />

            {/* ═══ Churn analytics band ═══ */}
            {data.length > 0 && (
                <Box sx={{ display: 'grid', gap: 2, mb: 2, gridTemplateColumns: { xs: '1fr', md: '1.1fr 1fr', lg: '1.2fr 1fr 1fr' } }}>
                    {/* Portfolio health */}
                    <Paper sx={panelSx}>
                        {panelTitle('Portfolio Health')}
                        <Box sx={{ display: 'flex', height: 14, borderRadius: 999, overflow: 'hidden', mb: 2, bgcolor: T.subtle }}>
                            {analytics.breakdown.map(s => s.count > 0 && (
                                <Box key={s.key} title={`${s.label}: ${s.count}`} sx={{ width: `${s.pct}%`, bgcolor: s.color, transition: 'width .5s ease' }} />
                            ))}
                        </Box>
                        <Stack spacing={0.75}>
                            {analytics.breakdown.map(s => (
                                <Box key={s.key} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                                        <Box sx={{ width: 10, height: 10, borderRadius: '3px', bgcolor: s.color }} />
                                        <Typography variant="body2" color={T.textSec}>{s.label}</Typography>
                                    </Box>
                                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                                        <Typography variant="body2" fontWeight={700} color={T.textStr} sx={{ fontVariantNumeric: 'tabular-nums' }}>{s.count.toLocaleString()}</Typography>
                                        <Typography variant="caption" color={T.textMut} sx={{ width: 42, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{s.pct.toFixed(1)}%</Typography>
                                    </Box>
                                </Box>
                            ))}
                        </Stack>
                    </Paper>

                    {/* YTD % change distribution */}
                    <Paper sx={panelSx}>
                        {panelTitle(`YTD ${METRICS[metric].label} % Change`)}
                        <Box sx={{ height: 170 }}>
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={analytics.dist} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                    <CartesianGrid strokeDasharray="3 6" stroke={T.grid} vertical={false} />
                                    <XAxis dataKey="label" axisLine={false} tickLine={false} tick={{ fontSize: 10, fill: T.axis }} interval={0} />
                                    <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: T.axis }} allowDecimals={false} width={32} />
                                    <ReTooltip cursor={{ fill: 'var(--bg-hover, #f8fafc)' }} contentStyle={chartTooltipStyle} formatter={(v) => [v, 'Merchants']} />
                                    <Bar dataKey="count" radius={[5, 5, 0, 0]}>
                                        {analytics.dist.map((d, i) => <Cell key={i} fill={d.color} />)}
                                    </Bar>
                                </BarChart>
                            </ResponsiveContainer>
                        </Box>
                    </Paper>

                    {/* Steepest decline — call list */}
                    <Paper sx={panelSx}>
                        {panelTitle('Steepest YTD Decline')}
                        <Stack spacing={1}>
                            {analytics.topDeclining.length === 0 && (
                                <Typography variant="body2" color={T.textMut}>No declining merchants in range.</Typography>
                            )}
                            {analytics.topDeclining.map((d, i) => {
                                const meta = STATUS_META[d.status] || { color: T.textSec, bg: T.subtle };
                                const pct = Number(val(d, 'ytd_pct'));
                                return (
                                    <Box key={d.mid || i} onClick={() => setStatusFilter(d.status)}
                                        sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1, cursor: 'pointer', '&:hover .mn': { color: meta.color } }}>
                                        <Box sx={{ minWidth: 0 }}>
                                            <Typography className="mn" variant="body2" fontWeight={600} color={T.textSec} noWrap sx={{ maxWidth: 150, transition: 'color .15s' }}>{d.name || d.mid}</Typography>
                                            <Typography variant="caption" color={T.textMut}>{fmtMeasure(val(d, 'ytd_current'))} now</Typography>
                                        </Box>
                                        <Chip label={pctFormatter(pct)} size="small" sx={{ bgcolor: meta.bg, color: meta.color, fontWeight: 700, fontVariantNumeric: 'tabular-nums' }} />
                                    </Box>
                                );
                            })}
                        </Stack>
                    </Paper>
                </Box>
            )}

            {/* Metric toggle + status quick-filters */}
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }} justifyContent="space-between" sx={{ mb: 2 }}>
                <ToggleButtonGroup size="small" exclusive value={metric}
                    onChange={(e, v) => v && setMetric(v)} aria-label="metric">
                    {Object.entries(METRICS).map(([k, m]) => (
                        <ToggleButton key={k} value={k} sx={{ textTransform: 'none', fontWeight: 600 }}>{m.label}</ToggleButton>
                    ))}
                </ToggleButtonGroup>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                    {STATUS_ORDER.map(s => {
                        const meta = s === 'ALL' ? { label: 'All', color: T.textStr, bg: T.border } : STATUS_META[s];
                        const count = s === 'ALL' ? data.length : (statusCounts[s] || 0);
                        const active = statusFilter === s;
                        return (
                            <Chip key={s} label={`${meta.label} (${count})`} size="small" clickable
                                onClick={() => setStatusFilter(s)}
                                sx={{
                                    fontWeight: 700,
                                    color: active ? 'var(--on-accent, #fff)' : meta.color,
                                    bgcolor: active ? meta.color : meta.bg,
                                    border: active ? `1px solid ${meta.color}` : '1px solid transparent',
                                }} />
                        );
                    })}
                </Stack>
            </Stack>

            <Paper sx={{
                ...premiumTableWrapper,
                '& .mom-header-group': { bgcolor: 'var(--attr-hdr-mom-bg, #fef3c7)', color: 'var(--attr-hdr-mom-tx, #92400e)', fontWeight: 'bold' },
                '& .mtd-header-group': { bgcolor: 'var(--attr-hdr-mtd-bg, #eff6ff)', color: 'var(--attr-hdr-mtd-tx, #1e40af)', fontWeight: 'bold' },
                '& .ytd-header-group': { bgcolor: 'var(--attr-hdr-ytd-bg, #f8fafc)', color: 'var(--attr-hdr-ytd-tx, #334155)', fontWeight: 'bold' }
            }}>
                <DataGrid
                    rows={filteredData} columns={columns} columnGroupingModel={columnGroupingModel}
                    loading={loading} disableRowSelectionOnClick rowHeight={60}
                    initialState={{
                        pagination: { paginationModel: { pageSize: 25 } },
                        sorting: { sortModel: [{ field: 'ytd_pct_col', sort: 'asc' }] },
                    }}
                    pageSizeOptions={[25, 50, 100]}
                    experimentalFeatures={{ columnGrouping: true }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default AttritionReport;
