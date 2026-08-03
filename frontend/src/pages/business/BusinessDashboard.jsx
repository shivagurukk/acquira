import React, { useState, useEffect, useMemo, useCallback } from 'react';
import {
    LayoutGrid, TrendingUp, Users, UserPlus, UserMinus, AlertCircle,
    Filter, DollarSign, Activity, ArrowUpRight, ArrowRight, RefreshCw,
    ArrowUp, ArrowDown, Percent, Receipt, Globe, ShieldCheck, ShieldAlert, X,
} from 'lucide-react';
import Loader from '../../components/Loader';
import BusinessFilters from '../../components/BusinessFilters';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';
import api from '../../api/axios';

/* ────────────────────────────────────────────────────────────────────────────
   Business Dashboard — restrained financial-instrument register.
   Every tile carries three layers of information:
     value  →  period-over-period delta  →  context line (window / prior value)
   All colors flow from CSS variables so light & dark themes both render
   correctly (the previous pastel-gradient tiles hardcoded light-mode colors).
   ──────────────────────────────────────────────────────────────────────────── */

const EMPTY_FILTERS = {
    startDate: '', endDate: '', openDateStart: '', openDateEnd: '',
    partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
    sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
    merchantName: '', midList: [], sidList: [],
};

const pctDelta = (cur, prev) => {
    const c = Number(cur), p = Number(prev);
    if (!isFinite(c) || !isFinite(p) || p === 0) return null;
    return ((c - p) / p) * 100;
};

const fmtBps = (v) => (v === null || v === undefined ? '—' : `${Number(v).toFixed(1)} bps`);
const fmtPct = (v) => (v === null || v === undefined ? '—' : `${Number(v).toFixed(1)}%`);

/* "2026-06-25" → "Jun 25" / "Jun 25, 2026" */
const shortDate = (iso, withYear = false) => {
    if (!iso) return '';
    const d = new Date(`${iso}T00:00:00`);
    if (isNaN(d)) return String(iso);
    const opts = withYear ? { month: 'short', day: 'numeric', year: 'numeric' } : { month: 'short', day: 'numeric' };
    return d.toLocaleDateString('en-US', opts);
};
const rangeLabel = (from, to) => {
    if (!from && !to) return '';
    if (!from || from === to) return shortDate(to || from);
    return `${shortDate(from)} – ${shortDate(to)}`;
};

/* ▲/▼ period-over-period chip */
const DeltaChip = ({ delta, label }) => {
    if (delta === null || delta === undefined) return null;
    const near0 = Math.abs(delta) < 0.05;
    const up = delta >= 0;
    const color = near0 ? 'var(--text-muted)' : up ? 'var(--success, #059669)' : 'var(--danger, #dc2626)';
    const Icon = up ? ArrowUp : ArrowDown;
    return (
        <span title={label} style={{
            display: 'inline-flex', alignItems: 'center', gap: 3,
            padding: '2px 7px', borderRadius: 999,
            border: '1px solid var(--border)',
            background: 'var(--bg-subtle)', color,
            fontSize: '0.68rem', fontWeight: 700,
            fontVariantNumeric: 'tabular-nums',
            lineHeight: 1.4, whiteSpace: 'nowrap',
        }}>
            {!near0 && <Icon size={10} strokeWidth={2.5} />}
            {Math.abs(delta) >= 1000 ? '>999' : Math.abs(delta).toFixed(1)}%
        </span>
    );
};

/* Thin inline progress bar used in the DCC tiles */
const MiniBar = ({ pct, color }) => {
    const clamped = Math.max(0, Math.min(100, Number(pct) || 0));
    return (
        <div style={{ height: 4, borderRadius: 999, background: 'var(--bg-subtle)', border: '1px solid var(--border)', overflow: 'hidden', marginTop: 8 }}>
            <div style={{ height: '100%', width: `${clamped}%`, background: color, borderRadius: 999 }} />
        </div>
    );
};

