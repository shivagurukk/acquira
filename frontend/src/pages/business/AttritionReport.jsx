import React, { useState, useEffect, useMemo } from 'react';
import { Box, Paper, Typography } from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { Activity, TrendingDown, TrendingUp, Users, DollarSign } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const AttritionReport = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);

    const today = new Date();
    const firstDay = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().split('T')[0];
    const lastDay = today.toISOString().split('T')[0];

    const [filters, setFilters] = useState({
        startDate: firstDay, endDate: lastDay,
        openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [],
        rmList: [], teamLeaderList: [], sectorList: [],
        destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
        datePreset: 'MONTH'
    });

    useEffect(() => { fetchData(); }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/attrition-report', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                body: JSON.stringify(filters)
            });
            if (res.ok) {
                const result = await res.json();
                setData(result.map((r, i) => ({ id: r.mid || i, ...r })));
            }
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const selectedYear = filters.endDate ? new Date(filters.endDate).getFullYear() : new Date().getFullYear();
    const prevYear = selectedYear - 1;

    const kpis = useMemo(() => {
        if (!data.length) return [];
        const declining = data.filter(d => (d.ytd_pct || 0) < 0).length;
        const growing = data.filter(d => (d.ytd_pct || 0) > 0).length;
        const totalCurrYtd = data.reduce((s, d) => s + (d.ytd_current || 0), 0);
        const totalPrevYtd = data.reduce((s, d) => s + (d.ytd_prev || 0), 0);
        const ytdChange = totalPrevYtd > 0 ? ((totalCurrYtd - totalPrevYtd) / totalPrevYtd) * 100 : 0;
        return [
            { title: 'Total Merchants', value: data.length.toString(), icon: Users, color: '#6366f1' },
            { title: 'Declining (YTD)', value: declining.toString(), icon: TrendingDown, color: '#ef4444', subtitle: `${data.length > 0 ? ((declining / data.length) * 100).toFixed(0) : 0}% of total` },
            { title: 'Growing (YTD)', value: growing.toString(), icon: TrendingUp, color: '#10b981', subtitle: `${data.length > 0 ? ((growing / data.length) * 100).toFixed(0) : 0}% of total` },
            { title: 'YTD Volume Change', value: `${ytdChange >= 0 ? '+' : ''}${ytdChange.toFixed(1)}%`, icon: DollarSign, color: ytdChange >= 0 ? '#10b981' : '#ef4444', trend: ytdChange, trendLabel: `${prevYear} vs ${selectedYear}` },
        ];
    }, [data, selectedYear, prevYear]);

    const currencyFormatter = (value) => value == null ? '-' : new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', notation: 'compact' }).format(value);
    const pctFormatter = (value) => value == null ? '-' : `${value.toFixed(1)}%`;

    const columns = [
        {
            field: 'mid', headerName: 'MID', width: 140,
            renderCell: (params) => <Typography variant="body2" sx={{ fontFamily: 'monospace', color: '#64748b' }}>{params.value}</Typography>
        },
        {
            field: 'merchant_info', headerName: 'MERCHANT NAME', width: 250,
            renderCell: (params) => <Typography variant="body2" sx={{ fontWeight: 600, color: '#0f172a' }}>{params.row.name}</Typography>
        },
        { field: 'mtd_prev', headerName: `${prevYear} Vol`, width: 120, type: 'number', renderCell: (params) => <Typography variant="body2" sx={{ color: '#475569' }}>{currencyFormatter(params.value)}</Typography> },
        { field: 'mtd_current', headerName: `${selectedYear} Vol`, width: 120, type: 'number', renderCell: (params) => <Typography variant="body2" fontWeight="600" sx={{ color: '#0f172a' }}>{currencyFormatter(params.value)}</Typography> },
        {
            field: 'mtd_pct', headerName: '% Change', width: 120, type: 'number',
            renderCell: (params) => <Typography variant="body2" sx={{ fontWeight: 'bold', color: params.value < 0 ? '#ef4444' : params.value > 0 ? '#10b981' : '#cbd5e1' }}>{pctFormatter(params.value)}</Typography>
        },
        { field: 'ytd_prev', headerName: `${prevYear} Vol`, width: 120, type: 'number', renderCell: (params) => <Typography variant="body2" sx={{ color: '#475569' }}>{currencyFormatter(params.value)}</Typography> },
        { field: 'ytd_current', headerName: `${selectedYear} Vol`, width: 120, type: 'number', renderCell: (params) => <Typography variant="body2" fontWeight="600" sx={{ color: '#0f172a' }}>{currencyFormatter(params.value)}</Typography> },
        {
            field: 'ytd_pct', headerName: '% Change', width: 120, type: 'number',
            renderCell: (params) => <Typography variant="body2" sx={{ fontWeight: 'bold', color: params.value < 0 ? '#ef4444' : params.value > 0 ? '#10b981' : '#cbd5e1' }}>{pctFormatter(params.value)}</Typography>
        }
    ];

    const columnGroupingModel = [
        { groupId: 'mtd_group', headerName: `MTD Comparison (${prevYear} vs ${selectedYear})`, headerClassName: 'mtd-header-group', children: [{ field: 'mtd_prev' }, { field: 'mtd_current' }, { field: 'mtd_pct' }] },
        { groupId: 'ytd_group', headerName: `YTD Comparison (${prevYear} vs ${selectedYear})`, headerClassName: 'ytd-header-group', children: [{ field: 'ytd_prev' }, { field: 'ytd_current' }, { field: 'ytd_pct' }] }
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Attrition Report (YoY)" subtitle="Year-over-year volume comparison performance"
                icon={Activity}
                onExport={() => exportToCSV(data, 'attrition_report')}
                onRunReport={fetchData} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={fetchData} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />
            <Paper sx={{
                ...premiumTableWrapper,
                '& .mtd-header-group': { bgcolor: '#eff6ff', color: '#1e40af', fontWeight: 'bold' },
                '& .ytd-header-group': { bgcolor: '#f8fafc', color: '#334155', fontWeight: 'bold' }
            }}>
                <DataGrid
                    rows={data} columns={columns} columnGroupingModel={columnGroupingModel}
                    loading={loading} disableRowSelectionOnClick rowHeight={60}
                    initialState={{ pagination: { paginationModel: { pageSize: 25 } } }}
                    pageSizeOptions={[25, 50, 100]}
                    experimentalFeatures={{ columnGrouping: true }}
                    sx={premiumDataGridStyles}
                />
            </Paper>
        </Box>
    );
};

export default AttritionReport;
