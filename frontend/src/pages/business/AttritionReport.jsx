import React, { useState, useEffect } from 'react';
import { Loader2 } from 'lucide-react';
import BusinessFilters from '../../components/BusinessFilters';
import ReportHeader from '../../components/ReportHeader';
import { exportToCSV } from '../../utils/exportUtils';

const AttritionReport = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(true);

    // Default to current month
    const today = new Date();
    const firstDay = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().split('T')[0];
    const lastDay = today.toISOString().split('T')[0];

    const [filters, setFilters] = useState({
        startDate: firstDay, endDate: lastDay,
        openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [],
        rmList: [], teamLeaderList: [], sectorList: [],
        destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '',
        datePreset: 'Custom'
    });

    useEffect(() => {
        fetchData();
    }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/attrition-report', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify(filters)
            });

            if (res.ok) {
                const result = await res.json();
                setData(result);
            }
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    const handleApply = () => {
        fetchData();
    };

    // Unified handler for ReportHeader (partial updates) and other filters
    const handleFilterChange = (key, val) => {
        if (typeof key === 'object') {
            setFilters(prev => ({ ...prev, ...key }));
        } else {
            setFilters(prev => ({ ...prev, [key]: val }));
        }
    };

    // Calculate Dynamic Years for Headers
    const selectedYear = filters.endDate ? new Date(filters.endDate).getFullYear() : new Date().getFullYear();
    const prevYear = selectedYear - 1;

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', notation: 'compact' }).format(val || 0);
    const formatPct = (val) => `${(val || 0).toFixed(1)}%`;
    const getPctColor = (val) => val < 0 ? 'text-red-500' : val > 0 ? 'text-green-500' : 'text-slate-400';

    return (
        <div className="p-6 bg-slate-50 min-h-screen flex flex-col gap-6">

            {/* Header */}
            <ReportHeader
                title="Attrition Report (YoY)"
                subtitle="Year-over-Year Volume Comparison Performance"
                onExport={() => exportToCSV(data, 'attrition_report')}
                onRunReport={fetchData}
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

            <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden flex-1">
                <table className="w-full text-sm text-slate-600 border-collapse">
                    <thead className="bg-slate-50 text-slate-700">
                        <tr>
                            <th className="p-3 border-b border-r border-slate-200 text-left">Merchant</th>
                            <th colSpan="3" className="p-3 border-b border-r border-slate-200 text-center bg-blue-50 text-blue-800">
                                MTD Comparison ({prevYear} vs {selectedYear})
                            </th>
                            <th colSpan="3" className="p-3 border-b text-center bg-indigo-50 text-indigo-800">
                                YTD Comparison ({prevYear} vs {selectedYear})
                            </th>
                        </tr>
                        <tr className="text-xs uppercase tracking-wide bg-slate-100 text-slate-500">
                            <th className="p-3 border-b border-r text-left">Internal ID / Name</th>
                            {/* MTD */}
                            <th className="p-3 border-b text-right">{prevYear} Vol</th>
                            <th className="p-3 border-b text-right">{selectedYear} Vol</th>
                            <th className="p-3 border-b border-r text-right">% Change</th>
                            {/* YTD */}
                            <th className="p-3 border-b text-right">{prevYear} Vol</th>
                            <th className="p-3 border-b text-right">{selectedYear} Vol</th>
                            <th className="p-3 border-b text-right">% Change</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                        {data.length === 0 && !loading ? (
                            <tr>
                                <td colSpan="7" className="px-6 py-8 text-center text-slate-400 italic">
                                    No data found. Ensure you have data for both years to see comparisons.
                                </td>
                            </tr>
                        ) : (
                            data.map((row, idx) => (
                                <tr key={idx} className="hover:bg-slate-50 transition-colors">
                                    <td className="px-4 py-3 border-r border-slate-100">
                                        <div className="font-medium text-slate-800">{row.name}</div>
                                        <div className="text-xs text-slate-400">{row.mid}</div>
                                    </td>

                                    {/* MTD */}
                                    <td className="px-4 py-3 text-right">{formatCurrency(row.mtd_prev)}</td>
                                    <td className="px-4 py-3 text-right font-medium">{formatCurrency(row.mtd_current)}</td>
                                    <td className={`px-4 py-3 text-right border-r border-slate-100 font-bold ${getPctColor(row.mtd_pct)}`}>
                                        {formatPct(row.mtd_pct)}
                                    </td>

                                    {/* YTD */}
                                    <td className="px-4 py-3 text-right">{formatCurrency(row.ytd_prev)}</td>
                                    <td className="px-4 py-3 text-right font-medium">{formatCurrency(row.ytd_current)}</td>
                                    <td className={`px-4 py-3 text-right font-bold ${getPctColor(row.ytd_pct)}`}>
                                        {formatPct(row.ytd_pct)}
                                    </td>
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
                {loading && <div className="p-10 flex justify-center text-slate-400"><Loader2 className="animate-spin" /></div>}
            </div>
        </div>
    );
};

export default AttritionReport;
