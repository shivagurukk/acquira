import React from 'react';
import { TrendingUp, TrendingDown, Minus, ArrowRight } from 'lucide-react';

/* ─── Micro Sparkline ─────────────────────────────────────────── */
const Sparkline = ({ data = [], color = '#2563eb' }) => {
    if (!data || data.length < 2) return null;
    const W = 72, H = 28;
    const max = Math.max(...data), min = Math.min(...data);
    const range = max - min || 1;
    const pts = data.map((v, i) => {
        const x = (i / (data.length - 1)) * W;
        const y = H - ((v - min) / range) * (H - 4) - 2;
        return `${x},${y}`;
    });
    const polyline = pts.join(' ');
    const area = `0,${H} ${polyline} ${W},${H}`;
    return (
        <svg width={W} height={H} style={{ display: 'block', overflow: 'visible' }}>
            <defs>
                <linearGradient id={`g${color.slice(1)}`} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity="0.12" />
                    <stop offset="100%" stopColor={color} stopOpacity="0" />
                </linearGradient>
            </defs>
            <polygon points={area} fill={`url(#g${color.slice(1)})`} />
            <polyline points={polyline} fill="none" stroke={color} strokeWidth="1.5"
                strokeLinecap="round" strokeLinejoin="round" />
        </svg>
    );
};

/* ─── Trend Pill ──────────────────────────────────────────────── */
const TrendPill = ({ value, isPositive, isNeutral }) => {
    if (isNeutral || value === null || value === undefined) return null;
    const bg    = isPositive ? 'var(--success-bg, #ecfdf5)' : 'var(--danger-bg, #fef2f2)';
    const color = isPositive ? '#059669' : '#dc2626';
    const Icon  = isPositive ? TrendingUp : TrendingDown;
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 3,
            padding: '3px 8px', borderRadius: '8px',
            fontSize: '11px', fontWeight: 600,
            background: bg, color,
        }}>
            <Icon size={10} strokeWidth={2.5} />
            {Math.abs(Number(value)).toFixed(1)}%
        </span>
    );
};

/* ─── Skeleton Pulse ──────────────────────────────────────────── */
const Pulse = ({ w = '100%', h = 16, r = 6 }) => (
    <div style={{
        width: w, height: h, borderRadius: r,
        background: 'var(--bg-subtle, #f3f4f6)',
        animation: 'kpiPulse 1.5s ease-in-out infinite',
    }} />
);

/* ─── Single KPI Card ─────────────────────────────────────────── */
export const KpiCard = ({
    title, value, subtitle, trend, trendLabel,
    icon: Icon, color = '#2563eb', sparkData,
    loading, onClick,
}) => {
    const isPositive = Number(trend) > 0;
    const isNeutral  = !trend || Number(trend) === 0;
    const clickable  = typeof onClick === 'function';

    if (loading) {
        return (
            <div style={{
                background: 'var(--bg-card, #fff)',
                border: '1px solid var(--border, #e5e7eb)',
                borderRadius: 'var(--radius-lg, 14px)',
                padding: '24px',
            }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
                    <Pulse w={40} h={40} r={12} />
                    <Pulse w={48} h={22} r={8} />
                </div>
                <Pulse w="55%" h={28} r={6} />
                <div style={{ marginTop: 10 }}><Pulse w="40%" h={14} r={4} /></div>
            </div>
        );
    }

    return (
        <div
            onClick={onClick}
            role={clickable ? 'button' : undefined}
            tabIndex={clickable ? 0 : undefined}
            style={{
                position: 'relative',
                background: 'var(--bg-card, #fff)',
                border: '1px solid var(--border, #e5e7eb)',
                borderRadius: 'var(--radius-lg, 14px)',
                padding: '24px',
                cursor: clickable ? 'pointer' : 'default',
                overflow: 'hidden',
                transition: 'all 0.2s ease',
            }}
            onMouseEnter={e => {
                if (clickable) {
                    e.currentTarget.style.boxShadow = 'var(--shadow-hover)';
                    e.currentTarget.style.transform = 'translateY(-2px)';
                    e.currentTarget.style.borderColor = 'var(--border, #d1d5db)';
                }
            }}
            onMouseLeave={e => {
                e.currentTarget.style.boxShadow = 'none';
                e.currentTarget.style.transform = '';
                e.currentTarget.style.borderColor = 'var(--border, #e5e7eb)';
            }}
            onKeyDown={e => { if (clickable && (e.key === 'Enter' || e.key === ' ')) onClick(); }}
        >
            {/* Sparkline backdrop */}
            {sparkData?.length > 1 && (
                <div style={{ position: 'absolute', bottom: 6, right: 8, opacity: 0.4 }}>
                    <Sparkline data={sparkData} color={color} />
                </div>
            )}

            <div style={{ position: 'relative', zIndex: 1 }}>
                {/* Icon + trend */}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
                    {Icon ? (
                        <div style={{
                            width: 40, height: 40, borderRadius: 12,
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            background: `${color}08`,
                            border: `1px solid ${color}15`,
                        }}>
                            <Icon size={18} color={color} strokeWidth={1.8} />
                        </div>
                    ) : <div />}
                    <TrendPill value={trend} isPositive={isPositive} isNeutral={isNeutral} />
                </div>

                {/* Value */}
                <div style={{
                    fontSize: '1.5rem', fontWeight: 700,
                    letterSpacing: '-0.03em', lineHeight: 1,
                    color: 'var(--text, #111827)',
                    marginBottom: 8,
                }}>
                    {value ?? '—'}
                </div>

                {/* Label */}
                <div style={{
                    fontSize: '0.8rem', fontWeight: 500,
                    color: 'var(--text-muted, #9ca3af)',
                    display: 'flex', alignItems: 'center', gap: 4,
                }}>
                    {title}
                    {clickable && <ArrowRight size={12} color="var(--brand, #2563eb)" />}
                </div>

                {subtitle && (
                    <div style={{
                        marginTop: 4,
                        fontSize: '0.72rem',
                        color: 'var(--text-muted, #9ca3af)',
                    }}>
                        {subtitle}
                    </div>
                )}
            </div>
        </div>
    );
};

/* ─── KPI Row ─────────────────────────────────────────────────── */
const KpiCards = ({ cards = [], loading, cols }) => {
    const count = cards.length || 4;

    return (
        <>
            <style>{`
                @keyframes kpiPulse {
                    0%,100% { opacity:1; }
                    50% { opacity:0.5; }
                }
            `}</style>
            <div style={{
                display: 'grid',
                gridTemplateColumns: `repeat(auto-fit, minmax(min(200px, 100%), 1fr))`,
                gap: '16px',
                marginBottom: '28px',
            }}>
                {loading
                    ? Array.from({ length: count }).map((_, i) => <KpiCard key={i} loading />)
                    : cards.map((card, i) => (
                        <KpiCard key={i} {...card}
                            onClick={card.drillDown ? card.drillDown : undefined}
                            style={{ animation: `staggerIn 0.35s ease-out both`, animationDelay: `${i * 50}ms` }}
                        />
                    ))
                }
            </div>
        </>
    );
};

export default KpiCards;
export { Sparkline };
