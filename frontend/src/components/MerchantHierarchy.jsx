import React, { useState, useEffect } from 'react';
import { Search, Filter, Calendar, Store, CreditCard, ChevronDown, ChevronRight, X } from 'lucide-react';
import Loader from '../components/Loader';
import DateRangePicker from './DateRangePicker';

const MerchantHierarchy = ({ viewMode = 'LIST' }) => {
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

    useEffect(() => {
        fetchHierarchy(0, true);
    }, []);

    const fetchHierarchy = async (pageIdx = 0, reset = false, clear = false) => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');

            const currentFilters = clear ? {} : filters;
            const queryParams = new URLSearchParams();
            queryParams.append('page', pageIdx);
            queryParams.append('size', 20);

            if (currentFilters.search) queryParams.append('search', currentFilters.search);
            if (currentFilters.sid) queryParams.append('sid', currentFilters.sid);
            if (currentFilters.tid) queryParams.append('tid', currentFilters.tid);
            if (currentFilters.storeName) queryParams.append('storeName', currentFilters.storeName);

            if (currentFilters.merchantOnboardingFrom) queryParams.append('mFrom', currentFilters.merchantOnboardingFrom);
            if (currentFilters.merchantOnboardingTo) queryParams.append('mTo', currentFilters.merchantOnboardingTo);
            if (currentFilters.storeCreatedFrom) queryParams.append('sFrom', currentFilters.storeCreatedFrom);
            if (currentFilters.storeCreatedTo) queryParams.append('sTo', currentFilters.storeCreatedTo);
            if (currentFilters.terminalCreatedFrom) queryParams.append('tFrom', currentFilters.terminalCreatedFrom);
            if (currentFilters.terminalCreatedTo) queryParams.append('tTo', currentFilters.terminalCreatedTo);

            const res = await fetch(`/api/merchants/hierarchy?${queryParams.toString()}`, {
                headers: {
                    'Authorization': `Bearer ${token}`,
                    'X-Tenant-Id': tenantId
                }
            });

            if (res.ok) {
                const data = await res.json();
                setMerchants(data.content || []);
                setHasMore(!data.last);
                setTotalPages(data.totalPages || 0);
                setTotalElements(data.totalElements || 0);

                if (data.content.length > 0 && (data.content[0].stores && data.content[0].stores.length > 0)) {
                    autoExpand(data.content);
                }
            }
        } catch (error) {
            console.error("Failed to fetch hierarchy", error);
        } finally {
            setLoading(false);
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

    const [fetchedMerchants, setFetchedMerchants] = useState(new Set());
    const [fetchedStores, setFetchedStores] = useState(new Set());

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

    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

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
        setTimeout(() => fetchHierarchy(0, true, true), 50);
    };

    if (loading && page === 0 && merchants.length === 0) return <Loader />;

    return (
        <div style={{ padding: '24px', background: '#f8fafc', minHeight: '100vh', display: 'flex', flexDirection: 'column', gap: '20px' }}>

            <div style={{ background: 'white', padding: '20px', borderRadius: '12px', boxShadow: '0 1px 2px rgba(0,0,0,0.05)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                    <h2 style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#0f172a' }}>
                        {viewMode === 'STORES' ? 'Store Management' : viewMode === 'TERMINALS' ? 'Terminal Management' : 'Merchant Universe'}
                    </h2>
                    <button onClick={clearFilters} style={{ fontSize: '0.9rem', color: '#64748b', background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '5px' }}>
                        <X size={16} /> Clear Filters
                    </button>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '15px' }}>
                    <div style={{ position: 'relative', gridColumn: 'span 2' }}>
                        <Search size={16} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: '#94a3b8' }} />
                        <input
                            type="text"
                            placeholder="Search Merchant Name, MID..."
                            value={filters.search}
                            onChange={(e) => handleFilterChange('search', e.target.value)}
                            style={{
                                width: '100%', padding: '10px 10px 10px 35px', borderRadius: '8px',
                                border: '1px solid #e2e8f0', fontSize: '0.9rem'
                            }}
                        />
                    </div>

                    <input type="text" placeholder="Filter by SID" value={filters.sid} onChange={(e) => handleFilterChange('sid', e.target.value)} style={inputStyle} />
                    <input type="text" placeholder="Filter by TID" value={filters.tid} onChange={(e) => handleFilterChange('tid', e.target.value)} style={inputStyle} />
                    <input type="text" placeholder="Filter by Store Name" value={filters.storeName} onChange={(e) => handleFilterChange('storeName', e.target.value)} style={inputStyle} />

                    <DateRangePicker
                        label="Merchant Onboarded"
                        startDate={filters.merchantOnboardingFrom}
                        endDate={filters.merchantOnboardingTo}
                        onChange={(start, end) => {
                            handleFilterChange('merchantOnboardingFrom', start);
                            handleFilterChange('merchantOnboardingTo', end);
                        }}
                    />

                    <DateRangePicker
                        label="Store Created"
                        startDate={filters.storeCreatedFrom}
                        endDate={filters.storeCreatedTo}
                        onChange={(start, end) => {
                            handleFilterChange('storeCreatedFrom', start);
                            handleFilterChange('storeCreatedTo', end);
                        }}
                    />

                    <DateRangePicker
                        label="Terminal Created"
                        startDate={filters.terminalCreatedFrom}
                        endDate={filters.terminalCreatedTo}
                        onChange={(start, end) => {
                            handleFilterChange('terminalCreatedFrom', start);
                            handleFilterChange('terminalCreatedTo', end);
                        }}
                    />

                    <button
                        onClick={applyFilters}
                        style={{
                            background: '#3b82f6', color: 'white', border: 'none', borderRadius: '8px',
                            padding: '0 20px', cursor: 'pointer', fontWeight: 'bold',
                            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', height: '42px', marginTop: 'auto'
                        }}
                    >
                        <Filter size={16} /> Apply
                    </button>
                </div>
            </div>

            <div style={{ background: 'white', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)', overflow: 'hidden' }}>
                <div style={{ padding: '15px 20px', background: '#f8fafc', borderBottom: '1px solid #e2e8f0', display: 'grid', gridTemplateColumns: '40px 2fr 1fr 1fr 1fr', fontWeight: 'bold', color: '#64748b', fontSize: '0.85rem' }}>
                    <div></div>
                    <div>Entity Name</div>
                    <div>ID (MID/SID/TID)</div>
                    <div>Status</div>
                    <div>Created Date</div>
                </div>

                {merchants.length === 0 ? (
                    <div style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>
                        {loading ? 'Loading...' : 'No data found matching your filters.'}
                    </div>
                ) : (
                    <>
                        {merchants.map(m => (
                            <div key={m.merchantId} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                <div
                                    onClick={() => toggleMerchant(m.merchantId)}
                                    style={{
                                        padding: '15px 20px', display: 'grid', gridTemplateColumns: '40px 2fr 1fr 1fr 1fr',
                                        alignItems: 'center', cursor: 'pointer',
                                        background: expandedMerchants[m.merchantId] ? '#eff6ff' : 'white',
                                        transition: 'background 0.2s'
                                    }}
                                >
                                    <div>
                                        {expandedMerchants[m.merchantId] ? <ChevronDown size={18} color="#3b82f6" /> : <ChevronRight size={18} color="#94a3b8" />}
                                    </div>
                                    <div style={{ fontWeight: '600', color: '#0f172a' }}>{m.name}</div>
                                    <div style={{ fontFamily: 'monospace', color: '#64748b' }}>{m.mid}</div>
                                    <div><StatusBadge status={m.status} /></div>
                                    <div style={{ color: '#64748b', fontSize: '0.9rem' }}>{formatDate(m.createdDate)}</div>
                                </div>

                                {expandedMerchants[m.merchantId] && m.stores && m.stores.map(s => (
                                    <div key={s.storeId}>
                                        <div
                                            onClick={() => toggleStore(s.storeId, m.merchantId)}
                                            style={{
                                                padding: '12px 20px 12px 60px', display: 'grid', gridTemplateColumns: '40px 2fr 1fr 1fr 1fr',
                                                alignItems: 'center', cursor: 'pointer',
                                                background: '#f8fafc', borderTop: '1px solid #f1f5f9'
                                            }}
                                        >
                                            <div>
                                                {expandedStores[s.storeId] ? <ChevronDown size={16} color="#3b82f6" /> : <ChevronRight size={16} color="#94a3b8" />}
                                            </div>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                                <Store size={14} color="#64748b" />
                                                <span>{s.name}</span>
                                            </div>
                                            <div style={{ fontFamily: 'monospace', color: '#64748b', fontSize: '0.9rem' }}>{s.sid}</div>
                                            <div><StatusBadge status={s.status} size="small" /></div>
                                            <div style={{ color: '#64748b', fontSize: '0.85rem' }}>{formatDate(s.createdDate)}</div>
                                        </div>

                                        {expandedStores[s.storeId] && s.terminals && s.terminals.map(t => (
                                            <div
                                                key={t.terminalId}
                                                style={{
                                                    padding: '10px 20px 10px 100px', display: 'grid', gridTemplateColumns: '40px 2fr 1fr 1fr 1fr',
                                                    alignItems: 'center', background: 'white', borderTop: '1px dashed #e2e8f0', fontSize: '0.9rem'
                                                }}
                                            >
                                                <div></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#475569' }}>
                                                    <CreditCard size={14} />
                                                    <span>{t.deviceNumber || 'Terminal'}</span>
                                                </div>
                                                <div style={{ fontFamily: 'monospace', color: '#64748b' }}>{t.tid}</div>
                                                <div><StatusBadge status={t.status} size="small" /></div>
                                                <div style={{ color: '#64748b', fontSize: '0.85rem' }}>{formatDate(t.createdDate)}</div>
                                            </div>
                                        ))}
                                    </div>
                                ))}
                            </div>
                        ))}
                    </>
                )}
                {/* Pagination */}
                {totalPages > 1 && (
                    <div style={{ padding: '16px 20px', borderTop: '1px solid #e2e8f0', display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: '#f8fafc' }}>
                        <span style={{ fontSize: '0.85rem', color: '#64748b' }}>
                            Page {page + 1} of {totalPages} • {totalElements} total merchants
                        </span>
                        <div style={{ display: 'flex', gap: '8px' }}>
                            <button
                                onClick={() => { const p = Math.max(0, page - 1); setPage(p); fetchHierarchy(p, true); }}
                                disabled={page === 0 || loading}
                                style={{
                                    padding: '8px 16px', background: page === 0 ? '#f1f5f9' : '#fff', border: '1px solid #e2e8f0',
                                    borderRadius: '8px', cursor: page === 0 ? 'default' : 'pointer', color: page === 0 ? '#cbd5e1' : '#3b82f6',
                                    fontWeight: '600', fontSize: '0.85rem'
                                }}
                            >← Previous</button>
                            {totalPages <= 7 ? (
                                Array.from({ length: totalPages }, (_, i) => (
                                    <button key={i} onClick={() => { setPage(i); fetchHierarchy(i, true); }}
                                        style={{
                                            padding: '8px 12px', border: '1px solid #e2e8f0', borderRadius: '8px', cursor: 'pointer',
                                            fontWeight: '600', fontSize: '0.85rem', minWidth: '38px',
                                            background: page === i ? '#3b82f6' : '#fff', color: page === i ? '#fff' : '#64748b',
                                        }}
                                    >{i + 1}</button>
                                ))
                            ) : (
                                <>
                                    {[0, 1, 2].filter(i => i < totalPages).map(i => (
                                        <button key={i} onClick={() => { setPage(i); fetchHierarchy(i, true); }}
                                            style={{ padding: '8px 12px', border: '1px solid #e2e8f0', borderRadius: '8px', cursor: 'pointer', fontWeight: '600', fontSize: '0.85rem', minWidth: '38px', background: page === i ? '#3b82f6' : '#fff', color: page === i ? '#fff' : '#64748b' }}
                                        >{i + 1}</button>
                                    ))}
                                    {page > 3 && <span style={{ padding: '8px 4px', color: '#94a3b8' }}>…</span>}
                                    {page > 2 && page < totalPages - 3 && (
                                        <button style={{ padding: '8px 12px', border: '1px solid #e2e8f0', borderRadius: '8px', fontWeight: '600', fontSize: '0.85rem', minWidth: '38px', background: '#3b82f6', color: '#fff' }}>{page + 1}</button>
                                    )}
                                    {page < totalPages - 4 && <span style={{ padding: '8px 4px', color: '#94a3b8' }}>…</span>}
                                    {[totalPages - 3, totalPages - 2, totalPages - 1].filter(i => i >= 3 && i < totalPages).map(i => (
                                        <button key={i} onClick={() => { setPage(i); fetchHierarchy(i, true); }}
                                            style={{ padding: '8px 12px', border: '1px solid #e2e8f0', borderRadius: '8px', cursor: 'pointer', fontWeight: '600', fontSize: '0.85rem', minWidth: '38px', background: page === i ? '#3b82f6' : '#fff', color: page === i ? '#fff' : '#64748b' }}
                                        >{i + 1}</button>
                                    ))}
                                </>
                            )}
                            <button
                                onClick={() => { const p = Math.min(totalPages - 1, page + 1); setPage(p); fetchHierarchy(p, true); }}
                                disabled={page >= totalPages - 1 || loading}
                                style={{
                                    padding: '8px 16px', background: page >= totalPages - 1 ? '#f1f5f9' : '#fff', border: '1px solid #e2e8f0',
                                    borderRadius: '8px', cursor: page >= totalPages - 1 ? 'default' : 'pointer', color: page >= totalPages - 1 ? '#cbd5e1' : '#3b82f6',
                                    fontWeight: '600', fontSize: '0.85rem'
                                }}
                            >Next →</button>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
};

const StatusBadge = ({ status, size = 'normal' }) => {
    const isActive = status === 'ACTIVE';
    return (
        <span style={{
            padding: size === 'small' ? '2px 6px' : '4px 8px',
            borderRadius: '12px',
            fontSize: size === 'small' ? '0.7rem' : '0.75rem',
            fontWeight: 'bold',
            background: isActive ? '#dcfce7' : '#fee2e2',
            color: isActive ? '#166534' : '#991b1b',
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

const inputStyle = {
    padding: '10px', borderRadius: '8px', border: '1px solid #e2e8f0', width: '100%', fontSize: '0.9rem'
};



export default MerchantHierarchy;
