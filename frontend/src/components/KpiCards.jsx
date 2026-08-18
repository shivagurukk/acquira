import React from 'react';
import BenchmarkRail from './BenchmarkRail';

/* ─── Inline Sparkline ────────────────────────────────────────────
   A micro-chart in the card footer: gradient area fill, hairline
   stroke, haloed terminal dot. Colour comes from the card's token so
   it stays on-token in both colour schemes. */
const Sparkline = ({ data = [], color = 'var(--chart-2)', width = 140, height = 36 }) => {
    // The colour is a `var(--token)` string, so the gradient needs its own
    // unique id rather than one derived from the colour.
    const gid = `kpi-spark-${React.useId().replace(/:/g, '')}`;
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
    return (
        <svg width="100%" height={H} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none"
            style={{ display: 'block' }} aria-hidden="true">
            <defs>
                <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={color} stopOpacity="0.28" />
                    <stop offset="100%" stopColor={color} stopOpacity="0" />
                </linearGradient>
            </defs>
            <polygon points={area} fill={`url(#${gid})`} />
            <polyline points={polyline} fill="none" stroke={color} strokeWidth="1.5"
                strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
            <circle cx={lastX} cy={lastY} r="4.5" fill={color} opacity="0.2" />
            <circle cx={lastX} cy={lastY} r="2.4" fill={color} />
        </svg>
    );
};

/* ─── Delta ────────────────────────────────────────────────────────
   Up = teal, down = burnt sienna, flat = muted. Colour is always
   paired with a glyph (▲ ▼ —) so meaning survives colourblindness
   and greyscale printing. Mono face, tabular figures. */
const Delta = ({ value, isPositive, isNeutral, rose }) => {
    if (value === null || value === undefined) return null;
    // `rose` (did the value go up) drives the GLYPH; `isPositive`
    // drives the good/bad COLOUR. They diverge for inverted metrics —
    // e.g. fewer leakage alerts is good even though the number fell.
    const roseDir = rose === undefined ? isPositive : rose;
    const glyph = isNeutral ? '—' : roseDir ? '▲' : '▼';
    const color = isNeutral
        ? 'var(--text-muted)'
        : isPositive ? 'var(--success)' : 'var(--danger)';
    const bg = isNeutral
        ? 'var(--bg-subtle)'
        : isPositive ? 'var(--success-bg)' : 'var(--danger-bg)';
    return (
        <span className="num" style={{
            display: 'inline-flex', alignItems: 'center', gap: 4,
            padding: '2px 6px', borderRadius: 'var(--radius-chip)',
            fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
            fontSize: '11px', fontWeight: 500,
            background: bg, color,
        }}>
            <span className={isNeutral ? undefined : roseDir ? 'dx-arrow-up' : 'dx-arrow-down'}>
                {glyph}
            </span>
            {isNeutral ? '' : `${Math.abs(Number(value)).toFixed(1)}%`}
        </span>
    );
};

/* ─── Skeleton Pulse ──────────────────────────────────────────── */
const Pulse = ({ w = '100%', h = 14, r = 6 }) => (
    <div className="dx-shimmer" style={{ width: w, height: h, borderRadius: r }} />
);

/* ─── Single KPI Card ─────────────────────────────────────────────
   Hairline border, 20px padding, no drop shadow, no hover lift.
   Icons are a sidebar-only affordance — none here. `benchmark`
   ({ percentile, benchmarkLabel }) renders a BenchmarkRail under the
   value; pass it only where a real benchmark exists. */
