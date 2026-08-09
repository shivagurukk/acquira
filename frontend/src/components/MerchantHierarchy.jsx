import React, { useState, useEffect, useRef } from 'react';
import { Search, Filter, Store, CreditCard, ChevronDown, ChevronRight, X, AlertTriangle } from 'lucide-react';
import Loader from '../components/Loader';
import DateRangePicker from './DateRangePicker';
import { useAuth } from '../contexts/AuthContext';
import api from '../api/axios';

const MerchantHierarchy = ({ viewMode = 'LIST' }) => {
    // tenantVersion bumps on every super-admin tenant switch. This tree is
    // tenant-scoped server-side (X-Tenant-Id header); on a switch we must reset
    // paging/expansion caches and re-fetch, else stale rows from the previous
    // tenant linger (a cross-tenant leak to the viewer).
    const { tenantVersion } = useAuth();
    const [merchants, setMerchants] = useState([]);
    const [loading, setLoading] = useState(false);
    const [filters, setFilters] = useState({
        merchantOnboardingFrom: '',
        merchantOnboardingTo: '',
        storeCreatedFrom: '',
        storeCreatedTo: '',
        terminalCreatedFrom: '',
        terminalCreatedTo: '',
        search: '',
        sid: '',
        tid: '',
        storeName: ''
    });

    const [expandedMerchants, setExpandedMerchants] = useState({});
    const [expandedStores, setExpandedStores] = useState({});

    const [fetchedMerchants, setFetchedMerchants] = useState(new Set());
    const [fetchedStores, setFetchedStores] = useState(new Set());

    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

    useEffect(() => {
        fetchHierarchy(0, true);
    }, []);

    // On tenant switch: clear filters + expansion/fetch caches (they belong to the
    // previous tenant) and reload page 1. Skip the initial mount so we don't
    // double-fetch alongside the mount effect above.
    const didMountRef = useRef(false);
    useEffect(() => {
        if (!didMountRef.current) { didMountRef.current = true; return; }
        setFilters({
            merchantOnboardingFrom: '', merchantOnboardingTo: '',
            storeCreatedFrom: '', storeCreatedTo: '',
            terminalCreatedFrom: '', terminalCreatedTo: '',
            search: '', sid: '', tid: '', storeName: ''
        });
        setExpandedMerchants({});
        setExpandedStores({});
        setFetchedMerchants(new Set());
        setFetchedStores(new Set());
        setPage(0);
        fetchHierarchy(0, true, true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantVersion]);

    // Failures used to be console-only, so a 403/500/expired token looked like
    // an empty tenant; the raw fetch also bypassed the axios 401-refresh flow.
    const [error, setError] = useState(null);
    // Discard out-of-order responses — the numbered page buttons could fire two
    // overlapping fetches and the slower one won.
    const reqIdRef = useRef(0);

    const fetchHierarchy = async (pageIdx = 0, reset = false, clear = false) => {
        const reqId = ++reqIdRef.current;
        setLoading(true);
        setError(null);
        try {
            const currentFilters = clear ? {} : filters;
            const params = { page: pageIdx, size: 20 };

            if (currentFilters.search) params.search = currentFilters.search;
            if (currentFilters.sid) params.sid = currentFilters.sid;
            if (currentFilters.tid) params.tid = currentFilters.tid;
            if (currentFilters.storeName) params.storeName = currentFilters.storeName;

            if (currentFilters.merchantOnboardingFrom) params.mFrom = currentFilters.merchantOnboardingFrom;
            if (currentFilters.merchantOnboardingTo) params.mTo = currentFilters.merchantOnboardingTo;
            if (currentFilters.storeCreatedFrom) params.sFrom = currentFilters.storeCreatedFrom;
            if (currentFilters.storeCreatedTo) params.sTo = currentFilters.storeCreatedTo;
            if (currentFilters.terminalCreatedFrom) params.tFrom = currentFilters.terminalCreatedFrom;
            if (currentFilters.terminalCreatedTo) params.tTo = currentFilters.terminalCreatedTo;

            const res = await api.get('/merchants/hierarchy', { params });
            if (reqId !== reqIdRef.current) return;
            const data = res.data || {};
            setMerchants(data.content || []);
            setHasMore(!data.last);
            setTotalPages(data.totalPages || 0);
            setTotalElements(data.totalElements || 0);

            if ((data.content || []).length > 0 && (data.content[0].stores && data.content[0].stores.length > 0)) {
                autoExpand(data.content);
            }
        } catch (e) {
            if (reqId !== reqIdRef.current) return;
            console.error("Failed to fetch hierarchy", e);
            setMerchants([]);
            setTotalPages(0);
            setTotalElements(0);
            setError(e?.response?.data?.error || e?.response?.statusText || 'Could not load the merchant hierarchy.');
        } finally {
            if (reqId === reqIdRef.current) setLoading(false);
        }
    };

    const autoExpand = (data) => {
        const newExpandedMerchants = { ...expandedMerchants };
        const newExpandedStores = { ...expandedStores };
        const shouldExpand = filters.search || filters.sid || filters.tid || filters.storeName;

        if (shouldExpand) {
            data.forEach(m => {
                newExpandedMerchants[m.merchantId] = true;
                if (m.stores) {
                    m.stores.forEach(s => {
                        newExpandedStores[s.storeId] = true;
                    });
                }
            });
            setExpandedMerchants(newExpandedMerchants);
            setExpandedStores(newExpandedStores);
        }
    };

    const toggleMerchant = async (id) => {
        setExpandedMerchants(prev => ({ ...prev, [id]: !prev[id] }));

        const m = merchants.find(m => m.merchantId === id);
        if (m && (!m.stores || m.stores.length === 0)) {
            if (!fetchedMerchants.has(id)) {
                await fetchStoresForMerchant(id);
            }
        }
    };

    const fetchStoresForMerchant = async (merchantId) => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const res = await fetch(`/api/merchants/${merchantId}/stores`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) {
                const stores = await res.json();
                const storeDtos = stores.map(s => ({
                    storeId: s.storeId, name: s.name, sid: s.sid, status: s.status, createdDate: s.createdDate,
                    terminals: []
                }));

                setMerchants(prev => prev.map(m =>
                    m.merchantId === merchantId ? { ...m, stores: storeDtos } : m
                ));
                setFetchedMerchants(prev => new Set(prev).add(merchantId));
            }
        } catch (e) { console.error(e); }
    };

    const toggleStore = async (storeId, merchantId) => {
        setExpandedStores(prev => ({ ...prev, [storeId]: !prev[storeId] }));
        // Skip the fetch when the hierarchy response already delivered this
        // store's terminals: those are FILTERED (TID / created-date), and the
        // /stores/{id}/terminals endpoint returns ALL terminals — expanding
        // used to overwrite the filtered list with unfiltered rows.
        const m = merchants.find(mm => mm.merchantId === merchantId);
        const s = m?.stores?.find(ss => ss.storeId === storeId);
        if (s?.terminals?.length > 0) return;
        if (!fetchedStores.has(storeId)) {
            await fetchTerminalsForStore(storeId, merchantId);
        }
    };

    const fetchTerminalsForStore = async (storeId, merchantId) => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const res = await fetch(`/api/stores/${storeId}/terminals`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) {
                const terminals = await res.json();
                const tDtos = terminals.map(t => ({
                    terminalId: t.terminalId, tid: t.tid, deviceNumber: t.deviceNumber,
                    type: t.type, status: t.status, createdDate: t.createdDate
                }));

                setMerchants(prev => prev.map(m => {
                    if (m.merchantId === merchantId && m.stores) {
                        return {
                            ...m,
                            stores: m.stores.map(s =>
                                s.storeId === storeId ? { ...s, terminals: tDtos } : s
                            )
                        };
                    }
                    return m;
                }));
                setFetchedStores(prev => new Set(prev).add(storeId));
            }
        } catch (e) { console.error(e); }
    };

    const handleFilterChange = (key, value) => {
        setFilters(prev => ({ ...prev, [key]: value }));
    };

    const applyFilters = () => {
        setPage(0);
        setFetchedMerchants(new Set());
        setFetchedStores(new Set());
        fetchHierarchy(0, true);
    };

    const clearFilters = () => {
        setFilters({
            merchantOnboardingFrom: '', merchantOnboardingTo: '',
            storeCreatedFrom: '', storeCreatedTo: '',
            terminalCreatedFrom: '', terminalCreatedTo: '',
            search: '', sid: '', tid: '', storeName: ''
        });
        setPage(0);
        setFetchedMerchants(new Set());
        setFetchedStores(new Set());
        // clear=true bypasses stale filter state directly — no deferral needed.
        fetchHierarchy(0, true, true);
    };

    const GRID = '40px 2fr 1fr 1fr 1fr';

    if (loading && page === 0 && merchants.length === 0) return <Loader />;

    return (
        <div className="mh-root">

            {/* ── Filter card ── */}
            <div className="mh-card mh-filter">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
                    <span style={{ fontSize: '0.78rem', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-muted)' }}>
                        Filters
                    </span>
                    <button onClick={clearFilters} className="mh-clear">
                        <X size={13} /> Clear
                    </button>
                </div>

                {/* Row 1: Search + text filters */}
                <div style={{ display: 'flex', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
                    <div style={{ position: 'relative', flex: '2 1 280px', minWidth: 200 }}>
                        <Search size={15} style={{ position: 'absolute', left: 11, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
                        <input
                            type="text"
                            placeholder="Search merchant name, MID…"
                            value={filters.search}
                            onChange={(e) => handleFilterChange('search', e.target.value)}
                            onKeyDown={(e) => e.key === 'Enter' && applyFilters()}
                            className="mh-input"
                            style={{ paddingLeft: 35 }}
                        />
                    </div>
                    <input type="text" placeholder="SID" value={filters.sid} onChange={(e) => handleFilterChange('sid', e.target.value)}
                        className="mh-input" style={{ flex: '1 1 120px', minWidth: 100 }} />
                    <input type="text" placeholder="TID" value={filters.tid} onChange={(e) => handleFilterChange('tid', e.target.value)}
                        className="mh-input" style={{ flex: '1 1 120px', minWidth: 100 }} />
                    <input type="text" placeholder="Store name" value={filters.storeName} onChange={(e) => handleFilterChange('storeName', e.target.value)}
                        className="mh-input" style={{ flex: '1 1 150px', minWidth: 120 }} />
                </div>

                {/* Row 2: Date filters + Apply */}
                <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
                    <div style={{ flex: '1 1 220px', minWidth: 200 }}>
                        <DateRangePicker
                            label="Merchant Onboarded"
                            startDate={filters.merchantOnboardingFrom}
                            endDate={filters.merchantOnboardingTo}
                            onChange={(start, end) => {
                                handleFilterChange('merchantOnboardingFrom', start);
                                handleFilterChange('merchantOnboardingTo', end);
                            }}
                        />
                    </div>
                    <div style={{ flex: '1 1 220px', minWidth: 200 }}>
                        <DateRangePicker
                            label="Store Created"
                            startDate={filters.storeCreatedFrom}
                            endDate={filters.storeCreatedTo}
                            onChange={(start, end) => {
                                handleFilterChange('storeCreatedFrom', start);
                                handleFilterChange('storeCreatedTo', end);
                            }}
                        />
                    </div>
                    <div style={{ flex: '1 1 220px', minWidth: 200 }}>
                        <DateRangePicker
                            label="Terminal Created"
                            startDate={filters.terminalCreatedFrom}
                            endDate={filters.terminalCreatedTo}
                            onChange={(start, end) => {
                                handleFilterChange('terminalCreatedFrom', start);
                                handleFilterChange('terminalCreatedTo', end);
                            }}
                        />
                    </div>
                    <button onClick={applyFilters} className="mh-apply">
                        <Filter size={14} /> Apply
                    </button>
                </div>
            </div>

            {/* ── Table card ── */}
            <div className="mh-card" style={{ overflow: 'hidden', padding: 0 }}>
                <div className="mh-thead" style={{ gridTemplateColumns: GRID }}>
                    <div></div>
                    <div>Entity Name</div>
                    <div>ID (MID/SID/TID)</div>
                    <div>Status</div>
                    <div>Created Date</div>
                </div>

                {error && (
                    <div role="alert" style={{
                        display: 'flex', alignItems: 'center', gap: 8, margin: 16, padding: '10px 14px',
                        borderRadius: 'var(--radius-md, 10px)', border: '1px solid var(--danger-border, #fecaca)',
                        background: 'var(--danger-bg, #fef2f2)', color: 'var(--danger-text, #991b1b)',
                        fontSize: '0.82rem', fontWeight: 600,
                    }}>
                        <AlertTriangle size={15} /> {error}
                        <button onClick={() => fetchHierarchy(page, true)}
                            style={{ marginLeft: 'auto', fontSize: '0.78rem', fontWeight: 700, color: 'var(--brand, #2563eb)',
                                background: 'var(--bg-card, #fff)', border: '1px solid var(--border, #e2e8f0)',
                                borderRadius: 6, padding: '4px 10px', cursor: 'pointer' }}>Retry</button>
                    </div>
                )}
                {loading ? (
                    // Loading covers EVERY fetch, not just the first: page changes
                    // and filter applies used to leave stale rows with no signal.
                    <div style={{ padding: 48, textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.9rem' }}>
                        Loading…
                    </div>
                ) : merchants.length === 0 ? (
                    <div style={{ padding: 48, textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.9rem' }}>
                        {error ? 'The merchant list could not be loaded.' : 'No data found matching your filters.'}
                    </div>
                ) : (
                    merchants.map(m => (
                        <div key={m.merchantId} style={{ borderBottom: '1px solid var(--border-light)' }}>
                            <div
                                onClick={() => toggleMerchant(m.merchantId)}
                                className="mh-row"
                                style={{
                                    gridTemplateColumns: GRID,
                                    background: expandedMerchants[m.merchantId] ? 'var(--brand-light)' : 'transparent',
                                }}
                            >
                                <div>
                                    {expandedMerchants[m.merchantId]
                                        ? <ChevronDown size={17} style={{ color: 'var(--brand)' }} />
                                        : <ChevronRight size={17} style={{ color: 'var(--text-muted)' }} />}
                                </div>
                                <div style={{ fontWeight: 600, color: 'var(--text)' }}>{m.name}</div>
                                <div style={{ fontFamily: 'ui-monospace, monospace', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>{m.mid}</div>
                                <div><StatusBadge status={m.status} /></div>
                                <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>{formatDate(m.createdDate)}</div>
                            </div>

                            {expandedMerchants[m.merchantId] && m.stores && m.stores.map(s => (
                                <div key={s.storeId}>
                                    <div
                                        onClick={() => toggleStore(s.storeId, m.merchantId)}
                                        className="mh-row mh-row-store"
                                        style={{ gridTemplateColumns: GRID }}
                                    >
                                        <div style={{ paddingLeft: 22 }}>
                                            {expandedStores[s.storeId]
                                                ? <ChevronDown size={15} style={{ color: 'var(--brand)' }} />
                                                : <ChevronRight size={15} style={{ color: 'var(--text-muted)' }} />}
                                        </div>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text)' }}>
                                            <Store size={14} style={{ color: 'var(--text-secondary)' }} />
                                            <span>{s.name}</span>
                                        </div>
                                        <div style={{ fontFamily: 'ui-monospace, monospace', color: 'var(--text-secondary)', fontSize: '0.82rem' }}>{s.sid}</div>
                                        <div><StatusBadge status={s.status} size="small" /></div>
                                        <div style={{ color: 'var(--text-secondary)', fontSize: '0.82rem' }}>{formatDate(s.createdDate)}</div>
                                    </div>

                                    {expandedStores[s.storeId] && s.terminals && s.terminals.map(t => (
                                        <div
                                            key={t.terminalId}
                                            className="mh-row mh-row-terminal"
                                            style={{ gridTemplateColumns: GRID }}
                                        >
                                            <div></div>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-secondary)', paddingLeft: 40 }}>
                                                <CreditCard size={14} />
                                                <span>{t.deviceNumber || 'Terminal'}</span>
                                            </div>
                                            <div style={{ fontFamily: 'ui-monospace, monospace', color: 'var(--text-secondary)', fontSize: '0.82rem' }}>{t.tid}</div>
                                            <div><StatusBadge status={t.status} size="small" /></div>
                                            <div style={{ color: 'var(--text-secondary)', fontSize: '0.82rem' }}>{formatDate(t.createdDate)}</div>
                                        </div>
                                    ))}
                                </div>
                            ))}
                        </div>
                    ))
                )}

                {/* Pagination */}
                {totalPages > 1 && (
                    <div className="mh-pager">
                        <span style={{ fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
                            Page {page + 1} of {totalPages} • {totalElements} total merchants
                        </span>
                        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                            <button
                                onClick={() => { const p = Math.max(0, page - 1); setPage(p); fetchHierarchy(p, true); }}
                                disabled={page === 0 || loading}
                                className="mh-page-btn"
                            >← Prev</button>
                            {totalPages <= 7 ? (
                                Array.from({ length: totalPages }, (_, i) => (
                                    <button key={i} disabled={loading} onClick={() => { setPage(i); fetchHierarchy(i, true); }}
                                        className={`mh-page-num${page === i ? ' active' : ''}`}
                                    >{i + 1}</button>
                                ))
                            ) : (
                                <>
                                    {[0, 1, 2].filter(i => i < totalPages).map(i => (
                                        <button key={i} disabled={loading} onClick={() => { setPage(i); fetchHierarchy(i, true); }}
                                            className={`mh-page-num${page === i ? ' active' : ''}`}
                                        >{i + 1}</button>
                                    ))}
                                    {page > 3 && <span style={{ padding: '8px 4px', color: 'var(--text-muted)' }}>…</span>}
                                    {page > 2 && page < totalPages - 3 && (
                                        <button className="mh-page-num active">{page + 1}</button>
                                    )}
                                    {page < totalPages - 4 && <span style={{ padding: '8px 4px', color: 'var(--text-muted)' }}>…</span>}
                                    {[totalPages - 3, totalPages - 2, totalPages - 1].filter(i => i >= 3 && i < totalPages).map(i => (
                                        <button key={i} disabled={loading} onClick={() => { setPage(i); fetchHierarchy(i, true); }}
                                            className={`mh-page-num${page === i ? ' active' : ''}`}
                                        >{i + 1}</button>
                                    ))}
                                </>
                            )}
                            <button
                                onClick={() => { const p = Math.min(totalPages - 1, page + 1); setPage(p); fetchHierarchy(p, true); }}
                                disabled={page >= totalPages - 1 || loading}
                                className="mh-page-btn"
                            >Next →</button>
                        </div>
                    </div>
                )}
            </div>

            <style>{`
                .mh-root { display: flex; flex-direction: column; gap: 18px; padding: 24px; }
                .mh-card {
                    background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-lg); box-shadow: var(--shadow-card);
                }
                .mh-filter { padding: 20px; }
                .mh-clear {
                    display: inline-flex; align-items: center; gap: 5px; background: none; border: none;
                    cursor: pointer; font-size: 0.8rem; font-weight: 600; color: var(--text-secondary);
                    transition: color 0.15s;
                }
                .mh-clear:hover { color: var(--danger); }
                .mh-input {
                    width: 100%; padding: 9px 12px; border-radius: var(--radius-md);
                    border: 1px solid var(--border); background: var(--bg-card); color: var(--text);
                    font-size: 0.86rem; font-family: inherit; box-sizing: border-box; transition: border-color 0.15s;
                }
                .mh-input::placeholder { color: var(--text-muted); }
                .mh-input:focus { outline: none; border-color: var(--brand); }
                .mh-apply {
                    display: inline-flex; align-items: center; justify-content: center; gap: 7px;
                    background: var(--brand); color: #fff; border: none; border-radius: var(--radius-md);
                    padding: 9px 22px; cursor: pointer; font-weight: 600; font-size: 0.86rem;
                    white-space: nowrap; flex-shrink: 0; transition: background 0.15s; height: 38px;
                }
                .mh-apply:hover { background: var(--brand-dark); }

                .mh-thead {
                    display: grid; padding: 13px 20px; background: var(--bg-subtle);
                    border-bottom: 1px solid var(--border); font-weight: 700; color: var(--text-secondary);
                    font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.05em;
                }
                .mh-row {
                    display: grid; padding: 13px 20px; align-items: center; cursor: pointer;
                    transition: background 0.15s;
                }
                .mh-row:hover { background: var(--bg-hover); }
                .mh-row-store { background: var(--bg-subtle); border-top: 1px solid var(--border-light); }
                .mh-row-terminal {
                    background: var(--bg-card); border-top: 1px dashed var(--border);
                    font-size: 0.85rem; cursor: default;
                }
                .mh-row-terminal:hover { background: var(--bg-card); }

                .mh-pager {
                    padding: 14px 20px; border-top: 1px solid var(--border); display: flex;
                    justify-content: space-between; align-items: center; background: var(--bg-subtle);
                    flex-wrap: wrap; gap: 10px;
                }
                .mh-page-btn {
                    padding: 7px 14px; background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-md); cursor: pointer; color: var(--brand);
                    font-weight: 600; font-size: 0.82rem; transition: all 0.15s;
                }
                .mh-page-btn:hover:not(:disabled) { border-color: var(--brand); }
                .mh-page-btn:disabled { color: var(--text-disabled); cursor: default; opacity: 0.6; }
                .mh-page-num {
                    padding: 7px 11px; border: 1px solid var(--border); border-radius: var(--radius-md);
                    cursor: pointer; font-weight: 600; font-size: 0.82rem; min-width: 36px;
                    background: var(--bg-card); color: var(--text-secondary); transition: all 0.15s;
                }
                .mh-page-num:hover { border-color: var(--brand); color: var(--text); }
                .mh-page-num.active { background: var(--brand); color: #fff; border-color: var(--brand); }
            `}</style>
        </div>
    );
};

const StatusBadge = ({ status, size = 'normal' }) => {
    const isActive = status === 'ACTIVE';
    return (
        <span style={{
            padding: size === 'small' ? '2px 8px' : '4px 10px',
            borderRadius: 999,
            fontSize: size === 'small' ? '0.68rem' : '0.72rem',
            fontWeight: 700,
            background: isActive ? 'var(--success-bg)' : 'var(--danger-bg)',
            color: isActive ? 'var(--success-text)' : 'var(--danger-text)',
            border: '1px solid var(--border-light)',
            display: 'inline-block'
        }}>
            {status}
        </span>
    );
};

const formatDate = (dateString) => {
    if (!dateString) return '-';
    return new Date(dateString).toLocaleDateString();
};

export default MerchantHierarchy;
