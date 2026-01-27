import React, { useState, useEffect } from 'react';
import { Loader2 } from 'lucide-react';
import BusinessFilters from '../../components/BusinessFilters';
import ReportHeader from '../../components/ReportHeader';
import { exportToCSV } from '../../utils/exportUtils';

const DebitPrepaidMetrics = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(true);

    const [filters, setFilters] = useState({
        startDate: '', endDate: '',
        openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [],
        rmList: [], teamLeaderList: [], sectorList: [],
        destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '',
        datePreset: 'Custom'
    });

    // Load initial data
    useEffect(() => {
        // Initial load with defaults
        fetchData();
    }, []);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            // Mock API call or real one
            const res = await fetch('/api/business/debit-prepaid-metrics', {
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

    return (
        <div className="p-6 bg-slate-50 min-h-screen flex flex-col gap-6">

            {/* Header */}
            <ReportHeader
                title="Debit & Prepaid Metrics Report"
                subtitle="Domestic Debit and Prepaid Performance by Merchant"
                onExport={() => exportToCSV(data, 'debit_prepaid_metrics')}
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
                <table className="w-full text-sm text-slate-600">
                    <thead className="bg-slate-50 border-b border-slate-200">
                        <tr>
                            <th className="px-6 py-3 text-left font-semibold text-slate-700">MID</th>
                            <th className="px-6 py-3 text-right font-semibold text-slate-700">Count</th>
                            <th className="px-6 py-3 text-right font-semibold text-slate-700">Volume (AED)</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                        {data.length === 0 && !loading ? (
                            <tr>
                                <td colSpan="3" className="px-6 py-8 text-center text-slate-400 italic">
                                    No data found for the selected filters.
                                </td>
                            </tr>
                        ) : (
                            data.map((row, idx) => (
                                <tr key={idx} className="hover:bg-slate-50 transition-colors">
                                    <td className="px-6 py-3 font-medium text-slate-800">{row.mid}</td>
                                    <td className="px-6 py-3 text-right">{new Intl.NumberFormat().format(row.count)}</td>
                                    <td className="px-6 py-3 text-right font-bold text-slate-900">
                                        {new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED' }).format(row.volume)}
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

export default DebitPrepaidMetrics;
