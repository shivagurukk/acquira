import React, { useState, useEffect } from 'react';
import { motion } from 'framer-motion';
import { TrendingUp, Users, DollarSign, Activity, Calendar, Download } from 'lucide-react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { CockpitSegmentedControl, QuerySummary } from '../../components/CockpitControls';

const SalesAnalytics = () => {
    const [loading, setLoading] = useState(true);
    const [kpis, setKpis] = useState({});
    const [trends, setTrends] = useState([]);
    const [period, setPeriod] = useState('30d'); // 7d, 30d, 90d

    useEffect(() => {
        fetchData();
    }, [period]);

    const fetchData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId') || '1';

            // Parallel fetch for KPIs and Trends
            const [kpiRes, trendRes] = await Promise.all([
                fetch('/api/business/dashboard/kpis', { headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId } }),
                fetch(`/api/business/dashboard/trends/daily?period=${period}`, { headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId } })
            ]);

            if (kpiRes.ok) setKpis(await kpiRes.json());
            if (trendRes.ok) setTrends(await trendRes.json());

        } catch (error) {
            console.error("Failed to fetch analytics", error);
        } finally {
            setLoading(false);
        }
    };

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'AED', maximumFractionDigits: 0 }).format(val || 0);
    const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);

    return (
        <div className="page-container" style={{ padding: '20px', color: '#1e293b', height: '100vh', display: 'flex', flexDirection: 'column' }}>

            {/* Header */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: '800', color: '#0f172a' }}>Sales Analytics</h1>
                    <p style={{ color: '#64748b', fontSize: '13px' }}>Real-time performance metrics and acquisition insights</p>
                </div>

                <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
                    <div style={{ background: '#f1f5f9', padding: '4px', borderRadius: '8px', display: 'flex', gap: '4px' }}>
                        {['7d', '30d', '90d'].map(p => (
                            <button
                                key={p}
                                onClick={() => setPeriod(p)}
                                style={{
                                    padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                    background: period === p ? 'white' : 'transparent',
                                    color: period === p ? '#0f172a' : '#64748b',
                                    boxShadow: period === p ? '0 1px 2px rgba(0,0,0,0.05)' : 'none'
                                }}
                            >
                                {p === '7d' ? 'Last 7 Days' : p === '30d' ? 'Last 30 Days' : 'Last 90 Days'}
                            </button>
                        ))}
                    </div>

                    <button style={{ padding: '8px 16px', background: '#0f172a', color: 'white', borderRadius: '8px', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px' }}>
                        <Download size={16} /> Export
                    </button>
                </div>
            </div>

            {/* Dashboard Container (Scrollable) */}
            <div style={{ flex: 1, overflow: 'auto', borderRadius: '8px' }}>

                {/* KPI Grid */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
                    <KpiCard
                        title="Total Volume (MTD)"
                        value={formatCurrency(kpis.mtdVolume)}
                        trend="+12.5%"
                        icon={DollarSign}
                        color="blue"
                    />
                    <KpiCard
                        title="Transactions (MTD)"
                        value={formatNumber(kpis.mtdCount)}
                        trend="+5.2%"
                        icon={Activity}
                        color="indigo"
                    />
                    <KpiCard
                        title="Active Merchants"
                        value={formatNumber(kpis.activeMerchants)}
                        trend="+8 new"
                        icon={Users}
                        color="emerald"
                    />
                    <KpiCard
                        title="Zero Sales Alerts"
                        value={formatNumber(kpis.zeroSalesMerchants || 0)}
                        trend="Needs Attention"
                        icon={TrendingUp}
                        color="amber"
                        alert
                    />
                </div>

                {/* Main Content Split */}
                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

                    {/* Chart Section */}
                    <div className="lg:col-span-2 bg-white p-6 rounded-lg border border-slate-200 shadow-sm">
                        <div className="flex justify-between items-center mb-6">
                            <h3 className="text-sm font-bold text-slate-700 uppercase">Volume Trend</h3>
                        </div>
                        <div className="h-[350px]">
                            <ResponsiveContainer width="100%" height="100%">
                                <AreaChart data={trends}>
                                    <defs>
                                        <linearGradient id="colorVol" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="5%" stopColor="#6366f1" stopOpacity={0.3} />
                                            <stop offset="95%" stopColor="#6366f1" stopOpacity={0} />
                                        </linearGradient>
                                    </defs>
                                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                    <XAxis
                                        dataKey="date"
                                        axisLine={false}
                                        tickLine={false}
                                        tick={{ fill: '#94a3b8', fontSize: 12 }}
                                        tickFormatter={(str) => new Date(str).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
                                    />
                                    <YAxis
                                        axisLine={false}
                                        tickLine={false}
                                        tick={{ fill: '#94a3b8', fontSize: 12 }}
                                        tickFormatter={(val) => `$${val / 1000}k`}
                                    />
                                    <Tooltip
                                        contentStyle={{ borderRadius: '8px', border: '1px solid #e2e8f0', boxShadow: '0 4px 6px rgba(0,0,0,0.05)' }}
                                        formatter={(val) => [formatCurrency(val), 'Volume']}
                                        labelFormatter={(label) => new Date(label).toLocaleDateString()}
                                    />
                                    <Area
                                        type="monotone"
                                        dataKey="value"
                                        stroke="#6366f1"
                                        strokeWidth={3}
                                        fillOpacity={1}
                                        fill="url(#colorVol)"
                                    />
                                </AreaChart>
                            </ResponsiveContainer>
                        </div>
                    </div>

                    {/* Side Stats / Insights */}
                    <div className="space-y-6">
                        <div className="bg-white p-6 rounded-lg border border-slate-200 shadow-sm h-full">
                            <h3 className="text-sm font-bold text-slate-700 uppercase mb-4">Acquisition Pulse</h3>
                            <div className="space-y-4">
                                <StatRow label="Onboarded This Month" value={kpis.newMerchants || 0} />
                                <StatRow label="Active Merchants" value={kpis.activeMerchants || 0} />
                                <div className="h-px bg-slate-100 my-4" />
                                <StatRow label="Dormant Merchants" value={kpis.dormantMerchants || 0} color="text-red-500" />
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>

    );
};

const KpiCard = ({ title, value, trend, icon: Icon, color, alert }) => (
    <div className={`p-6 rounded-lg border ${alert ? 'bg-amber-50 border-amber-100' : 'bg-white border-slate-200'} shadow-sm relative overflow-hidden group hover:shadow-md transition-all`}>
        <div className="flex justify-between items-start mb-4">
            <div className={`p-3 rounded-lg ${alert ? 'bg-amber-100 text-amber-600' : `bg-${color}-50 text-${color}-600`}`}>
                <Icon size={20} />
            </div>
            {trend && <span className={`text-xs font-bold px-2 py-1 rounded-full ${alert ? 'bg-white/50 text-amber-700' : 'bg-green-50 text-green-700'}`}>{trend}</span>}
        </div>
        <div className="mt-2">
            <h4 className={`text-xs uppercase font-bold tracking-wide ${alert ? 'text-amber-800' : 'text-slate-500'}`}>{title}</h4>
            <p className={`text-2xl font-black mt-1 ${alert ? 'text-amber-900' : 'text-slate-900'}`}>{value}</p>
        </div>
    </div>
);

const StatRow = ({ label, value, color = 'text-slate-900' }) => (
    <div className="flex justify-between items-center text-sm">
        <span className="text-slate-500">{label}</span>
        <span className={`font-bold ${color}`}>{value}</span>
    </div>
);

export default SalesAnalytics;
