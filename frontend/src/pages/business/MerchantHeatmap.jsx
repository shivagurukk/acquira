import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography, Stack, MenuItem, Select, FormControl, InputLabel, Avatar, Autocomplete, TextField, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { Grid as GridIcon, TrendingUp, DollarSign, Users } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', maximumFractionDigits: 0 }).format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const MerchantHeatmap = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [years, setYears] = useState([new Date().getFullYear()]);
    const [year, setYear] = useState(new Date().getFullYear());
    const [maxVolume, setMaxVolume] = useState(0);
    const [sidList, setSidList] = useState([]);
    const [sidOptions, setSidOptions] = useState([]);
    const [showFilters, setShowFilters] = useState(false);
    // Full-feature filter set (matches the rest of the business screens). The
    // heatmap previously only had a Year+SID inline picker; this adds Partners,
    // MCC, RM, Schemes, Card Types, Channels etc. via the BusinessFilters drawer.
    const [filters, setFilters] = useState({
        startDate: '', endDate: '',
        partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
        sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
    });

    useEffect(() => {
        // Build year list dynamically: current year and a few back. Was hardcoded
        // [2024, 2025, 2026] which would silently stop showing the current year
        // once the calendar advances.
        const cy = new Date().getFullYear();
        setYears([cy - 2, cy - 1, cy]);
        fetchSidOptions();
    }, []);
    useEffect(() => { fetchData(); }, [year, sidList, filters]);

    const fetchSidOptions = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            // Pass tenant header explicitly so dropdown is scoped to the user's tenant.
            const res = await fetch('/api/business/filter-options', {
                headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
            });
            if (res.ok) {
                const data = await res.json();
                setSidOptions((data.sids || []).map(s => String(s)));
            }
        } catch (e) { console.error(e); }
    };

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            // Inline SID picker takes precedence over the drawer's sidList.
            const effSidList = sidList.length > 0 ? sidList : (filters.sidList || []);
            // Build the filter body. Send empty arrays/strings for unset filters so
            // the backend treats them as "not specified" and skips the WHERE clause.
            const body = {
                ...filters,
                sidList: effSidList,
                // The heatmap is year-scoped; the date range is implied by `year`.
                // Sending null start/end avoids a redundant WHERE business_date BETWEEN.
                startDate: null,
                endDate: null,
            };
            const response = await fetch(`/api/analytics/heatmap-filtered?year=${year}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`,
                    ...(tenantId ? { 'X-Tenant-Id': tenantId } : {})
                },
                body: JSON.stringify(body),
            });
            if (response.ok) processData(await response.json());
            else {
                console.error('heatmap-filtered failed', response.status, await response.text());
                processData([]);
            }
        } catch (error) { console.error("Failed to fetch heatmap data", error); }
        finally { setLoading(false); }
    };

    const processData = (rawData) => {
        if (!rawData) return;
        let grouped = {}, maxVol = 0;
        rawData.forEach(row => {
            const key = row.merchantId;
            if (!grouped[key]) grouped[key] = { id: row.merchantId, merchantName: row.merchantName, merchantId: row.merchantId, volumes: {}, total: 0 };
            grouped[key].volumes[row.month] = row.totalVolume;
            grouped[key].total += row.totalVolume;
            if (row.totalVolume > maxVol) maxVol = row.totalVolume;
        });
        const rows = Object.values(grouped).map(item => {
            const flattened = { id: item.id, merchantName: item.merchantName, merchantId: item.merchantId, total: item.total };
            for (let i = 1; i <= 12; i++) flattened[`month_${i}`] = item.volumes[i] || 0;
            return flattened;
        });
        setData(rows.sort((a, b) => a.merchantName.localeCompare(b.merchantName)));
        setMaxVolume(maxVol);
    };

    const getHeatmapColor = (value) => {
        if (!value || maxVolume === 0) return { bg: '#ffffff', color: '#9ca3af' };
        const pct = value / maxVolume;
        if (pct > 0.75) return { bg: '#14532d', color: '#ffffff' };
        if (pct > 0.50) return { bg: '#16a34a', color: '#ffffff' };
        if (pct > 0.35) return { bg: '#22c55e', color: '#ffffff' };
        if (pct > 0.15) return { bg: '#86efac', color: '#064e3b' };
        return { bg: '#dcfce7', color: '#14532d' };
    };

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol = data.reduce((s, d) => s + (d.total || 0), 0);
        const activeMonths = data.reduce((s, d) => { let c = 0; for (let i = 1; i <= 12; i++) if (d[`month_${i}`] > 0) c++; return s + c; }, 0);
        return [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: '#6366f1' },
            { title: 'Total Annual Volume', value: `AED ${formatCompact(totalVol)}`, icon: DollarSign, color: '#10b981' },
            { title: 'Active Merchant-Months', value: activeMonths.toString(), icon: GridIcon, color: '#3b82f6' },
            { title: 'Avg per Merchant', value: `AED ${formatCompact(data.length > 0 ? totalVol / data.length : 0)}`, icon: TrendingUp, color: '#f59e0b' },
        ];
    }, [data]);

    const columns = [
        {
            field: 'merchantId', headerName: 'MID', width: 120,
            renderCell: (params) => <Typography variant="body2" color="#64748b" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>{params.value}</Typography>
        },
        {
            field: 'merchantName', headerName: 'MERCHANT NAME', width: 220,
            renderCell: (params) => (
                <Stack direction="row" spacing={1.5} alignItems="center" sx={{ height: '100%' }}>
                    <Avatar sx={{ bgcolor: '#f1f5f9', color: '#475569', fontWeight: 'bold', width: 28, height: 28, fontSize: '0.7rem' }}>
                        {params.value ? params.value.charAt(0) : '?'}
                    </Avatar>
                    <Typography variant="body2" fontWeight="700" color="#0f172a" noWrap title={params.value}>{params.value}</Typography>
                </Stack>
            )
        },
        ...Array.from({ length: 12 }, (_, i) => i + 1).map(month => ({
            field: `month_${month}`,
            headerName: new Date(0, month - 1).toLocaleString('en-US', { month: 'short' }).toUpperCase(),
            width: 95, align: 'center', headerAlign: 'center',
            renderCell: (params) => {
                const val = params.value;
                const style = getHeatmapColor(val);
                return (
                    <Box sx={{ width: '100%', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', bgcolor: style.bg, color: style.color, fontWeight: '700', fontSize: '0.7rem' }}>
                        {val > 0 ? formatCurrency(val) : ''}
                    </Box>
                );
            }
        })),
        {
            field: 'total', headerName: 'TOTAL', width: 140, align: 'right', headerAlign: 'right',
            renderCell: (params) => <Typography color="#0f172a" fontWeight="800" variant="body2">{formatCurrency(params.value)}</Typography>
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
            <Autocomplete
                multiple freeSolo size="small"
                options={sidOptions} value={sidList}
                onChange={(e, val) => setSidList(val)}
                renderInput={(params) => <TextField {...params} label="Filter by SID" placeholder={sidList.length ? '' : 'All Stores'} sx={{ minWidth: 250 }} />}
                renderTags={(value, getTagProps) =>
                    value.map((option, index) => (
                        <Chip {...getTagProps({ index })} key={option} label={option} size="small"
                            sx={{ bgcolor: '#3B82F6', color: 'white', fontWeight: 600, '& .MuiChip-deleteIcon': { color: 'white', opacity: 0.7 } }} />
                    ))
                }
            />
        </Stack>
    );

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Growth Heatmap" subtitle={`Annual performance visualization — ${year}`}
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
                onApply={fetchData}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            <KpiCards cards={kpis} />

            <Paper elevation={0} sx={{ p: 1.5, mb: 2, borderRadius: '10px', display: 'flex', alignItems: 'center', gap: 3, bgcolor: 'white', border: '1px solid #e2e8f0' }}>
                <Typography variant="caption" fontWeight="bold" color="#64748b">LEGEND:</Typography>
                {[
                    { color: '#14532d', label: 'High' }, { color: '#16a34a', label: 'Good' },
                    { color: '#22c55e', label: 'Med' }, { color: '#86efac', label: 'Low' },
                    { color: '#dcfce7', label: 'Min' }
                ].map(type => (
                    <Stack key={type.label} direction="row" spacing={0.75} alignItems="center">
                        <Box sx={{ width: 10, height: 10, borderRadius: '3px', bgcolor: type.color }} />
                        <Typography variant="caption" fontWeight="600" color="#64748b">{type.label}</Typography>
                    </Stack>
                ))}
            </Paper>

            <Paper elevation={0} sx={{ ...premiumTableWrapper, border: '1px solid #e2e8f0' }}>
                <DataGrid
                    rows={data} columns={columns} loading={loading} disableRowSelectionOnClick
                    rowHeight={50}
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true, quickFilterProps: { debounceMs: 500 } } }}
                    sx={{ ...premiumDataGridStyles,
                        '& .MuiDataGrid-cell': { borderBottom: '1px solid #f1f5f9', padding: 0 },
                        '& .MuiDataGrid-row:hover': { bgcolor: 'transparent' },
                    }}
                />
            </Paper>
        </Box>
    );
};

export default MerchantHeatmap;
