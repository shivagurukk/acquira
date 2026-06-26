import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/axios';
import {
    Activity, DollarSign, AlertTriangle, Store, Target,
    RefreshCw, BarChart3, ArrowRight, Clock, Percent, TrendingUp, Layers, Crown
} from 'lucide-react';
import {
    AreaChart, Area, XAxis, YAxis, CartesianGrid,
    Tooltip as ReTooltip, ResponsiveContainer, PieChart as RePieChart,
    Pie, Cell, ReferenceLine
} from 'recharts';
import EmptyState from '../components/EmptyState';
import SkeletonLoader from '../components/SkeletonLoader';
import { useAuth } from '../contexts/AuthContext';
import { createFmt } from '../utils/formatters';
import { compactAxisFormatter } from '../utils/chartConfig';

const PALETTE = ['#3b82f6', '#10b981', '#8b5cf6', '#f59e0b', '#06b6d4', '#ef4444'];

const REDUCED_MOTION = typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches : false;

/* ─── Animated number that eases from its last value to the new target ─── */
const CountUp = ({ to = 0, format = (n) => n, duration = 850 }) => {
    const [val, setVal] = useState(REDUCED_MOTION ? to : 0);
    const ref = useRef({ raf: 0, from: REDUCED_MOTION ? to : 0 });
    useEffect(() => {
        if (REDUCED_MOTION) { setVal(to); ref.current.from = to; return; }
        const from = ref.current.from || 0;
        const start = performance.now();
        const tick = (now) => {
            const t = Math.min((now - start) / duration, 1);
            const eased = 1 - Math.pow(1 - t, 3);
            const cur = from + (to - from) * eased;
            setVal(cur);
            if (t < 1) ref.current.raf = requestAnimationFrame(tick);
            else ref.current.from = to;
        };
        cancelAnimationFrame(ref.current.raf);
        ref.current.raf = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(ref.current.raf);
    }, [to, duration]);
    return <>{format(val)}</>;
};

/* ─── Inline full-width sparkline used as a soft KPI backdrop ─── */
const KpiSpark = ({ data = [], color = '#3b82f6' }) => {
    if (!data || data.length < 2) return null;
    const W = 100, H = 40;
    const max = Math.max(...data), min = Math.min(...data);
    const range = max - min || 1;
    const pts = data.map((v, i) => {
        const x = (i / (data.length - 1)) * W;
        const y = H - ((v - min) / range) * (H - 6) - 3;
        return `${x},${y}`;
    });
    const id = `kpg-${color.slice(1)}`;
    return (
        <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none"
            width="100%" height="100%" style={{ display: 'block' }}>
            <defs>
                <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity="0.18" />
                    <stop offset="100%" stopColor={color} stopOpacity="0" />
                </linearGradient>
            </defs>
            <polygon points={`0,${H} ${pts.join(' ')} ${W},${H}`} fill={`url(#${id})`} />
            <polyline points={pts.join(' ')} fill="none" stroke={color}
                strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
        </svg>
    );
};

