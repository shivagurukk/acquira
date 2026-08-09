import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Box, Paper, Typography, Stack, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { DollarSign, Users, Hash, Percent, TrendingUp } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';
import { formatMsf } from '../../utils/formatters';
import { useDataBounds } from '../../hooks/useDataBounds';

/* ── Date Preset Resolver ──────────────────────────────────────── */
const computeDateRange = (preset) => {
    const now = new Date();
    // Local-date formatter — toISOString() shifts dates by one day in non-UTC timezones.
    // See PremiumReportHeader.jsx for the full bug explanation.
    const fmt = (d) => {
        const yr = d.getFullYear();
        const mo = String(d.getMonth() + 1).padStart(2, '0');
        const dy = String(d.getDate()).padStart(2, '0');
        return `${yr}-${mo}-${dy}`;
    };
    switch (preset) {
        case 'TODAY':      return { startDate: fmt(now), endDate: fmt(now) };
        case 'MONTH':      return { startDate: fmt(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmt(now) };
        case 'LAST_MONTH': return { startDate: fmt(new Date(now.getFullYear(), now.getMonth() - 1, 1)), endDate: fmt(new Date(now.getFullYear(), now.getMonth(), 0)) };
        case 'YEAR':       return { startDate: fmt(new Date(now.getFullYear(), 0, 1)), endDate: fmt(now) };
        case 'PY':         return { startDate: fmt(new Date(now.getFullYear() - 1, 0, 1)), endDate: fmt(new Date(now.getFullYear() - 1, 11, 31)) };
        default:           return {};
    }
};

const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

/* ── Design tokens ────────────────────────────────────────────────
   One accent (brand blue) + financial semantics (success/warning/danger for
   MSF-rate banding). Everything else is ink on card. Colour never decorates —
   it always encodes magnitude (blue ramp), rank (medals) or rate health. */
const C = {
    text:    'var(--text, #111827)',
    textSec: 'var(--text-secondary, #6b7280)',
    textMut: 'var(--text-muted, #9ca3af)',
    border:  'var(--border, #e5e7eb)',
    borderLt:'var(--border-light, #f3f4f6)',
    subtle:  'var(--bg-subtle, #f3f4f6)',
    card:    'var(--bg-card, #fff)',
    brand:   'var(--brand, #2563eb)',
    success: 'var(--success, #059669)',
    warning: 'var(--warning, #d97706)',
    danger:  'var(--danger, #dc2626)',
};

// Sequential blue ramp for the concentration band — darkest = largest share
// (magnitude, one hue). The tail of the portfolio is neutral slate: it is the
// "everyone else" mass, not a sixth competitor.
const RAMP = ['#1e40af', '#2563eb', '#3b82f6', '#60a5fa', '#93c5fd'];
const RAMP_REST = 'var(--border, #cbd5e1)';

const MEDAL_COLORS = ['#b48b0b', '#8c96a3', '#b0704a'];

// MSF rate banding — same good/ok/low thresholds used across business reports.
const rateColor = (pct) => pct >= 2 ? C.success : pct >= 1 ? C.warning : C.danger;

/* ── Section eyebrow ─────────────────────────────────────────── */
const Eyebrow = ({ children }) => (
    <Typography component="div" sx={{
        fontSize: '0.68rem', fontWeight: 700, letterSpacing: '0.08em',
        textTransform: 'uppercase', color: C.textMut, mb: 1.25,
    }}>{children}</Typography>
);

/* ── Portfolio concentration band ─────────────────────────────────
   The page's signature: one full-width stacked strip answering the first
   question a portfolio owner has — "how dependent am I on my biggest
   merchants?" — with the top-5 share as a headline sentence. Clickable
   segments filter nothing (it is a reading, not a control); hover reveals
   exact values. 2px surface gaps keep segments legible without borders. */
const ConcentrationBand = ({ data, formatCurrency }) => {
    const { top, rest, total, topShare } = useMemo(() => {
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const sorted = data.slice().sort((a, b) => (b.volume || 0) - (a.volume || 0));
        const top5 = sorted.slice(0, 5).map((d, i) => ({
            name: d.merchantName || d.mid || '—',
            volume: d.volume || 0,
            msf: d.msf || 0,
            share: totalVol > 0 ? (d.volume || 0) / totalVol * 100 : 0,
            color: RAMP[i],
        }));
        const restVol = totalVol - top5.reduce((s, d) => s + d.volume, 0);
        return {
            top: top5,
            rest: { volume: restVol, share: totalVol > 0 ? restVol / totalVol * 100 : 0, count: Math.max(0, data.length - 5) },
            total: totalVol,
            topShare: top5.reduce((s, d) => s + d.share, 0),
        };
    }, [data]);

    if (!data.length || total <= 0) return null;

    const segments = [...top.map(t => ({ ...t, isRest: false })),
        ...(rest.volume > 0 ? [{ name: `${rest.count} other merchants`, volume: rest.volume, share: rest.share, color: RAMP_REST, isRest: true }] : [])];

    return (
        <Paper sx={{ ...premiumTableWrapper, flex: 'none', p: { xs: 2, md: 3 } }}>
            <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between"
                alignItems={{ xs: 'flex-start', md: 'baseline' }} spacing={1} sx={{ mb: 2 }}>
                <Box>
                    <Eyebrow>Portfolio concentration</Eyebrow>
                    <Typography sx={{ fontSize: '1.05rem', fontWeight: 600, color: C.text, letterSpacing: '-0.01em' }}>
                        Top 5 merchants carry{' '}
                        <Box component="span" sx={{ fontWeight: 800, fontVariantNumeric: 'tabular-nums' }}>
                            {topShare.toFixed(1)}%
                        </Box>{' '}
                        of volume
                    </Typography>
                </Box>
                <Typography sx={{ fontSize: '0.78rem', color: C.textMut, fontVariantNumeric: 'tabular-nums' }}>
                    {formatNumber(data.length)} merchants · {formatCurrency(total)} total
                </Typography>
            </Stack>

            {/* The strip. Gaps are drawn by the surface showing through. */}
            <Box sx={{ display: 'flex', gap: '2px', height: 22, borderRadius: '6px', overflow: 'hidden', mb: 1.5 }}>
                {segments.map((s, i) => (
                    <Box key={i} title={`${s.name} — ${formatCurrency(s.volume)} (${s.share.toFixed(1)}%)`}
                        sx={{
                            width: `${Math.max(s.share, 0.5)}%`, bgcolor: s.color,
                            transition: 'opacity .15s', '&:hover': { opacity: 0.8 },
                        }} />
                ))}
            </Box>

            {/* Legend: identity chip + value + share, one quiet row per segment. */}
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: 'repeat(3, 1fr)' }, columnGap: 3, rowGap: 0.5 }}>
                {segments.map((s, i) => (
                    <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0, py: 0.25 }}>
                        <Box sx={{ width: 10, height: 10, borderRadius: '3px', bgcolor: s.color, flexShrink: 0 }} />
                        <Typography noWrap sx={{ fontSize: '0.8rem', fontWeight: s.isRest ? 400 : 600, color: s.isRest ? C.textMut : C.textSec, flex: 1, minWidth: 0 }}>
                            {s.name}
                        </Typography>
                        <Typography sx={{ fontSize: '0.78rem', color: C.textSec, fontVariantNumeric: 'tabular-nums' }}>
                            {formatCompact(s.volume)}
                        </Typography>
                        <Typography sx={{ fontSize: '0.78rem', fontWeight: 700, color: C.text, fontVariantNumeric: 'tabular-nums', width: 48, textAlign: 'right' }}>
                            {s.share.toFixed(1)}%
                        </Typography>
                    </Box>
                ))}
            </Box>
        </Paper>
    );
};

