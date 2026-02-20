import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { RefreshCw, Download, Calendar, ArrowRight, ChevronRight, ChevronDown, Loader2 } from 'lucide-react';

const FinanceSummary = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [period, setPeriod] = useState('MONTH'); // TODAY, MONTH, YEAR, PY, CUSTOM
    const [customRange, setCustomRange] = useState({ start: '', end: '' });
    const [showCustomPicker, setShowCustomPicker] = useState(false);

    // Drill-down State
    const [expandedMonth, setExpandedMonth] = useState(null);
    const [expandedDate, setExpandedDate] = useState(null);
    const [dailyData, setDailyData] = useState({});
    const [merchantData, setMerchantData] = useState({});
    const [loadingDaily, setLoadingDaily] = useState(false);
    const [loadingMerchants, setLoadingMerchants] = useState(false);

    useEffect(() => {
        fetchData();
    }, [period]);

    const fetchData = async () => {
        if (period === 'CUSTOM' && (!customRange.start || !customRange.end)) return;
        setLoading(true);
        setExpandedMonth(null); // Reset drill-down
        setExpandedDate(null);
        try {
            const token = localStorage.getItem('token');
            let query = `period=${period}`;
            if (period === 'CUSTOM') {
                query += `&startDate=${customRange.start}&endDate=${customRange.end}`;
            }
            const tenantId = localStorage.getItem('defaultTenantId');
            const hdrs = { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) };
            const res = await fetch(`/api/finance/summary?${query}`, { headers: hdrs });
            if (res.ok) {
                setData(await res.json());
            }
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    const fetchDailyData = async (monthLabel, avgDate) => {
        setLoadingDaily(true);
        try {
            const dateObj = new Date(avgDate);
            const startStr = new Date(dateObj.getFullYear(), dateObj.getMonth(), 1).toISOString().split('T')[0];
            const endStr = new Date(dateObj.getFullYear(), dateObj.getMonth() + 1, 0).toISOString().split('T')[0];

            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const hdrs = { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) };
            const res = await fetch(`/api/finance/summary?period=CUSTOM&groupBy=DAY&startDate=${startStr}&endDate=${endStr}`, { headers: hdrs });
            if (res.ok) {
                const list = await res.json();
                setDailyData(prev => ({ ...prev, [monthLabel]: list }));
            }
        } catch (e) { console.error(e); }
        finally { setLoadingDaily(false); }
    };

    const fetchMerchantData = async (dateStr) => {
        setLoadingMerchants(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const hdrs = { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) };
            const res = await fetch(`/api/finance/summary?period=CUSTOM&groupBy=MERCHANT&startDate=${dateStr}&endDate=${dateStr}`, { headers: hdrs });
            if (res.ok) {
                const list = await res.json();
                setMerchantData(prev => ({ ...prev, [dateStr]: list }));
            }
        } catch (e) { console.error(e); }
        finally { setLoadingMerchants(false); }
    };

    const toggleMonth = (row) => {
        if (expandedMonth === row.month_label) {
            setExpandedMonth(null);
            setExpandedDate(null);
        } else {
            setExpandedMonth(row.month_label);
            setExpandedDate(null);
            if (!dailyData[row.month_label]) {
                fetchDailyData(row.month_label, row.sort_date);
            }
        }
    };

    const toggleDate = (row) => {
        if (expandedDate === row.sort_date) {
            setExpandedDate(null);
        } else {
            setExpandedDate(row.sort_date);
            if (!merchantData[row.sort_date]) {
                fetchMerchantData(row.sort_date); // sort_date is YYYY-MM-DD for daily rows
            }
        }
    };

    const handleApplyCustom = () => {
        setPeriod('CUSTOM');
        fetchData();
        setShowCustomPicker(false);
    };

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 0 }).format(val || 0);
    const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
    // NEW CALCULATION: Volume Share % (Vol / Total Month Vol)
    const formatPct = (vol, totalVol) => {
        if (!totalVol || totalVol === 0) return '0.00%';
        return ((vol / totalVol) * 100).toFixed(2) + '%';
    };

    const totals = data.reduce((acc, row) => {
        const keys = [
            'dom_debit_cnt', 'dom_debit_vol', 'dom_debit_msf', 'dom_debit_optin',
            'dom_credit_cnt', 'dom_credit_vol', 'dom_credit_msf', 'dom_credit_optin',
            'int_cnt', 'int_vol', 'int_msf', 'int_optin',
            'total_vol', 'total_msf'
        ];
        keys.forEach(k => acc[k] = (acc[k] || 0) + (row[k] || 0));
        return acc;
    }, {});


    // --- Reusable Row Component to avoid duplication ---
    const DataRow = ({ row, isExpanded, onClick, level = 1 }) => {
        // level 1: Month, 2: Day, 3: Merchant
        const isMerchant = level === 3;
        const bgClass = isExpanded ? 'bg-blue-50' : (level % 2 === 0 ? 'bg-slate-50' : 'bg-white');
        const paddingLeft = level === 1 ? '8px' : level === 2 ? '30px' : '60px';

        return (
            <tr
                onClick={onClick}
                style={{ cursor: onClick ? 'pointer' : 'default', borderBottom: '1px solid #f1f5f9' }}
                className={`${bgClass} hover:bg-slate-100 transition-colors group`}
            >
                <td style={{ position: 'sticky', left: 0, background: 'inherit', borderRight: '1px solid #e2e8f0', padding: `12px 8px 12px ${paddingLeft}`, fontWeight: '600', color: '#334155', borderBottom: '1px solid #f1f5f9' }}>
                    <div className="flex items-center gap-2">
                        {onClick && (
                            <span className={`transition-transform duration-200 ${isExpanded ? 'rotate-90' : ''}`}>
                                <ChevronRight size={14} className="text-slate-400" />
                            </span>
                        )}
                        {/* Use sort_date as label for Merchants if month_label is same (it reuses logic) or just use row.month_label */}
                        {row.month_label}
                    </div>
                </td>

                {/* Dom Debit */}
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatNumber(row.dom_debit_cnt)}</td>
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '500' }}>{formatCurrency(row.dom_debit_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatCurrency(row.dom_debit_msf)}</td>
                {/* Fixed: Use Total Vol for denominator */}
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatPct(row.dom_debit_vol, row.total_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b', borderRight: '1px solid #f1f5f9' }}>{formatCurrency(row.dom_debit_optin)}</td>

                {/* Dom Credit */}
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatNumber(row.dom_credit_cnt)}</td>
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '500' }}>{formatCurrency(row.dom_credit_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatCurrency(row.dom_credit_msf)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatPct(row.dom_credit_vol, row.total_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b', borderRight: '1px solid #f1f5f9' }}>{formatCurrency(row.dom_credit_optin)}</td>

                {/* International */}
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatNumber(row.int_cnt)}</td>
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '500' }}>{formatCurrency(row.int_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatCurrency(row.int_msf)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatPct(row.int_vol, row.total_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b', borderRight: '1px solid #f1f5f9' }}>{formatCurrency(row.int_optin)}</td>

                {/* Total */}
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '700' }}>{formatCurrency(row.total_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '600', color: '#64748b' }}>{formatCurrency(row.total_msf)}</td>
            </tr>
        );
    };

    return (
        <div className="page-container" style={{ padding: '20px', color: '#1e293b', height: '100vh', display: 'flex', flexDirection: 'column' }}>

            {/* Header */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: '800', color: '#0f172a' }}>Finance Summary Report</h1>
                    <p style={{ color: '#64748b', fontSize: '13px' }}>Consolidated view of performance by card type and channel</p>
                </div>

                <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                    <div style={{ background: '#f1f5f9', padding: '4px', borderRadius: '8px', display: 'flex', gap: '4px' }}>
                        {['TODAY', 'MONTH', 'YEAR', 'PY'].map(p => (
                            <button
                                key={p}
                                onClick={() => { setPeriod(p); setShowCustomPicker(false); }}
                                style={{
                                    padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                    background: period === p ? 'white' : 'transparent',
                                    color: period === p ? '#0f172a' : '#64748b',
                                    boxShadow: period === p ? '0 1px 2px rgba(0,0,0,0.05)' : 'none',
                                    transition: 'all 0.2s'
                                }}
                            >
                                {p === 'PY' ? 'Prev Year' : p}
                            </button>
                        ))}
                        <button
                            onClick={() => setShowCustomPicker(!showCustomPicker)}
                            style={{
                                padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                background: period === 'CUSTOM' ? 'white' : 'transparent',
                                color: period === 'CUSTOM' ? '#0f172a' : '#64748b',
                                boxShadow: period === 'CUSTOM' ? '0 1px 2px rgba(0,0,0,0.05)' : 'none',
                                display: 'flex', alignItems: 'center', gap: '4px'
                            }}
                        >
                            Custom <Calendar size={12} />
                        </button>
                    </div>

                    <button onClick={fetchData} style={{ padding: '8px', background: '#f1f5f9', borderRadius: '8px', border: 'none', cursor: 'pointer' }}>
                        <RefreshCw size={16} color="#64748b" />
                    </button>

                    <button style={{ padding: '8px 16px', background: '#0f172a', color: 'white', borderRadius: '8px', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px' }}>
                        <Download size={16} /> Export
                    </button>
                </div>
            </div>

            {/* Custom Date Picker Popover */}
            {showCustomPicker && (
                <div style={{ position: 'absolute', right: '120px', top: '80px', background: 'white', padding: '16px', borderRadius: '12px', boxShadow: '0 4px 20px rgba(0,0,0,0.1)', zIndex: 50, border: '1px solid #e2e8f0' }}>
                    <div style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '12px' }}>
                        <input type="date" value={customRange.start} onChange={e => setCustomRange({ ...customRange, start: e.target.value })} style={{ padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1' }} />
                        <ArrowRight size={16} color="#94a3b8" />
                        <input type="date" value={customRange.end} onChange={e => setCustomRange({ ...customRange, end: e.target.value })} style={{ padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1' }} />
                    </div>
                    <button onClick={handleApplyCustom} style={{ width: '100%', padding: '8px', background: '#3b82f6', color: 'white', border: 'none', borderRadius: '6px', fontWeight: '600', cursor: 'pointer' }}>
                        Apply Filter
                    </button>
                </div>
            )}

            {/* Table Container */}
            <div style={{ flex: 1, overflow: 'auto', border: '1px solid #e2e8f0', borderRadius: '8px', background: 'white', position: 'relative' }}>
                <table style={{ minWidth: '1400px', width: '100%', borderCollapse: 'separate', borderSpacing: 0 }}>
                    <thead style={{ position: 'sticky', top: 0, zIndex: 10, background: 'white' }}>
                        <tr style={{ height: '40px' }}>
                            <th style={{ position: 'sticky', left: 0, zIndex: 20, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', borderRight: '1px solid #e2e8f0', minWidth: '220px' }}></th>
                            <th colSpan="5" style={{ background: '#e0f2fe', borderBottom: '1px solid #bae6fd', borderRight: '1px solid #e2e8f0', color: '#0369a1', fontSize: '12px', fontWeight: '700', textTransform: 'uppercase' }}>Domestic Debit & Prepaid</th>
                            <th colSpan="5" style={{ background: '#dcfce7', borderBottom: '1px solid #bbf7d0', borderRight: '1px solid #e2e8f0', color: '#15803d', fontSize: '12px', fontWeight: '700', textTransform: 'uppercase' }}>Domestic Credit</th>
                            <th colSpan="5" style={{ background: '#ffedd5', borderBottom: '1px solid #fed7aa', borderRight: '1px solid #e2e8f0', color: '#c2410c', fontSize: '12px', fontWeight: '700', textTransform: 'uppercase' }}>International</th>
                            <th colSpan="2" style={{ background: '#f1f5f9', borderBottom: '1px solid #e2e8f0', color: '#334155', fontSize: '12px', fontWeight: '700', textTransform: 'uppercase' }}>Total</th>
                        </tr>
                        <tr style={{ height: '40px', background: '#f8fafc', fontSize: '11px', color: '#64748b' }}>
                            <th style={{ position: 'sticky', left: 0, zIndex: 20, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', borderRight: '1px solid #e2e8f0', padding: '8px', textAlign: 'left' }}>Month</th>
                            {['Count', 'Volume', 'MSF', '%', 'Opt-in Vol', 'Count', 'Volume', 'MSF', '%', 'Opt-in Vol', 'Count', 'Volume', 'MSF', '%', 'Opt-in Vol', 'Volume', 'MSF'].map((h, i) => (
                                <th key={i} style={{ borderBottom: '1px solid #e2e8f0', padding: '8px', borderRight: (i + 1) % 5 === 0 ? '1px solid #e2e8f0' : 'none' }}>{h}</th>
                            ))}
                        </tr>
                    </thead>
                    <tbody style={{ fontSize: '12px' }}>
                        {loading ? (
                            <tr><td colSpan="18" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>Loading Financial Data...</td></tr>
                        ) : data.length === 0 ? (
                            <tr><td colSpan="18" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>No data for selected period</td></tr>
                        ) : (
                            data.map((row) => (
                                <React.Fragment key={row.month_label}>
                                    {/* Level 1: Month */}
                                    <DataRow row={row} isExpanded={expandedMonth === row.month_label} onClick={() => toggleMonth(row)} level={1} />

                                    {/* Level 2: Days */}
                                    {expandedMonth === row.month_label && (
                                        <>
                                            {loadingDaily && <tr><td colSpan="18" className="text-center py-4 bg-slate-50"><Loader2 className="animate-spin inline text-slate-400" size={16} /></td></tr>}
                                            {(dailyData[row.month_label] || []).map(day => (
                                                <React.Fragment key={day.sort_date}>
                                                    <DataRow row={day} isExpanded={expandedDate === day.sort_date} onClick={() => toggleDate(day)} level={2} />

                                                    {/* Level 3: Merchants */}
                                                    {expandedDate === day.sort_date && (
                                                        <>
                                                            {loadingMerchants && <tr><td colSpan="18" className="text-center py-4 bg-slate-50 ml-10"><Loader2 className="animate-spin inline text-indigo-400" size={16} /></td></tr>}
                                                            {(merchantData[day.sort_date] || []).map(merch => (
                                                                <DataRow key={merch.merchant_id} row={merch} isExpanded={false} onClick={null} level={3} />
                                                            ))}
                                                            {merchantData[day.sort_date]?.length === 0 && (
                                                                <tr><td colSpan="18" className="pl-20 py-2 italic text-slate-400 bg-slate-50">No merchant data available</td></tr>
                                                            )}
                                                        </>
                                                    )}
                                                </React.Fragment>
                                            ))}
                                        </>
                                    )}
                                </React.Fragment>
                            ))
                        )}
                    </tbody>
                    <tfoot style={{ position: 'sticky', bottom: 0, zIndex: 10, background: '#f1f5f9', fontWeight: '700' }}>
                        <tr>
                            <td style={{ position: 'sticky', left: 0, background: '#f1f5f9', borderRight: '1px solid #cbd5e1', padding: '12px 8px', borderTop: '2px solid #cbd5e1' }}>TOTAL</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatNumber(totals.dom_debit_cnt)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.dom_debit_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.dom_debit_msf)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatPct(totals.dom_debit_vol, totals.total_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1', borderRight: '1px solid #cbd5e1' }}>{formatCurrency(totals.dom_debit_optin)}</td>

                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatNumber(totals.dom_credit_cnt)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.dom_credit_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.dom_credit_msf)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatPct(totals.dom_credit_vol, totals.total_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1', borderRight: '1px solid #cbd5e1' }}>{formatCurrency(totals.dom_credit_optin)}</td>

                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatNumber(totals.int_cnt)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.int_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.int_msf)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatPct(totals.int_vol, totals.total_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1', borderRight: '1px solid #cbd5e1' }}>{formatCurrency(totals.int_optin)}</td>

                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.total_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.total_msf)}</td>
                        </tr>
                    </tfoot>
                </table>
            </div>
        </div>
    );
};

export default FinanceSummary;