export const KpiCard = ({
    title, value, subtitle, trend, trendLabel, invertTrend = false,
    color = 'var(--chart-2)', sparkData, benchmark,
    loading, onClick,
}) => {
    const rose       = Number(trend) > 0;          // did the value move up?
    const isPositive = invertTrend ? !rose : rose; // is that movement good?
    const isNeutral  = !trend || Number(trend) === 0;
    const clickable  = typeof onClick === 'function';
    const hasSpark   = sparkData?.length > 1;

    if (loading) {
        return (
            <div className="dx-card" style={{ padding: '20px' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 14 }}>
                    <Pulse w="45%" h={13} />
                    <Pulse w={44} h={18} />
                </div>
                <Pulse w="55%" h={24} />
                <div style={{ marginTop: 14 }}><Pulse w="100%" h={36} /></div>
            </div>
        );
    }

    return (
        <div
            className="dx-card"
            onClick={onClick}
            role={clickable ? 'button' : undefined}
            tabIndex={clickable ? 0 : undefined}
            style={{
                position: 'relative',
                display: 'flex',
                flexDirection: 'column',
                // flex/minWidth are for the .dx-rise wrapper the row puts
                // around each card; harmless when rendered standalone.
                flex: 1, minWidth: 0,
                padding: '20px 20px 0',
                cursor: clickable ? 'pointer' : 'default',
                overflow: 'hidden',
            }}
            onMouseEnter={e => {
                if (clickable) e.currentTarget.style.background = 'var(--bg-hover)';
            }}
            // Cleared rather than reset to a literal, so the .dx-card
            // background (which differs per scheme) takes over again.
            onMouseLeave={e => { e.currentTarget.style.background = ''; }}
            onKeyDown={e => { if (clickable && (e.key === 'Enter' || e.key === ' ')) onClick(); }}
        >
            {/* Gradient accent along the card's top edge. */}
            <span aria-hidden="true" style={{
                position: 'absolute', top: 0, left: 0, right: 0, height: 2,
                background: `linear-gradient(90deg, ${color}, transparent)`,
                opacity: 0.7, pointerEvents: 'none',
            }} />
            {/* ── Top: section title + delta ── */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 8, marginBottom: 8 }}>
                <div className="section-title" style={{
                    fontSize: 13, fontWeight: 600, letterSpacing: '0.02em',
                    color: 'var(--text-muted)',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {title}
                </div>
                <Delta value={trend} isPositive={isPositive} isNeutral={isNeutral} rose={rose} />
            </div>

            {/* ── Value — mono face, tabular figures ── */}
            <div className="num" style={{
                fontSize: '20px', fontWeight: 500, lineHeight: 1.15,
                fontFamily: 'var(--font-mono)',
                fontVariantNumeric: 'tabular-nums',
                color: 'var(--text)',
                marginBottom: 4,
            }}>
                {value ?? '—'}
            </div>

            {subtitle && (
                <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                    {subtitle}
                </div>
            )}

            {/* Benchmark rail — only where a real benchmark exists. */}
            {benchmark?.percentile != null && (
                <div style={{ marginTop: 10 }}>
                    <BenchmarkRail percentile={benchmark.percentile} benchmarkLabel={benchmark.benchmarkLabel} />
                </div>
            )}

            {/* ── Footer: full-width sparkline band ── */}
            <div style={{ marginTop: 'auto', paddingTop: 12, marginLeft: -20, marginRight: -20 }}>
                {hasSpark ? (
                    <div style={{
                        borderTop: '1px solid var(--border)',
                        padding: '8px 12px 10px',
                    }}>
                        {trendLabel && (
                            <div style={{
                                fontFamily: 'var(--font-mono)',
                                fontSize: '10px', letterSpacing: '0.08em',
                                textTransform: 'uppercase', color: 'var(--text-muted)',
                                marginBottom: 4,
                            }}>
                                {trendLabel}
                            </div>
                        )}
                        <Sparkline data={sparkData} color={color} />
                    </div>
                ) : (
                    <div style={{ height: 16 }} />
                )}
            </div>
        </div>
    );
};

/* ─── KPI Row ─────────────────────────────────────────────────── */
const KpiCards = ({ cards = [], loading, cols }) => {
    const count = cards.length || 4;

    return (
        <div style={{
            display: 'grid',
            gridTemplateColumns: `repeat(auto-fit, minmax(min(220px, 100%), 1fr))`,
            gap: '12px',
            marginBottom: '20px',
        }}>
            {loading
                ? Array.from({ length: count }).map((_, i) => <KpiCard key={i} loading />)
                : cards.map((card, i) => (
                    <div key={i} className="dx-rise" style={{ animationDelay: `${i * 60}ms`, display: 'flex' }}>
                        <KpiCard {...card}
                            onClick={card.drillDown ? card.drillDown : undefined}
                        />
                    </div>
                ))
            }
        </div>
    );
};

export default KpiCards;
export { Sparkline };
