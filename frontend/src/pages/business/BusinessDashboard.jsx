import React, { useState, useEffect, useMemo } from 'react';
import {
    LayoutGrid, TrendingUp, Users, UserPlus, UserMinus, AlertCircle,
    Filter, DollarSign, Activity, ArrowUpRight, ArrowRight, RefreshCw
} from 'lucide-react';
import Loader from '../../components/Loader';
import BusinessFilters from '../../components/BusinessFilters';
import PageHeader from '../../components/PageHeader';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';

const BusinessDashboard = () => {
    const navigate = useNavigate();
    const { currencySymbol } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
    const [kpis, setKpis] = useState(null);
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState({
        startDate: '', endDate: '', openDateStart: '', openDateEnd: '',
        partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
        sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
        merchantName: '', midList: [], sidList: [],
    });
    const [filterOpen, setFilterOpen] = useState(false);

    useEffect(() => { fetchKpis(); }, []);

    const fetchKpis = async () => {
        setLoading(true);
        try {
            const token    = localStorage.getItem('token');
            const tenantId = localStorage.getItem('defaultTenantId');
            // Use the filtered POST endpoint so the BusinessFilters drawer fields
            // (partner / RM / MCC / scheme / card-type / destination / channel /
            // team-leader / MID / SID / merchant-name) actually scope the result.
            // The previous GET endpoint silently dropped everything except
            // startDate/endDate, leaving the drawer cosmetic.
            const res = await fetch('/api/business/dashboard/kpis-filtered', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`,
                    'X-Tenant-Id': tenantId,
                },
                body: JSON.stringify(filters),
            });
            if (res.ok) setKpis(await res.json());
            else console.error('kpis-filtered failed', res.status, await res.text());
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    /* ── KPI card definitions grouped by section ── */
    const sections = useMemo(() => {
        if (!kpis) return [];
        return [
            {
                title: 'Transactions',
                cards: [
                    { label: 'Daily Transactions', value: fmt.number(kpis.dailyCount), icon: Activity,    iconBg: '#3b82f6', bg: 'linear-gradient(135deg, #eff6ff, #dbeafe)', border: '#93c5fd' },
                    { label: 'MTD Transactions',   value: fmt.number(kpis.mtdCount),   icon: LayoutGrid,  iconBg: '#6366f1', bg: 'linear-gradient(135deg, #eef2ff, #e0e7ff)', border: '#a5b4fc' },
                    { label: 'YTD Transactions',   value: fmt.number(kpis.ytdCount),   icon: LayoutGrid,  iconBg: '#8b5cf6', bg: 'linear-gradient(135deg, #f5f3ff, #ede9fe)', border: '#c4b5fd' },
                ],
            },
            {
                title: 'Volume',
                cards: [
                    { label: 'Daily Volume', value: fmt.currency(kpis.dailyVolume), icon: DollarSign,  iconBg: '#10b981', bg: 'linear-gradient(135deg, #ecfdf5, #d1fae5)', border: '#6ee7b7' },
                    { label: 'MTD Volume',   value: fmt.currency(kpis.mtdVolume),   icon: TrendingUp,  iconBg: '#059669', bg: 'linear-gradient(135deg, #ecfdf5, #d1fae5)', border: '#6ee7b7' },
                    { label: 'YTD Volume',   value: fmt.currency(kpis.ytdVolume),   icon: TrendingUp,  iconBg: '#047857', bg: 'linear-gradient(135deg, #ecfdf5, #d1fae5)', border: '#6ee7b7' },
                ],
            },
            {
                title: 'Merchants',
                cards: [
                    { label: 'Active Merchants',  value: fmt.number(kpis.activeMerchants),  icon: Users,       iconBg: '#06b6d4', bg: 'linear-gradient(135deg, #ecfeff, #cffafe)', border: '#67e8f9', drillDown: '/merchants' },
                    { label: 'New Merchants',      value: fmt.number(kpis.newMerchants),     icon: UserPlus,    iconBg: '#22c55e', bg: 'linear-gradient(135deg, #f0fdf4, #dcfce7)', border: '#86efac' },
                    { label: 'Dormant Merchants',  value: fmt.number(kpis.dormantMerchants), icon: UserMinus,   iconBg: '#f97316', bg: 'linear-gradient(135deg, #fff7ed, #ffedd5)', border: '#fdba74', drillDown: '/business/attrition' },
                    { label: 'Zero Sales',         value: fmt.number(kpis.zeroSalesMerchants), icon: AlertCircle, iconBg: '#ef4444', bg: 'linear-gradient(135deg, #fef2f2, #fee2e2)', border: '#fca5a5', drillDown: '/business/zero-transaction' },
                ],
            },
        ];
    }, [kpis]);

    return (
        <div style={{ background: '#f1f5f9', minHeight: '100vh' }}>
            {/* ═══ Header ═══ */}
            <div style={{
                padding: '20px 28px', background: '#fff', borderBottom: '1px solid #e2e8f0',
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
                        <h1 style={{ margin: 0, fontSize: '1.2rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.03em' }}>
                            Business Dashboard
                        </h1>
                        <p style={{ margin: '2px 0 0', fontSize: '0.82rem', color: '#94a3b8' }}>
                            {kpis?.effectiveDate ? `Data as of ${kpis.effectiveDate}` : 'Merchant portfolio overview'}
                        </p>
                    </div>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <button onClick={() => setFilterOpen(true)} style={{
                        display: 'flex', alignItems: 'center', gap: 6, padding: '8px 16px',
                        background: '#fff', border: '1px solid #e2e8f0', borderRadius: 10, cursor: 'pointer',
                        color: '#64748b', fontSize: 13, fontWeight: 600,
                    }}>
                        <Filter size={15} /> Filters
                    </button>
                    <button onClick={fetchKpis} style={{
                        padding: 9, background: '#fff', border: '1px solid #e2e8f0', borderRadius: 10,
                        cursor: 'pointer', display: 'flex', alignItems: 'center',
                    }}>
                        <RefreshCw size={15} color="#64748b" className={loading ? 'spin' : ''} />
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
                                        color: '#64748b', textTransform: 'uppercase',
                                        letterSpacing: '0.06em',
                                    }}>
                                        {section.title}
                                    </h2>
                                    <div style={{ flex: 1, height: 1, background: '#e2e8f0' }} />
                                </div>

                                {/* Cards grid */}
                                <div style={{
                                    display: 'grid',
                                    gridTemplateColumns: `repeat(${Math.min(section.cards.length, 4)}, 1fr)`,
                                    gap: 14,
                                }}>
                                    {section.cards.map((card) => (
                                        <div
                                            key={card.label}
                                            onClick={() => card.drillDown && navigate(card.drillDown)}
                                            style={{
                                                background: card.bg,
                                                border: `1px solid ${card.border}`,
                                                borderRadius: 14,
                                                padding: '20px 22px',
                                                cursor: card.drillDown ? 'pointer' : 'default',
                                                transition: 'all 0.25s ease',
                                                position: 'relative',
                                                overflow: 'hidden',
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
                                            {/* Icon */}
                                            <div style={{
                                                width: 40, height: 40, borderRadius: 11,
                                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                background: card.iconBg,
                                                boxShadow: `0 4px 12px ${card.iconBg}40`,
                                                marginBottom: 14,
                                            }}>
                                                <card.icon size={19} color="#fff" strokeWidth={2} />
                                            </div>

                                            {/* Value */}
                                            <div style={{
                                                fontSize: '1.5rem', fontWeight: 800,
                                                color: '#0f172a', letterSpacing: '-0.04em',
                                                lineHeight: 1, marginBottom: 5,
                                            }}>
                                                {card.value || '—'}
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

                        {/* Quick Links */}
                        <div style={{
                            background: '#fff', borderRadius: 14, border: '1px solid #e2e8f0',
                            padding: '22px 24px', boxShadow: '0 1px 3px rgba(0,0,0,0.04)',
                        }}>
                            <h3 style={{ margin: '0 0 16px', fontSize: '0.95rem', fontWeight: 700, color: '#0f172a' }}>
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
                                        padding: '14px 16px', background: '#f8fafc', border: '1px solid #e2e8f0',
                                        borderRadius: 10, cursor: 'pointer', width: '100%', textAlign: 'left',
                                        fontFamily: 'inherit', transition: 'all 0.15s',
                                    }}
                                    onMouseEnter={e => { e.currentTarget.style.borderColor = link.color; e.currentTarget.style.background = '#fff'; }}
                                    onMouseLeave={e => { e.currentTarget.style.borderColor = '#e2e8f0'; e.currentTarget.style.background = '#f8fafc'; }}
                                    >
                                        <span style={{ fontSize: '0.85rem', fontWeight: 600, color: '#334155' }}>{link.label}</span>
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
            `}</style>
        </div>
    );
};

export default BusinessDashboard;
