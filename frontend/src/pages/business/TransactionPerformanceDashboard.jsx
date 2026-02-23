import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Box, Paper, Typography, Stack, IconButton, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { ChevronRight, ChevronDown, TrendingUp, Layers, DollarSign, Hash, CreditCard } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'decimal', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);
const formatMonth = (dateStr) => {
    if (!dateStr || !dateStr.includes('-')) return dateStr;
    if (/^\d{4}-\d{2}$/.test(dateStr)) {
        const [year, month] = dateStr.split('-');
        return new Date(parseInt(year), parseInt(month) - 1).toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
    }
    return dateStr;
};

const TransactionPerformanceDashboard = () => {
    const [rows, setRows] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState(() => {
        const now = new Date();
        const fmt = (d) => d.toISOString().split('T')[0];
        return { datePreset: 'YEAR', startDate: fmt(new Date(now.getFullYear(), 0, 1)), endDate: fmt(now) };
    });
    const [expanded, setExpanded] = useState({});

    const fetchApiData = async (groupBy, parentValue, grandParentValue) => {
        const token = localStorage.getItem('token');
        const tenantId = localStorage.getItem('defaultTenantId');
        const queryParams = new URLSearchParams({ groupBy, parentValue: parentValue || '', grandParentValue: grandParentValue || '' });
        // Resolve datePreset to real dates before sending
        const body = { ...filters };
        if (body.datePreset && body.datePreset !== 'CUSTOM' && (!body.startDate || !body.endDate)) {
            const now = new Date();
            const fmt = (d) => d.toISOString().split('T')[0];
            if (body.datePreset === 'YEAR') { body.startDate = fmt(new Date(now.getFullYear(), 0, 1)); body.endDate = fmt(now); }
            else if (body.datePreset === 'MONTH') { body.startDate = fmt(new Date(now.getFullYear(), now.getMonth(), 1)); body.endDate = fmt(now); }
        }
        delete body.datePreset;
        const res = await fetch(`/api/business/performance-dashboard?${queryParams}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) },
            body: JSON.stringify(body)
        });
        if (res.ok) return await res.json();
        return [];
    };

    useEffect(() => { loadInitialData(); }, []);

    const loadInitialData = async () => {
        setLoading(true);
        try {
            const data = await fetchApiData('MONTH', null, null);
            const formatedRows = data.map((d, i) => ({
                id: `MONTH-${d.row_label}`, level: 0, groupKey: 'MONTH',
                label: formatMonth(d.row_label), rawValue: d.row_label, ...d
            }));
            setRows(formatedRows);
            setExpanded({});
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    const handleFilterChange = (keyOrObj, val) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
        else setFilters(prev => ({ ...prev, [keyOrObj]: val }));
    };

    const handleToggle = async (row) => {
        const isCurrentlyExpanded = expanded[row.id];
        if (isCurrentlyExpanded) {
            const rowIndex = rows.findIndex(r => r.id === row.id);
            if (rowIndex === -1) return;
            let countToRemove = 0;
            for (let i = rowIndex + 1; i < rows.length; i++) {
                if (rows[i].level > row.level) countToRemove++; else break;
            }
            const newRows = [...rows];
            newRows.splice(rowIndex + 1, countToRemove);
            setRows(newRows);
            const newExpanded = { ...expanded }; delete newExpanded[row.id]; setExpanded(newExpanded);
        } else {
            let nextGroupBy = '', nextParent = '', nextGrandParent = null;
            if (row.level === 0) { nextGroupBy = 'DAY'; nextParent = row.rawValue; }
            else if (row.level === 1) { nextGroupBy = 'MERCHANT'; nextParent = row.rawValue; }
            else if (row.level === 2) { nextGroupBy = 'STORE'; nextParent = row.mid; nextGrandParent = row.parentDay; }
            else return;
            setExpanded(prev => ({ ...prev, [row.id]: true }));
            try {
                const children = await fetchApiData(nextGroupBy, nextParent, nextGrandParent);
                const newChildren = children.map(c => {
                    let id = '', label = '';
                    if (row.level === 0) { id = `DAY-${c.row_label}`; label = c.row_label; }
                    else if (row.level === 1) { id = `MERCHANT-${c.mid}-${row.rawValue}`; label = c.merchant_name ? `${c.merchant_name} (${c.mid})` : `MID: ${c.mid}`; }
                    else if (row.level === 2) { id = `STORE-${c.sid}-${row.rawValue}`; label = `SID: ${c.sid}`; }
                    return { ...c, id, level: row.level + 1, label, rawValue: c.row_label || c.mid || c.sid, parentDay: row.level === 1 ? row.rawValue : row.parentDay };
                });
                const rowIndex = rows.findIndex(r => r.id === row.id);
                const newRows = [...rows];
                newRows.splice(rowIndex + 1, 0, ...newChildren);
                setRows(newRows);
            } catch (err) {
                console.error(err);
                setExpanded(prev => { const n = { ...prev }; delete n[row.id]; return n; });
            }
        }
    };

    // KPI from top-level rows
    const kpis = useMemo(() => {
        const topRows = rows.filter(r => r.level === 0);
        if (!topRows.length) return [];
        const totalVol = topRows.reduce((s, r) => s + (r.total_vol || 0), 0);
        const totalDebit = topRows.reduce((s, r) => s + (r.dom_debit_vol || 0), 0);
        const totalCredit = topRows.reduce((s, r) => s + (r.dom_credit_vol || 0), 0);
        const totalIntl = topRows.reduce((s, r) => s + (r.int_vol || 0), 0);
        return [
            { title: 'Total Volume', value: `AED ${formatCompact(totalVol)}`, icon: TrendingUp, color: '#6366f1', sparkData: topRows.slice().reverse().map(r => r.total_vol || 0) },
            { title: 'Domestic Debit', value: `AED ${formatCompact(totalDebit)}`, icon: CreditCard, color: '#3b82f6', subtitle: `${totalVol > 0 ? ((totalDebit / totalVol) * 100).toFixed(1) : 0}% of total` },
            { title: 'Domestic Credit', value: `AED ${formatCompact(totalCredit)}`, icon: DollarSign, color: '#10b981', subtitle: `${totalVol > 0 ? ((totalCredit / totalVol) * 100).toFixed(1) : 0}% of total` },
            { title: 'International', value: `AED ${formatCompact(totalIntl)}`, icon: Layers, color: '#f59e0b', subtitle: `${totalVol > 0 ? ((totalIntl / totalVol) * 100).toFixed(1) : 0}% of total` },
        ];
    }, [rows]);

    const columns = [
        {
            field: 'entity', headerName: 'PERIOD / ENTITY', width: 280, sortable: false,
            renderCell: (params) => {
                const { row } = params;
                const isExp = !!expanded[row.id];
                const canExpand = row.level < 3;
                return (
                    <Box sx={{ pl: row.level * 3, display: 'flex', alignItems: 'center', gap: 1 }}>
                        {canExpand && (
                            <IconButton size="small" onClick={(e) => { e.stopPropagation(); handleToggle(row); }}
                                sx={{ color: '#64748b', '&:hover': { bgcolor: '#f1f5f9' } }}>
                                {isExp ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                            </IconButton>
                        )}
                        <Typography variant="body2" fontWeight={row.level === 0 ? 700 : 500} color="text.primary">{row.label}</Typography>
                    </Box>
                );
            }
        },
        { field: 'dom_debit_cnt', headerName: 'Count', type: 'number', width: 90, valueFormatter: (value) => formatNumber(value) },
        { field: 'dom_debit_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_debit_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_debit_pct', headerName: '%', width: 70, valueGetter: (value, row) => ((row.dom_debit_vol / row.total_vol) * 100 || 0).toFixed(1) + '%' },
        { field: 'dom_debit_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_credit_cnt', headerName: 'Count', type: 'number', width: 90, valueFormatter: (value) => formatNumber(value) },
        { field: 'dom_credit_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_credit_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_credit_pct', headerName: '%', width: 70, valueGetter: (value, row) => ((row.dom_credit_vol / row.total_vol) * 100 || 0).toFixed(1) + '%' },
        { field: 'dom_credit_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },
        { field: 'int_cnt', headerName: 'Count', type: 'number', width: 90, valueFormatter: (value) => formatNumber(value) },
        { field: 'int_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value) },
        { field: 'int_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'int_pct', headerName: '%', width: 70, valueGetter: (value, row) => ((row.int_vol / row.total_vol) * 100 || 0).toFixed(1) + '%' },
        { field: 'int_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },
        {
            field: 'total_vol', headerName: 'TOTAL VOL', type: 'number', width: 140,
            renderCell: (params) => <Typography fontWeight="700" color="primary.main">{formatCurrency(params.value)}</Typography>
        },
    ];

    const columnGroupingModel = [
        { groupId: 'DomesticDebit', headerName: 'Domestic Debit & Prepaid', headerClassName: 'super-header-debit', children: [{ field: 'dom_debit_cnt' }, { field: 'dom_debit_vol' }, { field: 'dom_debit_msf' }, { field: 'dom_debit_pct' }, { field: 'dom_debit_optin' }] },
        { groupId: 'DomesticCredit', headerName: 'Domestic Credit', headerClassName: 'super-header-credit', children: [{ field: 'dom_credit_cnt' }, { field: 'dom_credit_vol' }, { field: 'dom_credit_msf' }, { field: 'dom_credit_pct' }, { field: 'dom_credit_optin' }] },
        { groupId: 'International', headerName: 'International', headerClassName: 'super-header-intl', children: [{ field: 'int_cnt' }, { field: 'int_vol' }, { field: 'int_msf' }, { field: 'int_pct' }, { field: 'int_optin' }] },
    ];

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Transaction Performance" subtitle="Drill-down: Month → Day → Merchant → Store"
                icon={TrendingUp}
                onExport={() => exportToCSV(rows, 'transaction_performance')}
                onRunReport={loadInitialData} onFilterChange={handleFilterChange}
                loading={loading} showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)} filters={filters}
            />
            <BusinessFilters filters={filters} onChange={setFilters} onApply={loadInitialData} isOpen={showFilters} onClose={() => setShowFilters(false)} />
            <KpiCards cards={kpis} />
            <Paper sx={{
                ...premiumTableWrapper,
                '& .super-header-debit': { bgcolor: '#eff6ff', color: '#1e40af', fontWeight: '700' },
                '& .super-header-credit': { bgcolor: '#f0fdf4', color: '#15803d', fontWeight: '700' },
                '& .super-header-intl': { bgcolor: '#fff7ed', color: '#c2410c', fontWeight: '700' },
            }}>
                <DataGrid
                    rows={rows} columns={columns} columnGroupingModel={columnGroupingModel}
                    loading={loading} disableRowSelectionOnClick
                    experimentalFeatures={{ columnGrouping: true }} rowHeight={50}
                    slots={{ toolbar: GridToolbar }}
                    sx={{ ...premiumDataGridStyles,
                        '& .row-level-1': { bgcolor: '#f8fafc !important' },
                        '& .row-level-2': { bgcolor: '#f1f5f9 !important' },
                        '& .row-level-3': { bgcolor: '#e2e8f0 !important' },
                    }}
                    getRowClassName={(params) => `row-level-${params.row.level}`}
                />
            </Paper>
        </Box>
    );
};

export default TransactionPerformanceDashboard;
