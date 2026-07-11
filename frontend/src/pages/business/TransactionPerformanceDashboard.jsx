import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Box, Paper, Typography, Stack, IconButton, Chip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { ChevronRight, ChevronDown, TrendingUp, Layers, DollarSign, Hash, CreditCard, Users, Percent, Receipt, Monitor, Globe } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import SkeletonLoader from '../../components/SkeletonLoader';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useAuth } from '../../contexts/AuthContext';

const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'decimal', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(val || 0);
const formatCompact = (val) => new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);
// % of total, guarded: a zero or negative total (refund-dominated cell) has no
// meaningful share — show an em dash instead of Infinity / NaN / negative %.
const pctOfTotal = (part, total) => {
    const t = Number(total), p = Number(part);
    if (!isFinite(t) || t <= 0) return '—';
    return ((p / t) * 100).toFixed(1) + '%';
};
// Negative volume cells (refund netting) get flagged red instead of looking broken.
const negClass = (params) => (Number(params.value) < 0 ? 'neg-vol' : '');
const formatMonth = (dateStr) => {
    if (!dateStr || !dateStr.includes('-')) return dateStr;
    if (/^\d{4}-\d{2}$/.test(dateStr)) {
        const [year, month] = dateStr.split('-');
        return new Date(parseInt(year), parseInt(month) - 1).toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
    }
    return dateStr;
};

