import React from 'react';
import { TrendingUp, TrendingDown, ArrowRight } from 'lucide-react';

/* ─── Inline Sparkline ────────────────────────────────────────────
   Seats as a full-width footer band inside the card. A soft area fill,
   a hairline baseline, and a terminal dot make it read as an intentional
   micro-chart rather than a corner decoration. Colour is driven by the
   card's accent so it stays on-token in both light and dark themes. */
const Sparkline = ({ data = [], color = 'var(--brand, #2563eb)', width = 140, height = 36 }) => {
    if (!data || data.length < 2) return null;
    const W = width, H = height;
    const pad = 3;                          // keep stroke off the top/bottom edge
    const max = Math.max(...data), min = Math.min(...data);
    const range = max - min || 1;
    const stepX = W / (data.length - 1);
    const yOf = (v) => H - pad - ((v - min) / range) * (H - pad * 2);
    const pts = data.map((v, i) => [i * stepX, yOf(v)]);
    const polyline = pts.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
    const area = `0,${H} ${polyline} ${W},${H}`;
    const [lastX, lastY] = pts[pts.length - 1];
    // Deterministic gradient id per colour so multiple cards don't collide.
    const gid = `spark-${String(color).replace(/[^a-z0-9]/gi, '')}`;
    return (
        <svg width="100%" height={H} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none"
            style={{ display: 'block' }} aria-hidden="true">
            <defs>
                <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity="0.14" />
                    <stop offset="100%" stopColor={color} stopOpacity="0" />
                </linearGradient>
            </defs>
            <polygon points={area} fill={`url(#${gid})`} />
            <polyline points={polyline} fill="none" stroke={color} strokeWidth="1.5"
                strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
            <circle cx={lastX} cy={lastY} r="2.4" fill={color} />
        </svg>
    );
};

