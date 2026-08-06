import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Stack, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import { DollarSign, Store, CreditCard, Hash, Users, TrendingUp, BarChart3, Percent } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer, chartTooltipStyle } from '../../theme/dataGridStyles';
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

// ── Local design tokens — every colour routes through a CSS var + fallback. ──
const C = {
    text:    'var(--text, #0f172a)',
    textStr: 'var(--text, #1e293b)',
    textSec: 'var(--text-secondary, #475569)',
    textMut: 'var(--text-muted, #64748b)',
    icon:    'var(--text-muted, #94a3b8)',
    border:  'var(--border, #e2e8f0)',
    subtle:  'var(--bg-subtle, #f8fafc)',
    track:   'var(--bg-subtle, #f1f5f9)',
    bar:     'var(--accent-indigo, #6366f1)',
    card:    'var(--bg-card, #fff)',
    success: 'var(--success, #10b981)',
    warning: 'var(--warning, #f59e0b)',
    danger:  'var(--danger, #ef4444)',
    brandAlt:'var(--brand-alt, #3b82f6)',
};

const MEDAL_COLORS = ['#eab308', '#94a3b8', '#c2703d'];
const AVATAR_PALETTE = ['#6366f1', '#3b82f6', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6', '#06b6d4', '#f97316'];
const avatarColorFor = (name) => {
    const s = String(name || '?');
    let hash = 0;
    for (let i = 0; i < s.length; i++) hash = (hash * 31 + s.charCodeAt(i)) >>> 0;
    return AVATAR_PALETTE[hash % AVATAR_PALETTE.length];
};

// MSF rate colour thresholds — same "good/ok/low" banding used across business reports.
const rateColor = (pct) => pct >= 2 ? C.success : pct >= 1 ? C.warning : C.danger;

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
    // latest) from /api/business/data-bounds, with a wide fallback. One
    // implementation shared across every business report page.
    const { startDate: boundsStart, endDate: boundsEnd, boundsLoaded } = useDataBounds(tenantVersion);

    // Push the resolved window into filter state once it arrives. CUSTOM
    // because we're supplying an explicit range, not a preset.
    useEffect(() => {
        if (!boundsLoaded) return;
        setFilters(prev => ({
            ...prev,
            datePreset: 'CUSTOM',
            startDate: boundsStart,
            endDate:   boundsEnd,
        }));
    }, [boundsLoaded, boundsStart, boundsEnd]);

    useEffect(() => {
        if (boundsLoaded) fetchReport();
    }, [boundsLoaded]); // eslint-disable-line react-hooks/exhaustive-deps

    // fetchReport(override): the header's date-preset chips pass the fully
    // resolved next-filters object (see PremiumReportHeader P1-4 v2). Posting
    // that object directly avoids the stale-closure race where the request
    // body was still the previous (data-bounds full-window) filters while the
    // chips already showed the new preset — which made this page report
    // all-history totals under a "This year" chip.
    const fetchReport = async (override) => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const source = (override && override.startDate !== undefined) ? override : filters;
            const body = { ...source };
            // A non-CUSTOM preset must ALWAYS win when the user picks one. The old
            // `(!startDate || !endDate)` guard meant the preset was ignored once
            // the dates were pre-filled. CUSTOM => use explicit dates as-is.
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
        // P0-2 FIX: previously used `d.mid || d.sid || i` which collided when one
        // merchant had multiple stores (same mid, different sid). MUI DataGrid
        // de-duplicates on `id` so the second store row silently disappeared.
        // Compose mid+sid+index so every row is unique.
        return data.map((d, i) => ({ id: `${d.mid || ''}-${d.sid || ''}-${i}`, ...d, maxVol, rank: rankOf.get(d) }));
    }, [data]);

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalMsf = data.reduce((s, d) => s + (d.msf || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        const topVols = data.slice().sort((a, b) => (b.volume || 0) - (a.volume || 0)).slice(0, 10).map(d => d.volume || 0);
        const avgRate = totalVol > 0 ? (totalMsf / totalVol) * 100 : 0;
        return [
            { title: 'Total Merchants', value: formatNumber(data.length), icon: Users, color: 'var(--accent-indigo, #6366f1)' },
            { title: 'Total Volume', value: `${currencyCode} ${formatCompact(totalVol)}`, icon: TrendingUp, color: 'var(--brand-alt, #3b82f6)', sparkData: topVols },
            { title: 'Total MSF', value: `${currencyCode} ${formatCompact(totalMsf)}`, icon: DollarSign, color: 'var(--success, #10b981)' },
            { title: 'Total Transactions', value: formatCompact(totalCount), icon: Hash, color: 'var(--warning, #f59e0b)' },
            { title: 'Avg MSF Rate', value: `${avgRate.toFixed(2)}%`, icon: Percent, color: 'var(--accent-pink, #ec4899)' },
        ];
    }, [data]);

    // Top 10 merchants by volume, for the distribution chart — same slice the
    // KPI sparkline uses, kept in sync so the chart and card agree.
    const topMerchants = useMemo(() => {
        return data.slice().sort((a, b) => (b.volume || 0) - (a.volume || 0)).slice(0, 10)
            .map(d => ({ ...d, shortName: (d.merchantName || '').length > 18 ? `${d.merchantName.slice(0, 18)}…` : d.merchantName }));
    }, [data]);

    const columns = [
        {
            field: 'rank', headerName: 'RANK', width: 70, sortable: false, align: 'center', headerAlign: 'center',
            renderCell: (params) => {
                const r = params.value;
                if (r <= 3) {
                    return (
                        <Box sx={{
                            width: 28, height: 28, borderRadius: '50%',
                            bgcolor: `${MEDAL_COLORS[r - 1]}22`, color: MEDAL_COLORS[r - 1],
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            fontWeight: 800, fontSize: '0.78rem',
                        }}>{r}</Box>
                    );
                }
                return <Typography variant="body2" color={C.textMut} fontWeight={600}>{r}</Typography>;
            }
        },
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', flex: 1.5, minWidth: 220,
            renderCell: (params) => (
                <Stack direction="row" spacing={1.25} alignItems="center">
                    <Box sx={{
                        width: 30, height: 30, borderRadius: '50%', flexShrink: 0,
                        bgcolor: `${avatarColorFor(params.value)}1f`, color: avatarColorFor(params.value),
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        fontWeight: 700, fontSize: '0.78rem',
                    }}>
                        {String(params.value || '?').charAt(0).toUpperCase()}
                    </Box>
                    <Typography variant="body2" fontWeight="700" color={C.textStr}>{params.value}</Typography>
                </Stack>
            )
        },
        {
            field: 'mid', headerName: 'MID', flex: 1, minWidth: 140,
            renderCell: (params) => (
                <Stack direction="row" spacing={1} alignItems="center">
                    <CreditCard size={14} color="var(--text-muted, #94a3b8)" />
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600, color: C.textSec }}>{params.value}</Typography>
                </Stack>
            )
        },
        {
            field: 'sid', headerName: 'SID', flex: 0.8, minWidth: 100,
            renderCell: (params) => (
                <Stack direction="row" spacing={1} alignItems="center">
                    <Store size={14} color="var(--text-muted, #94a3b8)" />
                    <Typography variant="body2" sx={{ fontFamily: 'monospace', color: C.textMut }}>{params.value}</Typography>
                </Stack>
            )
        },
        {
            field: 'count', headerName: 'COUNT', type: 'number', flex: 0.8, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Chip label={formatNumber(params.value)} size="small" variant="outlined" sx={{ fontWeight: 600, borderColor: C.border, bgcolor: C.subtle, color: 'var(--text, inherit)', fontVariantNumeric: 'tabular-nums' }} />
        },
        {
            field: 'volume', headerName: 'VOLUME (AED)', flex: 1.5, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center' }}>
                    <Typography variant="body2" fontWeight="700" color={C.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
                    <Box sx={{ width: '80%', height: 4, bgcolor: C.track, borderRadius: 2, mt: 0.5, overflow: 'hidden' }}>
                        <Box sx={{ width: `${(params.value / params.row.maxVol) * 100}%`, height: '100%', bgcolor: C.bar, borderRadius: 2 }} />
                    </Box>
                </Box>
            )
        },
        {
            field: 'msf', headerName: 'MSF (AED)', flex: 1.2, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color="var(--text, #334155)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatMsf(params.value)}</Typography>
        },
        {
            field: 'msfRate', headerName: 'MSF RATE', flex: 0.9, align: 'right', headerAlign: 'right', sortable: false,
            renderCell: (params) => {
                const pct = params.row.volume > 0 ? (params.row.msf / params.row.volume) * 100 : 0;
                return (
                    <Chip label={`${pct.toFixed(2)}%`} size="small" sx={{
                        fontWeight: 700, fontVariantNumeric: 'tabular-nums',
                        bgcolor: `${rateColor(pct)}1a`, color: rateColor(pct), border: 'none',
                    }} />
                );
            }
        },
        {
            field: 'opt_in_volume', headerName: 'OPT-IN (AED)', flex: 1.2, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="500" color={C.textMut} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        }
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Financial Summary" subtitle="Business Universe — per-merchant breakdown"
                icon={DollarSign}
                onExport={() => exportToCSV(data, 'merchant_financial_summary')}
                onRunReport={() => fetchReport()} onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={(next) => fetchReport(next)}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={() => fetchReport()} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />

            {topMerchants.length > 1 && (
                <Paper sx={{ ...premiumTableWrapper, p: 2.5 }}>
                    <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1.5 }}>
                        <BarChart3 size={16} color={C.textSec} />
                        <Typography variant="body2" fontWeight={700} color={C.textStr}>
                            Top {topMerchants.length} Merchants — Volume &amp; MSF
                        </Typography>
                    </Stack>
                    <Box sx={{ height: Math.max(220, topMerchants.length * 34) }}>
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart data={topMerchants} layout="vertical" margin={{ left: 8, right: 16 }}>
                                <CartesianGrid strokeDasharray="3 3" stroke="var(--border-light, #f3f4f6)" horizontal={false} />
                                <XAxis type="number" tick={{ fontSize: 11, fill: C.textMut }} tickFormatter={formatCompact} />
                                <YAxis dataKey="shortName" type="category" width={130} tick={{ fontSize: 11, fill: C.textSec }} />
                                <Tooltip formatter={(v) => formatCurrency(v)} contentStyle={chartTooltipStyle} cursor={{ fill: 'var(--bg-hover, #f1f5f9)' }} />
                                <Bar dataKey="volume" name="Volume" radius={[0, 6, 6, 0]} barSize={12}>
                                    {topMerchants.map((_, i) => <Cell key={i} fill={C.brandAlt} />)}
                                </Bar>
                                <Bar dataKey="msf" name="MSF" fill={C.warning} radius={[0, 6, 6, 0]} barSize={12} />
                            </BarChart>
                        </ResponsiveContainer>
                    </Box>
                </Paper>
            )}

            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={rows} columns={columns} loading={loading} rowHeight={60}
                    disableRowSelectionOnClick slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default MerchantFinancialSummary;
