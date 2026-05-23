import { createTheme } from '@mui/material/styles';

// ═══════════════════════════════════════════════════════════
// Acquira Design System — Modern Minimal Theme
// Clean · Spacious · Professional
//
// createAppTheme(mode) returns a MUI theme for 'light' or 'dark'.
// The custom ThemeContext calls this so MUI components (DataGrid,
// Dialog, Paper, Button…) respond to dark mode instead of staying
// permanently light.
// ═══════════════════════════════════════════════════════════

const LIGHT_PALETTE = {
    mode: 'light',
    primary:   { main: '#2563eb', light: '#3b82f6', dark: '#1d4ed8', contrastText: '#ffffff' },
    secondary: { main: '#059669', light: '#10b981', dark: '#047857', contrastText: '#ffffff' },
    error:     { main: '#dc2626', light: '#ef4444', dark: '#b91c1c' },
    warning:   { main: '#d97706', light: '#f59e0b', dark: '#b45309' },
    background:{ default: '#f9fafb', paper: '#ffffff' },
    text:      { primary: '#111827', secondary: '#6b7280' },
    divider:   '#e5e7eb',
};

const DARK_PALETTE = {
    mode: 'dark',
    primary:   { main: '#3b82f6', light: '#60a5fa', dark: '#2563eb', contrastText: '#ffffff' },
    secondary: { main: '#10b981', light: '#34d399', dark: '#059669', contrastText: '#ffffff' },
    error:     { main: '#ef4444', light: '#f87171', dark: '#dc2626' },
    warning:   { main: '#f59e0b', light: '#fbbf24', dark: '#d97706' },
    background:{ default: '#0f172a', paper: '#1e293b' },
    text:      { primary: '#f1f5f9', secondary: '#94a3b8' },
    divider:   '#334155',
};

export function createAppTheme(mode = 'light') {
    const isDark = mode === 'dark';
    const palette = isDark ? DARK_PALETTE : LIGHT_PALETTE;

    return createTheme({
        palette,
        typography: {
            fontFamily: '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
            h1: { fontSize: '1.75rem', fontWeight: 700, letterSpacing: '-0.025em', lineHeight: 1.2 },
            h2: { fontSize: '1.5rem', fontWeight: 700, letterSpacing: '-0.025em', lineHeight: 1.25 },
            h3: { fontSize: '1.25rem', fontWeight: 600, letterSpacing: '-0.02em', lineHeight: 1.3 },
            h4: { fontSize: '1.125rem', fontWeight: 600, lineHeight: 1.35 },
            h5: { fontSize: '1rem', fontWeight: 600, lineHeight: 1.4 },
            h6: { fontSize: '0.875rem', fontWeight: 600, lineHeight: 1.5 },
            body1: { fontSize: '0.875rem', lineHeight: 1.6 },
            body2: { fontSize: '0.8125rem', lineHeight: 1.5 },
            caption: { fontSize: '0.75rem', lineHeight: 1.4 },
            button: { textTransform: 'none', fontWeight: 600, fontSize: '0.8125rem' },
        },
        shape: { borderRadius: 10 },
        components: {
            MuiButton: {
                styleOverrides: {
                    root: {
                        borderRadius: 10,
                        boxShadow: 'none',
                        padding: '8px 18px',
                        fontWeight: 600,
                        '&:hover': { boxShadow: '0 1px 3px 0 rgb(0 0 0 / 0.06)' },
                    },
                    sizeSmall: { padding: '5px 14px', fontSize: '0.75rem' },
                },
            },
            MuiPaper: {
                styleOverrides: {
                    root: {
                        backgroundImage: 'none',
                        boxShadow: isDark
                            ? '0 1px 3px 0 rgb(0 0 0 / 0.4)'
                            : '0 1px 3px 0 rgb(0 0 0 / 0.03)',
                    },
                    rounded: { borderRadius: 14 },
                    elevation1: {
                        boxShadow: isDark
                            ? '0 1px 3px 0 rgb(0 0 0 / 0.4)'
                            : '0 1px 3px 0 rgb(0 0 0 / 0.03)',
                    },
                    elevation8: {
                        boxShadow: isDark
                            ? '0 10px 20px -4px rgb(0 0 0 / 0.5), 0 4px 8px -4px rgb(0 0 0 / 0.4)'
                            : '0 10px 20px -4px rgb(0 0 0 / 0.06), 0 4px 8px -4px rgb(0 0 0 / 0.03)',
                    },
                },
            },
            MuiCard: { styleOverrides: { root: { borderRadius: 14 } } },
            MuiDialog: { styleOverrides: { paper: { borderRadius: 18 } } },
            MuiChip: { styleOverrides: { root: { borderRadius: 8, fontWeight: 500 } } },
            MuiTextField: { defaultProps: { variant: 'outlined', size: 'small' } },
            MuiDataGrid: {
                styleOverrides: {
                    root: {
                        border: 'none',
                        borderRadius: 10,
                        '& .MuiDataGrid-cell': {
                            borderBottom: '1px solid var(--border-light)',
                            fontSize: '0.82rem',
                        },
                        '& .MuiDataGrid-columnHeaders': {
                            backgroundColor: 'var(--bg-subtle)',
                            borderBottom: '1px solid var(--border)',
                            color: 'var(--text-secondary)',
                            fontSize: '0.72rem',
                            fontWeight: 700,
                            letterSpacing: '0.04em',
                        },
                        '& .MuiDataGrid-row:hover': {
                            backgroundColor: 'var(--bg-hover) !important',
                        },
                    },
                },
            },
            MuiTooltip: {
                styleOverrides: {
                    tooltip: {
                        fontSize: '0.75rem',
                        borderRadius: 8,
                        padding: '6px 12px',
                        backgroundColor: isDark ? '#334155' : '#111827',
                    },
                },
            },
        },
    });
}

// Default light theme kept as a named export for any module that
// imports a ready-made theme directly.
const theme = createAppTheme('light');
export default theme;
