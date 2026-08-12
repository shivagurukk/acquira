import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, ToggleButton, ToggleButtonGroup, Chip, Stack, Tooltip, Drawer, IconButton, Divider } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { Activity, TrendingUp, Users, DollarSign, AlertTriangle, ShieldAlert, Brain, X, CalendarClock, ArrowRight } from 'lucide-react';
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

// Attrition status → colour + label. Mirrors classifyAttrition() in the backend,
// which classifies on the current month against the trailing 3-month average:
// churned <30% or zero, declining = 3 months constantly dropping, performing >=90%.
// Foreground/background both routed through CSS vars so dark mode can retint.
// Severity maps onto the app's status semantics: churned = danger (red),
// declining = warning (amber), stable = neutral ink, performing = success —
// the previous purple/orange pairing made the most severe state read as a
// brand accent instead of a problem.
const STATUS_META = {
    CHURNED:    { label: 'Churned',    color: 'var(--attr-churned, #dc2626)',   bg: 'var(--attr-churned-bg, #fef2f2)' },
    DECLINING:  { label: 'Declining',  color: 'var(--attr-declining, #d97706)', bg: 'var(--attr-declining-bg, #fffbeb)' },
    STABLE:     { label: 'Stable',     color: 'var(--attr-stable, #64748b)',    bg: 'var(--attr-stable-bg, #f1f5f9)' },
    PERFORMING: { label: 'Performing', color: 'var(--attr-growing, #059669)',   bg: 'var(--attr-growing-bg, #ecfdf5)' },
    // Trading this month with no trailing 3-month baseline — nothing to attrite
    // FROM. Brand-blue: informational, neither healthy nor at-risk.
    NEW:        { label: 'New',        color: 'var(--attr-new, #2563eb)',       bg: 'var(--attr-new-bg, #eff6ff)' },
};

// Predicted churn-risk band → colour. These are the ML forward-looking scores,
// distinct from the backward-looking attrition STATUS above.
const RISK_META = {
    HIGH:   { label: 'High',   color: 'var(--attr-atrisk, #dc2626)',    bg: 'var(--attr-atrisk-bg, #fef2f2)' },
    MEDIUM: { label: 'Medium', color: 'var(--attr-declining, #d97706)', bg: 'var(--attr-declining-bg, #fffbeb)' },
    LOW:    { label: 'Low',    color: 'var(--attr-growing, #059669)',   bg: 'var(--attr-growing-bg, #ecfdf5)' },
};

// The attrition backend anchors ALL comparison windows on [startDate, endDate]:
// MoM = the same-length window one month earlier, YoY = one year earlier, YTD =
// Jan-1-of-endYear -> endDate. The correct default "current" window is therefore
// the LATEST DATA MONTH, not the full data history.
const firstOfMonth = (isoDate) => (isoDate ? `${String(isoDate).slice(0, 7)}-01` : '');

