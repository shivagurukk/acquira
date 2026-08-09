import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Box, Paper, Typography, Stack, Tabs, Tab, Chip, Tooltip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { Layers, AlertCircle, Users, Hash, DollarSign, TrendingUp, Inbox, Receipt, Percent, Coins } from 'lucide-react';
import api from '../api/axios';
import PremiumReportHeader from '../components/PremiumReportHeader';
import BusinessFilters from '../components/BusinessFilters';
import KpiCards from '../components/KpiCards';
import { exportToCSV } from '../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../theme/dataGridStyles';
import { useAuth } from '../contexts/AuthContext';
import { useDataBounds } from '../hooks/useDataBounds';

/**
 * Group Management Reports — enriched to consume the full payload the backend
 * (GroupAnalyticsController.buildEnrichedResponse) already returns:
 *
 *   volume (settlement basis), msf, interchange, schemeFee, netRevenue,
 *   avgTicket, msfRateBps, marginPct, sharePct, debitPrepaidVolume,
 *   creditVolume, merchantCount, txnCount
 *
 * Screen structure mirrors the other business reports:
 *  - PremiumReportHeader with date-preset chips + Run/Export
 *  - BusinessFilters drawer
 *  - Two rows of KPI tiles (scale + economics)
 *  - MUI DataGrid: full-width flex columns with inline share bar,
 *    credit/debit mix bar, and a colour-coded margin chip
 *  - POST /api/group-analytics/{type}/filtered first, legacy GET fallback
 *
 * Volume everywhere on this page is total_base_volume (settlement,
 * single-currency) per the platform data-sourcing rule.
 */

/* ── Local design tokens (CSS-var routed, dark-mode safe) ─────────── */
const T = {
    card:    'var(--bg-card, #ffffff)',
    subtle:  'var(--bg-subtle, #f8fafc)',
    border:  'var(--border, #e2e8f0)',
    text:    'var(--text, #0f172a)',
    textSec: 'var(--text-secondary, #475569)',
    textMut: 'var(--text-muted, #94a3b8)',
    brand:   'var(--brand, #3b82f6)',
    good:    'var(--success, #10b981)',
    warn:    'var(--warning, #f59e0b)',
    bad:     'var(--danger, #ef4444)',
    barBg:   'var(--border-light, #eef2f7)',
    credit:  'var(--grp-credit, #6366f1)',
    debit:   'var(--grp-debit, #14b8a6)',
};

