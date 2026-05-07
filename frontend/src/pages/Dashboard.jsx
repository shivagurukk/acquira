import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    Activity, Users, CreditCard, DollarSign, TrendingUp, TrendingDown,
    AlertTriangle, Store, Target, RefreshCw, ArrowUpRight, ArrowDownRight,
    BarChart3, ArrowRight, Clock, ExternalLink
} from 'lucide-react';
import {
    AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip as ReTooltip, ResponsiveContainer, PieChart as RePieChart,
    Pie, Cell
} from 'recharts';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import SkeletonLoader from '../components/SkeletonLoader';
import { useAuth } from '../contexts/AuthContext';
import { createFmt } from '../utils/formatters';
import { chartGridProps, chartAxisProps, compactAxisFormatter } from '../utils/chartConfig';

const PALETTE = ['#3b82f6', '#10b981', '#8b5cf6', '#f59e0b', '#06b6d4', '#ef4444'];

const Dashboard = () => {
    const navigate = useNavigate();
    const { currencySymbol } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
    const [loading, setLoading] = useState(true);
    const [period, setPeriod] = useState('30');
    const [metrics, setMetrics] = useState(null);
    const [dailyData, setDailyData] = useState([]);
    const [schemeData, setSchemeData] = useState([]);
    const [topMerchants, setTopMerchants] = useState([]);
    const [lastRefresh, setLastRefresh] = useState(new Date());
    // Latest date for which data exists; resolved from /api/business/data-bounds.
    // Used as the end-date of the rolling window so the dashboard isn't empty
    // when transaction data lags real time (e.g. it's May but data ends in April).
    const [latestDataDate, setLatestDataDate] = useState(null);
    const [boundsLoaded, setBoundsLoaded] = useState(false);

    useEffect(() => {
        const loadBounds = async () => {
            try {
                const token = localStorage.getItem('token');
                const tenantId = localStorage.getItem('defaultTenantId');
                const res = await fetch('/api/business/data-bounds', {
                    headers: { 'Authorization': `Bearer ${token}`, ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}) }
                });
                if (res.ok) {
                    const b = await res.json();
                    if (b?.latest) {
                        setLatestDataDate(new Date(b.latest));
                    }
                }
            } catch (e) { console.warn('data-bounds fetch failed; falling back to today', e); }
            setBoundsLoaded(true);
        };
        loadBounds();
    }, []);

    const fetchAllData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const headers = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId };
            // Anchor the rolling window on the latest date that actually has data.
            // Falls back to today if the bounds endpoint hasn't returned yet.
            const end = latestDataDate ? new Date(latestDataDate) : new Date();
            const start = new Date(end);
            start.setDate(end.getDate() - parseInt(period));
            // Local-date formatter — toISOString() shifts dates by one day in non-UTC timezones.
            const fmtLocal = (d) => {
                const yr = d.getFullYear();
                const mo = String(d.getMonth() + 1).padStart(2, '0');
                const dy = String(d.getDate()).padStart(2, '0');
                return `${yr}-${mo}-${dy}`;
            };
            const body = JSON.stringify({ startDate: fmtLocal(start), endDate: fmtLocal(end) });

            const metricsRes = await fetch('/api/business/executive-metrics', { method: 'POST', headers, body });
            if (metricsRes.ok) setMetrics(await metricsRes.json());

            try {
                const dailyTrendRes = await fetch('/api/business/performance-dashboard?groupBy=DAY', { method: 'POST', headers, body });
                if (dailyTrendRes.ok) {
                    const trendData = await dailyTrendRes.json();
                    if (trendData?.length > 0) {
                        setDailyData(trendData.filter(r => r.row_label).sort((a, b) => a.row_label.localeCompare(b.row_label)).slice(-30).map(r => ({
                            date: fmt.date(r.row_label), volume: Number(r.total_vol || 0),
                            txns: Number((r.dom_debit_cnt || 0) + (r.dom_credit_cnt || 0) + (r.int_cnt || 0)), msf: Number(r.total_msf || 0),
                        })));
                    }
                }
            } catch (e) { console.warn('Daily trend fetch failed', e); }

            try {
                const merchantRes = await fetch('/api/business/performance-dashboard?groupBy=MERCHANT', { method: 'POST', headers, body });
                if (merchantRes.ok) {
                    const merchData = await merchantRes.json();
                    if (merchData?.length > 0) {
                        setTopMerchants(merchData.map(r => ({
                            name: r.merchant_name || r.row_label || 'Unknown', volume: Number(r.total_vol || 0),
                            txns: Number((r.dom_debit_cnt || 0) + (r.dom_credit_cnt || 0) + (r.int_cnt || 0)), msf: Number(r.total_msf || 0),
                        })).sort((a, b) => b.volume - a.volume).slice(0, 8));
                    }
                }
            } catch (e) { console.warn('Top merchants fetch failed', e); }

            try {
                const schemeRes = await fetch('/api/analytics/scheme-breakdown', { method: 'POST', headers, body });
                if (schemeRes.ok) {
                    const schData = await schemeRes.json();
                    if (schData?.length > 0) {
                        setSchemeData(schData.filter(r => r.card_scheme && Number(r.total_volume || 0) > 0).map(r => ({
                            name: r.card_scheme, value: Number(r.total_volume || 0), count: Number(r.total_txns || 0),
                        })).sort((a, b) => b.value - a.value).slice(0, 6));
                    }
                }
            } catch (e) { console.warn('Scheme breakdown fetch failed', e); }

            setLastRefresh(new Date());
        } catch (error) { console.error("Failed to fetch dashboard data", error); }
        finally { setLoading(false); }
    };

    useEffect(() => {
        // Wait for bounds before issuing the first fetch — otherwise the very first
        // load uses today as end-date and renders empty before refetching when bounds resolve.
        if (boundsLoaded) fetchAllData();
    }, [period, boundsLoaded]);

    const kpis = useMemo(() => {
        if (!metrics) return [];
        return [
            { label: 'Total Volume', value: fmt.currency(metrics.totalVolume), change: fmt.growth(metrics.volumeGrowth), up: metrics.volumeGrowth >= 0, icon: DollarSign, bg: 'linear-gradient(135deg, #ecfdf5 0%, #d1fae5 100%)', iconBg: '#10b981', borderClr: '#a7f3d0', drillDown: '/business/volume-revenue' },
            { label: 'Active Merchants', value: fmt.number(metrics.activeMerchants), change: fmt.growth(metrics.merchantsGrowth), up: metrics.merchantsGrowth >= 0, icon: Store, bg: 'linear-gradient(135deg, #eff6ff 0%, #dbeafe 100%)', iconBg: '#3b82f6', borderClr: '#93c5fd', drillDown: '/merchants' },
            { label: 'Transactions', value: fmt.number(metrics.totalTxns), change: fmt.growth(metrics.txnsGrowth), up: metrics.txnsGrowth >= 0, icon: Activity, bg: 'linear-gradient(135deg, #f5f3ff 0%, #ede9fe 100%)', iconBg: '#8b5cf6', borderClr: '#c4b5fd', drillDown: '/business/performance' },
            { label: 'Avg Txn Value', value: metrics.totalTxns > 0 ? fmt.currency(metrics.totalVolume / metrics.totalTxns) : fmt.currency(0), change: '', up: true, icon: Target, bg: 'linear-gradient(135deg, #ecfeff 0%, #cffafe 100%)', iconBg: '#06b6d4', borderClr: '#67e8f9', drillDown: '/business/daily-dashboard' },
            { label: 'Leakage Alerts', value: fmt.number(metrics.leakageCount), change: fmt.growth(metrics.leakageGrowth), up: metrics.leakageGrowth <= 0, icon: AlertTriangle, bg: 'linear-gradient(135deg, #fff7ed 0%, #ffedd5 100%)', iconBg: '#f97316', borderClr: '#fdba74', drillDown: '/business/zero-transaction' },
        ];
    }, [metrics]);

    const totalSchemeVol = useMemo(() => schemeData.reduce((s, d) => s + d.value, 0), [schemeData]);

    const tooltipStyle = {
        background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12,
        boxShadow: '0 10px 40px rgba(0,0,0,0.1)', fontSize: 12, padding: '12px 16px',
    };

    return (
        <div style={{ flex: 1, overflowY: 'auto', background: '#f1f5f9', minHeight: '100vh', fontFamily: "'Inter', -apple-system, sans-serif" }}>
            {/* ═══ Header ═══ */}
            <div style={{
                padding: '20px 28px', background: '#fff',
                borderBottom: '1px solid #e2e8f0',
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                position: 'sticky', top: 0, zIndex: 10,
            }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                    <div style={{
                        width: 42, height: 42, borderRadius: 12,
                        background: 'linear-gradient(135deg, #3b82f6, #8b5cf6)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        boxShadow: '0 4px 12px rgba(59,130,246,0.3)',
                    }}>
                        <BarChart3 size={20} color="#fff" strokeWidth={2} />
                    </div>
                    <div>
                        <h1 style={{ margin: 0, fontSize: '1.25rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.03em' }}>
                            Executive Dashboard
                        </h1>
                        <p style={{ margin: '2px 0 0', fontSize: '0.82rem', color: '#94a3b8' }}>
                            Real-time financial performance and merchant health
                        </p>
                    </div>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <div style={{
                        display: 'flex', background: '#f1f5f9', borderRadius: 10, padding: 3,
                        border: '1px solid #e2e8f0',
                    }}>
                        {[{ label: '7D', val: '7' }, { label: '30D', val: '30' }, { label: '90D', val: '90' }, { label: 'YTD', val: '365' }].map(p => (
                            <button key={p.val} onClick={() => setPeriod(p.val)} style={{
                                padding: '7px 16px', border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600,
                                borderRadius: 8,
                                background: period === p.val ? '#fff' : 'transparent',
                                color: period === p.val ? '#0f172a' : '#64748b',
                                boxShadow: period === p.val ? '0 1px 3px rgba(0,0,0,0.08)' : 'none',
                                transition: 'all 0.15s',
                            }}>{p.label}</button>
                        ))}
                    </div>
                    <button onClick={fetchAllData} style={{
                        padding: 9, background: '#fff', border: '1px solid #e2e8f0', borderRadius: 10,
                        cursor: 'pointer', color: '#64748b', display: 'flex', alignItems: 'center',
                        boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
                    }}>
                        <RefreshCw size={15} className={loading ? 'spin' : ''} />
                    </button>
                </div>
            </div>

            <div style={{ padding: '24px 28px 40px' }}>

                {/* ═══ KPI Cards — Gradient Backgrounds ═══ */}
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 16, marginBottom: 24 }}>
                    {loading && !metrics ? (
                        Array.from({ length: 5 }).map((_, i) => (
                            <div key={i} style={{
                                background: '#fff', borderRadius: 16, padding: 22,
                                border: '1px solid #e2e8f0', height: 140,
                            }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 14 }}>
                                    <div style={{ width: 42, height: 42, borderRadius: 12, background: '#f1f5f9' }} />
                                    <div style={{ width: 52, height: 24, borderRadius: 8, background: '#f1f5f9' }} />
                                </div>
                                <div style={{ width: '60%', height: 26, borderRadius: 6, background: '#f1f5f9', marginBottom: 8 }} />
                                <div style={{ width: '40%', height: 14, borderRadius: 4, background: '#f1f5f9' }} />
                            </div>
                        ))
                    ) : (
                        kpis.map((kpi, i) => (
                            <div key={kpi.label} onClick={() => kpi.drillDown && navigate(kpi.drillDown)}
                                style={{
                                    background: kpi.bg,
                                    borderRadius: 16, padding: 22,
                                    border: `1px solid ${kpi.borderClr}`,
                                    cursor: kpi.drillDown ? 'pointer' : 'default',
                                    transition: 'all 0.25s ease',
                                    position: 'relative', overflow: 'hidden',
                                }}
                                onMouseEnter={e => {
                                    if (kpi.drillDown) {
                                        e.currentTarget.style.transform = 'translateY(-4px)';
                                        e.currentTarget.style.boxShadow = `0 12px 32px ${kpi.iconBg}20`;
                                    }
                                }}
                                onMouseLeave={e => {
                                    e.currentTarget.style.transform = 'none';
                                    e.currentTarget.style.boxShadow = 'none';
                                }}
                            >
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 14 }}>
                                    <div style={{
                                        width: 42, height: 42, borderRadius: 12,
                                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                                        background: kpi.iconBg,
                                        boxShadow: `0 4px 12px ${kpi.iconBg}40`,
                                    }}>
                                        <kpi.icon size={20} color="#fff" strokeWidth={2} />
                                    </div>
                                    {kpi.change && (
                                        <div style={{
                                            display: 'flex', alignItems: 'center', gap: 3,
                                            color: kpi.up ? '#059669' : '#dc2626',
                                            fontSize: 12, fontWeight: 700,
                                            background: kpi.up ? 'rgba(255,255,255,0.8)' : 'rgba(255,255,255,0.8)',
                                            padding: '4px 10px', borderRadius: 20,
                                            backdropFilter: 'blur(4px)',
                                        }}>
                                            {kpi.up ? <ArrowUpRight size={13} /> : <ArrowDownRight size={13} />}
                                            {kpi.change}
                                        </div>
                                    )}
                                </div>
                                <div style={{
                                    fontSize: '1.65rem', fontWeight: 800,
                                    color: '#0f172a',
                                    letterSpacing: '-0.04em', lineHeight: 1,
                                    marginBottom: 6,
                                }}>
                                    {kpi.value}
                                </div>
                                <div style={{
                                    fontSize: '0.82rem', fontWeight: 500,
                                    color: '#475569',
                                    display: 'flex', alignItems: 'center', gap: 5,
                                }}>
                                    {kpi.label}
                                    {kpi.drillDown && <ArrowRight size={13} color={kpi.iconBg} />}
                                </div>
                            </div>
                        ))
                    )}
                </div>

                {/* ═══ Charts Row ═══ */}
                <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 5fr) minmax(0, 2fr)', gap: 16, marginBottom: 20 }}>
                    {/* Area Chart */}
                    <div style={{
                        background: '#fff', borderRadius: 16, border: '1px solid #e2e8f0', padding: '22px 24px',
                        boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
                    }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                            <div>
                                <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' }}>
                                    Transaction Volume Trend
                                </h3>
                                <p style={{ margin: '3px 0 0', fontSize: '0.78rem', color: '#94a3b8' }}>
                                    Daily volume over selected period
                                </p>
                            </div>
                            <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
                                {[{ label: 'Volume', color: '#3b82f6' }, { label: 'MSF', color: '#8b5cf6' }].map(l => (
                                    <div key={l.label} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                                        <span style={{ width: 8, height: 8, borderRadius: '50%', background: l.color }} />
                                        <span style={{ fontSize: '0.72rem', color: '#94a3b8', fontWeight: 500 }}>{l.label}</span>
                                    </div>
                                ))}
                            </div>
                        </div>
                        <div style={{ height: 300 }}>
                            {dailyData.length > 0 ? (
                                <ResponsiveContainer width="100%" height="100%">
                                    <AreaChart data={dailyData}>
                                        <defs>
                                            <linearGradient id="volGrad" x1="0" y1="0" x2="0" y2="1">
                                                <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.15} />
                                                <stop offset="100%" stopColor="#3b82f6" stopOpacity={0.01} />
                                            </linearGradient>
                                            <linearGradient id="msfGrad" x1="0" y1="0" x2="0" y2="1">
                                                <stop offset="0%" stopColor="#8b5cf6" stopOpacity={0.1} />
                                                <stop offset="100%" stopColor="#8b5cf6" stopOpacity={0.01} />
                                            </linearGradient>
                                        </defs>
                                        <CartesianGrid strokeDasharray="3 6" stroke="#f1f5f9" vertical={false} />
                                        <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: '#94a3b8' }} />
                                        <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: '#94a3b8' }} tickFormatter={compactAxisFormatter} />
                                        <ReTooltip contentStyle={tooltipStyle} formatter={(val) => [fmt.currency(val)]} />
                                        <Area type="monotone" dataKey="volume" stroke="#3b82f6" strokeWidth={2.5} fill="url(#volGrad)" dot={false} activeDot={{ r: 5, fill: '#3b82f6', stroke: '#fff', strokeWidth: 2 }} />
                                        <Area type="monotone" dataKey="msf" stroke="#8b5cf6" strokeWidth={1.5} fill="url(#msfGrad)" dot={false} activeDot={{ r: 4, fill: '#8b5cf6', stroke: '#fff', strokeWidth: 2 }} />
                                    </AreaChart>
                                </ResponsiveContainer>
                            ) : (
                                loading ? <SkeletonLoader variant="chart" height={300} />
                                    : <EmptyState variant="chart" compact action={{ label: 'Upload Data', to: '/upload' }} />
                            )}
                        </div>
                    </div>

                    {/* Pie Chart */}
                    <div style={{
                        background: '#fff', borderRadius: 16, border: '1px solid #e2e8f0', padding: '22px 24px',
                        boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
                    }}>
                        <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' }}>
                            Volume by Scheme
                        </h3>
                        <p style={{ margin: '3px 0 0 0', fontSize: '0.78rem', color: '#94a3b8', marginBottom: 8 }}>
                            Card scheme distribution
                        </p>
                        {schemeData.length > 0 ? (
                            <>
                                <div style={{ height: 200 }}>
                                    <ResponsiveContainer width="100%" height="100%">
                                        <RePieChart>
                                            <Pie data={schemeData} cx="50%" cy="50%" innerRadius={55} outerRadius={85} dataKey="value"
                                                stroke="#fff" strokeWidth={3} paddingAngle={2}>
                                                {schemeData.map((_, i) => (<Cell key={i} fill={PALETTE[i % PALETTE.length]} />))}
                                            </Pie>
                                            <ReTooltip contentStyle={tooltipStyle} formatter={(val) => [fmt.currency(val)]} />
                                        </RePieChart>
                                    </ResponsiveContainer>
                                </div>
                                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 4 }}>
                                    {schemeData.map((s, i) => (
                                        <div key={s.name} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                                <span style={{
                                                    width: 10, height: 10, borderRadius: 3,
                                                    background: PALETTE[i % PALETTE.length], flexShrink: 0,
                                                    boxShadow: `0 2px 4px ${PALETTE[i % PALETTE.length]}30`,
                                                }} />
                                                <span style={{ fontSize: '0.82rem', color: '#64748b', fontWeight: 500 }}>{s.name}</span>
                                            </div>
                                            <span style={{ fontSize: '0.85rem', color: '#0f172a', fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                                                {totalSchemeVol > 0 ? ((s.value / totalSchemeVol) * 100).toFixed(1) : 0}%
                                            </span>
                                        </div>
                                    ))}
                                </div>
                            </>
                        ) : (
                            loading ? <SkeletonLoader variant="chart" height={200} />
                                : <EmptyState variant="chart" compact title="No scheme data" message="Card scheme data will appear after processing." />
                        )}
                    </div>
                </div>

                {/* ═══ Top Merchants ═══ */}
                <div style={{
                    background: '#fff', borderRadius: 16, border: '1px solid #e2e8f0', padding: '22px 24px',
                    boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
                }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                        <div>
                            <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' }}>
                                Top Merchants by Volume
                            </h3>
                            <p style={{ margin: '3px 0 0', fontSize: '0.78rem', color: '#94a3b8' }}>
                                Highest performing merchants this period
                            </p>
                        </div>
                        <button onClick={() => navigate('/business/volume-revenue')} style={{
                            background: 'linear-gradient(135deg, #3b82f6, #6366f1)',
                            border: 'none', color: '#fff',
                            fontSize: 12, padding: '8px 18px', borderRadius: 10, cursor: 'pointer',
                            display: 'flex', alignItems: 'center', gap: 5, fontWeight: 600, flexShrink: 0,
                            boxShadow: '0 4px 12px rgba(59,130,246,0.3)',
                            transition: 'all 0.15s',
                        }}
                        onMouseEnter={e => { e.currentTarget.style.transform = 'translateY(-1px)'; e.currentTarget.style.boxShadow = '0 6px 20px rgba(59,130,246,0.35)'; }}
                        onMouseLeave={e => { e.currentTarget.style.transform = 'none'; e.currentTarget.style.boxShadow = '0 4px 12px rgba(59,130,246,0.3)'; }}
                        >
                            View all <ArrowRight size={13} />
                        </button>
                    </div>
                    <div style={{ height: 300 }}>
                        {topMerchants.length > 0 ? (
                            <ResponsiveContainer width="100%" height="100%">
                                <BarChart data={topMerchants} layout="vertical" margin={{ left: 10, right: 30 }}>
                                    <CartesianGrid strokeDasharray="3 6" stroke="#f1f5f9" horizontal={false} vertical={true} />
                                    <XAxis type="number" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: '#94a3b8' }} tickFormatter={compactAxisFormatter} />
                                    <YAxis dataKey="name" type="category" width={140}
                                        tick={{ fontSize: 12, fill: '#475569', fontWeight: 500 }}
                                        axisLine={false} tickLine={false} />
                                    <ReTooltip contentStyle={tooltipStyle} formatter={(val) => [fmt.currency(val), 'Volume']} />
                                    <Bar dataKey="volume" radius={[0, 8, 8, 0]} barSize={24}>
                                        {topMerchants.map((_, i) => (
                                            <Cell key={i} fill={PALETTE[i % PALETTE.length]} fillOpacity={0.85} />
                                        ))}
                                    </Bar>
                                </BarChart>
                            </ResponsiveContainer>
                        ) : (
                            loading ? <SkeletonLoader variant="chart" height={280} />
                                : <EmptyState variant="merchant" compact action={{ label: 'Upload Data', to: '/upload' }} />
                        )}
                    </div>
                </div>

                {/* Footer */}
                <div style={{
                    display: 'flex', alignItems: 'center', gap: 6,
                    fontSize: 12, color: '#94a3b8',
                    marginTop: 20, justifyContent: 'flex-end',
                }}>
                    <Clock size={12} /> Last updated: {lastRefresh.toLocaleTimeString()}
                </div>
            </div>

            <style>{`
                @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
                .spin { animation: spin 1s linear infinite; }
                @media (max-width: 1024px) {
                    div[style*="5fr"] { grid-template-columns: 1fr !important; }
                }
            `}</style>
        </div>
    );
};

export default Dashboard;
