import React, { useState, useEffect } from 'react';
import { Lightbulb } from 'lucide-react';
import Loader from '../../components/Loader';

const OpportunityIntelligence = () => {
    const [opps, setOpps] = useState([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        fetchOpps();
    }, []);

    const fetchOpps = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const res = await fetch('/api/business/opportunity', {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) setOpps(await res.json());
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div style={{ padding: '24px', background: '#f8fafc', minHeight: '100vh' }}>
            <h2 style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#0f172a', display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '24px' }}>
                <Lightbulb size={24} /> Opportunity Intelligence
            </h2>

            <div style={{ background: 'white', borderRadius: '12px', overflow: 'hidden', boxShadow: '0 1px 3px rgba(0,0,0,0.05)' }}>
                {loading ? <Loader /> : (
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem' }}>
                        <thead style={{ background: '#f1f5f9', color: '#64748b' }}>
                            <tr>
                                <th style={thStyle}>Merchant ID</th>
                                <th style={thStyle}>Score</th>
                                <th style={thStyle}>Reason</th>
                                <th style={thStyle}>Calc Date</th>
                            </tr>
                        </thead>
                        <tbody>
                            {opps.length === 0 ? (
                                <tr><td colSpan="4" style={{ padding: '40px', textAlign: 'center', color: '#94a3b8' }}>No opportunities identified.</td></tr>
                            ) : (
                                opps.map(o => (
                                    <tr key={o.id} style={{ borderBottom: '1px solid #e2e8f0' }}>
                                        <td style={tdStyle}>{o.merchantId}</td>
                                        <td style={tdStyle}>
                                            <div style={{ width: '40px', height: '40px', borderRadius: '50%', background: o.score >= 80 ? '#dcfce7' : '#fef3c7', color: o.score >= 80 ? '#166534' : '#92400e', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 'bold' }}>
                                                {o.score}
                                            </div>
                                        </td>
                                        <td style={tdStyle}>{o.reasonTags}</td>
                                        <td style={tdStyle}>{o.calcDate}</td>
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

export default OpportunityIntelligence;
