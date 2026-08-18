import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, ToggleButton, ToggleButtonGroup, Chip, Stack, Tooltip, Drawer, IconButton, Divider, Popover } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { Activity, TrendingUp, Users, DollarSign, AlertTriangle, ShieldAlert, Brain, X, CalendarClock, ArrowRight, Info, Scale } from 'lucide-react';
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

// ─── Status vocabulary, stated in the UI ──────────────────────────
// These sentences mirror classifyAttrition() in VolumeRevenueRepository
// EXACTLY, in evaluation order. Users were inventing their own meanings for
// Churned/Declining/Stable because the thresholds lived only in backend code;
// the "How statuses are decided" popover renders this list verbatim.
// Keep in sync with the backend if the thresholds ever move.
const STATUS_RULES = [
    { key: 'NEW',        rule: 'Trading this month, but no volume at all in the prior three months — there is no baseline to compare against.' },
    { key: 'CHURNED',    rule: 'No volume this month, or below 30% of the three-month average.' },
    { key: 'DECLINING',  rule: 'Volume fell in each of the last three months in a row.' },
    { key: 'PERFORMING', rule: 'At or above 90% of the three-month average.' },
    { key: 'STABLE',     rule: 'Everything else — between 30% and 90% of the three-month average, and not falling every month.' },
];

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
// Month label off the ISO STRING, never `new Date(iso)` — that parses as UTC
// midnight and shifts a day (and so a month) in timezones behind UTC.
const monthLabel = (iso) => {
    if (!iso) return '';
    const m = Number(String(iso).slice(5, 7));
    return m >= 1 && m <= 12 ? MONTHS[m - 1] : '';
};
const dayLabel = (iso) => (iso ? `${Number(String(iso).slice(8, 10))} ${monthLabel(iso)}` : '');

// Whole days between two ISO dates, compared at UTC midnight so the result is
// timezone-stable (the rest of this file avoids new Date(iso) for the same
// reason — it parses as UTC and shifts a day in negative offsets).
const daysBetween = (fromIso, toIso) => {
    if (!fromIso || !toIso) return null;
    const a = Date.parse(`${String(fromIso).slice(0, 10)}T00:00:00Z`);
    const b = Date.parse(`${String(toIso).slice(0, 10)}T00:00:00Z`);
    if (isNaN(a) || isNaN(b)) return null;
    return Math.round((b - a) / 86400000);
};

/**
 * Why THIS merchant carries THIS status, in its own numbers.
 *
 * Every input is already on the row (the backend ships cur_month, avg_3m,
 * prev_m1..3 and avg_3m_ratio_pct alongside the status), so the explanation
 * needs no extra request. Always phrased in volume: the classifier is
 * volume-based even while the grid is showing Transactions or MSF, which is
 * itself a documented source of "these numbers disagree" confusion.
 */
