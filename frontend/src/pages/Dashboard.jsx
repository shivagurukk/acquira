import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/axios';
import {
    Activity, DollarSign, AlertTriangle, Store, Target,
    RefreshCw, BarChart3, ArrowRight, Clock
} from 'lucide-react';
import {
    AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip as ReTooltip, ResponsiveContainer, PieChart as RePieChart,
    Pie, Cell
} from 'recharts';
import KpiCards from '../components/KpiCards';
import EmptyState from '../components/EmptyState';
import SkeletonLoader from '../components/SkeletonLoader';
import { useAuth } from '../contexts/AuthContext';
import { createFmt } from '../utils/formatters';
import { compactAxisFormatter } from '../utils/chartConfig';

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
                const res = await api.get('/business/data-bounds');
                if (res.data?.latest) {
                    setLatestDataDate(new Date(res.data.latest));
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

            // PERF: these four dashboard queries are independent of each other.
            // Fire them concurrently instead of awaiting each in series — dashboard
            // load time drops to roughly the slowest single call instead of the sum.
            const metricsP = fetch('/api/business/executive-metrics', { method: 'POST', headers, body })
                .then(r => r.ok ? r.json() : null)
                .then(d => { if (d) setMetrics(d); })
                .catch(e => console.warn('Metrics fetch failed', e));

            const dailyP = fetch('/api/business/performance-dashboard?groupBy=DAY', { method: 'POST', headers, body })
                .then(r => r.ok ? r.json() : null)
                .then(trendData => {
                    if (trendData?.length > 0) {
                        setDailyData(trendData.filter(r => r.row_label).sort((a, b) => a.row_label.localeCompare(b.row_label)).slice(-30).map(r => ({
                            date: fmt.date(r.row_label), volume: Number(r.total_vol || 0),
                            txns: Number((r.dom_debit_cnt || 0) + (r.dom_credit_cnt || 0) + (r.int_cnt || 0)), msf: Number(r.total_msf || 0),
                        })));
                    }
                })
                .catch(e => console.warn('Daily trend fetch failed', e));

            const merchantP = fetch('/api/business/performance-dashboard?groupBy=MERCHANT', { method: 'POST', headers, body })
                .then(r => r.ok ? r.json() : null)
                .then(merchData => {
                    if (merchData?.length > 0) {
                        setTopMerchants(merchData.map(r => ({
                            name: r.merchant_name || r.row_label || 'Unknown', volume: Number(r.total_vol || 0),
                            txns: Number((r.dom_debit_cnt || 0) + (r.dom_credit_cnt || 0) + (r.int_cnt || 0)), msf: Number(r.total_msf || 0),
                        })).sort((a, b) => b.volume - a.volume).slice(0, 8));
                    }
                })
                .catch(e => console.warn('Top merchants fetch failed', e));

            const schemeP = fetch('/api/analytics/scheme-breakdown', { method: 'POST', headers, body })
                .then(r => r.ok ? r.json() : null)
                .then(schData => {
                    if (schData?.length > 0) {
                        setSchemeData(schData.filter(r => r.card_scheme && Number(r.total_volume || 0) > 0).map(r => ({
                            name: r.card_scheme, value: Number(r.total_volume || 0), count: Number(r.total_txns || 0),
                        })).sort((a, b) => b.value - a.value).slice(0, 6));
                    }
                })
                .catch(e => console.warn('Scheme breakdown fetch failed', e));

            await Promise.all([metricsP, dailyP, merchantP, schemeP]);

            setLastRefresh(new Date());
        } catch (error) { console.error("Failed to fetch dashboard data", error); }
        finally { setLoading(false); }
    };

    useEffect(() => {
        // Wait for bounds before issuing the first fetch — otherwise the very first
        // load uses today as end-date and renders empty before refetching when bounds resolve.
        if (boundsLoaded) fetchAllData();
    }, [period, boundsLoaded]);

    // KPI cards now use the shared <KpiCards> component for visual consistency
    // with the rest of the app. `trend` is the raw growth %; `invertTrend` flips
    // the good/bad colour for metrics where "down is good" (leakage). `drillDown`
    // is a navigation handler. Sparklines reuse the daily series we already fetch.
    const kpis = useMemo(() => {
        if (!metrics) return [];
        const volSpark = dailyData.length ? dailyData.map(d => d.volume) : undefined;
        const txnSpark = dailyData.length ? dailyData.map(d => d.txns) : undefined;
        return [
            { title: 'Total Volume', value: fmt.currency(metrics.totalVolume), trend: metrics.volumeGrowth, icon: DollarSign, color: '#10b981', sparkData: volSpark, drillDown: () => navigate('/business/volume-revenue') },
            { title: 'Active Merchants', value: fmt.number(metrics.activeMerchants), trend: metrics.merchantsGrowth, icon: Store, color: '#3b82f6', drillDown: () => navigate('/merchants') },
            { title: 'Transactions', value: fmt.number(metrics.totalTxns), trend: metrics.txnsGrowth, icon: Activity, color: '#8b5cf6', sparkData: txnSpark, drillDown: () => navigate('/business/performance') },
            { title: 'Avg Txn Value', value: metrics.totalTxns > 0 ? fmt.currency(metrics.totalVolume / metrics.totalTxns) : fmt.currency(0), icon: Target, color: '#06b6d4', drillDown: () => navigate('/business/daily-dashboard') },
            { title: 'Leakage Alerts', value: fmt.number(metrics.leakageCount), trend: metrics.leakageGrowth, invertTrend: true, icon: AlertTriangle, color: '#f97316', drillDown: () => navigate('/business/zero-transaction') },
        ];
    }, [metrics, dailyData, navigate, fmt]);

    const totalSchemeVol = useMemo(() => schemeData.reduce((s, d) => s + d.value, 0), [schemeData]);

    const periods = [{ label: '7D', val: '7' }, { label: '30D', val: '30' }, { label: '90D', val: '90' }, { label: 'YTD', val: '365' }];

    const tooltipStyle = {
        background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12,
        boxShadow: 'var(--shadow-hover)', fontSize: 12, padding: '12px 16px', color: 'var(--text)',
    };

    return (
        <div style={{ flex: 1, overflowY: 'auto', background: 'var(--bg)', minHeight: '100vh', fontFamily: "'Inter', -apple-system, sans-serif" }}>
            {/* ═══ Header ═══ */}
            <div style={{
                padding: '20px 28px', background: 'var(--bg-card)',
                borderBottom: '1px solid var(--border)',
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                position: 'sticky', top: 0, zIndex: 10, flexWrap: 'wrap', gap: 12,
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
                        <h1 style={{ margin: 0, fontSize: '1.25rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.03em' }}>
                            Executive Dashboard
                        </h1>
                        <p style={{ margin: '2px 0 0', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
                            Real-time financial performance and merchant health
                        </p>
                    </div>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <div role="group" aria-label="Time period" style={{
                        display: 'flex', background: 'var(--bg-subtle)', borderRadius: 10, padding: 3,
                        border: '1px solid var(--border)',
                    }}>
                        {periods.map(p => {
                            const active = period === p.val;
                            return (
                                <button key={p.val} onClick={() => setPeriod(p.val)}
                                    aria-pressed={active}
                                    style={{
                                        padding: '7px 16px', border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600,
                                        borderRadius: 8,
                                        background: active ? 'var(--bg-card)' : 'transparent',
                                        color: active ? 'var(--text)' : 'var(--text-secondary)',
                                        boxShadow: active ? '0 1px 3px rgba(0,0,0,0.08)' : 'none',
                                        transition: 'all 0.15s',
                                    }}>{p.label}</button>
                            );
                        })}
                    </div>
                    <button onClick={fetchAllData} aria-label="Refresh dashboard" title="Refresh" style={{
                        padding: 9, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10,
                        cursor: 'pointer', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center',
                        boxShadow: '0 1px 2px rgba(0,0,0,0.04)',
                    }}>
                        <RefreshCw size={15} className={loading ? 'spin' : ''} />
                    </button>
                </div>
            </div>

            <div style={{ padding: '24px 28px 40px' }}>

                {/* ═══ KPI Cards (shared component) ═══ */}
                <KpiCards cards={kpis} loading={loading && !metrics} />

                {/* ═══ Charts Row ═══ */}
                <div className="dash-charts-grid" style={{ display: 'grid', gap: 16, marginBottom: 20 }}>
                    {/* Area Chart */}
                    <div style={{
                        background: 'var(--bg-card)', borderRadius: 16, border: '1px solid var(--border)', padding: '22px 24px',
                        boxShadow: 'var(--shadow-card)',
                    }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                            <div>
                                <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em' }}>
                                    Transaction Volume Trend
                                </h3>
                                <p style={{ margin: '3px 0 0', fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                                    Daily volume over selected period
                                </p>
                            </div>
                            <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
                                {[{ label: 'Volume', color: '#3b82f6' }, { label: 'MSF', color: '#8b5cf6' }].map(l => (
                                    <div key={l.label} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                                        <span style={{ width: 8, height: 8, borderRadius: '50%', background: l.color }} />
                                        <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', fontWeight: 500 }}>{l.label}</span>
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
                                        <CartesianGrid strokeDasharray="3 6" stroke="var(--border-light)" vertical={false} />
                                        <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--text-muted)' }} />
                                        <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--text-muted)' }} tickFormatter={compactAxisFormatter} />
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
                        background: 'var(--bg-card)', borderRadius: 16, border: '1px solid var(--border)', padding: '22px 24px',
                        boxShadow: 'var(--shadow-card)',
                    }}>
                        <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em' }}>
                            Volume by Scheme
                        </h3>
                        <p style={{ margin: '3px 0 0 0', fontSize: '0.78rem', color: 'var(--text-muted)', marginBottom: 8 }}>
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
                                                <span style={{ fontSize: '0.82rem', color: 'var(--text-secondary)', fontWeight: 500 }}>{s.name}</span>
                                            </div>
                                            <span style={{ fontSize: '0.85rem', color: 'var(--text)', fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
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
                    background: 'var(--bg-card)', borderRadius: 16, border: '1px solid var(--border)', padding: '22px 24px',
                    boxShadow: 'var(--shadow-card)',
                }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                        <div>
                            <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em' }}>
                                Top Merchants by Volume
                            </h3>
                            <p style={{ margin: '3px 0 0', fontSize: '0.78rem', color: 'var(--text-muted)' }}>
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
                                    <CartesianGrid strokeDasharray="3 6" stroke="var(--border-light)" horizontal={false} vertical={true} />
                                    <XAxis type="number" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--text-muted)' }} tickFormatter={compactAxisFormatter} />
                                    <YAxis dataKey="name" type="category" width={140}
                                        tick={{ fontSize: 12, fill: 'var(--text-secondary)', fontWeight: 500 }}
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
                    fontSize: 12, color: 'var(--text-muted)',
                    marginTop: 20, justifyContent: 'flex-end',
                }}>
                    <Clock size={12} /> Last updated: {lastRefresh.toLocaleTimeString()}
                </div>
            </div>

            <style>{`
                @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
                .spin { animation: spin 1s linear infinite; }
                .dash-charts-grid { grid-template-columns: minmax(0, 5fr) minmax(0, 2fr); }
                @media (max-width: 1024px) {
                    .dash-charts-grid { grid-template-columns: 1fr; }
                }
            `}</style>
        </div>
    );
};

export default Dashboard;
