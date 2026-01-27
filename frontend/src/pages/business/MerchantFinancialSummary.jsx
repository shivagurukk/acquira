import React, { useState, useEffect } from 'react';
import { Loader2 } from 'lucide-react';
import BusinessFilters from '../../components/BusinessFilters';
import ReportHeader from '../../components/ReportHeader';
import { exportToCSV } from '../../utils/exportUtils';

const MerchantFinancialSummary = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(true);

    // Filters State
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
        fetchReport();
    }, []);

    const fetchReport = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/merchant-financial-summary', {
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
            console.error("Failed to load report", error);
        } finally {
            setLoading(false);
        }
    };

    // Unified handler for ReportHeader (partial updates) and other filters
    const handleFilterChange = (key, val) => {
        if (typeof key === 'object') {
            setFilters(prev => ({ ...prev, ...key }));
        } else {
            setFilters(prev => ({ ...prev, [key]: val }));
        }
    };

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', minimumFractionDigits: 2 }).format(val || 0);
    const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

    return (
        <div className="p-6 bg-slate-50 min-h-screen flex flex-col gap-6">

            {/* Header */}
            <ReportHeader
                title="Merchant Financial Summary"
                subtitle="Business Universe Report"
                onExport={() => exportToCSV(data, 'merchant_financial_summary')}
                onRunReport={fetchReport}
                filters={filters}
                onFilterChange={handleFilterChange}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                loading={loading}
            />

            {/* Filters Bar */}
            {showFilters && (
                <div className="bg-white p-6 rounded-xl border border-slate-200 shadow-sm">
                    <BusinessFilters
                        filters={filters}
                        onChange={setFilters}
                        onApply={fetchReport}
                        variant="panel"
                    />
                </div>
            )}

            {/* Data Table */}
            <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden flex-1">
                <div className="overflow-x-auto">
                    <table className="w-full text-sm text-left">
                        <thead className="bg-slate-50 text-slate-500 font-semibold border-b border-slate-200">
                            <tr>
                                <th className="px-6 py-3">MID</th>
                                <th className="px-6 py-3">SID</th>
                                <th className="px-6 py-3 text-right">Count</th>
                                <th className="px-6 py-3 text-right">Volume (AED)</th>
                                <th className="px-6 py-3 text-right">MSF (AED)</th>
                                <th className="px-6 py-3 text-right">Opt-in Volume (AED)</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100">
                            {loading ? (
                                <tr>
                                    <td colSpan="6" className="p-10 text-center text-slate-400">
                                        <div className="flex justify-center items-center gap-2">
                                            <Loader2 className="animate-spin" size={20} /> Loading Data...
                                        </div>
                                    </td>
                                </tr>
                            ) : data.length === 0 ? (
                                <tr>
                                    <td colSpan="6" className="p-10 text-center text-slate-400 italic">No data found for the selected filters.</td>
                                </tr>
                            ) : (
                                data.map((row, idx) => (
                                    <tr key={idx} className="hover:bg-slate-50 transition-colors">
                                        <td className="px-6 py-4 font-medium text-slate-700 font-mono">{row.mid}</td>
                                        <td className="px-6 py-4 text-slate-600 font-mono">{row.sid}</td>
                                        <td className="px-6 py-4 text-right text-slate-600">{formatNumber(row.count)}</td>
                                        <td className="px-6 py-4 text-right font-medium text-slate-800">{formatCurrency(row.volume)}</td>
                                        <td className="px-6 py-4 text-right text-slate-600">{formatCurrency(row.msf)}</td>
                                        <td className="px-6 py-4 text-right text-slate-600">{formatCurrency(row.opt_in_volume)}</td>
                                    </tr>
                                ))
                            )}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    );
};

export default MerchantFinancialSummary;
