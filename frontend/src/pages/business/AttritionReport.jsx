import React, { useState, useEffect, useMemo, useRef } from 'react';
import { Box, Paper, Typography, ToggleButton, ToggleButtonGroup, Chip, Stack, Tooltip, Drawer, IconButton, Divider, Popover } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { Activity, AlertTriangle, X, CalendarClock, ArrowRight, Info, Scale } from 'lucide-react';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt, convertForDisplay, isUsdDisplay } from '../../utils/formatters';
import api from '../../api/axios';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import SkeletonLoader from '../../components/SkeletonLoader';
import BusinessFilters from '../../components/BusinessFilters';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
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
    // Net spread = net margin + DCC (acquirer) + rental. A merchant whose
    // volume is falling but whose rental is steady is a different call from
    // one whose whole spread is collapsing. Only measurable on the
    // merchant-grain route (meta.spreadAvailable) — greyed otherwise.
    spread:  { label: 'Net Spread',   suffix: '_spread', kind: 'currency' },
};

// Attrition status → colour + label. Mirrors classifyAttrition() in the backend,
// which classifies on the current month against the trailing 3-month average:
// churned <30% or zero, declining = 3 months constantly dropping, performing >=90%.
// Foreground/background both routed through CSS vars so dark mode can retint.
// Severity maps onto the app's status semantics: churned = danger (red),
// declining = warning (amber), stable = neutral ink, performing = success —
// the previous purple/orange pairing made the most severe state read as a
// brand accent instead of a problem.
// The --attr-* tokens are defined in index.css, derived from the Meridian
// semantic palette (negative / attention / success / primary), so they flip
// with dark mode. Fallbacks mirror the light Meridian values.
// `hard` marks the statuses allowed to carry full-strength colour — everything
// else renders quiet so risk is the only thing that pops.
const STATUS_META = {
    CHURNED:    { label: 'Churned',    color: 'var(--attr-churned, #B3382C)',   bg: 'var(--attr-churned-bg, #F4E4E1)',   hard: true },
    DECLINING:  { label: 'Declining',  color: 'var(--attr-declining, #8C5E12)', bg: 'var(--attr-declining-bg, #F0E7D6)', hard: true },
    // Onboarded but never processed a single transaction across the whole
    // queried horizon. Quiet colour: actionable (activation call), not churn.
    NON_STARTER: { label: 'Non-starter', color: 'var(--attr-nonstarter, #6E5A99)', bg: 'var(--attr-nonstarter-bg, #EAE5F3)' },
    STABLE:     { label: 'Stable',     color: 'var(--attr-stable, #51618C)',    bg: 'var(--attr-stable-bg, #E4E9F2)' },
    PERFORMING: { label: 'Performing', color: 'var(--attr-growing, #0B6B4D)',   bg: 'var(--attr-growing-bg, #DFEFE8)' },
    // Trading this month with no trailing 3-month baseline — nothing to attrite
    // FROM. Steel: informational, neither healthy nor at-risk.
    NEW:        { label: 'New',        color: 'var(--attr-new, #3F63B0)',       bg: 'var(--attr-new-bg, #E2E9F6)' },
};

// Predicted churn-risk band → colour. These are the ML forward-looking scores,
// distinct from the backward-looking attrition STATUS above.
const RISK_META = {
    HIGH:   { label: 'High',   color: 'var(--attr-atrisk, #B3382C)',    bg: 'var(--attr-atrisk-bg, #F4E4E1)' },
    MEDIUM: { label: 'Medium', color: 'var(--attr-declining, #8C5E12)', bg: 'var(--attr-declining-bg, #F0E7D6)' },
    LOW:    { label: 'Low',    color: 'var(--attr-growing, #0B6B4D)',   bg: 'var(--attr-growing-bg, #DFEFE8)' },
};