const explainStatus = (row, moneyFn) => {
    if (!row || !row.status) return '';
    // This runs inside a DataGrid cell renderer: a throw here would blank the
    // whole grid, so a missing/blank classifier field degrades to a dash.
    const money = (v) => {
        if (v == null || v === '' || isNaN(Number(v))) return '—';
        try { return moneyFn(Number(v)); } catch { return String(v); }
    };
    const ratio = row.avg_3m_ratio_pct;
    const ratioTxt = ratio == null ? null : `${Number(ratio).toFixed(0)}%`;
    const avgTxt = money(row.avg_3m);
    const curTxt = money(row.cur_month);
    const tail = ' Status is always measured on volume, whichever metric the grid is showing.';

    switch (row.status) {
        case 'NEW':
            return `New — trading this month (${curTxt}) with no volume in the prior three months, so there is no baseline to compare against.${tail}`;
        case 'CHURNED':
            return Number(row.cur_month) > 0
                ? `Churned — this month's volume (${curTxt}) is ${ratioTxt} of the three-month average (${avgTxt}), below the 30% threshold.${tail}`
                : `Churned — no volume at all this month, against a three-month average of ${avgTxt}.${tail}`;
        case 'DECLINING':
            return `Declining — volume fell three months running: ${money(row.prev_m3)} → ${money(row.prev_m2)} → ${money(row.prev_m1)} → ${curTxt} this month.${tail}`;
        case 'PERFORMING':
            return `Performing — this month's volume (${curTxt}) is ${ratioTxt} of the three-month average (${avgTxt}), at or above the 90% threshold.${tail}`;
        case 'STABLE':
            return `Stable — this month's volume (${curTxt}) is ${ratioTxt} of the three-month average (${avgTxt}): under the 90% performing mark but above the 30% churn threshold.${tail}`;
        default:
            return '';
    }
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
    // Anchor for the "How statuses are decided" reference popover. Click-to-open
    // (not a hover tooltip) because it is a five-rule list people need to read.
    const [rulesAnchor, setRulesAnchor] = useState(null);
    // ── Comparison lens ──
    // The grid used to render Month-on-Month, Period YoY and YTD side by side:
    // up to eleven numeric columns with "% Change" appearing three times under
    // three different group headers. Users could not tell which comparison the
    // page wanted them to read, so they trusted none of them. One lens at a
    // time; YTD by default because it is both the most-asked executive question
    // and always applicable (MoM is hidden on ranges over a month).
    const [lens, setLens] = useState('ytd');

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

    // ── Dual-clock disclosure ──
    // This page runs on TWO windows and users conflate them: the money columns
    // follow the selected date range, while the Status column is classified on
    // calendar months anchored at the latest LOADED data date, whatever the
    // range says. Stating both windows in plain words, permanently and above
    // the data, removes the single largest source of "why is this merchant
    // churned when I selected last quarter?" questions.
    const clocks = useMemo(() => {
        if (!meta?.currentStart || !meta?.currentEnd) return null;
        const money = `${dayLabel(meta.currentStart)} → ${dayLabel(meta.currentEnd)}`;
        const anchorMonth = monthLabel(meta.classifierAnchor);
        const from = monthLabel(meta.baselineStart);
        const to = monthLabel(meta.baselineEnd);
        const status = anchorMonth
            ? `${anchorMonth} month-to-date vs ${from && to ? `${from}–${to}` : 'prior 3-month'} average`
            : null;
        return { money, status };
    }, [meta]);

    // ── Drawer narrowings, surfaced ──
    // Once the filter drawer closes, its filters are invisible: a user can be
    // staring at 3 merchants out of 400 with nothing on screen explaining why,
    // which reads as broken data rather than an active filter. Every server-side
    // narrowing now appears as one removable chip alongside the status/bucket
    // chips, so the full filter state is readable in a single row.
    const drawerChips = useMemo(() => {
        const out = [];
        // Clearing re-queries with the seeded object rather than relying on
        // setFilters having committed — the same race this page fixed elsewhere.
        const apply = (patch) => {
            const next = { ...filters, ...patch };
            setFilters(next);
            fetchData(next);
        };
        const LISTS = [
            ['midList', 'MID'], ['sidList', 'SID'], ['partnerList', 'Partner'],
            ['rmList', 'RM'], ['teamLeaderList', 'Team lead'], ['mccList', 'MCC'],
            ['industryList', 'Industry'], ['channelList', 'Channel'],
            ['schemeList', 'Scheme'], ['cardTypeList', 'Card type'],
            ['destinationList', 'Destination'],
        ];
        LISTS.forEach(([key, label]) => {
            const v = filters[key];
            if (Array.isArray(v) && v.length) {
                out.push({
                    id: key,
                    label: v.length === 1 ? `${label}: ${v[0]}` : `${label}: ${v.length} selected`,
                    clear: () => apply({ [key]: [] }),
                });
            }
        });
        if (filters.merchantName && filters.merchantName.trim()) {
            out.push({ id: 'merchantName', label: `Name: "${filters.merchantName.trim()}"`, clear: () => apply({ merchantName: '' }) });
        }
        if (filters.openDateStart || filters.openDateEnd) {
            out.push({
                id: 'openDate',
                label: `Opened ${filters.openDateStart || '…'} → ${filters.openDateEnd || '…'}`,
                clear: () => apply({ openDateStart: '', openDateEnd: '' }),
            });
        }
        return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filters]);

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

    // ── Lens definitions ──
    // Each lens names one comparison window and the row keys behind it. The grid
    // reads whichever is active through STABLE column ids (cmp_prev/cmp_cur/
    // cmp_pct), so switching lens re-labels and re-values the same three columns
    // instead of swapping column identity — which keeps the sort model, column
    // widths and any user column state intact across a switch.
    const LENSES = useMemo(() => ({
        mom: {
            label: 'Month-on-Month', group: 'Month-on-Month',
            prevHeader: 'Prev Month', curHeader: 'This Month',
            prevKey: 'mom_prev', curKey: 'mom_current', pctKey: 'mom_pct',
            about: 'The selected range against the same-length window one month earlier.',
        },
        mtd: {
            label: 'Period YoY', group: `Period YoY · ${prevYear} vs ${selectedYear}`,
            prevHeader: `${prevYear}`, curHeader: `${selectedYear}`,
            prevKey: 'mtd_prev', curKey: 'mtd_current', pctKey: 'mtd_pct',
            about: 'The selected range against the same range one year earlier.',
        },
        ytd: {
            label: 'Year to date', group: `Year to date · ${prevYear} vs ${selectedYear}`,
            prevHeader: `${prevYear} YTD`, curHeader: `${selectedYear} YTD`,
            prevKey: 'ytd_prev', curKey: 'ytd_current', pctKey: 'ytd_pct',
            about: 'January 1st to the end of the selected range, against the same span last year.',
        },
    }), [prevYear, selectedYear]);

    // MoM can become inapplicable while it is selected (user widens the range),
    // so resolve rather than trusting the raw state.
    const activeLens = (lens === 'mom' && !momApplicable) ? 'ytd' : lens;
    const L = LENSES[activeLens];

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

    // ── Verdict ──
    // The page's actual job stated in one sentence: who is being lost, what it
    // costs, and where the attention should go. Everything below is the evidence
    // for it. Built from rows already loaded — no extra request.
    const verdict = useMemo(() => {
        if (!data.length) return null;
        const churned = statusCounts.CHURNED;
        const declining = statusCounts.DECLINING;
        const lostValue = data.reduce((s, d) =>
            d.status === 'CHURNED' ? s + (Number(val(d, 'ytd_current')) || 0) : s, 0);
        // The most useful call list on the page: merchants the classifier still
        // reads as healthy but the model flags as likely to leave. They are
        // invisible in a status-only view precisely because nothing looks wrong yet.
        const silentRisk = rows.filter(r =>
            r.churnBand === 'HIGH' && (r.status === 'PERFORMING' || r.status === 'STABLE'));
        const silentValue = silentRisk.reduce((s, d) => s + (Number(val(d, 'ytd_current')) || 0), 0);

        const parts = [];
        if (churned > 0) {
            parts.push(`${churned} merchant${churned === 1 ? '' : 's'} churned, holding ${fmtMeasure(lostValue)} of year-to-date ${METRICS[metric].label.toLowerCase()}`);
        }
        if (declining > 0) parts.push(`${declining} more ${declining === 1 ? 'is' : 'are'} declining`);
        if (!parts.length) parts.push('No merchants churned or declining in this view');

        return {
            headline: `${parts.join(' · ')}.`,
            action: silentRisk.length > 0
                ? `${silentRisk.length} still-healthy merchant${silentRisk.length === 1 ? '' : 's'} (${fmtMeasure(silentValue)}) ${silentRisk.length === 1 ? 'is' : 'are'} flagged high churn risk — the most useful call list here.`
                : null,
            tone: churned > 0 || declining > 0 ? 'bad' : 'good',
        };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [data, rows, statusCounts, metric]);

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
    /**
     * % change cell for one comparison window.
     *
     * A percentage is only a comparison when there is something to compare
     * against. With an empty prior window calculateGrowth() returns +100%,
     * so a merchant that simply had no history rendered identically to one
     * that genuinely doubled — and a whole empty window produced a screen of
     * +100% that read as portfolio-wide growth. Those cases now say what they
     * are instead of borrowing the language of growth.
     */
    const pctCellFor = (prevBase, curBase) => (params) => {
        const prev = Number(val(params.row, prevBase));
        const cur = Number(val(params.row, curBase));
        const prevMissing = val(params.row, prevBase) == null || isNaN(prev) || prev === 0;

        if (prevMissing) {
            const isNewActivity = !isNaN(cur) && cur > 0;
            return (
                <Tooltip arrow title={isNewActivity
                    ? 'No activity in the comparison window, so this is new activity rather than measurable growth.'
                    : 'No activity in either window — nothing to compare.'}>
                    <Typography variant="body2"
                        sx={{ color: T.textMut, fontWeight: 600, cursor: 'help', textDecoration: 'underline dotted' }}>
                        {isNewActivity ? 'new' : '—'}
                    </Typography>
                </Tooltip>
            );
        }
        return (
            <Typography variant="body2" sx={{ fontWeight: 'bold', color: params.value < 0 ? 'var(--danger, #ef4444)' : params.value > 0 ? 'var(--success, #10b981)' : T.textMut }}>
                {pctFormatter(params.value)}
            </Typography>
        );
    };
    // Status chip carries its own reasoning — the thresholds live in backend
    // code, so without this the label is an unexplained verdict.
    const statusCell = (params) => {
        const m = STATUS_META[params.value] || { label: params.value, color: T.textSec, bg: T.subtle };
        const why = explainStatus(params.row, fmt.currency);
        const chip = <Chip label={m.label} size="small" sx={{ bgcolor: m.bg, color: m.color, fontWeight: 700, cursor: why ? 'help' : 'default' }} />;
        return why ? <Tooltip arrow title={why}>{chip}</Tooltip> : chip;
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
            // Status and Predicted Churn are two DIFFERENT questions that were
            // being read as one contradictory signal (a "Performing" merchant at
            // 80% churn risk looked like a bug rather than the most valuable row
            // on the page). The headers now say which direction each one looks.
            { field: 'status', headerName: 'STATUS · SO FAR', width: 140,
                description: 'Looking back: this month\'s volume measured against this merchant\'s own average of the prior three months. Hover any chip for its numbers.',
                valueGetter: (v, row) => row.status, renderCell: statusCell },
        ];
        // Predicted churn-risk column, inserted right after Status — only when the
        // batch has produced scores for this tenant.
        if (churnAvailable) {
            base.push({
                field: 'churn_risk', headerName: 'PREDICTED CHURN', width: 165, type: 'number',
                description: 'Looking ahead: model-scored likelihood of churn in the next 30–60 days. Independent of Status — a healthy merchant can carry a high score, and that is the useful case.',
                valueGetter: (v, row) => (row.churnProbability == null ? -1 : row.churnProbability),
                renderCell: churnCell,
            });
        }
        return [
            ...base,
            // ── The one active comparison ──
            // Fixed column ids, lens-driven labels and values. All three windows
            // remain available (lens control above the grid, every window at once
            // in the merchant panel and the CSV export) — they are simply no
            // longer competing for attention in the same row of headers.
            { field: 'cmp_prev', headerName: L.prevHeader, width: 130, type: 'number',
                valueGetter: (v, row) => val(row, L.prevKey), renderCell: measureCell },
            { field: 'cmp_cur', headerName: L.curHeader, width: 130, type: 'number',
                valueGetter: (v, row) => val(row, L.curKey), renderCell: measureCellBold },
            { field: 'cmp_pct', headerName: '% Change', width: 120, type: 'number',
                valueGetter: (v, row) => val(row, L.pctKey), renderCell: pctCellFor(L.prevKey, L.curKey) },
        ];
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [metric, selectedYear, prevYear, churnAvailable, activeLens, L]);

    const columnGroupingModel = useMemo(() => ([
        { groupId: 'cmp_group', headerName: L.group, headerClassName: 'cmp-header-group',
            children: [{ field: 'cmp_prev' }, { field: 'cmp_cur' }, { field: 'cmp_pct' }] },
    ]), [L]);

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
                    // Column spec mirrors the on-screen grid: friendly names,
                    // active metric only, and the same MoM applicability rule.
                    const m = METRICS[metric].label;
                    exportToCSV(filteredData, parts.join('_'), [
                        { label: 'MID', key: 'mid' },
                        { label: 'Merchant Name', key: 'name' },
                        { label: 'Status', getter: r => STATUS_META[r.status]?.label || r.status },
                        { label: 'Last Active', key: 'last_activity' },
                        { label: 'Days Quiet', getter: r => daysBetween(r.last_activity, meta?.classifierAnchor) ?? '' },
                        ...(churnAvailable ? [
                            { label: 'Churn Risk %', getter: r => r.churnProbability == null ? '' : Number(r.churnProbability).toFixed(1) },
                            { label: 'Churn Risk Band', getter: r => r.churnBand ? (RISK_META[r.churnBand]?.label || r.churnBand) : '' },
                            { label: 'Churn Top Reason', key: 'churnReason' },
                        ] : []),
                        ...(momApplicable ? [
                            { label: `Prev Month ${m}`, getter: r => val(r, 'mom_prev') },
                            { label: `Current Month ${m}`, getter: r => val(r, 'mom_current') },
                            { label: 'MoM % Change', getter: r => val(r, 'mom_pct') },
                        ] : []),
                        { label: `Period ${prevYear} ${m}`, getter: r => val(r, 'mtd_prev') },
                        { label: `Period ${selectedYear} ${m}`, getter: r => val(r, 'mtd_current') },
                        { label: 'Period YoY % Change', getter: r => val(r, 'mtd_pct') },
                        { label: `YTD ${prevYear} ${m}`, getter: r => val(r, 'ytd_prev') },
                        { label: `YTD ${selectedYear} ${m}`, getter: r => val(r, 'ytd_current') },
                        { label: 'YTD % Change', getter: r => val(r, 'ytd_pct') },
                    ]);
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

            {/* ── Verdict ──
                The answer first, in words. The tiles, band, charts and grid below
                are the evidence; previously a user had to assemble the answer from
                them unaided. */}
            {verdict && !loading && (
                <Box sx={{
                    px: 2, py: 1.5, mb: 1.5,
                    borderRadius: 'var(--radius-lg, 12px)',
                    border: `1px solid ${T.border}`,
                    background: `linear-gradient(100deg,
                        color-mix(in srgb, ${verdict.tone === 'bad' ? 'var(--danger)' : 'var(--success)'} 12%, var(--bg-card)) 0%,
                        color-mix(in srgb, ${verdict.tone === 'bad' ? 'var(--danger)' : 'var(--success)'} 3%, var(--bg-card)) 55%,
                        var(--bg-card) 100%)`,
                }}>
                    <Typography sx={{ fontSize: '0.95rem', fontWeight: 700, color: T.text, lineHeight: 1.45 }}>
                        {verdict.headline}
                    </Typography>
                    {verdict.action && (
                        <Typography sx={{ fontSize: '0.82rem', color: T.textSec, mt: 0.5, display: 'flex', alignItems: 'center', gap: 0.75 }}>
                            <Brain size={14} style={{ flexShrink: 0, color: 'var(--attr-atrisk, #dc2626)' }} />
                            {verdict.action}
                        </Typography>
                    )}
                </Box>
            )}

            {/* ── The two clocks, stated ──
                Money columns follow the selected range; Status follows calendar
                months anchored at the latest data. Both are named here so neither
                is ever assumed to be the other. */}
            {clocks && data.length > 0 && (
                <Box sx={{
                    display: 'flex', alignItems: 'center', gap: { xs: 0.75, sm: 2 }, flexWrap: 'wrap',
                    px: 1.75, py: 0.9, mb: 1.5,
                    borderRadius: 'var(--radius-md, 10px)',
                    border: `1px solid ${T.border}`,
                    bgcolor: T.subtle,
                }}>
                    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, minWidth: 0 }}>
                        <CalendarClock size={14} style={{ flexShrink: 0, color: 'var(--text-muted)' }} />
                        <Typography variant="caption" sx={{ color: T.textMut, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase' }}>
                            Money columns
                        </Typography>
                        <Typography variant="caption" sx={{ color: T.textStr, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                            {clocks.money}
                        </Typography>
                    </Box>
                    {clocks.status && (
                        <>
                            <Box sx={{ width: '1px', height: 14, bgcolor: T.border, display: { xs: 'none', sm: 'block' } }} />
                            <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, minWidth: 0 }}>
                                <Scale size={14} style={{ flexShrink: 0, color: 'var(--text-muted)' }} />
                                <Typography variant="caption" sx={{ color: T.textMut, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase' }}>
                                    Status
                                </Typography>
                                <Typography variant="caption" sx={{ color: T.textStr, fontWeight: 600 }}>
                                    {clocks.status}
                                </Typography>
                                <Tooltip arrow title="The status classifier always runs on whole calendar months anchored at the latest loaded data date, so it does not move with the selected date range above.">
                                    <Box component="span" sx={{ display: 'inline-flex', color: T.textMut, cursor: 'help' }}>
                                        <Info size={13} />
                                    </Box>
                                </Tooltip>
                            </Box>
                        </>
                    )}
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
                            {/* The status WINDOW is stated in the dual-clock bar above; what
                                was missing here is the RULES. The thresholds lived only in
                                backend code, so every user invented their own meaning for
                                "Declining" and then disputed the label. */}
                            <Box component="button" type="button"
                                onClick={(e) => setRulesAnchor(e.currentTarget)}
                                sx={{
                                    display: 'inline-flex', alignItems: 'center', gap: 0.5,
                                    px: 0.5, py: 0.25, border: 'none', background: 'none',
                                    cursor: 'pointer', fontFamily: 'inherit',
                                    color: 'var(--brand, #2563eb)', fontSize: '0.72rem', fontWeight: 700,
                                    '&:hover': { textDecoration: 'underline' },
                                }}>
                                <Info size={12} /> How statuses are decided
                            </Box>
                        </Box>
                        <Popover
                            open={Boolean(rulesAnchor)} anchorEl={rulesAnchor}
                            onClose={() => setRulesAnchor(null)}
                            anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}
                            slotProps={{ paper: { sx: { p: 2, maxWidth: 460, borderRadius: 'var(--radius-lg, 12px)' } } }}
                        >
                            <Typography sx={{ fontSize: '0.8rem', fontWeight: 700, color: T.text, mb: 0.5 }}>
                                How statuses are decided
                            </Typography>
                            <Typography variant="caption" sx={{ color: T.textMut, display: 'block', mb: 1.5 }}>
                                Each merchant's volume this month is compared with its own average of the
                                prior three months. Rules are checked in this order — the first match wins.
                            </Typography>
                            <Stack spacing={1.25}>
                                {STATUS_RULES.map(({ key, rule }) => {
                                    const m = STATUS_META[key];
                                    return (
                                        <Box key={key} sx={{ display: 'flex', gap: 1.25, alignItems: 'flex-start' }}>
                                            <Chip label={m.label} size="small"
                                                sx={{ bgcolor: m.bg, color: m.color, fontWeight: 700, flexShrink: 0, minWidth: 88 }} />
                                            <Typography variant="caption" sx={{ color: T.textSec, lineHeight: 1.5 }}>
                                                {rule}
                                            </Typography>
                                        </Box>
                                    );
                                })}
                            </Stack>
                            <Divider sx={{ my: 1.5, borderColor: T.border }} />
                            <Typography variant="caption" sx={{ color: T.textMut, lineHeight: 1.5, display: 'block' }}>
                                Status is always measured on <b>volume</b>, even when the table is showing
                                Transactions or Revenue. Hover any status chip in the table to see that
                                merchant's own numbers.
                            </Typography>
                        </Popover>
                        {/* Nothing previously told users the band was interactive, so its
                            best feature went unused. Show the affordance until it is. */}
                        {statusFilter !== 'ALL' ? (
                            <Typography variant="caption" onClick={() => setStatusFilter('ALL')}
                                sx={{ cursor: 'pointer', color: 'var(--brand, #2563eb)', fontWeight: 700 }}>
                                Showing {STATUS_META[statusFilter]?.label} — clear ✕
                            </Typography>
                        ) : (
                            <Typography variant="caption" sx={{ color: T.textMut }}>
                                Click a segment to filter the table
                            </Typography>
                        )}
                    </Box>
                    <Box sx={{ display: 'flex', gap: '2px', height: 18, borderRadius: '6px', overflow: 'hidden', mb: 1.5, bgcolor: T.subtle }}>
                        {analytics.breakdown.map(s => s.count > 0 && (
                            <Box key={s.key} title={`${s.label}: ${s.count} — click to filter`}
                                onClick={() => setStatusFilter(prev => prev === s.key ? 'ALL' : s.key)}
                                sx={{ width: `${s.pct}%`,
                                    // Vertical gradient body — same treatment as the chart bars.
                                    background: `linear-gradient(180deg,
                                        color-mix(in srgb, ${s.color} 88%, #fff) 0%,
                                        ${s.color} 45%,
                                        color-mix(in srgb, ${s.color} 62%, var(--bg-card)) 100%)`,
                                    transition: 'width .5s ease, opacity .15s', cursor: 'pointer',
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
                                    <Box sx={{ width: 10, height: 10, borderRadius: '3px',
                                        background: `linear-gradient(180deg, color-mix(in srgb, ${s.color} 88%, #fff) 0%, ${s.color} 100%)` }} />
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
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
                    <Box>
                        <Typography variant="caption" sx={{ color: T.textMut, fontWeight: 700, letterSpacing: '0.05em', textTransform: 'uppercase', display: 'block', mb: 0.5 }}>
                            Measure
                        </Typography>
                        <ToggleButtonGroup size="small" exclusive value={metric}
                            onChange={(e, v) => v && setMetric(v)} aria-label="metric">
                            {Object.entries(METRICS).map(([k, m]) => (
                                <ToggleButton key={k} value={k} sx={{ textTransform: 'none', fontWeight: 600 }}>{m.label}</ToggleButton>
                            ))}
                        </ToggleButtonGroup>
                    </Box>
                    {/* ── Comparison lens ──
                        One window at a time. Every window is still reachable: switch
                        here, or open any merchant to see all of them at once. */}
                    <Box>
                        <Typography variant="caption" sx={{ color: T.textMut, fontWeight: 700, letterSpacing: '0.05em', textTransform: 'uppercase', display: 'block', mb: 0.5 }}>
                            Compare against
                        </Typography>
                        <ToggleButtonGroup size="small" exclusive value={activeLens}
                            onChange={(e, v) => v && setLens(v)} aria-label="comparison window">
                            {Object.entries(LENSES).map(([k, def]) => {
                                const disabled = k === 'mom' && !momApplicable;
                                const btn = (
                                    <ToggleButton key={k} value={k} disabled={disabled}
                                        sx={{ textTransform: 'none', fontWeight: 600 }}>
                                        {def.label}
                                    </ToggleButton>
                                );
                                return disabled ? (
                                    // A disabled control with no reason given reads as a bug.
                                    <Tooltip key={k} arrow title="Month-on-Month needs a range of a month or less — on a longer range the shifted window overlaps the current one, so the comparison is meaningless.">
                                        <span>{btn}</span>
                                    </Tooltip>
                                ) : (
                                    <Tooltip key={k} arrow title={def.about}>{btn}</Tooltip>
                                );
                            })}
                        </ToggleButtonGroup>
                    </Box>
                </Stack>
                {/* One row carrying EVERY active narrowing — the in-page ones
                    (status band, distribution bucket) and the drawer's
                    server-side ones, which were previously invisible. */}
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
                    {drawerChips.map(c => (
                        <Chip key={c.id} size="small" onDelete={c.clear} label={c.label}
                            sx={{ fontWeight: 600, color: T.textSec, bgcolor: T.subtle, border: `1px solid ${T.border}`,
                                '& .MuiChip-deleteIcon': { color: T.textMut, opacity: 0.8 } }} />
                    ))}
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
                    {/* MoM applicability is now explained on the disabled lens button
                        itself, so the standalone note here would be a second voice. */}
                    <Typography variant="caption" color={T.textMut} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {filteredData.length.toLocaleString()} of {data.length.toLocaleString()} merchants
                    </Typography>
                </Stack>
            </Stack>

            <Paper sx={{
                ...premiumTableWrapper,
                // The single active comparison group sits INSIDE the navy header
                // bar, so it stays transparent (a light fill here would punch a
                // pale band through the gradient) and separates itself with a
                // translucent rule and slightly dimmed type instead.
                '& .cmp-header-group': {
                    bgcolor: 'transparent',
                    boxShadow: 'inset 0 -1px 0 rgba(255, 255, 255, 0.22)',
                    '& .MuiDataGrid-columnHeaderTitle': {
                        fontSize: '0.68rem', fontWeight: 700, letterSpacing: '0.06em',
                        textTransform: 'uppercase', color: 'var(--table-head-muted)',
                    },
                },
            }}>
                <DataGrid
                    rows={filteredData} columns={columns} columnGroupingModel={columnGroupingModel}
                    loading={loading} disableRowSelectionOnClick rowHeight={52}
                    onRowClick={(params) => setDetailRow(params.row)}
                    initialState={{
                        pagination: { paginationModel: { pageSize: 25 } },
                        // Worst change first, on whichever comparison is in view —
                        // the column id is lens-independent so this survives a switch.
                        sorting: { sortModel: [{ field: 'cmp_pct', sort: 'asc' }] },
                    }}
                    pageSizeOptions={[25, 50, 100]}
                    experimentalFeatures={{ columnGrouping: true }}
                    // The row rule must MERGE with the shared one, not replace it:
                    // a bare `'& .MuiDataGrid-row': { cursor }` key overwrites the
                    // whole selector and silently drops the shared row tint, hover
                    // and selected styling.
                    sx={{
                        ...premiumDataGridStyles,
                        '& .MuiDataGrid-row': {
                            ...premiumDataGridStyles['& .MuiDataGrid-row'],
                            cursor: 'pointer',
                        },
                    }}
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
                                    <Typography variant="caption" sx={{ fontFamily: 'monospace', color: T.textMut, display: 'block' }}>{detailRow.mid}</Typography>
                                    {/* Status and churn chips live in their own labelled
                                        sections below, where each says which direction it
                                        looks — repeating them here would re-merge the two
                                        signals this panel exists to separate. Recency,
                                        though, belongs up top: it sets the urgency of the
                                        call before any of the analysis is read. */}
                                    {(() => {
                                        const quiet = daysBetween(detailRow.last_activity, meta?.classifierAnchor);
                                        if (detailRow.last_activity == null) return null;
                                        const urgent = quiet != null && quiet >= 30;
                                        return (
                                            <Typography variant="caption" sx={{ mt: 0.75, display: 'inline-flex', alignItems: 'center', gap: 0.5, color: urgent ? 'var(--danger-text)' : T.textSec, fontWeight: 600 }}>
                                                <CalendarClock size={12} />
                                                {quiet == null ? `Last active ${detailRow.last_activity}`
                                                    : quiet <= 0 ? 'Active on the latest data day'
                                                    : `Quiet ${quiet} day${quiet === 1 ? '' : 's'} · last active ${detailRow.last_activity}`}
                                            </Typography>
                                        );
                                    })()}
                                </Box>
                                <IconButton size="small" onClick={() => setDetailRow(null)} sx={{ color: T.textMut }}>
                                    <X size={18} />
                                </IconButton>
                            </Box>

                            {/* Body */}
                            <Box sx={{ p: 2.5, flex: 1, overflowY: 'auto' }}>
                                {/* ── LOOKING BACK: the status, and the four months behind it ──
                                    The chart is the explanation: four monthly bars against the
                                    dashed three-month average is exactly what the classifier
                                    compares, so the verdict becomes visible rather than asserted. */}
                                <Box sx={{ mb: 2.5 }}>
                                    <Typography variant="caption" fontWeight={700} color={T.textMut}
                                        sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', display: 'block', mb: 1 }}>
                                        Looking back · what already happened
                                    </Typography>
                                    <Paper variant="outlined" sx={{ borderColor: T.borderLt, borderRadius: '10px', p: 1.75 }}>
                                        <Chip label={sMeta.label} size="small" sx={{ bgcolor: sMeta.bg, color: sMeta.color, fontWeight: 700, mb: 1 }} />
                                        <Typography variant="body2" sx={{ color: T.textSec, lineHeight: 1.55, mb: 1.5 }}>
                                            {explainStatus(detailRow, fmt.currency)}
                                        </Typography>
                                        {(() => {
                                            const trend = [
                                                { back: 3, v: Number(detailRow.prev_m3) || 0 },
                                                { back: 2, v: Number(detailRow.prev_m2) || 0 },
                                                { back: 1, v: Number(detailRow.prev_m1) || 0 },
                                                { back: 0, v: Number(detailRow.cur_month) || 0, current: true },
                                            ];
                                            const avg3 = Number(detailRow.avg_3m) || 0;
                                            const peak = Math.max(...trend.map(t => t.v), avg3, 1);
                                            // Month names walk back from the classifier anchor, off
                                            // the ISO string (never new Date(iso) — that shifts a day,
                                            // and so a month, in timezones behind UTC).
                                            const anchorM = meta?.classifierAnchor ? Number(String(meta.classifierAnchor).slice(5, 7)) : null;
                                            const nameFor = (back) => anchorM ? MONTHS[((anchorM - 1 - back) % 12 + 12) % 12] : ['3 ago', '2 ago', 'Last', 'This'][3 - back];
                                            const avgTop = 100 - (avg3 / peak) * 100;
                                            return (
                                                <>
                                                    <Box sx={{ position: 'relative', height: 96, display: 'flex', alignItems: 'flex-end', gap: 1.25, px: 0.5 }}>
                                                        {/* Three-month average — the line the status is measured against */}
                                                        {avg3 > 0 && (
                                                            <Box sx={{
                                                                position: 'absolute', left: 0, right: 0, top: `${avgTop}%`,
                                                                borderTop: `1px dashed ${T.textMut}`, pointerEvents: 'none',
                                                            }} />
                                                        )}
                                                        {trend.map(t => (
                                                            <Tooltip key={t.back} arrow title={`${nameFor(t.back)}: ${fmt.currency(t.v)}`}>
                                                                <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', height: '100%', cursor: 'help' }}>
                                                                    <Box sx={{
                                                                        height: `${Math.max((t.v / peak) * 100, 1.5)}%`,
                                                                        borderRadius: '4px 4px 0 0',
                                                                        background: t.current
                                                                            ? `linear-gradient(180deg, ${sMeta.color} 0%, color-mix(in srgb, ${sMeta.color} 55%, var(--bg-card)) 100%)`
                                                                            : `linear-gradient(180deg, var(--chart-4) 0%, color-mix(in srgb, var(--chart-4) 45%, var(--bg-card)) 100%)`,
                                                                    }} />
                                                                </Box>
                                                            </Tooltip>
                                                        ))}
                                                    </Box>
                                                    <Box sx={{ display: 'flex', gap: 1.25, px: 0.5, mt: 0.75 }}>
                                                        {trend.map(t => (
                                                            <Typography key={t.back} variant="caption"
                                                                sx={{ flex: 1, textAlign: 'center', color: t.current ? T.text : T.textMut, fontWeight: t.current ? 700 : 500 }}>
                                                                {nameFor(t.back)}
                                                            </Typography>
                                                        ))}
                                                    </Box>
                                                    {avg3 > 0 && (
                                                        <Typography variant="caption" sx={{ color: T.textMut, display: 'block', mt: 0.75 }}>
                                                            Dashed line = three-month average ({fmt.currency(avg3)}). Volume, always.
                                                        </Typography>
                                                    )}
                                                </>
                                            );
                                        })()}
                                    </Paper>
                                </Box>

                                {/* ── LOOKING AHEAD: the model's forecast, kept visibly separate ── */}
                                {rMeta && (
                                    <Box sx={{ mb: 2.5 }}>
                                        <Typography variant="caption" fontWeight={700} color={T.textMut}
                                            sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', display: 'block', mb: 1 }}>
                                            Looking ahead · predicted next 30–60 days
                                        </Typography>
                                        <Paper variant="outlined" sx={{ borderColor: T.borderLt, borderRadius: '10px', p: 1.75 }}>
                                            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1.25 }}>
                                                <Chip label={`${rMeta.label} risk`} size="small"
                                                    sx={{ bgcolor: rMeta.bg, color: rMeta.color, fontWeight: 700 }} />
                                                {detailRow.churnProbability != null && (
                                                    <Typography variant="body2" fontWeight={700} sx={{ color: rMeta.color, fontVariantNumeric: 'tabular-nums' }}>
                                                        {(Number(detailRow.churnProbability) * 100).toFixed(0)}%
                                                    </Typography>
                                                )}
                                            </Stack>
                                            {detailRow.churnProbability != null && (
                                                <Box sx={{ height: 6, borderRadius: 999, bgcolor: T.subtle, overflow: 'hidden', mb: 1.25 }}>
                                                    <Box sx={{
                                                        width: `${Math.min(Math.max(Number(detailRow.churnProbability) * 100, 0), 100)}%`,
                                                        height: '100%', borderRadius: 999,
                                                        background: `linear-gradient(90deg, color-mix(in srgb, ${rMeta.color} 55%, var(--bg-card)), ${rMeta.color})`,
                                                    }} />
                                                </Box>
                                            )}
                                            {detailRow.churnReason && (
                                                <Typography variant="body2" sx={{ color: T.textSec, lineHeight: 1.5 }}>
                                                    <b>Top driver:</b> {detailRow.churnReason}
                                                    {detailRow.churnScoredBy === 'HEURISTIC' ? ' (heuristic, not the trained model)' : ''}
                                                </Typography>
                                            )}
                                            <Typography variant="caption" sx={{ color: T.textMut, display: 'block', mt: 1 }}>
                                                Scored independently of the status above — a healthy merchant with a high
                                                score is a warning worth acting on, not a contradiction.
                                            </Typography>
                                        </Paper>
                                    </Box>
                                )}
                                {/* Every window and every metric at once — the detail the
                                    grid deliberately no longer competes to show. */}
                                <Typography variant="caption" fontWeight={700} color={T.textMut}
                                    sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', display: 'block', mb: 1 }}>
                                    All comparisons
                                </Typography>
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
