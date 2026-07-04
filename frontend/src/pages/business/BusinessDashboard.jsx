import React, { useState, useEffect, useMemo } from 'react';
import {
    LayoutGrid, TrendingUp, Users, UserPlus, UserMinus, AlertCircle,
    Filter, DollarSign, Activity, ArrowUpRight, ArrowRight, RefreshCw,
    ArrowUp, ArrowDown, Percent, Receipt, Globe, ShieldCheck, ShieldAlert,
} from 'lucide-react';
import Loader from '../../components/Loader';
import BusinessFilters from '../../components/BusinessFilters';
import PageHeader from '../../components/PageHeader';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import api from '../../api/axios';

/* Percentage delta vs a prior-period value. Returns null when no meaningful
   comparison exists (missing / zero prior), so the chip is simply omitted. */
const pctDelta = (cur, prev) => {
    const c = Number(cur), p = Number(prev);
    if (!isFinite(c) || !isFinite(p) || p === 0) return null;
    return ((c - p) / p) * 100;
};

const fmtBps = (v) => (v === null || v === undefined ? '—' : `${Number(v).toFixed(1)} bps`);
const fmtPct = (v) => (v === null || v === undefined ? '—' : `${Number(v).toFixed(1)}%`);

/* Small ▲/▼ period-over-period chip rendered next to a KPI value. */
const DeltaChip = ({ delta, label }) => {
    if (delta === null || delta === undefined) return null;
    const up = delta >= 0;
    const near0 = Math.abs(delta) < 0.05;
    const color = near0 ? '#64748b' : up ? '#059669' : '#dc2626';
    const bg    = near0 ? 'rgba(100,116,139,0.10)' : up ? 'rgba(5,150,105,0.10)' : 'rgba(220,38,38,0.08)';
    const Icon = up ? ArrowUp : ArrowDown;
    return (
        <span title={label} style={{
            display: 'inline-flex', alignItems: 'center', gap: 3,
            padding: '2px 7px', borderRadius: 999,
            background: bg, color,
            fontSize: '0.68rem', fontWeight: 700,
            fontVariantNumeric: 'tabular-nums',
            lineHeight: 1.4, whiteSpace: 'nowrap',
        }}>
            {!near0 && <Icon size={10} strokeWidth={2.5} />}
            {Math.abs(delta) >= 1000 ? '>999' : Math.abs(delta).toFixed(1)}%
        </span>
    );
};

