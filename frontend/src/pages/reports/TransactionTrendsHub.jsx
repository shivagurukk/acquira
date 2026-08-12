import React, { useState, useEffect, useRef } from 'react';
import { Download, ChevronRight, Loader2, AlertTriangle } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { formatMsf, formatCurrency, resolveDecimals } from '../../utils/formatters';

const TransactionTrendsHub = () => {
    const { tenantVersion, currencyCode, currencyDecimals } = useAuth();
    // --- State ---
    const [filters, setFilters] = useState({
        datePreset: 'CURRENT_YEAR',
        dateFrom: '',
        dateTo: '',
        // Default to the current calendar year. (Was hardcoded to 2025.)
        year: new Date().getFullYear(),
        mcc: [],
        rm: [],
        mid: [],
        optStatus: 'ALL'
    });

    const [monthlyData, setMonthlyData] = useState([]);
    // Level-2/3 caches. Keyed by `${year}-${month}` / date string. Both are
    // CLEARED whenever the monthly query re-runs — previously the cache was
    // keyed by month number alone, so switching "This Year" → "Last Year" and
    // expanding January replayed the OTHER year's cached days.
    const [dailyData, setDailyData] = useState({});
    const [merchantData, setMerchantData] = useState({});

    // Expansion state (monthKey = `${year}-${month}` so a custom range that
    // spans a year boundary can hold two Januaries without colliding).
    const [expandedMonth, setExpandedMonth] = useState(null);
    const [expandedDate, setExpandedDate] = useState(null);

    const [loading, setLoading] = useState(false);
    const [loadingDaily, setLoadingDaily] = useState(false);
    const [loadingMerchants, setLoadingMerchants] = useState(false);

    // Failures used to be console-only: a 500/403 rendered exactly like an
    // empty portfolio. Each level now carries an error the table displays.
    const [error, setError] = useState(null);
    const [drillError, setDrillError] = useState(null);

    // Monotonic request id — a slower, older monthly response must never
    // overwrite a newer one after rapid preset toggling.
    const reqIdRef = useRef(0);

    // Fetch Monthly
    const fetchMonthly = async () => {
        const reqId = ++reqIdRef.current;
        setLoading(true);
        setError(null);
        setExpandedMonth(null);
        setExpandedDate(null);
        setDailyData({});
        setMerchantData({});
        try {
            // Use api/axios.js — relative URL + auto Authorization + auto X-Tenant-Id
            const res = await api.post('/trends/monthly', filters);
            if (reqId !== reqIdRef.current) return; // superseded — discard
            setMonthlyData(res.data || []);
        } catch (e) {
            if (reqId !== reqIdRef.current) return;
            console.error(e);
            setMonthlyData([]);
            setError(e?.response?.data?.error || e?.response?.statusText || 'Could not load trends.');
        } finally {
            if (reqId === reqIdRef.current) setLoading(false);
        }
    };

    // Auto-fetch. Skips CUSTOM until both dates are set — selecting "Custom"
    // used to fire a full-year query immediately, thrown away on "Go".
    useEffect(() => {
        if (filters.datePreset === 'CUSTOM' && (!filters.dateFrom || !filters.dateTo)) return;
        fetchMonthly();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filters.year, filters.optStatus, filters.datePreset, tenantVersion]);

    // Toggle Details logic (Level 1 -> 2)
    const toggleMonth = async (monthKey, monthNum, year) => {
        if (expandedMonth === monthKey) {
            setExpandedMonth(null);
            setExpandedDate(null);
            return;
        }
        setExpandedMonth(monthKey);
        setExpandedDate(null);
        setDrillError(null);

        if (!dailyData[monthKey]) {
            setLoadingDaily(true);
            try {
                const res = await api.post('/trends/daily', { ...filters, month: monthNum, year });
                setDailyData(prev => ({ ...prev, [monthKey]: res.data || [] }));
            } catch (e) {
                console.error(e);
                setDrillError('Could not load the daily breakdown. Click the month to retry.');
            }
            finally { setLoadingDaily(false); }
        }
    };

    // Toggle Date Breakdown logic (Level 2 -> 3).
    // Reads /trends/merchants — the SAME table and filters as the parent rows,
    // top 100 by volume with a real total count. The old /finance/profitability
    // call read a different (settlement-grain) table with no filters, so the
    // merchant rows never summed to the day row above them.
    const toggleDate = async (dateStr) => {
        if (expandedDate === dateStr) {
            setExpandedDate(null);
            return;
        }
        setExpandedDate(dateStr);
        setDrillError(null);

        if (!merchantData[dateStr]) {
            setLoadingMerchants(true);
            try {
                const res = await api.post('/trends/merchants', { ...filters, dateFrom: dateStr, dateTo: dateStr });
                setMerchantData(prev => ({ ...prev, [dateStr]: {
                    rows: res.data?.rows || [],
                    totalMerchants: res.data?.totalMerchants ?? (res.data?.rows || []).length,
                } }));
            } catch (e) {
                console.error(e);
                setDrillError('Could not load the merchant breakdown. Click the date to retry.');
            }
            finally { setLoadingMerchants(false); }
        }
    };

    // Export the monthly table as CSV (client-side — the table is small).
    const exportCsv = () => {
        if (!monthlyData.length) return;
        const esc = (v) => {
            const s = String(v ?? '');
            return `"${(/^[=+\-@]/.test(s) ? `'${s}` : s).replace(/"/g, '""')}"`;
        };
        // Money columns use the tenant's decimals (3 for BHD) and the file
        // states its currency, so a BHD export is not silently truncated to fils.
        const dp = resolveDecimals(currencyDecimals, currencyCode);
        const lines = [
            `Currency,${currencyCode || 'UNKNOWN'}`,
            ['Month', 'Year', 'Transactions', 'Volume', 'MSF', 'Opt-In Volume'].join(','),
        ];
        monthlyData.forEach(r => lines.push([
            esc(r.month_name), r.year, r.count,
            Number(r.volume || 0).toFixed(dp), Number(r.msf || 0).toFixed(Math.max(4, dp)),
            Number(r.opt_in_volume || 0).toFixed(dp),
        ].join(',')));
        const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `transaction-trends-${filters.datePreset.toLowerCase()}.csv`;
        a.click();
        URL.revokeObjectURL(a.href);
    };

    // No 'AED' fallback and no whole-unit rounding — the central formatter
    // applies the tenant's currency and decimals (3dp for BHD).
    const fmt = (val) => formatCurrency(val);
    const fmtInt = (val) => new Intl.NumberFormat('en-US').format(val || 0);

    const PRESETS = [
        { label: "This Year", value: "CURRENT_YEAR" },
        { label: "Last Year", value: "PREVIOUS_YEAR" },
        { label: "Custom", value: "CUSTOM" }
    ];

    return (
        <div className="page-container" style={{ padding: '20px', color: '#1e293b', height: 'var(--vh100, 100vh)', display: 'flex', flexDirection: 'column' }}>

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

                    <button onClick={exportCsv} disabled={!monthlyData.length}
                        style={{ padding: '8px 16px', background: '#0f172a', color: 'white', borderRadius: '8px', border: 'none',
                            cursor: monthlyData.length ? 'pointer' : 'default', opacity: monthlyData.length ? 1 : 0.5,
                            display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px' }}>
                        <Download size={16} /> Export
                    </button>
                </div>
            </div>

            {/* Error banners */}
            {error && (
                <div role="alert" style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px', marginBottom: 12,
                    borderRadius: 8, border: '1px solid #fecaca', background: '#fef2f2', color: '#991b1b', fontSize: 13, fontWeight: 600 }}>
                    <AlertTriangle size={15} /> {error}
                    <button onClick={fetchMonthly} style={{ marginLeft: 'auto', fontSize: 12, fontWeight: 700, color: '#2563eb',
                        background: '#fff', border: '1px solid #e2e8f0', borderRadius: 6, padding: '4px 10px', cursor: 'pointer' }}>Retry</button>
                </div>
            )}
            {drillError && (
                <div role="alert" style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 14px', marginBottom: 12,
                    borderRadius: 8, border: '1px solid #fed7aa', background: '#fffbeb', color: '#92400e', fontSize: 13, fontWeight: 600 }}>
                    <AlertTriangle size={14} /> {drillError}
                </div>
            )}

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
                            <tr><td colSpan="5" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>
                                {error ? 'Trends could not be loaded.' : 'No data for selected period'}
                            </td></tr>
                        ) : (
                            monthlyData.map((row) => {
                                const monthKey = `${row.year}-${row.month_num}`;
                                return (
                                <React.Fragment key={monthKey}>
                                    <tr
                                        onClick={() => toggleMonth(monthKey, row.month_num, row.year)}
                                        style={{ borderBottom: '1px solid #f1f5f9', cursor: 'pointer', background: expandedMonth === monthKey ? '#f0f9ff' : 'white' }}
                                        className="hover:bg-slate-50 transition-colors"
                                    >
                                        <td style={{ position: 'sticky', left: 0, background: 'inherit', borderRight: '1px solid #e2e8f0', padding: '12px', fontWeight: '600', color: '#334155', borderBottom: '1px solid #f1f5f9' }}>
                                            <div className="flex items-center gap-2">
                                                <ChevronRight size={14} className={`text-slate-400 transition-transform ${expandedMonth === monthKey ? 'rotate-90' : ''}`} />
                                                {row.month_name ? row.month_name.trim() : ''} <span className="text-xs text-slate-400 font-normal">{row.year}</span>
                                            </div>
                                        </td>
                                        <td style={{ padding: '12px', textAlign: 'right', color: '#64748b' }}>{fmtInt(row.count)}</td>
                                        <td style={{ padding: '12px', textAlign: 'right', fontWeight: '600' }}>{fmt(row.volume)}</td>
                                        <td style={{ padding: '12px', textAlign: 'right', color: 'green' }}>{formatMsf(row.msf, currencyCode)}</td>
                                        <td style={{ padding: '12px', textAlign: 'right', color: '#64748b' }}>{fmt(row.opt_in_volume)}</td>
                                    </tr>

                                    {/* Level 2: Daily */}
                                    {expandedMonth === monthKey && (
                                        <>
                                            {loadingDaily && <tr><td colSpan="5" className="text-center py-2"><Loader2 className="animate-spin inline" size={16} /></td></tr>}
                                            {(dailyData[monthKey] || []).map(day => (
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
                                                        <td style={{ padding: '8px', textAlign: 'right', fontSize: '11px' }}>{formatMsf(day.msf, currencyCode)}</td>
                                                        <td style={{ padding: '8px', textAlign: 'right', fontSize: '11px' }}>{fmt(day.opt_in_volume)}</td>
                                                    </tr>

                                                    {/* Level 3: Merchant (same table + filters as the parent day) */}
                                                    {expandedDate === day.date && (
                                                        <>
                                                            {loadingMerchants && <tr><td colSpan="5" className="text-center py-2"><Loader2 className="animate-spin inline text-indigo-500" size={14} /></td></tr>}
                                                            {merchantData[day.date] && merchantData[day.date].totalMerchants > merchantData[day.date].rows.length && (
                                                                <tr style={{ background: '#fff' }}>
                                                                    <td colSpan="5" style={{ padding: '4px 6px 4px 70px', color: '#94a3b8', fontSize: '10.5px', fontStyle: 'italic' }}>
                                                                        Top {merchantData[day.date].rows.length} of {fmtInt(merchantData[day.date].totalMerchants)} merchants by volume
                                                                    </td>
                                                                </tr>
                                                            )}
                                                            {(merchantData[day.date]?.rows || []).map((m) => (
                                                                <tr key={m.mid} style={{ background: '#fff', borderBottom: '1px solid #f1f5f9' }}>
                                                                    <td style={{ padding: '6px 6px 6px 70px', color: '#64748b', fontSize: '11px', fontStyle: 'italic', borderRight: '1px solid #f1f5f9' }}>
                                                                        {m.name || m.mid}
                                                                    </td>
                                                                    <td style={{ padding: '6px', textAlign: 'right', fontSize: '11px', color: '#94a3b8' }}>{fmtInt(m.count)}</td>
                                                                    <td style={{ padding: '6px', textAlign: 'right', fontSize: '11px', color: '#94a3b8' }}>{fmt(m.volume)}</td>
                                                                    <td style={{ padding: '6px', textAlign: 'right', fontSize: '11px', color: '#94a3b8' }}>{formatMsf(m.msf, currencyCode)}</td>
                                                                    <td style={{ padding: '6px', textAlign: 'right', fontSize: '11px', color: '#94a3b8' }}>{fmt(m.opt_in_volume)}</td>
                                                                </tr>
                                                            ))}
                                                        </>
                                                    )}
                                                </React.Fragment>
                                            ))}
                                        </>
                                    )}
                                </React.Fragment>
                            );})
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default TransactionTrendsHub;
