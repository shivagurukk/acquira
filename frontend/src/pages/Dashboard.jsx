import React from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Activity, Users, CreditCard, DollarSign, TrendingUp, Bell, ChevronRight, PieChart, AlertTriangle, Building, Upload } from 'lucide-react';
import TenantSwitcher from '../components/TenantSwitcher';
import FinancialLoader from '../components/FinancialLoader';

const Dashboard = () => {
    const navigate = useNavigate();
    const [filterValue, setFilterValue] = React.useState('Last 30 Days');


    const [stats, setStats] = React.useState([
        { label: 'Total Volume', value: '$0', change: '0%', icon: DollarSign, color: '#10B981', bg: '#ecfdf5' },
        { label: 'Active Merchants', value: '0', change: '0%', icon: Users, color: '#3B82F6', bg: '#eff6ff' },
        { label: 'Transactions', value: '0', change: '0%', icon: Activity, color: '#8B5CF6', bg: '#f5f3ff' },
        { label: 'Leakage Alert', value: '0', change: '0%', icon: AlertTriangle, color: '#EF4444', bg: '#fef2f2' },
    ]);

    React.useEffect(() => {
        const fetchMetrics = async () => {
            try {
                const token = localStorage.getItem('token');
                // Calculate dates for "Last 30 Days"
                const end = new Date();
                const start = new Date();
                start.setDate(end.getDate() - 30);

                const res = await fetch('/api/business/executive-metrics', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Authorization': `Bearer ${token}`
                    },
                    body: JSON.stringify({
                        startDate: start.toISOString().split('T')[0],
                        endDate: end.toISOString().split('T')[0]
                    })
                });

                if (res.ok) {
                    const data = await res.json();

                    const formatCurrency = (val) => {
                        if (val >= 1000000) return '$' + (val / 1000000).toFixed(1) + 'M';
                        if (val >= 1000) return '$' + (val / 1000).toFixed(1) + 'K';
                        return '$' + val.toLocaleString();
                    };

                    const formatNumber = (val) => {
                        if (val >= 1000000) return (val / 1000000).toFixed(1) + 'M';
                        if (val >= 1000) return (val / 1000).toFixed(1) + 'K';
                        return val.toLocaleString();
                    };

                    const formatGrowth = (val) => {
                        const sign = val >= 0 ? '+' : '';
                        return `${sign}${val.toFixed(1)}%`;
                    };

                    setStats([
                        {
                            label: 'Total Volume',
                            value: formatCurrency(data.totalVolume),
                            change: formatGrowth(data.volumeGrowth),
                            icon: DollarSign, color: '#10B981', bg: '#ecfdf5'
                        },
                        {
                            label: 'Active Merchants',
                            value: formatNumber(data.activeMerchants),
                            change: formatGrowth(data.merchantsGrowth),
                            icon: Users, color: '#3B82F6', bg: '#eff6ff'
                        },
                        {
                            label: 'Transactions',
                            value: formatNumber(data.totalTxns),
                            change: formatGrowth(data.txnsGrowth),
                            icon: Activity, color: '#8B5CF6', bg: '#f5f3ff'
                        },
                        {
                            label: 'Leakage Alert',
                            value: formatNumber(data.leakageCount),
                            change: formatGrowth(data.leakageGrowth),
                            icon: AlertTriangle, color: '#EF4444', bg: '#fef2f2'
                        },
                    ]);
                }
            } catch (error) {
                console.error("Failed to fetch dashboard metrics", error);
            }
        };

        fetchMetrics();
    }, []);

    return (
        <div style={{ flex: 1, padding: '40px', overflowY: 'auto' }}>
            {/* Header */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '40px' }}>
                <div>
                    <h1 style={{ fontSize: '28px', fontWeight: '700', color: '#0f172a' }}>Executive Dashboard</h1>
                    <p style={{ color: '#64748b', marginTop: '5px' }}>Overview of financial performance and merchant health.</p>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '20px' }}>
                    <TenantSwitcher />
                    <div style={{ background: '#ffffff', padding: '10px', borderRadius: '50%', border: '1px solid #e2e8f0', cursor: 'pointer' }}>
                        <Bell size={20} color="#64748b" />
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                        <div style={{ width: '40px', height: '40px', background: '#0f172a', borderRadius: '50%', color: 'white', display: 'flex', justifyContent: 'center', alignItems: 'center', fontWeight: '600' }}>AD</div>
                    </div>
                </div>
            </div>

            {/* KPI Grid */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '24px', marginBottom: '30px' }}>
                {stats.map((stat, index) => (
                    <KPICard key={index} stat={stat} index={index} />
                ))}
            </div>

            {/* Chart Section */}
            <motion.div
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.4 }}
                style={{ background: '#ffffff', borderRadius: '16px', padding: '30px', border: '1px solid #e2e8f0', boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.05)' }}
            >
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '20px' }}>
                    <h3 style={{ fontSize: '1.2rem', fontWeight: '600', color: '#0f172a' }}>Transaction Volume Trend</h3>
                    <input
                        type="text"
                        value={filterValue}
                        onChange={(e) => setFilterValue(e.target.value)}
                        placeholder="Filter period"
                        style={{ padding: '6px 12px', border: '1px solid #e2e8f0', borderRadius: '6px', color: '#64748b' }}
                    />
                </div>
                <div style={{ height: '300px', background: '#f8fafc', borderRadius: '12px', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
                    {filterValue ? (
                        <div style={{ color: '#64748b', fontSize: '0.9rem' }}>
                            Chart data loading (Simulated)
                        </div>
                    ) : null}
                </div>
            </motion.div>
        </div>
    );
};

const KPICard = ({ stat, index }) => (
    <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: index * 0.1 }}
        style={{ background: '#ffffff', padding: '24px', borderRadius: '16px', border: '1px solid #e2e8f0', boxShadow: '0 2px 4px rgba(0,0,0,0.02)' }}
    >
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '15px' }}>
            <div style={{ background: stat.bg, padding: '10px', borderRadius: '10px' }}>
                <stat.icon size={22} color={stat.color} />
            </div>
            <span style={{ color: stat.change.startsWith('+') ? '#10B981' : '#EF4444', fontSize: '13px', fontWeight: '600', display: 'flex', alignItems: 'center' }}>
                {stat.change}
            </span>
        </div>
        <h3 style={{ fontSize: '32px', fontWeight: '700', color: '#0f172a', margin: '0 0 5px 0' }}>{stat.value}</h3>
        <span style={{ color: '#64748b', fontSize: '14px' }}>{stat.label}</span>
    </motion.div>
);

export default Dashboard;
