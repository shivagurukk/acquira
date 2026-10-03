// ═══════════════════════════════════════════════════════════
// Acquira Design System — Meridian Theme
// Graphite structure · one steel accent · muted validated chart hues
//
// Token reference only. The component layer (components/ui/system) reads
// these same values as CSS custom properties declared in system.css for
// :root and html.dark; the `dark` class on <html> (toggled by
// ThemeContext) picks the scheme. Keep the two in step.
// ═══════════════════════════════════════════════════════════

// Raw token values. These mirror the --canvas/--surface/… custom
// properties in index.css — the one place hex values may live.
export const TOKENS = {
    light: {
        canvas:    '#F1F7FF',
        surface:   '#EAF1FA',
        hairline:  '#E4E7EC',
        ink:       '#14295E',
        muted:     '#51618C',
        primary:   '#3F63B0',
        wash:      '#DCE8F7',
        negative:  '#B3382C',
        attention: '#8C5E12',
        projected: '#64748B',
        success:   '#0B6B4D',
    },
    dark: {
        // Graphite dark scheme — mirrors html.dark in index.css.
        canvas:    '#0E1116',
        surface:   '#141B26',
        hairline:  '#272E38',
        ink:       '#E7EAEF',
        muted:     '#98A2AF',
        primary:   '#5E82D2',
        wash:      '#1C2637',
        negative:  '#E2705C',
        attention: '#D9A03F',
        projected: '#93A0B4',
        success:   '#34B98A',
    },
    // Sequential chart ramp — steel, dark → light.
    chartRamp:     ['#263C6E', '#33518F', '#3F63B0', '#7191CE', '#C2CFEA'],
    chartRampDark: ['#33518F', '#4A6DC0', '#5E82D2', '#8AA5E0', '#C3D1F0'],
    // Categorical series — CVD-validated fixed order (steel, copper,
    // jade, brass, plum), stepped per surface.
    categorical:     ['#3F63B0', '#CA5F28', '#0FA070', '#B08C1E', '#A85D9C'],
    categoricalDark: ['#5E82D2', '#D5763A', '#21A176', '#B48A20', '#BA65A8'],
};
