import React, { useState, useEffect } from 'react';
import { Download, Filter, Search, Calendar, ChevronRight, ChevronDown, Loader2 } from 'lucide-react';
import { SmartEmptyState } from '../../components/CockpitControls';

// --- Preset Values ---
const PRESETS = [
    { label: "Today", value: "CURRENT_DAY" },
    { label: "Yesterday", value: "PREVIOUS_DAY" },
    { label: "This Year", value: "CURRENT_YEAR" },
    { label: "Last Year", value: "PREVIOUS_YEAR" },
    { label: "Custom", value: "CUSTOM" }
];

const MerchantInsightHub = () => {
    // --- State ---
    const [filters, setFilters] = useState({
        datePreset: "PREVIOUS_YEAR",
        dateFrom: "",
        dateTo: "",
        optStatus: "ALL", // Default ALL
        rm: [], mcc: [], mid: [], sid: [], tid: [],
        intlLocal: [], cardType: [], debit: [], scheme: [],
        posEcom: [], partner: []
    });

    const [options, setOptions] = useState({
        mcc: [], rm: [], mid: [], partner: [], posEcom: []
    });

    const [data, setData] = useState([]);
    const [totalElements, setTotalElements] = useState(0);
    const [pageCount, setPageCount] = useState(0);
    const [loading, setLoading] = useState(false);
    const [showFilters, setShowFilters] = useState(true);

    // --- Pagination Logic ---
    const [currentPage, setCurrentPage] = useState(1);
    const itemsPerPage = 20;

    // Reset page to 1 only when filters change drastically? 
    // Actually, "Run Report" should handle reset. Pagination just changes page.

    // --- Data Fetching ---
    useEffect(() => {
        // Fetch Masters
        const fetchMasters = async () => {
            const token = localStorage.getItem('token');
            const headers = { 'Authorization': `Bearer ${token}` };
            try {
                const [mccRes, rmRes, partnerRes, channelRes] = await Promise.all([
                    fetch('http://localhost:8081/api/reports/filters/mcc', { headers }),
                    fetch('http://localhost:8081/api/reports/filters/rm', { headers }),
                    fetch('http://localhost:8081/api/reports/filters/partners', { headers }),
                    fetch('http://localhost:8081/api/reports/filters/channels', { headers })
                ]);

                const mccData = mccRes.ok ? await mccRes.json() : [];
                const rmData = rmRes.ok ? await rmRes.json() : [];
                const partnerData = partnerRes.ok ? await partnerRes.json() : [];
                const channelData = channelRes.ok ? await channelRes.json() : [];

                setOptions({
                    mcc: mccData || [],
                    rm: rmData || [],
                    partner: partnerData || [],
                    posEcom: channelData || [],
                    mid: [] // Loaded lazily if needed/requested, or we can add it back
                });

                fetchReport(1); // Initial load
            } catch (e) { console.error("Error fetching master filters", e); }
        };
        fetchMasters();
    }, []);

    // Effect to refetch when page changes (but not when filters change, that's manual Run Report)
    useEffect(() => {
        if (currentPage > 1) fetchReport(currentPage);
    }, [currentPage]);

    const fetchReport = async (pageOverride) => {
        setLoading(true);
        const pageToFetch = pageOverride || currentPage;
        // If override provided (e.g. Run Report clicked), update state
        if (pageOverride && pageOverride !== currentPage) setCurrentPage(pageOverride);

        try {
            const token = localStorage.getItem('token');
            const res = await fetch('http://localhost:8081/api/reports/insight/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                body: JSON.stringify({
                    ...filters,
                    page: pageToFetch - 1,  // Backend is 0-indexed
                    size: itemsPerPage
                })
            });
            if (res.ok) {
                const json = await res.json();
                setData(json.content || []); // Handle new wrapper
                setTotalElements(json.totalElements || 0);
                setPageCount(json.totalPages || 0);
            }
        } catch (err) { console.error(err); }
        finally { setLoading(false); }
    };

    const handleRunReport = () => {
        setCurrentPage(1);
        fetchReport(1);
    };

    const handleFilterChange = (key, val) => {
        // Handle multi-select for arrays, single for others
        const newFilters = { ...filters, [key]: val };
        setFilters(newFilters);
        // Note: We don't auto-fetch on filter change anymore, waiting for Run Report
    };

    const toggleFilter = (key, val) => {
        const current = filters[key];
        const updated = current.includes(val) ? current.filter(i => i !== val) : [...current, val];
        handleFilterChange(key, updated);
    };

    // Helper for formatting
    const fmt = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val || 0);
    const fmtInt = (val) => new Intl.NumberFormat('en-US').format(val || 0);

    return (
        <div className="page-container" style={{ padding: '20px', color: '#1e293b', height: '100vh', display: 'flex', flexDirection: 'column' }}>

            {/* Header */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: '800', color: '#0f172a' }}>Merchant Insight Hub</h1>
                    <p style={{ color: '#64748b', fontSize: '13px' }}>Financial analytics cockpit & custom reporting</p>
                </div>

                <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                    <div style={{ background: '#f1f5f9', padding: '4px', borderRadius: '8px', display: 'flex', gap: '4px', alignItems: 'center' }}>
                        {PRESETS.map(p => (
                            <button
                                key={p.value}
                                onClick={() => handleFilterChange('datePreset', p.value)}
                                style={{
                                    padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                    background: filters.datePreset === p.value ? 'white' : 'transparent',
                                    color: filters.datePreset === p.value ? '#0f172a' : '#64748b',
                                    boxShadow: filters.datePreset === p.value ? '0 1px 2px rgba(0,0,0,0.05)' : 'none'
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
                                    onChange={(e) => handleFilterChange('dateFrom', e.target.value)}
                                    style={{ fontSize: '11px', padding: '4px', borderRadius: '4px', border: '1px solid #cbd5e1' }}
                                />
                                <span style={{ fontSize: '12px', color: '#94a3b8' }}>-</span>
                                <input
                                    type="date"
                                    value={filters.dateTo}
                                    onChange={(e) => handleFilterChange('dateTo', e.target.value)}
                                    style={{ fontSize: '11px', padding: '4px', borderRadius: '4px', border: '1px solid #cbd5e1' }}
                                />
                            </div>
                        )}
                    </div>

                    <button
                        onClick={() => setShowFilters(!showFilters)}
                        style={{ padding: '8px 16px', background: showFilters ? '#e2e8f0' : 'white', border: '1px solid #cbd5e1', color: '#475569', borderRadius: '8px', cursor: 'pointer', fontSize: '13px', fontWeight: '600', display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <Filter size={14} /> Filters
                    </button>

                    <button onClick={handleRunReport} style={{ padding: '8px 16px', background: '#3b82f6', color: 'white', borderRadius: '8px', border: 'none', cursor: 'pointer', fontSize: '13px', fontWeight: '600' }}>
                        Run Report
                    </button>
                    <button style={{ padding: '8px 16px', background: '#0f172a', color: 'white', borderRadius: '8px', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px' }}>
                        <Download size={16} /> Export
                    </button>
                </div>
            </div>

            {/* Dynamic Filter Panel */}
            {showFilters && (
                <div style={{ background: '#f8fafc', padding: '16px', borderRadius: '8px', marginBottom: '20px', border: '1px solid #e2e8f0', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px' }}>

                    {/* MCC Filter */}
                    <div>
                        <label style={{ display: 'block', fontSize: '11px', fontWeight: '700', color: '#64748b', marginBottom: '6px', textTransform: 'uppercase' }}>MCC</label>
                        <select
                            onChange={(e) => handleFilterChange('mcc', e.target.value ? [e.target.value] : [])}
                            value={filters.mcc?.[0] || ''}
                            style={{ width: '100%', padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', fontSize: '12px', color: '#334155' }}
                        >
                            <option value="">All MCCs</option>
                            {options.mcc.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                        </select>
                    </div>

                    {/* RM Filter */}
                    <div>
                        <label style={{ display: 'block', fontSize: '11px', fontWeight: '700', color: '#64748b', marginBottom: '6px', textTransform: 'uppercase' }}>Relationship Mgr</label>
                        <select
                            onChange={(e) => handleFilterChange('rm', e.target.value ? [e.target.value] : [])}
                            value={filters.rm?.[0] || ''}
                            style={{ width: '100%', padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', fontSize: '12px', color: '#334155' }}
                        >
                            <option value="">All RMs</option>
                            {options.rm.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                        </select>
                    </div>

                    {/* Partner Filter */}
                    <div>
                        <label style={{ display: 'block', fontSize: '11px', fontWeight: '700', color: '#64748b', marginBottom: '6px', textTransform: 'uppercase' }}>Partner</label>
                        <select
                            onChange={(e) => handleFilterChange('partner', e.target.value ? [e.target.value] : [])}
                            value={filters.partner?.[0] || ''}
                            style={{ width: '100%', padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', fontSize: '12px', color: '#334155' }}
                        >
                            <option value="">All Partners</option>
                            {options.partner.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                        </select>
                    </div>

                    {/* Channel (POS/ECOM) */}
                    <div>
                        <label style={{ display: 'block', fontSize: '11px', fontWeight: '700', color: '#64748b', marginBottom: '6px', textTransform: 'uppercase' }}>Channel (POS/ECOM)</label>
                        <select
                            onChange={(e) => handleFilterChange('posEcom', e.target.value ? [e.target.value] : [])}
                            value={filters.posEcom?.[0] || ''}
                            style={{ width: '100%', padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', fontSize: '12px', color: '#334155' }}
                        >
                            <option value="">All Channels</option>
                            {options.posEcom.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                        </select>
                    </div>

                    {/* Opt Status */}
                    <div>
                        <label style={{ display: 'block', fontSize: '11px', fontWeight: '700', color: '#64748b', marginBottom: '6px', textTransform: 'uppercase' }}>Opt Status</label>
                        <div style={{ display: 'flex', gap: '8px' }}>
                            {['ALL', 'OPT_IN', 'OPT_OUT'].map(st => (
                                <button
                                    key={st}
                                    onClick={() => handleFilterChange('optStatus', st)}
                                    style={{
                                        flex: 1, padding: '8px', borderRadius: '6px', border: filters.optStatus === st ? '1px solid #3b82f6' : '1px solid #cbd5e1',
                                        background: filters.optStatus === st ? '#eff6ff' : 'white',
                                        color: filters.optStatus === st ? '#1d4ed8' : '#64748b',
                                        fontSize: '11px', fontWeight: '600', cursor: 'pointer'
                                    }}
                                >
                                    {st === 'ALL' ? 'All' : st === 'OPT_IN' ? 'In' : 'Out'}
                                </button>
                            ))}
                        </div>
                    </div>

                </div>
            )}

            {/* Table Container */}
            <div style={{ flex: 1, overflow: 'auto', border: '1px solid #e2e8f0', borderRadius: '8px', background: 'white', position: 'relative' }}>
                <table style={{ minWidth: '100%', borderCollapse: 'separate', borderSpacing: 0 }}>
                    <thead style={{ position: 'sticky', top: 0, zIndex: 10, background: 'white' }}>
                        <tr style={{ height: '40px', background: '#f8fafc', fontSize: '11px', color: '#64748b' }}>
                            {['Date', 'Merchant', 'Details', 'Store/Term', 'Type', 'Txns', 'Volume', 'MSR'].map((h, i) => (
                                <th key={i} style={{ position: 'sticky', top: 0, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', borderRight: '1px solid #e2e8f0', padding: '12px', textAlign: i > 4 ? 'right' : 'left', textTransform: 'uppercase' }}>{h}</th>
                            ))}
                        </tr>
                    </thead>
                    <tbody style={{ fontSize: '12px' }}>
                        {loading ? (
                            <tr><td colSpan="8" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}><Loader2 className="animate-spin inline" /> Loading Market Data...</td></tr>
                        ) : data.length === 0 ? (
                            <tr><td colSpan="8" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>No records found.</td></tr>
                        ) : (
                            data.map((row, i) => (
                                <tr key={i} style={{ borderBottom: '1px solid #f1f5f9', background: i % 2 === 0 ? 'white' : '#fafafa' }}>
                                    <td style={{ padding: '10px', color: '#334155', whiteSpace: 'nowrap' }}>{row.business_date}</td>
                                    <td style={{ padding: '10px', fontWeight: '600', color: '#1e293b' }}>
                                        <div>{row.merchant_name}</div>
                                        <div style={{ fontSize: '10px', color: '#94a3b8', fontFamily: 'monospace' }}>{row.mid}</div>
                                        {row.rm && <div style={{ fontSize: '10px', color: '#6366f1', marginTop: '2px' }}>RM: {row.rm}</div>}
                                    </td>
                                    <td style={{ padding: '10px', color: '#64748b' }}>
                                        <span style={{ background: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', fontSize: '10px', marginRight: '4px' }}>{row.mcc}</span>
                                        <span style={{ background: row.intl_local === 'INTERNATIONAL' ? '#f3e8ff' : '#ecfdf5', color: row.intl_local === 'INTERNATIONAL' ? '#7e22ce' : '#047857', padding: '2px 6px', borderRadius: '4px', fontSize: '10px' }}>{row.intl_local?.substring(0, 3)}</span>
                                        {row.pos_ecom && <span style={{ marginLeft: '4px', background: '#fff7ed', color: '#c2410c', padding: '2px 6px', borderRadius: '4px', fontSize: '10px' }}>{row.pos_ecom}</span>}
                                    </td>
                                    <td style={{ padding: '10px', color: '#64748b', fontSize: '11px' }}>
                                        <div><span style={{ color: '#94a3b8' }}>S:</span> {row.sid}</div>
                                        <div><span style={{ color: '#94a3b8' }}>T:</span> {row.tid}</div>
                                    </td>
                                    <td style={{ padding: '10px', color: '#64748b' }}>{row.card_type}</td>
                                    <td style={{ padding: '10px', textAlign: 'right', fontFamily: 'monospace' }}>{fmtInt(row.total_txns)}</td>
                                    <td style={{ padding: '10px', textAlign: 'right', fontWeight: 'bold' }}>{fmt(row.total_volume)}</td>
                                    <td style={{ padding: '10px', textAlign: 'right', color: '#059669', background: '#ecfdf550' }}>{fmt(row.total_msf)}</td>
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>

            {/* Pagination Footer */}
            <div style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 0', borderTop: '1px solid #e2e8f0', marginTop: '10px' }}>
                <span style={{ fontSize: '12px', color: '#64748b' }}>Page {currentPage} of {pageCount || 1}</span>
                <div style={{ display: 'flex', gap: '4px' }}>
                    <button disabled={currentPage === 1} onClick={() => setCurrentPage(currentPage - 1)} style={{ padding: '4px 12px', borderRadius: '4px', border: '1px solid #e2e8f0', background: 'white', cursor: 'pointer', fontSize: '12px' }}>Prev</button>
                    <button disabled={currentPage >= pageCount} onClick={() => setCurrentPage(currentPage + 1)} style={{ padding: '4px 12px', borderRadius: '4px', border: '1px solid #e2e8f0', background: 'white', cursor: 'pointer', fontSize: '12px' }}>Next</button>
                </div>
            </div>
        </div>
    );
};

export default MerchantInsightHub;
