// ─── Shared DataGrid Styles — Ledger ─────────────────────────────
// Import: import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
//
// Tables: 40px rows, 12px horizontal cell padding, sticky hairlined
// header, hover = primary wash at 40%, no zebra striping. Numeric
// columns (DataGrid type: 'number') render in the mono face,
// right-aligned with tabular figures.

export const premiumDataGridStyles = {
    border: 'none',
    '--DataGrid-rowBorderColor': 'var(--border)',
    fontFamily: 'var(--font-ui)',

    // ── Header: sticky, hairline bottom, quiet label ──
    '& .MuiDataGrid-columnHeaders': {
        bgcolor: 'var(--bg-card)',
        borderBottom: '1px solid var(--border)',
    },
    '& .MuiDataGrid-columnHeader': {
        bgcolor: 'var(--bg-card)',
        '&:focus, &:focus-within': { outline: 'none' },
    },
    '& .MuiDataGrid-columnHeaderTitle': {
        fontWeight: 600,
        color: 'var(--text-secondary)',
        fontSize: '12px',
        letterSpacing: '0.02em',
    },
    '& .MuiDataGrid-columnHeader--alignRight .MuiDataGrid-columnHeaderTitle': {
        fontFamily: 'var(--font-mono)',
    },
    '& .MuiDataGrid-iconSeparator': { display: 'none' },
    '& .MuiDataGrid-menuIcon': { color: 'var(--text-muted)' },
    '& .MuiDataGrid-sortIcon': { color: 'var(--text-secondary)' },

    // ── Rows: no zebra; hover = primary wash at 40% ──
    '& .MuiDataGrid-row': {
        transition: 'background-color 150ms ease',
        '&:hover': { bgcolor: 'var(--bg-hover) !important' },
        '&.Mui-selected': {
            bgcolor: 'var(--wash) !important',
            '&:hover': { bgcolor: 'var(--wash) !important' },
        },
    },
    '& .MuiDataGrid-cell': {
        borderBottom: '1px solid var(--border)',
        display: 'flex', alignItems: 'center',
        color: 'var(--text)', fontSize: '13px',
        padding: '0 12px',
        '&:focus, &:focus-within': { outline: 'none' },
    },
    // Numerals: mono face + tabular figures on every numeric column.
    '& .MuiDataGrid-cell--textRight': {
        fontFamily: 'var(--font-mono)',
        fontVariantNumeric: 'tabular-nums',
    },
    '& .MuiDataGrid-withBorderColor': { borderColor: 'var(--border)' },

    // ── Toolbar ──
    '& .MuiDataGrid-toolbarContainer': {
        p: 1.5, gap: 1,
        borderBottom: '1px solid var(--border)',
        '& .MuiButtonBase-root': {
            fontSize: '12px', fontWeight: 500,
            color: 'var(--text-secondary)',
            borderRadius: 'var(--radius-sm)',
            '&:hover': { bgcolor: 'var(--bg-hover)' },
        },
    },

    // ── Footer / pagination ──
    '& .MuiDataGrid-footerContainer': {
        borderTop: '1px solid var(--border)',
        minHeight: 44,
    },
    '& .MuiTablePagination-root': {
        color: 'var(--text-secondary)', fontSize: '12px',
        fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
    },

    // ── Slim scrollbars ──
    '& ::-webkit-scrollbar': { width: 6, height: 6 },
    '& ::-webkit-scrollbar-track': { background: 'transparent' },
    '& ::-webkit-scrollbar-thumb': { background: 'var(--border)', borderRadius: 2 },
    '& ::-webkit-scrollbar-thumb:hover': { background: 'var(--text-muted)' },
};

// 40px rows + sticky header defaults for DataGrid callers.
export const premiumDataGridProps = {
    rowHeight: 40,
    columnHeaderHeight: 40,
};

export const premiumTableWrapper = {
    flex: 1, width: '100%', borderRadius: 'var(--radius-md)', overflow: 'hidden',
    bgcolor: 'var(--bg-card)', border: '1px solid var(--border)',
    boxShadow: 'none',
};

export const pageContainer = {
    p: 'var(--space-page)', bgcolor: 'var(--bg)',
    minHeight: '100vh', display: 'flex', flexDirection: 'column', gap: 2,
};

export const chartTooltipStyle = {
    background: 'var(--bg-card)',
    border: '1px solid var(--border)',
    borderRadius: '4px',
    fontSize: '12px',
    fontFamily: 'var(--font-mono)',
    fontVariantNumeric: 'tabular-nums',
    color: 'var(--text)',
    boxShadow: 'var(--shadow-pop)',
    padding: '8px 12px',
};
