import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Stack, Tabs, Tab, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { Layers, AlertCircle, Users, Hash, DollarSign, TrendingUp } from 'lucide-react';
import api from '../api/axios';
import PremiumReportHeader from '../components/PremiumReportHeader';
import BusinessFilters from '../components/BusinessFilters';
import KpiCards from '../components/KpiCards';
import { exportToCSV } from '../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../theme/dataGridStyles';
import { useAuth } from '../contexts/AuthContext';

/**
 * Group Management Reports — restructured to match the rest of the business
 * screens (DebitPrepaidMetrics, MerchantFinancialSummary, MerchantHeatmap):
 *
 *  - PremiumReportHeader with date-preset chips + Run/Export
 *  - BusinessFilters drawer (partner / RM / MCC / team-leader / merchant /
 *    MID / SID etc.)
 *  - MUI DataGrid instead of the hand-rolled HTML table
 *  - Hits the new POST /api/group-analytics/{type}/filtered endpoint that
 *    accepts the full VolumeRevenueFilterDTO. The legacy GET still works as
 *    a fallback if the new endpoint isn't deployed yet (P1-1).
 *
 * The four tabs (MCC / MERCHANT / SALES / REFERRAL) all use the same
 * filter set; switching tab re-runs with the same applied filters.
 */

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

const GroupReports = () => {
    const { currencyCode = 'AED', formatCurrency: fmtCurr } = useAuth() || {};
    const formatCurrency = useCallback((val) => {
        if (fmtCurr) return fmtCurr(val);
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: currencyCode }).format(val || 0);
    }, [fmtCurr, currencyCode]);

    const [activeTab, setActiveTab] = useState('MCC');
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [errorMsg, setErrorMsg] = useState(null);
    const [boundsLoaded, setBoundsLoaded] = useState(false);

    const [filters, setFilters] = useState({
        datePreset: 'MONTH', startDate: '', endDate: '',
        partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
        sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
    });

    /* ── Resolve sensible default date range from /api/business/data-bounds ── */
    useEffect(() => {
        const fmtLocal = (d) => {
            const yr = d.getFullYear();
            const mo = String(d.getMonth() + 1).padStart(2, '0');
            const dy = String(d.getDate()).padStart(2, '0');
            return `${yr}-${mo}-${dy}`;
        };
        const loadBounds = async () => {
            try {
                const res = await api.get('/business/data-bounds');
                const b = res.data;
                if (b?.latest) {
                    const latest = new Date(b.latest);
                    const first = new Date(latest.getFullYear(), latest.getMonth(), 1);
                    setFilters(prev => ({
                        ...prev,
                        startDate: fmtLocal(first),
                        endDate:   fmtLocal(latest),
                    }));
                    setBoundsLoaded(true);
                    return;
                }
            } catch (e) { /* fall through */ }
            const range = computeDateRange('MONTH');
            setFilters(prev => ({ ...prev, ...range }));
            setBoundsLoaded(true);
        };
        loadBounds();
    }, []);

    /* ── Fetch report data ──────────────────────────────────────────── */
    const fetchData = useCallback(async (overrideFilters) => {
        setLoading(true);
        setErrorMsg(null);
        try {
            const payload = overrideFilters || filters;
            const body = { ...payload };
            if (body.datePreset && body.datePreset !== 'CUSTOM' && (!body.startDate || !body.endDate)) {
                const range = computeDateRange(body.datePreset);
                body.startDate = range.startDate;
                body.endDate = range.endDate;
            }
            delete body.datePreset;

            // Try the new POST/filtered endpoint first. If the backend doesn't
            // have it yet (404), gracefully fall back to the legacy GET so a
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

    // Don't fire the first request until data-bounds resolved (avoids
    // guaranteed-empty fetch on a fresh page where transaction data lags).
    useEffect(() => {
        if (boundsLoaded) fetchData();
    }, [boundsLoaded, activeTab]); // eslint-disable-line react-hooks/exhaustive-deps

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    /* ── KPI cards ──────────────────────────────────────────────────── */
    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol      = data.reduce((s, d) => s + (Number(d.volume) || 0), 0);
        const totalTxns     = data.reduce((s, d) => s + (Number(d.txnCount) || 0), 0);
        const totalMerch    = data.reduce((s, d) => s + (Number(d.merchantCount) || 0), 0);
        const groupCount    = data.length;
        return [
            { title: 'Groups',             value: formatNumber(groupCount), icon: Layers,      color: '#6366f1' },
            { title: 'Total Volume',       value: `${currencyCode} ${formatCompact(totalVol)}`, icon: DollarSign,  color: '#3b82f6' },
            { title: 'Total Transactions', value: formatCompact(totalTxns), icon: Hash,        color: '#10b981' },
            { title: 'Total Merchants',    value: formatNumber(totalMerch), icon: Users,       color: '#f59e0b' },
        ];
    }, [data, currencyCode]);

    /* ── Grid rows + columns ────────────────────────────────────────── */
    const rows = useMemo(() => {
        // Compose unique id from group key + index so the same key never
        // collides if it appears twice (shouldn't, but cheap insurance).
        return data.map((d, i) => ({
            id: `${d.id ?? ''}-${i}`,
            label: d.label,
            merchantCount: Number(d.merchantCount) || 0,
            txnCount: Number(d.txnCount) || 0,
            volume: Number(d.volume) || 0,
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

    const columns = useMemo(() => [
        {
            field: 'label', headerName: labelHeader, flex: 1.2, minWidth: 200,
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="700" color="#0f172a">
                    {params.value || '—'}
                </Typography>
            )
        },
        {
            field: 'merchantCount', headerName: 'MERCHANTS', type: 'number', width: 130, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Chip label={formatNumber(params.value)} size="small" variant="outlined"
                    sx={{ fontWeight: 600, borderColor: '#e2e8f0', bgcolor: '#f8fafc' }} />
            )
        },
        {
            field: 'txnCount', headerName: 'TRANSACTIONS', type: 'number', width: 150, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" color="#64748b" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {formatNumber(params.value)}
                </Typography>
            )
        },
        {
            field: 'volume', headerName: `VOLUME (${currencyCode})`, type: 'number', flex: 1, minWidth: 180, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Typography variant="body2" fontWeight="700" color="#0f172a" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {formatCurrency(params.value)}
                </Typography>
            )
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
            <Paper elevation={0} sx={{ mb: 2, borderRadius: '10px', border: '1px solid #e2e8f0', bgcolor: 'white' }}>
                <Tabs
                    value={activeTab}
                    onChange={(_, v) => setActiveTab(v)}
                    sx={{
                        px: 2,
                        '& .MuiTab-root': { textTransform: 'none', fontWeight: 600, fontSize: '0.9rem' },
                        '& .Mui-selected': { color: '#3b82f6' },
                        '& .MuiTabs-indicator': { backgroundColor: '#3b82f6' },
                    }}
                >
                    {TABS.map(t => <Tab key={t.id} value={t.id} label={t.label} />)}
                </Tabs>
            </Paper>

            {errorMsg && (
                <Paper elevation={0} sx={{ p: 2, mb: 2, borderRadius: 2, bgcolor: '#fef2f2', border: '1px solid #fecaca' }}>
                    <Stack direction="row" spacing={1.5} alignItems="center">
                        <AlertCircle size={18} color="#b91c1c" />
                        <Box>
                            <Typography variant="body2" fontWeight="600" color="#991b1b">Failed to load report</Typography>
                            <Typography variant="caption" color="#7f1d1d">{errorMsg}</Typography>
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
