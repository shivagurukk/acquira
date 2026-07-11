import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, ToggleButton, ToggleButtonGroup, Chip, Stack } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { HeartHandshake, TrendingUp, Users, DollarSign, UserMinus, UserPlus, RefreshCw, AlertTriangle } from 'lucide-react';
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
// Mirror AttritionReport: every colour routes through a CSS variable with a
// light-mode fallback so the report re-themes cleanly under html.dark.
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
    axis:     'var(--text-muted, #94a3b8)',
    grid:     'var(--border-light, #eef2f7)',
    warnBg:   'var(--warning-bg, #fffbeb)',
    warnBorder: 'var(--warning-border, #fde68a)',
    warnText: 'var(--warning-text, #92400e)',
};

// Metric → key-suffix the backend returns. Volume/txns/msf each carry their own
// current/prior/pct keys (cur_volume, prior_volume, volume_pct, …).
const METRICS = {
    volume:  { label: 'Volume',       base: 'volume', kind: 'currency' },
    txns:    { label: 'Transactions', base: 'txns',   kind: 'count' },
    revenue: { label: 'Revenue (MSF)',base: 'msf',    kind: 'currency' },
};

// Retention status → colour + label. Mirrors classifyRetention() in the backend.
const STATUS_META = {
    CHURNED:     { label: 'Churned',     color: 'var(--ret-churned, #dc2626)',   bg: 'var(--ret-churned-bg, #fee2e2)' },
    REACTIVATED: { label: 'Reactivated', color: 'var(--ret-react, #0891b2)',     bg: 'var(--ret-react-bg, #cffafe)' },
    RETAINED:    { label: 'Retained',    color: 'var(--ret-retained, #059669)',  bg: 'var(--ret-retained-bg, #d1fae5)' },
    NEW:         { label: 'New',         color: 'var(--ret-new, #7c3aed)',       bg: 'var(--ret-new-bg, #f3e8ff)' },
};
const STATUS_ORDER = ['ALL', 'CHURNED', 'REACTIVATED', 'RETAINED', 'NEW'];

