import React, { useState, useEffect } from 'react';
import BusinessFilters from '../../components/BusinessFilters';
import ReportHeader from '../../components/ReportHeader';
import { TrendingUp, TrendingDown, ArrowRight, ArrowLeft, ChevronDown, ChevronRight, Loader2 } from 'lucide-react';
import { exportToCSV } from '../../utils/exportUtils';

const TransactionPerformanceDashboard = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(true);

    // Drill-down State
    const [expandedRows, setExpandedRows] = useState({}); // Key: "MONTH-2024-01" or "DAY-2024-01-01"

    const [filters, setFilters] = useState({
        startDate: '', endDate: '',
        openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [],
        rmList: [], teamLeaderList: [], sectorList: [],
        destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '',
        datePreset: 'Custom'
    });

    // Initial Load (Months)
    useEffect(() => {
        fetchData('MONTH', null, null);
    }, []);

    const fetchData = async (groupBy, parentValue, grandParentValue) => {
        // Avoid fetching if already loaded (unless refreshing?)
        // For simplicity, we just fetch.

        let targetKey = groupBy === 'MONTH' ? 'ROOT' :
            groupBy === 'DAY' ? `MONTH-${parentValue}` :
                groupBy === 'MERCHANT' ? `DAY-${parentValue}` :
                    groupBy === 'STORE' ? `MERCHANT-${parentValue}-${grandParentValue}` : 'UNKNOWN';

        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const queryParams = new URLSearchParams({
                groupBy,
                parentValue: parentValue || '',
                grandParentValue: grandParentValue || ''
            });

            // We use POST to send the complex filter object, but query params for drill-down context
            const res = await fetch(`/api/business/performance-dashboard?${queryParams}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify(filters)
            });

            if (res.ok) {
                const result = await res.json();

                if (groupBy === 'MONTH') {
                    setData(result);
                } else {
                    // Store child data in a map or state attached to parent
                    // Implementation choice: We can store it in a nested structure or a flat map of "ParentKey" -> [Children]
                    // Let's use a flat map for expanded data
                    setExpandedRows(prev => ({ ...prev, [targetKey]: result }));
                }
            }
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    const handleApply = () => {
        setExpandedRows({}); // Reset expansion on filter change
        fetchData('MONTH', null, null);
    };

    // Unified handler for ReportHeader (partial updates) and other filters
    const handleFilterChange = (key, val) => {
        if (typeof key === 'object') {
            setFilters(prev => ({ ...prev, ...key }));
        } else {
            setFilters(prev => ({ ...prev, [key]: val }));
        }
    };

    const toggleRow = (row, level, parentValue = null) => {
        // level 0: Month (row.row_label = YYYY-MM)
        // level 1: Day (row.row_label = YYYY-MM-DD)
        // level 2: Merchant (row.row_label = MID)
        // level 3: Store (row.row_label = SID)

        let key = '';
        let nextGroupBy = '';
        let nextParent = '';
        let nextGrandParent = null;

        if (level === 0) {
            key = `MONTH-${row.row_label}`;
            nextGroupBy = 'DAY';
            nextParent = row.row_label;
        } else if (level === 1) {
            key = `DAY-${row.row_label}`;
            nextGroupBy = 'MERCHANT';
            nextParent = row.row_label;
        } else if (level === 2) {
            // For Merchant, we need parent Day to filter query? 
            // Yes, `getPerformanceDashboardData` expects parentValue as Day for Merchant filtering
            // But wait, the repo logic says: "if MERCHANT group by, parentValue is Day"
            // And "if STORE group by, parentValue is MID, grandParent is Day"
            key = `MERCHANT-${row.row_label}-${parentValue}`; // MID + Day
            nextGroupBy = 'STORE';
            nextParent = row.row_label; // MID
            nextGrandParent = parentValue; // Day
        } else {
            return; // No deeper level
        }

        if (expandedRows[key]) {
            // Already loaded, just toggle visibility? 
            // For this simple implementation, let's just use the presence of data to indicate expanded.
            // To collapse, we remove it? Or keep it and have a separate 'visibility' state.
            // Let's remove to simplify.
            const newExpanded = { ...expandedRows };
            delete newExpanded[key];
            setExpandedRows(newExpanded);
        } else {
            fetchData(nextGroupBy, nextParent, nextGrandParent);
        }
    };

    // Helper to check if expanded
    const isExpanded = (key) => !!expandedRows[key];

    // --- Table Row Rendering ---
    const DataRow = ({ row, level, parentDay }) => {
        // Construct unique key for this row's children
        let childKey = '';
        if (level === 0) childKey = `MONTH-${row.row_label}`;
        else if (level === 1) childKey = `DAY-${row.row_label}`;
        else if (level === 2) childKey = `MERCHANT-${row.row_label}-${parentDay}`;

        const expanded = isExpanded(childKey);

        const indent = level * 20 + 'px';
        const bg = level === 0 ? 'bg-white' : level === 1 ? 'bg-slate-50' : level === 2 ? 'bg-blue-50' : 'bg-indigo-50';
        const label = level === 2 ? `MID: ${row.mid || row.row_label}` : level === 3 ? `SID: ${row.sid || row.row_label}` : row.row_label;

        return (
            <>
                <tr className={`${bg} hover:bg-slate-100 transition-colors border-b border-slate-100`}>
                    <td className="sticky left-0 z-10 bg-inherit px-4 py-3 font-medium text-slate-700 border-r border-slate-200">
                        <div style={{ paddingLeft: indent }} className="flex items-center gap-2">
                            {level < 3 && (
                                <button onClick={() => toggleRow(row, level, parentDay)} className="p-1 hover:bg-slate-200 rounded">
                                    {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                                </button>
                            )}
                            {label}
                        </div>
                    </td>

                    {/* Domestic Debit */}
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.dom_debit_cnt)}</td>
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.dom_debit_vol)}</td>
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.dom_debit_msf)}</td>
                    <td className="px-2 py-2 text-right text-slate-400 text-xs">{(row.dom_debit_vol / row.total_vol * 100 || 0).toFixed(1)}%</td>
                    <td className="px-2 py-2 text-right border-r border-slate-200">{new Intl.NumberFormat().format(row.dom_debit_optin)}</td>

                    {/* Domestic Credit */}
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.dom_credit_cnt)}</td>
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.dom_credit_vol)}</td>
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.dom_credit_msf)}</td>
                    <td className="px-2 py-2 text-right text-slate-400 text-xs">{(row.dom_credit_vol / row.total_vol * 100 || 0).toFixed(1)}%</td>
                    <td className="px-2 py-2 text-right border-r border-slate-200">{new Intl.NumberFormat().format(row.dom_credit_optin)}</td>

                    {/* Intl */}
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.int_cnt)}</td>
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.int_vol)}</td>
                    <td className="px-2 py-2 text-right">{new Intl.NumberFormat().format(row.int_msf)}</td>
                    <td className="px-2 py-2 text-right text-slate-400 text-xs">{(row.int_vol / row.total_vol * 100 || 0).toFixed(1)}%</td>
                    <td className="px-2 py-2 text-right border-r border-slate-200">{new Intl.NumberFormat().format(row.int_optin)}</td>

                    {/* Total */}
                    <td className="px-2 py-2 text-right font-bold">{new Intl.NumberFormat().format(row.total_vol)}</td>
                </tr>

                {expanded && expandedRows[childKey] && expandedRows[childKey].map((child, idx) => (
                    <DataRow key={idx} row={child} level={level + 1} parentDay={level === 1 ? child.row_label : parentDay} />
                ))}
            </>
        );
    };

    return (
        <div className="p-6 bg-slate-50 min-h-screen flex flex-col gap-6">

            {/* Header */}
            <ReportHeader
                title="Transaction Performance Dashboard"
                subtitle="Drill-down: Month > Day > Merchant > Store"
                onExport={() => exportToCSV(data, 'transaction_performance_dashboard')}
                onRunReport={() => fetchData('MONTH', null, null)}
                filters={filters}
                onFilterChange={handleFilterChange}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                loading={loading}
            />

            {showFilters && (
                <div className="bg-white p-6 rounded-xl border border-slate-200 shadow-sm">
                    <BusinessFilters
                        filters={filters}
                        onChange={setFilters}
                        onApply={handleApply}
                        variant="panel"
                    />
                </div>
            )}

            {/* Charts & Table Section */}

            <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden overflow-x-auto flex-1 h-[600px]">
                <table className="w-full text-sm text-slate-600 border-collapse">
                    <thead className="bg-slate-100 text-slate-500 font-bold sticky top-0 z-20">
                        <tr>
                            <th className="sticky left-0 z-30 bg-slate-100 border-r border-b border-slate-200 p-2 text-left min-w-[200px]">Period / Entity</th>
                            <th colSpan="5" className="border-b border-r border-slate-200 bg-blue-50 text-blue-700 text-center py-1">Domestic Debit & Prepaid</th>
                            <th colSpan="5" className="border-b border-r border-slate-200 bg-green-50 text-green-700 text-center py-1">Domestic Credit</th>
                            <th colSpan="5" className="border-b border-r border-slate-200 bg-orange-50 text-orange-700 text-center py-1">International</th>
                            <th className="border-b border-slate-200 text-center py-1">Total</th>
                        </tr>
                        <tr className="text-xs">
                            <th className="sticky left-0 z-30 bg-slate-100 border-b border-r border-slate-200"></th>
                            {/* Dom Debit */}
                            <th className="p-2 border-b">Count</th><th className="p-2 border-b">Vol</th><th className="p-2 border-b">MSF</th><th className="p-2 border-b">%</th><th className="p-2 border-b border-r border-slate-200">Opt-In</th>
                            {/* Dom Credit */}
                            <th className="p-2 border-b">Count</th><th className="p-2 border-b">Vol</th><th className="p-2 border-b">MSF</th><th className="p-2 border-b">%</th><th className="p-2 border-b border-r border-slate-200">Opt-In</th>
                            {/* Intl */}
                            <th className="p-2 border-b">Count</th><th className="p-2 border-b">Vol</th><th className="p-2 border-b">MSF</th><th className="p-2 border-b">%</th><th className="p-2 border-b border-r border-slate-200">Opt-In</th>

                            <th className="p-2 border-b">Volume</th>
                        </tr>
                    </thead>
                    <tbody>
                        {data.map((row, idx) => (
                            <DataRow key={idx} row={row} level={0} />
                        ))}
                    </tbody>
                </table>
                {loading && <div className="p-10 flex justify-center text-slate-400"><Loader2 className="animate-spin" /></div>}
            </div>
        </div>
    );
};

export default TransactionPerformanceDashboard;