const BusinessDashboard = () => {
    const navigate = useNavigate();
    const { currencySymbol, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
    const [kpis, setKpis] = useState(null);
    const [revenue, setRevenue] = useState(null);
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState({
        startDate: '', endDate: '', openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
        sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
    });
    const [filterOpen, setFilterOpen] = useState(false);

    useEffect(() => { fetchKpis(); }, [tenantVersion]);

    const fetchKpis = async () => {
        setLoading(true);
        try {
            const [kRes, rRes] = await Promise.allSettled([
                api.post('/business/dashboard/kpis-filtered', filters),
                api.post('/business/revenue-kpis', filters),
            ]);
            if (kRes.status === 'fulfilled') setKpis(kRes.value.data);
            else console.error('kpis-filtered failed', kRes.reason);
            if (rRes.status === 'fulfilled') setRevenue(rRes.value.data);
            else { console.error('revenue-kpis failed', rRes.reason); setRevenue(null); }
        } finally {
            setLoading(false);
        }
    };

    /* ── KPI card definitions grouped by section ── */
    const sections = useMemo(() => {
        if (!kpis) return [];
        const filtered = !!kpis.filtersApplied;
        return [
            {
                title: 'Transactions',
                span: 4, // 3 cards across a 12-col grid
                cards: [
                    { label: 'Daily Transactions', value: fmt.number(kpis.dailyCount), icon: Activity,    iconBg: '#3b82f6', bg: 'linear-gradient(135deg, #eff6ff, #dbeafe)', border: '#93c5fd',
                      delta: pctDelta(kpis.dailyCount, kpis.prevDailyCount), deltaLabel: 'vs prior period' },
                    { label: 'MTD Transactions',   value: fmt.number(kpis.mtdCount),   icon: LayoutGrid,  iconBg: '#6366f1', bg: 'linear-gradient(135deg, #eef2ff, #e0e7ff)', border: '#a5b4fc',
                      delta: pctDelta(kpis.mtdCount, kpis.prevMtdCount), deltaLabel: 'vs last month pace' },
                    { label: 'YTD Transactions',   value: fmt.number(kpis.ytdCount),   icon: LayoutGrid,  iconBg: '#8b5cf6', bg: 'linear-gradient(135deg, #f5f3ff, #ede9fe)', border: '#c4b5fd',
                      delta: pctDelta(kpis.ytdCount, kpis.prevYtdCount), deltaLabel: 'vs prior YTD' },
                ],
            },
            {
                title: 'Volume',
                span: 4, // 3 cards across a 12-col grid
                cards: [
                    { label: 'Daily Volume', value: fmt.currency(kpis.dailyVolume), icon: DollarSign,  iconBg: '#10b981', bg: 'linear-gradient(135deg, #ecfdf5, #d1fae5)', border: '#6ee7b7',
                      delta: pctDelta(kpis.dailyVolume, kpis.prevDailyVolume), deltaLabel: 'vs prior period' },
                    { label: 'MTD Volume',   value: fmt.currency(kpis.mtdVolume),   icon: TrendingUp,  iconBg: '#059669', bg: 'linear-gradient(135deg, #ecfdf5, #d1fae5)', border: '#6ee7b7',
                      delta: pctDelta(kpis.mtdVolume, kpis.prevMtdVolume), deltaLabel: 'vs last month pace' },
                    { label: 'YTD Volume',   value: fmt.currency(kpis.ytdVolume),   icon: TrendingUp,  iconBg: '#047857', bg: 'linear-gradient(135deg, #ecfdf5, #d1fae5)', border: '#6ee7b7',
                      delta: pctDelta(kpis.ytdVolume, kpis.prevYtdVolume), deltaLabel: 'vs prior YTD' },
                ],
            },
            {
                title: 'Merchants',
                span: 3, // 4 cards across a 12-col grid
                cards: [
                    { label: 'Active Merchants',  value: fmt.number(kpis.activeMerchants),  icon: Users,       iconBg: '#06b6d4', bg: 'linear-gradient(135deg, #ecfeff, #cffafe)', border: '#67e8f9', drillDown: '/merchants' },
                    { label: 'New Merchants',      value: fmt.number(kpis.newMerchants),     icon: UserPlus,    iconBg: '#22c55e', bg: 'linear-gradient(135deg, #f0fdf4, #dcfce7)', border: '#86efac',
                      badge: filtered ? 'tenant-wide' : null },
                    { label: 'Dormant Merchants',  value: fmt.number(kpis.dormantMerchants), icon: UserMinus,   iconBg: '#f97316', bg: 'linear-gradient(135deg, #fff7ed, #ffedd5)', border: '#fdba74', drillDown: '/business/attrition',
                      badge: filtered ? 'tenant-wide' : null },
                    { label: 'Zero Sales',         value: fmt.number(kpis.zeroSalesMerchants), icon: AlertCircle, iconBg: '#ef4444', bg: 'linear-gradient(135deg, #fef2f2, #fee2e2)', border: '#fca5a5', drillDown: '/business/zero-transaction' },
                ],
            },
        ];
    }, [kpis]);

    /* ── Effective-rate & DCC sections (from /business/revenue-kpis) ── */
    const revSections = useMemo(() => {
        if (!revenue) return [];
        const netTakeAvail = revenue.netTakeRateBps !== null && revenue.netTakeRateBps !== undefined;
        return [
            {
                title: 'Effective Rate',
                span: 4, // 3 cards
                cards: [
                    { label: 'Effective MSF Rate', value: fmtBps(revenue.msfRateBps), icon: Percent, iconBg: '#0ea5e9',
                      bg: 'linear-gradient(135deg, #f0f9ff, #e0f2fe)', border: '#7dd3fc',
                      sub: `${fmt.currency(revenue.totalMsf)} MSF on ${fmt.currency(revenue.totalVolume)}` },
                    { label: 'Net Take Rate', value: netTakeAvail ? fmtBps(revenue.netTakeRateBps) : '—', icon: TrendingUp, iconBg: '#6366f1',
                      bg: 'linear-gradient(135deg, #eef2ff, #e0e7ff)', border: '#a5b4fc',
                      sub: netTakeAvail ? `${fmt.currency(revenue.netRevenue)} net revenue` : 'Unavailable with filters applied' },
                    { label: 'Average Ticket', value: fmt.currency(revenue.avgTicket), icon: Receipt, iconBg: '#8b5cf6',
                      bg: 'linear-gradient(135deg, #f5f3ff, #ede9fe)', border: '#c4b5fd',
                      sub: `${fmt.number(revenue.totalTxns)} transactions` },
                ],
            },
            {
                title: 'Dynamic Currency Conversion (DCC)',
                span: 3, // 4 cards
                cards: [
                    { label: 'DCC Opt-In Rate', value: fmtPct(revenue.dccOptinRatePct), icon: ShieldCheck, iconBg: '#10b981',
                      bg: 'linear-gradient(135deg, #ecfdf5, #d1fae5)', border: '#6ee7b7',
                      sub: 'of eligible volume' },
                    { label: 'DCC Penetration', value: fmtPct(revenue.dccPenetrationPct), icon: Globe, iconBg: '#0891b2',
                      bg: 'linear-gradient(135deg, #ecfeff, #cffafe)', border: '#67e8f9',
                      sub: 'eligible share of total' },
                    { label: 'Opted-In Volume', value: fmt.currency(revenue.dccOptinVolume), icon: DollarSign, iconBg: '#059669',
                      bg: 'linear-gradient(135deg, #f0fdf4, #dcfce7)', border: '#86efac',
                      sub: `${fmt.currency(revenue.dccEligibleVolume)} eligible` },
                    { label: 'Missed DCC Volume', value: fmt.currency(revenue.dccMissedVolume), icon: ShieldAlert, iconBg: '#f59e0b',
                      bg: 'linear-gradient(135deg, #fffbeb, #fef3c7)', border: '#fcd34d',
                      sub: 'eligible but not opted-in' },
                ],
            },
        ];
    }, [revenue, fmt]);

    return (
        <div style={{ background: 'var(--bg)', minHeight: '100vh' }}>
            {/* ═══ Header ═══ */}
            <div style={{
                padding: '20px 28px', background: 'var(--bg-card)', borderBottom: '1px solid var(--border)',
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                position: 'sticky', top: 0, zIndex: 10,
            }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                    <div style={{
                        width: 42, height: 42, borderRadius: 12,
                        background: 'linear-gradient(135deg, #3b82f6, #6366f1)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        boxShadow: '0 4px 12px rgba(59,130,246,0.3)',
                    }}>
                        <LayoutGrid size={20} color="#fff" />
                    </div>
                    <div>
                        <h1 style={{ margin: 0, fontSize: '1.2rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.03em' }}>
                            Business Dashboard
                        </h1>
                        <p style={{ margin: '2px 0 0', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
                            {kpis?.effectiveDate ? `Data as of ${kpis.effectiveDate}` : 'Merchant portfolio overview'}
                        </p>
                    </div>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <button onClick={() => setFilterOpen(true)} style={{
                        display: 'flex', alignItems: 'center', gap: 6, padding: '8px 16px',
                        background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10, cursor: 'pointer',
                        color: 'var(--text-secondary)', fontSize: 13, fontWeight: 600,
                    }}>
                        <Filter size={15} /> Filters
                    </button>
                    <button onClick={fetchKpis} style={{
                        padding: 9, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10,
                        cursor: 'pointer', display: 'flex', alignItems: 'center',
                    }}>
                        <RefreshCw size={15} color="var(--text-secondary)" className={loading ? 'spin' : ''} />
                    </button>
                    <button onClick={() => navigate('/business/executive-dashboard-v2')} style={{
                        display: 'flex', alignItems: 'center', gap: 6, padding: '8px 18px',
                        background: 'linear-gradient(135deg, #0f172a, #1e293b)', color: '#fff',
                        border: 'none', borderRadius: 10, cursor: 'pointer', fontSize: 12, fontWeight: 700,
                        boxShadow: '0 2px 8px rgba(15,23,42,0.2)',
                    }}>
                        <TrendingUp size={14} /> PREMIUM INSIGHTS
                    </button>
                </div>
            </div>

            <div style={{ padding: '24px 28px 40px' }}>
                <BusinessFilters
                    filters={filters}
                    onChange={setFilters}
                    onApply={fetchKpis}
                    isOpen={filterOpen}
                    onClose={() => setFilterOpen(false)}
                />

                {loading ? (
                    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
                        <Loader />
                    </div>
                ) : (
                    <>
                        {/* KPI Sections */}
                        {sections.map((section) => (
                            <div key={section.title} style={{ marginBottom: 28 }}>
                                {/* Section header */}
                                <div style={{
                                    display: 'flex', alignItems: 'center', gap: 8,
                                    marginBottom: 14,
                                }}>
                                    <h2 style={{
                                        margin: 0, fontSize: '0.78rem', fontWeight: 700,
                                        color: 'var(--text-secondary)', textTransform: 'uppercase',
                                        letterSpacing: '0.06em',
                                    }}>
                                        {section.title}
                                    </h2>
                                    <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
                                </div>

                                {/* Cards grid — shared 12-col track so every row's
                                    columns align vertically (3-card rows span 4,
                                    the 4-card row spans 3). Collapses on narrow screens. */}
                                <div className="bd-cards-grid">
                                    {section.cards.map((card) => (
                                        <div
                                            key={card.label}
                                            className="bd-card"
                                            onClick={() => card.drillDown && navigate(card.drillDown)}
                                            style={{
                                                gridColumn: `span ${section.span}`,
                                                background: card.bg,
                                                border: `1px solid ${card.border}`,
                                                borderRadius: 14,
                                                padding: '20px 22px',
                                                cursor: card.drillDown ? 'pointer' : 'default',
                                                transition: 'transform 0.2s ease, box-shadow 0.2s ease',
                                                position: 'relative',
                                                overflow: 'hidden',
                                                minHeight: 132,
                                                display: 'flex',
                                                flexDirection: 'column',
                                            }}
                                            onMouseEnter={e => {
                                                e.currentTarget.style.transform = 'translateY(-3px)';
                                                e.currentTarget.style.boxShadow = `0 8px 24px ${card.iconBg}18`;
                                            }}
                                            onMouseLeave={e => {
                                                e.currentTarget.style.transform = 'none';
                                                e.currentTarget.style.boxShadow = 'none';
                                            }}
                                        >
                                            {/* Scope badge (tenant-wide counts while filters applied) */}
                                            {card.badge && (
                                                <span title="This count is tenant-wide; the drawer filters do not narrow it." style={{
                                                    position: 'absolute', top: 12, right: 12,
                                                    padding: '2px 8px', borderRadius: 999,
                                                    background: 'rgba(71,85,105,0.10)', color: '#475569',
                                                    fontSize: '0.62rem', fontWeight: 700,
                                                    textTransform: 'uppercase', letterSpacing: '0.05em',
                                                }}>
                                                    {card.badge}
                                                </span>
                                            )}

                                            {/* Icon */}
                                            <div style={{
                                                width: 40, height: 40, borderRadius: 11,
                                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                background: card.iconBg,
                                                boxShadow: `0 4px 12px ${card.iconBg}40`,
                                                marginBottom: 'auto',
                                            }}>
                                                <card.icon size={19} color="#fff" strokeWidth={2} />
                                            </div>

                                            {/* Value + period-over-period chip */}
                                            <div style={{
                                                display: 'flex', alignItems: 'baseline', gap: 8,
                                                marginTop: 14, marginBottom: 5,
                                            }}>
                                                <div style={{
                                                    fontSize: '1.5rem', fontWeight: 800,
                                                    color: '#0f172a', letterSpacing: '-0.04em',
                                                    lineHeight: 1.05,
                                                    fontVariantNumeric: 'tabular-nums',
                                                    whiteSpace: 'nowrap',
                                                }}>
                                                    {card.value || '—'}
                                                </div>
                                                <DeltaChip delta={card.delta} label={card.deltaLabel} />
                                            </div>

                                            {/* Label */}
                                            <div style={{
                                                fontSize: '0.82rem', fontWeight: 500,
                                                color: '#475569',
                                                display: 'flex', alignItems: 'center', gap: 4,
                                            }}>
                                                {card.label}
                                                {card.drillDown && <ArrowRight size={12} color={card.iconBg} />}
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        ))}

                        {/* Effective-rate & DCC sections */}
                        {revSections.map((section) => (
                            <div key={section.title} style={{ marginBottom: 28 }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
                                    <h2 style={{
                                        margin: 0, fontSize: '0.78rem', fontWeight: 700,
                                        color: 'var(--text-secondary)', textTransform: 'uppercase',
                                        letterSpacing: '0.06em',
                                    }}>
                                        {section.title}
                                    </h2>
                                    <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
                                </div>

                                <div className="bd-cards-grid">
                                    {section.cards.map((card) => (
                                        <div
                                            key={card.label}
                                            className="bd-card"
                                            style={{
                                                gridColumn: `span ${section.span}`,
                                                background: card.bg,
                                                border: `1px solid ${card.border}`,
                                                borderRadius: 14,
                                                padding: '20px 22px',
                                                transition: 'transform 0.2s ease, box-shadow 0.2s ease',
                                                position: 'relative',
                                                overflow: 'hidden',
                                                minHeight: 132,
                                                display: 'flex',
                                                flexDirection: 'column',
                                            }}
                                            onMouseEnter={e => {
                                                e.currentTarget.style.transform = 'translateY(-3px)';
                                                e.currentTarget.style.boxShadow = `0 8px 24px ${card.iconBg}18`;
                                            }}
                                            onMouseLeave={e => {
                                                e.currentTarget.style.transform = 'none';
                                                e.currentTarget.style.boxShadow = 'none';
                                            }}
                                        >
                                            <div style={{
                                                width: 40, height: 40, borderRadius: 11,
                                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                background: card.iconBg,
                                                boxShadow: `0 4px 12px ${card.iconBg}40`,
                                                marginBottom: 'auto',
                                            }}>
                                                <card.icon size={19} color="#fff" strokeWidth={2} />
                                            </div>

                                            <div style={{
                                                fontSize: '1.5rem', fontWeight: 800,
                                                color: '#0f172a', letterSpacing: '-0.04em',
                                                lineHeight: 1.05, marginTop: 14, marginBottom: 5,
                                                fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
                                            }}>
                                                {card.value || '—'}
                                            </div>

                                            <div style={{ fontSize: '0.82rem', fontWeight: 600, color: '#334155' }}>
                                                {card.label}
                                            </div>
                                            {card.sub && (
                                                <div style={{ fontSize: '0.72rem', fontWeight: 500, color: '#64748b', marginTop: 2 }}>
                                                    {card.sub}
                                                </div>
                                            )}
                                        </div>
                                    ))}
                                </div>
                            </div>
                        ))}

                        {/* Quick Links */}
                        <div style={{
                            background: 'var(--bg-card)', borderRadius: 14, border: '1px solid var(--border)',
                            padding: '22px 24px', boxShadow: 'var(--shadow-card)',
                        }}>
                            <h3 style={{ margin: '0 0 16px', fontSize: '0.95rem', fontWeight: 700, color: 'var(--text)' }}>
                                Quick Actions
                            </h3>
                            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 10 }}>
                                {[
                                    { label: 'Volume & Revenue Summary', path: '/business/volume-revenue', color: '#3b82f6' },
                                    { label: 'Transaction Performance', path: '/business/performance', color: '#8b5cf6' },
                                    { label: 'Merchant Analytics', path: '/business/merchant-analytics', color: '#06b6d4' },
                                    { label: 'Attrition Report', path: '/business/attrition', color: '#f97316' },
                                ].map(link => (
                                    <button key={link.path} onClick={() => navigate(link.path)} style={{
                                        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                                        padding: '14px 16px', background: 'var(--bg-subtle)', border: '1px solid var(--border)',
                                        borderRadius: 10, cursor: 'pointer', width: '100%', textAlign: 'left',
                                        fontFamily: 'inherit', transition: 'all 0.15s',
                                    }}
                                    onMouseEnter={e => { e.currentTarget.style.borderColor = link.color; e.currentTarget.style.background = 'var(--bg-card)'; }}
                                    onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = 'var(--bg-subtle)'; }}
                                    >
                                        <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text)' }}>{link.label}</span>
                                        <ArrowUpRight size={14} color={link.color} />
                                    </button>
                                ))}
                            </div>
                        </div>
                    </>
                )}
            </div>

            <style>{`
                @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
                .spin { animation: spin 1s linear infinite; }

                /* Shared 12-column track. Every row aligns to the same columns:
                   3-card rows use span 4, the 4-card row uses span 3. */
                .bd-cards-grid {
                    display: grid;
                    grid-template-columns: repeat(12, 1fr);
                    gap: 14px;
                }
                /* Tablet: 2 across, each card full-width of the 2-col track. */
                @media (max-width: 900px) {
                    .bd-cards-grid { grid-template-columns: repeat(2, 1fr); }
                    .bd-cards-grid > .bd-card { grid-column: span 1 !important; }
                }
                /* Mobile: single column. */
                @media (max-width: 560px) {
                    .bd-cards-grid { grid-template-columns: 1fr; }
                    .bd-cards-grid > .bd-card { grid-column: span 1 !important; }
                }
            `}</style>
        </div>
    );
};

export default BusinessDashboard;