const MerchantFinancialSummary = () => {
    const { currencyCode = 'AED', formatCurrency: fmtCurr, tenantVersion } = useAuth() || {};
    const formatCurrency = useCallback((val) => {
        if (fmtCurr) return fmtCurr(val);
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: currencyCode, minimumFractionDigits: 2 }).format(val || 0);
    }, [fmtCurr, currencyCode]);

    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState(() => {
        // Empty defaults; useDataBounds populates these with the full data window.
        return { datePreset: 'MONTH', startDate: '', endDate: '' };
    });

    /* ── Default date range ─────────────────────────────────────── */
    // Shared useDataBounds hook: resolves the FULL data window (earliest ->
    // latest) from /api/business/data-bounds, with a wide fallback.
    const { startDate: boundsStart, endDate: boundsEnd, boundsLoaded } = useDataBounds(tenantVersion);

    // Latest filters, readable from the effect below without making it depend on
    // `filters` (which would re-run the report on every drawer change).
    const filtersRef = useRef(filters);
    filtersRef.current = filters;

    // Push the resolved window into filter state AND run the first report with
    // it, from one object — see the stale-closure history in git for why these
    // must not be two separate effects.
    useEffect(() => {
        if (!boundsLoaded) return;
        const next = {
            ...filtersRef.current,
            datePreset: 'CUSTOM',
            startDate: boundsStart,
            endDate:   boundsEnd,
        };
        setFilters(next);
        fetchReport(next);
    }, [boundsLoaded, boundsStart, boundsEnd, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

    // fetchReport(override): the header's date-preset chips pass the fully
    // resolved next-filters object (see PremiumReportHeader P1-4 v2), avoiding
    // the stale-closure race between chip state and request body.
    const fetchReport = async (override) => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const source = (override && override.startDate !== undefined) ? override : filters;
            const body = { ...source };
            // A non-CUSTOM preset must ALWAYS win when the user picks one.
            if (body.datePreset && body.datePreset !== 'CUSTOM') {
                const range = computeDateRange(body.datePreset);
                if (range.startDate && range.endDate) {
                    body.startDate = range.startDate;
                    body.endDate = range.endDate;
                }
            }
            delete body.datePreset;
            const res = await fetch('/api/business/merchant-financial-summary', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) },
                body: JSON.stringify(body)
            });
            if (res.ok) setData(await res.json());
        } catch (error) { console.error("Failed to load report", error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const rows = useMemo(() => {
        if (!data.length) return [];
        const maxVol = Math.max(...data.map(d => d.volume || 0));
        const sortedByVol = data.slice().sort((a, b) => (b.volume || 0) - (a.volume || 0));
        const rankOf = new Map(sortedByVol.map((d, i) => [d, i + 1]));
        // Compose mid+sid+index so every row is unique (same mid can span stores).
        return data.map((d, i) => ({ id: `${d.mid || ''}-${d.sid || ''}-${i}`, ...d, maxVol, rank: rankOf.get(d) }));
    }, [data]);

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalMsf = data.reduce((s, d) => s + (d.msf || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        const topVols = data.slice().sort((a, b) => (b.volume || 0) - (a.volume || 0)).slice(0, 10).map(d => d.volume || 0);
        const avgRate = totalVol > 0 ? (totalMsf / totalVol) * 100 : 0;
        // Four tiles, one accent: volume leads (brand + sparkline), revenue is
        // the money outcome (success), rate and counts stay neutral ink.
        return [
            { title: 'Total Volume', value: `${currencyCode} ${formatCompact(totalVol)}`, icon: TrendingUp, color: C.brand, sparkData: topVols, trendLabel: 'Top 10 merchants' },
            { title: 'MSF Revenue', value: `${currencyCode} ${formatCompact(totalMsf)}`, icon: DollarSign, color: C.success },
            { title: 'Avg MSF Rate', value: `${avgRate.toFixed(2)}%`, icon: Percent, color: rateColor(avgRate) },
            { title: 'Merchants', value: formatNumber(data.length), subtitle: `${formatCompact(totalCount)} transactions`, icon: Users, color: 'var(--text-secondary, #6b7280)' },
        ];
    }, [data, currencyCode]); // eslint-disable-line react-hooks/exhaustive-deps

    const columns = [
        {
            field: 'rank', headerName: 'RANK', width: 64, sortable: false, align: 'center', headerAlign: 'center',
            renderCell: (params) => {
                const r = params.value;
                if (r <= 3) {
                    return (
                        <Box sx={{
                            width: 26, height: 26, borderRadius: '8px',
                            bgcolor: `color-mix(in srgb, ${MEDAL_COLORS[r - 1]} 14%, transparent)`,
                            color: MEDAL_COLORS[r - 1],
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            fontWeight: 800, fontSize: '0.76rem',
                        }}>{r}</Box>
                    );
                }
                return <Typography variant="body2" sx={{ color: C.textMut, fontWeight: 500, fontVariantNumeric: 'tabular-nums' }}>{r}</Typography>;
            }
        },
        {
            field: 'merchantName', headerName: 'MERCHANT', flex: 1.6, minWidth: 230,
            renderCell: (params) => (
                <Box sx={{ minWidth: 0 }}>
                    <Typography variant="body2" noWrap sx={{ fontWeight: 600, color: C.text, lineHeight: 1.3 }}>
                        {params.value || '—'}
                    </Typography>
                    <Typography noWrap sx={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.7rem', color: C.textMut, lineHeight: 1.4 }}>
                        {params.row.mid}{params.row.sid ? ` · ${params.row.sid}` : ''}
                    </Typography>
                </Box>
            )
        },
        {
            field: 'count', headerName: 'TXNS', type: 'number', flex: 0.7, minWidth: 90, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" sx={{ color: C.textSec, fontVariantNumeric: 'tabular-nums' }}>
                    {formatNumber(params.value)}
                </Typography>
            )
        },
        {
            field: 'volume', headerName: `VOLUME (${currencyCode})`, flex: 1.5, minWidth: 170, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center' }}>
                    <Typography variant="body2" sx={{ fontWeight: 700, color: C.text, fontVariantNumeric: 'tabular-nums' }}>
                        {formatCurrency(params.value)}
                    </Typography>
                    <Box sx={{ width: '76%', height: 3, bgcolor: C.subtle, borderRadius: 2, mt: 0.5, overflow: 'hidden' }}>
                        <Box sx={{ width: `${(params.value / params.row.maxVol) * 100}%`, height: '100%', bgcolor: C.brand, borderRadius: 2 }} />
                    </Box>
                </Box>
            )
        },
        {
            field: 'msf', headerName: `MSF (${currencyCode})`, flex: 1.1, minWidth: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontWeight: 600, color: C.textSec, fontVariantNumeric: 'tabular-nums' }}>
                    {formatMsf(params.value)}
                </Typography>
            )
        },
        {
            field: 'msfRate', headerName: 'MSF RATE', flex: 0.8, minWidth: 96, align: 'right', headerAlign: 'right', sortable: false,
            renderCell: (params) => {
                const pct = params.row.volume > 0 ? (params.row.msf / params.row.volume) * 100 : 0;
                return (
                    <Chip label={`${pct.toFixed(2)}%`} size="small" sx={{
                        height: 22, fontSize: '0.72rem',
                        fontWeight: 700, fontVariantNumeric: 'tabular-nums',
                        bgcolor: `color-mix(in srgb, ${rateColor(pct)} 10%, transparent)`,
                        color: rateColor(pct), border: 'none',
                    }} />
                );
            }
        },
        {
            field: 'opt_in_volume', headerName: `OPT-IN (${currencyCode})`, flex: 1, minWidth: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" sx={{ color: C.textMut, fontVariantNumeric: 'tabular-nums' }}>
                    {formatCurrency(params.value)}
                </Typography>
            )
        }
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Financial Summary" subtitle="Volume, MSF revenue and pricing health per merchant"
                icon={DollarSign}
                onExport={() => exportToCSV(data, 'merchant_financial_summary')}
                onRunReport={() => fetchReport()} onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={(next) => fetchReport(next)}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={() => fetchReport()} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} loading={loading && !data.length} />

            <ConcentrationBand data={data} formatCurrency={formatCurrency} />

            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={rows} columns={columns} loading={loading} rowHeight={56}
                    disableRowSelectionOnClick slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    initialState={{ sorting: { sortModel: [{ field: 'volume', sort: 'desc' }] } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default MerchantFinancialSummary;
