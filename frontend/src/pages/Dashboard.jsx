import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    Activity, Users, CreditCard, DollarSign, TrendingUp, TrendingDown,
    AlertTriangle, Store, Target, RefreshCw, ArrowUpRight, ArrowDownRight,
    BarChart3, ArrowRight, Clock
} from 'lucide-react';
import {
    AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip as ReTooltip, ResponsiveContainer, PieChart as RePieChart,
    Pie, Cell
} from 'recharts';
import TenantSwitcher from '../components/TenantSwitcher';

const CHART_COLORS = ['#3b82f6', '#8b5cf6', '#06b6d4', '#10b981', '#f59e0b', '#ef4444'];

const fmt = {
    currency: (val) => {
        if (val === 0 || val == null) return '$0';
        if (val >= 1_000_000) return '$' + (val / 1_000_000).toFixed(2) + 'M';
        if (val >= 1_000) return '$' + (val / 1_000).toFixed(1) + 'K';
        return '$' + val.toLocaleString();
    },
    number: (val) => {
        if (val == null) return '0';
        if (val >= 1_000_000) return (val / 1_000_000).toFixed(1) + 'M';
        if (val >= 1_000) return (val / 1_000).toFixed(1) + 'K';
        return val.toLocaleString();
    },
    growth: (val) => {
        if (val == null) return '+0.0%';
        const sign = val >= 0 ? '+' : '';
        return `${sign}${val.toFixed(1)}%`;
    },
    date: (d) => new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
};

