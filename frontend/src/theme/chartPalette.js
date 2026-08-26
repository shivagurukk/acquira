/**
 * Chart palette — the single source of colour for every Recharts surface.
 *
 * Before this file each dashboard carried its own hex map, so the charts
 * drifted apart and several pages did not react to the theme at all. Every
 * chart should now pull series colours, axis/grid styling and tooltip
 * styling from here.
 *
 * Two flavours are exported because Recharts needs both:
 *   - `CHART_VARS` / `SERIES` — `var(--token)` strings. Preferred: they flip
 *     with the theme automatically, no re-render required.
 *   - `RAMP_HEX` — literal hexes keyed by mode, for the few places that need
 *     a real colour (canvas painting, colour maths, gradient stop opacity
 *     that must be computed, libraries that cannot resolve custom properties).
 */

/* ── Sequential steel ramp, dark → light (magnitude encoding) ── */
export const CHART_VARS = [
    'var(--chart-1)',
    'var(--chart-2)',
    'var(--chart-3)',
    'var(--chart-4)',
    'var(--chart-5)',
];

/* Categorical series — CVD-validated fixed order (steel, copper, jade,
   brass, plum), then the neutral slate + ramp fringes for overflow.
   Never cycle past this list: fold extra series into "Other". */
export const CATEGORICAL = [
    'var(--cat-1)',
    'var(--cat-2)',
    'var(--cat-3)',
    'var(--cat-4)',
    'var(--cat-5)',
    'var(--chart-alt)',
    'var(--chart-1)',
    'var(--chart-5)',
];

/* Named roles used across the dashboards. Steel is the hero series
   (volume / net revenue); overlay lines ride graphite so the accent
   stays scarce; fee components take the categorical slots. */
export const SERIES = {
    volume:      'var(--chart-3)',
    /* Cost & Margin Mix stack — one hue per segment (user's call) so each
       cost keeps its own identity and margin reads instantly as "kept":
       margin jade-green, interchange the deep imperial it always wore,
       scheme fee a lighter sky blue, PG fee copper. Tokens are the
       --mix-* set in index.css, validated per mode as a categorical
       palette; each segment body is a soft vertical gradient of its hue. */
    netRevenue:  'var(--mix-margin)',
    marginPct:   'var(--chart-line)',
    interchange: 'var(--mix-interchange)',
    schemeFee:   'var(--mix-scheme)',
    ecomFee:     'var(--mix-pg)',
    other:       'var(--chart-5)',
    forecast:    'var(--chart-alt)',
    benchmark:   'var(--projected)',
    positive:    'var(--success)',
    negative:    'var(--danger)',
    attention:   'var(--warning)',
};

export const RAMP_HEX = {
    light: ['#193A70', '#244E87', '#355FA8', '#7FA6D8', '#C8D8ED'],
    dark:  ['#33518F', '#4A6DC0', '#5E82D2', '#8AA5E0', '#C3D1F0'],
};

/* ── Shared Recharts props ─────────────────────────────────────── */
export const GRID_PROPS = {
    stroke: 'var(--chart-grid)',
    strokeDasharray: '3 6',
    vertical: false,
};

export const AXIS_PROPS = {
    stroke: 'transparent',
    tick: { fill: 'var(--chart-axis)', fontSize: 11, fontFamily: 'var(--font-mono)' },
    tickLine: false,
    axisLine: false,
};

export const TOOLTIP_PROPS = {
    cursor: { fill: 'color-mix(in srgb, var(--primary) 6%, transparent)' },
    contentStyle: {
        background: 'var(--glass-bg)',
        backdropFilter: 'saturate(180%) blur(12px)',
        WebkitBackdropFilter: 'saturate(180%) blur(12px)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
        boxShadow: 'var(--shadow-pop)',
        fontSize: 12,
        fontFamily: 'var(--font-ui)',
        color: 'var(--text)',
        padding: '8px 12px',
    },
    labelStyle: {
        color: 'var(--text-secondary)',
        fontSize: 11,
        fontWeight: 600,
        letterSpacing: '0.02em',
        marginBottom: 4,
    },
    itemStyle: { fontFamily: 'var(--font-mono)', fontSize: 12 },
};

export const LEGEND_PROPS = {
    iconType: 'circle',
    iconSize: 8,
    wrapperStyle: {
        fontSize: 12,
        fontFamily: 'var(--font-ui)',
        color: 'var(--text-secondary)',
        paddingTop: 8,
    },
};

/* Motion — charts animate in once, quickly, and not at all when the user
   has asked for reduced motion. */
export const prefersReducedMotion = () =>
    typeof window !== 'undefined' &&
    !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

export const ANIM = () => ({
    isAnimationActive: !prefersReducedMotion(),
    animationDuration: 700,
    animationEasing: 'ease-out',
});

/**
 * Gradient <defs> for area/bar fills. Render `<ChartGradients ids={...} />`
 * inside a chart and reference the stops with `fill="url(#id)"`.
 */
export const gradientId = (key) => `dxGrad-${key}`;

export const seriesColor = (key, index = 0) =>
    SERIES[key] || CATEGORICAL[index % CATEGORICAL.length];
