import React, { useState, useEffect, useMemo } from 'react';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import { weekRules } from '../../utils/weekRules';
import api from '../../api/axios';
import { Box, Paper, Typography, Stack, Tooltip, MenuItem, Select, FormControl, Chip, Autocomplete, TextField, Button } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { TrendingUp, TrendingDown, Calendar, Users, DollarSign, Activity, TrendingUp as PerfIcon } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import SkeletonLoader from '../../components/SkeletonLoader';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

// currency formatting is built from the tenant's currency via useAuth + createFmt (see inside component)

// ─── Local design tokens ─────────────────────────────────────────
// Every colour routes through a CSS variable with a light-mode fallback, matching
// the pattern used on the Attrition Report and Volume/Revenue pages, so this page
// re-skins correctly under html.dark + ThemeContext instead of staying hardcoded.
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
    brandAlt: 'var(--brand-alt, #3b82f6)',
    success:  'var(--success, #059669)',
    danger:   'var(--danger, #dc2626)',
    warning:  'var(--warning, #d97706)',
    // "This is the one you picked" green, shared with the Executive Daily
    // Merchant Dashboard's day picker. Deeper than the mint --success so white
    // label text clears 4.5:1 on it.
    select:   'var(--select-green, #12805C)',
};

// Merchant status → muted tint chip, mirroring STATUS_META on the Attrition
// Report so status colouring reads the same language across the app.
const STATUS_META = {
    Stable: { label: 'Stable',  color: 'var(--success, #059669)', bg: 'var(--success-bg, #d1fae5)' },
    Watch:  { label: 'Watch',   color: 'var(--warning, #d97706)', bg: 'var(--warning-bg, #fef3c7)' },
    Risk:   { label: 'At Risk', color: 'var(--danger, #dc2626)',  bg: 'var(--danger-bg, #fee2e2)' },
};