// ─── Status vocabulary, stated in the UI ──────────────────────────
// These sentences mirror classifyAttrition() in VolumeRevenueRepository
// EXACTLY, in evaluation order. Users were inventing their own meanings for
// Churned/Declining/Stable because the thresholds lived only in backend code;
// the "How statuses are decided" popover renders this list verbatim.
// Keep in sync with the backend if the thresholds ever move.
const STATUS_RULES = [
    { key: 'NON_STARTER', rule: 'Onboarded but has never processed any volume at all. Decided first, before the rules below.' },
    { key: 'NEW',        rule: 'Trading this month, but no volume at all in the prior three months — there is no baseline to compare against.' },
    { key: 'CHURNED',    rule: 'Three straight silent months — no volume this month or in either of the two months before it.' },
    { key: 'DECLINING',  rule: 'At risk but not gone: no volume this month only, or trading below 30% of the three-month average, or volume falling three months in a row.' },
    { key: 'PERFORMING', rule: 'At or above 90% of the three-month average.' },
    { key: 'STABLE',     rule: 'Everything else — between 30% and 90% of the three-month average, and not falling every month.' },
];
// Population exclusions applied by the backend before any status is decided.
// Stated in the same popover so "where did merchant X go" has an answer.
const EXCLUSION_NOTE = 'Closed merchants and merchants onboarded within the last 30 days are excluded from this report entirely.';

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
        case 'NON_STARTER':
            return `Non-starter — onboarded but has never processed any volume.${tail}`;
        case 'NEW':
            return `New — trading this month (${curTxt}) with no volume in the prior three months, so there is no baseline to compare against.${tail}`;
        case 'CHURNED':
            return `Churned — three straight silent months: no volume this month or in either of the two months before it.${tail}`;
        case 'DECLINING':
            if (!(Number(row.cur_month) > 0))
                return `Declining — no volume this month, against a three-month average of ${avgTxt}. Not yet churned: there was volume within the last two months.${tail}`;
            if (ratio != null && Number(ratio) < 30)
                return `Declining — this month's volume (${curTxt}) is ${ratioTxt} of the three-month average (${avgTxt}), below the 30% threshold.${tail}`;
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
    /* ── Loading is split by INTENT, not just visually moved ──
       `loading`   — a report request is in flight (any kind).
       `initialLoaded` — the first report has settled (success OR failure).
       Derived below: isInitialLoading drives the one-time page skeleton;
       isReportLoading (filter re-runs) keeps the header, filters, briefing
       and grid chrome mounted and shows progress INSIDE the grid instead
       of collapsing the page toward the top loader. Starts true so the
       first paint is a skeleton, not a "no rows" flash. */
    const [loading, setLoading] = useState(true);
    const [initialLoaded, setInitialLoaded] = useState(false);
    // Monotonic guard: a slow older response must never overwrite a newer
    // one (rapid filter switching), and its abort must not clear state.
    const fetchSeqRef = useRef(0);
    const fetchAbortRef = useRef(null);
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
    // Data caveats (empty comparison windows) collapse into one pill on the
    // briefing card; this anchors the popover with the full sentences.
    const [notesAnchor, setNotesAnchor] = useState(null);
    // Portfolio health band sizing: share of YTD value (the executive default)
    // or merchant count. A churned whale and a churned minnow must not look
    // the same width.
    const [bandBasis, setBandBasis] = useState('value');
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
        // Cancel any in-flight report request: rapid filter changes must not
        // let a slow older response land after (and overwrite) a newer one.
        fetchAbortRef.current?.abort();
        const controller = new AbortController();
        fetchAbortRef.current = controller;
        const seq = ++fetchSeqRef.current;
        setLoading(true);
        setError(null);
        try {
            // …-with-meta also returns the comparison-window flags, so an empty
            // prior-year window can be called out instead of silently rendering
            // as +100% growth for every merchant.
            const res = await api.post('/business/attrition-report-with-meta', body, { signal: controller.signal });
            if (seq !== fetchSeqRef.current) return; // superseded — newer request owns the screen
            const rows = Array.isArray(res.data) ? res.data : (res.data?.rows || []);
            setData(rows.map((r, i) => ({ id: r.mid ?? `row-${i}`, ...r })));
            setMeta(Array.isArray(res.data) ? null : (res.data?.meta || null));
        } catch (e) {
            // A cancelled request is not a failure — the newer request's states
            // are already correct, so touch nothing (especially not loading).
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            if (seq !== fetchSeqRef.current) return;
            // Previously console.error only — a 500/403 left an empty grid that
            // looked exactly like a portfolio with no merchants.
            console.error(e);
            setData([]);
            setMeta(null);
            setError(e?.response?.data?.error || e?.response?.statusText || e?.message || 'Could not load the attrition report.');
        }
        finally {
            if (seq === fetchSeqRef.current) {
                setLoading(false);
                setInitialLoaded(true); // settled either way — never stuck on the skeleton
            }
        }
    };

    const isInitialLoading = loading && !initialLoaded;
    const isReportLoading = loading && initialLoaded;

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

    // ── Month column labels ──
    // The grid's fixed money columns are the classifier months (current + two
    // prior — the same latest-data clock as Status) plus last year in full and
    // this year to date. Labels walk back from the classifier anchor, read off
    // the ISO string (never new Date(iso) — that shifts a day, and so a month,
    // in timezones behind UTC).
    const monthCols = useMemo(() => {
        const anchor = meta?.classifierAnchor ? String(meta.classifierAnchor) : '';
        const m = anchor ? Number(anchor.slice(5, 7)) : null;
        const y = anchor ? Number(anchor.slice(0, 4)) : null;
        const d = anchor ? Number(anchor.slice(8, 10)) : null;
        const name = (back) => (m ? MONTHS[((m - 1 - back) % 12 + 12) % 12] : ['2 Months Ago', 'Last Month', 'This Month'][2 - back]);
        // Date.UTC(y, m, 0) is CONSTRUCTED, not parsed — safe. Day count of the
        // anchor month, to label a partial month as "Aug 1–18" instead of "Aug".
        const daysIn = (m && y) ? new Date(Date.UTC(y, m, 0)).getUTCDate() : null;
        const cur = (m && d && daysIn && d < daysIn) ? `${name(0)} 1–${d}` : name(0);
        return { m2: name(2), m1: name(1), cur };
    }, [meta]);

    // Helpers that read the active-metric value off a row.
    const val = (row, base) => row[`${base}${suffix}`];
    // Export-side MEASURE value: converted when the executive USD display
    // toggle is on and the active metric is money; counts and the percent
    // columns (which keep using val directly) are never converted.
    const mval = (row, base) => {
        const v = val(row, base);
        return (v == null || kind !== 'currency') ? v : convertForDisplay(Number(v));
    };
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
        const c = { CHURNED: 0, DECLINING: 0, NON_STARTER: 0, STABLE: 0, PERFORMING: 0, NEW: 0 };
        data.forEach(d => { if (c[d.status] != null) c[d.status]++; });
        return c;
    }, [data]);

    // ── The briefing ──
    // Everything the executive layer needs, computed once from rows already
    // loaded: the verdict sentence, the money at risk (and its share of the
    // book), the adverse counts, the YTD trajectory, and the silent-risk call
    // list (healthy today, model says leaving). No extra requests.
    const briefing = useMemo(() => {
        if (!data.length) return null;
        const churned = statusCounts.CHURNED;
        const declining = statusCounts.DECLINING;
        const totalCur = data.reduce((s, d) => s + (Number(val(d, 'ytd_current')) || 0), 0);
        const totalPrev = data.reduce((s, d) => s + (Number(val(d, 'ytd_prev')) || 0), 0);
        // Match the backend's calculateGrowth: prev=0 & cur>0 → +100% ("new"),
        // both zero → 0%.
        const ytdChange = totalPrev > 0 ? ((totalCur - totalPrev) / totalPrev) * 100 : (totalCur > 0 ? 100 : 0);
        const ytdIsNew = totalPrev === 0 && totalCur > 0;
        const atRiskValue = data.reduce((s, d) =>
            (d.status === 'CHURNED' || d.status === 'DECLINING') ? s + (Number(val(d, 'ytd_current')) || 0) : s, 0);
        const atRiskShare = totalCur > 0 ? (atRiskValue / totalCur) * 100 : 0;

        // Headline leads with merchant counts, not a dollar figure — a $ total
        // pooled across thousands of merchants (many tiny) reads as an alarming
        // but meaningless number to an exec. The money still appears, sized
        // down, in the "at risk" tile below.
        const parts = [];
        if (churned > 0) {
            parts.push(`${churned} of ${data.length} merchants have churned`);
        }
        if (declining > 0) parts.push(`${declining} more ${declining === 1 ? 'is' : 'are'} declining`);
        if (!parts.length) parts.push(`No merchants churned or declining across the ${data.length}-merchant book`);

        return {
            headline: `${parts.join(' · ')}.`,
            tone: churned > 0 || declining > 0 ? 'bad' : 'good',
            atRiskValue, atRiskShare, churned, declining,
            ytdChange, ytdIsNew,
            // The good number: how much of the book is NOT at risk. Value share
            // is 100 − at-risk share by construction, so the two always sum.
            healthyCount: (statusCounts.STABLE || 0) + (statusCounts.PERFORMING || 0) + (statusCounts.NEW || 0),
            healthyValue: totalCur - atRiskValue,
            healthyShare: totalCur > 0 ? 100 - atRiskShare : 0,
        };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [data, rows, statusCounts, metric]);

    // YTD-% distribution buckets — hoisted so both the chart AND the click-to-
    // filter path share one definition (no drift between what a bar shows and
    // what clicking it selects).
    const BUCKETS = useMemo(() => ([
        { label: '≤ −50%', dir: -1, test: p => p <= -50, color: 'var(--attr-dist-1, #7A251C)' },
        { label: '−50 … −20%', dir: -1, test: p => p > -50 && p <= -20, color: 'var(--attr-dist-2, #B3382C)' },
        { label: '−20 … 0%', dir: -1, test: p => p > -20 && p < 0, color: 'var(--attr-dist-3, #8C5E12)' },
        { label: '0 … +20%', dir: 1, test: p => p >= 0 && p <= 20, color: 'var(--attr-dist-4, #79C4A8)' },
        { label: '> +20%', dir: 1, test: p => p > 20, color: 'var(--attr-dist-5, #0FA070)' },
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
    const STATUS_BARS = ['CHURNED', 'DECLINING', 'NON_STARTER', 'STABLE', 'PERFORMING', 'NEW'];
    const analytics = useMemo(() => {
        const total = data.length || 1;
        // Per-status YTD value, so the health band can be sized by money —
        // the executive default — as well as by merchant count.
        const valueByStatus = {};
        data.forEach(d => {
            valueByStatus[d.status] = (valueByStatus[d.status] || 0) + (Number(val(d, 'ytd_current')) || 0);
        });
        const totalValue = Object.values(valueByStatus).reduce((s, v) => s + v, 0) || 1;
        const breakdown = STATUS_BARS.map(s => ({
            key: s, ...STATUS_META[s], count: statusCounts[s] || 0,
            pct: ((statusCounts[s] || 0) / total) * 100,
            value: valueByStatus[s] || 0,
            pctValue: ((valueByStatus[s] || 0) / totalValue) * 100,
        }));
        const dist = BUCKETS.map((b, i) => ({
            label: b.label, color: b.color, dir: b.dir, index: i,
            count: data.filter(d => { const p = Number(val(d, 'ytd_pct')); return !isNaN(p) && val(d, 'ytd_pct') != null && b.test(p); }).length,
        }));
        return { breakdown, dist };
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [data, metric, statusCounts]);

    // MSF margin: revenue as a % of volume for one window. Reads the raw row
    // keys (not val()) because margin is metric-independent. Null when the
    // volume side is zero or the backend hasn't returned the window yet.
    const marginPct = (row, msfKey, volKey) => {
        const vol = Number(row?.[volKey]);
        const msf = Number(row?.[msfKey]);
        if (!vol || isNaN(vol) || vol <= 0 || row?.[msfKey] == null || isNaN(msf)) return null;
        return (msf / vol) * 100;
    };
    const marginCell = (params) => (
        <Typography variant="body2" sx={{ color: T.textSec, fontVariantNumeric: 'tabular-nums' }}>
            {params.value == null ? '—' : `${Number(params.value).toFixed(2)}%`}
        </Typography>
    );

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
            <Typography variant="body2" sx={{ fontWeight: 'bold', color: params.value < 0 ? 'var(--danger, #B3382C)' : params.value > 0 ? 'var(--success, #0FA070)' : T.textMut }}>
                {pctFormatter(params.value)}
            </Typography>
        );
    };
    // Status renders as dot + coloured text, not a pill: two pill columns side
    // by side turned every row into a chip carnival. It keeps its reasoning
    // tooltip — the thresholds live in backend code, so without it the label
    // is an unexplained verdict.
    const statusCell = (params) => {
        const m = STATUS_META[params.value] || { label: params.value, color: T.textSec, bg: T.subtle };
        const why = explainStatus(params.row, fmt.currency);
        const cell = (
            <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.9, cursor: why ? 'help' : 'default' }}>
                <Box sx={{ width: 8, height: 8, borderRadius: '50%', flexShrink: 0, bgcolor: m.color }} />
                <Typography variant="body2" sx={{ color: m.color, fontWeight: 700, fontSize: '12.5px' }}>{m.label}</Typography>
            </Box>
        );
        return why ? <Tooltip arrow title={why}>{cell}</Tooltip> : cell;
    };

    // Predicted churn-risk cell. Only HIGH earns a filled pill — it is the one
    // forward-looking alarm on the page; medium/low read as quiet text so the
    // alarm stays an alarm. Top driver + model source in the tooltip.
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
                {band === 'HIGH' ? (
                    <Chip label={`High${pctTxt ? ' · ' + pctTxt : ''}`} size="small"
                        sx={{ bgcolor: m.color, color: '#fff', fontWeight: 700, fontVariantNumeric: 'tabular-nums' }} />
                ) : (
                    <Typography variant="body2" sx={{ color: m.color, fontWeight: 600, fontSize: '12.5px', fontVariantNumeric: 'tabular-nums', cursor: 'help' }}>
                        {m.label}{pctTxt ? ` · ${pctTxt}` : ''}
                    </Typography>
                )}
            </Tooltip>
        );
    };

    const columns = useMemo(() => {
        // Flex columns so the grid fills its card on any monitor — fixed widths
        // left a white void to the right of the last column on wide screens.
        // MID rides under the merchant name as a mono second line, not a column.
        const base = [
            { field: 'merchant_info', headerName: 'MERCHANT', flex: 1.7, minWidth: 210,
                valueGetter: (v, row) => row.name,
                renderCell: (p) => (
                    <Box sx={{ minWidth: 0, lineHeight: 1.3 }}>
                        <Typography variant="body2" noWrap sx={{ fontWeight: 600, color: T.text }}>{p.row.name}</Typography>
                        <Typography variant="caption" noWrap sx={{ fontFamily: 'var(--font-mono)', color: T.textMut, display: 'block' }}>{p.row.mid}</Typography>
                    </Box>
                ) },
            // Status and Predicted Churn are two DIFFERENT questions that were
            // being read as one contradictory signal (a "Performing" merchant at
            // 80% churn risk looked like a bug rather than the most valuable row
            // on the page). The headers say which direction each one looks.
            { field: 'status', headerName: 'STATUS · SO FAR', flex: 1, minWidth: 130,
                description: 'Looking back: this month\'s volume measured against this merchant\'s own average of the prior three months. Hover any status for its numbers.',
                valueGetter: (v, row) => row.status, renderCell: statusCell },
        ];
        // Predicted churn-risk column, inserted right after Status — only when the
        // batch has produced scores for this tenant.
        if (churnAvailable) {
            base.push({
                field: 'churn_risk', headerName: 'PREDICTED CHURN', flex: 1, minWidth: 140, type: 'number',
                description: 'Looking ahead: model-scored likelihood of churn in the next 30–60 days. Independent of Status — a healthy merchant can carry a high score, and that is the useful case.',
                valueGetter: (v, row) => (row.churnProbability == null ? -1 : row.churnProbability),
                renderCell: churnCell,
            });
        }
        const monthDesc = 'Calendar months on the same latest-data clock as Status. While the current month is partial, the prior months are cut to the same day-of-month so the comparison stays apples-to-apples.';
        return [
            ...base,
            // ── Fixed money columns ──
            // The three classifier months (trajectory), then last year in full
            // against this year to date (the run-rate question). MoM and Period
            // YoY remain in the merchant panel and the CSV export.
            { field: 'col_m2', headerName: monthCols.m2, flex: 0.9, minWidth: 105, type: 'number',
                description: monthDesc,
                valueGetter: (v, row) => val(row, 'prev_m2'), renderCell: measureCell },
            { field: 'col_m1', headerName: monthCols.m1, flex: 0.9, minWidth: 105, type: 'number',
                description: monthDesc,
                valueGetter: (v, row) => val(row, 'prev_m1'), renderCell: measureCell },
            { field: 'col_cur', headerName: monthCols.cur, flex: 0.9, minWidth: 110, type: 'number',
                description: monthDesc,
                valueGetter: (v, row) => val(row, 'cur_month'), renderCell: measureCellBold },
            { field: 'col_pyfull', headerName: `${prevYear} Full`, flex: 1, minWidth: 115, type: 'number',
                description: `The whole of calendar ${prevYear} (January to December).`,
                valueGetter: (v, row) => val(row, 'py_full'), renderCell: measureCell },
            { field: 'col_ytd', headerName: `${selectedYear} YTD`, flex: 1, minWidth: 115, type: 'number',
                description: `January 1st ${selectedYear} to the end of the selected range.`,
                valueGetter: (v, row) => val(row, 'ytd_current'), renderCell: measureCellBold },
            { field: 'col_ytd_pct', headerName: 'YTD %', flex: 0.7, minWidth: 95, type: 'number',
                description: `${selectedYear} YTD against the SAME span of ${prevYear} (January 1st to the same end day) — not against the full year, so a part-year book is not misread as collapse.`,
                valueGetter: (v, row) => val(row, 'ytd_pct'), renderCell: pctCellFor('ytd_prev', 'ytd_current') },
            // ── MSF margin ── Always MSF ÷ volume regardless of the Measure
            // toggle (a margin of txns makes no sense). Null when the volume
            // side is zero/missing — rendered as an em dash, never 0%.
            { field: 'col_margin_py', headerName: `${prevYear}`, flex: 0.7, minWidth: 90, type: 'number',
                description: `MSF revenue as a % of volume across the whole of ${prevYear}.`,
                valueGetter: (v, row) => marginPct(row, 'py_full_msf', 'py_full'), renderCell: marginCell },
            { field: 'col_margin_ytd', headerName: `${selectedYear} YTD`, flex: 0.7, minWidth: 100, type: 'number',
                description: `MSF revenue as a % of volume, January 1st ${selectedYear} to the end of the selected range.`,
                valueGetter: (v, row) => marginPct(row, 'ytd_current_msf', 'ytd_current'), renderCell: marginCell },
        ];
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [metric, selectedYear, prevYear, churnAvailable, monthCols]);

    const columnGroupingModel = useMemo(() => ([
        { groupId: 'months_group', headerName: 'Recent Months', headerClassName: 'cmp-header-group',
            children: [{ field: 'col_m2' }, { field: 'col_m1' }, { field: 'col_cur' }] },
        { groupId: 'year_group', headerName: `${prevYear} Full vs ${selectedYear} YTD`, headerClassName: 'cmp-header-group',
            children: [{ field: 'col_pyfull' }, { field: 'col_ytd' }, { field: 'col_ytd_pct' }] },
        { groupId: 'margin_group', headerName: 'MSF Margin', headerClassName: 'cmp-header-group',
            children: [{ field: 'col_margin_py' }, { field: 'col_margin_ytd' }] },
    ]), [prevYear, selectedYear]);

    const panelSx = { p: 2.5, borderRadius: '14px', border: `1px solid ${T.border}`, bgcolor: T.card, height: '100%' };
    const panelTitle = (t) => (
        <Typography variant="caption" fontWeight={700} color={T.textMut} sx={{ textTransform: 'uppercase', letterSpacing: '0.05em', mb: 1.5, display: 'block' }}>{t}</Typography>
    );

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Attrition" subtitle="Who is leaving, what it costs, and who to call next"
                icon={Activity}
                onExport={() => {
                    // Filename reflects the active narrowing so exports are self-describing.
                    const parts = ['attrition_report'];
                    if (statusFilter !== 'ALL') parts.push(statusFilter.toLowerCase());
                    if (bucketFilter != null && BUCKETS[bucketFilter]) parts.push(`ytd_${BUCKETS[bucketFilter].label.replace(/[^\w-]+/g, '')}`);
                    // Column spec mirrors the on-screen grid: friendly names,
                    // active metric only, and the same MoM applicability rule.
                    // Money metric columns are labelled (USD) when the executive
                    // display toggle is converting their values.
                    const m = METRICS[metric].label
                        + (METRICS[metric].kind === 'currency' && isUsdDisplay() ? ' (USD)' : '');
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
                        // The grid's fixed columns: classifier months + full prior year.
                        { label: `${monthCols.m2} ${m}`, getter: r => mval(r, 'prev_m2') },
                        { label: `${monthCols.m1} ${m}`, getter: r => mval(r, 'prev_m1') },
                        { label: `${monthCols.cur} ${m}`, getter: r => mval(r, 'cur_month') },
                        { label: `${prevYear} Full ${m}`, getter: r => mval(r, 'py_full') },
                        { label: `${prevYear} Margin %`, getter: r => { const p = marginPct(r, 'py_full_msf', 'py_full'); return p == null ? '' : p.toFixed(2); } },
                        { label: `${selectedYear} YTD Margin %`, getter: r => { const p = marginPct(r, 'ytd_current_msf', 'ytd_current'); return p == null ? '' : p.toFixed(2); } },
                        ...(momApplicable ? [
                            { label: `Prev Month ${m}`, getter: r => mval(r, 'mom_prev') },
                            { label: `Current Month ${m}`, getter: r => mval(r, 'mom_current') },
                            { label: 'MoM % Change', getter: r => val(r, 'mom_pct') },
                        ] : []),
                        { label: `Period ${prevYear} ${m}`, getter: r => mval(r, 'mtd_prev') },
                        { label: `Period ${selectedYear} ${m}`, getter: r => mval(r, 'mtd_current') },
                        { label: 'Period YoY % Change', getter: r => val(r, 'mtd_pct') },
                        { label: `YTD ${prevYear} ${m}`, getter: r => mval(r, 'ytd_prev') },
                        { label: `YTD ${selectedYear} ${m}`, getter: r => mval(r, 'ytd_current') },
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
                            color: 'var(--brand, #3F63B0)',
                            bgcolor: 'var(--bg-card, #ffffff)',
                            border: '1px solid var(--border, #e2e8f0)',
                            '&:hover': { borderColor: 'var(--brand, #3F63B0)' },
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
                        fontSize: '0.78rem', fontWeight: 700, color: 'var(--brand, #3F63B0)',
                        bgcolor: 'var(--bg-card, #ffffff)', border: '1px solid var(--border, #e2e8f0)',
                    }}>Retry</Box>
                </Box>
            )}

            {/* ═══ ① THE BRIEFING — verdict, exposure, call list, caveats ═══
                One card answers the executive's three questions before anything
                else renders: what happened, what it costs, who to call. The old
                verdict strip, five equal KPI cards, dual-clock bar and empty-
                window banner all collapse into this single hierarchy. Both
                clocks remain permanently stated (footer line) and the window
                caveats remain one click away (data-notes pill) — nothing is
                lost, it just stops shouting over the answer. */}
            {/* First visit only: a briefing-shaped skeleton holds the slot so
                the layout below doesn't assemble in jumps. */}
            {isInitialLoading && <SkeletonLoader variant="chart" height={230} />}
            {briefing && !isInitialLoading && (
                <Paper className="dx-rise" sx={{
                    borderRadius: 'var(--radius-xl, 14px)', overflow: 'hidden',
                    border: `1px solid ${T.border}`, bgcolor: T.card,
                    boxShadow: 'var(--shadow-card, none)',
                    // Filter re-runs: the briefing stays mounted (no page-height
                    // collapse, no scroll jump) and dims until fresh data lands.
                    opacity: isReportLoading ? 0.55 : 1,
                    transition: 'opacity 200ms ease',
                    pointerEvents: isReportLoading ? 'none' : 'auto',
                }}>
                    {/* Tone is a hairline, not a shout: a thin gradient rule across the
                        top replaces the old 3px solid left border. */}
                    <Box aria-hidden sx={{
                        height: 3,
                        background: briefing.tone === 'bad'
                            ? 'linear-gradient(90deg, var(--negative, #B3382C), color-mix(in srgb, var(--negative, #B3382C) 12%, transparent) 70%)'
                            : 'linear-gradient(90deg, var(--success, #0FA070), color-mix(in srgb, var(--success, #0FA070) 12%, transparent) 70%)',
                    }} />
                    <Box sx={{ p: { xs: 2.5, md: 3.5 }, pt: { xs: 2.25, md: 3 } }}>
                        {/* Masthead row: verdict prose on the left, the annual-report
                            stat band on the right, separated by hairline column rules.
                            The band leads with the GOOD number — book health — so the
                            briefing reads as a balance sheet, not a casualty list. */}
                        <Box sx={{
                            display: 'flex', flexDirection: { xs: 'column', lg: 'row' },
                            gap: { xs: 2.5, lg: 5 }, alignItems: { lg: 'center' },
                        }}>
                            <Box sx={{ flex: { lg: '0 1 420px' }, minWidth: { lg: 300 } }}>
                                <Typography variant="caption" sx={{
                                    display: 'inline-flex', alignItems: 'center', gap: 0.75,
                                    color: T.textMut, fontWeight: 700, fontSize: '0.66rem',
                                    letterSpacing: '0.12em', textTransform: 'uppercase',
                                }}>
                                    <Box component="span" aria-hidden sx={{
                                        width: 7, height: 7, borderRadius: '50%', flexShrink: 0,
                                        bgcolor: briefing.tone === 'bad' ? 'var(--negative, #B3382C)' : 'var(--success, #0FA070)',
                                    }} />
                                    Portfolio briefing · {selectedYear}
                                </Typography>
                                <Typography sx={{
                                    fontSize: { xs: '1.15rem', md: '1.3rem' }, fontWeight: 650,
                                    color: T.text, lineHeight: 1.35, letterSpacing: '-0.015em', mt: 0.75,
                                }}>
                                    {briefing.headline}
                                </Typography>
                            </Box>

                            {/* Stat band — four columns, hairline-ruled like a report
                                KPI strip. Labels whisper, numerals speak. */}
                            <Box sx={{
                                flex: 1, display: 'grid', ml: { lg: 'auto' },
                                gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, minmax(0, 1fr))' },
                                rowGap: { xs: 2, md: 0 },
                                borderTop: { xs: `1px solid ${T.borderLt}`, lg: 'none' },
                                pt: { xs: 2, lg: 0 },
                            }}>
                                {[
                                    {
                                        label: 'Book health',
                                        value: `${briefing.healthyShare.toFixed(0)}%`,
                                        color: 'var(--success-text, #0B6B4D)',
                                        line1: `${briefing.healthyCount.toLocaleString()} healthy merchants`,
                                        line2: `${fmtMeasure(briefing.healthyValue)} ${METRICS[metric].label.toLowerCase()} retained`,
                                    },
                                    {
                                        label: 'At risk',
                                        value: (briefing.churned + briefing.declining).toLocaleString(),
                                        color: (briefing.churned + briefing.declining) > 0 ? 'var(--attr-atrisk, #B3382C)' : T.text,
                                        line1: `${briefing.churned.toLocaleString()} churned · ${briefing.declining.toLocaleString()} declining`,
                                        line2: `${fmtMeasure(briefing.atRiskValue)} · ${briefing.atRiskShare.toFixed(0)}% of the book`,
                                    },
                                    {
                                        label: 'Attrition rate',
                                        value: `${(data.length > 0 ? (briefing.churned / data.length) * 100 : 0).toFixed(1)}%`,
                                        color: briefing.churned > 0 ? 'var(--attr-atrisk, #B3382C)' : T.text,
                                        line1: `churned share of ${data.length.toLocaleString()} merchants`,
                                        line2: `${(data.length > 0 ? ((briefing.churned + briefing.declining) / data.length) * 100 : 0).toFixed(1)}% incl. declining`,
                                    },
                                    {
                                        label: 'YTD trend',
                                        value: briefing.ytdIsNew ? '—' : `${briefing.ytdChange < 0 ? '▼' : '▲'} ${Math.abs(briefing.ytdChange).toFixed(1)}%`,
                                        color: briefing.ytdIsNew ? T.textMut
                                            : briefing.ytdChange < 0 ? 'var(--danger-text, #B3382C)' : 'var(--success-text, #0B6B4D)',
                                        line1: briefing.ytdIsNew ? `no ${prevYear} baseline` : `book-wide, vs ${prevYear}`,
                                        line2: null,
                                    },
                                ].map((s, i) => (
                                    <Box key={s.label} sx={{
                                        px: { xs: i % 2 === 0 ? 0 : 2, md: 3 },
                                        '&:first-of-type': { pl: { md: 0 } },
                                        borderLeft: {
                                            xs: i % 2 === 1 ? `1px solid ${T.borderLt}` : 'none',
                                            md: i === 0 ? 'none' : `1px solid ${T.borderLt}`,
                                        },
                                    }}>
                                        <Typography variant="caption" sx={{
                                            color: T.textMut, fontWeight: 700, fontSize: '0.66rem',
                                            letterSpacing: '0.1em', textTransform: 'uppercase', display: 'block',
                                        }}>
                                            {s.label}
                                        </Typography>
                                        <Typography className="num" sx={{
                                            fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
                                            fontSize: { xs: '1.55rem', md: '1.85rem' }, fontWeight: 650,
                                            lineHeight: 1.2, mt: 0.5, color: s.color,
                                        }}>
                                            {s.value}
                                        </Typography>
                                        <Typography variant="caption" sx={{ color: T.textSec, display: 'block', mt: 0.25, lineHeight: 1.45 }}>
                                            {s.line1}
                                        </Typography>
                                        {s.line2 && (
                                            <Typography variant="caption" sx={{ color: T.textMut, display: 'block', lineHeight: 1.45, fontVariantNumeric: 'tabular-nums' }}>
                                                {s.line2}
                                            </Typography>
                                        )}
                                    </Box>
                                ))}
                            </Box>
                        </Box>

                    {/* Footer — the two clocks, permanently stated, plus the data
                        caveats one click away. The page runs on TWO windows and
                        conflating them was the top confusion; this line never leaves. */}
                    <Box sx={{
                        display: 'flex', alignItems: 'center', gap: { xs: 1, sm: 2 }, flexWrap: 'wrap',
                        mt: 2.5, pt: 1.5, borderTop: `1px solid ${T.borderLt}`,
                    }}>
                        {clocks && (
                            <>
                                <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
                                    <CalendarClock size={13} style={{ flexShrink: 0, color: 'var(--text-muted)' }} />
                                    <Typography variant="caption" sx={{ color: T.textMut }}>
                                        Range <Box component="span" sx={{ color: T.textSec, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{clocks.money}</Box>
                                    </Typography>
                                </Box>
                                {clocks.status && (
                                    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
                                        <Scale size={13} style={{ flexShrink: 0, color: 'var(--text-muted)' }} />
                                        <Typography variant="caption" sx={{ color: T.textMut }}>
                                            Status &amp; months <Box component="span" sx={{ color: T.textSec, fontWeight: 600 }}>{clocks.status}</Box>
                                        </Typography>
                                        <Tooltip arrow title="Status and the month columns run on calendar months anchored at the latest loaded data date, so they do not move with the selected date range. YTD runs January 1st to the end of the selected range.">
                                            <Box component="span" sx={{ display: 'inline-flex', color: T.textMut, cursor: 'help' }}>
                                                <Info size={12} />
                                            </Box>
                                        </Tooltip>
                                    </Box>
                                )}
                            </>
                        )}
                        {emptyWindows.length > 0 && (
                            <Box component="button" type="button"
                                onClick={(e) => setNotesAnchor(e.currentTarget)}
                                sx={{
                                    ml: { sm: 'auto' }, display: 'inline-flex', alignItems: 'center', gap: 0.5,
                                    px: 1, py: 0.4, borderRadius: 'var(--radius-pill, 999px)', cursor: 'pointer',
                                    border: '1px solid color-mix(in srgb, var(--attention, #8C5E12) 35%, transparent)',
                                    bgcolor: 'var(--warning-bg)', color: 'var(--warning-text, #8C5E12)',
                                    fontFamily: 'inherit', fontSize: '0.72rem', fontWeight: 700,
                                }}>
                                <AlertTriangle size={12} />
                                {emptyWindows.length} data note{emptyWindows.length > 1 ? 's' : ''}
                            </Box>
                        )}
                        <Popover
                            open={Boolean(notesAnchor)} anchorEl={notesAnchor}
                            onClose={() => setNotesAnchor(null)}
                            anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
                            transformOrigin={{ vertical: 'top', horizontal: 'right' }}
                            slotProps={{ paper: { sx: { p: 2, maxWidth: 440, borderRadius: 'var(--radius-lg, 12px)' } } }}
                        >
                            <Typography sx={{ fontSize: '0.8rem', fontWeight: 700, color: T.text, mb: 0.75 }}>
                                Comparison windows with no data
                            </Typography>
                            <Typography variant="caption" sx={{ color: T.textSec, display: 'block', lineHeight: 1.55 }}>
                                No data in the {emptyWindows.join(' or ')} comparison {emptyWindows.length > 1 ? 'windows' : 'window'} —
                                results measured against {emptyWindows.length > 1 ? 'them' : 'it'} (+100% growth, "New" statuses)
                                are artifacts of missing history, not real change.
                            </Typography>
                        </Popover>
                        </Box>
                    </Box>
                </Paper>
            )}

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
                                    color: 'var(--brand, #3F63B0)', fontSize: '0.72rem', fontWeight: 700,
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
                            <Typography variant="caption" sx={{ color: T.textMut, lineHeight: 1.5, display: 'block', mt: 1 }}>
                                {EXCLUSION_NOTE}
                            </Typography>
                        </Popover>
                        {/* Nothing previously told users the band was interactive, so its
                            best feature went unused. Show the affordance until it is. */}
                        {/* Sizing basis. Count-weighted composition lies to an
                            executive — a churned whale and minnow look the same —
                            so value share is the default. */}
                        <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.25, ml: 'auto' }}>
                            {[['value', 'By value'], ['count', 'By count']].map(([k, lbl]) => (
                                <Box key={k} component="button" type="button" onClick={() => setBandBasis(k)}
                                    sx={{
                                        px: 1, py: 0.35, border: 'none', cursor: 'pointer', fontFamily: 'inherit',
                                        borderRadius: 'var(--radius-pill, 999px)', fontSize: '0.72rem',
                                        fontWeight: bandBasis === k ? 700 : 500,
                                        color: bandBasis === k ? 'var(--brand, #3F63B0)' : T.textMut,
                                        bgcolor: bandBasis === k ? 'var(--wash, #DCE8F7)' : 'transparent',
                                    }}>
                                    {lbl}
                                </Box>
                            ))}
                        </Box>
                        {statusFilter !== 'ALL' ? (
                            <Typography variant="caption" onClick={() => setStatusFilter('ALL')}
                                sx={{ cursor: 'pointer', color: 'var(--brand, #3F63B0)', fontWeight: 700 }}>
                                Showing {STATUS_META[statusFilter]?.label} — clear ✕
                            </Typography>
                        ) : (
                            <Typography variant="caption" sx={{ color: T.textMut }}>
                                Click a segment to filter the table
                            </Typography>
                        )}
                    </Box>
                    <Box sx={{ display: 'flex', gap: '2px', height: 22, borderRadius: '6px', overflow: 'hidden', mb: 1.5, bgcolor: T.subtle }}>
                        {analytics.breakdown.map(s => {
                            const share = bandBasis === 'value' ? s.pctValue : s.pct;
                            if (!(s.count > 0) || share <= 0) return null;
                            return (
                                <Box key={s.key}
                                    title={`${s.label}: ${s.count} merchant${s.count === 1 ? '' : 's'} · ${fmtMeasure(s.value)} — click to filter`}
                                    onClick={() => setStatusFilter(prev => prev === s.key ? 'ALL' : s.key)}
                                    sx={{ width: `${share}%`,
                                        // Only the adverse statuses keep full colour; healthy
                                        // segments are tints so risk share is what pops.
                                        background: s.hard
                                            ? `linear-gradient(180deg,
                                                color-mix(in srgb, ${s.color} 88%, #fff) 0%,
                                                ${s.color} 45%,
                                                color-mix(in srgb, ${s.color} 70%, var(--bg-card)) 100%)`
                                            : `color-mix(in srgb, ${s.color} 30%, var(--bg-card))`,
                                        transition: 'width .5s ease, opacity .15s', cursor: 'pointer',
                                        opacity: statusFilter === 'ALL' || statusFilter === s.key ? 1 : 0.3,
                                        '&:hover': { opacity: 1 } }} />
                            );
                        })}
                    </Box>
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', columnGap: 3, rowGap: 0.75 }}>
                        {analytics.breakdown.map(s => {
                            const active = statusFilter === s.key;
                            const share = bandBasis === 'value' ? s.pctValue : s.pct;
                            return (
                                <Box key={s.key}
                                    onClick={() => setStatusFilter(active ? 'ALL' : s.key)}
                                    sx={{ display: 'inline-flex', alignItems: 'center', gap: 1, cursor: 'pointer',
                                        px: 1, py: 0.5, borderRadius: '8px',
                                        border: `1px solid ${active ? s.color : 'transparent'}`,
                                        bgcolor: active ? s.bg : 'transparent',
                                        '&:hover': { bgcolor: s.bg } }}>
                                    <Box sx={{ width: 10, height: 10, borderRadius: '3px',
                                        background: s.hard ? s.color : `color-mix(in srgb, ${s.color} 38%, var(--bg-card))` }} />
                                    <Typography variant="body2" color={active ? s.color : T.textSec} fontWeight={active ? 700 : 500}>{s.label}</Typography>
                                    <Typography variant="body2" fontWeight={700} color={T.textStr} sx={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums' }}>{s.count.toLocaleString()}</Typography>
                                    <Typography variant="caption" color={T.textMut} sx={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums' }}>{share.toFixed(1)}%</Typography>
                                </Box>
                            );
                        })}
                    </Box>
                </Paper>
            )}

            {/* ═══ ② EVIDENCE — where the book moved ═══ */}
            {data.length > 0 && (
                <Box sx={{ display: 'grid', gap: 2, mb: 2, gridTemplateColumns: '1fr' }}>
                    {/* Movement — a diverging strip instead of a histogram: bucket
                        rows around a zero line stay legible whether the book has
                        15 merchants or 4,000. Click a row to filter the table. */}
                    <Paper sx={panelSx}>
                        <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
                            {panelTitle(`Movement · YTD ${METRICS[metric].label} %`)}
                            {bucketFilter != null && (
                                <Typography variant="caption" onClick={() => setBucketFilter(null)}
                                    sx={{ cursor: 'pointer', color: 'var(--brand, #3F63B0)', fontWeight: 700 }}>
                                    Clear ✕
                                </Typography>
                            )}
                        </Box>
                        {(() => {
                            const maxCount = Math.max(...analytics.dist.map(d => d.count), 1);
                            return (
                                <Stack spacing={0.5} sx={{ mt: 0.5 }}>
                                    {analytics.dist.map((d) => {
                                        const active = bucketFilter == null || bucketFilter === d.index;
                                        const half = (d.count / maxCount) * 50;
                                        return (
                                            <Box key={d.label}
                                                onClick={() => setBucketFilter(prev => prev === d.index ? null : d.index)}
                                                title={`${d.count} merchant${d.count === 1 ? '' : 's'} — click to filter`}
                                                sx={{
                                                    display: 'grid', gridTemplateColumns: '92px 1fr 34px', alignItems: 'center',
                                                    gap: 1.25, px: 0.5, py: 0.4, borderRadius: '6px', cursor: 'pointer',
                                                    opacity: active ? 1 : 0.35, transition: 'opacity .15s',
                                                    '&:hover': { opacity: 1, bgcolor: T.hover },
                                                }}>
                                                <Typography variant="caption" sx={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', color: T.textSec, whiteSpace: 'nowrap' }}>
                                                    {d.label}
                                                </Typography>
                                                <Box sx={{ position: 'relative', height: 16 }}>
                                                    {/* zero line */}
                                                    <Box sx={{ position: 'absolute', left: '50%', top: 0, bottom: 0, width: '1px', bgcolor: T.border }} />
                                                    {d.count > 0 && (
                                                        <Box sx={{
                                                            position: 'absolute', top: 2, bottom: 2,
                                                            ...(d.dir < 0
                                                                ? { right: '50%', width: `${half}%`, borderRadius: '4px 0 0 4px' }
                                                                : { left: '50%', width: `${half}%`, borderRadius: '0 4px 4px 0' }),
                                                            bgcolor: d.color, minWidth: 3,
                                                            transition: 'width .4s ease',
                                                        }} />
                                                    )}
                                                </Box>
                                                <Typography variant="caption" sx={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums', color: T.textStr, fontWeight: 600, textAlign: 'right' }}>
                                                    {d.count}
                                                </Typography>
                                            </Box>
                                        );
                                    })}
                                    <Typography variant="caption" sx={{ color: T.textMut, pt: 0.5 }}>
                                        Merchants by YTD change vs {prevYear} · shrinking left, growing right
                                    </Typography>
                                </Stack>
                            );
                        })()}
                    </Paper>
                </Box>
            )}

            {/* ═══ ③ WORKBENCH — the table, with its tools attached to it ═══
                The Measure toggle is the grid's OWN control, so it lives on the
                grid card as a toolbar rather than floating mid-page dressed as
                content. Chips and the row count sit on the same line. */}
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
                <Box sx={{
                    display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.5,
                    px: 1.75, py: 1.25, borderBottom: `1px solid ${T.border}`, bgcolor: T.card,
                }}>
                    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1 }}>
                        <Typography variant="caption" sx={{ color: T.textMut, fontWeight: 600 }}>Measure</Typography>
                        <ToggleButtonGroup size="small" exclusive value={metric}
                            onChange={(e, v) => v && setMetric(v)} aria-label="metric">
                            {Object.entries(METRICS).map(([k, m]) => (
                                <ToggleButton key={k} value={k}
                                    disabled={k === 'spread' && meta?.spreadAvailable === false}
                                    title={k === 'spread' && meta?.spreadAvailable === false
                                        ? 'Net spread is not available with card, channel, destination or store filters (DCC and rental are booked per merchant, not per card).'
                                        : undefined}
                                    sx={{ textTransform: 'none', fontWeight: 600, py: 0.4, px: 1.25 }}>{m.label}</ToggleButton>
                            ))}
                        </ToggleButtonGroup>
                    </Box>
                    {/* Every active narrowing — in-page (status band, movement
                        bucket) and the drawer's server-side ones — plus the count. */}
                    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center" sx={{ ml: 'auto' }}>
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
                        <Typography variant="caption" color={T.textMut} sx={{ fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums' }}>
                            {filteredData.length.toLocaleString()} of {data.length.toLocaleString()}
                        </Typography>
                    </Stack>
                </Box>
                <DataGrid
                    rows={filteredData} columns={columns} columnGroupingModel={columnGroupingModel}
                    loading={loading} disableRowSelectionOnClick rowHeight={52}
                    // Loading lives INSIDE the table: headers stay visible and
                    // the body renders skeleton rows matched to the real column
                    // widths — not a page-top spinner disconnected from the
                    // content that is actually changing.
                    slotProps={{ loadingOverlay: { variant: 'skeleton', noRowsVariant: 'skeleton' } }}
                    onRowClick={(params) => setDetailRow(params.row)}
                    initialState={{
                        pagination: { paginationModel: { pageSize: 25 } },
                        // Worst YTD change first.
                        sorting: { sortModel: [{ field: 'col_ytd_pct', sort: 'asc' }] },
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
                                                            color: isNaN(pctNum) || pct == null ? T.textMut : pctNum < 0 ? 'var(--danger, #B3382C)' : pctNum > 0 ? 'var(--success, #0FA070)' : T.textMut,
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