const AttritionReport = () => {
    const { currencySymbol, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const [data, setData] = useState([]);
    const [meta, setMeta] = useState(null);      // comparison-window coverage flags
    const [error, setError] = useState(null);
    const [churnByMid, setChurnByMid] = useState({});
    const [churnAvailable, setChurnAvailable] = useState(false);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [metric, setMetric] = useState('volume');
    const [statusFilter, setStatusFilter] = useState('ALL');
    // Interactive additions: YTD-% distribution-bucket filter (click a bar to
    // narrow the grid) and the row-detail side panel (click a row to inspect).
    const [bucketFilter, setBucketFilter] = useState(null);   // index into BUCKETS or null
    const [detailRow, setDetailRow] = useState(null);          // full row object or null

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
        setError(null);
        try {
            // …-with-meta also returns the comparison-window flags, so an empty
            // prior-year window can be called out instead of silently rendering
            // as +100% growth for every merchant.
            const res = await api.post('/business/attrition-report-with-meta', body);
            const rows = Array.isArray(res.data) ? res.data : (res.data?.rows || []);
            setData(rows.map((r, i) => ({ id: r.mid ?? `row-${i}`, ...r })));
            setMeta(Array.isArray(res.data) ? null : (res.data?.meta || null));
        } catch (e) {
            // Previously console.error only — a 500/403 left an empty grid that
            // looked exactly like a portfolio with no merchants.
            console.error(e);
            setData([]);
            setMeta(null);
            setError(e?.response?.data?.error || e?.response?.statusText || e?.message || 'Could not load the attrition report.');
        }
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

    // Read the year off the ISO STRING. `new Date('2026-01-01')` parses as UTC
    // midnight, so in any timezone behind UTC .getFullYear() returned 2025 — every
    // year column header, and the YoY group labels, were off by one whenever the
    // window ended on 1 January.
    const selectedYear = filters.endDate ? Number(String(filters.endDate).slice(0, 4)) : new Date().getFullYear();
    const prevYear = selectedYear - 1;
    const { suffix, kind } = METRICS[metric];

    // ── Empty-window detection ──
    // DataBoundsBanner covers "view ends BEFORE latest data". This covers the
    // opposite artifact: the selected window STARTS AFTER the latest data date
    // (e.g. "This month" clicked when data lags the calendar). The current
    // window is then empty, so MoM renders -100% for every merchant — noise,
    // not attrition. We surface a jump-to-latest-month banner instead of
    // letting the grid mislead.
    const latestYmd = latest ? String(latest).slice(0, 10) : '';
    const windowBeyondData = boundsLoaded && latestYmd && filters.startDate && filters.startDate > latestYmd;

    // Comparison windows the backend reported as containing no data at all.
    // Only meaningful once rows exist — with an empty portfolio every window is
    // trivially empty and the banner would be noise.
    const emptyWindows = useMemo(() => {
        if (!meta || data.length === 0) return [];
        const out = [];
        if (meta.momWindowHasData === false) out.push(`previous month (${meta.momPrevStart} → ${meta.momPrevEnd})`);
        if (meta.yoyWindowHasData === false) out.push(`prior year (${meta.yoyPrevStart} → ${meta.yoyPrevEnd})`);
        if (meta.ytdPrevWindowHasData === false) out.push(`prior YTD (${meta.ytdPrevStart} → ${meta.ytdPrevEnd})`);
        // Status classifier's trailing-3-month baseline — when empty, every status
        // reads New/Churned as an artifact of missing history, not real attrition.
        if (meta.baselineWindowHasData === false) out.push(`status baseline (${meta.baselineStart} → ${meta.baselineEnd})`);
        return out;
    }, [meta, data.length]);

    // ── MoM applicability ──
    // The backend's MoM window is the selected range shifted back ONE month. For
    // any range longer than a month the shifted window overlaps the current one
    // (select Jan→Aug and "previous month" is Dec→Jul — seven shared months), so
    // the % is arithmetically meaningless. Hide the group rather than render it.
    // Range length is read off meta (what the loaded rows were actually queried
    // with), not the live filters state, so the columns can't flicker mid-edit.
    const momApplicable = useMemo(() => {
        if (!meta?.currentStart || !meta?.currentEnd) return true; // legacy shape — keep old behavior
        const days = (Date.parse(meta.currentEnd) - Date.parse(meta.currentStart)) / 86400000 + 1;
        return days <= 31;
    }, [meta]);

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
        const c = { CHURNED: 0, DECLINING: 0, STABLE: 0, PERFORMING: 0, NEW: 0 };
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
        // Match the backend's calculateGrowth: prev=0 & cur>0 → +100% ("new"),
        // both zero → 0%. Previously this returned 0% while the row showed
        // +100% for the same numbers — two answers for one dataset.
        const ytdChange = totalPrev > 0 ? ((totalCur - totalPrev) / totalPrev) * 100 : (totalCur > 0 ? 100 : 0);
        const ytdIsNew = totalPrev === 0 && totalCur > 0;
        // "At risk" = the two adverse statuses under the rolling-month rules.
        const atRisk = statusCounts.CHURNED + statusCounts.DECLINING;
        const atRiskValue = data.reduce((s, d) =>
            (d.status === 'CHURNED' || d.status === 'DECLINING') ? s + (Number(val(d, 'ytd_current')) || 0) : s, 0);
        // Five tiles, scannable left-to-right as a sentence: how big is the
        // book, what's at risk, what's the money exposure, how is the year
        // going — plus the forward-looking score when the batch provides one.
        // Per-status counts live in the Portfolio Health band below, so the
        // cards don't repeat them.
        const cards = [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: 'var(--brand, #2563eb)' },
            { title: 'At Risk', value: atRisk.toString(), icon: AlertTriangle, color: 'var(--attr-atrisk, #dc2626)',
              subtitle: `${statusCounts.CHURNED} churned · ${statusCounts.DECLINING} declining` },
            { title: `${METRICS[metric].label} at Risk`, value: fmtMeasure(atRiskValue), icon: ShieldAlert,
              color: 'var(--attr-atrisk, #dc2626)', subtitle: 'held by churned + declining' },
            { title: `YTD ${METRICS[metric].label} Change`, value: ytdIsNew ? 'New (+100%)' : `${ytdChange >= 0 ? '+' : ''}${ytdChange.toFixed(1)}%`,
              icon: DollarSign, color: ytdChange >= 0 ? 'var(--success, #059669)' : 'var(--danger, #dc2626)', trend: ytdChange,
              trendLabel: ytdIsNew ? `no ${prevYear} baseline` : `${prevYear} vs ${selectedYear}` },
        ];
        // Forward-looking ML tile only when scores are present.
        if (churnAvailable) {
            cards.push({ title: 'High Churn Risk', value: highChurnCount.toString(), icon: Brain,
                color: 'var(--attr-atrisk, #dc2626)', subtitle: 'predicted next 30–60 days' });
        } else {
            cards.push({ title: 'Performing', value: statusCounts.PERFORMING.toString(), icon: TrendingUp,
                color: 'var(--attr-growing, #059669)', subtitle: '≥90% of 3-month average' });
        }
        return cards;
    }, [data, metric, statusCounts, selectedYear, prevYear, churnAvailable, highChurnCount]);

    // YTD-% distribution buckets — hoisted so both the chart AND the click-to-
    // filter path share one definition (no drift between what a bar shows and
    // what clicking it selects).
    const BUCKETS = useMemo(() => ([
        { label: '≤-50%', test: p => p <= -50, color: 'var(--attr-dist-1, #b91c1c)' },
        { label: '-50..-20%', test: p => p > -50 && p <= -20, color: 'var(--attr-dist-2, #ef4444)' },
        { label: '-20..0%', test: p => p > -20 && p < 0, color: 'var(--attr-dist-3, #f59e0b)' },
        { label: '0..+20%', test: p => p >= 0 && p <= 20, color: 'var(--attr-dist-4, #34d399)' },
        { label: '>+20%', test: p => p > 20, color: 'var(--attr-dist-5, #059669)' },
    ]), []);

    const filteredData = useMemo(() => {
        let out = statusFilter === 'ALL' ? rows : rows.filter(d => d.status === statusFilter);
        if (bucketFilter != null && BUCKETS[bucketFilter]) {
            const b = BUCKETS[bucketFilter];
            out = out.filter(d => {
                const p = Number(val(d, 'ytd_pct'));
                return val(d, 'ytd_pct') != null && !isNaN(p) && b.test(p);
            });
        }
        return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [rows, statusFilter, bucketFilter, metric]);

    // ── Churn analytics (all from the rows already returned) ──
    const STATUS_BARS = ['CHURNED', 'DECLINING', 'STABLE', 'PERFORMING', 'NEW'];
    const analytics = useMemo(() => {
        const total = data.length || 1;
        const breakdown = STATUS_BARS.map(s => ({
            key: s, ...STATUS_META[s], count: statusCounts[s] || 0,
            pct: ((statusCounts[s] || 0) / total) * 100,
        }));
        const dist = BUCKETS.map(b => ({
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
            // MoM (equal-length window vs one month earlier) — omitted when the
            // selected range exceeds a month, because the shifted window would
            // overlap the current one. See momApplicable above.
            ...(momApplicable ? [
                { field: 'mom_prev_col', headerName: 'Prev Month', width: 120, type: 'number',
                    valueGetter: (v, row) => val(row, 'mom_prev'), renderCell: measureCell },
                { field: 'mom_curr_col', headerName: 'Current', width: 120, type: 'number',
                    valueGetter: (v, row) => val(row, 'mom_current'), renderCell: measureCellBold },
                { field: 'mom_pct_col', headerName: '% Change', width: 110, type: 'number',
                    valueGetter: (v, row) => val(row, 'mom_pct'), renderCell: pctCell },
            ] : []),
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [metric, selectedYear, prevYear, churnAvailable, momApplicable]);

    const columnGroupingModel = [
        ...(momApplicable ? [
            { groupId: 'mom_group', headerName: 'Month-on-Month', headerClassName: 'mom-header-group',
                children: [{ field: 'mom_prev_col' }, { field: 'mom_curr_col' }, { field: 'mom_pct_col' }] },
        ] : []),
        { groupId: 'mtd_group', headerName: `Period YoY (${prevYear} vs ${selectedYear})`, headerClassName: 'mtd-header-group',
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
                onExport={() => {
                    // Filename reflects the active narrowing so exports are self-describing.
                    const parts = ['attrition_report'];
                    if (statusFilter !== 'ALL') parts.push(statusFilter.toLowerCase());
                    if (bucketFilter != null && BUCKETS[bucketFilter]) parts.push(`ytd_${BUCKETS[bucketFilter].label.replace(/[^\w-]+/g, '')}`);
                    exportToCSV(filteredData, parts.join('_'));
                }}
                onRunReport={() => fetchData()} onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={(next) => fetchData(next)}
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

            {/* ── Empty-window banner ──
                The inverse of DataBoundsBanner: the selected window starts AFTER
                the latest data date, so the "current" side of every comparison is
                empty and MoM renders -100% across the board. Call it out and offer
                the latest data month in one click. */}
            {windowBeyondData && (
                <Box role="status" sx={{
                    display: 'flex', alignItems: 'center', gap: 1.25, flexWrap: 'wrap',
                    px: 1.75, py: 1, mb: 1.5,
                    borderRadius: 'var(--radius-md, 10px)',
                    border: '1px solid var(--danger-border, #fecaca)',
                    bgcolor: 'var(--danger-bg, #fef2f2)',
                    color: 'var(--danger-text, #991b1b)',
                }}>
                    <CalendarClock size={15} style={{ flexShrink: 0 }} />
                    <Typography sx={{ fontSize: '0.82rem', fontWeight: 600, color: 'inherit' }}>
                        Selected window starts after the latest data ({latestYmd}). Comparisons below show -100% artifacts, not real attrition.
                    </Typography>
                    <Box
                        onClick={() => {
                            const seeded = {
                                ...filters,
                                datePreset: 'CUSTOM',
                                startDate: firstOfMonth(boundsEnd) || boundsStart,
                                endDate: boundsEnd,
                            };
                            setFilters(seeded);
                            fetchData(seeded);
                        }}
                        sx={{
                            ml: 'auto', display: 'inline-flex', alignItems: 'center', gap: 0.5,
                            px: 1.25, py: 0.5, borderRadius: 'var(--radius-sm, 6px)', cursor: 'pointer',
                            fontSize: '0.78rem', fontWeight: 700, whiteSpace: 'nowrap',
                            color: 'var(--brand, #2563eb)',
                            bgcolor: 'var(--bg-card, #ffffff)',
                            border: '1px solid var(--border, #e2e8f0)',
                            '&:hover': { borderColor: 'var(--brand, #2563eb)' },
                        }}
                    >
                        Use latest data month <ArrowRight size={13} />
                    </Box>
                </Box>
            )}

            {/* Request failure — was console-only, so a broken endpoint rendered
                as "no merchants" and read as a real (catastrophic) result. */}
            {error && (
                <Box role="alert" sx={{
                    display: 'flex', alignItems: 'center', gap: 1.25, flexWrap: 'wrap',
                    px: 1.75, py: 1, mb: 1.5, borderRadius: 'var(--radius-md, 10px)',
                    border: '1px solid var(--danger-border, #fecaca)',
                    bgcolor: 'var(--danger-bg, #fef2f2)', color: 'var(--danger-text, #991b1b)',
                }}>
                    <AlertTriangle size={15} style={{ flexShrink: 0 }} />
                    <Typography sx={{ fontSize: '0.82rem', fontWeight: 600, color: 'inherit' }}>
                        Could not load the attrition report. {error}
                    </Typography>
                    <Box onClick={() => fetchData()} sx={{
                        ml: 'auto', px: 1.25, py: 0.5, borderRadius: 'var(--radius-sm, 6px)', cursor: 'pointer',
                        fontSize: '0.78rem', fontWeight: 700, color: 'var(--brand, #2563eb)',
                        bgcolor: 'var(--bg-card, #ffffff)', border: '1px solid var(--border, #e2e8f0)',
                    }}>Retry</Box>
                </Box>
            )}

            {/* ── Empty comparison-window banner ──
                A window with NO data at all makes every merchant in it read as
                +100% / "New". That is an artifact of missing history, not growth,
                so name the specific window rather than letting the % columns imply
                a result they cannot support. */}
            {emptyWindows.length > 0 && !loading && (
                <Box role="status" sx={{
                    display: 'flex', alignItems: 'center', gap: 1.25, flexWrap: 'wrap',
                    px: 1.75, py: 1, mb: 1.5, borderRadius: 'var(--radius-md, 10px)',
                    border: '1px solid var(--warning-border, #fed7aa)',
                    bgcolor: 'var(--warning-bg, #fffbeb)', color: 'var(--warning-text, #92400e)',
                }}>
                    <AlertTriangle size={15} style={{ flexShrink: 0 }} />
                    <Typography sx={{ fontSize: '0.82rem', fontWeight: 600, color: 'inherit' }}>
                        No data in the {emptyWindows.join(' or ')} comparison {emptyWindows.length > 1 ? 'windows' : 'window'} —
                        results measured against {emptyWindows.length > 1 ? 'them' : 'it'} (+100% growth, "New" statuses) are artifacts of missing history, not real change.
                    </Typography>
                </Box>
            )}

            <KpiCards cards={kpis} />

            {/* ═══ Portfolio health band — the page's centrepiece ═══
                One full-width composition strip: the whole book, divided by
                attrition status, worst-first. Click a segment or legend chip to
                filter the grid below; the same control is the filter indicator. */}
            {data.length > 0 && (
                <Paper sx={{ ...panelSx, mb: 2, height: 'auto' }}>
                    <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
                        <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5, flexWrap: 'wrap' }}>
                            {panelTitle('Portfolio Health')}
                            {/* Status is classified on calendar months anchored at the latest
                                loaded data date — independent of the money columns' selected
                                range. Say so, or a custom range reads as the status basis. */}
                            {meta?.classifierAnchor && (
                                <Typography variant="caption" color={T.textMut}>
                                    status as of {meta.classifierAnchor} · month-to-date vs prior 3 months
                                </Typography>
                            )}
                        </Box>
                        {statusFilter !== 'ALL' && (
                            <Typography variant="caption" onClick={() => setStatusFilter('ALL')}
                                sx={{ cursor: 'pointer', color: 'var(--brand, #2563eb)', fontWeight: 700 }}>
                                Showing {STATUS_META[statusFilter]?.label} — clear ✕
                            </Typography>
                        )}
                    </Box>
                    <Box sx={{ display: 'flex', gap: '2px', height: 18, borderRadius: '6px', overflow: 'hidden', mb: 1.5, bgcolor: T.subtle }}>
                        {analytics.breakdown.map(s => s.count > 0 && (
                            <Box key={s.key} title={`${s.label}: ${s.count} — click to filter`}
                                onClick={() => setStatusFilter(prev => prev === s.key ? 'ALL' : s.key)}
                                sx={{ width: `${s.pct}%`, bgcolor: s.color, transition: 'width .5s ease, opacity .15s', cursor: 'pointer',
                                    opacity: statusFilter === 'ALL' || statusFilter === s.key ? 1 : 0.3,
                                    '&:hover': { opacity: 1 } }} />
                        ))}
                    </Box>
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', columnGap: 3, rowGap: 0.75 }}>
                        {analytics.breakdown.map(s => {
                            const active = statusFilter === s.key;
                            return (
                                <Box key={s.key}
                                    onClick={() => setStatusFilter(active ? 'ALL' : s.key)}
                                    sx={{ display: 'inline-flex', alignItems: 'center', gap: 1, cursor: 'pointer',
                                        px: 1, py: 0.5, borderRadius: '8px',
                                        border: `1px solid ${active ? s.color : 'transparent'}`,
                                        bgcolor: active ? s.bg : 'transparent',
                                        '&:hover': { bgcolor: s.bg } }}>
                                    <Box sx={{ width: 10, height: 10, borderRadius: '3px', bgcolor: s.color }} />
                                    <Typography variant="body2" color={active ? s.color : T.textSec} fontWeight={active ? 700 : 500}>{s.label}</Typography>
                                    <Typography variant="body2" fontWeight={700} color={T.textStr} sx={{ fontVariantNumeric: 'tabular-nums' }}>{s.count.toLocaleString()}</Typography>
                                    <Typography variant="caption" color={T.textMut} sx={{ fontVariantNumeric: 'tabular-nums' }}>{s.pct.toFixed(1)}%</Typography>
                                </Box>
                            );
                        })}
                    </Box>
                </Paper>
            )}

            {/* ═══ Churn analytics panels ═══ */}
            {data.length > 0 && (
                <Box sx={{ display: 'grid', gap: 2, mb: 2, gridTemplateColumns: { xs: '1fr', md: '1.4fr 1fr' } }}>
                    {/* YTD % change distribution — click a bar to filter the grid to that bucket */}
                    <Paper sx={panelSx}>
                        <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
                            {panelTitle(`YTD ${METRICS[metric].label} % Change`)}
                            {bucketFilter != null && (
                                <Typography variant="caption" onClick={() => setBucketFilter(null)}
                                    sx={{ cursor: 'pointer', color: 'var(--brand, #2563eb)', fontWeight: 700 }}>
                                    Clear ✕
                                </Typography>
                            )}
                        </Box>
                        <Box sx={{ height: 170 }}>
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={analytics.dist} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                                    <CartesianGrid strokeDasharray="3 6" stroke={T.grid} vertical={false} />
                                    <XAxis dataKey="label" axisLine={false} tickLine={false} tick={{ fontSize: 10, fill: T.axis }} interval={0} />
                                    <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: T.axis }} allowDecimals={false} width={32} />
                                    <ReTooltip cursor={{ fill: 'var(--bg-hover, #f8fafc)' }} contentStyle={chartTooltipStyle}
                                        formatter={(v) => [v, 'Merchants — click bar to filter']} />
                                    <Bar dataKey="count" radius={[5, 5, 0, 0]} cursor="pointer"
                                        onClick={(entry, index) => setBucketFilter(prev => prev === index ? null : index)}>
                                        {analytics.dist.map((d, i) => (
                                            <Cell key={i} fill={d.color}
                                                fillOpacity={bucketFilter == null || bucketFilter === i ? 1 : 0.3} />
                                        ))}
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
                {/* Active narrowings only — the Portfolio Health band above is the
                    status filter control, so no duplicate chip row here. */}
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
                    {bucketFilter != null && BUCKETS[bucketFilter] && (
                        <Chip size="small" onDelete={() => setBucketFilter(null)}
                            label={`YTD ${BUCKETS[bucketFilter].label}`}
                            sx={{ fontWeight: 700, color: 'var(--on-accent, #fff)', bgcolor: BUCKETS[bucketFilter].color,
                                '& .MuiChip-deleteIcon': { color: 'var(--on-accent, #fff)', opacity: 0.8 } }} />
                    )}
                    {statusFilter !== 'ALL' && STATUS_META[statusFilter] && (
                        <Chip size="small" onDelete={() => setStatusFilter('ALL')}
                            label={STATUS_META[statusFilter].label}
                            sx={{ fontWeight: 700, color: STATUS_META[statusFilter].color, bgcolor: STATUS_META[statusFilter].bg,
                                '& .MuiChip-deleteIcon': { color: STATUS_META[statusFilter].color, opacity: 0.7 } }} />
                    )}
                    {!momApplicable && (
                        <Tooltip arrow title="The MoM window is the selected range shifted back one month. On a range longer than a month the two windows overlap, so the comparison is meaningless — pick a single month to see it.">
                            <Typography variant="caption" color={T.textMut} sx={{ cursor: 'help', textDecoration: 'underline dotted' }}>
                                Month-on-Month hidden for ranges over one month
                            </Typography>
                        </Tooltip>
                    )}
                    <Typography variant="caption" color={T.textMut} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {filteredData.length.toLocaleString()} of {data.length.toLocaleString()} merchants
                    </Typography>
                </Stack>
            </Stack>

            <Paper sx={{
                ...premiumTableWrapper,
                // Window groups: quiet uppercase labels with a hairline accent
                // underline instead of the pastel banner fills — the accent
                // encodes "which window", the surface stays calm.
                '& .mom-header-group, & .mtd-header-group, & .ytd-header-group': {
                    bgcolor: 'var(--bg-subtle, #f8fafc)',
                    '& .MuiDataGrid-columnHeaderTitle': {
                        fontSize: '0.68rem', fontWeight: 700, letterSpacing: '0.06em',
                        textTransform: 'uppercase', color: 'var(--text-secondary, #6b7280)',
                    },
                },
                '& .mom-header-group': { boxShadow: 'inset 0 -2px 0 var(--warning, #d97706)' },
                '& .mtd-header-group': { boxShadow: 'inset 0 -2px 0 var(--brand, #2563eb)' },
                '& .ytd-header-group': { boxShadow: 'inset 0 -2px 0 var(--text-muted, #9ca3af)' },
            }}>
                <DataGrid
                    rows={filteredData} columns={columns} columnGroupingModel={columnGroupingModel}
                    loading={loading} disableRowSelectionOnClick rowHeight={52}
                    onRowClick={(params) => setDetailRow(params.row)}
                    initialState={{
                        pagination: { paginationModel: { pageSize: 25 } },
                        sorting: { sortModel: [{ field: 'ytd_pct_col', sort: 'asc' }] },
                    }}
                    pageSizeOptions={[25, 50, 100]}
                    experimentalFeatures={{ columnGrouping: true }}
                    sx={{ ...premiumDataGridStyles, '& .MuiDataGrid-row': { cursor: 'pointer' } }}
                />
            </Paper>

            {/* ═══ Merchant detail panel — no extra query, reads the clicked row ═══ */}
            <Drawer anchor="right" open={!!detailRow} onClose={() => setDetailRow(null)}
                PaperProps={{ sx: { width: { xs: '100%', sm: 420 }, bgcolor: T.card, borderLeft: `1px solid ${T.border}` } }}>
                {detailRow && (() => {
                    const sMeta = STATUS_META[detailRow.status] || { label: detailRow.status, color: T.textSec, bg: T.subtle };
                    const rMeta = detailRow.churnBand ? (RISK_META[detailRow.churnBand] || null) : null;
                    // All three metrics, all three windows — straight off the row.
                    const windows = [
                        // MoM hidden on >1-month ranges for the same reason as the grid group.
                        ...(momApplicable ? [
                            { key: 'mom', label: 'Month-on-Month', prevKey: 'mom_prev', curKey: 'mom_current', pctKey: 'mom_pct' },
                        ] : []),
                        { key: 'mtd', label: `Period YoY (${prevYear} vs ${selectedYear})`, prevKey: 'mtd_prev', curKey: 'mtd_current', pctKey: 'mtd_pct' },
                        { key: 'ytd', label: `YTD (${prevYear} vs ${selectedYear})`, prevKey: 'ytd_prev', curKey: 'ytd_current', pctKey: 'ytd_pct' },
                    ];
                    const metricRows = Object.entries(METRICS).map(([k, m]) => ({ k, ...m }));
                    const readVal = (base, sfx) => detailRow[`${base}${sfx}`];
                    const fmtBy = (v, kindOf) => v == null ? '-' : (kindOf === 'count' ? Number(v).toLocaleString('en-US') : fmt.currency(v));
                    return (
                        <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
                            {/* Header */}
                            <Box sx={{ p: 2.5, borderBottom: `1px solid ${T.border}`, display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 1 }}>
                                <Box sx={{ minWidth: 0 }}>
                                    <Typography fontWeight={700} color={T.text} noWrap sx={{ fontSize: '1.05rem' }}>{detailRow.name || detailRow.mid}</Typography>
                                    <Typography variant="caption" sx={{ fontFamily: 'monospace', color: T.textMut }}>{detailRow.mid}</Typography>
                                    <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                                        <Chip label={sMeta.label} size="small" sx={{ bgcolor: sMeta.bg, color: sMeta.color, fontWeight: 700 }} />
                                        {rMeta && (
                                            <Chip size="small" sx={{ bgcolor: rMeta.bg, color: rMeta.color, fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}
                                                label={`Churn ${rMeta.label}${detailRow.churnProbability != null ? ' · ' + (Number(detailRow.churnProbability) * 100).toFixed(0) + '%' : ''}`} />
                                        )}
                                    </Stack>
                                </Box>
                                <IconButton size="small" onClick={() => setDetailRow(null)} sx={{ color: T.textMut }}>
                                    <X size={18} />
                                </IconButton>
                            </Box>

                            {/* Body */}
                            <Box sx={{ p: 2.5, flex: 1, overflowY: 'auto' }}>
                                {detailRow.churnReason && (
                                    <Box sx={{ mb: 2.5, p: 1.5, borderRadius: '10px', bgcolor: T.subtle, border: `1px solid ${T.borderLt}` }}>
                                        <Typography variant="caption" fontWeight={700} color={T.textMut} sx={{ textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                                            Top churn driver{detailRow.churnScoredBy === 'HEURISTIC' ? ' (heuristic)' : ''}
                                        </Typography>
                                        <Typography variant="body2" color={T.textSec} sx={{ mt: 0.5 }}>{detailRow.churnReason}</Typography>
                                    </Box>
                                )}
                                {windows.map((w, wi) => (
                                    <Box key={w.key} sx={{ mb: wi < windows.length - 1 ? 2.5 : 0 }}>
                                        <Typography variant="caption" fontWeight={700} color={T.textMut}
                                            sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', display: 'block', mb: 1 }}>
                                            {w.label}
                                        </Typography>
                                        <Paper variant="outlined" sx={{ borderColor: T.borderLt, borderRadius: '10px', overflow: 'hidden' }}>
                                            {metricRows.map((m, mi) => {
                                                const prev = readVal(w.prevKey, m.suffix);
                                                const cur = readVal(w.curKey, m.suffix);
                                                const pct = readVal(w.pctKey, m.suffix);
                                                const pctNum = Number(pct);
                                                return (
                                                    <Box key={m.k} sx={{
                                                        display: 'grid', gridTemplateColumns: '1fr auto auto auto', gap: 1.5,
                                                        alignItems: 'center', px: 1.5, py: 1,
                                                        borderBottom: mi < metricRows.length - 1 ? `1px solid ${T.borderLt}` : 'none',
                                                        bgcolor: m.k === metric ? T.subtle : 'transparent',
                                                    }}>
                                                        <Typography variant="body2" fontWeight={m.k === metric ? 700 : 500} color={T.textSec}>{m.label}</Typography>
                                                        <Typography variant="body2" color={T.textMut} sx={{ fontVariantNumeric: 'tabular-nums' }}>{fmtBy(prev, m.kind)}</Typography>
                                                        <Typography variant="body2" fontWeight={600} color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{fmtBy(cur, m.kind)}</Typography>
                                                        <Typography variant="body2" fontWeight={700} sx={{
                                                            width: 64, textAlign: 'right', fontVariantNumeric: 'tabular-nums',
                                                            color: isNaN(pctNum) || pct == null ? T.textMut : pctNum < 0 ? 'var(--danger, #ef4444)' : pctNum > 0 ? 'var(--success, #10b981)' : T.textMut,
                                                        }}>
                                                            {pct == null ? '-' : `${pctNum >= 0 ? '+' : ''}${pctNum.toFixed(1)}%`}
                                                        </Typography>
                                                    </Box>
                                                );
                                            })}
                                        </Paper>
                                    </Box>
                                ))}
                                <Divider sx={{ my: 2, borderColor: T.borderLt }} />
                                <Typography variant="caption" color={T.textMut}>
                                    Columns: metric · previous window · current window · % change. Highlighted row is the metric selected on the page.
                                </Typography>
                            </Box>
                        </Box>
                    );
                })()}
            </Drawer>
        </Box>
    );
};

export default AttritionReport;
