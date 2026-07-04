import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography, Stack, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { DollarSign, Store, CreditCard, Hash, Users, TrendingUp } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';
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

    const fetchReport = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const body = { ...filters };
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
        // P0-2 FIX: previously used `d.mid || d.sid || i` which collided when one
        // merchant had multiple stores (same mid, different sid). MUI DataGrid
        // de-duplicates on `id` so the second store row silently disappeared.
        // Compose mid+sid+index so every row is unique.
        return data.map((d, i) => ({ id: `${d.mid || ''}-${d.sid || ''}-${i}`, ...d, maxVol }));
    }, [data]);

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalMsf = data.reduce((s, d) => s + (d.msf || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        const topVols = data.slice().sort((a, b) => (b.volume || 0) - (a.volume || 0)).slice(0, 10).map(d => d.volume || 0);
        return [
            { title: 'Total Merchants', value: formatNumber(data.length), icon: Users, color: 'var(--accent-indigo, #6366f1)' },
            { title: 'Total Volume', value: `${currencyCode} ${formatCompact(totalVol)}`, icon: TrendingUp, color: 'var(--brand-alt, #3b82f6)', sparkData: topVols },
            { title: 'Total MSF', value: `${currencyCode} ${formatCompact(totalMsf)}`, icon: DollarSign, color: 'var(--success, #10b981)' },
            { title: 'Total Transactions', value: formatCompact(totalCount), icon: Hash, color: 'var(--warning, #f59e0b)' },
        ];
    }, [data]);

    const columns = [
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', flex: 1.5, minWidth: 200,
            renderCell: (params) => <Typography variant="body2" fontWeight="700" color={C.textStr}>{params.value}</Typography>
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
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color="var(--text, #334155)" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
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
                onRunReport={fetchReport} onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={fetchReport}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchReport} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />
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
