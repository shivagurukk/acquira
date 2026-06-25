import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, ToggleButton, ToggleButtonGroup, Chip, Stack } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { Activity, TrendingDown, TrendingUp, Users, DollarSign, AlertTriangle, UserMinus } from 'lucide-react';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import api from '../../api/axios';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useDataBounds } from '../../hooks/useDataBounds';

// Metric → key-suffix the backend returns. Volume keeps the original (suffix-less)
// keys for backward compatibility; txns/revenue use the parallel suffixed keys.
const METRICS = {
    volume:  { label: 'Volume',       suffix: '',      kind: 'currency' },
    txns:    { label: 'Transactions', suffix: '_txns', kind: 'count' },
    revenue: { label: 'Revenue (MSF)',suffix: '_msf',  kind: 'currency' },
};

// Attrition status → colour + label. Mirrors classifyAttrition() in the backend.
const STATUS_META = {
    CHURNED:   { label: 'Churned',   color: '#7c3aed', bg: '#f3e8ff' },
    AT_RISK:   { label: 'At Risk',   color: '#dc2626', bg: '#fee2e2' },
    DECLINING: { label: 'Declining', color: '#ea580c', bg: '#ffedd5' },
    STABLE:    { label: 'Stable',    color: '#475569', bg: '#f1f5f9' },
    GROWING:   { label: 'Growing',   color: '#059669', bg: '#d1fae5' },
};
const STATUS_ORDER = ['ALL', 'CHURNED', 'AT_RISK', 'DECLINING', 'STABLE', 'GROWING'];