/* ─── Enhanced KPI card (page-local; same data shape as before) ─── */
const EnhancedKpi = ({ title, value, rawValue, format, subtitle, trend, invertTrend, icon: Icon, color, sparkData, onClick }) => {
    const rose = Number(trend) > 0;
    const isPositive = invertTrend ? !rose : rose;
    const isNeutral = !trend || Number(trend) === 0;
    const trendColor = isPositive ? '#059669' : '#dc2626';
    const trendBg = isPositive ? 'rgba(5,150,105,0.10)' : 'rgba(220,38,38,0.10)';
    const clickable = typeof onClick === 'function';
    const hasRaw = rawValue !== undefined && rawValue !== null && Number.isFinite(Number(rawValue));

    return (
        <div
            className="dash-kpi"
            onClick={onClick}
            role={clickable ? 'button' : undefined}
            tabIndex={clickable ? 0 : undefined}
            onKeyDown={e => { if (clickable && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onClick(); } }}
            style={{
                position: 'relative', overflow: 'hidden',
                background: 'var(--bg-card)', border: '1px solid var(--border)',
                borderRadius: 18, padding: '20px 22px 18px',
                cursor: clickable ? 'pointer' : 'default',
                '--kpi-accent': color,
            }}
        >
            {/* top accent bar */}
            <span style={{
                position: 'absolute', top: 0, left: 0, right: 0, height: 3,
                background: `linear-gradient(90deg, ${color}, ${color}55)`,
            }} />

            {/* sparkline backdrop */}
            {sparkData?.length > 1 && (
                <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: 46, opacity: 0.55, pointerEvents: 'none' }}>
                    <KpiSpark data={sparkData} color={color} />
                </div>
            )}

            <div style={{ position: 'relative', zIndex: 1 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 14 }}>
                    <div style={{
                        width: 42, height: 42, borderRadius: 12,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        background: `${color}14`, border: `1px solid ${color}26`, color,
                    }}>
                        <Icon size={19} strokeWidth={2} />
                    </div>
                    {!isNeutral && (
                        <span style={{
                            display: 'inline-flex', alignItems: 'center', gap: 3,
                            padding: '3px 9px', borderRadius: 8, fontSize: 11, fontWeight: 700,
                            background: trendBg, color: trendColor,
                        }}>
                            {rose ? '▲' : '▼'} {Math.abs(Number(trend)).toFixed(1)}%
                        </span>
                    )}
                </div>

                <div style={{ fontSize: '1.65rem', fontWeight: 800, letterSpacing: '-0.035em', lineHeight: 1, color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>
                    {hasRaw ? <CountUp to={Number(rawValue)} format={format} /> : (value ?? '—')}
                </div>

                <div style={{ marginTop: 8, fontSize: '0.8rem', fontWeight: 600, color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: 5 }}>
                    {title}
                    {clickable && <ArrowRight size={12} className="dash-kpi-arrow" color={color} />}
                </div>
                {subtitle && <div style={{ marginTop: 3, fontSize: '0.72rem', color: 'var(--text-muted)' }}>{subtitle}</div>}
            </div>
        </div>
    );
};

/* ─── At-a-glance micro stat ─── */
const Glance = ({ icon: Icon, label, value, color }) => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '6px 0', minWidth: 0 }}>
        <div style={{
            width: 36, height: 36, borderRadius: 10, flexShrink: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            background: `${color}14`, color,
        }}>
            <Icon size={17} strokeWidth={2} />
        </div>
        <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{value}</div>
            <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', fontWeight: 500 }}>{label}</div>
        </div>
    </div>
);

/* ─── Initials + deterministic color for merchant avatars ─── */
const initials = (name = '') => {
    const parts = name.trim().split(/\s+/).filter(Boolean);
    if (!parts.length) return '?';
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
};

