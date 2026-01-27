import React, { useState, useEffect } from 'react';
import axios from '../../api/axios';
import { ArrowUp, ArrowDown, Download, Filter } from 'lucide-react';
import BusinessFilterBar from '../../components/BusinessFilterBar';

const ProfitabilityAnalysis = () => {
    const [breakdown, setBreakdown] = useState([]);
    const [groupBy, setGroupBy] = useState('merchant'); // merchant, mcc, scheme, channel
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState({});
    const [sortConfig, setSortConfig] = useState({ key: 'totalNetRevenue', direction: 'desc' });

    const tenantId = 1; // TODO: Context

    useEffect(() => {
        fetchData();
    }, [groupBy, filters, tenantId]);

    const fetchData = async () => {
        setLoading(true);
        try {
            const queryParams = new URLSearchParams();
            queryParams.append('groupBy', groupBy);
            queryParams.append('size', '100');
            if (filters.startDate) queryParams.append('from', filters.startDate);
            if (filters.endDate) queryParams.append('to', filters.endDate);

            const response = await axios.get(`/api/finance/profitability?${queryParams.toString()}`, {
                headers: { 'X-Tenant-Id': tenantId }
            });
            setBreakdown(response.data.content);
        } catch (error) {
            console.error("Error fetching profitability data", error);
        } finally {
            setLoading(false);
        }
    };

    const handleExport = async () => {
        try {
            const queryParams = new URLSearchParams();
            queryParams.append('groupBy', groupBy);
            if (filters.startDate) queryParams.append('from', filters.startDate);
            if (filters.endDate) queryParams.append('to', filters.endDate);

            const response = await axios.get(`/api/finance/export/profitability?${queryParams.toString()}`, {
                headers: { 'X-Tenant-Id': tenantId },
                responseType: 'blob'
            });

            const url = window.URL.createObjectURL(new Blob([response.data]));
            const link = document.createElement('a');
            link.href = url;
            link.setAttribute('download', `profitability_${groupBy}_${new Date().toISOString().slice(0, 10)}.csv`);
            document.body.appendChild(link);
            link.click();
            link.remove();
        } catch (error) {
            console.error("Export failed", error);
            alert("Export failed. Please try again.");
        }
    };

    const handleFilterChange = (newFilters) => {
        setFilters(newFilters);
    };

    const handleSort = (key) => {
        let direction = 'desc';
        if (sortConfig.key === key && sortConfig.direction === 'desc') {
            direction = 'asc';
        }
        setSortConfig({ key, direction });
    };

    const sortedData = [...breakdown].sort((a, b) => {
        const aVal = a[sortConfig.key] || 0;
        const bVal = b[sortConfig.key] || 0;
        if (aVal < bVal) return sortConfig.direction === 'asc' ? -1 : 1;
        if (aVal > bVal) return sortConfig.direction === 'asc' ? 1 : -1;
        return 0;
    });

    // Calculate Margin % on frontend for display
    const calculatedData = sortedData.map(item => {
        const vol = item.totalVolume || 0;
        const net = item.totalNetRevenue || 0;
        const margin = vol > 0 ? (net / vol) * 100 : 0;
        return { ...item, marginPct: margin };
    });

    // Sort again if we sorted by marginPct which is derived
    if (sortConfig.key === 'marginPct') {
        calculatedData.sort((a, b) => {
            if (a.marginPct < b.marginPct) return sortConfig.direction === 'asc' ? -1 : 1;
            if (a.marginPct > b.marginPct) return sortConfig.direction === 'asc' ? 1 : -1;
            return 0;
        });
    }

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val || 0);

    const columns = [
        { key: 'key', label: groupBy.toUpperCase() }, // 'key' holds the name/id
        { key: 'totalTxns', label: 'Txn Count' },
        { key: 'totalVolume', label: 'Volume' },
        { key: 'totalMsf', label: 'MSF Revenue' },
        { key: 'totalInterchange', label: 'Interchange' },
        { key: 'totalSchemeFee', label: 'Scheme Fees' },
        { key: 'totalNetRevenue', label: 'Net Revenue' },
        { key: 'marginPct', label: 'Margin %' },
    ];

    return (
        <div className="p-8 bg-gray-50 min-h-screen font-sans">
            <div className="flex justify-between items-center mb-8">
                <div>
                    <h1 className="text-3xl font-bold text-gray-900">Profitability Analysis</h1>
                    <p className="text-gray-500 mt-1">Deep dive into profitability drivers</p>
                </div>
            </div>

            <BusinessFilterBar onFilterChange={handleFilterChange} />

            <div className="flex justify-between items-center mb-6 mt-6">
                <div className="flex bg-white rounded-lg shadow-sm p-1 border border-gray-200">
                    {['merchant', 'mcc', 'scheme', 'channel'].map(mode => (
                        <button
                            key={mode}
                            onClick={() => setGroupBy(mode)}
                            className={`px-4 py-2 rounded-md text-sm font-medium capitalize transition-colors ${groupBy === mode ? 'bg-indigo-600 text-white' : 'text-gray-600 hover:bg-gray-50'}`}
                        >
                            {mode}
                        </button>
                    ))}
                </div>
                <button
                    onClick={handleExport}
                    className="flex items-center gap-2 px-4 py-2 bg-white border border-gray-200 rounded-lg text-gray-700 hover:bg-gray-50 shadow-sm"
                >
                    <Download size={18} /> Export CSV
                </button>
            </div>

            <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
                <table className="w-full text-left border-collapse">
                    <thead className="bg-gray-50 text-gray-600 text-xs uppercase font-semibold">
                        <tr>
                            {columns.map(col => (
                                <th
                                    key={col.key}
                                    className="p-4 border-b border-gray-200 cursor-pointer hover:bg-gray-100"
                                    onClick={() => handleSort(col.key)}
                                >
                                    <div className="flex items-center gap-1">
                                        {col.label}
                                        {sortConfig.key === col.key && (
                                            sortConfig.direction === 'asc' ? <ArrowUp size={14} /> : <ArrowDown size={14} />
                                        )}
                                    </div>
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                        {loading ? (
                            <tr><td colSpan={columns.length} className="p-8 text-center text-gray-500">Loading Analysis...</td></tr>
                        ) : calculatedData.length === 0 ? (
                            <tr><td colSpan={columns.length} className="p-8 text-center text-gray-500">No Data Found</td></tr>
                        ) : (
                            calculatedData.map((row, idx) => (
                                <tr key={idx} className="hover:bg-gray-50 transition-colors">
                                    <td className="p-4 font-medium text-gray-900">
                                        {row.key}
                                        {row.name && <div className="text-xs text-gray-500 font-normal">{row.name}</div>}
                                    </td>
                                    <td className="p-4 text-gray-600">{row.totalTxns}</td>
                                    <td className="p-4 text-gray-600">{formatCurrency(row.totalVolume)}</td>
                                    <td className="p-4 text-blue-600">{formatCurrency(row.totalMsf)}</td>
                                    <td className="p-4 text-orange-600">{formatCurrency(row.totalInterchange)}</td>
                                    <td className="p-4 text-gray-600">{formatCurrency(row.totalSchemeFee)}</td>
                                    <td className={`p-4 font-bold ${row.totalNetRevenue < 0 ? 'text-red-600' : 'text-green-600'}`}>
                                        {formatCurrency(row.totalNetRevenue)}
                                    </td>
                                    <td className="p-4 text-gray-800">
                                        <span className={`px-2 py-1 rounded text-xs font-bold ${row.marginPct < 0.5 ? 'bg-red-100 text-red-700' : 'bg-green-100 text-green-700'}`}>
                                            {row.marginPct.toFixed(2)}%
                                        </span>
                                    </td>
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default ProfitabilityAnalysis;
