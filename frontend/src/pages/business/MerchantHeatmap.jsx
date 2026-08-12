import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, Stack, MenuItem, Select, FormControl, InputLabel, Tooltip, ToggleButton, ToggleButtonGroup } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { Grid as GridIcon, TrendingUp, DollarSign, Users } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt, formatCompactCurrency } from '../../utils/formatters';
import api from '../../api/axios';


// ─── Local design tokens ─────────────────────────────────────────
// Every colour routes through a CSS variable with a light-mode fallback, matching
// the Attrition / Volume / Daily-Merchant pages, so the heatmap re-skins under
// html.dark + ThemeContext. The heat ramp is blue (brand-consistent) rather than
// the old hardcoded green step-function.
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
};

const MONTH_ABBR = Array.from({ length: 12 }, (_, i) =>
    // Mid-month date avoids the epoch/timezone boundary bug (new Date(0, m-1)
    // lands in Dec 1969 east of UTC).
    new Date(2000, i, 15).toLocaleString('en-US', { month: 'short' }).toUpperCase()
);

const MerchantHeatmap = () => {
    const { tenantVersion, currencySymbol, currencyDecimals } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);
    const formatCurrency = fmt.currency;
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [years, setYears] = useState([new Date().getFullYear()]);
    const [year, setYear] = useState(new Date().getFullYear());
    const [maxVolume, setMaxVolume] = useState(0);   // dataset-wide max month cell (Absolute scale)
    const [maxTotal, setMaxTotal] = useState(0);     // dataset-wide max row total (TOTAL bar)
    const [showFilters, setShowFilters] = useState(false);
    // Heat-scale mode. 'row' normalises each merchant to its own peak month —
    // best for reading growth/decline trajectory (the page's stated purpose),
    // so it's the default. 'abs' normalises against the dataset max — best for
    // "who's biggest".
    const [scaleMode, setScaleMode] = useState('row');
    // Full-feature filter set (matches the rest of the business screens). The
    // heatmap previously had two SID inputs (an inline Autocomplete in the header
    // AND the drawer's sidList). Removed the inline one — the drawer is the
    // single source of truth for filters now. P1-2 fix.
    const [filters, setFilters] = useState({
        startDate: '', endDate: '',
        partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
        sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
    });
    // P0-1 FIX: separate "applied" filters from the in-progress drawer state.
    // Previously the useEffect re-fetched on every keystroke in the drawer,
    // causing flicker and stale-data races. Now we only refetch when the user
    // clicks Apply (or year changes).
    const [appliedFilters, setAppliedFilters] = useState(filters);

    useEffect(() => {
        // Build year list dynamically: current year and a few back. Was hardcoded
        // [2024, 2025, 2026] which would silently stop showing the current year
        // once the calendar advances.
        const cy = new Date().getFullYear();
        setYears([cy - 2, cy - 1, cy]);
    }, []);

    // P0-1 FIX: depend on appliedFilters (not the live `filters` object).
    useEffect(() => { fetchData(); }, [year, appliedFilters, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

    const fetchData = async () => {
        setLoading(true);
        try {
            const body = {
                ...appliedFilters,
                startDate: null,
                endDate: null,
            };
            const res = await api.post(`/analytics/heatmap-filtered?year=${year}`, body);
            processData(res.data);
        } catch (error) { console.error('Failed to fetch heatmap data', error); }
        finally { setLoading(false); }
    };

    const handleApply = () => {
        setAppliedFilters(filters);
        setShowFilters(false);
    };

    const processData = (rawData) => {
        if (!rawData) { setData([]); setMaxVolume(0); setMaxTotal(0); return; }
        let grouped = {}, maxVol = 0, maxTot = 0;
        rawData.forEach(row => {
            const key = row.merchantId;
            if (!grouped[key]) grouped[key] = { id: row.merchantId, merchantName: row.merchantName, merchantId: row.merchantId, volumes: {}, total: 0 };
            grouped[key].volumes[row.month] = row.totalVolume;
            grouped[key].total += row.totalVolume;
            if (row.totalVolume > maxVol) maxVol = row.totalVolume;
        });
        const rows = Object.values(grouped).map(item => {
            const flattened = { id: item.id, merchantName: item.merchantName, merchantId: item.merchantId, total: item.total };
            let rowMax = 0;
            for (let i = 1; i <= 12; i++) {
                const v = item.volumes[i] || 0;
                flattened[`month_${i}`] = v;
                if (v > rowMax) rowMax = v;
            }
            flattened.rowMax = rowMax;   // per-merchant peak, for 'row' scale mode
            if (item.total > maxTot) maxTot = item.total;
            return flattened;
        });
        setData(rows.sort((a, b) => a.merchantName.localeCompare(b.merchantName)));
        setMaxVolume(maxVol);
        setMaxTotal(maxTot);
    };

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.total || 0), 0);
        const activeMonths = data.reduce((s, d) => { let c = 0; for (let i = 1; i <= 12; i++) if (d[`month_${i}`] > 0) c++; return s + c; }, 0);
        return [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: 'var(--accent-indigo, #6366f1)', trendLabel: `${year}` },
            { title: 'Total Annual Volume', value: formatCompactCurrency(totalVol), icon: DollarSign, color: T.success, trendLabel: 'all merchants' },
            { title: 'Active Merchant-Months', value: activeMonths.toString(), icon: GridIcon, color: T.brandAlt, trendLabel: `of ${data.length * 12} possible` },
            { title: 'Avg per Merchant', value: formatCompactCurrency(data.length > 0 ? totalVol / data.length : 0), icon: TrendingUp, color: 'var(--warning, #f59e0b)', trendLabel: 'annual volume' },
        ];
    }, [data, currencySymbol, year]);

    // ── Month heat cell ──
    // Flat blue heat tile — colour is the signal, exact number lives in the
    // tooltip. Intensity is either row-relative (each merchant's own peak, for
    // reading trajectory) or dataset-absolute (for reading size), per scaleMode.
    // Peak month of the row gets a thin accent ring. Tooltip carries the exact
    // amount, % of the reference peak, and the MoM delta.
    const monthCell = (params, month) => {
        const val = Number(params.value) || 0;
        const ref = scaleMode === 'row' ? (params.row.rowMax || 0) : maxVolume;
        const intensity = ref > 0 && val > 0 ? Math.max(val / ref, 0.12) : 0;
        const isPeak = val > 0 && (params.row.rowMax || 0) > 0 && val === params.row.rowMax;
        // MoM delta vs previous month in the same row.
        const prev = month > 1 ? (Number(params.row[`month_${month - 1}`]) || 0) : 0;
        const momPct = prev > 0 ? ((val - prev) / prev) * 100 : null;
        const pctOfRef = ref > 0 ? Math.round((val / ref) * 100) : 0;
        const label = MONTH_ABBR[month - 1];

        const tip = val > 0
            ? (
                <Box sx={{ py: 0.25 }}>
                    <Typography sx={{ fontWeight: 700, fontSize: '0.78rem' }}>{label} {year}</Typography>
                    <Typography sx={{ fontSize: '0.75rem' }}>{formatCurrency(val)}</Typography>
                    <Typography sx={{ fontSize: '0.68rem', opacity: 0.85 }}>
                        {pctOfRef}% of {scaleMode === 'row' ? 'row peak' : 'dataset peak'}
                        {momPct !== null && ` · ${momPct >= 0 ? '▲' : '▼'} ${Math.abs(momPct).toFixed(0)}% MoM`}
                    </Typography>
                </Box>
            )
            : `${label} ${year}: no volume`;

        return (
            <Tooltip arrow title={tip}>
                <Box sx={{ width: '100%', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', px: '3px' }}>
                    <Box sx={{
                        width: '100%', height: 30, borderRadius: '4px',
                        bgcolor: val > 0 ? `color-mix(in srgb, ${T.brandAlt} ${Math.round(intensity * 100)}%, transparent)` : T.subtle,
                        boxShadow: isPeak ? `inset 0 0 0 1.5px ${T.brand}` : 'none',
                        transition: 'transform 0.12s ease',
                        '&:hover': { transform: 'scale(1.08)' },
                    }} />
                </Box>
            </Tooltip>
        );
    };

    const columns = [
        {
            field: 'merchantId', headerName: 'MID', width: 110,
            renderCell: (params) => <Typography variant="body2" color={T.textSec} sx={{ fontFamily: 'monospace', fontWeight: 600 }}>{params.value}</Typography>
        },
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', width: 220,
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="600" color={T.text} noWrap title={params.value} sx={{ height: '100%', display: 'flex', alignItems: 'center' }}>
                    {params.value}
                </Typography>
            )
        },
        ...Array.from({ length: 12 }, (_, i) => i + 1).map(month => ({
            field: `month_${month}`,
            headerName: MONTH_ABBR[month - 1],
            width: 64, align: 'center', headerAlign: 'center', sortable: false,
            // Subtle quarter separators after Mar / Jun / Sep give the 12-month
            // strip a readable rhythm.
            cellClassName: (month % 3 === 0 && month !== 12) ? 'quarter-end' : undefined,
            headerClassName: (month % 3 === 0 && month !== 12) ? 'quarter-end' : undefined,
            renderCell: (params) => monthCell(params, month),
        })),
        {
            field: 'total', headerName: 'TOTAL', width: 180, align: 'right', headerAlign: 'right',
            renderCell: (params) => {
                const share = maxTotal > 0 ? (params.value / maxTotal) * 100 : 0;
                return (
                    <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center', height: '100%' }}>
                        <Typography color={T.text} fontWeight="700" variant="body2" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
                        <Box sx={{ width: '80%', height: 4, bgcolor: T.subtle, borderRadius: 2, mt: 0.5, overflow: 'hidden' }}>
                            <Box sx={{ width: `${share}%`, height: '100%', bgcolor: T.brand, borderRadius: 2 }} />
                        </Box>
                    </Box>
                );
            }
        }
    ];

    const extraControls = (
        <Stack direction="row" spacing={2} alignItems="center">
            <FormControl size="small" sx={{ minWidth: 120 }}>
                <InputLabel sx={{ fontSize: '0.85rem' }}>Year</InputLabel>
                <Select value={year} label="Year" onChange={(e) => setYear(Number(e.target.value))}
                    sx={{ borderRadius: 2, fontSize: '0.9rem', fontWeight: 600 }}>
                    {years.map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                </Select>
            </FormControl>
            <ToggleButtonGroup size="small" exclusive value={scaleMode}
                onChange={(e, v) => v && setScaleMode(v)} aria-label="heat scale">
                <ToggleButton value="row" sx={{ textTransform: 'none', fontWeight: 600, px: 1.5 }}>Per-merchant</ToggleButton>
                <ToggleButton value="abs" sx={{ textTransform: 'none', fontWeight: 600, px: 1.5 }}>Absolute</ToggleButton>
            </ToggleButtonGroup>
        </Stack>
    );

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Growth Heatmap" subtitle={`Annual performance visualization — ${year} · ${data.length} merchant${data.length === 1 ? '' : 's'}`}
                icon={GridIcon}
                onExport={() => exportToCSV(data, `heatmap_${year}`)}
                onRunReport={fetchData} loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(s => !s)}
                filters={filters}
                onFilterChange={(patch) => setFilters(prev => ({ ...prev, ...patch }))}
                hideDatePresets
            >
                {extraControls}
            </PremiumReportHeader>

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={handleApply}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            <KpiCards cards={kpis} />

            <Paper elevation={0} sx={{
                ...premiumTableWrapper, border: `1px solid ${T.border}`,
                '& .quarter-end': { borderRight: `1px solid ${T.border} !important` },
            }}>
                <DataGrid
                    rows={data} columns={columns} loading={loading} disableRowSelectionOnClick
                    rowHeight={48}
                    initialState={{ sorting: { sortModel: [{ field: 'total', sort: 'desc' }] } }}
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    sx={{ ...premiumDataGridStyles,
                        '& .MuiDataGrid-cell': { borderBottom: `1px solid ${T.borderLt}` },
                        '& .MuiDataGrid-row:hover': { bgcolor: T.hover },
                    }}
                />
                {/* Inline heat-scale legend — replaces the old full-width legend
                    bar. Label reflects the active scale mode. */}
                {!loading && data.length > 0 && (
                    <Stack direction="row" spacing={1} alignItems="center" sx={{ px: 2, py: 1.25, borderTop: `1px solid ${T.borderLt}` }}>
                        <Typography variant="caption" color={T.textMut} fontWeight={600}>
                            {scaleMode === 'row' ? 'Row min' : 'Low'}
                        </Typography>
                        <Box sx={{ display: 'flex', gap: '2px' }}>
                            {[0.12, 0.3, 0.48, 0.66, 0.84, 1].map((op, i) => (
                                <Box key={i} sx={{ width: 18, height: 12, borderRadius: '2px', bgcolor: `color-mix(in srgb, ${T.brandAlt} ${Math.round(op * 100)}%, transparent)` }} />
                            ))}
                        </Box>
                        <Typography variant="caption" color={T.textMut} fontWeight={600}>
                            {scaleMode === 'row' ? 'Row peak (per merchant)' : 'Dataset peak'}
                        </Typography>
                        <Box sx={{ flex: 1 }} />
                        <Box sx={{ width: 14, height: 14, borderRadius: '3px', boxShadow: `inset 0 0 0 1.5px ${T.brand}` }} />
                        <Typography variant="caption" color={T.textMut} fontWeight={600}>Peak month</Typography>
                    </Stack>
                )}
            </Paper>
        </Box>
    );
};

export default MerchantHeatmap;
