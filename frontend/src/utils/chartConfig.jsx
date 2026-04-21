// ─── Shared Chart Configuration ──────────────────────────────────────
// Standardizes Recharts styling across all dashboard and report pages.

// Brand-aligned chart color palette
export const CHART_COLORS = [
    '#2563eb', // blue
    '#059669', // emerald
    '#7c3aed', // violet
    '#d97706', // amber
    '#0891b2', // cyan
    '#dc2626', // red
    '#db2777', // pink
    '#0d9488', // teal
    '#4f46e5', // indigo
    '#65a30d', // lime
];

// Tooltip container style
export const chartTooltipStyle = {
    background: 'var(--bg-card, #fff)',
    border: '1px solid var(--border, #e5e7eb)',
    borderRadius: '10px',
    fontSize: '12px',
    color: 'var(--text, #111827)',
    boxShadow: '0 4px 12px rgba(0,0,0,0.06)',
    padding: '10px 14px',
    lineHeight: '1.6',
};

// Tooltip label style
export const chartTooltipLabelStyle = {
    color: 'var(--text-muted, #9ca3af)',
    fontWeight: 500,
    marginBottom: 4,
    fontSize: '11px',
    textTransform: 'uppercase',
    letterSpacing: '0.05em',
};

// Shared CartesianGrid props
export const chartGridProps = {
    strokeDasharray: '3 6',
    stroke: 'var(--border, #e5e7eb)',
    strokeOpacity: 0.5,
    vertical: false,
};

// Shared axis tick style
export const chartAxisTick = {
    fontSize: 11,
    fill: 'var(--text-muted, #9ca3af)',
    fontFamily: 'Inter, sans-serif',
};

// Shared axis props
export const chartAxisProps = {
    axisLine: false,
    tickLine: false,
    tick: chartAxisTick,
};

// Card wrapper style for chart sections
export const chartCardStyle = {
    background: 'var(--bg-card, #fff)',
    borderRadius: 'var(--radius-lg, 14px)',
    padding: '24px',
    border: '1px solid var(--border, #e5e7eb)',
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
                        <path d="M2 6h8M6 2l4 4-4 4" stroke="var(--brand,#2563eb)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
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
