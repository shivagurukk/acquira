import React, { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { RefreshCw, Filter, Calendar, ArrowUp, ArrowDown, Search } from 'lucide-react';
import api from '../api/axios';

const GroupReports = () => {
    const [activeTab, setActiveTab] = useState('MCC'); // MCC, MERCHANT, SALES, REFERRAL
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [period, setPeriod] = useState('MONTH'); // TODAY, MONTH, YEAR, PY
    const [searchTerm, setSearchTerm] = useState('');
    const [sortConfig, setSortConfig] = useState({ key: 'volume', direction: 'desc' });

    useEffect(() => {
        fetchData();
    }, [activeTab, period]);

    const fetchData = async () => {
        setLoading(true);
        try {
            // Use api/axios.js — the interceptor attaches Authorization + X-Tenant-Id
            // automatically and uses a relative URL so the Vite dev proxy / production
            // origin both work without hardcoding `localhost:8081`.
            const res = await api.get(`/group-analytics/${activeTab}`, { params: { period } });
            setData(res.data || []);
        } catch (error) {
            console.error('group-analytics fetch failed', error);
            setData([]);
        } finally {
            setLoading(false);
        }
    };

    const handleSort = (key) => {
        let direction = 'desc';
        if (sortConfig.key === key && sortConfig.direction === 'desc') {
            direction = 'asc';
        }
        setSortConfig({ key, direction });
    };

    const sortedData = [...data].sort((a, b) => {
        if (a[sortConfig.key] < b[sortConfig.key]) return sortConfig.direction === 'asc' ? -1 : 1;
        if (a[sortConfig.key] > b[sortConfig.key]) return sortConfig.direction === 'asc' ? 1 : -1;
        return 0;
    }).filter(item =>
        item.label.toLowerCase().includes(searchTerm.toLowerCase())
    );

    const tabs = [
        { id: 'MCC', label: 'MCC Performance' },
        { id: 'MERCHANT', label: 'Top Merchants' },
        { id: 'SALES', label: 'Sales Performance' },
        { id: 'REFERRAL', label: 'Referral Partners' }
    ];

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED' }).format(val);

    return (
        <div className="page-container" style={{ padding: '30px', color: '#1e293b' }}>
            {/* Header */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '30px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: '800', color: '#0f172a' }}>Group Management Reports</h1>
                    <p style={{ color: '#64748b' }}>Analyze performance across different business groups</p>
                </div>
                <div style={{ display: 'flex', gap: '10px' }}>
                    {['TODAY', 'MONTH', 'YEAR', 'PY'].map(p => (
                        <button
                            key={p}
                            onClick={() => setPeriod(p)}
                            style={{
                                padding: '8px 16px',
                                borderRadius: '8px',
                                fontSize: '13px',
                                fontWeight: '600',
                                border: 'none',
                                cursor: 'pointer',
                                background: period === p ? '#0f172a' : '#e2e8f0',
                                color: period === p ? 'white' : '#64748b',
                                transition: 'all 0.2s'
                            }}
                        >
                            {p === 'PY' ? 'Prev Year' : p}
                        </button>
                    ))}
                    <button onClick={fetchData} style={{ padding: '8px', background: '#f1f5f9', borderRadius: '8px', border: 'none', cursor: 'pointer' }}>
                        <RefreshCw size={18} color="#64748b" />
                    </button>
                </div>
            </div>

            {/* Tabs */}
            <div style={{ display: 'flex', gap: '20px', borderBottom: '1px solid #e2e8f0', marginBottom: '20px' }}>
                {tabs.map(tab => (
                    <button
                        key={tab.id}
                        onClick={() => setActiveTab(tab.id)}
                        style={{
                            padding: '12px 0',
                            background: 'transparent',
                            border: 'none',
                            borderBottom: activeTab === tab.id ? '2px solid #3b82f6' : '2px solid transparent',
                            color: activeTab === tab.id ? '#3b82f6' : '#64748b',
                            fontWeight: activeTab === tab.id ? '600' : '500',
                            cursor: 'pointer',
                            fontSize: '15px'
                        }}
                    >
                        {tab.label}
                    </button>
                ))}
            </div>

            {/* Search & Stats */}
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '20px' }}>
                <div style={{ position: 'relative', width: '300px' }}>
                    <Search size={16} color="#94a3b8" style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)' }} />
                    <input
                        placeholder="Search..."
                        value={searchTerm}
                        onChange={(e) => setSearchTerm(e.target.value)}
                        style={{
                            width: '100%', padding: '10px 10px 10px 36px',
                            borderRadius: '8px', border: '1px solid #cbd5e1', outline: 'none'
                        }}
                    />
                </div>
                <div style={{ display: 'flex', gap: '20px', alignItems: 'center' }}>
                    <div style={{ textAlign: 'right' }}>
                        <div style={{ fontSize: '12px', color: '#64748b' }}>Total Volume</div>
                        <div style={{ fontSize: '18px', fontWeight: 'bold' }}>
                            {formatCurrency(data.reduce((sum, item) => sum + (item.volume || 0), 0))}
                        </div>
                    </div>
                </div>
            </div>

            {/* Table */}
            <div style={{ background: 'white', borderRadius: '12px', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)', overflow: 'hidden' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                        <tr style={{ background: '#f8fafc', borderBottom: '1px solid #e2e8f0', textAlign: 'left' }}>
                            <th style={{ padding: '16px', fontSize: '13px', color: '#64748b', fontWeight: '600' }}>
                                Group / Label
                            </th>
                            <th
                                style={{ padding: '16px', fontSize: '13px', color: '#64748b', fontWeight: '600', cursor: 'pointer' }}
                                onClick={() => handleSort('merchantCount')}
                            >
                                <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                                    Merchant Count
                                    {sortConfig.key === 'merchantCount' && (sortConfig.direction === 'asc' ? <ArrowUp size={14} /> : <ArrowDown size={14} />)}
                                </div>
                            </th>
                            <th
                                style={{ padding: '16px', fontSize: '13px', color: '#64748b', fontWeight: '600', cursor: 'pointer' }}
                                onClick={() => handleSort('txnCount')}
                            >
                                <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                                    Total Txns
                                    {sortConfig.key === 'txnCount' && (sortConfig.direction === 'asc' ? <ArrowUp size={14} /> : <ArrowDown size={14} />)}
                                </div>
                            </th>
                            <th
                                style={{ padding: '16px', fontSize: '13px', color: '#64748b', fontWeight: '600', cursor: 'pointer' }}
                                onClick={() => handleSort('volume')}
                            >
                                <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                                    Volume
                                    {sortConfig.key === 'volume' && (sortConfig.direction === 'asc' ? <ArrowUp size={14} /> : <ArrowDown size={14} />)}
                                </div>
                            </th>
                        </tr>
                    </thead>
                    <tbody>
                        {loading ? (
                            <tr>
                                <td colSpan="4" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>Loading...</td>
                            </tr>
                        ) : sortedData.length === 0 ? (
                            <tr>
                                <td colSpan="4" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>No Data Found</td>
                            </tr>
                        ) : (
                            sortedData.map((row, idx) => (
                                <tr key={idx} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                    <td style={{ padding: '16px', fontWeight: '500' }}>{row.label}</td>
                                    <td style={{ padding: '16px', color: '#64748b' }}>{row.merchantCount}</td>
                                    <td style={{ padding: '16px', color: '#64748b' }}>{row.txnCount}</td>
                                    <td style={{ padding: '16px', fontWeight: '600' }}>{formatCurrency(row.volume)}</td>
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default GroupReports;
