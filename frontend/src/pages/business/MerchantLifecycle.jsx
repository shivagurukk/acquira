import React, { useState, useEffect } from 'react';
import { Users } from 'lucide-react';
import Loader from '../../components/Loader';
import BusinessFilters from '../../components/BusinessFilters';

const MerchantLifecycle = () => {
    const [stats, setStats] = useState(null);
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState({});

    useEffect(() => {
        fetchStats();
    }, [filters]);

    const fetchStats = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');

            const queryParams = new URLSearchParams();
            if (filters.startDate) queryParams.append('startDate', filters.startDate);
            if (filters.endDate) queryParams.append('endDate', filters.endDate);

            const res = await fetch(`/api/business/lifecycle/summary?${queryParams.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) setStats(await res.json());
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    const handleFilterChange = (newFilters) => {
        setFilters(newFilters);
    };

    if (loading) return <Loader />;

    return (
        <div className="p-6 bg-slate-50 min-h-screen flex flex-col gap-6">
            <div className="flex justify-between items-center">
                <h2 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
                    <Users size={24} /> Merchant Lifecycle
                </h2>
            </div>

            <BusinessFilters onFilterChange={handleFilterChange} />

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '20px' }}>
                <StatusCard title="Onboarded" count={stats?.ONBOARDED || 0} color="#3b82f6" />
                <StatusCard title="Activated" count={stats?.ACTIVATED || 0} color="#10b981" />
                <StatusCard title="Active" count={stats?.ACTIVE || 0} color="#8b5cf6" />
                <StatusCard title="Dormant" count={stats?.DORMANT || 0} color="#f59e0b" />
                <StatusCard title="Churned" count={0} color="#ef4444" />
            </div>
        </div>
    );
};

const StatusCard = ({ title, count, color }) => (
    <div style={{ background: 'white', padding: '24px', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', textAlign: 'center', borderTop: `4px solid ${color}` }}>
        <div style={{ fontSize: '0.9rem', color: '#64748b', fontWeight: '600' }}>{title}</div>
        <div style={{ fontSize: '2rem', fontWeight: 'bold', color: '#0f172a', marginTop: '10px' }}>{count}</div>
    </div>
);

export default MerchantLifecycle;