const DailyMerchantDashboard = () => {
    const { currencySymbol, currencyDecimals, tenantVersion, homeCountryCode } = useAuth();
    const formatCurrency = useMemo(() => createFmt(currencySymbol, currencyDecimals).currency, [currencySymbol, currencyDecimals]);
    /* The weekend is the tenant's, not a fixed Sat+Sun: this grid used to shade
       Saturday and Sunday for every bank, which is right for the UAE and wrong
       for Bahrain, Oman and Egypt (Fri+Sat). */
    const week = useMemo(() => weekRules(homeCountryCode), [homeCountryCode]);
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [filterOptions, setFilterOptions] = useState({ sids: [], mids: [] });
    // Default to the CURRENT calendar month. The user can switch to last month
    // via a quick button, or pick any month/year. If the chosen month has no
    // data we surface a "jump to latest available" button instead of silently
    // walking backwards (which used to land on a random old month like Apr 2025).
    const _now = new Date();
    const [filters, setFilters] = useState({
        year: _now.getFullYear(),
        month: _now.getMonth() + 1,
        // BusinessFilters drawer fields. Inline midList/sidList still take
        // precedence; the drawer's are merged in the request body.
        sidList: [],
        midList: [],
        partnerList: [], rmList: [], teamLeaderList: [], mccList: [],
        merchantName: '',
        // Card-level filters — the backend silently ignores these for this
        // dashboard (see DailyMerchantDashboardController), but the drawer still
        // shows them for visual consistency with other screens.
        schemeList: [], cardTypeList: [], destinationList: [], channelList: [],
        industryList: [], sectorList: [], terminalTypeList: [],
        startDate: '', endDate: '',
    });
    const [showFilters, setShowFilters] = useState(false);
    // The latest month that actually has data (from /data-bounds), held as
    // { year, month } so we can offer a "jump to latest" button when the
    // selected month is empty. Null until the bounds call returns.
    const [latestAvailable, setLatestAvailable] = useState(null);

    // Discover the latest month that has data — used ONLY to offer a jump button
    // when the current selection is empty. It no longer changes the default month.
    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const res = await api.get('/business/data-bounds');
                if (!cancelled && res.data?.latest) {
                    const [y, m] = res.data.latest.split('-');
                    setLatestAvailable({ year: Number(y), month: Number(m) });
                }
            } catch (e) {
                console.error('data-bounds fetch failed (non-fatal)', e);
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    useEffect(() => { fetchFilterOptions(); }, [tenantVersion]);
    useEffect(() => {
        fetchDashboardData();
    }, [filters.year, filters.month, filters.sidList, filters.midList,
        filters.partnerList, filters.rmList, filters.teamLeaderList, filters.mccList,
        filters.merchantName, tenantVersion]);

    const fetchFilterOptions = async () => {
        try {
            const res = await api.get('/business/filter-options');
            setFilterOptions({
                sids: (res.data.sids || []).map(s => String(s)),
                mids: (res.data.mids || []).map(s => String(s)),
            });
        } catch (e) { console.error(e); }
    };

    const fetchDashboardData = async () => {
        setLoading(true);
        try {
            // POST to the filtered endpoint so we can send the full drawer filter
            // shape. Year/month stay as query params; everything else goes in body.
            const body = {
                ...filters,
                year: undefined, month: undefined,
                startDate: null, endDate: null,
            };
            const res = await api.post(
                `/business/daily-merchant-dashboard-filtered?year=${filters.year}&month=${filters.month}`,
                body
            );
            const result = res.data;
            // FIX: removed Math.random fake sparkline fallback and the dead
            // sparklineData field — the backend never populates it (confirmed in
            // DailyMerchantDashboardController / MerchantDailyMetricsDTO), so the
            // old TREND (7D) column always rendered empty. Column removed below.
            setData(result.map((r, i) => {
                const dailyVolumes = r.dailyVolumes || {};
                // Row-relative heat scale: each merchant's own peak day, not a
                // fixed AED threshold — otherwise every mid-size-or-larger day
                // saturates to identical full colour and the heatmap says nothing.
                const dailyMax = Math.max(0, ...Object.values(dailyVolumes).map(v => Number(v) || 0));
                return { id: r.merchantId || i, ...r, dailyVolumes, dailyMax };
            }));
        } catch (error) { console.error("Failed to fetch data", error); }
        finally { setLoading(false); }
    };

    const daysInMonth = new Date(filters.year, filters.month, 0).getDate();
    const monthName = new Date(0, filters.month - 1).toLocaleString('default', { month: 'long' });

    // Is the selected month the current calendar month? Used to mark "today"'s
    // column in the heat strip so the grid reads against the calendar.
    const todayDate = new Date();
    const isCurrentMonth = todayDate.getFullYear() === filters.year && (todayDate.getMonth() + 1) === filters.month;
    const todayDay = isCurrentMonth ? todayDate.getDate() : null;

    // Portfolio-wide daily total series (sum across all merchants per day) —
    // powers the Month Volume KPI sparkline. Built once per data/month change,
    // not recomputed per render of the day-grid cells.
    const portfolioDailySeries = useMemo(() => {
        const totals = Array.from({ length: daysInMonth }, () => 0);
        data.forEach(row => {
            const dv = row.dailyVolumes || {};
            for (let d = 1; d <= daysInMonth; d++) totals[d - 1] += Number(dv[d]) || 0;
        });
        return totals;
    }, [data, daysInMonth]);

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.totalVolume || d.totalMtd || 0), 0);
        const totalToday = data.reduce((s, d) => s + (d.todayVol || d.todayVolume || 0), 0);
        const growing = data.filter(d => (d.trendPct || 0) >= 0).length;
        const growingPct = data.length > 0 ? (growing / data.length) * 100 : 0;
        // "vs yesterday" for the Today Volume tile — only meaningful when
        // viewing the current month and there IS a yesterday in it.
        let todayTrend;
        if (isCurrentMonth && todayDay > 1) {
            const yesterdayTotal = data.reduce((s, d) => s + (Number((d.dailyVolumes || {})[todayDay - 1]) || 0), 0);
            todayTrend = yesterdayTotal > 0 ? ((totalToday - yesterdayTotal) / yesterdayTotal) * 100 : undefined;
        }
        return [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: 'var(--accent-indigo, #6366f1)',
              trendLabel: `${monthName} ${filters.year}` },
            { title: 'Month Volume', value: formatCurrency(totalVol), icon: DollarSign, color: T.brandAlt,
              trendLabel: 'daily portfolio total', sparkData: portfolioDailySeries },
            { title: 'Today Volume', value: formatCurrency(totalToday), icon: Activity, color: T.success,
              trend: todayTrend, trendLabel: todayTrend === undefined ? 'no prior day in range' : 'vs yesterday' },
            { title: 'Performance', value: `${growing}/${data.length}`, icon: PerfIcon, color: T.warning,
              subtitle: `${growingPct.toFixed(0)}% growing`, trendLabel: 'merchants trending up' },
        ];
    }, [data, monthName, filters.year, portfolioDailySeries, isCurrentMonth, todayDay]);

    // Quick-select helpers for the month bar.
    const _today = new Date();
    const thisMonth = { year: _today.getFullYear(), month: _today.getMonth() + 1 };
    const _lm = new Date(_today.getFullYear(), _today.getMonth() - 1, 1);
    const lastMonth = { year: _lm.getFullYear(), month: _lm.getMonth() + 1 };
    const isSelected = (sel) => filters.year === sel.year && filters.month === sel.month;
    const selectMonth = (sel) => setFilters(prev => ({ ...prev, year: sel.year, month: sel.month }));

    /* A chosen month is GREEN, matching the day picker on the Executive Daily
       Merchant Dashboard — selection reads as its own signal rather than more
       of the page's brand blue. Deep enough that the white label clears 4.5:1. */
    const quickBtnSx = (active) => ({
        height: 40, px: 2, borderRadius: 2, textTransform: 'none', fontWeight: 700,
        fontSize: '0.8rem', boxShadow: 'none',
        bgcolor: active ? T.select : T.card,
        color: active ? '#fff' : T.textSec,
        border: '1px solid', borderColor: active ? T.select : T.border,
        '&:hover': { bgcolor: active ? 'var(--select-green-dark, #0C6547)' : T.subtle },
    });

    const extraControls = (
        <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
            <Stack direction="row" spacing={1} alignItems="center">
                <Button disableElevation variant="contained" sx={quickBtnSx(isSelected(thisMonth))}
                    onClick={() => selectMonth(thisMonth)}>This Month</Button>
                <Button disableElevation variant="contained" sx={quickBtnSx(isSelected(lastMonth))}
                    onClick={() => selectMonth(lastMonth)}>Last Month</Button>
            </Stack>
            <FormControl size="small" variant="outlined">
                <Select value={filters.month} onChange={(e) => setFilters(prev => ({ ...prev, month: Number(e.target.value) }))}
                    sx={{ borderRadius: 2, height: 40, bgcolor: T.card, fontWeight: 600, '& .MuiOutlinedInput-notchedOutline': { borderColor: T.border } }}>
                    {Array.from({ length: 12 }, (_, i) => <MenuItem key={i + 1} value={i + 1}>{new Date(0, i).toLocaleString('default', { month: 'long' })}</MenuItem>)}
                </Select>
            </FormControl>
            <FormControl size="small" variant="outlined">
                <Select value={filters.year} onChange={(e) => setFilters(prev => ({ ...prev, year: Number(e.target.value) }))}
                    sx={{ borderRadius: 2, height: 40, bgcolor: T.card, fontWeight: 600, '& .MuiOutlinedInput-notchedOutline': { borderColor: T.border } }}>
                    {[2024, 2025, 2026].map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                </Select>
            </FormControl>
            <Autocomplete
                multiple freeSolo size="small"
                options={filterOptions.sids} value={filters.sidList}
                onChange={(e, val) => setFilters(prev => ({ ...prev, sidList: val }))}
                renderInput={(params) => <TextField {...params} label="SID" placeholder={filters.sidList.length ? '' : 'All'} sx={{ minWidth: 180 }} />}
                renderTags={(value, getTagProps) =>
                    value.map((option, index) => <Chip {...getTagProps({ index })} key={option} label={option} size="small" sx={{ bgcolor: T.brand, color: 'white', fontWeight: 600, '& .MuiChip-deleteIcon': { color: 'white', opacity: 0.7 } }} />)
                }
            />
            <Autocomplete
                multiple freeSolo size="small"
                options={filterOptions.mids} value={filters.midList}
                onChange={(e, val) => setFilters(prev => ({ ...prev, midList: val }))}
                renderInput={(params) => <TextField {...params} label="MID" placeholder={filters.midList.length ? '' : 'All'} sx={{ minWidth: 180 }} />}
                renderTags={(value, getTagProps) =>
                    value.map((option, index) => <Chip {...getTagProps({ index })} key={option} label={option} size="small" sx={{ bgcolor: T.success, color: 'white', fontWeight: 600, '& .MuiChip-deleteIcon': { color: 'white', opacity: 0.7 } }} />)
                }
            />
        </Stack>
    );

    // ── Day-grid heat cells ──
    // Flat, borderless heat cells — intensity is the signal, not embedded text.
    // Intensity is RELATIVE TO THE ROW'S OWN PEAK DAY (row.dailyMax), so every
    // merchant's day-shape is legible regardless of its absolute size. A fixed
    // AED threshold (the old `val / 5000`) saturates every mid-or-larger day to
    // identical full colour and the heatmap says nothing. Values render only in
    // the tooltip; the peak day gets a thin accent ring.
    const dayColumns = Array.from({ length: daysInMonth }, (_, i) => i + 1).map(day => {
        const dow = new Date(filters.year, filters.month - 1, day).getDay();
        const isWeekend = week.weekendDays.includes(dow);
        const isToday = todayDay === day;
        return {
            field: `day_${day}`, headerName: `${day}`, width: 34, align: 'center', headerAlign: 'center',
            headerClassName: isToday ? 'daycol-today' : (isWeekend ? 'daycol-weekend' : undefined),
            // The whole column carries the weekend ground, not just its header —
            // the rhythm of the trading week should be readable down the grid.
            cellClassName: isWeekend && !isToday ? 'daycol-weekend' : undefined,
            sortable: false,
            renderCell: (params) => {
                const val = Number((params.row.dailyVolumes || {})[day]) || 0;
                const rowMax = params.row.dailyMax || 0;
                const isPeak = val > 0 && rowMax > 0 && val === rowMax;
                const intensity = rowMax > 0 ? Math.max(val / rowMax, 0.14) : 0;
                const dateLabel = new Date(filters.year, filters.month - 1, day).toLocaleDateString('en-US', { month: 'short', day: 'numeric', weekday: 'short' })
                    + (isWeekend ? ' · weekend' : '');
                const pctOfPeak = rowMax > 0 ? Math.round((val / rowMax) * 100) : 0;
                return (
                    <Tooltip arrow title={val > 0 ? `${dateLabel}: ${formatCurrency(val)} (${pctOfPeak}% of month peak)` : `${dateLabel}: no volume`}>
                        <Box sx={{
                            width: 26, height: 26, borderRadius: '4px',
                            bgcolor: val > 0 ? `color-mix(in srgb, ${T.brandAlt} ${Math.round(intensity * 100)}%, transparent)` : T.subtle,
                            boxShadow: isPeak ? `inset 0 0 0 1.5px ${T.brand}` : 'none',
                            transition: 'transform 0.12s ease',
                            '&:hover': { transform: 'scale(1.15)' },
                        }} />
                    </Tooltip>
                );
            }
        };
    });

    const columns = [
        {
            field: 'merchantName', headerName: 'MERCHANT', width: 220,
            renderCell: (params) => (
                <Box sx={{ minWidth: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '100%' }}>
                    <Typography variant="body2" fontWeight="600" color={T.text} noWrap>{params.value}</Typography>
                    <Typography variant="caption" color={T.textMut} fontFamily="monospace" sx={{ fontSize: '0.7rem', display: 'block', mt: 0.2 }}>
                        {params.row.mid}
                    </Typography>
                </Box>
            )
        },
        {
            field: 'sid', headerName: 'SID', width: 110,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '12px', color: T.textSec }}>
                    {params.value || '-'}
                </Typography>
            )
        },
        {
            field: 'status', headerName: 'STATUS', width: 100, align: 'center', headerAlign: 'center',
            renderCell: (params) => {
                const status = params.row.uiStatus || params.row.stabilityLabel || 'Stable';
                const meta = STATUS_META[status] || STATUS_META.Stable;
                return <Chip label={meta.label} size="small" sx={{ bgcolor: meta.bg, color: meta.color, fontWeight: 700, fontSize: '0.7rem', height: 22 }} />;
            }
        },
        {
            field: 'todayVol', headerName: 'TODAY', width: 120, align: 'right', headerAlign: 'right',
            valueGetter: (value, row) => row.todayVol ?? row.todayVolume ?? 0,
            renderCell: (params) => (
                <Stack alignItems="flex-end" justifyContent="center" height="100%" spacing={0.3}>
                    <Typography fontWeight="700" fontSize="0.85rem" color={T.text} sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
                    {params.row.trendPct !== undefined && params.row.trendPct !== 0 && (
                        <Chip
                            icon={params.row.trendPct >= 0 ? <TrendingUp size={11} /> : <TrendingDown size={11} />}
                            label={`${Math.abs(params.row.trendPct).toFixed(0)}%`} size="small"
                            sx={{ height: 18, fontSize: '0.62rem', fontWeight: 700, fontVariantNumeric: 'tabular-nums',
                                bgcolor: params.row.trendPct >= 0 ? 'var(--success-bg, rgba(16, 185, 129, 0.1))' : 'var(--danger-bg, rgba(239, 68, 68, 0.1))',
                                color: params.row.trendPct >= 0 ? T.success : T.danger, '& .MuiChip-icon': { color: 'inherit' } }} />
                    )}
                </Stack>
            )
        },
        {
            field: 'totalVolume', headerName: 'MONTH TOTAL', width: 120, align: 'right', headerAlign: 'right',
            valueGetter: (value, row) => row.totalVolume ?? row.totalMtd ?? 0,
            renderCell: (params) => <Typography fontWeight="700" color={T.text} fontSize="0.88rem" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        },
        ...dayColumns,
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Daily Merchant Dashboard"
                subtitle={`Tracking performance across ${data.length} merchant${data.length === 1 ? '' : 's'} for ${monthName} ${filters.year} · ${daysInMonth} days`}
                icon={Calendar}
                onExport={() => exportToCSV(data, 'daily_merchant_dashboard')}
                onRunReport={fetchDashboardData} loading={loading} hideDatePresets
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(s => !s)}
                filters={filters}
                onFilterChange={(patch) => setFilters(prev => ({ ...prev, ...patch }))}
            >
                {extraControls}
            </PremiumReportHeader>

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={fetchDashboardData}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            {loading ? (
                <Box mb={3}><SkeletonLoader variant="kpi-row" count={4} /></Box>
            ) : (
                <Box mb={3}><KpiCards cards={kpis} /></Box>
            )}

            {!loading && data.length === 0 && (
                <Paper sx={{ p: 2.5, mb: 3, borderRadius: 'var(--radius-lg, 14px)', border: '1px solid var(--warning-border, #fde68a)', bgcolor: 'var(--warning-bg, #fffbeb)',
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap' }}>
                    <Stack direction="row" spacing={1.5} alignItems="center">
                        <Calendar size={20} color={T.warning} />
                        <Typography variant="body2" fontWeight={600} color="var(--warning-text, #92400e)">
                            No data for {monthName} {filters.year}.
                            {latestAvailable && ` Latest available data is ${new Date(0, latestAvailable.month - 1).toLocaleString('default', { month: 'long' })} ${latestAvailable.year}.`}
                        </Typography>
                    </Stack>
                    {latestAvailable && !(latestAvailable.year === filters.year && latestAvailable.month === filters.month) && (
                        <Button disableElevation variant="contained"
                            sx={{ textTransform: 'none', fontWeight: 700, borderRadius: 2, bgcolor: T.warning, '&:hover': { bgcolor: 'var(--warning-dark, #b45309)' } }}
                            onClick={() => selectMonth(latestAvailable)}>
                            Jump to {new Date(0, latestAvailable.month - 1).toLocaleString('default', { month: 'short' })} {latestAvailable.year}
                        </Button>
                    )}
                </Paper>
            )}

            <Paper sx={{
                ...premiumTableWrapper,
                // A translucent grey, not --bg-subtle: in the light palette
                // subtle (#EFF4FB) and card (#EAF1FA) are five units apart and
                // the weekend column was invisible. This darkens in light mode
                // and lightens in dark, and !important is needed to beat MUI's
                // own column-header background.
                '& .daycol-weekend': {
                    bgcolor: 'color-mix(in srgb, var(--chart-alt, #64748B) 15%, transparent) !important',
                },
                '& .daycol-today': { bgcolor: 'var(--brand-light, #eff6ff)', borderBottom: `2px solid ${T.brand} !important` },
            }}>
                <DataGrid
                    rows={data} columns={columns} loading={loading} disableRowSelectionOnClick
                    rowHeight={56} columnHeaderHeight={44}
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 }, printOptions: { disableToolbarButton: true } } }}
                    sx={premiumDataGridStyles}
                />
                {/* Heat-scale legend — explains what the day-grid colour encodes,
                    since the cells intentionally carry no inline numbers. */}
                {!loading && data.length > 0 && (
                    <Stack direction="row" spacing={1} alignItems="center" sx={{ px: 2, py: 1.25, borderTop: `1px solid ${T.borderLt}` }}>
                        <Typography variant="caption" color={T.textMut} fontWeight={600}>No volume</Typography>
                        <Box sx={{ display: 'flex', gap: '2px' }}>
                            {[0.14, 0.32, 0.5, 0.68, 0.86, 1].map((op, i) => (
                                <Box key={i} sx={{ width: 16, height: 12, borderRadius: '2px', bgcolor: `color-mix(in srgb, ${T.brandAlt} ${Math.round(op * 100)}%, transparent)` }} />
                            ))}
                        </Box>
                        <Typography variant="caption" color={T.textMut} fontWeight={600}>Row peak (per merchant)</Typography>
                        {/* Which columns are shaded, and why — the answer differs
                            by bank, so the grid should say it rather than assume
                            the reader knows their own country's week. */}
                        <Box sx={{ width: 1, borderLeft: `1px solid ${T.borderLt}`, height: 16, mx: 1 }} />
                        <Box sx={{ width: 16, height: 12, borderRadius: '2px',
                            bgcolor: 'color-mix(in srgb, var(--chart-alt, #64748B) 15%, transparent)',
                            border: `1px solid ${T.borderLt}` }} />
                        <Typography variant="caption" color={T.textMut} fontWeight={600}>
                            Weekend ({week.longLabel})
                        </Typography>
                    </Stack>
                )}
            </Paper>
        </Box>
    );
};

export default DailyMerchantDashboard;
