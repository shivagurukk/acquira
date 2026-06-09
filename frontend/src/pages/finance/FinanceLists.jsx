import React, { useState, useEffect } from 'react';
import axios from '../../api/axios';
import { AlertCircle, TrendingDown, ArrowRight, Download, Loader } from 'lucide-react';
import useExcelExport from '../../hooks/useExcelExport';
import { formatCurrency } from '../../utils/formatters';

const FinanceLists = () => {
    const [activeTab, setActiveTab] = useState('loss-making');
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const { exportExcel, isExporting } = useExcelExport();

    useEffect(() => {
        fetchList();
    }, [activeTab]);

    const fetchList = async () => {
        setLoading(true);
        try {
            const endpoint = activeTab === 'loss-making'
                ? '/finance/loss-making-merchants'
                : '/finance/high-volume-low-margin?minVolume=5000&maxMarginPct=0.8';

            // axios interceptor already attaches Authorization and X-Tenant-Id headers
            const response = await axios.get(endpoint);
            setData(response.data.content || response.data || []);
        } catch (error) {
            console.error("Error fetching list", error);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="p-8 bg-gray-50 min-h-screen font-sans">
            <div className="flex justify-between items-center mb-6">
                <h1 className="text-2xl font-bold text-gray-900">Finance Actions & Alerts</h1>
                <button
                    onClick={() => exportExcel(activeTab === 'loss-making' ? 'FINANCE_LOSS_MAKING' : 'FINANCE_LOW_MARGIN')}
                    disabled={isExporting || loading}
                    className="flex items-center gap-2 px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
                >
                    {isExporting ? <Loader size={16} className="animate-spin" /> : <Download size={16} />}
                    {isExporting ? 'Exporting...' : 'Export List'}
                </button>
            </div>

            <div className="flex gap-4 mb-6 border-b border-gray-200">
                <button
                    onClick={() => setActiveTab('loss-making')}
                    className={`pb-3 px-4 text-sm font-medium border-b-2 transition-colors flex items-center gap-2 
                    ${activeTab === 'loss-making' ? 'border-red-500 text-red-600' : 'border-transparent text-gray-500 hover:text-gray-700'}`}
                >
                    <AlertCircle size={18} />
                    Loss Making Merchants
                </button>
                <button
                    onClick={() => setActiveTab('low-margin')}
                    className={`pb-3 px-4 text-sm font-medium border-b-2 transition-colors flex items-center gap-2 
                    ${activeTab === 'low-margin' ? 'border-orange-500 text-orange-600' : 'border-transparent text-gray-500 hover:text-gray-700'}`}
                >
                    <TrendingDown size={18} />
                    High Vol / Low Margin
                </button>
            </div>

            <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
                <table className="w-full text-left">
                    <thead className="bg-gray-50 text-gray-600 text-xs uppercase font-semibold">
                        <tr>
                            <th className="p-4">Merchant ID</th>
                            <th className="p-4">Total Volume</th>
                            <th className="p-4">Net Revenue</th>
                            <th className="p-4">Margin %</th>
                            <th className="p-4">Action</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                        {loading ? (
                            <tr><td colSpan="5" className="p-8 text-center text-gray-500">Scanning Portfolio...</td></tr>
                        ) : data.length === 0 ? (
                            <tr><td colSpan="5" className="p-8 text-center text-green-600 font-medium">No alerts found!</td></tr>
                        ) : (
                            data.map((row, idx) => {
                                const margin = row.totalVolume > 0 ? (row.netRevenue / row.totalVolume) * 100 : 0;
                                return (
                                    <tr key={idx} className="hover:bg-gray-50">
                                        <td className="p-4 font-medium text-gray-900">
                                            {row.merchantName || 'Unknown'}
                                            <span className="text-xs text-gray-400 ml-2">({row.merchantId})</span>
                                        </td>
                                        <td className="p-4 text-gray-600">{formatCurrency(row.totalVolume)}</td>
                                        <td className={`p-4 font-bold ${row.netRevenue < 0 ? 'text-red-600' : 'text-orange-600'}`}>
                                            {formatCurrency(row.netRevenue)}
                                        </td>
                                        <td className="p-4 text-gray-800">{margin.toFixed(2)}%</td>
                                        <td className="p-4">
                                            <button className="text-blue-600 hover:text-blue-800 text-sm font-medium flex items-center gap-1">
                                                Review <ArrowRight size={14} />
                                            </button>
                                        </td>
                                    </tr>
                                );
                            })
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default FinanceLists;
