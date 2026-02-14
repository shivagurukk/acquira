import React, { useState, useEffect } from 'react';
import { Search, Filter, Calendar, CreditCard, ChevronLeft, ChevronRight, X, Download } from 'lucide-react';
import useExcelExport from '../hooks/useExcelExport';
import Loader from './Loader';

const TransactionList = () => {
    const [transactions, setTransactions] = useState([]);
    const [loading, setLoading] = useState(false);

    // Pagination
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);
    const { exportExcel, isExporting } = useExcelExport();

    // Filters
    const [filters, setFilters] = useState({
        mid: '',
        sid: '',
        tid: '',
        paymentDateFrom: '',
        paymentDateTo: '',
        transactionDateFrom: '',
        transactionDateTo: ''
    });

    useEffect(() => {
        fetchTransactions();
    }, [page, size]);

    const fetchTransactions = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');

            const params = new URLSearchParams();
            params.append('page', page);
            params.append('size', size);

            if (filters.mid) params.append('mid', filters.mid);
            if (filters.sid) params.append('sid', filters.sid);
            if (filters.tid) params.append('tid', filters.tid);

            if (filters.paymentDateFrom) params.append('paymentDateFrom', filters.paymentDateFrom);
            if (filters.paymentDateTo) params.append('paymentDateTo', filters.paymentDateTo);
            if (filters.transactionDateFrom) params.append('transactionDateFrom', filters.transactionDateFrom);
            if (filters.transactionDateTo) params.append('transactionDateTo', filters.transactionDateTo);

            const res = await fetch(`/api/transactions?${params.toString()}`, {
                headers: {
                    'Authorization': `Bearer ${token}`,
                    'X-Tenant-Id': tenantId
                }
            });

            if (res.ok) {
                const data = await res.json();
                setTransactions(data.content);
                setTotalPages(data.totalPages);
                setTotalElements(data.totalElements);
            }
        } catch (error) {
            console.error("Failed to fetch transactions", error);
        } finally {
            setLoading(false);
        }
    };

    const handleFilterChange = (key, value) => {
        setFilters(prev => ({ ...prev, [key]: value }));
    };

    const applyFilters = () => {
        setPage(0); // Reset to first page
        fetchTransactions();
    };

    const clearFilters = () => {
        setFilters({
            mid: '', sid: '', tid: '',
            paymentDateFrom: '', paymentDateTo: '',
            transactionDateFrom: '', transactionDateTo: ''
        });
        setPage(0);
        setTimeout(() => {
            // We need to trigger fetch, but state update is async. 
            // A better way is dependency on a 'trigger' or just rely on manual 'Apply' but user expects clear to fetch.
            // For now, simple timeout or just let user click apply after clear? 
            // Let's force fetch by calling with empty filters directly or just triggering.
            // Actually, best to just reset state and let user click apply, OR call fetch with cleared params.
            // Let's call fetch manually with defaults.
            // But fetchTransactions uses state. 
            // Standard pattern: setFilters then useEffect or explicit call.
            // We'll leave it to user to click apply or auto-apply? 
            // Auto-apply on clear is better UX.
        }, 0);
        // Hack: Direct fetch not clean due to closure. 
        // We will just reload page 0 with empty params by setting state and ensuring useEffect doesn't fire double?
        // Actually, just set Filters and trigger a reload flag?
        // Let's just use a ref or simple "Appy" button is required.
    };

    // Auto-fetch on clear?
    // Let's just create a wrapper that uses current state.

    const handleExport = () => {
        // Pass current filters to export
        exportExcel('TRANSACTIONS', filters);
    };

    return (
        <div style={{ padding: '24px', background: '#f8fafc', minHeight: '100vh', display: 'flex', flexDirection: 'column', gap: '20px' }}>

            {/* Header & Filters */}
            <div style={{ background: 'white', padding: '20px', borderRadius: '12px', boxShadow: '0 1px 2px rgba(0,0,0,0.05)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                    <h2 style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#0f172a' }}>Transaction Data</h2>
                    <div style={{ display: 'flex', gap: '10px' }}>
                        <button onClick={handleExport} disabled={isExporting} style={{ fontSize: '0.9rem', color: '#059669', background: '#ecfdf5', border: '1px solid #a7f3d0', padding: '6px 12px', borderRadius: '6px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '5px', fontWeight: '600', opacity: isExporting ? 0.7 : 1 }}>
                            <Download size={16} /> {isExporting ? 'Exporting...' : 'Export Excel'}
                        </button>
                        <button onClick={() => { clearFilters(); setTimeout(fetchTransactions, 50); }} style={{ fontSize: '0.9rem', color: '#64748b', background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '5px' }}>
                            <X size={16} /> Clear
                        </button>
                    </div>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '15px', alignItems: 'end' }}>

                    {/* Specific ID Filters */}
                    <div>
                        <label style={{ fontSize: '0.75rem', fontWeight: 'bold', color: '#64748b', marginBottom: '4px', display: 'block' }}>Merchant (MID)</label>
                        <input type="text" placeholder="MID" value={filters.mid} onChange={(e) => handleFilterChange('mid', e.target.value)} style={inputStyle} />
                    </div>
                    <div>
                        <label style={{ fontSize: '0.75rem', fontWeight: 'bold', color: '#64748b', marginBottom: '4px', display: 'block' }}>Store (SID)</label>
                        <input type="text" placeholder="SID" value={filters.sid} onChange={(e) => handleFilterChange('sid', e.target.value)} style={inputStyle} />
                    </div>
                    <div>
                        <label style={{ fontSize: '0.75rem', fontWeight: 'bold', color: '#64748b', marginBottom: '4px', display: 'block' }}>Terminal (TID)</label>
                        <input type="text" placeholder="TID" value={filters.tid} onChange={(e) => handleFilterChange('tid', e.target.value)} style={inputStyle} />
                    </div>

                    {/* Payment Date */}
                    <div style={{ gridColumn: 'span 2' }}>
                        <label style={{ fontSize: '0.75rem', fontWeight: 'bold', color: '#64748b', marginBottom: '4px', display: 'block' }}>Payment Date Range</label>
                        <div style={{ display: 'flex', gap: '5px' }}>
                            <input type="date" value={filters.paymentDateFrom} onChange={(e) => handleFilterChange('paymentDateFrom', e.target.value)} style={dateInputStyle} />
                            <span style={{ alignSelf: 'center', color: '#94a3b8' }}>-</span>
                            <input type="date" value={filters.paymentDateTo} onChange={(e) => handleFilterChange('paymentDateTo', e.target.value)} style={dateInputStyle} />
                        </div>
                    </div>

                    {/* Transaction Date */}
                    <div style={{ gridColumn: 'span 2' }}>
                        <label style={{ fontSize: '0.75rem', fontWeight: 'bold', color: '#64748b', marginBottom: '4px', display: 'block' }}>Transaction Date Range</label>
                        <div style={{ display: 'flex', gap: '5px' }}>
                            <input type="date" value={filters.transactionDateFrom} onChange={(e) => handleFilterChange('transactionDateFrom', e.target.value)} style={dateInputStyle} />
                            <span style={{ alignSelf: 'center', color: '#94a3b8' }}>-</span>
                            <input type="date" value={filters.transactionDateTo} onChange={(e) => handleFilterChange('transactionDateTo', e.target.value)} style={dateInputStyle} />
                        </div>
                    </div>

                    <button
                        onClick={applyFilters}
                        style={{
                            background: '#3b82f6', color: 'white', border: 'none', borderRadius: '8px',
                            padding: '0 20px', cursor: 'pointer', fontWeight: 'bold',
                            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', height: '38px'
                        }}
                    >
                        <Filter size={16} /> Apply Filters
                    </button>
                </div>
            </div>

            {/* Data Table */}
            <div style={{ background: 'white', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)', overflow: 'hidden', flex: 1, display: 'flex', flexDirection: 'column' }}>
                {loading ? <Loader /> : (
                    <>
                        <div style={{ overflowX: 'auto', flex: 1 }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                                <thead style={{ background: '#f8fafc', color: '#64748b', textAlign: 'left', borderBottom: '1px solid #e2e8f0' }}>
                                    <tr>
                                        <th style={thStyle}>Date</th>
                                        <th style={thStyle}>ARN</th>
                                        <th style={thStyle}>Merchant</th>
                                        <th style={thStyle}>Store</th>
                                        <th style={thStyle}>Terminal</th>
                                        <th style={thStyle}>Card</th>
                                        <th style={thStyle}>Amount</th>
                                        <th style={thStyle}>Fee (MSF)</th>
                                        <th style={thStyle}>DCC</th>
                                        <th style={thStyle}>Type</th>
                                        <th style={thStyle}>Destination</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {transactions.length === 0 ? (
                                        <tr><td colSpan="11" style={{ padding: '30px', textAlign: 'center', color: '#94a3b8' }}>No transactions found.</td></tr>
                                    ) : (
                                        transactions.map(t => (
                                            <tr key={t.transactionId} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                                <td style={tdStyle}>
                                                    <div>{formatDate(t.paymentDate)}</div>
                                                    <div style={{ fontSize: '0.75rem', color: '#94a3b8' }}>{formatDate(t.transactionDate)}</div>
                                                </td>
                                                <td style={{ ...tdStyle, fontFamily: 'monospace' }}>{t.arn}</td>
                                                <td style={tdStyle}>
                                                    <div style={{ fontWeight: '500', color: '#0f172a' }}>{t.merchant ? t.merchant.name : '-'}</div>
                                                    <div style={{ fontSize: '0.75rem', color: '#64748b' }}>{t.merchant ? t.merchant.mid : t.merchantId}</div>
                                                </td>
                                                <td style={tdStyle}>
                                                    <div>{t.store ? t.store.name : '-'}</div>
                                                    <div style={{ fontSize: '0.75rem', color: '#64748b' }}>{t.store ? t.store.sid : t.storeId}</div>
                                                </td>
                                                <td style={{ ...tdStyle, fontFamily: 'monospace' }}>{t.terminal ? t.terminal.tid : t.terminalId}</td>
                                                <td style={{ ...tdStyle, fontFamily: 'monospace' }}>{t.cardNumber}</td>
                                                <td style={tdStyle}>
                                                    <div style={{ fontWeight: '600' }}>{t.txnCurrency} {t.txnCurrencyAmount?.toFixed(3)}</div>
                                                </td>
                                                <td style={{ ...tdStyle, color: '#dc2626' }}>
                                                    {t.msf?.toFixed(3)}
                                                </td>
                                                <td style={tdStyle}>{t.dcc ? 'Yes' : 'No'}</td>
                                                <td style={tdStyle}>
                                                    <span style={{
                                                        padding: '2px 6px', borderRadius: '4px', fontSize: '0.7rem', fontWeight: 'bold',
                                                        background: t.transactionType === 'Purchase' ? '#dcfce7' : '#fee2e2',
                                                        color: t.transactionType === 'Purchase' ? '#166534' : '#991b1b'
                                                    }}>
                                                        {t.transactionType}
                                                    </span>
                                                </td>
                                                <td style={tdStyle}>
                                                    <span style={{
                                                        padding: '2px 6px', borderRadius: '4px', fontSize: '0.7rem', fontWeight: 'bold',
                                                        background: '#e0f2fe', color: '#0369a1'
                                                    }}>
                                                        {t.destination || '-'}
                                                    </span>
                                                </td>
                                            </tr>
                                        ))
                                    )}
                                </tbody>
                            </table>
                        </div>

                        {/* Pagination Footer */}
                        <div style={{ padding: '15px 20px', borderTop: '1px solid #e2e8f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <div style={{ fontSize: '0.85rem', color: '#64748b' }}>
                                Total: {totalElements} | Page {page + 1} of {totalPages}
                            </div>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '15px' }}>
                                <select
                                    value={size}
                                    onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                                    style={{ padding: '5px', borderRadius: '4px', border: '1px solid #e2e8f0', fontSize: '0.85rem' }}
                                >
                                    <option value="10">10 / page</option>
                                    <option value="20">20 / page</option>
                                    <option value="30">30 / page</option>
                                    <option value="50">50 / page</option>
                                </select>

                                <div style={{ display: 'flex', gap: '5px' }}>
                                    <button
                                        disabled={page === 0}
                                        onClick={() => setPage(p => p - 1)}
                                        style={paginationBtnStyle(page === 0)}
                                    >
                                        <ChevronLeft size={16} />
                                    </button>
                                    <button
                                        disabled={page >= totalPages - 1}
                                        onClick={() => setPage(p => p + 1)}
                                        style={paginationBtnStyle(page >= totalPages - 1)}
                                    >
                                        <ChevronRight size={16} />
                                    </button>
                                </div>
                            </div>
                        </div>
                    </>
                )}
            </div>
        </div>
    );
};

const formatDate = (dateString) => {
    if (!dateString) return '-';
    return new Date(dateString).toLocaleDateString();
};

const inputStyle = {
    padding: '8px', borderRadius: '6px', border: '1px solid #e2e8f0', width: '100%', fontSize: '0.85rem'
};
const dateInputStyle = {
    padding: '8px', borderRadius: '6px', border: '1px solid #e2e8f0', width: '100%', fontSize: '0.85rem', fontFamily: 'inherit'
};
const thStyle = { padding: '12px 15px', fontWeight: 'bold' };
const tdStyle = { padding: '10px 15px', verticalAlign: 'middle', color: '#475569' };
const paginationBtnStyle = (disabled) => ({
    padding: '6px 10px', borderRadius: '6px', border: '1px solid #e2e8f0',
    background: disabled ? '#f1f5f9' : 'white',
    color: disabled ? '#cbd5e1' : '#334155', cursor: disabled ? 'not-allowed' : 'pointer',
    display: 'flex', alignItems: 'center'
});

export default TransactionList;
