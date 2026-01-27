import React, { useState, useEffect } from 'react';
import { AlertCircle, Download, Calendar, ArrowRight } from 'lucide-react';
import Loader from '../../components/Loader';

const ZeroSales = () => {
    const [merchants, setMerchants] = useState([]);
    const [loading, setLoading] = useState(true);
    const [days, setDays] = useState(30); // 7, 30, 60
    const [showCustomPicker, setShowCustomPicker] = useState(false);

    useEffect(() => {
        fetchData();
    }, [days]);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId') || '1';
            const res = await fetch(`/api/business/zero-sales?days=${days}&size=100`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) {
                const data = await res.json();
                setMerchants(data.content || []);
            }
        } catch (error) { console.error(error); }
        finally { setLoading(false); }
    };

    return (
        <div className="page-container" style={{ padding: '20px', color: '#1e293b', height: '100vh', display: 'flex', flexDirection: 'column' }}>

            {/* Header */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: '800', color: '#0f172a', display: 'flex', alignItems: 'center', gap: '10px' }}>
                        Zero Sales Report
                    </h1>
                    <p style={{ color: '#64748b', fontSize: '13px' }}>Merchants with no transaction volume in the selected period</p>
                </div>

                <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                    <div style={{ background: '#f1f5f9', padding: '4px', borderRadius: '8px', display: 'flex', gap: '4px' }}>
                        {[7, 30, 60].map(d => (
                            <button
                                key={d}
                                onClick={() => setDays(d)}
                                style={{
                                    padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                    background: days === d ? 'white' : 'transparent',
                                    color: days === d ? '#0f172a' : '#64748b',
                                    boxShadow: days === d ? '0 1px 2px rgba(0,0,0,0.05)' : 'none',
                                    transition: 'all 0.2s'
                                }}
                            >
                                Last {d} Days
                            </button>
                        ))}
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
                            <th style={{ position: 'sticky', left: 0, zIndex: 20, background: '#f8fafc', borderBottom: '1px solid #e2e8f0', borderRight: '1px solid #e2e8f0', padding: '12px', textAlign: 'left', textTransform: 'uppercase' }}>Merchant Name</th>
                            <th style={{ borderBottom: '1px solid #e2e8f0', padding: '12px', textAlign: 'left', textTransform: 'uppercase' }}>Merchant ID</th>
                            <th style={{ borderBottom: '1px solid #e2e8f0', padding: '12px', textAlign: 'left', textTransform: 'uppercase' }}>Location</th>
                            <th style={{ borderBottom: '1px solid #e2e8f0', padding: '12px', textAlign: 'right', textTransform: 'uppercase' }}>Last Transaction</th>
                            <th style={{ borderBottom: '1px solid #e2e8f0', padding: '12px', textAlign: 'center', textTransform: 'uppercase' }}>Status</th>
                        </tr>
                    </thead>
                    <tbody style={{ fontSize: '12px' }}>
                        {loading ? (
                            <tr><td colSpan="5" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>Loading Data...</td></tr>
                        ) : merchants.length === 0 ? (
                            <tr><td colSpan="5" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>No merchants found</td></tr>
                        ) : (
                            merchants.map((m, i) => (
                                <tr key={i} style={{ borderBottom: '1px solid #f1f5f9', background: i % 2 === 0 ? 'white' : '#fafafa' }}>
                                    <td style={{ position: 'sticky', left: 0, background: i % 2 === 0 ? 'white' : '#fafafa', borderRight: '1px solid #e2e8f0', padding: '12px', fontWeight: '600', color: '#334155', borderBottom: '1px solid #f1f5f9' }}>
                                        {m.merchantName || 'Unknown Merchant'}
                                    </td>
                                    <td style={{ padding: '12px', color: '#64748b', fontFamily: 'monospace' }}>{m.merchantId}</td>
                                    <td style={{ padding: '12px', color: '#64748b' }}>{m.location || 'N/A'}</td>
                                    <td style={{ padding: '12px', textAlign: 'right', fontWeight: '500', color: '#64748b' }}>
                                        {m.lastTxnDate ? new Date(m.lastTxnDate).toLocaleDateString() : 'Never'}
                                    </td>
                                    <td style={{ padding: '12px', textAlign: 'center' }}>
                                        <span style={{ background: '#fee2e2', color: '#ef4444', padding: '4px 8px', borderRadius: '4px', fontSize: '11px', fontWeight: '700' }}>
                                            NO ACTIVITY
                                        </span>
                                    </td>
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default ZeroSales;
