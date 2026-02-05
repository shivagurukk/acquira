import React, { useState, useEffect, useCallback } from 'react';
import {
    Box,
    Paper,
    Typography,
    Stack,
    IconButton,
    CircularProgress,
    Chip
} from '@mui/material';
import { DataGrid, GridToolbar, useGridApiRef } from '@mui/x-data-grid';
import {
    ChevronRight,
    ChevronDown,
    Loader2
} from 'lucide-react';
import StandardReportHeader from '../../components/StandardReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import { exportToCSV } from '../../utils/exportUtils';

// --- HELPERS ---
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'decimal', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(val || 0);
const formatMonth = (dateStr) => {
    if (!dateStr || !dateStr.includes('-')) return dateStr;
    if (/^\d{4}-\d{2}$/.test(dateStr)) {
        const [year, month] = dateStr.split('-');
        const date = new Date(parseInt(year), parseInt(month) - 1);
        return date.toLocaleDateString('en-US', { month: 'short', year: 'numeric' });
    }
    return dateStr;
};

const TransactionPerformanceDashboard = () => {
    const [rows, setRows] = useState([]); // Flat list of all visible rows
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [filters, setFilters] = useState({ datePreset: 'Custom' });

    // Tracking expansion state: key -> boolean
    const [expanded, setExpanded] = useState({});

    // --- FETCH DATA ---
    const fetchApiData = async (groupBy, parentValue, grandParentValue) => {
        const token = localStorage.getItem('token');
        const queryParams = new URLSearchParams({
            groupBy,
            parentValue: parentValue || '',
            grandParentValue: grandParentValue || ''
        });

        const res = await fetch(`/api/business/performance-dashboard?${queryParams}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
            body: JSON.stringify(filters)
        });

        if (res.ok) return await res.json();
        return [];
    };

    // --- INITIAL LOAD ---
    useEffect(() => {
        loadInitialData();
    }, []);

    const loadInitialData = async () => {
        setLoading(true);
        try {
            const data = await fetchApiData('MONTH', null, null);
            // Transform to Row format
            const formatedRows = data.map((d, i) => ({
                id: `MONTH-${d.row_label}`, // Unique ID
                level: 0,
                groupKey: 'MONTH',
                label: formatMonth(d.row_label),
                rawValue: d.row_label,
                ...d
            }));
            setRows(formatedRows);
            setExpanded({});
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    // --- EXPAND/COLLAPSE LOGIC ---
    const handleToggle = async (row) => {
        const isCurrentlyExpanded = expanded[row.id];

        if (isCurrentlyExpanded) {
            // COLLAPSE: Remove all descendants
            // We need to find all rows that start with this row's ID prefix or are children
            // A simple way since it's a flat list sorted by hierarchy implies children are immediately after parent?
            // Yes, if we insert them there.

            // Recursive function to gather all child IDs to remove
            // Or simpler: filter out any row whose ID *contains* the parent ID as a prefix? 
            // ID construction: MONTH-2024-01 -> DAY-2024-01-01 -> MERCHANT-MID-2024-01-01
            // Wait, ID structure varies.
            // Let's rely on `parentId` reference if we had it, or just index logic.
            // Safe way: Traverse down from row index and remove until we hit a node of same or higher level (lower level number).

            const rowIndex = rows.findIndex(r => r.id === row.id);
            if (rowIndex === -1) return;

            let countToRemove = 0;
            for (let i = rowIndex + 1; i < rows.length; i++) {
                if (rows[i].level > row.level) {
                    countToRemove++;
                } else {
                    break;
                }
            }

            const newRows = [...rows];
            newRows.splice(rowIndex + 1, countToRemove);
            setRows(newRows);

            const newExpanded = { ...expanded };
            delete newExpanded[row.id];
            // Also need to delete keys of children? Not strictly necessary but clean.
            setExpanded(newExpanded);

        } else {
            // EXPAND: Fetch and Insert
            let nextGroupBy = '';
            let nextParent = '';
            let nextGrandParent = null;

            if (row.level === 0) { // MONTH -> DAY
                nextGroupBy = 'DAY';
                nextParent = row.rawValue; // YYYY-MM
            } else if (row.level === 1) { // DAY -> MERCHANT
                nextGroupBy = 'MERCHANT';
                nextParent = row.rawValue; // YYYY-MM-DD
            } else if (row.level === 2) { // MERCHANT -> STORE
                nextGroupBy = 'STORE';
                nextParent = row.mid; // MID
                nextGrandParent = row.parentDay; // Need to store parent Day in row data
            } else {
                return;
            }

            // Set loading state for this specific row?
            // We can use a visual indicator.
            // For now, toggle expanded to show loader in cell?
            setExpanded(prev => ({ ...prev, [row.id]: true })); // Optimistic

            try {
                const children = await fetchApiData(nextGroupBy, nextParent, nextGrandParent);

                // Transform Children
                const newChildren = children.map(c => {
                    let id = '';
                    let label = '';
                    let parentRef = null;

                    if (row.level === 0) {
                        id = `DAY-${c.row_label}`;
                        label = c.row_label;
                        parentRef = row.rawValue;
                    } else if (row.level === 1) {
                        id = `MERCHANT-${c.mid}-${row.rawValue}`;
                        // Use Merchant Name if available, otherwise just MID
                        label = c.merchant_name ? `${c.merchant_name} (${c.mid})` : `MID: ${c.mid}`;
                        parentRef = row.rawValue; // Day
                    } else if (row.level === 2) {
                        id = `STORE-${c.sid}-${row.rawValue}`;
                        label = `SID: ${c.sid}`;
                    }

                    return {
                        ...c,
                        id,
                        level: row.level + 1,
                        label,
                        rawValue: c.row_label || c.mid || c.sid,
                        parentDay: row.level === 1 ? row.rawValue : row.parentDay
                    };
                });

                // Insert
                const rowIndex = rows.findIndex(r => r.id === row.id);
                const newRows = [...rows];
                newRows.splice(rowIndex + 1, 0, ...newChildren);
                setRows(newRows);

            } catch (err) {
                console.error(err);
                // Revert expansion on failure
                setExpanded(prev => {
                    const n = { ...prev };
                    delete n[row.id];
                    return n;
                });
            }
        }
    };


    // --- COLUMNS ---
    const columns = [
        {
            field: 'entity',
            headerName: 'PERIOD / ENTITY',
            width: 280,
            sortable: false,
            renderCell: (params) => {
                const { row } = params;
                const isExp = !!expanded[row.id];
                const canExpand = row.level < 3; // Max depth

                return (
                    <Box sx={{ pl: row.level * 3, display: 'flex', alignItems: 'center', gap: 1 }}>
                        {canExpand && (
                            <IconButton size="small" onClick={(e) => { e.stopPropagation(); handleToggle(row); }}>
                                {isExp ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                            </IconButton>
                        )}
                        <Typography variant="body2" fontWeight={row.level === 0 ? 700 : 500} color="text.primary">
                            {row.label}
                        </Typography>
                    </Box>
                );
            }
        },
        // Domestic Debit
        { field: 'dom_debit_cnt', headerName: 'Count', type: 'number', width: 90, valueFormatter: (value) => formatNumber(value) },
        { field: 'dom_debit_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_debit_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_debit_pct', headerName: '%', width: 70, valueGetter: (value, row) => ((row.dom_debit_vol / row.total_vol) * 100 || 0).toFixed(1) + '%' },
        { field: 'dom_debit_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },

        // Domestic Credit
        { field: 'dom_credit_cnt', headerName: 'Count', type: 'number', width: 90, valueFormatter: (value) => formatNumber(value) },
        { field: 'dom_credit_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_credit_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'dom_credit_pct', headerName: '%', width: 70, valueGetter: (value, row) => ((row.dom_credit_vol / row.total_vol) * 100 || 0).toFixed(1) + '%' },
        { field: 'dom_credit_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },

        // Intl
        { field: 'int_cnt', headerName: 'Count', type: 'number', width: 90, valueFormatter: (value) => formatNumber(value) },
        { field: 'int_vol', headerName: 'Vol', type: 'number', width: 120, valueFormatter: (value) => formatCurrency(value) },
        { field: 'int_msf', headerName: 'MSF', type: 'number', width: 100, valueFormatter: (value) => formatCurrency(value) },
        { field: 'int_pct', headerName: '%', width: 70, valueGetter: (value, row) => ((row.int_vol / row.total_vol) * 100 || 0).toFixed(1) + '%' },
        { field: 'int_optin', headerName: 'Opt-In', type: 'number', width: 110, valueFormatter: (value) => formatCurrency(value) },

        // Total
        {
            field: 'total_vol',
            headerName: 'TOTAL VOL',
            type: 'number',
            width: 140,
            renderCell: (params) => (
                <Typography fontWeight="700" color="primary.main">{formatCurrency(params.value)}</Typography>
            )
        },
    ];

    const columnGroupingModel = [
        {
            groupId: 'DomesticDebit',
            headerName: 'Domestic Debit & Prepaid',
            headerClassName: 'super-header-debit',
            children: [{ field: 'dom_debit_cnt' }, { field: 'dom_debit_vol' }, { field: 'dom_debit_msf' }, { field: 'dom_debit_pct' }, { field: 'dom_debit_optin' }],
        },
        {
            groupId: 'DomesticCredit',
            headerName: 'Domestic Credit',
            headerClassName: 'super-header-credit',
            children: [{ field: 'dom_credit_cnt' }, { field: 'dom_credit_vol' }, { field: 'dom_credit_msf' }, { field: 'dom_credit_pct' }, { field: 'dom_credit_optin' }],
        },
        {
            groupId: 'International',
            headerName: 'International',
            headerClassName: 'super-header-intl',
            children: [{ field: 'int_cnt' }, { field: 'int_vol' }, { field: 'int_msf' }, { field: 'int_pct' }, { field: 'int_optin' }],
        },
    ];

    return (
        <Box sx={{ p: 3, bgcolor: '#F8FAFC', minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>

            <StandardReportHeader
                title="Transaction Performance"
                subtitle="Drill-down: Month > Day > Merchant > Store"
                onExport={() => exportToCSV(rows, 'transaction_performance')}
                onRefresh={loadInitialData}
                onFilterChange={(k, v) => setFilters(prev => ({ ...prev, [k]: v }))}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                filters={filters}
            />

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={loadInitialData}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            <Paper sx={{
                flex: 1,
                width: '100%',
                borderRadius: '12px',
                overflow: 'hidden',
                boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
                border: '1px solid #E2E8F0',
                '& .super-header-debit': { bgcolor: '#eff6ff', color: '#1e40af', fontWeight: '700' },
                '& .super-header-credit': { bgcolor: '#f0fdf4', color: '#15803d', fontWeight: '700' },
                '& .super-header-intl': { bgcolor: '#fff7ed', color: '#c2410c', fontWeight: '700' },
            }}>
                <DataGrid
                    rows={rows}
                    columns={columns}
                    columnGroupingModel={columnGroupingModel}
                    loading={loading}
                    disableRowSelectionOnClick
                    experimentalFeatures={{ columnGrouping: true }}
                    rowHeight={50}
                    slots={{ toolbar: GridToolbar }}
                    sx={{
                        border: 'none',
                        '& .MuiDataGrid-columnHeaders': {
                            bgcolor: '#f8fafc',
                            color: '#475569',
                            fontWeight: 700,
                            textTransform: 'uppercase',
                            fontSize: '0.7rem'
                        },
                        // Custom styles for specific column headers can be targeted via field classes if needed
                        '& .MuiDataGrid-row': {
                            bgcolor: '#ffffff',
                            borderBottom: '1px solid #f1f5f9'
                        },
                        '& .MuiDataGrid-row:hover': { bgcolor: '#f8fafc' },
                        // Indent styling simulation (zebra for different levels?)
                        // We can set dynamic row style based on level if we want
                    }}
                    getRowClassName={(params) => `row-level-${params.row.level}`}
                />
            </Paper>
            <style jsx global>{`
                .row-level-1 { background-color: #f8fafc !important; }
                .row-level-2 { background-color: #f1f5f9 !important; }
                .row-level-3 { background-color: #e2e8f0 !important; }
            `}</style>
        </Box>
    );
};

export default TransactionPerformanceDashboard;