const prettyDate = (ymd) => {
    if (!ymd) return '';
    try { return new Date(ymd + 'T00:00:00').toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' }); }
    catch { return ymd; }
};

const RetentionReport = () => {
    const { currencySymbol, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
    const [data, setData] = useState([]);
    const [meta, setMeta] = useState(null);
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

    useEffect(() => {
        if (!boundsLoaded) return;
        // Default to "last month vs prior month" rather than the full data
        // range — retention only means something over a short, recent window.
        // A YTD-style default would compare against a prior window that's
        // typically wider than the tenant's actual history.
        //
        // toYmd MUST format in local time. Using toISOString() shifts local
        // midnight back to UTC (in GST/UTC+3 that rolls May 1 → "2026-04-30"),
        // which silently mis-dates the whole window and corrupts churn
        // classification. Format from local Y/M/D components instead.
        const toYmd = (d) => {
            const y = d.getFullYear();
            const m = String(d.getMonth() + 1).padStart(2, '0');
            const day = String(d.getDate()).padStart(2, '0');
            return `${y}-${m}-${day}`;
        };
        const end = boundsEnd ? new Date(boundsEnd + 'T00:00:00') : new Date();
        const lastDayPrevMonth = new Date(end.getFullYear(), end.getMonth(), 0);
        const firstDayPrevMonth = new Date(end.getFullYear(), end.getMonth() - 1, 1);
        const suggestedStart = toYmd(firstDayPrevMonth) < boundsStart ? boundsStart : toYmd(firstDayPrevMonth);
        const suggestedEnd = toYmd(lastDayPrevMonth) > boundsEnd ? boundsEnd : toYmd(lastDayPrevMonth);

        // Set state AND fetch with the same computed window in one place, so
        // the first fetch actually uses the last-month default instead of the
        // empty-date filters (which the server falls back to current MTD).
        const next = { ...filters, datePreset: 'CUSTOM', startDate: suggestedStart, endDate: suggestedEnd };
        setFilters(next);
        fetchData(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [boundsLoaded, boundsStart, boundsEnd]);

    // Accept an optional override so callers that have just computed a new
    // filter set (default-window effect, jump-to-latest) can fetch with the
    // *new* values immediately instead of racing React's state commit — the
    // same setFilters+fetchData same-commit race we've fixed elsewhere.
    //
    // IMPORTANT: only treat the argument as an override when it's a plain
    // filter object. Some callers (e.g. a header "Run report" button) may
    // invoke this as an onClick handler, which would otherwise pass a DOM
    // event as `overrideFilters` and POST the event as the request body —
    // silently sending garbage instead of the current filters.
    const fetchData = async (overrideFilters) => {
        const isPlainFilterObject =
            overrideFilters &&
            typeof overrideFilters === 'object' &&
            !(typeof overrideFilters.preventDefault === 'function') && // not a DOM/synthetic event
            !('nativeEvent' in overrideFilters);
        const active = isPlainFilterObject ? overrideFilters : filters;
        setLoading(true);
        try {
            const res = await api.post('/business/retention-report', active);
            const rows = res.data?.rows || [];
            // Stable DataGrid row key: prefer merchant_id when the backend
            // supplies it (duplicate MIDs from re-onboarded merchants otherwise
            // collide); fall back to mid, then row index.
            setData(rows.map((r, i) => ({ id: r.merchant_id ?? r.mid ?? i, ...r })));
            setMeta(res.data?.meta || null);
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const { base, kind } = METRICS[metric];

    // Helpers that read the active-metric value off a row.
    const cur   = (row) => row[`cur_${base === 'volume' ? 'volume' : base === 'txns' ? 'txns' : 'msf'}`];
    const prior = (row) => row[`prior_${base === 'volume' ? 'volume' : base === 'txns' ? 'txns' : 'msf'}`];
    const pct   = (row) => row[`${base === 'volume' ? 'volume' : base === 'txns' ? 'txns' : 'msf'}_pct`];
    // A % change is only meaningful when the merchant had prior-window activity
    // on the active metric. The backend emits +100% whenever prior==0 (its
    // divide-by-zero sentinel), which would otherwise dump every NEW/REACTIVATED
    // merchant into the ">+20%" growth bucket and make the "no comparable
    // merchants" empty-state unreachable. Treat prior==0 as non-comparable.
    const priorVal   = (row) => Number(prior(row)) || 0;
    const comparable = (row) => priorVal(row) !== 0;
    const fmtCount = (v) => v == null ? '-' : Number(v).toLocaleString('en-US');
    const fmtMeasure = (v) => v == null ? '-' : (kind === 'count' ? fmtCount(v) : fmt.currency(v));
    const pctFormatter = (v) => v == null ? '-' : `${v >= 0 ? '+' : ''}${Number(v).toFixed(1)}%`;

    // Prior window had zero portfolio-wide activity — every stat on this page
    // (churn rate, revenue-weighted churn, reactivation) is meaningless noise
    // in that state, not a real signal. Distinguish it from "genuinely 0% churn".
    const priorWindowEmpty = meta && meta.priorWindowHasData === false;

    // Status counts across the whole portfolio (not the status-filtered view).
    const statusCounts = useMemo(() => {
        const c = { CHURNED: 0, REACTIVATED: 0, RETAINED: 0, NEW: 0 };
        data.forEach(d => { if (c[d.status] != null) c[d.status]++; });
        return c;
    }, [data]);

    // ── Headline retention KPIs ──
    // churnRate       = churned / (retained + churned)  [merchants active last period]
    // revWtdChurn     = MSF lost to churn / total prior-window MSF of that base
    // reactivationRate= reactivated / (reactivated + churned + retained-that-were-dormant proxy)
    //                   → reactivated / merchants that were dormant entering the window
    const kpis = useMemo(() => {
        if (!data.length || priorWindowEmpty) return [];
        const churned = statusCounts.CHURNED;
        const retained = statusCounts.RETAINED;
        const reactivated = statusCounts.REACTIVATED;
        // Base of merchants that were active in the prior window = retained + churned.
        const priorActiveBase = retained + churned;
        const churnRate = priorActiveBase > 0 ? (churned / priorActiveBase) * 100 : 0;

        // Revenue-weighted churn: MSF lost from churned merchants ÷ prior-window MSF
        // across the prior-active base (retained + churned).
        const lostMsf = data.reduce((s, d) => d.status === 'CHURNED' ? s + (Number(d.prior_msf) || 0) : s, 0);
        const priorMsfBase = data.reduce((s, d) =>
            (d.status === 'CHURNED' || d.status === 'RETAINED') ? s + (Number(d.prior_msf) || 0) : s, 0);
        const revWtdChurn = priorMsfBase > 0 ? (lostMsf / priorMsfBase) * 100 : 0;

        // Win-back vs loss: reactivated ÷ (reactivated + churned). This is NOT
        // a true reactivation rate — a true rate would divide by the merchants
        // that entered the window dormant, which isn't derivable from this
        // payload (both-windows-silent merchants are dropped upstream). It
        // reads as "for every merchant we lost this window, how many did we
        // win back", which is a defensible headline until the backend supplies
        // a real dormant-entering count.
        const winLossBase = reactivated + churned;
        const winBackRate = winLossBase > 0 ? (reactivated / winLossBase) * 100 : 0;

        // Prefer the true reactivation rate when the backend supplies the
        // dormant-entering base (merchants dormant ≤12mo entering the window):
        //   reactivated ÷ dormantEnteringBase.
        // Fall back to the win-back proxy (reactivated ÷ (reactivated+churned))
        // when the base is absent (older backend) or zero.
        const dormantBase = meta?.dormantEnteringBase;
        const hasTrueRate = dormantBase != null && Number(dormantBase) > 0;
        const reactCard = hasTrueRate
            ? { title: 'Reactivation Rate',
                value: `${((reactivated / Number(dormantBase)) * 100).toFixed(1)}%`,
                icon: RefreshCw, color: 'var(--ret-react, #0891b2)',
                subtitle: `${reactivated} of ${dormantBase} dormant reactivated` }
            : { title: 'Win-Back vs Loss',
                value: `${winBackRate.toFixed(1)}%`,
                icon: RefreshCw, color: 'var(--ret-react, #0891b2)',
                subtitle: `${reactivated} won back · ${churned} lost` };

        // Merchants actually transacting in the CURRENT window (churned are, by
        // definition, silent now and must not be counted as active).
        const activeNow = retained + reactivated + statusCounts.NEW;

        return [
            { title: 'Active Merchants', value: activeNow.toString(),
              icon: Users, color: 'var(--accent-indigo, #6366f1)',
              subtitle: `${churned} churned this window` },
            { title: 'Churn Rate', value: `${churnRate.toFixed(1)}%`, icon: UserMinus,
              color: 'var(--ret-churned, #dc2626)', subtitle: `${churned} of ${priorActiveBase} prior-active` },
            { title: 'Revenue-Weighted Churn', value: `${revWtdChurn.toFixed(1)}%`, icon: DollarSign,
              color: 'var(--ret-churned, #dc2626)', subtitle: `${fmt.currency(lostMsf)} MSF lost` },
            reactCard,
            { title: 'Retained', value: retained.toString(), icon: TrendingUp,
              color: 'var(--ret-retained, #059669)',
              subtitle: `${data.length ? ((retained / data.length) * 100).toFixed(0) : 0}% of portfolio` },
            { title: 'New This Period', value: statusCounts.NEW.toString(), icon: UserPlus,
              color: 'var(--ret-new, #7c3aed)' },
        ];
    }, [data, metric, statusCounts, fmt, priorWindowEmpty, meta]);

    const filteredData = useMemo(
        () => statusFilter === 'ALL' ? data : data.filter(d => d.status === statusFilter),
        [data, statusFilter]
    );

    // ── Retention analytics band ──
    const STATUS_BARS = ['CHURNED', 'REACTIVATED', 'RETAINED', 'NEW'];
    const analytics = useMemo(() => {
        const total = data.length || 1;
        const breakdown = STATUS_BARS.map(s => ({
            key: s, ...STATUS_META[s], count: statusCounts[s] || 0,
            pct: ((statusCounts[s] || 0) / total) * 100,
        }));
        // % change distribution on the active metric.
        const buckets = [
            { label: '≤-50%', test: p => p <= -50, color: 'var(--ret-dist-1, #b91c1c)' },
            { label: '-50..-20%', test: p => p > -50 && p <= -20, color: 'var(--ret-dist-2, #ef4444)' },
            { label: '-20..0%', test: p => p > -20 && p < 0, color: 'var(--ret-dist-3, #f59e0b)' },
            { label: '0..+20%', test: p => p >= 0 && p <= 20, color: 'var(--ret-dist-4, #34d399)' },
            { label: '>+20%', test: p => p > 20, color: 'var(--ret-dist-5, #059669)' },
        ];
        const dist = buckets.map(b => ({
            label: b.label, color: b.color,
            count: data.filter(d => {
                if (!comparable(d)) return false;      // prior==0 → not a real % change
                const p = Number(pct(d));
                return pct(d) != null && !isNaN(p) && b.test(p);
            }).length,
        }));
        const distTotal = dist.reduce((s, b) => s + b.count, 0);
        // Biggest MSF at risk — churned merchants ranked by prior MSF (the call list).
        const topChurnedByRev = data
            .filter(d => d.status === 'CHURNED')
            .sort((a, b) => (Number(b.prior_msf) || 0) - (Number(a.prior_msf) || 0))
            .slice(0, 6);
        return { breakdown, dist, distTotal, topChurnedByRev };
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

    // Columns use flex (not fixed width) so the grid stretches to fill the
    // full container width at any viewport instead of leaving a dead band on
    // the right. minWidth keeps each column readable when the window is narrow;
    // flex distributes the remaining space proportionally.
    const columns = useMemo(() => [
        { field: 'mid', headerName: 'MID', flex: 0.9, minWidth: 120,
            renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', color: T.textSec }}>{p.value}</Typography> },
        { field: 'merchant_info', headerName: 'MERCHANT NAME', flex: 1.8, minWidth: 200,
            valueGetter: (v, row) => row.name,
            renderCell: (p) => <Typography variant="body2" sx={{ fontWeight: 600, color: T.text }}>{p.row.name}</Typography> },
        { field: 'status', headerName: 'STATUS', flex: 1, minWidth: 130,
            valueGetter: (v, row) => row.status, renderCell: statusCell },
        // Prior window
        { field: 'prior_col', headerName: 'Prior Period', flex: 1.1, minWidth: 130, type: 'number',
            valueGetter: (v, row) => prior(row), renderCell: measureCell },
        // Current window
        { field: 'cur_col', headerName: 'Current Period', flex: 1.1, minWidth: 130, type: 'number',
            valueGetter: (v, row) => cur(row), renderCell: measureCellBold },
        // % change — null for non-comparable rows (no prior activity) so they
        // render as '-' instead of a misleading +100%.
        { field: 'pct_col', headerName: '% Change', flex: 0.9, minWidth: 110, type: 'number',
            valueGetter: (v, row) => comparable(row) ? pct(row) : null, renderCell: pctCell },
        // Revenue lost (only meaningful for churned) — always MSF
        { field: 'lost_msf', headerName: 'MSF at Risk', flex: 1.1, minWidth: 130, type: 'number',
            valueGetter: (v, row) => Number(row.lost_msf) || 0,
            renderCell: (p) => (
                <Typography variant="body2" sx={{ fontWeight: p.value > 0 ? 700 : 400, color: p.value > 0 ? 'var(--ret-churned, #dc2626)' : T.textMut }}>
                    {p.value > 0 ? fmt.currency(p.value) : '-'}
                </Typography>
            ) },
    // fmt is a dependency because the render cells format currency with the
    // tenant's symbol — omitting it left the grid formatting with the old
    // currency after a tenant switch until a metric toggle forced a rebuild.
    ], [metric, fmt]);

    const columnGroupingModel = [
        { groupId: 'window_group', headerName: `${METRICS[metric].label} — Prior vs Current`, headerClassName: 'ret-header-group',
            children: [{ field: 'prior_col' }, { field: 'cur_col' }, { field: 'pct_col' }] },
    ];

    const panelSx = { p: 2.5, borderRadius: '14px', border: `1px solid ${T.border}`, bgcolor: T.card, height: '100%' };
    const panelTitle = (t) => (
        <Typography variant="caption" fontWeight={700} color={T.textMut} sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', mb: 1.5, display: 'block' }}>{t}</Typography>
    );

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Retention Report" subtitle="Churn, revenue-weighted churn and reactivation across the current vs prior equal-length window"
                icon={HeartHandshake}
                onExport={() => exportToCSV(filteredData, 'retention_report')}
                onRunReport={() => fetchData()} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchData} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <DataBoundsBanner
                latest={latest}
                boundsLoaded={boundsLoaded}
                currentEnd={filters.endDate}
                onJumpToLatest={() => {
                    const next = { ...filters, datePreset: 'CUSTOM', startDate: boundsStart, endDate: boundsEnd };
                    setFilters(next);
                    fetchData(next);
                }}
            />

            {/* ═══ Prior-window data guard ═══
                Retention needs an equal-length window before the current one.
                When that window has no data at all, every KPI on this page is
                a divide-by-near-zero artifact (e.g. "100% churn" from one
                stray row) rather than a real signal — say so plainly instead
                of showing numbers that look authoritative but aren't. */}
            {!loading && meta && priorWindowEmpty && (
                <Box role="status" sx={{
                    display: 'flex', alignItems: 'flex-start', gap: 1.25, flexWrap: 'wrap',
                    px: 2, py: 1.5, mb: 2, borderRadius: 'var(--radius-md, 10px)',
                    border: `1px solid ${T.warnBorder}`, bgcolor: T.warnBg, color: T.warnText,
                }}>
                    <AlertTriangle size={17} style={{ flexShrink: 0, marginTop: 1 }} />
                    <Box sx={{ minWidth: 0 }}>
                        <Typography sx={{ fontSize: '0.85rem', fontWeight: 700, color: 'inherit' }}>
                            No data in the comparison window — retention figures aren't meaningful here
                        </Typography>
                        <Typography sx={{ fontSize: '0.8rem', color: 'inherit', opacity: 0.9, mt: 0.25 }}>
                            The selected range ({prettyDate(meta.currentStart)} – {prettyDate(meta.currentEnd)}) needs an equal-length prior
                            window ({prettyDate(meta.priorStart)} – {prettyDate(meta.priorEnd)}) to compare against, and that window has no
                            recorded activity. Try a shorter, more recent range — This month or Last month usually works best for retention.
                        </Typography>
                    </Box>
                </Box>
            )}

            {!priorWindowEmpty && <KpiCards cards={kpis} />}

            {/* ═══ Retention analytics band ═══ */}
            {data.length > 0 && !priorWindowEmpty && (
                <Box sx={{ display: 'grid', gap: 2, mb: 2, gridTemplateColumns: { xs: '1fr', md: '1.1fr 1fr', lg: '1.2fr 1fr 1fr' } }}>
                    {/* Portfolio composition */}
                    <Paper sx={panelSx}>
                        {panelTitle('Retention Composition')}
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

                    {/* % change distribution */}
                    <Paper sx={panelSx}>
                        {panelTitle(`${METRICS[metric].label} % Change`)}
                        {analytics.distTotal === 0 ? (
                            <Box sx={{ height: 170, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                                <Typography variant="body2" color={T.textMut} sx={{ textAlign: 'center', maxWidth: 220 }}>
                                    No merchants have both a prior and current value to compare on this metric.
                                </Typography>
                            </Box>
                        ) : (
                            <Box sx={{ height: 170 }}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <BarChart data={analytics.dist} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                        <CartesianGrid strokeDasharray="3 6" stroke={T.grid} vertical={false} />
                                        <XAxis dataKey="label" axisLine={false} tickLine={false} tick={{ fontSize: 10, fill: T.axis }} interval={0} />
                                        <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: T.axis }} allowDecimals={false} width={32} />
                                        <ReTooltip cursor={{ fill: 'var(--bg-hover, #f8fafc)' }} contentStyle={chartTooltipStyle} formatter={(v) => [v, 'Merchants']} />
                                        <Bar dataKey="count" radius={[5, 5, 0, 0]} maxBarSize={56}>
                                            {analytics.dist.map((d, i) => <Cell key={i} fill={d.color} />)}
                                        </Bar>
                                    </BarChart>
                                </ResponsiveContainer>
                            </Box>
                        )}
                    </Paper>

                    {/* Biggest MSF at risk — churned call list */}
                    <Paper sx={panelSx}>
                        {panelTitle('Biggest MSF at Risk (Churned)')}
                        <Stack spacing={1}>
                            {analytics.topChurnedByRev.length === 0 && (
                                <Typography variant="body2" color={T.textMut}>No churned merchants in range.</Typography>
                            )}
                            {analytics.topChurnedByRev.map((d, i) => {
                                const meta = STATUS_META.CHURNED;
                                return (
                                    <Box key={d.mid || i} onClick={() => setStatusFilter('CHURNED')}
                                        sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1, cursor: 'pointer', '&:hover .mn': { color: meta.color } }}>
                                        <Box sx={{ minWidth: 0 }}>
                                            <Typography className="mn" variant="body2" fontWeight={600} color={T.textSec} noWrap sx={{ maxWidth: 150, transition: 'color .15s' }}>{d.name || d.mid}</Typography>
                                            <Typography variant="caption" color={T.textMut}>{fmt.currency(Number(d.prior_msf) || 0)} prior MSF</Typography>
                                        </Box>
                                        <Chip label="Churned" size="small" sx={{ bgcolor: meta.bg, color: meta.color, fontWeight: 700 }} />
                                    </Box>
                                );
                            })}
                        </Stack>
                    </Paper>
                </Box>
            )}

            {/* Metric toggle + status quick-filters */}
            {data.length > 0 && (
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
            )}

            {data.length === 0 && !loading ? (
                <Paper sx={{ ...panelSx, py: 6, textAlign: 'center' }}>
                    <HeartHandshake size={28} style={{ color: T.textMut, marginBottom: 8 }} />
                    <Typography variant="body1" fontWeight={700} color={T.textStr}>No merchant activity in this range</Typography>
                    <Typography variant="body2" color={T.textMut} sx={{ mt: 0.5, maxWidth: 420, mx: 'auto' }}>
                        Widen the date range or pick a period where merchants transacted, then run the report again.
                    </Typography>
                </Paper>
            ) : (
                <Paper sx={{
                    ...premiumTableWrapper,
                    '& .ret-header-group': { bgcolor: 'var(--ret-hdr-bg, #ecfeff)', color: 'var(--ret-hdr-tx, #155e75)', fontWeight: 'bold' },
                }}>
                    <DataGrid
                        rows={filteredData} columns={columns} columnGroupingModel={columnGroupingModel}
                        loading={loading} disableRowSelectionOnClick rowHeight={60}
                        initialState={{
                            pagination: { paginationModel: { pageSize: 25 } },
                            sorting: { sortModel: [{ field: 'lost_msf', sort: 'desc' }] },
                        }}
                        pageSizeOptions={[25, 50, 100]}
                        experimentalFeatures={{ columnGrouping: true }}
                        sx={premiumDataGridStyles}
                    />
                </Paper>
            )}
        </Box>
    );
};

export default RetentionReport;