/* One KPI tile. Token-driven; the only per-tile color is the accent. */
const KpiTile = ({ card, navigate }) => (
    <div
        className="bd-card"
        onClick={() => card.drillDown && navigate(card.drillDown)}
        style={{
            gridColumn: `span ${card.span}`,
            background: 'var(--bg-card)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-lg, 14px)',
            padding: '16px 18px 14px',
            cursor: card.drillDown ? 'pointer' : 'default',
            transition: 'transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease',
            position: 'relative',
            minHeight: 118,
            display: 'flex', flexDirection: 'column',
        }}
        onMouseEnter={e => {
            e.currentTarget.style.transform = 'translateY(-2px)';
            e.currentTarget.style.boxShadow = 'var(--shadow-card, 0 6px 20px rgba(0,0,0,0.08))';
            e.currentTarget.style.borderColor = card.accent;
        }}
        onMouseLeave={e => {
            e.currentTarget.style.transform = 'none';
            e.currentTarget.style.boxShadow = 'none';
            e.currentTarget.style.borderColor = 'var(--border)';
        }}
    >
        {/* Top row: icon chip + label + badge */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span style={{
                width: 26, height: 26, borderRadius: 8, flexShrink: 0,
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                background: `color-mix(in srgb, ${card.accent} 14%, transparent)`,
                color: card.accent,
            }}>
                <card.icon size={14} strokeWidth={2.2} />
            </span>
            <span style={{
                fontSize: '0.74rem', fontWeight: 600, color: 'var(--text-secondary)',
                textTransform: 'uppercase', letterSpacing: '0.04em',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {card.label}
            </span>
            {card.badge && (
                <span title={card.badgeTitle} style={{
                    marginLeft: 'auto', padding: '1px 7px', borderRadius: 999,
                    border: '1px dashed var(--border)',
                    color: 'var(--text-muted)', background: 'transparent',
                    fontSize: '0.6rem', fontWeight: 700,
                    textTransform: 'uppercase', letterSpacing: '0.05em', whiteSpace: 'nowrap',
                }}>
                    {card.badge}
                </span>
            )}
            {card.drillDown && !card.badge && (
                <ArrowRight size={13} color="var(--text-muted)" style={{ marginLeft: 'auto' }} />
            )}
        </div>

        {/* Value + delta */}
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
            <span style={{
                fontSize: '1.45rem', fontWeight: 750, color: 'var(--text)',
                letterSpacing: '-0.03em', lineHeight: 1.05,
                fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
            }}>
                {card.value ?? '—'}
            </span>
            <DeltaChip delta={card.delta} label={card.deltaLabel} />
        </div>

        {/* Context line — the prior value / window this number is measured over */}
        {card.sub && (
            <div style={{
                marginTop: 6, fontSize: '0.72rem', fontWeight: 500,
                color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {card.sub}
            </div>
        )}
        {card.bar !== undefined && <MiniBar pct={card.bar} color={card.accent} />}
    </div>
);

/* Human labels for the applied-filter chip bar */
const FILTER_CHIP_DEFS = [
    ['merchantName',   v => `Name: ${v}`,               () => ''],
    ['midList',        v => `MID ×${v.length}`,         () => []],
    ['sidList',        v => `SID ×${v.length}`,         () => []],
    ['partnerList',    v => `Partner ×${v.length}`,     () => []],
    ['rmList',         v => `RM ×${v.length}`,          () => []],
    ['teamLeaderList', v => `Team Lead ×${v.length}`,   () => []],
    ['mccList',        v => `MCC ×${v.length}`,         () => []],
    ['industryList',   v => `Industry ×${v.length}`,    () => []],
    ['schemeList',     v => `Scheme ×${v.length}`,      () => []],
    ['cardTypeList',   v => `Card Type ×${v.length}`,   () => []],
    ['destinationList',v => `Destination ×${v.length}`, () => []],
    ['channelList',    v => `Channel ×${v.length}`,     () => []],
];

const BusinessDashboard = () => {
    const navigate = useNavigate();
    const { currencySymbol, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);
    const [kpis, setKpis] = useState(null);
    const [revenue, setRevenue] = useState(null);
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState(EMPTY_FILTERS);
    const [filterOpen, setFilterOpen] = useState(false);

    /* fetch with an explicit filter payload — chip removal and Reset call this
       with the *next* filters directly, so there is no set-state / fetch race. */
    const fetchKpis = useCallback(async (payload) => {
        setLoading(true);
        try {
            const body = payload ?? filters;
            const [kRes, rRes] = await Promise.allSettled([
                api.post('/business/dashboard/kpis-filtered', body),
                api.post('/business/revenue-kpis', body),
            ]);
            if (kRes.status === 'fulfilled') setKpis(kRes.value.data);
            else console.error('kpis-filtered failed', kRes.reason);
            if (rRes.status === 'fulfilled') setRevenue(rRes.value.data);
            else { console.error('revenue-kpis failed', rRes.reason); setRevenue(null); }
        } finally {
            setLoading(false);
        }
    }, [filters]);

    useEffect(() => {
        setFilters(EMPTY_FILTERS);
        fetchKpis(EMPTY_FILTERS);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantVersion]);

    const removeFilter = (key, resetVal) => {
        const next = { ...filters, [key]: resetVal };
        setFilters(next);
        fetchKpis(next);
    };
    const clearAll = () => { setFilters(EMPTY_FILTERS); fetchKpis(EMPTY_FILTERS); };

    /* applied-filter chips (incl. date ranges) */
    const chips = useMemo(() => {
        const out = [];
        if (filters.startDate || filters.endDate) {
            out.push({ key: '__txnDate', label: `Txn date: ${rangeLabel(filters.startDate, filters.endDate)}`,
                onRemove: () => { const n = { ...filters, startDate: '', endDate: '' }; setFilters(n); fetchKpis(n); } });
        }
        if (filters.openDateStart || filters.openDateEnd) {
            out.push({ key: '__openDate', label: `Open date: ${rangeLabel(filters.openDateStart, filters.openDateEnd)}`,
                onRemove: () => { const n = { ...filters, openDateStart: '', openDateEnd: '' }; setFilters(n); fetchKpis(n); } });
        }
        FILTER_CHIP_DEFS.forEach(([key, toLabel, resetVal]) => {
            const v = filters[key];
            const set = Array.isArray(v) ? v.length > 0 : (v && String(v).trim() !== '');
            if (set) out.push({ key, label: toLabel(v), onRemove: () => removeFilter(key, resetVal()) });
        });
        return out;
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filters]);

    /* ── Transactions / Volume / Merchants sections ── */
    const sections = useMemo(() => {
        if (!kpis) return [];
        const filtered = !!kpis.filtersApplied;
        const custom = !!kpis.customRange;
        const end = kpis.effectiveDate;
        const dailyCaption   = custom ? rangeLabel(kpis.rangeStart, end) : shortDate(end);
        const mtdCaption     = end ? `${shortDate(`${String(end).slice(0, 8)}01`)} – ${shortDate(end)}` : '';
        const ytdCaption     = end ? `${shortDate(`${String(end).slice(0, 5)}01-01`)} – ${shortDate(end)}` : '';
        const firstLabel     = custom ? 'Selected Period' : 'Daily';
        const firstDelta     = custom ? 'vs preceding period of equal length' : 'vs prior day';
        const merchantWindow = rangeLabel(kpis.merchantWindowStart, end);

        return [
            {
                title: 'Transactions',
                caption: `as of ${shortDate(end, true)}`,
                cards: [
                    { span: 4, label: `${firstLabel} Transactions`, value: fmt.number(kpis.dailyCount), icon: Activity, accent: '#3b82f6',
                      delta: pctDelta(kpis.dailyCount, kpis.prevDailyCount), deltaLabel: firstDelta,
                      sub: `${dailyCaption} · prev ${fmt.number(kpis.prevDailyCount)}` },
                    { span: 4, label: 'MTD Transactions', value: fmt.number(kpis.mtdCount), icon: LayoutGrid, accent: '#6366f1',
                      delta: pctDelta(kpis.mtdCount, kpis.prevMtdCount), deltaLabel: 'vs same span last month',
                      sub: `${mtdCaption} · prev ${fmt.number(kpis.prevMtdCount)}` },
                    { span: 4, label: 'YTD Transactions', value: fmt.number(kpis.ytdCount), icon: LayoutGrid, accent: '#8b5cf6',
                      delta: pctDelta(kpis.ytdCount, kpis.prevYtdCount), deltaLabel: 'vs same span prior year',
                      sub: `${ytdCaption} · prev ${fmt.number(kpis.prevYtdCount)}` },
                ],
            },
            {
                title: 'Volume',
                caption: filtered ? 'filtered · cardholder-currency basis' : 'cardholder-currency basis',
                cards: [
                    { span: 4, label: `${firstLabel} Volume`, value: fmt.currency(kpis.dailyVolume), icon: DollarSign, accent: '#10b981',
                      delta: pctDelta(kpis.dailyVolume, kpis.prevDailyVolume), deltaLabel: firstDelta,
                      sub: `${dailyCaption} · prev ${fmt.currency(kpis.prevDailyVolume)}` },
                    { span: 4, label: 'MTD Volume', value: fmt.currency(kpis.mtdVolume), icon: TrendingUp, accent: '#059669',
                      delta: pctDelta(kpis.mtdVolume, kpis.prevMtdVolume), deltaLabel: 'vs same span last month',
                      sub: `${mtdCaption} · prev ${fmt.currency(kpis.prevMtdVolume)}` },
                    { span: 4, label: 'YTD Volume', value: fmt.currency(kpis.ytdVolume), icon: TrendingUp, accent: '#047857',
                      delta: pctDelta(kpis.ytdVolume, kpis.prevYtdVolume), deltaLabel: 'vs same span prior year',
                      sub: `${ytdCaption} · prev ${fmt.currency(kpis.prevYtdVolume)}` },
                ],
            },
            {
                title: 'Merchants',
                caption: custom ? `activity window ${merchantWindow}` : `active window: month-to-date (${merchantWindow})`,
                cards: [
                    { span: 3, label: 'Active', value: fmt.number(kpis.activeMerchants), icon: Users, accent: '#06b6d4',
                      drillDown: '/merchants', sub: `with volume ${merchantWindow}` },
                    { span: 3, label: 'New', value: fmt.number(kpis.newMerchants), icon: UserPlus, accent: '#22c55e',
                      badge: filtered ? 'tenant-wide' : null,
                      badgeTitle: 'Tenant-wide snapshot; drawer filters do not narrow this count.',
                      sub: kpis.snapshotDate ? `snapshot ${shortDate(kpis.snapshotDate)}` : null },
                    { span: 3, label: 'Dormant', value: fmt.number(kpis.dormantMerchants), icon: UserMinus, accent: '#f97316',
                      drillDown: '/business/attrition',
                      badge: filtered ? 'tenant-wide' : null,
                      badgeTitle: 'Tenant-wide snapshot; drawer filters do not narrow this count.',
                      sub: kpis.snapshotDate ? `snapshot ${shortDate(kpis.snapshotDate)}` : null },
                    { span: 3, label: 'Zero Sales', value: fmt.number(kpis.zeroSalesMerchants), icon: AlertCircle, accent: '#ef4444',
                      drillDown: '/business/zero-transaction', sub: `no volume ${merchantWindow}` },
                ],
            },
        ];
    }, [kpis, fmt]);

    /* ── Effective Rate & DCC sections ── */
    const revSections = useMemo(() => {
        if (!revenue) return [];
        const window = rangeLabel(revenue.startDate, revenue.endDate);
        const netTakeAvail = revenue.netTakeRateBps !== null && revenue.netTakeRateBps !== undefined;
        const eligible = Number(revenue.dccEligibleVolume) || 0;
        const missedPctOfEligible = eligible > 0
            ? ((Number(revenue.dccMissedVolume) || 0) / eligible) * 100 : null;
        return [
            {
                title: 'Effective Rate',
                caption: `${window}${revenue.filtersApplied ? ' · filtered' : ''}`,
                cards: [
                    { span: 4, label: 'Effective MSF Rate', value: fmtBps(revenue.msfRateBps), icon: Percent, accent: '#0ea5e9',
                      sub: `${fmt.currency(revenue.totalMsf)} MSF on ${fmt.currency(revenue.totalVolume)}` },
                    { span: 4, label: 'Net Take Rate', value: netTakeAvail ? fmtBps(revenue.netTakeRateBps) : '—', icon: TrendingUp, accent: '#6366f1',
                      sub: netTakeAvail
                          ? `${fmt.currency(revenue.netRevenue)} net margin after interchange, scheme fees & VAT`
                          : 'Bank-grain metric — unavailable while filters are applied' },
                    { span: 4, label: 'Average Ticket', value: fmt.currency(revenue.avgTicket), icon: Receipt, accent: '#8b5cf6',
                      sub: `${fmt.number(revenue.totalTxns)} transactions` },
                ],
            },
            {
                title: 'Dynamic Currency Conversion',
                caption: `${window} · merchant grain (card-level filters not applied)`,
                cards: [
                    { span: 3, label: 'Opt-In Rate', value: fmtPct(revenue.dccOptinRatePct), icon: ShieldCheck, accent: '#10b981',
                      bar: revenue.dccOptinRatePct,
                      sub: `${fmt.currency(revenue.dccOptinVolume)} of ${fmt.currency(revenue.dccEligibleVolume)} eligible` },
                    { span: 3, label: 'DCC Penetration', value: fmtPct(revenue.dccPenetrationPct), icon: Globe, accent: '#0891b2',
                      bar: revenue.dccPenetrationPct,
                      sub: `eligible share of ${fmt.currency(revenue.dccSourceBaseVolume)} total` },
                    { span: 3, label: 'Opted-In Volume', value: fmt.currency(revenue.dccOptinVolume), icon: DollarSign, accent: '#059669',
                      sub: `${fmt.number(revenue.dccOptinCount)} of ${fmt.number(revenue.dccEligibleCount)} eligible txns` },
                    { span: 3, label: 'Missed DCC Volume', value: fmt.currency(revenue.dccMissedVolume), icon: ShieldAlert, accent: '#f59e0b',
                      sub: missedPctOfEligible !== null
                          ? `${missedPctOfEligible.toFixed(1)}% of eligible left unconverted`
                          : 'eligible but not opted-in' },
                ],
            },
        ];
    }, [revenue, fmt]);

    return (
        <div style={{ background: 'var(--bg)', minHeight: '100vh' }}>
            {/* ═══ Header ═══ */}
            <div style={{
                padding: '18px 28px', background: 'var(--bg-card)', borderBottom: '1px solid var(--border)',
                display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                position: 'sticky', top: 0, zIndex: 10, gap: 12, flexWrap: 'wrap',
            }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <div style={{
                        width: 38, height: 38, borderRadius: 10,
                        border: '1px solid var(--border)', background: 'var(--bg-subtle)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                    }}>
                        <LayoutGrid size={18} color="var(--brand, #3b82f6)" />
                    </div>
                    <div>
                        <h1 style={{ margin: 0, fontSize: '1.15rem', fontWeight: 700, color: 'var(--text)', letterSpacing: '-0.02em' }}>
                            Business Dashboard
                        </h1>
                        <p style={{ margin: '2px 0 0', fontSize: '0.8rem', color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums' }}>
                            {kpis?.effectiveDate ? `Data through ${shortDate(kpis.effectiveDate, true)}` : 'Merchant portfolio overview'}
                        </p>
                    </div>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <button onClick={() => setFilterOpen(true)} style={{
                        display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px',
                        background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10, cursor: 'pointer',
                        color: 'var(--text-secondary)', fontSize: 13, fontWeight: 600, fontFamily: 'inherit',
                    }}>
                        <Filter size={15} /> Filters
                        {chips.length > 0 && (
                            <span style={{
                                minWidth: 18, height: 18, borderRadius: 999, padding: '0 5px',
                                background: 'var(--brand, #3b82f6)', color: '#fff',
                                fontSize: '0.68rem', fontWeight: 700,
                                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                            }}>
                                {chips.length}
                            </span>
                        )}
                    </button>
                    <button onClick={() => fetchKpis()} title="Refresh" style={{
                        padding: 9, background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10,
                        cursor: 'pointer', display: 'flex', alignItems: 'center',
                    }}>
                        <RefreshCw size={15} color="var(--text-secondary)" className={loading ? 'spin' : ''} />
                    </button>
                    <button onClick={() => navigate('/business/executive-dashboard-v2')} style={{
                        display: 'flex', alignItems: 'center', gap: 6, padding: '8px 16px',
                        background: 'var(--text)', color: 'var(--bg-card)',
                        border: 'none', borderRadius: 10, cursor: 'pointer', fontSize: 12, fontWeight: 700, fontFamily: 'inherit',
                    }}>
                        <TrendingUp size={14} /> Executive View
                    </button>
                </div>
            </div>

            <div style={{ padding: '20px 28px 40px' }}>
                <BusinessFilters
                    filters={filters}
                    onChange={setFilters}
                    onApply={fetchKpis}
                    isOpen={filterOpen}
                    onClose={() => setFilterOpen(false)}
                />

                {/* Applied-filter chip bar — every applied filter is visible and
                    individually removable, so it is always clear what scope the
                    numbers below are measured over. */}
                {chips.length > 0 && (
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', marginBottom: 18 }}>
                        <span style={{ fontSize: '0.72rem', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                            Filtered by
                        </span>
                        {chips.map(chip => (
                            <span key={chip.key} style={{
                                display: 'inline-flex', alignItems: 'center', gap: 6,
                                padding: '4px 8px 4px 10px', borderRadius: 999,
                                border: '1px solid var(--border)', background: 'var(--bg-card)',
                                fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-secondary)',
                            }}>
                                {chip.label}
                                <X size={12} style={{ cursor: 'pointer', color: 'var(--text-muted)' }} onClick={chip.onRemove} />
                            </span>
                        ))}
                        <button onClick={clearAll} style={{
                            border: 'none', background: 'transparent', cursor: 'pointer',
                            fontSize: '0.75rem', fontWeight: 700, color: 'var(--brand, #3b82f6)', fontFamily: 'inherit',
                        }}>
                            Clear all
                        </button>
                    </div>
                )}

                {loading ? (
                    <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
                        <Loader />
                    </div>
                ) : (
                    <>
                        {[...sections, ...revSections].map((section) => (
                            <div key={section.title} style={{ marginBottom: 26 }}>
                                <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 12 }}>
                                    <h2 style={{
                                        margin: 0, fontSize: '0.78rem', fontWeight: 700,
                                        color: 'var(--text-secondary)', textTransform: 'uppercase',
                                        letterSpacing: '0.06em', whiteSpace: 'nowrap',
                                    }}>
                                        {section.title}
                                    </h2>
                                    <div style={{ flex: 1, height: 1, background: 'var(--border)' }} />
                                    {section.caption && (
                                        <span style={{ fontSize: '0.72rem', color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                                            {section.caption}
                                        </span>
                                    )}
                                </div>
                                <div className="bd-cards-grid">
                                    {section.cards.map((card) => (
                                        <KpiTile key={card.label} card={card} navigate={navigate} />
                                    ))}
                                </div>
                            </div>
                        ))}

                        {/* Quick Links */}
                        <div style={{
                            background: 'var(--bg-card)', borderRadius: 14, border: '1px solid var(--border)',
                            padding: '20px 22px',
                        }}>
                            <h3 style={{ margin: '0 0 14px', fontSize: '0.9rem', fontWeight: 700, color: 'var(--text)' }}>
                                Quick Actions
                            </h3>
                            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 10 }}>
                                {[
                                    { label: 'Volume & Revenue Summary', path: '/business/volume-revenue' },
                                    { label: 'Transaction Performance', path: '/business/performance' },
                                    { label: 'Merchant Analytics', path: '/business/merchant-analytics' },
                                    { label: 'Attrition Report', path: '/business/attrition' },
                                ].map(link => (
                                    <button key={link.path} onClick={() => navigate(link.path)} style={{
                                        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                                        padding: '13px 15px', background: 'var(--bg-subtle)', border: '1px solid var(--border)',
                                        borderRadius: 10, cursor: 'pointer', width: '100%', textAlign: 'left',
                                        fontFamily: 'inherit', transition: 'border-color 0.15s, background 0.15s',
                                    }}
                                    onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--brand, #3b82f6)'; e.currentTarget.style.background = 'var(--bg-card)'; }}
                                    onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.background = 'var(--bg-subtle)'; }}
                                    >
                                        <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text)' }}>{link.label}</span>
                                        <ArrowUpRight size={14} color="var(--text-muted)" />
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
                .bd-cards-grid {
                    display: grid;
                    grid-template-columns: repeat(12, 1fr);
                    gap: 12px;
                }
                @media (max-width: 900px) {
                    .bd-cards-grid { grid-template-columns: repeat(2, 1fr); }
                    .bd-cards-grid > .bd-card { grid-column: span 1 !important; }
                }
                @media (max-width: 560px) {
                    .bd-cards-grid { grid-template-columns: 1fr; }
                    .bd-cards-grid > .bd-card { grid-column: span 1 !important; }
                }
                @media (prefers-reduced-motion: reduce) {
                    .bd-card { transition: none !important; }
                    .spin { animation: none; }
                }
            `}</style>
        </div>
    );
};

export default BusinessDashboard;