/* ─── relative "Ns ago" formatter ─── */
const relTime = (ms) => {
    const s = Math.max(0, Math.floor(ms / 1000));
    if (s < 5) return 'just now';
    if (s < 60) return `${s}s ago`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m ago`;
    return `${Math.floor(m / 60)}h ago`;
};

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
    const [latestDataDate, setLatestDataDate] = useState(null);
    const [boundsLoaded, setBoundsLoaded] = useState(false);
    const [activeScheme, setActiveScheme] = useState(null);   // donut hover index
    const [showAvg, setShowAvg] = useState(true);             // trend avg reference line
    const [nowTick, setNowTick] = useState(Date.now());       // drives "Ns ago"

    useEffect(() => {
        const loadBounds = async () => {
            try {
                const res = await api.get('/business/data-bounds');
                if (res.data?.latest) setLatestDataDate(new Date(res.data.latest));
            } catch (e) { console.warn('data-bounds fetch failed; falling back to today', e); }
            setBoundsLoaded(true);
        };
        loadBounds();
    }, []);

    // tick the relative-time label every 15s
    useEffect(() => {
        const id = setInterval(() => setNowTick(Date.now()), 15000);
        return () => clearInterval(id);
    }, []);

    const fetchAllData = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            const headers = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId };
            const end = latestDataDate ? new Date(latestDataDate) : new Date();
            const start = new Date(end);
            start.setDate(end.getDate() - parseInt(period));
            const fmtLocal = (d) => {
                const yr = d.getFullYear();
                const mo = String(d.getMonth() + 1).padStart(2, '0');
                const dy = String(d.getDate()).padStart(2, '0');
                return `${yr}-${mo}-${dy}`;
            };
            const body = JSON.stringify({ startDate: fmtLocal(start), endDate: fmtLocal(end) });

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

    useEffect(() => { if (boundsLoaded) fetchAllData(); }, [period, boundsLoaded]);

    const kpis = useMemo(() => {
        if (!metrics) return [];
        const volSpark = dailyData.length ? dailyData.map(d => d.volume) : undefined;
        const txnSpark = dailyData.length ? dailyData.map(d => d.txns) : undefined;
        const msfSpark = dailyData.length ? dailyData.map(d => d.msf) : undefined;
        const num = (n) => fmt.number(Math.round(n));
        const avgTxn = metrics.totalTxns > 0 ? metrics.totalVolume / metrics.totalTxns : 0;
        return [
            { title: 'Total Volume', rawValue: metrics.totalVolume, format: fmt.currency, trend: metrics.volumeGrowth, icon: DollarSign, color: '#10b981', sparkData: volSpark, drillDown: () => navigate('/business/volume-revenue') },
            { title: 'Active Merchants', rawValue: metrics.activeMerchants, format: num, trend: metrics.merchantsGrowth, icon: Store, color: '#3b82f6', drillDown: () => navigate('/merchants') },
            { title: 'Transactions', rawValue: metrics.totalTxns, format: num, trend: metrics.txnsGrowth, icon: Activity, color: '#8b5cf6', sparkData: txnSpark, drillDown: () => navigate('/business/performance') },
            { title: 'Avg Txn Value', rawValue: avgTxn, format: fmt.currency, icon: Target, color: '#06b6d4', sparkData: msfSpark, drillDown: () => navigate('/business/daily-dashboard') },
            { title: 'Leakage Alerts', rawValue: metrics.leakageCount, format: num, trend: metrics.leakageGrowth, invertTrend: true, icon: AlertTriangle, color: '#f97316', drillDown: () => navigate('/business/zero-transaction') },
        ];
    }, [metrics, dailyData, navigate, fmt]);

    const totalSchemeVol = useMemo(() => schemeData.reduce((s, d) => s + d.value, 0), [schemeData]);
    const maxMerchantVol = useMemo(() => (topMerchants[0]?.volume || 1), [topMerchants]);
    const avgVolume = useMemo(() => (dailyData.length ? dailyData.reduce((s, d) => s + d.volume, 0) / dailyData.length : 0), [dailyData]);

    // ── Derived "at a glance" context (all from data already fetched) ──
    const glance = useMemo(() => {
        const totalMsf = dailyData.reduce((s, d) => s + (d.msf || 0), 0);
        const feeRate = metrics?.totalVolume > 0 ? (totalMsf / metrics.totalVolume) * 100 : 0;
        const peak = dailyData.reduce((best, d) => (d.volume > (best?.volume || 0) ? d : best), null);
        const leadScheme = schemeData[0];
        return { feeRate, peak, leadScheme, schemes: schemeData.length };
    }, [dailyData, schemeData, metrics]);

    const periods = [{ label: '7D', val: '7' }, { label: '30D', val: '30' }, { label: '90D', val: '90' }, { label: 'YTD', val: '365' }];

    const tooltipStyle = {
        background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12,
        boxShadow: 'var(--shadow-hover)', fontSize: 12, padding: '12px 16px', color: 'var(--text)',
    };

    const dataDateLabel = latestDataDate ? fmt.date(latestDataDate.toISOString().slice(0, 10)) : null;

    // donut center reflects the hovered slice, else the total
    const centerScheme = activeScheme != null ? schemeData[activeScheme] : null;

    return (
        <div style={{ flex: 1, overflowY: 'auto', background: 'var(--bg)', minHeight: '100vh', fontFamily: "'Inter', -apple-system, sans-serif" }}>
            {/* ═══ Header ═══ */}
            <div style={{
                position: 'sticky', top: 0, zIndex: 10,
                background: 'var(--bg-card)', borderBottom: '1px solid var(--border)',
                backgroundImage: 'radial-gradient(900px 120px at 0% -40%, rgba(59,130,246,0.10), transparent 70%)',
            }}>
                <div style={{
                    padding: '18px 28px', display: 'flex', justifyContent: 'space-between',
                    alignItems: 'center', flexWrap: 'wrap', gap: 12,
                }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                        <div style={{
                            width: 44, height: 44, borderRadius: 13,
                            background: 'linear-gradient(135deg, #3b82f6, #8b5cf6)',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            boxShadow: '0 6px 16px rgba(99,102,241,0.35)',
                        }}>
                            <BarChart3 size={21} color="#fff" strokeWidth={2} />
                        </div>
                        <div>
                            <h1 style={{ margin: 0, fontSize: '1.28rem', fontWeight: 800, color: 'var(--text)', letterSpacing: '-0.035em', display: 'flex', alignItems: 'center', gap: 9 }}>
                                Executive Dashboard
                                <span className="dash-live" title="Live data">
                                    <span className="dash-live-dot" /> LIVE
                                </span>
                            </h1>
                            <p style={{ margin: '3px 0 0', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
                                Real-time financial performance and merchant health
                                {dataDateLabel && <> · <span style={{ fontWeight: 600, color: 'var(--text-secondary)' }}>data thru {dataDateLabel}</span></>}
                            </p>
                        </div>
                    </div>
                    <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, color: 'var(--text-muted)' }} title={lastRefresh.toLocaleString()}>
                            <Clock size={12} /> updated {relTime(nowTick - lastRefresh.getTime())}
                        </span>
                        <div role="group" aria-label="Time period" style={{
                            display: 'flex', background: 'var(--bg-subtle)', borderRadius: 10, padding: 3, border: '1px solid var(--border)',
                        }}>
                            {periods.map(p => {
                                const active = period === p.val;
                                return (
                                    <button key={p.val} onClick={() => setPeriod(p.val)} aria-pressed={active}
                                        style={{
                                            padding: '7px 16px', border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 700,
                                            borderRadius: 8,
                                            background: active ? 'linear-gradient(135deg, #3b82f6, #6366f1)' : 'transparent',
                                            color: active ? '#fff' : 'var(--text-secondary)',
                                            boxShadow: active ? '0 2px 8px rgba(59,130,246,0.30)' : 'none',
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
            </div>

            <div style={{ padding: '24px 28px 40px', position: 'relative' }}>
                {/* faint hero glow behind the KPI strip */}
                <div aria-hidden style={{
                    position: 'absolute', top: 0, left: 0, right: 0, height: 220, pointerEvents: 'none',
                    background: 'radial-gradient(680px 200px at 18% 0%, rgba(99,102,241,0.07), transparent 70%)',
                }} />

                {/* ═══ KPI Cards ═══ */}
                {loading && !metrics ? (
                    <SkeletonLoader variant="kpi-row" count={5} />
                ) : (
                    <div className="dash-kpi-grid">
                        {kpis.map((c, i) => (
                            <div key={c.title} style={{ animation: 'dashIn 0.4s ease both', animationDelay: `${i * 55}ms` }}>
                                <EnhancedKpi {...c} onClick={c.drillDown} />
                            </div>
                        ))}
                    </div>
                )}

                {/* ═══ At a glance ═══ */}
                {metrics && (
                    <div style={{
                        background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16,
                        padding: '12px 24px', marginBottom: 20, boxShadow: 'var(--shadow-card)',
                        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: 8,
                    }}>
                        <Glance icon={Percent} color="#10b981" label="Effective MSF rate"
                            value={`${glance.feeRate.toFixed(2)}%`} />
                        <Glance icon={TrendingUp} color="#3b82f6" label={glance.peak ? `Peak day · ${glance.peak.date}` : 'Peak day'}
                            value={glance.peak ? fmt.currency(glance.peak.volume) : '—'} />
                        <Glance icon={Crown} color="#f59e0b" label={glance.leadScheme ? `Top scheme · ${totalSchemeVol > 0 ? ((glance.leadScheme.value / totalSchemeVol) * 100).toFixed(0) : 0}%` : 'Top scheme'}
                            value={glance.leadScheme ? glance.leadScheme.name : '—'} />
                        <Glance icon={Layers} color="#8b5cf6" label="Card schemes tracked"
                            value={fmt.number(glance.schemes)} />
                    </div>
                )}

                {/* ═══ Charts Row ═══ */}
                <div className="dash-charts-grid" style={{ display: 'grid', gap: 16, marginBottom: 20 }}>
                    {/* Area Chart */}
                    <div style={{ background: 'var(--bg-card)', borderRadius: 16, border: '1px solid var(--border)', padding: '22px 24px', boxShadow: 'var(--shadow-card)' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20, flexWrap: 'wrap', gap: 10 }}>
                            <div>
                                <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em' }}>Transaction Volume Trend</h3>
                                <p style={{ margin: '3px 0 0', fontSize: '0.78rem', color: 'var(--text-muted)' }}>Daily volume over selected period</p>
                            </div>
                            <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
                                {/* avg-line toggle */}
                                <button onClick={() => setShowAvg(v => !v)} aria-pressed={showAvg} title="Toggle average line"
                                    style={{
                                        display: 'flex', alignItems: 'center', gap: 6, padding: '5px 10px', borderRadius: 8,
                                        border: '1px solid var(--border)', cursor: 'pointer', fontSize: 11, fontWeight: 600,
                                        background: showAvg ? 'rgba(59,130,246,0.10)' : 'var(--bg-subtle)',
                                        color: showAvg ? '#3b82f6' : 'var(--text-muted)',
                                    }}>
                                    <span style={{ width: 14, height: 0, borderTop: `2px dashed ${showAvg ? '#3b82f6' : 'var(--text-muted)'}` }} /> Avg
                                </button>
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
                                                <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.22} />
                                                <stop offset="100%" stopColor="#3b82f6" stopOpacity={0.01} />
                                            </linearGradient>
                                            <linearGradient id="msfGrad" x1="0" y1="0" x2="0" y2="1">
                                                <stop offset="0%" stopColor="#8b5cf6" stopOpacity={0.14} />
                                                <stop offset="100%" stopColor="#8b5cf6" stopOpacity={0.01} />
                                            </linearGradient>
                                        </defs>
                                        <CartesianGrid strokeDasharray="3 6" stroke="var(--border-light)" vertical={false} />
                                        <XAxis dataKey="date" axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--text-muted)' }} minTickGap={24} />
                                        <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 11, fill: 'var(--text-muted)' }} tickFormatter={compactAxisFormatter} width={56} />
                                        <ReTooltip contentStyle={tooltipStyle} formatter={(val, key) => [fmt.currency(val), key === 'msf' ? 'MSF' : 'Volume']} />
                                        {showAvg && avgVolume > 0 && (
                                            <ReferenceLine y={avgVolume} stroke="#3b82f6" strokeDasharray="4 5" strokeOpacity={0.6}
                                                label={{ value: `avg ${fmt.currency(avgVolume)}`, position: 'right', fill: 'var(--text-muted)', fontSize: 10 }} />
                                        )}
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

                    {/* Donut */}
                    <div style={{ background: 'var(--bg-card)', borderRadius: 16, border: '1px solid var(--border)', padding: '22px 24px', boxShadow: 'var(--shadow-card)' }}>
                        <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em' }}>Volume by Scheme</h3>
                        <p style={{ margin: '3px 0 8px', fontSize: '0.78rem', color: 'var(--text-muted)' }}>Card scheme distribution</p>
                        {schemeData.length > 0 ? (
                            <>
                                <div style={{ height: 200, position: 'relative' }}>
                                    <ResponsiveContainer width="100%" height="100%">
                                        <RePieChart>
                                            <Pie data={schemeData} cx="50%" cy="50%" innerRadius={60} outerRadius={88} dataKey="value"
                                                stroke="var(--bg-card)" strokeWidth={3} paddingAngle={2}
                                                onMouseEnter={(_, idx) => setActiveScheme(idx)}
                                                onMouseLeave={() => setActiveScheme(null)}>
                                                {schemeData.map((_, i) => (
                                                    <Cell key={i} fill={PALETTE[i % PALETTE.length]}
                                                        opacity={activeScheme == null || activeScheme === i ? 1 : 0.32}
                                                        style={{ transition: 'opacity 0.18s ease', cursor: 'pointer' }} />
                                                ))}
                                            </Pie>
                                            <ReTooltip contentStyle={tooltipStyle} formatter={(val) => [fmt.currency(val), 'Volume']} />
                                        </RePieChart>
                                    </ResponsiveContainer>
                                    {/* center label — reflects hovered slice, else total */}
                                    <div style={{
                                        position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
                                        alignItems: 'center', justifyContent: 'center', pointerEvents: 'none', padding: '0 18px', textAlign: 'center',
                                    }}>
                                        <div style={{ fontSize: '0.66rem', color: 'var(--text-muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%' }}>
                                            {centerScheme ? centerScheme.name : 'Total'}
                                        </div>
                                        <div style={{ fontSize: '1.05rem', fontWeight: 800, color: 'var(--text)', letterSpacing: '-0.02em' }}>
                                            {fmt.currency(centerScheme ? centerScheme.value : totalSchemeVol)}
                                        </div>
                                        {centerScheme && totalSchemeVol > 0 && (
                                            <div style={{ fontSize: '0.72rem', color: 'var(--text-muted)', fontWeight: 600 }}>
                                                {((centerScheme.value / totalSchemeVol) * 100).toFixed(1)}%
                                            </div>
                                        )}
                                    </div>
                                </div>
                                <div style={{ display: 'flex', flexDirection: 'column', gap: 9, marginTop: 8 }}>
                                    {schemeData.map((s, i) => (
                                        <div key={s.name}
                                            onMouseEnter={() => setActiveScheme(i)} onMouseLeave={() => setActiveScheme(null)}
                                            style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, cursor: 'default', opacity: activeScheme == null || activeScheme === i ? 1 : 0.5, transition: 'opacity 0.15s' }}>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                                                <span style={{ width: 10, height: 10, borderRadius: 3, background: PALETTE[i % PALETTE.length], flexShrink: 0, boxShadow: `0 2px 4px ${PALETTE[i % PALETTE.length]}30` }} />
                                                <span style={{ fontSize: '0.82rem', color: 'var(--text-secondary)', fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.name}</span>
                                            </div>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexShrink: 0 }}>
                                                <span style={{ fontSize: '0.74rem', color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums' }}>{fmt.currency(s.value)}</span>
                                                <span style={{ fontSize: '0.82rem', color: 'var(--text)', fontWeight: 700, fontVariantNumeric: 'tabular-nums', width: 46, textAlign: 'right' }}>
                                                    {totalSchemeVol > 0 ? ((s.value / totalSchemeVol) * 100).toFixed(1) : 0}%
                                                </span>
                                            </div>
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

                {/* ═══ Top Merchants (ranked list) ═══ */}
                <div style={{ background: 'var(--bg-card)', borderRadius: 16, border: '1px solid var(--border)', padding: '22px 24px', boxShadow: 'var(--shadow-card)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 18 }}>
                        <div>
                            <h3 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em' }}>Top Merchants by Volume</h3>
                            <p style={{ margin: '3px 0 0', fontSize: '0.78rem', color: 'var(--text-muted)' }}>Highest performing merchants this period</p>
                        </div>
                        <button onClick={() => navigate('/business/volume-revenue')} style={{
                            background: 'linear-gradient(135deg, #3b82f6, #6366f1)', border: 'none', color: '#fff',
                            fontSize: 12, padding: '8px 18px', borderRadius: 10, cursor: 'pointer',
                            display: 'flex', alignItems: 'center', gap: 5, fontWeight: 600, flexShrink: 0,
                            boxShadow: '0 4px 12px rgba(59,130,246,0.3)', transition: 'all 0.15s',
                        }}
                            onMouseEnter={e => { e.currentTarget.style.transform = 'translateY(-1px)'; e.currentTarget.style.boxShadow = '0 6px 20px rgba(59,130,246,0.35)'; }}
                            onMouseLeave={e => { e.currentTarget.style.transform = 'none'; e.currentTarget.style.boxShadow = '0 4px 12px rgba(59,130,246,0.3)'; }}
                        >View all <ArrowRight size={13} /></button>
                    </div>

                    {topMerchants.length > 0 ? (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                            {topMerchants.map((m, i) => {
                                const pct = (m.volume / maxMerchantVol) * 100;
                                const sharePct = metrics?.totalVolume > 0 ? (m.volume / metrics.totalVolume) * 100 : 0;
                                const c = PALETTE[i % PALETTE.length];
                                return (
                                    <div key={m.name + i} className="dash-merch-row"
                                        onClick={() => navigate('/business/volume-revenue')}
                                        style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '12px 10px', borderRadius: 12, cursor: 'pointer' }}>
                                        {/* avatar with rank chip */}
                                        <div style={{ position: 'relative', flexShrink: 0 }}>
                                            <span style={{
                                                width: 38, height: 38, borderRadius: 11, display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                fontSize: 13, fontWeight: 800, color: c,
                                                background: `${c}16`, border: `1px solid ${c}2e`,
                                            }}>{initials(m.name)}</span>
                                            <span style={{
                                                position: 'absolute', top: -6, left: -6,
                                                width: 18, height: 18, borderRadius: 6, display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                fontSize: 10, fontWeight: 800,
                                                background: i === 0 ? 'linear-gradient(135deg,#fbbf24,#f59e0b)' : 'var(--bg-card)',
                                                color: i === 0 ? '#fff' : 'var(--text-muted)',
                                                border: i === 0 ? 'none' : '1px solid var(--border)',
                                                boxShadow: i === 0 ? '0 2px 6px rgba(245,158,11,0.4)' : 'none',
                                            }}>{i + 1}</span>
                                        </div>
                                        <div style={{ flex: 1, minWidth: 0 }}>
                                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 10, marginBottom: 6 }}>
                                                <span style={{ fontSize: '0.86rem', fontWeight: 600, color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{m.name}</span>
                                                <span style={{ display: 'flex', alignItems: 'baseline', gap: 8, flexShrink: 0 }}>
                                                    <span style={{ fontSize: '0.88rem', fontWeight: 800, color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{fmt.currency(m.volume)}</span>
                                                    <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums', width: 42, textAlign: 'right' }}>{sharePct.toFixed(1)}%</span>
                                                </span>
                                            </div>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                                                <div style={{ flex: 1, height: 7, borderRadius: 999, background: 'var(--bg-subtle)', overflow: 'hidden' }}>
                                                    <div style={{ width: `${Math.max(pct, 2)}%`, height: '100%', borderRadius: 999, background: `linear-gradient(90deg, ${c}, ${c}cc)`, transition: 'width 0.6s ease' }} />
                                                </div>
                                                <span style={{ fontSize: '0.68rem', color: 'var(--text-muted)', flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}>
                                                    {fmt.number(m.txns)} txns{m.msf > 0 ? ` · ${fmt.currency(m.msf)} MSF` : ''}
                                                </span>
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    ) : (
                        loading ? <SkeletonLoader variant="chart" height={280} />
                            : <EmptyState variant="merchant" compact action={{ label: 'Upload Data', to: '/upload' }} />
                    )}
                </div>
            </div>

            <style>{`
                @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
                .spin { animation: spin 1s linear infinite; }
                @keyframes dashIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }

                .dash-kpi-grid {
                    display: grid;
                    grid-template-columns: repeat(5, 1fr);
                    gap: 16px; margin-bottom: 20px;
                }
                @media (max-width: 1200px) { .dash-kpi-grid { grid-template-columns: repeat(3, 1fr); } }
                @media (max-width: 720px)  { .dash-kpi-grid { grid-template-columns: repeat(2, 1fr); } }

                .dash-kpi { transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease; }
                .dash-kpi:hover {
                    transform: translateY(-3px);
                    border-color: color-mix(in srgb, var(--kpi-accent) 45%, var(--border));
                    box-shadow: 0 12px 28px color-mix(in srgb, var(--kpi-accent) 18%, transparent);
                }
                .dash-kpi:hover .dash-kpi-arrow { transform: translateX(3px); }
                .dash-kpi-arrow { transition: transform .18s ease; }

                .dash-merch-row { transition: background .15s ease; }
                .dash-merch-row:hover { background: var(--bg-subtle); }

                .dash-charts-grid { grid-template-columns: minmax(0, 5fr) minmax(0, 2fr); }
                @media (max-width: 1024px) { .dash-charts-grid { grid-template-columns: 1fr; } }

                .dash-live {
                    display: inline-flex; align-items: center; gap: 5px;
                    font-size: 9px; font-weight: 800; letter-spacing: 0.08em;
                    color: #059669; background: rgba(5,150,105,0.10);
                    padding: 2px 8px; border-radius: 999px;
                }
                .dash-live-dot {
                    width: 6px; height: 6px; border-radius: 50%; background: #10b981;
                    animation: dashPulse 1.6s ease-in-out infinite;
                }
                @keyframes dashPulse { 0%,100% { opacity: 1; box-shadow: 0 0 0 0 rgba(16,185,129,0.5); } 50% { opacity: 0.6; box-shadow: 0 0 0 5px rgba(16,185,129,0); } }

                @media (prefers-reduced-motion: reduce) {
                    .dash-kpi, .dash-kpi-arrow, .dash-merch-row, .spin { transition: none !important; animation: none !important; }
                }
            `}</style>
        </div>
    );
};

export default Dashboard;
