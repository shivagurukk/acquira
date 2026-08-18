// ─── Shared Chart Configuration ──────────────────────────────────────
// Standardizes Recharts styling across all dashboard and report pages.
// Colour comes from theme/chartPalette so this file stays a styling
// convenience layer rather than a second palette.

import { CATEGORICAL, TOOLTIP_PROPS, prefersReducedMotion } from '../theme/chartPalette';

export const CHART_COLORS = CATEGORICAL;

// Tooltip container style
export const chartTooltipStyle = TOOLTIP_PROPS.contentStyle;

// Tooltip label style
export const chartTooltipLabelStyle = {
    color: 'var(--text-muted)',
    fontWeight: 600,
    marginBottom: 4,
    fontSize: '11px',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
};

// Shared CartesianGrid props
export const chartGridProps = {
    strokeDasharray: '3 6',
    stroke: 'var(--chart-grid)',
    vertical: false,
};

// Shared axis tick style
export const chartAxisTick = {
    fontSize: 11,
    fill: 'var(--chart-axis)',
    fontFamily: 'var(--font-mono)',
};

// Shared axis props
export const chartAxisProps = {
    axisLine: false,
    tickLine: false,
    tick: chartAxisTick,
};

// Animated border sweep shared by every card surface. Two-layer
// background: opaque card fill over a conic border ring whose angle
// (--dxa, registered in index.css) is animated by dxBorderSweep.
export const cardSweep = {
    background: `        radial-gradient(140% 90% at 50% 0%,
          color-mix(in srgb, var(--primary) var(--dxg, 6%), transparent) 0%,
          transparent 60%) padding-box,
        var(--dx-card-grid),
        conic-gradient(from var(--dxa),
          var(--border) 0deg, var(--border) 280deg,
          color-mix(in srgb, var(--primary) 40%, var(--border)) 310deg,
          var(--primary) 332deg,
          var(--border) 352deg) border-box`,
    border: '2px solid transparent',
    // Inline styles cannot carry a media query, so the reduced-motion
    // guard happens here instead of in CSS.
    animation: prefersReducedMotion() ? 'none' : 'dxBorderSweep 6s linear infinite, dxGridPulse 5s ease-in-out infinite',
};

// Card wrapper style for chart sections
export const chartCardStyle = {
    ...cardSweep,
    borderRadius: 'var(--radius-lg)',
    padding: '24px',
    boxShadow: 'var(--shadow-card)',
};

// Compact number formatter for axes
export const compactAxisFormatter = (val) => {
    if (val == null) return '';
    const abs = Math.abs(val);
    if (abs >= 1_000_000) return `${(val / 1_000_000).toFixed(1)}M`;
    if (abs >= 1_000)     return `${(val / 1_000).toFixed(0)}K`;
    return val.toLocaleString();
};

// ── Chart Header Component ─────────────────────────────────────────────
export const ChartHeader = ({ title, subtitle, onTitleClick, legends = [], extra }) => (
    <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        marginBottom: '20px',
        gap: 8,
    }}>
        <div>
            <h3
                style={{
                    fontSize: '0.95rem',
                    fontWeight: 650,
                    margin: 0,
                    color: 'var(--text, #111827)',
                    cursor: onTitleClick ? 'pointer' : 'default',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '5px',
                    letterSpacing: '-0.02em',
                    lineHeight: 1.3,
                }}
                onClick={onTitleClick}
            >
                {title}
                {onTitleClick && (
                    <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                        <path d="M2 6h8M6 2l4 4-4 4" stroke="var(--primary)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                )}
            </h3>
            {subtitle && (
                <p style={{
                    fontSize: '0.78rem',
                    color: 'var(--text-muted, #9ca3af)',
                    margin: '3px 0 0',
                    lineHeight: 1.4,
                }}>
                    {subtitle}
                </p>
            )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexShrink: 0 }}>
            {legends.length > 0 && (
                <div style={{
                    display: 'flex',
                    gap: '14px',
                    fontSize: '0.72rem',
                    color: 'var(--text-muted, #9ca3af)',
                    fontWeight: 500,
                }}>
                    {legends.map((leg, i) => (
                        <span key={i} style={{ display: 'flex', alignItems: 'center', gap: '5px' }}>
                            <span style={{
                                width: 8, height: 8, borderRadius: '50%',
                                background: leg.color,
                                flexShrink: 0,
                            }} />
                            {leg.label}
                        </span>
                    ))}
                </div>
            )}
            {extra}
        </div>
    </div>
);