const TransactionPerformanceDashboard = () => {
    const { tenantVersion, currencySymbol } = useAuth();
    const [rows, setRows] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState(() => {
        const now = new Date();
        // Local-date formatter — toISOString() shifts dates by one day in non-UTC
        // timezones. See PremiumReportHeader for the full bug explanation.
        const fmt = (d) => {
            const yr = d.getFullYear();
            const mo = String(d.getMonth() + 1).padStart(2, '0');
            const dy = String(d.getDate()).padStart(2, '0');
            return `${yr}-${mo}-${dy}`;
        };
        return { datePreset: 'YEAR', startDate: fmt(new Date(now.getFullYear(), 0, 1)), endDate: fmt(now) };
    });
    const [expanded, setExpanded] = useState({});
    // Single-row KPI totals from the backend TOTAL grain (whole filtered range).
    // Needed because Active Merchants (COUNT DISTINCT) and POS/ECOM splits
    // cannot be derived from the monthly rows.
    const [kpiTotals, setKpiTotals] = useState(null);

    const fetchApiData = async (groupBy, parentValue, grandParentValue) => {
        const token = localStorage.getItem('token');
        const tenantId = localStorage.getItem('defaultTenantId');
        const queryParams = new URLSearchParams({ groupBy, parentValue: parentValue || '', grandParentValue: grandParentValue || '' });
        // Resolve datePreset to real dates before sending
        const body = { ...filters };
        if (body.datePreset && body.datePreset !== 'CUSTOM' && (!body.startDate || !body.endDate)) {
            const now = new Date();
            // Local-date formatter — same timezone bug fix as above.
            const fmt = (d) => {
                const yr = d.getFullYear();
                const mo = String(d.getMonth() + 1).padStart(2, '0');
                const dy = String(d.getDate()).padStart(2, '0');
                return `${yr}-${mo}-${dy}`;
            };
            if (body.datePreset === 'YEAR') { body.startDate = fmt(new Date(now.getFullYear(), 0, 1)); body.endDate = fmt(now); }
            else if (body.datePreset === 'MONTH') { body.startDate = fmt(new Date(now.getFullYear(), now.getMonth(), 1)); body.endDate = fmt(now); }
            else if (body.datePreset === 'LAST_MONTH') {
                body.startDate = fmt(new Date(now.getFullYear(), now.getMonth() - 1, 1));
                body.endDate   = fmt(new Date(now.getFullYear(), now.getMonth(), 0));
            }
            else if (body.datePreset === 'TODAY') { body.startDate = fmt(now); body.endDate = fmt(now); }
            else if (body.datePreset === 'PY') {
                body.startDate = fmt(new Date(now.getFullYear() - 1, 0, 1));
                body.endDate   = fmt(new Date(now.getFullYear() - 1, 11, 31));
            }
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

    useEffect(() => { loadInitialData(); }, [tenantVersion]);

    const loadInitialData = async () => {
        setLoading(true);
        try {
            // TOTAL grain fetched in parallel; .catch keeps the grid alive if the
            // backend predates the TOTAL branch (tiles fall back to month sums).
            const [data, totalData] = await Promise.all([
                fetchApiData('MONTH', null, null),
                fetchApiData('TOTAL', null, null).catch(() => []),
            ]);
            const formatedRows = data.map((d, i) => ({
                id: `MONTH-${d.row_label}`, level: 0, groupKey: 'MONTH',
                label: formatMonth(d.row_label), rawValue: d.row_label, ...d
            }));
            setRows(formatedRows);
            setKpiTotals(Array.isArray(totalData) && totalData.length ? totalData[0] : null);
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

    // KPI tiles. Volume/MSF/count come from the TOTAL grain when available
    // (falls back to summing top-level month rows — bucket counts are an
    // exhaustive partition, so their sum IS total transactions). Active
    // Merchants and POS/ECOM exist only on the TOTAL grain.
    const kpis = useMemo(() => {
        const topRows = rows.filter(r => r.level === 0);
        if (!topRows.length && !kpiTotals) return [];
        const sumTop = (fn) => topRows.reduce((s, r) => s + (fn(r) || 0), 0);
        const totalVol = kpiTotals ? Number(kpiTotals.total_vol || 0) : sumTop(r => r.total_vol);
        const totalMsf = kpiTotals ? Number(kpiTotals.total_msf || 0) : sumTop(r => r.total_msf);
        const totalCnt = kpiTotals ? Number(kpiTotals.total_cnt || 0)
            : sumTop(r => (r.dom_debit_cnt || 0) + (r.dom_credit_cnt || 0) + (r.int_cnt || 0));
        const activeMerchants = kpiTotals ? Number(kpiTotals.active_merchants || 0) : null;
        const posVol = kpiTotals ? Number(kpiTotals.pos_vol || 0) : null;
        const ecomVol = kpiTotals ? Number(kpiTotals.ecom_vol || 0) : null;
        // Ratio guards: refund-heavy windows can make totals zero/negative —
        // show an em dash rather than Infinity / a nonsense negative rate.
        const avgTicket = totalCnt > 0 && totalVol > 0 ? totalVol / totalCnt : null;
        const takeRate = totalVol > 0 ? (totalMsf / totalVol) * 100 : null;
        return [
            { title: 'Total Volume', value: `${currencySymbol} ${formatCompact(totalVol)}`, icon: TrendingUp, color: '#6366f1', subtitle: 'Total processed amount', sparkData: topRows.slice().reverse().map(r => r.total_vol || 0) },
            { title: 'Total Transactions', value: formatNumber(totalCnt), icon: Hash, color: '#3b82f6', subtitle: 'Total transaction count' },
            { title: 'Total MSF', value: `${currencySymbol} ${formatCompact(totalMsf)}`, icon: DollarSign, color: '#10b981', subtitle: 'Merchant service fee revenue' },
            { title: 'Average Ticket', value: avgTicket !== null ? `${currencySymbol} ${formatCurrency(avgTicket)}` : '—', icon: Receipt, color: '#8b5cf6', subtitle: 'Volume ÷ transactions' },
            { title: 'Take Rate', value: takeRate !== null ? `${takeRate.toFixed(2)}%` : '—', icon: Percent, color: '#f59e0b', subtitle: 'MSF ÷ volume' },
            { title: 'Active Merchants', value: activeMerchants !== null ? formatNumber(activeMerchants) : '—', icon: Users, color: '#06b6d4', subtitle: 'With transactions in period' },
            { title: 'POS Volume', value: posVol !== null ? `${currencySymbol} ${formatCompact(posVol)}` : '—', icon: Monitor, color: '#0ea5e9', subtitle: posVol !== null ? `${pctOfTotal(posVol, totalVol)} of total · terminal-based` : 'Terminal-based volume' },
            { title: 'ECOM Volume', value: ecomVol !== null ? `${currencySymbol} ${formatCompact(ecomVol)}` : '—', icon: Globe, color: '#ec4899', subtitle: ecomVol !== null ? `${pctOfTotal(ecomVol, totalVol)} of total · online` : 'Online volume' },
        ];
    }, [rows, kpiTotals, currencySymbol]);

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
        { field: 'dom_debit_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value), cellClassName: negClass },
        { field: 'dom_debit_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_debit_pct', headerName: '%', width: 70, valueGetter: (value, row) => pctOfTotal(row.dom_debit_vol, row.total_vol) },
        { field: 'dom_debit_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_credit_cnt', headerName: 'Count', type: 'number', width: 90, valueFormatter: (value) => formatNumber(value) },
        { field: 'dom_credit_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value), cellClassName: negClass },
        { field: 'dom_credit_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_credit_pct', headerName: '%', width: 70, valueGetter: (value, row) => pctOfTotal(row.dom_credit_vol, row.total_vol) },
        { field: 'dom_credit_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },
        { field: 'int_cnt', headerName: 'Count', type: 'number', width: 90, valueFormatter: (value) => formatNumber(value) },
        { field: 'int_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value), cellClassName: negClass },
        { field: 'int_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'int_pct', headerName: '%', width: 70, valueGetter: (value, row) => pctOfTotal(row.int_vol, row.total_vol) },
        { field: 'int_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },
        {
            field: 'total_vol', headerName: 'TOTAL VOL', type: 'number', width: 140,
            renderCell: (params) => <Typography fontWeight="700" sx={{ color: Number(params.value) < 0 ? 'var(--danger, #dc2626)' : 'var(--brand, #4f46e5)' }}>{formatCurrency(params.value)}</Typography>
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
            {loading ? <SkeletonLoader variant="kpi-row" count={8} /> : <KpiCards cards={kpis} />}
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
                        '& .neg-vol': { color: 'var(--danger, #dc2626) !important', fontWeight: 600 },
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