/* ─── Trend Pill ──────────────────────────────────────────────── */
const TrendPill = ({ value, isPositive, isNeutral, rose }) => {
    if (isNeutral || value === null || value === undefined) return null;
    const bg    = isPositive ? 'var(--success-bg, #ecfdf5)' : 'var(--danger-bg, #fef2f2)';
    const color = isPositive ? 'var(--success, #059669)' : 'var(--danger, #dc2626)';
    // `rose` (did the value go up) drives the ARROW direction; `isPositive`
    // drives the good/bad COLOR. They diverge for inverted metrics — e.g.
    // fewer leakage alerts is good even though the number went down.
    const roseDir = rose === undefined ? isPositive : rose;
    const Icon  = roseDir ? TrendingUp : TrendingDown;
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 3,
            padding: '3px 8px', borderRadius: '8px',
            fontSize: '11px', fontWeight: 600,
            fontVariantNumeric: 'tabular-nums',
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
    title, value, subtitle, trend, trendLabel, invertTrend = false,
    icon: Icon, color = 'var(--brand, #2563eb)', sparkData,
    loading, onClick,
}) => {
    const rose       = Number(trend) > 0;          // did the value move up?
    const isPositive = invertTrend ? !rose : rose; // is that movement good?
    const isNeutral  = !trend || Number(trend) === 0;
    const clickable  = typeof onClick === 'function';
    const hasSpark   = sparkData?.length > 1;

    if (loading) {
        return (
            <div style={{
                background: 'var(--bg-card, #fff)',
                border: '1px solid var(--border, #e5e7eb)',
                borderRadius: 'var(--radius-lg, 14px)',
                padding: '20px 22px',
            }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
                    <Pulse w={40} h={40} r={12} />
                    <Pulse w={48} h={22} r={8} />
                </div>
                <Pulse w="55%" h={28} r={6} />
                <div style={{ marginTop: 10 }}><Pulse w="40%" h={14} r={4} /></div>
                <div style={{ marginTop: 18 }}><Pulse w="100%" h={36} r={6} /></div>
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
                display: 'flex',
                flexDirection: 'column',
                background: 'var(--bg-card, #fff)',
                border: '1px solid var(--border, #e5e7eb)',
                borderRadius: 'var(--radius-lg, 14px)',
                padding: '20px 22px 0',
                cursor: clickable ? 'pointer' : 'default',
                overflow: 'hidden',
                transition: 'box-shadow 0.2s ease, transform 0.2s ease, border-color 0.2s ease',
            }}
            onMouseEnter={e => {
                e.currentTarget.style.boxShadow = 'var(--shadow-hover, 0 6px 20px rgba(15,23,42,0.08))';
                e.currentTarget.style.transform = 'translateY(-2px)';
                e.currentTarget.style.borderColor = 'var(--border-strong, #d1d5db)';
            }}
            onMouseLeave={e => {
                e.currentTarget.style.boxShadow = 'none';
                e.currentTarget.style.transform = '';
                e.currentTarget.style.borderColor = 'var(--border, #e5e7eb)';
            }}
            onKeyDown={e => { if (clickable && (e.key === 'Enter' || e.key === ' ')) onClick(); }}
        >
            {/* ── Top: icon + trend ── */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
                {Icon ? (
                    <div style={{
                        width: 40, height: 40, borderRadius: 12,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        background: `color-mix(in srgb, ${color} 8%, transparent)`,
                        border: `1px solid color-mix(in srgb, ${color} 16%, transparent)`,
                    }}>
                        <Icon size={18} color={color} strokeWidth={1.8} />
                    </div>
                ) : <div />}
                <TrendPill value={trend} isPositive={isPositive} isNeutral={isNeutral} rose={rose} />
            </div>

            {/* ── Value ── */}
            <div style={{
                fontSize: '1.5rem', fontWeight: 700,
                letterSpacing: '-0.03em', lineHeight: 1,
                fontVariantNumeric: 'tabular-nums',
                color: 'var(--text, #111827)',
                marginBottom: 8,
            }}>
                {value ?? '—'}
            </div>

            {/* ── Label (+ optional trend label) ── */}
            <div style={{
                fontSize: '0.8rem', fontWeight: 500,
                color: 'var(--text-muted, #9ca3af)',
                display: 'flex', alignItems: 'center', gap: 4,
            }}>
                {title}
                {clickable && <ArrowRight size={12} color="var(--brand, #2563eb)" />}
            </div>

            {subtitle && (
                <div style={{ marginTop: 4, fontSize: '0.72rem', color: 'var(--text-muted, #9ca3af)' }}>
                    {subtitle}
                </div>
            )}

            {/* ── Footer: full-width sparkline band ──
                Fills the card base with a real micro-chart. When there's no
                series, a spacer keeps card heights aligned across the row. */}
            <div style={{ marginTop: 18, marginLeft: -22, marginRight: -22 }}>
                {hasSpark ? (
                    <div style={{
                        borderTop: '1px solid var(--border-light, #f3f4f6)',
                        padding: '8px 14px 10px',
                    }}>
                        {trendLabel && (
                            <div style={{
                                fontSize: '0.66rem', fontWeight: 500, letterSpacing: '0.02em',
                                textTransform: 'uppercase', color: 'var(--text-muted, #9ca3af)',
                                marginBottom: 4,
                            }}>
                                {trendLabel}
                            </div>
                        )}
                        <Sparkline data={sparkData} color={color} />
                    </div>
                ) : (
                    <div style={{ height: 20 }} />
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
                @keyframes kpiPulse { 0%,100% { opacity:1; } 50% { opacity:0.5; } }
            `}</style>
            <div style={{
                display: 'grid',
                gridTemplateColumns: `repeat(auto-fit, minmax(min(220px, 100%), 1fr))`,
                gap: '16px',
                marginBottom: '28px',
            }}>
                {loading
                    ? Array.from({ length: count }).map((_, i) => <KpiCard key={i} loading />)
                    : cards.map((card, i) => (
                        <KpiCard key={i} {...card}
                            onClick={card.drillDown ? card.drillDown : undefined}
                        />
                    ))
                }
            </div>
        </>
    );
};

export default KpiCards;
export { Sparkline };
