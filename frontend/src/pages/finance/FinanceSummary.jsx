import React, { useState, useEffect, useMemo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { RefreshCw, Download, Calendar, ArrowRight, ChevronRight, ChevronDown, Loader2 } from 'lucide-react';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt, formatMsf } from '../../utils/formatters';
import api from '../../api/axios';

const FinanceSummary = () => {
    const { currencySymbol, tenantVersion } = useAuth();
    const formatCurrency = useMemo(() => createFmt(currencySymbol).currency, [currencySymbol]);
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    // Every fetch used to `catch { console.error }`, so a 500/403/timeout looked
    // EXACTLY like a genuinely empty period ("No data for selected period").
    // That is why an erroring report was indistinguishable from an empty one.
    const [error, setError] = useState(null);
    // Default to YEAR rather than MONTH — the current month is often empty when
    // transaction data lags real-time (e.g. it's May but data ends in April). YEAR
    // is more likely to render something useful on first load. User can still pick
    // TODAY/MONTH/PY/CUSTOM via the period chips.
    const [period, setPeriod] = useState('YEAR');
    const [customRange, setCustomRange] = useState({ start: '', end: '' });
    const [showCustomPicker, setShowCustomPicker] = useState(false);
    // Bumped by "Apply Filter" so a custom range can be re-applied without the
    // period value itself changing.
    const [customApplied, setCustomApplied] = useState(0);

    // Drill-down State
    const [expandedMonth, setExpandedMonth] = useState(null);
    const [expandedDate, setExpandedDate] = useState(null);
    const [dailyData, setDailyData] = useState({});
    const [merchantData, setMerchantData] = useState({});
    const [loadingDaily, setLoadingDaily] = useState(false);
    const [loadingMerchants, setLoadingMerchants] = useState(false);

    useEffect(() => {
        fetchData();
    }, [period, tenantVersion, customApplied]);

    /** Best-effort message out of an axios error — the API returns {error: "..."}. */
    const errMsg = (e, fallback) =>
        e?.response?.data?.error || e?.response?.statusText || e?.message || fallback;

    // Local YYYY-MM-DD. toISOString() shifts by the browser's UTC offset, which
    // for any timezone ahead of UTC turned "1st of the month" into the last day
    // of the PREVIOUS month — so the day drill-down requested the wrong range.
    const ymd = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

    const fetchData = async () => {
        if (period === 'CUSTOM' && (!customRange.start || !customRange.end)) return;
        setLoading(true);
        setError(null);
        setExpandedMonth(null);
        setExpandedDate(null);
        // Drop the drill-down caches too. They are keyed by month/date only, so
        // after a tenant switch (or a period change) the previously loaded rows
        // would be re-displayed under the new context — showing another bank's
        // daily and merchant figures.
        setDailyData({});
        setMerchantData({});
        try {
            let query = `period=${period}`;
            if (period === 'CUSTOM') {
                query += `&startDate=${customRange.start}&endDate=${customRange.end}`;
            }
            const res = await api.get(`/finance/summary?${query}`);
            setData(Array.isArray(res.data) ? res.data : []);
        } catch (e) {
            console.error(e);
            setData([]);
            setError(errMsg(e, 'Could not load the finance summary.'));
        }
        finally { setLoading(false); }
    };

    const fetchDailyData = async (monthLabel, avgDate) => {
        setLoadingDaily(true);
        try {
            const dateObj = new Date(avgDate);
            const startStr = ymd(new Date(dateObj.getFullYear(), dateObj.getMonth(), 1));
            const endStr = ymd(new Date(dateObj.getFullYear(), dateObj.getMonth() + 1, 0));
            const res = await api.get(`/finance/summary?period=CUSTOM&groupBy=DAY&startDate=${startStr}&endDate=${endStr}`);
            setDailyData(prev => ({ ...prev, [monthLabel]: Array.isArray(res.data) ? res.data : [] }));
        } catch (e) {
            console.error(e);
            // Mark the drill-down as failed rather than leaving it indistinguishable
            // from "this month genuinely has no daily rows".
            setDailyData(prev => ({ ...prev, [monthLabel]: { error: errMsg(e, 'Could not load daily detail.') } }));
        }
        finally { setLoadingDaily(false); }
    };

    const fetchMerchantData = async (dateStr) => {
        setLoadingMerchants(true);
        try {
            const res = await api.get(`/finance/summary?period=CUSTOM&groupBy=MERCHANT&startDate=${dateStr}&endDate=${dateStr}`);
            setMerchantData(prev => ({ ...prev, [dateStr]: Array.isArray(res.data) ? res.data : [] }));
        } catch (e) {
            console.error(e);
            setMerchantData(prev => ({ ...prev, [dateStr]: { error: errMsg(e, 'Could not load merchant detail.') } }));
        }
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

    // The Export button previously had no onClick at all — it rendered and did
    // nothing. Emits the month rows currently on screen, in the same three-bucket
    // layout as the table.
    const handleExport = () => {
        if (!data.length) return;
        const cell = (v) => {
            const s = v === null || v === undefined ? '' : String(v);
            const safe = /^[=+\-@\t\r]/.test(s) ? `'${s}` : s;   // neutralise CSV formula injection
            return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
        };
        const header = [
            'Period',
            'Local Debit & Prepaid Count', 'Local Debit & Prepaid Volume', 'Local Debit & Prepaid MSF', 'Local Debit & Prepaid Volume %',
            'Local Credit Count', 'Local Credit Volume', 'Local Credit MSF', 'Local Credit Volume %',
            'International Count', 'International Volume', 'International MSF', 'International Volume %', 'International Opt-in Volume',
            'Total Volume', 'Total MSF',
        ];
        const pct = (v, t) => (!t ? '0.00' : ((v / t) * 100).toFixed(2));
        const lines = [header.map(cell).join(',')];
        for (const r of data) {
            lines.push([
                r.month_label,
                r.dom_debit_cnt || 0, r.dom_debit_vol || 0, r.dom_debit_msf || 0, pct(r.dom_debit_vol, r.total_vol),
                r.dom_credit_cnt || 0, r.dom_credit_vol || 0, r.dom_credit_msf || 0, pct(r.dom_credit_vol, r.total_vol),
                r.int_cnt || 0, r.int_vol || 0, r.int_msf || 0, pct(r.int_vol, r.total_vol), r.int_optin || 0,
                r.total_vol || 0, r.total_msf || 0,
            ].map(cell).join(','));
        }
        lines.push([
            'TOTAL',
            totals.dom_debit_cnt || 0, totals.dom_debit_vol || 0, totals.dom_debit_msf || 0, pct(totals.dom_debit_vol, totals.total_vol),
            totals.dom_credit_cnt || 0, totals.dom_credit_vol || 0, totals.dom_credit_msf || 0, pct(totals.dom_credit_vol, totals.total_vol),
            totals.int_cnt || 0, totals.int_vol || 0, totals.int_msf || 0, pct(totals.int_vol, totals.total_vol), totals.int_optin || 0,
            totals.total_vol || 0, totals.total_msf || 0,
        ].map(cell).join(','));

        // Leading BOM so Excel reads it as UTF-8.
        const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `finance-summary-${period.toLowerCase()}-${ymd(new Date())}.csv`;
        document.body.appendChild(a); a.click(); a.remove();
        URL.revokeObjectURL(url);
    };

    // Only signal intent — the effect below does the fetching. Calling fetchData()
    // directly here read `period` from a stale closure, so switching (say) YEAR →
    // CUSTOM fired a request for YEAR, and the effect then fired a second one for
    // CUSTOM; whichever landed last won, so the table could show the wrong range.
    const handleApplyCustom = () => {
        setPeriod('CUSTOM');
        setCustomApplied(n => n + 1);   // re-runs the effect even when already CUSTOM
        setShowCustomPicker(false);
    };

    // Drill-down slots hold either an array of rows or {error} — normalise both.
    const asRows = (v) => (Array.isArray(v) ? v : []);
    const errOf = (v) => (v && !Array.isArray(v) && v.error) ? v.error : null;
    const isLoaded = (v) => v !== undefined;

    const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
    // NEW CALCULATION: Volume Share % (Vol / Total Month Vol)
    const formatPct = (vol, totalVol) => {
        if (!totalVol || totalVol === 0) return '0.00%';
        return ((vol / totalVol) * 100).toFixed(2) + '%';
    };

    const totals = data.reduce((acc, row) => {
        const keys = [
            'dom_debit_cnt', 'dom_debit_vol', 'dom_debit_msf',
            'dom_credit_cnt', 'dom_credit_vol', 'dom_credit_msf',
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
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatMsf(row.dom_debit_msf)}</td>
                {/* Volume % — vol / total month vol */}
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b', borderRight: '1px solid #f1f5f9' }}>{formatPct(row.dom_debit_vol, row.total_vol)}</td>

                {/* Dom Credit */}
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatNumber(row.dom_credit_cnt)}</td>
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '500' }}>{formatCurrency(row.dom_credit_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatMsf(row.dom_credit_msf)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b', borderRight: '1px solid #f1f5f9' }}>{formatPct(row.dom_credit_vol, row.total_vol)}</td>

                {/* International */}
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatNumber(row.int_cnt)}</td>
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '500' }}>{formatCurrency(row.int_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatMsf(row.int_msf)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b' }}>{formatPct(row.int_vol, row.total_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', color: '#64748b', borderRight: '1px solid #f1f5f9' }}>{formatCurrency(row.int_optin)}</td>

                {/* Total */}
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '700' }}>{formatCurrency(row.total_vol)}</td>
                <td style={{ padding: '8px', textAlign: 'right', fontWeight: '600', color: '#64748b' }}>{formatMsf(row.total_msf)}</td>
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
                        {[
                            { key: 'TODAY',      label: 'Today' },
                            { key: 'MONTH',      label: 'This Month' },
                            { key: 'LAST_MONTH', label: 'Last Month' },
                            { key: 'YEAR',       label: 'This Year' },
                            { key: 'PY',         label: 'Last Year' },
                        ].map(p => (
                            <button
                                key={p.key}
                                onClick={() => { setPeriod(p.key); setShowCustomPicker(false); }}
                                style={{
                                    padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                    background: period === p.key ? 'white' : 'transparent',
                                    color: period === p.key ? '#0f172a' : '#64748b',
                                    boxShadow: period === p.key ? '0 1px 2px rgba(0,0,0,0.05)' : 'none',
                                    transition: 'all 0.2s'
                                }}
                            >
                                {p.label}
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

                    <button
                        onClick={handleExport}
                        disabled={loading || data.length === 0}
                        title={data.length === 0 ? 'Nothing to export' : 'Download the report as CSV'}
                        style={{ padding: '8px 16px', background: data.length === 0 ? '#94a3b8' : '#0f172a', color: 'white', borderRadius: '8px', border: 'none', cursor: data.length === 0 ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px' }}
                    >
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

            {/* Request-level failure — previously only logged to the console, so a
                broken endpoint was indistinguishable from an empty period. */}
            {error && (
                <div style={{ marginBottom: '12px', padding: '10px 14px', borderRadius: '8px', background: '#fef2f2', border: '1px solid #fecaca', color: '#b91c1c', fontSize: '13px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '12px' }}>
                    <span><strong>Could not load the report.</strong> {error}</span>
                    <button onClick={fetchData} style={{ padding: '4px 12px', borderRadius: '6px', border: '1px solid #fecaca', background: 'white', color: '#b91c1c', fontWeight: 600, cursor: 'pointer', fontSize: '12px' }}>Retry</button>
                </div>
            )}

            {/* Table Container */}
            <div style={{ flex: 1, overflow: 'auto', border: '1px solid #e2e8f0', borderRadius: '8px', background: 'white', position: 'relative' }}>
                <table style={{ minWidth: '1400px', width: '100%', borderCollapse: 'separate', borderSpacing: 0 }}>
                    <thead style={{ position: 'sticky', top: 0, zIndex: 10, background: 'white' }}>
                        <tr style={{ height: '40px' }}>
                            <th style={{ position: 'sticky', left: 0, zIndex: 20, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', borderRight: '1px solid #e2e8f0', minWidth: '220px' }}></th>
                            {/* Three mutually exclusive, exhaustive buckets — every transaction
                                lands in exactly one, so the three Volume % columns sum to 100%:
                                  1. Local (domestic) DEBIT + PREPAID
                                  2. Local (domestic) CREDIT — also the catch-all for domestic
                                     rows whose card_type is unmapped/blank, so the partition
                                     stays exhaustive
                                  3. International — any non-domestic destination */}
                            <th colSpan="4" title="Domestic destination, card type DEBIT or PREPAID" style={{ background: '#e0f2fe', borderBottom: '1px solid #bae6fd', borderRight: '1px solid #e2e8f0', color: '#0369a1', fontSize: '12px', fontWeight: '700', textTransform: 'uppercase', letterSpacing: '0.03em' }}>Local Debit &amp; Prepaid</th>
                            <th colSpan="4" title="Domestic destination, card type CREDIT (includes any unmapped domestic card type)" style={{ background: '#dcfce7', borderBottom: '1px solid #bbf7d0', borderRight: '1px solid #e2e8f0', color: '#15803d', fontSize: '12px', fontWeight: '700', textTransform: 'uppercase', letterSpacing: '0.03em' }}>Local Credit</th>
                            <th colSpan="5" title="Any non-domestic destination, all card types" style={{ background: '#ffedd5', borderBottom: '1px solid #fed7aa', borderRight: '1px solid #e2e8f0', color: '#c2410c', fontSize: '12px', fontWeight: '700', textTransform: 'uppercase', letterSpacing: '0.03em' }}>International</th>
                            <th colSpan="2" style={{ background: '#f1f5f9', borderBottom: '1px solid #e2e8f0', color: '#334155', fontSize: '12px', fontWeight: '700', textTransform: 'uppercase', letterSpacing: '0.03em' }}>Total</th>
                        </tr>
                        <tr style={{ height: '38px', background: '#f8fafc', fontSize: '11px', color: '#64748b' }}>
                            <th style={{ position: 'sticky', left: 0, zIndex: 20, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', borderRight: '1px solid #e2e8f0', padding: '8px', textAlign: 'left', fontWeight: '600' }}>Month</th>
                            {[
                                // [label, groupEnd?] — groupEnd draws the group divider on the right.
                                ['Count', false], ['Volume', false], ['MSF', false], ['Volume %', true],
                                ['Count', false], ['Volume', false], ['MSF', false], ['Volume %', true],
                                ['Count', false], ['Volume', false], ['MSF', false], ['Volume %', false], ['Opt-in Vol', true],
                                ['Volume', false], ['MSF', false],
                            ].map(([h, groupEnd], i) => (
                                <th key={i} style={{
                                    borderBottom: '1px solid #e2e8f0', padding: '8px', textAlign: 'right',
                                    fontWeight: '600', whiteSpace: 'nowrap',
                                    borderRight: groupEnd ? '1px solid #e2e8f0' : 'none',
                                }}>{h}</th>
                            ))}
                        </tr>
                    </thead>
                    <tbody style={{ fontSize: '12px' }}>
                        {loading ? (
                            <tr><td colSpan="16" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>Loading Financial Data...</td></tr>
                        ) : data.length === 0 ? (
                            <tr><td colSpan="16" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>
                                {error ? 'The report could not be loaded — see the message above.'
                                       : 'No transactions recorded for the selected period.'}
                            </td></tr>
                        ) : (
                            data.map((row) => (
                                <React.Fragment key={row.month_label}>
                                    {/* Level 1: Month */}
                                    <DataRow row={row} isExpanded={expandedMonth === row.month_label} onClick={() => toggleMonth(row)} level={1} />

                                    {/* Level 2: Days */}
                                    {expandedMonth === row.month_label && (
                                        <>
                                            {loadingDaily && <tr><td colSpan="16" className="text-center py-4 bg-slate-50"><Loader2 className="animate-spin inline text-slate-400" size={16} /></td></tr>}
                                            {asRows(dailyData[row.month_label]).map(day => (
                                                <React.Fragment key={day.sort_date}>
                                                    <DataRow row={day} isExpanded={expandedDate === day.sort_date} onClick={() => toggleDate(day)} level={2} />

                                                    {/* Level 3: Merchants */}
                                                    {expandedDate === day.sort_date && (
                                                        <>
                                                            {loadingMerchants && <tr><td colSpan="16" className="text-center py-4 bg-slate-50 ml-10"><Loader2 className="animate-spin inline text-indigo-400" size={16} /></td></tr>}
                                                            {asRows(merchantData[day.sort_date]).map(merch => (
                                                                <DataRow key={merch.merchant_id} row={merch} isExpanded={false} onClick={null} level={3} />
                                                            ))}
                                                            {!loadingMerchants && errOf(merchantData[day.sort_date]) && (
                                                                <tr><td colSpan="16" style={{ padding: '8px 8px 8px 60px', background: '#fef2f2', color: '#b91c1c', fontStyle: 'italic' }}>{errOf(merchantData[day.sort_date])}</td></tr>
                                                            )}
                                                            {!loadingMerchants && isLoaded(merchantData[day.sort_date]) && !errOf(merchantData[day.sort_date]) && asRows(merchantData[day.sort_date]).length === 0 && (
                                                                <tr><td colSpan="16" className="pl-20 py-2 italic text-slate-400 bg-slate-50" style={{ paddingLeft: '60px' }}>No merchant-level detail stored for this day.</td></tr>
                                                            )}
                                                        </>
                                                    )}
                                                </React.Fragment>
                                            ))}
                                            {/* A month row can be served from the monthly pre-aggregate while its
                                                day-level detail lives in a separate daily table. When that daily
                                                data is missing the expander used to open onto nothing at all, which
                                                reads as a broken report — say so instead. */}
                                            {!loadingDaily && errOf(dailyData[row.month_label]) && (
                                                <tr><td colSpan="16" style={{ padding: '10px 8px 10px 30px', background: '#fef2f2', color: '#b91c1c', fontStyle: 'italic' }}>{errOf(dailyData[row.month_label])}</td></tr>
                                            )}
                                            {!loadingDaily && isLoaded(dailyData[row.month_label]) && !errOf(dailyData[row.month_label]) && asRows(dailyData[row.month_label]).length === 0 && (
                                                <tr><td colSpan="16" style={{ padding: '10px 8px 10px 30px', background: '#f8fafc', color: '#94a3b8', fontStyle: 'italic' }}>
                                                    No daily detail available for {row.month_label}. The month total above comes from the monthly summary; daily rows for this period have not been loaded into the warehouse.
                                                </td></tr>
                                            )}
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
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatMsf(totals.dom_debit_msf)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1', borderRight: '1px solid #cbd5e1' }}>{formatPct(totals.dom_debit_vol, totals.total_vol)}</td>

                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatNumber(totals.dom_credit_cnt)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.dom_credit_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatMsf(totals.dom_credit_msf)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1', borderRight: '1px solid #cbd5e1' }}>{formatPct(totals.dom_credit_vol, totals.total_vol)}</td>

                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatNumber(totals.int_cnt)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.int_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatMsf(totals.int_msf)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatPct(totals.int_vol, totals.total_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1', borderRight: '1px solid #cbd5e1' }}>{formatCurrency(totals.int_optin)}</td>

                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatCurrency(totals.total_vol)}</td>
                            <td style={{ padding: '8px', textAlign: 'right', borderTop: '2px solid #cbd5e1' }}>{formatMsf(totals.total_msf)}</td>
                        </tr>
                    </tfoot>
                </table>
            </div>
        </div>
    );
};

export default FinanceSummary;
