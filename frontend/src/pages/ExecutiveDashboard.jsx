import React, { useState, useEffect } from 'react';
import { DollarSign, CreditCard, Activity, TrendingUp } from 'lucide-react';
import Loader from '../components/Loader';

const ExecutiveDashboard = () => {
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchData = async () => {
            try {
                const token = localStorage.getItem('token');
                const tenantId = localStorage.getItem('defaultTenantId');
                const response = await fetch('/api/analytics/executive', {
                    headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
                });
                if (response.ok) {
                    const result = await response.json();
                    setData(result);
                }
            } catch (error) {
                console.error("Failed to fetch dashboard", error);
            } finally {
                setLoading(false);
            }
        };
        fetchData();
    }, []);

    if (loading) return <Loader />;

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val || 0);

    return (
        <div style={{ padding: '24px' }}>
            <h1 style={{ fontSize: '24px', fontWeight: 'bold', color: '#0f172a', marginBottom: '24px' }}>Executive Overview</h1>

            {/* KPI Cards */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '24px', marginBottom: '32px' }}>
                <KpiCard title="Today's Volume" value={formatCurrency(data?.dailySnapshot?.totalVolume)} icon={<DollarSign size={24} color="#3b82f6" />} trend="+2.5%" />
                <KpiCard title="Today's Revenue" value={formatCurrency(data?.dailySnapshot?.totalRevenue)} icon={<TrendingUp size={24} color="#10b981" />} trend="+1.2%" />
                <KpiCard title="Month to Date Vol" value={formatCurrency(data?.mtdSnapshot?.totalVolume)} icon={<Activity size={24} color="#8b5cf6" />} />
                <KpiCard title="Active Merchants" value={data?.activeMerchants ? new Intl.NumberFormat().format(data.activeMerchants) : 0} icon={<CreditCard size={24} color="#f59e0b" />} />
            </div>

            {/* Trends Chart Placeholder */}
            <div style={{ background: 'white', padding: '24px', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
                <h3 style={{ fontSize: '18px', fontWeight: '600', marginBottom: '16px' }}>Volume Trend (30 Days)</h3>
                <div style={{ height: '300px', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f8fafc', borderRadius: '8px', color: '#94a3b8' }}>
                    Chart Visualization Coming Soon (Using Recharts)
                </div>
            </div>
        </div>
    );
};

const KpiCard = ({ title, value, icon, trend }) => (
    <div style={{ background: 'white', padding: '24px', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'start', marginBottom: '16px' }}>
            <div>
                <p style={{ color: '#64748b', fontSize: '14px', fontWeight: '500' }}>{title}</p>
                <h3 style={{ fontSize: '24px', fontWeight: 'bold', color: '#0f172a', marginTop: '4px' }}>{value}</h3>
            </div>
            <div style={{ padding: '12px', background: '#f1f5f9', borderRadius: '12px' }}>{icon}</div>
        </div>
        {trend && <span style={{ fontSize: '12px', color: '#10b981', fontWeight: '500' }}>{trend} vs yesterday</span>}
    </div>
);

export default ExecutiveDashboard;