const AttritionReport = () => {
    const { currencySymbol } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [metric, setMetric] = useState('volume');
    const [statusFilter, setStatusFilter] = useState('ALL');

    const [filters, setFilters] = useState({
        startDate: '', endDate: '',
        openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [],
        rmList: [], teamLeaderList: [], sectorList: [],
        destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
        datePreset: 'MONTH'
    });

    const { startDate: boundsStart, endDate: boundsEnd, boundsLoaded } = useDataBounds();

    useEffect(() => {
        if (!boundsLoaded) return;
        setFilters(prev => ({ ...prev, datePreset: 'CUSTOM', startDate: boundsStart, endDate: boundsEnd }));
    }, [boundsLoaded, boundsStart, boundsEnd]);

    useEffect(() => {
        if (boundsLoaded) fetchData();
    }, [boundsLoaded]);

    const fetchData = async () => {
        setLoading(true);
        try {
            const res = await api.post('/business/attrition-report', filters);
            setData(res.data.map((r, i) => ({ id: r.mid || i, ...r })));
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const selectedYear = filters.endDate ? new Date(filters.endDate).getFullYear() : new Date().getFullYear();
    const prevYear = selectedYear - 1;
    const { suffix, kind } = METRICS[metric];

    // Helpers that read the active-metric value off a row.
    const val = (row, base) => row[`${base}${suffix}`];
    const fmtCount = (v) => v == null ? '-' : Number(v).toLocaleString('en-US');
    const fmtMeasure = (v) => v == null ? '-' : (kind === 'count' ? fmtCount(v) : fmt.currency(v));
    const pctFormatter = (v) => v == null ? '-' : `${v >= 0 ? '+' : ''}${Number(v).toFixed(1)}%`;

    // Status counts come from the whole portfolio (not the status-filtered view).
    const statusCounts = useMemo(() => {
        const c = { CHURNED: 0, AT_RISK: 0, DECLINING: 0, STABLE: 0, GROWING: 0 };
        data.forEach(d => { if (c[d.status] != null) c[d.status]++; });
        return c;
    }, [data]);

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalCur = data.reduce((s, d) => s + (Number(val(d, 'ytd_current')) || 0), 0);
        const totalPrev = data.reduce((s, d) => s + (Number(val(d, 'ytd_prev')) || 0), 0);
        const ytdChange = totalPrev > 0 ? ((totalCur - totalPrev) / totalPrev) * 100 : 0;
        const atRisk = statusCounts.CHURNED + statusCounts.AT_RISK;
        return [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: '#6366f1' },
            { title: 'Churned', value: statusCounts.CHURNED.toString(), icon: UserMinus, color: '#7c3aed',
              subtitle: `${data.length ? ((statusCounts.CHURNED / data.length) * 100).toFixed(0) : 0}% of portfolio` },
            { title: 'At Risk', value: atRisk.toString(), icon: AlertTriangle, color: '#dc2626',
              subtitle: 'churned + steep decline' },
            { title: 'Declining (YTD)', value: statusCounts.DECLINING.toString(), icon: TrendingDown, color: '#ea580c' },
            { title: `YTD ${METRICS[metric].label} Change`, value: `${ytdChange >= 0 ? '+' : ''}${ytdChange.toFixed(1)}%`,
              icon: DollarSign, color: ytdChange >= 0 ? '#10b981' : '#ef4444', trend: ytdChange,
              trendLabel: `${prevYear} vs ${selectedYear}` },
        ];
    }, [data, metric, statusCounts, selectedYear, prevYear]);

    const filteredData = useMemo(
        () => statusFilter === 'ALL' ? data : data.filter(d => d.status === statusFilter),
        [data, statusFilter]
    );

    const measureCell = (params) => (
        <Typography variant="body2" sx={{ color: '#475569' }}>{fmtMeasure(params.value)}</Typography>
    );
    const measureCellBold = (params) => (
        <Typography variant="body2" fontWeight="600" sx={{ color: '#0f172a' }}>{fmtMeasure(params.value)}</Typography>
    );
    const pctCell = (params) => (
        <Typography variant="body2" sx={{ fontWeight: 'bold', color: params.value < 0 ? '#ef4444' : params.value > 0 ? '#10b981' : '#cbd5e1' }}>
            {pctFormatter(params.value)}
        </Typography>
    );
    const statusCell = (params) => {
        const m = STATUS_META[params.value] || { label: params.value, color: '#475569', bg: '#f1f5f9' };
        return <Chip label={m.label} size="small" sx={{ bgcolor: m.bg, color: m.color, fontWeight: 700 }} />;
    };

    const columns = useMemo(() => [
        { field: 'mid', headerName: 'MID', width: 130,
            renderCell: (p) => <Typography variant="body2" sx={{ fontFamily: 'monospace', color: '#64748b' }}>{p.value}</Typography> },
        { field: 'merchant_info', headerName: 'MERCHANT NAME', width: 230,
            valueGetter: (v, row) => row.name,
            renderCell: (p) => <Typography variant="body2" sx={{ fontWeight: 600, color: '#0f172a' }}>{p.row.name}</Typography> },
        { field: 'status', headerName: 'STATUS', width: 130,
            valueGetter: (v, row) => row.status, renderCell: statusCell },
        // MoM (equal-length window vs one month earlier)
        { field: 'mom_prev_col', headerName: 'Prev Month', width: 120, type: 'number',
            valueGetter: (v, row) => val(row, 'mom_prev'), renderCell: measureCell },
        { field: 'mom_curr_col', headerName: 'Current', width: 120, type: 'number',
            valueGetter: (v, row) => val(row, 'mom_current'), renderCell: measureCellBold },
        { field: 'mom_pct_col', headerName: '% Change', width: 110, type: 'number',
            valueGetter: (v, row) => val(row, 'mom_pct'), renderCell: pctCell },
        // MTD YoY
        { field: 'mtd_prev_col', headerName: `${prevYear}`, width: 120, type: 'number',
            valueGetter: (v, row) => val(row, 'mtd_prev'), renderCell: measureCell },
        { field: 'mtd_curr_col', headerName: `${selectedYear}`, width: 120, type: 'number',
            valueGetter: (v, row) => val(row, 'mtd_current'), renderCell: measureCellBold },
        { field: 'mtd_pct_col', headerName: '% Change', width: 110, type: 'number',
            valueGetter: (v, row) => val(row, 'mtd_pct'), renderCell: pctCell },
        // YTD YoY
        { field: 'ytd_prev_col', headerName: `${prevYear}`, width: 120, type: 'number',
            valueGetter: (v, row) => val(row, 'ytd_prev'), renderCell: measureCell },
        { field: 'ytd_curr_col', headerName: `${selectedYear}`, width: 120, type: 'number',
            valueGetter: (v, row) => val(row, 'ytd_current'), renderCell: measureCellBold },
        { field: 'ytd_pct_col', headerName: '% Change', width: 110, type: 'number',
            valueGetter: (v, row) => val(row, 'ytd_pct'), renderCell: pctCell },
    ], [metric, selectedYear, prevYear]);

    const columnGroupingModel = [
        { groupId: 'mom_group', headerName: 'Month-on-Month', headerClassName: 'mom-header-group',
            children: [{ field: 'mom_prev_col' }, { field: 'mom_curr_col' }, { field: 'mom_pct_col' }] },
        { groupId: 'mtd_group', headerName: `MTD (${prevYear} vs ${selectedYear})`, headerClassName: 'mtd-header-group',
            children: [{ field: 'mtd_prev_col' }, { field: 'mtd_curr_col' }, { field: 'mtd_pct_col' }] },
        { groupId: 'ytd_group', headerName: `YTD (${prevYear} vs ${selectedYear})`, headerClassName: 'ytd-header-group',
            children: [{ field: 'ytd_prev_col' }, { field: 'ytd_curr_col' }, { field: 'ytd_pct_col' }] },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Attrition Report (MoM & YoY)" subtitle="Month-on-month and year-over-year comparison with churn classification"
                icon={Activity}
                onExport={() => exportToCSV(filteredData, 'attrition_report')}
                onRunReport={fetchData} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchData} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />

            {/* Metric toggle + status quick-filters */}
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }} justifyContent="space-between" sx={{ mb: 2 }}>
                <ToggleButtonGroup size="small" exclusive value={metric}
                    onChange={(e, v) => v && setMetric(v)} aria-label="metric">
                    {Object.entries(METRICS).map(([k, m]) => (
                        <ToggleButton key={k} value={k} sx={{ textTransform: 'none', fontWeight: 600 }}>{m.label}</ToggleButton>
                    ))}
                </ToggleButtonGroup>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                    {STATUS_ORDER.map(s => {
                        const meta = s === 'ALL' ? { label: 'All', color: '#1e293b', bg: '#e2e8f0' } : STATUS_META[s];
                        const count = s === 'ALL' ? data.length : (statusCounts[s] || 0);
                        const active = statusFilter === s;
                        return (
                            <Chip key={s} label={`${meta.label} (${count})`} size="small" clickable
                                onClick={() => setStatusFilter(s)}
                                sx={{
                                    fontWeight: 700,
                                    color: active ? '#fff' : meta.color,
                                    bgcolor: active ? meta.color : meta.bg,
                                    border: active ? `1px solid ${meta.color}` : '1px solid transparent',
                                }} />
                        );
                    })}
                </Stack>
            </Stack>

            <Paper sx={{
                ...premiumTableWrapper,
                '& .mom-header-group': { bgcolor: '#fef3c7', color: '#92400e', fontWeight: 'bold' },
                '& .mtd-header-group': { bgcolor: '#eff6ff', color: '#1e40af', fontWeight: 'bold' },
                '& .ytd-header-group': { bgcolor: '#f8fafc', color: '#334155', fontWeight: 'bold' }
            }}>
                <DataGrid
                    rows={filteredData} columns={columns} columnGroupingModel={columnGroupingModel}
                    loading={loading} disableRowSelectionOnClick rowHeight={60}
                    initialState={{
                        pagination: { paginationModel: { pageSize: 25 } },
                        sorting: { sortModel: [{ field: 'ytd_pct_col', sort: 'asc' }] },
                    }}
                    pageSizeOptions={[25, 50, 100]}
                    experimentalFeatures={{ columnGrouping: true }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default AttritionReport;
