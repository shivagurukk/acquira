import { createTheme } from '@mui/material/styles';

// ═══════════════════════════════════════════════════════════
// Acquira Design System — Meridian Theme
// Graphite structure · one steel accent · muted validated chart hues
//
// Single theme with cssVariables + colorSchemes. Both schemes are
// declared here; MUI emits them as CSS custom properties scoped by
// the `dark` class on <html> (toggled by ThemeContext), so every
// component flips without a theme rebuild.
// ═══════════════════════════════════════════════════════════

// Raw token values. These mirror the --canvas/--surface/… custom
// properties in index.css — the one place hex values may live.
export const TOKENS = {
    light: {
        canvas:    '#F4F7FB',
        surface:   '#FFFFFF',
        hairline:  '#D9E2EF',
        ink:       '#102A56',
        muted:     '#66758C',
        primary:   '#2F5EA8',
        wash:      '#EAF1FB',
        negative:  '#C94B55',
        attention: '#C98A20',
        projected: '#64748B',
        success:   '#166A57',
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
    // Sequential chart ramp — NEXUS blue, dark → light.
    chartRamp:     ['#193A70', '#244E87', '#355FA8', '#7FA6D8', '#C8D8ED'],
    chartRampDark: ['#33518F', '#4A6DC0', '#5E82D2', '#8AA5E0', '#C3D1F0'],
    // Categorical series — CVD-validated fixed order (blue, amber,
    // green, light amber, purple), stepped per surface.
    categorical:     ['#2F5EA8', '#C98A20', '#1F8A70', '#D29B3D', '#8B6FB3'],
    categoricalDark: ['#5E82D2', '#DFA53E', '#21A176', '#E3B45C', '#A98FD0'],
};

const FONT_UI   = "'Public Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
const FONT_MONO = "'IBM Plex Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace";

const schemePalette = (t, mode) => ({
    mode,
    primary:   { main: t.primary, contrastText: mode === 'dark' ? t.canvas : '#FFFFFF' },
    secondary: { main: t.projected, contrastText: '#FFFFFF' },
    success:   { main: t.success },
    error:     { main: t.negative },
    warning:   { main: t.attention },
    info:      { main: t.projected },
    background: { default: t.canvas, paper: t.surface },
    text:      { primary: t.ink, secondary: t.muted, disabled: t.muted },
    divider:   t.hairline,
    action:    {
        hover: t.wash,
        selected: t.wash,
        hoverOpacity: 0.4,
    },
});

export function buildTheme() {
    return createTheme({
        // Emit both schemes as CSS variables; the `dark` class on <html>
        // (set by ThemeContext) selects the scheme.
        cssVariables: { colorSchemeSelector: 'class' },
        colorSchemes: {
            light: { palette: schemePalette(TOKENS.light, 'light') },
            dark:  { palette: schemePalette(TOKENS.dark, 'dark') },
        },
        typography: {
            // UI: Public Sans 12/13/14/16/20/28, weights 400/500/600 only.
            fontFamily: FONT_UI,
            h1: { fontSize: '28px', fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.2 },
            h2: { fontSize: '20px', fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.25 },
            h3: { fontSize: '16px', fontWeight: 600, lineHeight: 1.3 },
            h4: { fontSize: '16px', fontWeight: 600, lineHeight: 1.35 },
            h5: { fontSize: '14px', fontWeight: 600, lineHeight: 1.4 },
            // h6 doubles as the section title: sentence case, tracked, muted.
            h6: { fontSize: '13px', fontWeight: 600, letterSpacing: '0.02em', lineHeight: 1.5 },
            body1: { fontSize: '14px', lineHeight: 1.5 },
            body2: { fontSize: '13px', lineHeight: 1.5 },
            caption: { fontSize: '12px', lineHeight: 1.4 },
            button: { textTransform: 'none', fontWeight: 500, fontSize: '13px' },
            // Numeric variant — currency, counts, percentages, IDs.
            mono: { fontFamily: FONT_MONO, fontVariantNumeric: 'tabular-nums' },
        },
        shape: { borderRadius: 10 },
        transitions: {
            duration: {
                shortest: 120, shorter: 150, short: 180,
                standard: 200, complex: 260, enteringScreen: 200, leavingScreen: 180,
            },
            easing: {
                easeInOut: 'cubic-bezier(0.4, 0, 0.2, 1)',
                easeOut: 'cubic-bezier(0.22, 1, 0.36, 1)',
            },
        },
        components: {
            MuiButton: {
                defaultProps: { disableElevation: true },
                styleOverrides: {
                    root: ({ theme }) => ({
                        borderRadius: 8,
                        boxShadow: 'none',
                        padding: '7px 16px',
                        fontWeight: 500,
                        transition: 'background-color 200ms, box-shadow 200ms, transform 200ms, opacity 200ms',
                        '&:hover': { boxShadow: 'none' },
                        '&:active': { transform: 'translateY(1px)' },
                        '&:focus-visible': {
                            outline: `2px solid ${theme.vars.palette.primary.main}`,
                            outlineOffset: 2,
                        },
                    }),
                    // Primary CTA carries the brand gradient + a soft steel lift.
                    containedPrimary: {
                        backgroundImage: 'var(--grad-primary)',
                        boxShadow: '0 2px 8px rgba(47, 94, 168, 0.22)',
                        '&:hover': { boxShadow: '0 6px 18px rgba(47, 94, 168, 0.30)' },
                    },
                    sizeSmall: { padding: '5px 12px', fontSize: '12px' },
                },
            },
            MuiPaper: {
                defaultProps: { elevation: 0 },
                styleOverrides: {
                    root: ({ theme }) => ({
                        backgroundImage: 'none',
                        boxShadow: 'var(--shadow-sm)',
                        border: `1px solid ${theme.vars.palette.divider}`,
                    }),
                    rounded: { borderRadius: 12 },
                    // Menus / dialogs / popovers are the most elevated surfaces.
                    elevation8: { boxShadow: 'var(--shadow-pop)' },
                    elevation24: { boxShadow: 'var(--shadow-pop)' },
                },
            },
            MuiCard: {
                styleOverrides: {
                    root: () => ({
                        borderRadius: 12,
                        // Animated border sweep — mirrors .dx-card in index.css
                        // (--dxa registration + dxBorderSweep keyframes live there).
                        background: `                            radial-gradient(140% 90% at 50% 0%,
                              color-mix(in srgb, var(--primary) var(--dxg, 6%), transparent) 0%,
                              transparent 60%) padding-box,
                            var(--dx-card-grid),
                            conic-gradient(from var(--dxa),
                              var(--border) 0deg, var(--border) 280deg,
                              color-mix(in srgb, var(--dx-sweep, var(--primary)) 40%, var(--border)) 310deg,
                              var(--dx-sweep, var(--primary)) 332deg,
                              var(--border) 352deg) border-box`,
                        border: '2px solid transparent',
                        boxShadow: 'var(--shadow-card)',
                        animation: 'dxBorderSweep 6s linear infinite, dxGridPulse 5s ease-in-out infinite',
                        transition: 'box-shadow 220ms ease, transform 220ms ease',
                        '&:hover': {
                            boxShadow: 'var(--shadow-hover)',
                        },
                        '@media (prefers-reduced-motion: reduce)': { transition: 'none', animation: 'none' },
                    }),
                },
            },
            MuiCardContent: {
                styleOverrides: { root: { padding: 20, '&:last-child': { paddingBottom: 20 } } },
            },
            MuiDialog: {
                styleOverrides: {
                    paper: { borderRadius: 14, boxShadow: 'var(--shadow-pop)' },
                },
            },
            MuiMenu: {
                styleOverrides: {
                    paper: { borderRadius: 12, boxShadow: 'var(--shadow-pop)' },
                },
            },
            MuiPopover: {
                styleOverrides: {
                    paper: { borderRadius: 12, boxShadow: 'var(--shadow-pop)' },
                },
            },
            // Status chips: 11px mono, uppercase, 2px radius, tinted bg + solid
            // text — never solid fill with white text.
            MuiChip: {
                styleOverrides: {
                    root: {
                        borderRadius: 6,
                        fontFamily: FONT_MONO,
                        fontVariantNumeric: 'tabular-nums',
                        fontSize: '11px',
                        fontWeight: 500,
                        textTransform: 'uppercase',
                        letterSpacing: '0.02em',
                    },
                },
            },
            MuiTextField: { defaultProps: { variant: 'outlined', size: 'small' } },
            MuiToggleButton: {
                styleOverrides: {
                    root: ({ theme }) => ({
                        borderRadius: 8,
                        textTransform: 'none',
                        fontWeight: 500,
                        transition: 'background-color 200ms, color 200ms',
                        '&.Mui-selected': {
                            backgroundColor: theme.vars.palette.action.selected,
                            color: theme.vars.palette.primary.main,
                        },
                    }),
                },
            },
            // Tables: 40px rows, 12px cell padding, sticky hairlined header,
            // wash hover, no zebra.
            MuiTableCell: {
                styleOverrides: {
                    root: ({ theme }) => ({
                        padding: '0 12px',
                        height: 40,
                        borderBottom: `1px solid ${theme.vars.palette.divider}`,
                        fontSize: '13px',
                    }),
                    // Navy header bar — the one table treatment, shared with the
                    // DataGrid styles and the plain-table rules in index.css.
                    head: () => ({
                        fontSize: '12px',
                        fontWeight: 700,
                        letterSpacing: '0.03em',
                        color: 'var(--table-head-text)',
                        background: 'var(--table-head-bg)',
                        borderBottom: '2px solid var(--table-head-edge)',
                    }),
                },
            },
            MuiDataGrid: {
                styleOverrides: {
                    root: {
                        border: 'none',
                        borderRadius: 12,
                        '--DataGrid-rowBorderColor': 'var(--border)',
                        fontFamily: FONT_UI,
                        '& .MuiDataGrid-cell': {
                            borderBottom: '1px solid var(--border)',
                            fontSize: '13px',
                            padding: '0 12px',
                        },
                        '& .MuiDataGrid-cell--textRight, & .MuiDataGrid-cell[data-field] .num': {
                            fontFamily: FONT_MONO,
                            fontVariantNumeric: 'tabular-nums',
                        },
                        '& .MuiDataGrid-columnHeaders': {
                            background: 'var(--table-head-bg)',
                            borderBottom: 'none',
                            boxShadow: 'inset 0 -2px 0 var(--table-head-edge)',
                            color: 'var(--table-head-text)',
                            fontSize: '12px',
                            fontWeight: 700,
                            letterSpacing: '0.03em',
                        },
                        '& .MuiDataGrid-columnHeader': { backgroundColor: 'transparent' },
                        '& .MuiDataGrid-columnHeaderTitle': { color: 'var(--table-head-text)', fontWeight: 700 },
                        '& .MuiDataGrid-row': { backgroundColor: 'var(--table-row)' },
                        '& .MuiDataGrid-row:hover': {
                            backgroundColor: 'var(--bg-hover) !important',
                        },
                        '& .MuiDataGrid-row.Mui-selected': {
                            backgroundColor: 'var(--wash) !important',
                        },
                    },
                },
            },
            MuiTooltip: {
                styleOverrides: {
                    tooltip: ({ theme }) => ({
                        fontSize: '12px',
                        borderRadius: 8,
                        padding: '6px 10px',
                        backgroundColor: theme.vars.palette.text.primary,
                        color: theme.vars.palette.background.paper,
                    }),
                },
            },
            MuiLink: {
                styleOverrides: {
                    root: ({ theme }) => ({ color: theme.vars.palette.primary.main }),
                },
            },
        },
    });
}

// Backwards-compatible factory: callers passed 'light' | 'dark', but the
// theme now carries both schemes — the html.dark class picks the active one.
export function createAppTheme() {
    return buildTheme();
}

const theme = buildTheme();
export default theme;
