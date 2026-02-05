import React, { useState, useEffect } from 'react';
import { Download, Filter, Search, Calendar, ChevronRight, Loader2, PieChart } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

const TransactionTrendsHub = () => {
    // --- State ---
    const [filters, setFilters] = useState({
        datePreset: 'CURRENT_YEAR',
        dateFrom: '',
        dateTo: '',
        year: 2025,
        mcc: [],
        rm: [],
        mid: [],
        optStatus: 'ALL'
    });

    const [monthlyData, setMonthlyData] = useState([]);
    const [dailyData, setDailyData] = useState({});
    const [merchantData, setMerchantData] = useState({});

    // Expansion State
    const [expandedMonth, setExpandedMonth] = useState(null);
    const [expandedDate, setExpandedDate] = useState(null);

    const [loading, setLoading] = useState(false);
    const [loadingDaily, setLoadingDaily] = useState(false);
    const [loadingMerchants, setLoadingMerchants] = useState(false);

    // Fetch Monthly
    const fetchMonthly = async () => {
        setLoading(true);
        setExpandedMonth(null);
        setExpandedDate(null);
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('http://localhost:8081/api/trends/monthly', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                body: JSON.stringify(filters)
            });
            if (res.ok) setMonthlyData(await res.json());
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    // Auto-fetch
    useEffect(() => { fetchMonthly(); }, [filters.year, filters.optStatus, filters.datePreset]); // Auto-refresh on simple filter changes

    // Toggle Details logic (Level 1 -> 2)
    const toggleMonth = async (monthNum, year) => {
        if (expandedMonth === monthNum) {
            setExpandedMonth(null);
            setExpandedDate(null);
            return;
        }
        setExpandedMonth(monthNum);
        setExpandedDate(null);

        if (!dailyData[monthNum]) {
            setLoadingDaily(true);
            try {
                const token = localStorage.getItem('token');
                const res = await fetch('http://localhost:8081/api/trends/daily', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                    body: JSON.stringify({ ...filters, month: monthNum, year: year })
                });
                if (res.ok) {
                    const data = await res.json();
                    setDailyData(prev => ({ ...prev, [monthNum]: data }));
                }
            } catch (e) { console.error(e); }
            finally { setLoadingDaily(false); }
        }
    };

    // Toggle Date Breakdwon logic (Level 2 -> 3)
    const toggleDate = async (dateStr) => {
        if (expandedDate === dateStr) {
            setExpandedDate(null);
            return;
        }
        setExpandedDate(dateStr);

        if (!merchantData[dateStr]) {
            setLoadingMerchants(true);
            try {
                const token = localStorage.getItem('token');
                const res = await fetch(`http://localhost:8081/api/finance/profitability?groupBy=merchant&from=${dateStr}&to=${dateStr}&size=100`, {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (res.ok) {
                    const json = await res.json();
                    const list = json.content || json;
                    setMerchantData(prev => ({ ...prev, [dateStr]: list }));
                }
            } catch (e) { console.error(e); }
            finally { setLoadingMerchants(false); }
        }
    };

    const fmt = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', minimumFractionDigits: 0 }).format(val || 0);
    const fmtInt = (val) => new Intl.NumberFormat('en-US').format(val || 0);

    // Safer month name helpers
    const getMonthName = (m) => {
        if (!m) return '';
        const date = new Date(2025, m - 1, 1); // Use specific year and day 1 to avoid overflow
        return date.toLocaleString('default', { month: 'long' });
    };

    const PRESETS = [
        { label: "This Year", value: "CURRENT_YEAR" },
        { label: "Last Year", value: "PREVIOUS_YEAR" },
        { label: "Custom", value: "CUSTOM" }
    ];

    return (
        <div className="page-container" style={{ padding: '20px', color: '#1e293b', height: '100vh', display: 'flex', flexDirection: 'column' }}>

            {/* Header */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: '800', color: '#0f172a', display: 'flex', alignItems: 'center', gap: '10px' }}>
                        Transaction Hub
                    </h1>
                    <p style={{ color: '#64748b', fontSize: '13px' }}>Volume trends and fee generation analysis</p>
                </div>

                <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                    <div style={{ background: '#f1f5f9', padding: '4px', borderRadius: '8px', display: 'flex', gap: '4px', alignItems: 'center' }}>
                        {PRESETS.map(p => (
                            <button
                                key={p.value}
                                onClick={() => setFilters({ ...filters, datePreset: p.value })}
                                style={{
                                    padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                    background: filters.datePreset === p.value ? 'white' : 'transparent',
                                    color: filters.datePreset === p.value ? '#0f172a' : '#64748b',
                                    boxShadow: filters.datePreset === p.value ? '0 1px 2px rgba(0,0,0,0.05)' : 'none',
                                    transition: 'all 0.2s'
                                }}
                            >
                                {p.label}
                            </button>
                        ))}
                        {filters.datePreset === 'CUSTOM' && (
                            <div style={{ display: 'flex', gap: '4px', marginLeft: '8px', paddingRight: '8px' }}>
                                <input
                                    type="date"
                                    value={filters.dateFrom}
                                    onChange={(e) => setFilters({ ...filters, dateFrom: e.target.value })}
                                    style={{ fontSize: '11px', padding: '4px', borderRadius: '4px', border: '1px solid #cbd5e1' }}
                                />
                                <span style={{ fontSize: '12px', color: '#94a3b8' }}>-</span>
                                <input
                                    type="date"
                                    value={filters.dateTo}
                                    onChange={(e) => setFilters({ ...filters, dateTo: e.target.value })}
                                    style={{ fontSize: '11px', padding: '4px', borderRadius: '4px', border: '1px solid #cbd5e1' }}
                                />
                                <button onClick={fetchMonthly} style={{ fontSize: '10px', padding: '4px 8px', background: '#3b82f6', color: 'white', borderRadius: '4px', border: 'none', cursor: 'pointer' }}>Go</button>
                            </div>
                        )}
                    </div>

                    <button style={{ padding: '8px 16px', background: '#0f172a', color: 'white', borderRadius: '8px', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px' }}>
                        <Download size={16} /> Export
                    </button>
                </div>
            </div>

            {/* Table Container */}
            <div style={{ flex: 1, overflow: 'auto', border: '1px solid #e2e8f0', borderRadius: '8px', background: 'white', position: 'relative' }}>
                <table style={{ minWidth: '1000px', width: '100%', borderCollapse: 'separate', borderSpacing: 0 }}>
                    <thead style={{ position: 'sticky', top: 0, zIndex: 10, background: 'white' }}>
                        <tr style={{ height: '40px', background: '#f8fafc', fontSize: '11px', color: '#64748b' }}>
                            <th style={{ position: 'sticky', left: 0, zIndex: 20, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', borderRight: '1px solid #e2e8f0', padding: '12px', textAlign: 'left', textTransform: 'uppercase' }}>Period</th>
                            <th style={{ borderBottom: '1px solid #e2e8f0', padding: '12px', textAlign: 'right', textTransform: 'uppercase' }}>Transactions</th>
                            <th style={{ borderBottom: '1px solid #e2e8f0', padding: '12px', textAlign: 'right', textTransform: 'uppercase' }}>Volume</th>
                            <th style={{ borderBottom: '1px solid #e2e8f0', padding: '12px', textAlign: 'right', textTransform: 'uppercase' }}>MSF</th>
                            <th style={{ borderBottom: '1px solid #e2e8f0', padding: '12px', textAlign: 'right', textTransform: 'uppercase' }}>Opt-In Volume</th>
                        </tr>
                    </thead>
                    <tbody style={{ fontSize: '12px' }}>
                        {loading ? (
                            <tr><td colSpan="5" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>Loading Data...</td></tr>
                        ) : monthlyData.length === 0 ? (
                            <tr><td colSpan="5" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>No data for selected period</td></tr>
                        ) : (
                            monthlyData.map((row) => (
                                <React.Fragment key={row.month_num}>
                                    <tr
                                        onClick={() => toggleMonth(row.month_num, row.year)}
                                        style={{ borderBottom: '1px solid #f1f5f9', cursor: 'pointer', background: expandedMonth === row.month_num ? '#f0f9ff' : 'white' }}
                                        className="hover:bg-slate-50 transition-colors"
                                    >
                                        <td style={{ position: 'sticky', left: 0, background: 'inherit', borderRight: '1px solid #e2e8f0', padding: '12px', fontWeight: '600', color: '#334155', borderBottom: '1px solid #f1f5f9' }}>
                                            <div className="flex items-center gap-2">
                                                <ChevronRight size={14} className={`text-slate-400 transition-transform ${expandedMonth === row.month_num ? 'rotate-90' : ''}`} />
                                                {row.month_name ? row.month_name.trim() : ''} <span className="text-xs text-slate-400 font-normal">{row.year}</span>
                                            </div>
                                        </td>
                                        <td style={{ padding: '12px', textAlign: 'right', color: '#64748b' }}>{fmtInt(row.count)}</td>
                                        <td style={{ padding: '12px', textAlign: 'right', fontWeight: '600' }}>{fmt(row.volume)}</td>
                                        <td style={{ padding: '12px', textAlign: 'right', color: 'green' }}>{fmt(row.msf)}</td>
                                        <td style={{ padding: '12px', textAlign: 'right', color: '#64748b' }}>{fmt(row.opt_in_volume)}</td>
                                    </tr>

                                    {/* Level 2: Daily */}
                                    {expandedMonth === row.month_num && (
                                        <>
                                            {loadingDaily && <tr><td colSpan="5" className="text-center py-2"><Loader2 className="animate-spin inline" size={16} /></td></tr>}
                                            {(dailyData[row.month_num] || []).map(day => (
                                                <React.Fragment key={day.date}>
                                                    <tr
                                                        onClick={() => toggleDate(day.date)}
                                                        style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0', cursor: 'pointer' }}
                                                    >
                                                        <td style={{ padding: '8px 8px 8px 40px', fontWeight: '500', color: '#475569', fontSize: '11px', borderRight: '1px solid #e2e8f0' }}>
                                                            <div className="flex items-center gap-2">
                                                                <ChevronRight size={12} className={`text-slate-400 transition-transform ${expandedDate === day.date ? 'rotate-90' : ''}`} />
                                                                {day.date}
                                                            </div>
                                                        </td>
                                                        <td style={{ padding: '8px', textAlign: 'right', fontSize: '11px' }}>{fmtInt(day.count)}</td>
                                                        <td style={{ padding: '8px', textAlign: 'right', fontSize: '11px' }}>{fmt(day.volume)}</td>
                                                        <td style={{ padding: '8px', textAlign: 'right', fontSize: '11px' }}>{fmt(day.msf)}</td>
                                                        <td style={{ padding: '8px', textAlign: 'right', fontSize: '11px' }}>{fmt(day.opt_in_volume)}</td>
                                                    </tr>

                                                    {/* Level 3: Merchant */}
                                                    {expandedDate === day.date && (
                                                        <>
                                                            {loadingMerchants && <tr><td colSpan="5" className="text-center py-2"><Loader2 className="animate-spin inline text-indigo-500" size={14} /></td></tr>}
                                                            {(merchantData[day.date] || []).map((m, idx) => (
                                                                <tr key={idx} style={{ background: '#fff', borderBottom: '1px solid #f1f5f9' }}>
                                                                    <td style={{ padding: '6px 6px 6px 70px', color: '#64748b', fontSize: '11px', fontStyle: 'italic', borderRight: '1px solid #f1f5f9' }}>
                                                                        {m.name || m.merchantName}
                                                                    </td>
                                                                    <td style={{ padding: '6px', textAlign: 'right', fontSize: '11px', color: '#94a3b8' }}>{fmtInt(m.totalTxns)}</td>
                                                                    <td style={{ padding: '6px', textAlign: 'right', fontSize: '11px', color: '#94a3b8' }}>{fmt(m.totalVolume)}</td>
                                                                    <td style={{ padding: '6px' }}></td>
                                                                    <td style={{ padding: '6px' }}></td>
                                                                </tr>
                                                            ))}
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
                </table>
            </div>
        </div>
    );
};

export default TransactionTrendsHub;
