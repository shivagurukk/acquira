import { createTheme } from '@mui/material/styles';

// ═══════════════════════════════════════════════════════════
// Acquira Design System — Ledger Theme
// Dense · hairline structure · teal marks action + upward movement
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
        canvas:    '#EEF2F1',
        surface:   '#FFFFFF',
        hairline:  '#DDE4E3',
        ink:       '#101F1E',
        muted:     '#5A6B6A',
        primary:   '#12706B',
        wash:      '#E3F0EE',
        negative:  '#B4442F',
        attention: '#B5822A',
        projected: '#3E5C76',
    },
    dark: {
        canvas:    '#0C1616',
        surface:   '#132020',
        hairline:  '#1F3130',
        ink:       '#E4ECEA',
        muted:     '#8FA3A1',
        primary:   '#37B0A5',
        wash:      '#16302E',
        negative:  '#E0755C',
        attention: '#D9A542',
        projected: '#7794AE',
    },
    // Sequential chart ramp — teal only, light → dark.
    chartRamp: ['#0B4F4C', '#12706B', '#35948C', '#6FB3A8', '#A7CFC6'],
};

const FONT_UI   = "'Public Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
const FONT_MONO = "'IBM Plex Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace";

const schemePalette = (t, mode) => ({
    mode,
    primary:   { main: t.primary, contrastText: mode === 'dark' ? t.canvas : '#FFFFFF' },
    secondary: { main: t.projected, contrastText: '#FFFFFF' },
    success:   { main: t.primary },
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
        shape: { borderRadius: 4 },
        transitions: {
            duration: {
                shortest: 100, shorter: 120, short: 150,
                standard: 150, complex: 150, enteringScreen: 150, leavingScreen: 150,
            },
        },
        components: {
            MuiButton: {
                defaultProps: { disableElevation: true },
                styleOverrides: {
                    root: ({ theme }) => ({
                        borderRadius: 4,
                        boxShadow: 'none',
                        padding: '6px 14px',
                        fontWeight: 500,
                        transition: 'background-color 150ms, opacity 150ms',
                        '&:hover': { boxShadow: 'none' },
                        '&:focus-visible': {
                            outline: `2px solid ${theme.vars.palette.primary.main}`,
                            outlineOffset: 2,
                        },
                    }),
                    sizeSmall: { padding: '4px 10px', fontSize: '12px' },
                },
            },
            MuiPaper: {
                defaultProps: { elevation: 0 },
                styleOverrides: {
                    root: ({ theme }) => ({
                        backgroundImage: 'none',
                        boxShadow: 'none',
                        border: `1px solid ${theme.vars.palette.divider}`,
                    }),
                    rounded: { borderRadius: 4 },
                    // Menus / dialogs / popovers are the only elevated surfaces.
                    elevation8: { boxShadow: 'var(--shadow-pop)' },
                    elevation24: { boxShadow: 'var(--shadow-pop)' },
                },
            },
            MuiCard: {
                styleOverrides: {
                    root: ({ theme }) => ({
                        borderRadius: 4,
                        border: `1px solid ${theme.vars.palette.divider}`,
                        boxShadow: 'none',
                    }),
                },
            },
            MuiCardContent: {
                styleOverrides: { root: { padding: 20, '&:last-child': { paddingBottom: 20 } } },
            },
            MuiDialog: {
                styleOverrides: {
                    paper: { borderRadius: 4, boxShadow: 'var(--shadow-pop)' },
                },
            },
            MuiMenu: {
                styleOverrides: {
                    paper: { borderRadius: 4, boxShadow: 'var(--shadow-pop)' },
                },
            },
            MuiPopover: {
                styleOverrides: {
                    paper: { borderRadius: 4, boxShadow: 'var(--shadow-pop)' },
                },
            },
            // Status chips: 11px mono, uppercase, 2px radius, tinted bg + solid
            // text — never solid fill with white text.
            MuiChip: {
                styleOverrides: {
                    root: {
                        borderRadius: 2,
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
                        borderRadius: 4,
                        textTransform: 'none',
                        fontWeight: 500,
                        transition: 'background-color 150ms, color 150ms',
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
                    head: ({ theme }) => ({
                        fontSize: '12px',
                        fontWeight: 600,
                        color: theme.vars.palette.text.secondary,
                        backgroundColor: theme.vars.palette.background.paper,
                    }),
                },
            },
            MuiDataGrid: {
                styleOverrides: {
                    root: {
                        border: 'none',
                        borderRadius: 4,
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
                            backgroundColor: 'var(--bg-card)',
                            borderBottom: '1px solid var(--border)',
                            color: 'var(--text-secondary)',
                            fontSize: '12px',
                            fontWeight: 600,
                            letterSpacing: '0.02em',
                        },
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
                        borderRadius: 4,
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
