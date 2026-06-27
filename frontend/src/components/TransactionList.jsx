import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Filter, ChevronLeft, ChevronRight, X, Download } from 'lucide-react';
import useExcelExport from '../hooks/useExcelExport';
import Loader from './Loader';

const EMPTY_FILTERS = {
    mid: '', sid: '', tid: '',
    paymentDateFrom: '', paymentDateTo: '',
    transactionDateFrom: '', transactionDateTo: ''
};

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
    const [filters, setFilters] = useState({ ...EMPTY_FILTERS });

    // Use a ref to always have current filters available in fetchTransactions
    const filtersRef = useRef(filters);
    filtersRef.current = filters;

    const fetchTransactions = useCallback(async (overrideFilters) => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const activeFilters = overrideFilters || filtersRef.current;

            const params = new URLSearchParams();
            params.append('page', page);
            params.append('size', size);

            if (activeFilters.mid) params.append('mid', activeFilters.mid);
            if (activeFilters.sid) params.append('sid', activeFilters.sid);
            if (activeFilters.tid) params.append('tid', activeFilters.tid);

            if (activeFilters.paymentDateFrom) params.append('paymentDateFrom', activeFilters.paymentDateFrom);
            if (activeFilters.paymentDateTo) params.append('paymentDateTo', activeFilters.paymentDateTo);
            if (activeFilters.transactionDateFrom) params.append('transactionDateFrom', activeFilters.transactionDateFrom);
            if (activeFilters.transactionDateTo) params.append('transactionDateTo', activeFilters.transactionDateTo);

            const res = await fetch(`/api/transactions?${params.toString()}`, {
                headers: {
                    'Authorization': `Bearer ${token}`,
                    ...(tenantId ? { 'X-Tenant-Id': tenantId } : {})
                }
            });

            if (res.ok) {
                const data = await res.json();
                setTransactions(data.content || []);
                setTotalPages(data.totalPages || 0);
                setTotalElements(data.totalElements || 0);
            }
        } catch (error) {
            console.error("Failed to fetch transactions", error);
        } finally {
            setLoading(false);
        }
    }, [page, size]);

    useEffect(() => {
        fetchTransactions();
    }, [page, size]); // eslint-disable-line react-hooks/exhaustive-deps

    const handleFilterChange = (key, value) => {
        setFilters(prev => ({ ...prev, [key]: value }));
    };

    const applyFilters = () => {
        setPage(0);
        fetchTransactions();
    };

    const clearFilters = () => {
        const cleared = { ...EMPTY_FILTERS };
        setFilters(cleared);
        setPage(0);
        // Pass cleared filters directly to avoid stale closure
        fetchTransactions(cleared);
    };

    const handleExport = () => {
        exportExcel('TRANSACTIONS', filters);
    };

    return (
        <div className="tx-root">

            {/* ── Filter card ── */}
            <div className="tx-card" style={{ padding: 20 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 10 }}>
                    <span style={{ fontSize: '0.78rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-muted)' }}>
                        Filters
                    </span>
                    <div style={{ display: 'flex', gap: 8 }}>
                        <button onClick={handleExport} disabled={isExporting} className="tx-export">
                            <Download size={15} /> {isExporting ? 'Exporting…' : 'Export Excel'}
                        </button>
                        <button onClick={clearFilters} className="tx-clear">
                            <X size={14} /> Clear
                        </button>
                    </div>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 14, alignItems: 'end' }}>
                    <div>
                        <label className="tx-label">Merchant (MID)</label>
                        <input type="text" placeholder="MID" value={filters.mid} onChange={(e) => handleFilterChange('mid', e.target.value)} className="tx-input" />
                    </div>
                    <div>
                        <label className="tx-label">Store (SID)</label>
                        <input type="text" placeholder="SID" value={filters.sid} onChange={(e) => handleFilterChange('sid', e.target.value)} className="tx-input" />
                    </div>
                    <div>
                        <label className="tx-label">Terminal (TID)</label>
                        <input type="text" placeholder="TID" value={filters.tid} onChange={(e) => handleFilterChange('tid', e.target.value)} className="tx-input" />
                    </div>

                    <div style={{ gridColumn: 'span 2' }}>
                        <label className="tx-label">Payment Date Range</label>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                            <input type="date" value={filters.paymentDateFrom} onChange={(e) => handleFilterChange('paymentDateFrom', e.target.value)} className="tx-input" />
                            <span style={{ color: 'var(--text-muted)' }}>–</span>
                            <input type="date" value={filters.paymentDateTo} onChange={(e) => handleFilterChange('paymentDateTo', e.target.value)} className="tx-input" />
                        </div>
                    </div>

                    <div style={{ gridColumn: 'span 2' }}>
                        <label className="tx-label">Transaction Date Range</label>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                            <input type="date" value={filters.transactionDateFrom} onChange={(e) => handleFilterChange('transactionDateFrom', e.target.value)} className="tx-input" />
                            <span style={{ color: 'var(--text-muted)' }}>–</span>
                            <input type="date" value={filters.transactionDateTo} onChange={(e) => handleFilterChange('transactionDateTo', e.target.value)} className="tx-input" />
                        </div>
                    </div>

                    <button onClick={applyFilters} className="tx-apply">
                        <Filter size={15} /> Apply Filters
                    </button>
                </div>
            </div>

            {/* ── Data table ── */}
            <div className="tx-card" style={{ overflow: 'hidden', flex: 1, display: 'flex', flexDirection: 'column', padding: 0 }}>
                {loading ? <Loader /> : (
                    <>
                        <div style={{ overflowX: 'auto', flex: 1 }}>
                            <table className="tx-table">
                                <thead>
                                    <tr>
                                        {['Date', 'ARN', 'Merchant', 'Store', 'Terminal', 'Card', 'Amount', 'Fee (MSF)', 'DCC', 'Type', 'Destination'].map(h => (
                                            <th key={h}>{h}</th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {transactions.length === 0 ? (
                                        <tr><td colSpan="11" style={{ padding: 40, textAlign: 'center', color: 'var(--text-muted)' }}>No transactions found.</td></tr>
                                    ) : (
                                        transactions.map(t => (
                                            <tr key={t.transactionId}>
                                                <td>
                                                    <div style={{ color: 'var(--text)' }}>{formatDate(t.paymentDate)}</div>
                                                    <div style={{ fontSize: '0.74rem', color: 'var(--text-muted)' }}>{formatDate(t.transactionDate)}</div>
                                                </td>
                                                <td className="tx-mono">{t.arn}</td>
                                                <td>
                                                    <div style={{ fontWeight: 600, color: 'var(--text)' }}>{t.merchant ? t.merchant.name : '-'}</div>
                                                    <div style={{ fontSize: '0.74rem', color: 'var(--text-secondary)' }}>{t.merchant ? t.merchant.mid : t.merchantId}</div>
                                                </td>
                                                <td>
                                                    <div style={{ color: 'var(--text)' }}>{t.store ? t.store.name : '-'}</div>
                                                    <div style={{ fontSize: '0.74rem', color: 'var(--text-secondary)' }}>{t.store ? t.store.sid : t.storeId}</div>
                                                </td>
                                                <td className="tx-mono">{t.terminal ? t.terminal.tid : t.terminalId}</td>
                                                <td className="tx-mono">{t.cardNumber}</td>
                                                <td>
                                                    <span style={{ fontWeight: 600, color: 'var(--text)' }}>{t.txnCurrency} {t.txnCurrencyAmount?.toFixed(3)}</span>
                                                </td>
                                                <td style={{ color: 'var(--danger)', fontWeight: 600 }}>{t.msf?.toFixed(3)}</td>
                                                <td style={{ color: 'var(--text-secondary)' }}>{t.dcc ? 'Yes' : 'No'}</td>
                                                <td>
                                                    <span className="tx-pill" style={{
                                                        background: t.transactionType === 'Purchase' ? 'var(--success-bg)' : 'var(--danger-bg)',
                                                        color: t.transactionType === 'Purchase' ? 'var(--success-text)' : 'var(--danger-text)',
                                                    }}>
                                                        {t.transactionType}
                                                    </span>
                                                </td>
                                                <td>
                                                    <span className="tx-pill" style={{ background: 'var(--info-bg)', color: 'var(--info-text)' }}>
                                                        {t.destination || '-'}
                                                    </span>
                                                </td>
                                            </tr>
                                        ))
                                    )}
                                </tbody>
                            </table>
                        </div>

                        {/* Pagination */}
                        <div className="tx-pager">
                            <div style={{ fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
                                Total: {totalElements} • Page {page + 1} of {totalPages || 1}
                            </div>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                                <select
                                    value={size}
                                    onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                                    className="tx-select"
                                >
                                    <option value="10">10 / page</option>
                                    <option value="20">20 / page</option>
                                    <option value="30">30 / page</option>
                                    <option value="50">50 / page</option>
                                </select>

                                <div style={{ display: 'flex', gap: 6 }}>
                                    <button disabled={page === 0} onClick={() => setPage(p => p - 1)} className="tx-page-btn">
                                        <ChevronLeft size={16} />
                                    </button>
                                    <button disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)} className="tx-page-btn">
                                        <ChevronRight size={16} />
                                    </button>
                                </div>
                            </div>
                        </div>
                    </>
                )}
            </div>

            <style>{`
                .tx-root { display: flex; flex-direction: column; gap: 18px; height: 100%; padding: 24px; }
                .tx-card {
                    background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-lg); box-shadow: var(--shadow-card);
                }
                .tx-label {
                    display: block; font-size: 0.72rem; font-weight: 700; text-transform: uppercase;
                    letter-spacing: 0.04em; color: var(--text-muted); margin-bottom: 5px;
                }
                .tx-input {
                    width: 100%; padding: 8px 11px; border-radius: var(--radius-md);
                    border: 1px solid var(--border); background: var(--bg-card); color: var(--text);
                    font-size: 0.84rem; font-family: inherit; box-sizing: border-box; transition: border-color 0.15s;
                }
                .tx-input::placeholder { color: var(--text-muted); }
                .tx-input:focus { outline: none; border-color: var(--brand); }
                .tx-apply {
                    display: inline-flex; align-items: center; justify-content: center; gap: 7px;
                    background: var(--brand); color: #fff; border: none; border-radius: var(--radius-md);
                    padding: 0 20px; cursor: pointer; font-weight: 600; font-size: 0.84rem; height: 38px;
                    transition: background 0.15s; white-space: nowrap;
                }
                .tx-apply:hover { background: var(--brand-dark); }
                .tx-export {
                    display: inline-flex; align-items: center; gap: 6px; font-size: 0.82rem; font-weight: 600;
                    color: var(--success-text); background: var(--success-bg);
                    border: 1px solid var(--border-light);
                    padding: 7px 13px; border-radius: var(--radius-md); cursor: pointer; transition: opacity 0.15s;
                }
                .tx-export:disabled { opacity: 0.6; cursor: default; }
                .tx-clear {
                    display: inline-flex; align-items: center; gap: 5px; font-size: 0.82rem; font-weight: 600;
                    color: var(--text-secondary); background: none; border: none; cursor: pointer; transition: color 0.15s;
                }
                .tx-clear:hover { color: var(--danger); }

                .tx-table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
                .tx-table thead th {
                    position: sticky; top: 0; background: var(--bg-subtle); text-align: left;
                    padding: 12px 15px; font-size: 0.7rem; font-weight: 700; text-transform: uppercase;
                    letter-spacing: 0.04em; color: var(--text-secondary);
                    border-bottom: 1px solid var(--border); white-space: nowrap; z-index: 2;
                }
                .tx-table tbody td { padding: 11px 15px; vertical-align: middle; color: var(--text-secondary); border-bottom: 1px solid var(--border-light); white-space: nowrap; }
                .tx-table tbody tr { transition: background 0.12s; }
                .tx-table tbody tr:hover { background: var(--bg-hover); }
                .tx-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--text-secondary); }
                .tx-pill { padding: 3px 9px; border-radius: 999px; font-size: 0.7rem; font-weight: 700; display: inline-block; }

                .tx-pager {
                    padding: 13px 20px; border-top: 1px solid var(--border); display: flex;
                    justify-content: space-between; align-items: center; background: var(--bg-subtle); flex-wrap: wrap; gap: 10px;
                }
                .tx-select {
                    padding: 6px 10px; border-radius: var(--radius-md); border: 1px solid var(--border);
                    background: var(--bg-card); color: var(--text); font-size: 0.82rem; font-family: inherit; cursor: pointer;
                }
                .tx-page-btn {
                    padding: 6px 10px; border-radius: var(--radius-md); border: 1px solid var(--border);
                    background: var(--bg-card); color: var(--text-secondary); cursor: pointer;
                    display: flex; align-items: center; transition: all 0.15s;
                }
                .tx-page-btn:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
                .tx-page-btn:disabled { color: var(--text-disabled); cursor: not-allowed; opacity: 0.6; }
            `}</style>
        </div>
    );
};

const formatDate = (dateString) => {
    if (!dateString) return '-';
    return new Date(dateString).toLocaleDateString();
};

export default TransactionList;