const Dashboard = () => {
    const navigate = useNavigate();
    const [loading, setLoading] = useState(true);
    const [period, setPeriod] = useState('30');
    const [metrics, setMetrics] = useState(null);
    const [dailyData, setDailyData] = useState([]);
    const [schemeData, setSchemeData] = useState([]);
    const [topMerchants, setTopMerchants] = useState([]);
    const [lastRefresh, setLastRefresh] = useState(new Date());

    const fetchAllData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const headers = {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`,
                'X-Tenant-Id': tenantId
            };

            const end = new Date();
            const start = new Date();
            start.setDate(end.getDate() - parseInt(period));
            const body = JSON.stringify({
                startDate: start.toISOString().split('T')[0],
                endDate: end.toISOString().split('T')[0]
            });

            const metricsRes = await fetch('/api/business/executive-metrics', {
                method: 'POST', headers, body
            });
            if (metricsRes.ok) {
                const data = await metricsRes.json();
                setMetrics(data);
            }

            try {
                const summaryRes = await fetch('/api/business/volume-revenue-summary', {
                    method: 'POST', headers, body
                });
                if (summaryRes.ok) {
                    const summaryData = await summaryRes.json();
                    if (summaryData && summaryData.length > 0) {
                        const daily = summaryData
                            .filter(r => r.business_date)
                            .sort((a, b) => new Date(a.business_date) - new Date(b.business_date))
                            .slice(-30)
                            .map(r => ({
                                date: fmt.date(r.business_date),
                                volume: Number(r.total_volume || 0),
                                txns: Number(r.total_txns || 0),
                                msf: Number(r.total_msf || 0),
                            }));
                        setDailyData(daily);

                        const merchantMap = {};
                        summaryData.forEach(r => {
                            const name = r.merchant_name || r.mid || 'Unknown';
                            if (!merchantMap[name]) merchantMap[name] = { name, volume: 0, txns: 0, msf: 0 };
                            merchantMap[name].volume += Number(r.total_volume || 0);
                            merchantMap[name].txns += Number(r.total_txns || 0);
                            merchantMap[name].msf += Number(r.total_msf || 0);
                        });
                        const sorted = Object.values(merchantMap)
                            .sort((a, b) => b.volume - a.volume)
                            .slice(0, 8);
                        setTopMerchants(sorted);
                    }
                }
            } catch (e) { /* not critical */ }

            try {
                const perfRes = await fetch('/api/business/performance-dashboard?groupBy=card_scheme', {
                    method: 'POST', headers, body
                });
                if (perfRes.ok) {
                    const perfData = await perfRes.json();
                    if (perfData && perfData.length > 0) {
                        const schemes = perfData
                            .filter(r => r.card_scheme && r.total_volume > 0)
                            .map(r => ({
                                name: r.card_scheme,
                                value: Number(r.total_volume || 0),
                                count: Number(r.total_txns || 0),
                            }))
                            .sort((a, b) => b.value - a.value)
                            .slice(0, 6);
                        setSchemeData(schemes);
                    }
                }
            } catch (e) { /* not critical */ }

            setLastRefresh(new Date());
        } catch (error) {
            console.error("Failed to fetch dashboard data", error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { fetchAllData(); }, [period]);

    const kpis = useMemo(() => {
        if (!metrics) return [];
        return [
            { label: 'Total Volume', value: fmt.currency(metrics.totalVolume), change: fmt.growth(metrics.volumeGrowth), up: metrics.volumeGrowth >= 0, icon: DollarSign, color: '#10b981', sub: 'vs previous period' },
            { label: 'Active Merchants', value: fmt.number(metrics.activeMerchants), change: fmt.growth(metrics.merchantsGrowth), up: metrics.merchantsGrowth >= 0, icon: Store, color: '#3b82f6', sub: 'unique MIDs' },
            { label: 'Transactions', value: fmt.number(metrics.totalTxns), change: fmt.growth(metrics.txnsGrowth), up: metrics.txnsGrowth >= 0, icon: Activity, color: '#8b5cf6', sub: 'total processed' },
            { label: 'Avg Txn Value', value: metrics.totalTxns > 0 ? fmt.currency(metrics.totalVolume / metrics.totalTxns) : '$0', change: '', up: true, icon: Target, color: '#06b6d4', sub: 'per transaction' },
            { label: 'Leakage Alerts', value: fmt.number(metrics.leakageCount), change: fmt.growth(metrics.leakageGrowth), up: metrics.leakageGrowth <= 0, icon: AlertTriangle, color: '#f59e0b', sub: 'flagged items' },
        ];
    }, [metrics]);

    const totalSchemeVol = useMemo(() => schemeData.reduce((s, d) => s + d.value, 0), [schemeData]);

    return (
        <div style={{ flex: 1, padding: '32px', overflowY: 'auto', background: '#f8fafc', minHeight: '100vh', fontFamily: "'Inter', 'Segoe UI', sans-serif" }}>
            {/* Header */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '28px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: '700', color: '#1e293b', margin: '0 0 4px' }}>Executive Dashboard</h1>
                    <p style={{ color: '#64748b', fontSize: '14px', margin: 0 }}>Real-time overview of financial performance and merchant health</p>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                    <div style={{ display: 'flex', background: '#fff', borderRadius: '8px', border: '1px solid #e2e8f0', overflow: 'hidden' }}>
                        {[{ label: '7D', val: '7' }, { label: '30D', val: '30' }, { label: '90D', val: '90' }, { label: 'YTD', val: '365' }].map(p => (
                            <button key={p.val} onClick={() => setPeriod(p.val)} style={{
                                padding: '7px 14px', border: 'none', cursor: 'pointer', fontSize: '12px', fontWeight: '600',
                                background: period === p.val ? '#3b82f6' : '#fff',
                                color: period === p.val ? '#fff' : '#64748b', transition: 'all 0.2s',
                            }}>{p.label}</button>
                        ))}
                    </div>
                    <button onClick={fetchAllData} style={{
                        padding: '8px 12px', background: '#fff', border: '1px solid #e2e8f0',
                        borderRadius: '8px', cursor: 'pointer', color: '#64748b',
                    }}>
                        <RefreshCw size={14} className={loading ? 'spin' : ''} />
                    </button>
                    <TenantSwitcher />
                </div>
            </div>

            {/* KPI Cards */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '16px', marginBottom: '24px' }}>
                {kpis.map((kpi) => (
                    <div key={kpi.label} style={{
                        background: '#fff', borderRadius: '12px', padding: '20px',
                        border: '1px solid #e2e8f0', boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
                    }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '14px' }}>
                            <div style={{ background: `${kpi.color}12`, padding: '8px', borderRadius: '8px' }}>
                                <kpi.icon size={18} color={kpi.color} />
                            </div>
                            {kpi.change && (
                                <div style={{
                                    display: 'flex', alignItems: 'center', gap: '3px',
                                    color: kpi.up ? '#10b981' : '#ef4444', fontSize: '12px', fontWeight: '600',
                                    background: kpi.up ? '#f0fdf4' : '#fef2f2', padding: '3px 8px', borderRadius: '6px',
                                }}>
                                    {kpi.up ? <ArrowUpRight size={12} /> : <ArrowDownRight size={12} />}
                                    {kpi.change}
                                </div>
                            )}
                        </div>
                        <div style={{ fontSize: '24px', fontWeight: '700', color: '#1e293b', letterSpacing: '-0.5px' }}>
                            {loading ? '—' : kpi.value}
                        </div>
                        <div style={{ fontSize: '13px', color: '#64748b', marginTop: '4px' }}>{kpi.label}</div>
                        <div style={{ fontSize: '11px', color: '#94a3b8', marginTop: '2px' }}>{kpi.sub}</div>
                    </div>
                ))}
            </div>

            {/* Charts Row */}
            <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '16px', marginBottom: '24px' }}>
                {/* Area Chart */}
                <div style={{ background: '#fff', borderRadius: '12px', padding: '24px', border: '1px solid #e2e8f0', boxShadow: '0 1px 3px rgba(0,0,0,0.04)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                        <div>
                            <h3 style={{ fontSize: '15px', fontWeight: '600', color: '#1e293b', margin: 0 }}>Transaction Volume Trend</h3>
                            <p style={{ fontSize: '12px', color: '#94a3b8', margin: '4px 0 0' }}>Daily volume over selected period</p>
                        </div>
                        <div style={{ display: 'flex', gap: '16px', fontSize: '11px', color: '#64748b' }}>
                            <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                                <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#3b82f6' }} /> Volume
                            </span>
                            <span style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                                <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#8b5cf6' }} /> MSF
                            </span>
                        </div>
                    </div>
                    <div style={{ height: '280px' }}>
                        {dailyData.length > 0 ? (
                            <ResponsiveContainer width="100%" height="100%">
                                <AreaChart data={dailyData}>
                                    <defs>
                                        <linearGradient id="volGrad" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.15} />
                                            <stop offset="100%" stopColor="#3b82f6" stopOpacity={0} />
                                        </linearGradient>
                                        <linearGradient id="msfGrad" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="0%" stopColor="#8b5cf6" stopOpacity={0.1} />
                                            <stop offset="100%" stopColor="#8b5cf6" stopOpacity={0} />
                                        </linearGradient>
                                    </defs>
                                    <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
                                    <XAxis dataKey="date" tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                                    <YAxis tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false}
                                        tickFormatter={(v) => v >= 1000 ? `${(v / 1000).toFixed(0)}K` : v} />
                                    <ReTooltip contentStyle={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: '8px', fontSize: '12px' }}
                                        formatter={(val) => [fmt.currency(val)]} />
                                    <Area type="monotone" dataKey="volume" stroke="#3b82f6" strokeWidth={2} fill="url(#volGrad)" />
                                    <Area type="monotone" dataKey="msf" stroke="#8b5cf6" strokeWidth={1.5} fill="url(#msfGrad)" />
                                </AreaChart>
                            </ResponsiveContainer>
                        ) : (
                            <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#94a3b8', fontSize: '13px' }}>
                                {loading ? 'Loading chart data...' : 'No transaction data available for this period. Upload data to see trends.'}
                            </div>
                        )}
                    </div>
                </div>

                {/* Pie Chart */}
                <div style={{ background: '#fff', borderRadius: '12px', padding: '24px', border: '1px solid #e2e8f0', boxShadow: '0 1px 3px rgba(0,0,0,0.04)' }}>
                    <h3 style={{ fontSize: '15px', fontWeight: '600', color: '#1e293b', margin: '0 0 4px' }}>Volume by Scheme</h3>
                    <p style={{ fontSize: '12px', color: '#94a3b8', margin: '0 0 16px' }}>Card scheme distribution</p>
                    {schemeData.length > 0 ? (
                        <>
                            <div style={{ height: '180px' }}>
                                <ResponsiveContainer width="100%" height="100%">
                                    <RePieChart>
                                        <Pie data={schemeData} cx="50%" cy="50%" innerRadius={50} outerRadius={80} dataKey="value" stroke="#fff" strokeWidth={2}>
                                            {schemeData.map((_, i) => (<Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />))}
                                        </Pie>
                                        <ReTooltip contentStyle={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: '8px', fontSize: '12px' }}
                                            formatter={(val) => [fmt.currency(val)]} />
                                    </RePieChart>
                                </ResponsiveContainer>
                            </div>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', marginTop: '8px' }}>
                                {schemeData.map((s, i) => (
                                    <div key={s.name} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                            <span style={{ width: 10, height: 10, borderRadius: '3px', background: CHART_COLORS[i % CHART_COLORS.length] }} />
                                            <span style={{ fontSize: '12px', color: '#64748b' }}>{s.name}</span>
                                        </div>
                                        <span style={{ fontSize: '12px', color: '#1e293b', fontWeight: '600' }}>
                                            {totalSchemeVol > 0 ? ((s.value / totalSchemeVol) * 100).toFixed(1) : 0}%
                                        </span>
                                    </div>
                                ))}
                            </div>
                        </>
                    ) : (
                        <div style={{ height: '200px', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#94a3b8', fontSize: '13px' }}>
                            {loading ? 'Loading...' : 'No scheme data available'}
                        </div>
                    )}
                </div>
            </div>

            {/* Bottom Row: Top Merchants (full width, no Quick Actions) */}
            <div style={{ background: '#fff', borderRadius: '12px', padding: '24px', border: '1px solid #e2e8f0', boxShadow: '0 1px 3px rgba(0,0,0,0.04)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                    <div>
                        <h3 style={{ fontSize: '15px', fontWeight: '600', color: '#1e293b', margin: 0 }}>Top Merchants by Volume</h3>
                        <p style={{ fontSize: '12px', color: '#94a3b8', margin: '4px 0 0' }}>Highest performing merchants this period</p>
                    </div>
                    <button onClick={() => navigate('/business/volume-revenue')} style={{
                        background: '#f8fafc', border: '1px solid #e2e8f0', color: '#3b82f6',
                        fontSize: '12px', padding: '6px 14px', borderRadius: '8px', cursor: 'pointer',
                        display: 'flex', alignItems: 'center', gap: '4px', fontWeight: '500',
                    }}>
                        View All <ArrowRight size={12} />
                    </button>
                </div>
                <div style={{ height: '280px' }}>
                    {topMerchants.length > 0 ? (
                        <ResponsiveContainer width="100%" height="100%">
                            <BarChart data={topMerchants} layout="vertical" margin={{ left: 10, right: 30 }}>
                                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" horizontal={false} />
                                <XAxis type="number" tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false}
                                    tickFormatter={(v) => fmt.currency(v)} />
                                <YAxis dataKey="name" type="category" width={120} tick={{ fontSize: 11, fill: '#64748b' }} axisLine={false} tickLine={false} />
                                <ReTooltip contentStyle={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: '8px', fontSize: '12px' }}
                                    formatter={(val) => [fmt.currency(val), 'Volume']} />
                                <Bar dataKey="volume" radius={[0, 6, 6, 0]} barSize={22}>
                                    {topMerchants.map((_, i) => (
                                        <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} fillOpacity={0.85} />
                                    ))}
                                </Bar>
                            </BarChart>
                        </ResponsiveContainer>
                    ) : (
                        <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#94a3b8', fontSize: '13px' }}>
                            {loading ? 'Loading...' : 'No merchant data available. Upload transaction data to see top merchants.'}
                        </div>
                    )}
                </div>
            </div>

            {/* Footer */}
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: '#94a3b8', marginTop: '16px', justifyContent: 'flex-end' }}>
                <Clock size={12} /> Last updated: {lastRefresh.toLocaleTimeString()}
            </div>

            <style>{`
                @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
                .spin { animation: spin 1s linear infinite; }
            `}</style>
        </div>
    );
};

export default Dashboard;
