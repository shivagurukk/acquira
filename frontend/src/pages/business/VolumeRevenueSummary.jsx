import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Box, Paper, Typography, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { TrendingUp, TrendingDown, BarChart3, DollarSign, Hash, Percent } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';

const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const TrendPill = ({ val }) => {
    if (!val || val === 0) return <Typography variant="caption" color="text.secondary">-</Typography>;
    const isPositive = val > 0;
    return (
        <Chip
            icon={isPositive ? <TrendingUp size={14} /> : <TrendingDown size={14} />}
            label={`${Math.abs(val).toFixed(1)}%`}
            size="small"
            sx={{
                height: 24, bgcolor: isPositive ? 'rgba(16, 185, 129, 0.08)' : 'rgba(239, 68, 68, 0.08)',
                color: isPositive ? '#10b981' : '#ef4444', fontWeight: 700, border: 'none',
                '& .MuiChip-icon': { color: 'inherit' }
            }}
        />
    );
};

// Compute date range from preset
// IMPORTANT: format using LOCAL date components, not toISOString() which converts
// to UTC. In timezones east of UTC (e.g. IST = UTC+5:30), new Date(yr, mo, 1)
// is local midnight which translates to the PREVIOUS day in UTC. The old code
// used `.toISOString().split('T')[0]` and so "This month" produced 2026-04-30
// instead of 2026-05-01 in IST.
const fmt = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

