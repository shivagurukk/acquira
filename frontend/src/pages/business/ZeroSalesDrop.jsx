import React, { useState, useEffect } from 'react';
import { AlertCircle, ChevronLeft, ChevronRight } from 'lucide-react';
import Loader from '../../components/Loader';

const ZeroSalesDrop = () => {
    const [merchants, setMerchants] = useState([]);
    const [loading, setLoading] = useState(true);
    const [days, setDays] = useState(30);

    useEffect(() => {
        fetchData();
    }, [days]);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const res = await fetch(`/api/business/zero-sales?days=${days}&size=20`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) {
                const data = await res.json();
                setMerchants(data.content);
            }
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div style={{ padding: '24px', background: '#f8fafc', minHeight: '100vh' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
                <h2 style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#0f172a', display: 'flex', alignItems: 'center', gap: '10px' }}>
                    <AlertCircle size={24} /> Zero Sales & Drop Alerts
                </h2>
                <select value={days} onChange={e => setDays(e.target.value)} style={{ padding: '8px 12px', borderRadius: '8px', border: '1px solid #e2e8f0' }}>
                    <option value={7}>Last 7 Days</option>
                    <option value={30}>Last 30 Days</option>
                </select>
            </div>

            <div style={{ background: 'white', borderRadius: '12px', overflow: 'hidden', boxShadow: '0 1px 3px rgba(0,0,0,0.05)' }}>
                {loading ? <Loader /> : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem' }}>
                        <thead style={{ background: '#f8fafc', color: '#64748b' }}>
                            <tr>
                                <th style={thStyle}>Merchant ID</th>
                                <th style={thStyle}>Last Action</th>
                                <th style={thStyle}>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            {merchants.length === 0 ? (
                                <tr><td colSpan="3" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>No zero sales merchants found.</td></tr>
                            ) : (
                                merchants.map(m => (
                                    <tr key={m.id} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                        <td style={tdStyle}>{m.merchantId}</td>
                                        <td style={tdStyle}>{m.lastTxnDate || 'Never'}</td>
                                        <td style={tdStyle}>
                                            <span style={{ padding: '2px 8px', borderRadius: '12px', background: '#fee2e2', color: '#b91c1c', fontSize: '0.75rem', fontWeight: 'bold' }}>
                                                No Sales ({days}d)
                                            </span>
                                        </td>
                                    </tr>
                                ))
                            )}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
};

const thStyle = { padding: '16px', textAlign: 'left', fontWeight: '600' };
const tdStyle = { padding: '16px', color: '#334155' };

export default ZeroSalesDrop;
