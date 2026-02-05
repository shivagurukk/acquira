import React, { useState, useEffect } from 'react';
import BusinessFilters from '../../components/BusinessFilters';
import { Loader2 } from 'lucide-react';
import StandardReportHeader from '../../components/StandardReportHeader';
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

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', minimumFractionDigits: 2 }).format(val || 0);
    const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

    return (
        <div className="page-container" style={{ padding: '20px', color: '#1e293b', height: '100vh', display: 'flex', flexDirection: 'column' }}>

            <StandardReportHeader
                title="Debit & Prepaid Metrics Report"
                subtitle="Domestic Debit and Prepaid Performance by Merchant"
                onExport={() => exportToCSV(data, 'debit_prepaid_metrics')}
                onRefresh={fetchData}
                onFilterChange={handleFilterChange}
                loading={loading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                filters={filters}
            />

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={handleApply}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            <div style={{ flex: 1, overflow: 'hidden', border: '1px solid #e2e8f0', borderRadius: '12px', background: 'white', position: 'relative', boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)' }}>
                <div style={{ overflow: 'auto', height: '100%' }}>
                    <table style={{ minWidth: '1000px', width: '100%', borderCollapse: 'separate', borderSpacing: 0 }}>
                        <thead style={{ position: 'sticky', top: 0, zIndex: 10, background: '#f8fafc' }}>
                            <tr style={{ height: '48px', background: '#f8fafc', fontSize: '12px', color: '#64748b', letterSpacing: '0.05em' }}>
                                <th style={{ position: 'sticky', left: 0, zIndex: 20, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', borderRight: '1px solid #e2e8f0', padding: '16px 24px', textAlign: 'left', fontWeight: '600', textTransform: 'uppercase', width: '150px' }}>MID</th>
                                <th style={{ borderBottom: '1px solid #e2e8f0', padding: '16px 24px', textAlign: 'left', fontWeight: '600', textTransform: 'uppercase' }}>Merchant Name</th>
                                <th style={{ borderBottom: '1px solid #e2e8f0', padding: '16px 24px', textAlign: 'right', fontWeight: '600', textTransform: 'uppercase', width: '120px' }}>Count</th>
                                <th style={{ borderBottom: '1px solid #e2e8f0', padding: '16px 24px', textAlign: 'right', fontWeight: '600', textTransform: 'uppercase', width: '180px' }}>Volume (AED)</th>
                            </tr>
                        </thead>
                        <tbody style={{ fontSize: '14px' }}>
                            {loading ? (
                                Array(5).fill(0).map((_, i) => (
                                    <tr key={i}>
                                        <td colSpan="4" style={{ padding: '24px', borderBottom: '1px solid #f1f5f9' }}>
                                            <div className="animate-pulse flex space-x-4">
                                                <div className="flex-1 space-y-4 py-1">
                                                    <div className="h-4 bg-slate-100 rounded w-3/4"></div>
                                                </div>
                                            </div>
                                        </td>
                                    </tr>
                                ))
                            ) : data.length === 0 ? (
                                <tr>
                                    <td colSpan="4" style={{ padding: '60px', textAlign: 'center', color: '#94a3b8' }}>
                                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px' }}>
                                            <div style={{ background: '#f1f5f9', padding: '16px', borderRadius: '50%' }}>
                                                <Loader2 className="text-slate-400" size={32} />
                                            </div>
                                            <span style={{ fontSize: '16px', fontWeight: '500' }}>No data found for this period</span>
                                            <span style={{ fontSize: '13px', color: '#cbd5e1' }}>Try adjusting your filters</span>
                                        </div>
                                    </td>
                                </tr>
                            ) : (
                                data.map((row, idx) => (
                                    <tr key={idx} style={{ borderBottom: '1px solid #f1f5f9', transition: 'background-color 0.2s' }}
                                        onMouseEnter={(e) => e.currentTarget.style.backgroundColor = '#f8fafc'}
                                        onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'white'}>

                                        <td style={{ position: 'sticky', left: 0, background: 'inherit', borderRight: '1px solid #e2e8f0', padding: '16px 24px', whiteSpace: 'nowrap', borderBottom: '1px solid #f1f5f9' }}>
                                            <span style={{ fontFamily: '"Roboto Mono", monospace', fontSize: '13px', color: '#475569', background: '#f1f5f9', padding: '4px 8px', borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                                                {row.mid}
                                            </span>
                                        </td>

                                        <td style={{ padding: '16px 24px', textAlign: 'left', color: '#1e293b', borderBottom: '1px solid #f1f5f9', fontWeight: '600', fontSize: '14px' }}>
                                            {row.merchantName}
                                        </td>

                                        <td style={{ padding: '16px 24px', textAlign: 'right', color: '#64748b', borderBottom: '1px solid #f1f5f9', fontVariantNumeric: 'tabular-nums' }}>
                                            {formatNumber(row.count)}
                                        </td>

                                        <td style={{ padding: '16px 24px', textAlign: 'right', fontWeight: '700', color: '#0f172a', borderBottom: '1px solid #f1f5f9', fontVariantNumeric: 'tabular-nums', tracking: '-0.3px' }}>
                                            {formatCurrency(row.volume)}
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

export default DebitPrepaidMetrics;
