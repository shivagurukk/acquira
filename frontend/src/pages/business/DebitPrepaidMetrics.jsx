import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Box, Paper, Typography } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { CreditCard, Hash, DollarSign, TrendingUp } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';
import { useDataBounds } from '../../hooks/useDataBounds';

const computeDateRange = (preset) => {
    const now = new Date();
    // Local-date formatter — see PremiumReportHeader.jsx for the timezone bug explanation.
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

const DebitPrepaidMetrics = () => {
    const { currencyCode = 'AED', formatCurrency: fmtCurr } = useAuth() || {};

    const formatCurrency = useCallback((val) => {
        if (fmtCurr) return fmtCurr(val);
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: currencyCode, minimumFractionDigits: 2 }).format(val || 0);
    }, [fmtCurr, currencyCode]);

    const formatNumber  = (val) => new Intl.NumberFormat('en-US').format(val || 0);
    const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

    const [filters, setFilters] = useState(() => {
        // Start with empty dates; the data-bounds effect below replaces these with
        // the latest month that actually has data. Avoids the "empty by default"
        // problem when transaction data lags real time.
        return { datePreset: 'MONTH', startDate: '', endDate: '' };
    });
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [fetchError, setFetchError] = useState(null);

    /* ── Default date range ─────────────────────────────────────── */
    // Shared useDataBounds hook: resolves the FULL data window (earliest ->
    // latest) from /api/business/data-bounds, with a wide fallback. One
    // implementation shared across every business report page.
    const { startDate: boundsStart, endDate: boundsEnd, boundsLoaded } = useDataBounds();

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

    const fetchData = useCallback(async (overrideFilters) => {
        setLoading(true);
        setFetchError(null);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');

            const payload = overrideFilters || filters;

            // Date resolution: a non-CUSTOM preset must ALWAYS win when the user
            // picks one. The old `(!startDate || !endDate)` guard meant that once
            // loadBounds had filled the dates in, clicking a period chip did
            // nothing. CUSTOM means "use the explicit startDate/endDate as-is".
            const body = { ...payload };
            if (body.datePreset && body.datePreset !== 'CUSTOM') {
                const range = computeDateRange(body.datePreset);
                if (range.startDate && range.endDate) {
                    body.startDate = range.startDate;
                    body.endDate = range.endDate;
                }
            }
            // Remove non-DTO fields
            delete body.datePreset;

            const res = await fetch('/api/business/debit-prepaid-metrics', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`,
                    ...(tenantId ? { 'X-Tenant-Id': tenantId } : {})
                },
                body: JSON.stringify(body)
            });

            if (res.ok) {
                const result = await res.json();
                if (result.length === 0) {
                    setFetchError('No data found for selected filters. Try expanding your date range or removing filters.');
                }
                setData(result.map((r, i) => ({
                    id: `${r.mid || ''}-${r.sid || ''}-${i}`,
                    ...r
                })));
            } else {
                const errorText = await res.text();
                console.error('Debit-Prepaid API error:', res.status, errorText);
                setFetchError(`API returned ${res.status}. Check server logs.`);
                setData([]);
            }
        } catch (error) {
            console.error('Failed to fetch debit/prepaid metrics:', error);
            setFetchError(`Network error: ${error.message}`);
            setData([]);
        } finally {
            setLoading(false);
        }
    }, [filters]);

    useEffect(() => {
        // Wait until data-bounds resolved so we don't fire a guaranteed-empty fetch first.
        if (boundsLoaded) fetchData();
    }, [boundsLoaded]); // eslint-disable-line react-hooks/exhaustive-deps

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') {
            setFilters(prev => ({ ...prev, ...keyOrObj }));
        } else {
            setFilters(prev => ({ ...prev, [keyOrObj]: val }));
        }
    };

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol   = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        const uniqueMids = new Set(data.map(d => d.mid)).size;
        return [
            { title: 'Total Merchants',      value: formatNumber(uniqueMids), icon: CreditCard, color: '#6366f1' },
            { title: 'Total Volume',          value: `${currencyCode} ${formatCompact(totalVol)}`, icon: DollarSign, color: '#3b82f6' },
            { title: 'Total Transactions',    value: formatCompact(totalCount), icon: Hash,        color: '#10b981' },
            { title: 'Avg per Merchant',      value: `${currencyCode} ${formatCompact(uniqueMids > 0 ? totalVol / uniqueMids : 0)}`, icon: TrendingUp, color: '#f59e0b' },
        ];
    }, [data, currencyCode]);

    const columns = [
        {
            field: 'mid', headerName: 'MID', width: 150,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: '"Roboto Mono", monospace', fontSize: '13px', color: '#475569', bgcolor: '#f1f5f9', px: 1, py: 0.5, borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                    {params.value}
                </Typography>
            )
        },
        {
            field: 'sid', headerName: 'SID', width: 150,
            renderCell: (params) => (
                <Typography variant="body2" sx={{ fontFamily: '"Roboto Mono", monospace', fontSize: '13px', color: '#475569', bgcolor: '#f1f5f9', px: 1, py: 0.5, borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                    {params.value || '-'}
                </Typography>
            )
        },
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', flex: 1, minWidth: 200,
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color="#1e293b">{params.value}</Typography>
        },
        {
            field: 'count', headerName: 'COUNT', type: 'number', width: 120, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" color="#64748b" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatNumber(params.value)}</Typography>
        },
        {
            field: 'volume', headerName: `VOLUME (${currencyCode})`, type: 'number', flex: 1, minWidth: 180, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="700" color="#0f172a" sx={{ fontVariantNumeric: 'tabular-nums' }}>{formatCurrency(params.value)}</Typography>
        },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Debit & Prepaid Metrics" subtitle="Debit and prepaid card performance by merchant and store"
                icon={CreditCard}
                onExport={() => exportToCSV(data, 'debit_prepaid_metrics')}
                onRunReport={() => fetchData()} onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={() => fetchData()}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={() => fetchData()} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />

            {fetchError && !loading && data.length === 0 && (
                <Paper sx={{ p: 3, mb: 2, borderRadius: 2, bgcolor: '#fffbeb', border: '1px solid #fde68a' }}>
                    <Typography variant="body2" color="#92400e" fontWeight="600">{fetchError}</Typography>
                    <Typography variant="caption" color="#a16207" sx={{ mt: 1, display: 'block' }}>
                        This report shows transactions where card_type is DEBIT or PREPAID (any destination unless you've narrowed it via filters).
                        If this is empty, the underlying data may not have card_type populated — check the server log for the
                        "[DebitPrepaid] EMPTY result" diagnostic line which lists the actual card_type values present.
                    </Typography>
                </Paper>
            )}

            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={data} columns={columns} loading={loading} rowHeight={55}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
                    pageSizeOptions={[25, 50, 100]}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default DebitPrepaidMetrics;