/* ── Date preset resolver (timezone-safe) ─────────────────────────── */
const computeDateRange = (preset) => {
    const now = new Date();
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

const TABS = [
    { id: 'MCC',      label: 'MCC Performance' },
    { id: 'MERCHANT', label: 'Top Merchants' },
    { id: 'SALES',    label: 'Sales Performance' },
    { id: 'REFERRAL', label: 'Referral Partners' },
];

const formatNumber  = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

/* Margin chip colour bands: healthy >= 40%, watch 15–40%, thin/negative < 15% */
const marginBand = (pct) => {
    const p = Number(pct) || 0;
    if (p >= 40) return { fg: T.good, bg: 'var(--grp-margin-good-bg, #d1fae5)' };
    if (p >= 15) return { fg: T.warn, bg: 'var(--grp-margin-warn-bg, #fef3c7)' };
    return { fg: T.bad, bg: 'var(--grp-margin-bad-bg, #fee2e2)' };
};

const GroupReports = () => {
    const { currencyCode = 'AED', formatCurrency: fmtCurr, tenantVersion } = useAuth() || {};
    const formatCurrency = useCallback((val) => {
        if (fmtCurr) return fmtCurr(val);
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: currencyCode }).format(val || 0);
    }, [fmtCurr, currencyCode]);

    const [activeTab, setActiveTab] = useState('MCC');
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [errorMsg, setErrorMsg] = useState(null);

    const [filters, setFilters] = useState({
        datePreset: 'MONTH', startDate: '', endDate: '',
        partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
        sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
    });

    /* ── Default date range ─────────────────────────────────────── */
    const { startDate: boundsStart, endDate: boundsEnd, boundsLoaded } = useDataBounds(tenantVersion);

    // Latest filters, readable from the fetch effect below without making it
    // depend on `filters` (which would re-run the report on every drawer edit).
    const filtersRef = useRef(filters);
    filtersRef.current = filters;
    // Bounds signature already seeded into filter state, so a tab switch re-runs
    // the report without clobbering a date range the user has since chosen.
    const seededRef = useRef('');

    /* ── Fetch report data ──────────────────────────────────────────── */
    const fetchData = useCallback(async (overrideFilters) => {
        setLoading(true);
        setErrorMsg(null);
        try {
            // Guard: only treat the argument as an override when it's a plain
            // filter object — a header button wiring this as an onClick handler
            // would otherwise pass a DOM event and POST garbage as the body.
            const isPlainFilterObject =
                overrideFilters &&
                typeof overrideFilters === 'object' &&
                !(typeof overrideFilters.preventDefault === 'function') &&
                !('nativeEvent' in overrideFilters);
            const payload = isPlainFilterObject ? overrideFilters : filters;
            const body = { ...payload };
            // Date resolution: a non-CUSTOM preset always wins over any dates
            // pre-populated by data-bounds; CUSTOM = explicit dates as-is.
            if (body.datePreset && body.datePreset !== 'CUSTOM') {
                const range = computeDateRange(body.datePreset);
                if (range.startDate && range.endDate) {
                    body.startDate = range.startDate;
                    body.endDate = range.endDate;
                }
            }
            delete body.datePreset;

            // New POST/filtered endpoint first; legacy GET fallback on 404 so a
            // partial deploy doesn't break the page.
            try {
                const res = await api.post(`/group-analytics/${activeTab}/filtered`, body);
                setData(res.data || []);
            } catch (err) {
                if (err.response?.status === 404) {
                    const period = payload.datePreset && payload.datePreset !== 'CUSTOM' ? payload.datePreset : 'MONTH';
                    const res = await api.get(`/group-analytics/${activeTab}`, { params: { period } });
                    setData(res.data || []);
                    setErrorMsg('Backend running an older build — drawer filters are not applied. Period chip still works.');
                } else {
                    throw err;
                }
            }
        } catch (error) {
            console.error('group-analytics fetch failed', error);
            const status = error.response?.status;
            const msg = error.response?.data?.error
                     || error.response?.data?.message
                     || (typeof error.response?.data === 'string' ? error.response.data : null)
                     || error.message;
            if (status === 403) {
                setErrorMsg('Access denied. Verify your tenant context (X-Tenant-Id header) and group permissions.');
            } else if (status === 404) {
                setErrorMsg(`Endpoint not found for ${activeTab}. Backend may need a redeploy.`);
            } else if (status >= 500) {
                setErrorMsg(`Server error (${status}): ${msg}. Check core.log for the underlying SQL/exception.`);
            } else {
                setErrorMsg(`Request failed: ${msg}`);
            }
            setData([]);
        } finally {
            setLoading(false);
        }
    }, [filters, activeTab]);

    // Don't fire the first request until data-bounds resolved — and post an
    // explicit filter object rather than relying on fetchData's closure.
    //
    // Seeding the bounds into state and firing the fetch used to live in two
    // separate effects that both run in the same commit when boundsLoaded flips.
    // The fetch therefore read `filters` from its stale closure — still the
    // initial { datePreset: 'MONTH' } — and the body resolver expanded that into
    // the CURRENT CALENDAR MONTH, so the page opened on this month instead of
    // the data window and came back empty whenever the feed's latest
    // business_date was in an earlier month.
    useEffect(() => {
        if (!boundsLoaded) return;
        const sig = `${boundsStart}|${boundsEnd}|${tenantVersion}`;
        let next = filtersRef.current;
        if (seededRef.current !== sig) {
            seededRef.current = sig;
            next = { ...filtersRef.current, datePreset: 'CUSTOM', startDate: boundsStart, endDate: boundsEnd };
            setFilters(next);
        }
        fetchData(next);
    }, [boundsLoaded, boundsStart, boundsEnd, tenantVersion, activeTab]); // eslint-disable-line react-hooks/exhaustive-deps

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    /* ── KPI tiles ──────────────────────────────────────────────────── */
    // Two conceptual rows: scale (groups/volume/txns/merchants) and economics
    // (net margin, MSF, blended take rate, avg ticket). Blended bps and avg
    // ticket are recomputed from the totals (volume-weighted), NOT averaged
    // across rows — a simple average of per-row bps would over-weight small
    // groups and misstate the book's real take rate.
    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol    = data.reduce((s, d) => s + (Number(d.volume) || 0), 0);
        const totalTxns   = data.reduce((s, d) => s + (Number(d.txnCount) || 0), 0);
        const totalMerch  = data.reduce((s, d) => s + (Number(d.merchantCount) || 0), 0);
        const totalMsf    = data.reduce((s, d) => s + (Number(d.msf) || 0), 0);
        const totalNetRev = data.reduce((s, d) => s + (Number(d.netRevenue) || 0), 0);
        const groupCount  = data.length;
        const blendedBps  = totalVol > 0 ? (totalMsf / totalVol) * 10000 : 0;
        const avgTicket   = totalTxns > 0 ? totalVol / totalTxns : 0;
        const marginPct   = totalMsf > 0 ? (totalNetRev / totalMsf) * 100 : 0;
        return [
            { title: 'Groups',             value: formatNumber(groupCount),                      icon: Layers,     color: '#6366f1' },
            { title: 'Total Volume',       value: `${currencyCode} ${formatCompact(totalVol)}`,  icon: DollarSign, color: '#3b82f6',
              subtitle: 'settlement basis' },
            { title: 'Transactions',       value: formatCompact(totalTxns),                      icon: Hash,       color: '#10b981' },
            { title: 'Merchants',          value: formatNumber(totalMerch),                      icon: Users,      color: '#f59e0b' },
            { title: 'Net Margin',        value: `${currencyCode} ${formatCompact(totalNetRev)}`, icon: TrendingUp, color: '#059669',
              subtitle: `${marginPct.toFixed(1)}% margin on MSF` },
            { title: 'MSF',                value: `${currencyCode} ${formatCompact(totalMsf)}`,  icon: Receipt,    color: '#8b5cf6' },
            { title: 'Blended MSF Rate',   value: `${blendedBps.toFixed(1)} bps`,                icon: Percent,    color: '#0891b2',
              subtitle: 'volume-weighted' },
            { title: 'Avg Ticket',         value: formatCurrency(avgTicket),                     icon: Coins,      color: '#d97706' },
        ];
    }, [data, currencyCode, formatCurrency]);

    /* ── Grid rows + columns ────────────────────────────────────────── */
    const rows = useMemo(() => {
        return data.map((d, i) => ({
            id: `${d.id ?? ''}-${i}`,
            label: d.label,
            merchantCount: Number(d.merchantCount) || 0,
            txnCount: Number(d.txnCount) || 0,
            volume: Number(d.volume) || 0,
            sharePct: Number(d.sharePct) || 0,
            avgTicket: Number(d.avgTicket) || 0,
            msf: Number(d.msf) || 0,
            msfRateBps: Number(d.msfRateBps) || 0,
            netRevenue: Number(d.netRevenue) || 0,
            marginPct: Number(d.marginPct) || 0,
            debitPrepaidVolume: Number(d.debitPrepaidVolume) || 0,
            creditVolume: Number(d.creditVolume) || 0,
        }));
    }, [data]);

    const labelHeader = useMemo(() => {
        switch (activeTab) {
            case 'MCC':      return 'MCC';
            case 'MERCHANT': return 'MERCHANT NAME';
            case 'SALES':    return 'SALES USER';
            case 'REFERRAL': return 'REFERRAL PARTNER';
            default:         return 'GROUP';
        }
    }, [activeTab]);

    // All flex + minWidth so the grid fills the container edge-to-edge at any
    // viewport (no dead band, no misaligned fixed columns); narrow viewports
    // fall back to horizontal scroll instead of crushing the cells.
    const columns = useMemo(() => [
        {
            field: 'label', headerName: labelHeader, flex: 1.5, minWidth: 170,
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="700" sx={{ color: T.text }} noWrap>
                    {params.value || '—'}
                </Typography>
            )
        },
        {
            field: 'merchantCount', headerName: 'MERCHANTS', type: 'number', flex: 0.7, minWidth: 105, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Chip label={formatNumber(params.value)} size="small" variant="outlined"
                    sx={{ fontWeight: 600, borderColor: T.border, bgcolor: T.subtle, color: T.textSec }} />
            )
        },
        {
            field: 'txnCount', headerName: 'TXNS', type: 'number', flex: 0.7, minWidth: 95, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" sx={{ color: T.textSec, fontVariantNumeric: 'tabular-nums' }}>
                    {formatNumber(params.value)}
                </Typography>
            )
        },
        {
            field: 'volume', headerName: `VOLUME (${currencyCode})`, type: 'number', flex: 1.2, minWidth: 145, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="700" sx={{ color: T.text, fontVariantNumeric: 'tabular-nums' }}>
                    {formatCurrency(params.value)}
                </Typography>
            )
        },
        {
            // Share of grand-total volume — number + inline bar so the
            // concentration of the book is readable at a glance.
            field: 'sharePct', headerName: 'SHARE', type: 'number', flex: 0.9, minWidth: 110, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 1 }}>
                    <Typography variant="caption" fontWeight={700} sx={{ color: T.textSec, fontVariantNumeric: 'tabular-nums', minWidth: 42, textAlign: 'right' }}>
                        {Number(params.value).toFixed(1)}%
                    </Typography>
                    <Box sx={{ width: 46, height: 6, borderRadius: 3, bgcolor: T.barBg, overflow: 'hidden', flexShrink: 0 }}>
                        <Box sx={{ width: `${Math.min(Number(params.value) || 0, 100)}%`, height: '100%', bgcolor: T.brand, borderRadius: 3 }} />
                    </Box>
                </Box>
            )
        },
        {
            field: 'avgTicket', headerName: 'AVG TICKET', type: 'number', flex: 0.9, minWidth: 115, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" sx={{ color: T.textSec, fontVariantNumeric: 'tabular-nums' }}>
                    {formatCurrency(params.value)}
                </Typography>
            )
        },
        {
            field: 'msf', headerName: 'MSF', type: 'number', flex: 1, minWidth: 120, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Tooltip title={`${Number(params.row.msfRateBps).toFixed(1)} bps of volume`}>
                    <Typography variant="body2" sx={{ color: T.textSec, fontVariantNumeric: 'tabular-nums' }}>
                        {formatCurrency(params.value)}
                    </Typography>
                </Tooltip>
            )
        },
        {
            field: 'netRevenue', headerName: 'NET MARGIN', type: 'number', flex: 1, minWidth: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="700"
                    sx={{ color: Number(params.value) < 0 ? T.bad : T.text, fontVariantNumeric: 'tabular-nums' }}>
                    {formatCurrency(params.value)}
                </Typography>
            )
        },
        {
            // Net margin as % of MSF — colour-banded chip (>=40 healthy,
            // 15–40 watch, <15 thin/negative). Negative margin = fees exceed
            // MSF: the pricing-review flag.
            field: 'marginPct', headerName: 'MARGIN', type: 'number', flex: 0.7, minWidth: 95, align: 'right', headerAlign: 'right',
            renderCell: (params) => {
                const band = marginBand(params.value);
                return (
                    <Chip size="small" label={`${Number(params.value).toFixed(1)}%`}
                        sx={{ fontWeight: 700, fontSize: '.7rem', color: band.fg, bgcolor: band.bg }} />
                );
            }
        },
        {
            // Credit vs debit/prepaid mix as a mini stacked bar. Sorting uses
            // the credit share (valueGetter) so the column is still orderable.
            field: 'mix', headerName: 'CREDIT / DEBIT MIX', sortable: true, flex: 1, minWidth: 140,
            valueGetter: (v, row) => {
                const total = (row.creditVolume || 0) + (row.debitPrepaidVolume || 0);
                return total > 0 ? (row.creditVolume / total) * 100 : 0;
            },
            renderCell: (params) => {
                const credit = Number(params.row.creditVolume) || 0;
                const debit = Number(params.row.debitPrepaidVolume) || 0;
                const total = credit + debit;
                if (total <= 0) {
                    return <Typography variant="caption" sx={{ color: T.textMut }}>—</Typography>;
                }
                const creditPct = (credit / total) * 100;
                return (
                    <Tooltip title={`Credit ${creditPct.toFixed(0)}% · Debit/Prepaid ${(100 - creditPct).toFixed(0)}%`}>
                        <Box sx={{ width: '100%', display: 'flex', alignItems: 'center', gap: 1 }}>
                            <Box sx={{ flex: 1, height: 8, borderRadius: 4, overflow: 'hidden', display: 'flex', bgcolor: T.barBg }}>
                                <Box sx={{ width: `${creditPct}%`, bgcolor: T.credit }} />
                                <Box sx={{ width: `${100 - creditPct}%`, bgcolor: T.debit }} />
                            </Box>
                            <Typography variant="caption" sx={{ color: T.textMut, fontVariantNumeric: 'tabular-nums', minWidth: 30 }}>
                                {creditPct.toFixed(0)}%
                            </Typography>
                        </Box>
                    </Tooltip>
                );
            }
        },
    ], [labelHeader, currencyCode, formatCurrency]);

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Group Management Reports"
                subtitle="Analyze performance across MCC, merchants, sales users, and referral partners"
                icon={Layers}
                onExport={() => exportToCSV(data, `group_report_${activeTab.toLowerCase()}`)}
                onRunReport={() => fetchData()}
                onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={() => fetchData()}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                filters={filters}
            />

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={() => { fetchData(); setShowFilters(false); }}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            <KpiCards cards={kpis} />

            {/* Tabs — pick which grouping dimension to view */}
            <Paper elevation={0} sx={{ mb: 2, borderRadius: '10px', border: `1px solid ${T.border}`, bgcolor: T.card }}>
                <Tabs
                    value={activeTab}
                    onChange={(_, v) => setActiveTab(v)}
                    variant="scrollable" scrollButtons="auto"
                    sx={{
                        px: 2,
                        '& .MuiTab-root': { textTransform: 'none', fontWeight: 600, fontSize: '0.9rem' },
                        '& .Mui-selected': { color: T.brand },
                        '& .MuiTabs-indicator': { backgroundColor: T.brand },
                    }}
                >
                    {TABS.map(t => <Tab key={t.id} value={t.id} label={t.label} />)}
                </Tabs>
            </Paper>

            {errorMsg && (
                <Paper elevation={0} sx={{ p: 2, mb: 2, borderRadius: 2, bgcolor: 'var(--danger-bg, #fef2f2)', border: '1px solid var(--danger-border, #fecaca)' }}>
                    <Stack direction="row" spacing={1.5} alignItems="center">
                        <AlertCircle size={18} color="#b91c1c" />
                        <Box>
                            <Typography variant="body2" fontWeight="600" sx={{ color: 'var(--danger-text, #991b1b)' }}>Failed to load report</Typography>
                            <Typography variant="caption" sx={{ color: 'var(--danger-text, #7f1d1d)' }}>{errorMsg}</Typography>
                        </Box>
                    </Stack>
                </Paper>
            )}

            {/* Empty-state notice: request SUCCEEDED but returned no rows.
                All four tabs read from sum_daily_merchant (transaction
                summaries). The MCC tab additionally requires dim_store rows
                (it resolves MCC through the store dimension), so it can be
                empty while Top Merchants has data — that means the merchant
                master upload is missing store records for these merchants. */}
            {!loading && !errorMsg && data.length === 0 && (
                <Paper elevation={0} sx={{ p: 2, mb: 2, borderRadius: 2, bgcolor: 'var(--bg-subtle)', border: '1px solid var(--border)' }}>
                    <Stack direction="row" spacing={1.5} alignItems="center">
                        <Inbox size={18} color="var(--text-muted)" />
                        <Box>
                            <Typography variant="body2" fontWeight="600" color="var(--text)">No data for the selected period</Typography>
                            <Typography variant="caption" color="var(--text-secondary)">
                                {activeTab === 'MCC'
                                    ? 'The MCC view resolves categories through store records. If the Top Merchants tab shows data but this one is empty, the merchant master upload is missing store (SID) rows for these merchants. If all tabs are empty, upload a transaction file or widen the date range.'
                                    : 'Group reports are built from transaction summaries. If you have only uploaded merchant master data, upload a transaction file to populate these tabs. Otherwise, try a wider date range.'}
                            </Typography>
                        </Box>
                    </Stack>
                </Paper>
            )}

            <Paper sx={premiumTableWrapper}>
                <DataGrid
                    rows={rows} columns={columns} loading={loading}
                    rowHeight={55}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    initialState={{
                        pagination: { paginationModel: { pageSize: 25 } },
                        sorting: { sortModel: [{ field: 'volume', sort: 'desc' }] },
                    }}
                    pageSizeOptions={[25, 50, 100]}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default GroupReports;
