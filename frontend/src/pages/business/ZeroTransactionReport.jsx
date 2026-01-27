import React, { useState, useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { Filter, Search, Download, AlertCircle, Clock, CheckCircle, XCircle } from 'lucide-react';
import ReportHeader from '../../components/ReportHeader';
import { exportToCSV } from '../../utils/exportUtils';

const ZeroTransactionReport = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(true);

    // Filters
    const [rangeType, setRangeType] = useState('LAST_30'); // LAST_7, LAST_30, NEVER
    const [filters, setFilters] = useState({
        merchantName: '', // Maps to Entity Name or Merchant Name
        partnerList: [], // Aggregator Name
        midList: [],
        sidList: [],
        tidList: [],
        hideDatePresets: true // Custom flag for ReportHeader
    });

    // Input states for comma-separated lists
    const [aggregatorInput, setAggregatorInput] = useState('');
    const [midInput, setMidInput] = useState('');
    const [sidInput, setSidInput] = useState('');
    const [tidInput, setTidInput] = useState('');

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');

            // Parse inputs to lists
            const payload = {
                merchantName: filters.merchantName,
                partnerList: aggregatorInput ? aggregatorInput.split(',').map(s => s.trim()) : [],
                midList: midInput ? midInput.split(',').map(s => s.trim()) : [],
                sidList: sidInput ? sidInput.split(',').map(s => s.trim()) : [],
                tidList: tidInput ? tidInput.split(',').map(s => s.trim()) : [],
            };

            const res = await fetch(`/api/reports/zero-txn/list?rangeType=${rangeType}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
                body: JSON.stringify(payload)
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

    // Initial Load
    useEffect(() => {
        fetchData();
    }, [rangeType]); // Auto-reload on Range Switch

    const getStatusBadge = (status) => {
        if (status === 'Never Transacted') {
            return <span className="px-2 py-1 rounded-full bg-slate-100 text-slate-600 text-xs font-bold border border-slate-200">Never Transacted</span>;
        } else if (status === 'Inactive 30+') {
            return <span className="px-2 py-1 rounded-full bg-red-50 text-red-600 text-xs font-bold border border-red-200">Inactive 30+</span>;
        } else {
            return <span className="px-2 py-1 rounded-full bg-amber-50 text-amber-600 text-xs font-bold border border-amber-200">Inactive 7-30</span>;
        }
    };

    return (
        <div className="flex-1 p-8 overflow-y-auto bg-slate-50/50 min-h-screen">

            {/* Header */}
            <ReportHeader
                title="Zero Merchant Transaction Report"
                subtitle="Identify inactive merchants and potential churn risks."
                onExport={() => exportToCSV(data, 'zero_transaction_report')}
                onRunReport={fetchData}
                filters={filters}
                onFilterChange={() => { }} // No date filters to update from header
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                loading={loading}
            />

            {/* Filter Panel */}
            {showFilters && (
                <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm mb-6">
                    {/* Range Selection */}
                    <div className="flex items-center gap-4 mb-6 border-b border-slate-100 pb-6">
                        <span className="text-sm font-bold text-slate-700">Date Range:</span>
                        <div className="flex bg-slate-100 p-1 rounded-lg">
                            {['LAST_7', 'LAST_30', 'NEVER'].map((type) => (
                                <button
                                    key={type}
                                    onClick={() => setRangeType(type)}
                                    className={`px-4 py-1.5 text-xs font-bold rounded-md transition-all ${rangeType === type
                                        ? 'bg-white text-blue-600 shadow-sm'
                                        : 'text-slate-500 hover:text-slate-700'
                                        }`}
                                >
                                    {type === 'LAST_7' && 'Last 7 Days'}
                                    {type === 'LAST_30' && 'Last 30 Days'}
                                    {type === 'NEVER' && 'Since Onboarding'}
                                </button>
                            ))}
                        </div>
                        <div className="flex-1"></div>
                        <button
                            onClick={fetchData}
                            className="px-6 py-2 bg-slate-900 text-white font-bold rounded-lg hover:bg-slate-800 transition-colors flex items-center gap-2"
                        >
                            <Filter size={16} /> Apply Filters
                        </button>
                    </div>


                    {/* Checkbox / Field Filters */}
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
                        <div className="flex flex-col gap-1">
                            <label className="text-xs font-bold text-slate-500">Entity Name</label>
                            <input
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:border-blue-500"
                                placeholder="Search Entity/Merchant..."
                                value={filters.merchantName}
                                onChange={e => setFilters({ ...filters, merchantName: e.target.value })}
                            />
                        </div>
                        <div className="flex flex-col gap-1">
                            <label className="text-xs font-bold text-slate-500">Aggregator Name</label>
                            <input
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:border-blue-500"
                                placeholder="e.g. Partner A"
                                value={aggregatorInput}
                                onChange={e => setAggregatorInput(e.target.value)}
                            />
                        </div>
                        <div className="flex flex-col gap-1">
                            <label className="text-xs font-bold text-slate-500">Aggregator Code</label>
                            <input
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:border-blue-500"
                                placeholder="Code..."
                                disabled // Mapped to name for now in logic
                            />
                        </div>
                        <div className="flex flex-col gap-1">
                            <label className="text-xs font-bold text-slate-500">MID</label>
                            <input
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:border-blue-500"
                                placeholder="IDs..."
                                value={midInput}
                                onChange={e => setMidInput(e.target.value)}
                            />
                        </div>
                        <div className="flex flex-col gap-1">
                            <label className="text-xs font-bold text-slate-500">SID</label>
                            <input
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:border-blue-500"
                                placeholder="IDs..."
                                value={sidInput}
                                onChange={e => setSidInput(e.target.value)}
                            />
                        </div>
                        <div className="flex flex-col gap-1">
                            <label className="text-xs font-bold text-slate-500">Terminal ID</label>
                            <input
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:border-blue-500"
                                placeholder="IDs..."
                                value={tidInput}
                                onChange={e => setTidInput(e.target.value)}
                            />
                        </div>
                    </div>
                </div>
            )}

            {/* Table */}
            <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full text-sm text-left">
                        <thead className="text-xs text-slate-500 uppercase bg-slate-50 border-b border-slate-200 font-bold">
                            <tr>
                                <th className="px-6 py-4">Entity Name</th>
                                <th className="px-6 py-4">Aggregator</th>
                                <th className="px-6 py-4">Agg Code</th>
                                <th className="px-6 py-4">MID</th>
                                <th className="px-6 py-4">Merchant Name</th>
                                <th className="px-6 py-4">SID</th>
                                <th className="px-6 py-4">Store</th>
                                <th className="px-6 py-4">TID</th>
                                <th className="px-6 py-4">Status</th>
                                <th className="px-6 py-4 text-right">Last Txn</th>
                                <th className="px-6 py-4 text-right">Inactive Days</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-slate-100">
                            {loading ? (
                                <tr><td colSpan="11" className="px-6 py-10 text-center text-slate-500">Loading data...</td></tr>
                            ) : data.length === 0 ? (
                                <tr><td colSpan="11" className="px-6 py-10 text-center text-slate-400">No inactive merchants found in this range.</td></tr>
                            ) : (
                                data.map((row, i) => (
                                    <tr key={i} className="hover:bg-slate-50 transition-colors">
                                        <td className="px-6 py-4 font-medium text-slate-900">{row.entityName || '-'}</td>
                                        <td className="px-6 py-4 text-slate-600">{row.aggregatorName || '-'}</td>
                                        <td className="px-6 py-4 text-slate-500 font-mono text-xs">{row.aggregatorCode || '-'}</td>
                                        <td className="px-6 py-4 text-slate-600 font-mono text-xs">{row.mid}</td>
                                        <td className="px-6 py-4 text-slate-600">{row.merchantName}</td>
                                        <td className="px-6 py-4 text-slate-500 font-mono text-xs">{row.sid}</td>
                                        <td className="px-6 py-4 text-slate-600">{row.storeName}</td>
                                        <td className="px-6 py-4 text-slate-600 font-mono text-xs font-bold bg-slate-100 rounded px-1 w-fit">{row.terminalId}</td>
                                        <td className="px-6 py-4">
                                            {getStatusBadge(row.status)}
                                        </td>
                                        <td className="px-6 py-4 text-right text-slate-600">
                                            {row.lastTransactionDate ? row.lastTransactionDate : <span className="text-slate-300 italic">Never</span>}
                                        </td>
                                        <td className="px-6 py-4 text-right font-medium text-slate-700">
                                            {row.daysInactive > -1 ? row.daysInactive : '-'}
                                        </td>
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

export default ZeroTransactionReport;