const computeDateRange = (preset) => {
    const now = new Date();
    switch (preset) {
        case 'TODAY': return { startDate: fmt(now), endDate: fmt(now) };
        case 'MONTH': return { startDate: fmt(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmt(now) };
        case 'LAST_MONTH': return {
            startDate: fmt(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
            endDate: fmt(new Date(now.getFullYear(), now.getMonth(), 0))
        };
        case 'YEAR': return { startDate: fmt(new Date(now.getFullYear(), 0, 1)), endDate: fmt(now) };
        case 'PY': return {
            startDate: fmt(new Date(now.getFullYear() - 1, 0, 1)),
            endDate: fmt(new Date(now.getFullYear() - 1, 11, 31))
        };
        default: return {};
    }
};

const VolumeRevenueSummary = () => {
    const { currencySymbol, formatCurrency: fmtCurrency } = useAuth();
    const formatCurrency = (val) => fmtCurrency(val, { decimals: 2 });
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [showFilters, setShowFilters] = useState(false);

    // Initialize with actual date range for "This Year"
    const initialRange = computeDateRange('YEAR');
    const [filters, setFilters] = useState({ datePreset: 'YEAR', ...initialRange });

    // Track filter version to trigger re-fetch
    const [filterVersion, setFilterVersion] = useState(0);
    const isFirstRun = useRef(true);

    const fetchReport = useCallback(async (filtersToSend) => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const res = await fetch('/api/business/volume-revenue-summary', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`,
                    ...(tenantId ? { 'X-Tenant-Id': tenantId } : {})
                },
                body: JSON.stringify(filtersToSend)
            });
            if (res.ok) setData(await res.json());
        } catch (error) { console.error("Failed to load report", error); }
        finally { setLoading(false); }
    }, []);

    // Auto-fetch on filter changes (debounced by filterVersion)
    useEffect(() => {
        fetchReport(filters);
    }, [filterVersion]); // eslint-disable-line react-hooks/exhaustive-deps

    // Initial load
    useEffect(() => {
        if (isFirstRun.current) {
            isFirstRun.current = false;
            fetchReport(filters);
        }
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    // Called by PremiumReportHeader when a date preset or custom date is clicked
    const handleFilterChange = useCallback((keyOrObj, val) => {
        setFilters(prev => {
            let next;
            if (typeof keyOrObj === 'object') {
                next = { ...prev, ...keyOrObj };
            } else {
                next = { ...prev, [keyOrObj]: val };
            }

            // If a preset was picked (not CUSTOM), compute actual dates
            if (next.datePreset && next.datePreset !== 'CUSTOM' && keyOrObj?.datePreset) {
                const range = computeDateRange(next.datePreset);
                next = { ...next, ...range };
            }

            return next;
        });
        // Trigger re-fetch for preset changes (not for typing custom dates)
        if (typeof keyOrObj === 'object' && keyOrObj.datePreset && keyOrObj.datePreset !== 'CUSTOM') {
            setFilterVersion(v => v + 1);
        }
    }, []);

    // Called by BusinessFilters panel "Apply" or by "Run Report" button
    const handleRunReport = useCallback(() => {
        setFilterVersion(v => v + 1);
    }, []);

    // Called by BusinessFilters onChange (advanced filters)
    const handleAdvancedFilterChange = useCallback((newFilters) => {
        setFilters(newFilters);
    }, []);

    const rows = useMemo(() => {
        if (!data.length) return [];
        const maxVol = Math.max(...data.map(d => d.volume || 0));
        return data.map((curr, idx) => {
            const prev = data[idx + 1];
            const momVolPct = prev && prev.volume > 0 ? ((curr.volume - prev.volume) / prev.volume) * 100 : 0;
            const dateParts = curr.month.split('-');
            const dateObj = new Date(parseInt(dateParts[0]), parseInt(dateParts[1]) - 1);
            const monthStr = dateObj.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
            return { id: idx, ...curr, monthParams: { str: monthStr, raw: curr.month }, momVol: momVolPct, maxVol };
        });
    }, [data]);

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.volume || 0), 0);
        const totalMsf = data.reduce((s, d) => s + (d.msf || 0), 0);
        const totalCount = data.reduce((s, d) => s + (d.count || 0), 0);
        const avgVol = totalVol / data.length;
        const sparkVols = data.slice().reverse().map(d => d.volume || 0);
        const sparkMsf = data.slice().reverse().map(d => d.msf || 0);
        const latest = data[0]; const prev = data[1];
        const volTrend = prev && prev.volume > 0 ? ((latest.volume - prev.volume) / prev.volume) * 100 : 0;
        const msfTrend = prev && prev.msf > 0 ? ((latest.msf - prev.msf) / prev.msf) * 100 : 0;
        return [
            { title: 'Total Volume', value: `${currencySymbol} ${formatCompact(totalVol)}`, icon: BarChart3, color: '#3b82f6', trend: volTrend, trendLabel: 'vs prev month', sparkData: sparkVols },
            { title: 'Total MSF Revenue', value: `${currencySymbol} ${formatCompact(totalMsf)}`, icon: DollarSign, color: '#10b981', trend: msfTrend, trendLabel: 'vs prev month', sparkData: sparkMsf },
            { title: 'Transaction Count', value: formatNumber(totalCount), icon: Hash, color: '#f59e0b', sparkData: data.slice().reverse().map(d => d.count || 0) },
            { title: 'Avg Monthly Volume', value: `${currencySymbol} ${formatCompact(avgVol)}`, icon: Percent, color: '#06b6d4' },
        ];
    }, [data]);

    const columns = [
        {
            field: 'monthParams', headerName: 'Month', flex: 1.2, minWidth: 150,
            sortComparator: (v1, v2) => v1.raw.localeCompare(v2.raw),
            renderCell: (params) => <Typography variant="body2" fontWeight="700" color="#1e293b">{params.value.str}</Typography>
        },
        {
            field: 'count', headerName: 'Count', type: 'number', flex: 0.8, align: 'center', headerAlign: 'center',
            renderCell: (params) => <Chip label={formatNumber(params.value)} size="small" variant="outlined" sx={{ fontWeight: 600, borderColor: '#e2e8f0', bgcolor: '#f8fafc' }} />
        },
        {
            field: 'volume', headerName: 'Volume', flex: 1.5, align: 'right', headerAlign: 'right',
            renderCell: (params) => (
                <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', justifyContent: 'center' }}>
                    <Typography variant="body2" fontWeight="700" color="#0f172a">{formatCurrency(params.value)}</Typography>
                    <Box sx={{ width: '80%', height: 4, bgcolor: '#f1f5f9', borderRadius: 2, mt: 0.5, overflow: 'hidden' }}>
                        <Box sx={{ width: `${(params.value / params.row.maxVol) * 100}%`, height: '100%', bgcolor: '#6366f1', borderRadius: 2 }} />
                    </Box>
                </Box>
            )
        },
        { field: 'momVol', headerName: 'Trend', flex: 0.8, align: 'center', headerAlign: 'center', renderCell: (params) => <TrendPill val={params.value} /> },
        {
            field: 'msf', headerName: 'MSF', flex: 1.2, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="600" color="#334155">{formatCurrency(params.value)}</Typography>
        },
        {
            field: 'opt_in_volume', headerName: 'Opt-in volume', flex: 1.2, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography variant="body2" fontWeight="500" color="#64748b">{formatCurrency(params.value)}</Typography>
        }
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Volume & Revenue Statement" subtitle="Monthly financial performance overview"
                icon={BarChart3}
                onExport={() => exportToCSV(rows, 'volume_revenue_summary')}
                onRunReport={handleRunReport} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={handleAdvancedFilterChange} onApply={handleRunReport} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />
            <Paper sx={premiumTableWrapper}>
                <DataGrid rows={rows} columns={columns} loading={loading} rowHeight={65}
                    disableRowSelectionOnClick
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default VolumeRevenueSummary;
