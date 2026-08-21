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

    // ── Header: deep navy bar, light type, bright hairline beneath ──
    // The gradient is painted on the header CONTAINER and each header cell is
    // transparent, so one continuous bar runs the full width instead of the
    // gradient restarting inside every cell.
    '& .MuiDataGrid-columnHeaders': {
        background: 'var(--table-head-bg)',
        borderBottom: 'none',
        boxShadow: 'inset 0 -2px 0 var(--table-head-edge)',
    },
    '& .MuiDataGrid-columnHeader': {
        bgcolor: 'transparent',
        '&:focus, &:focus-within': { outline: 'none' },
    },
    // Filler/scroll-gap cells the grid inserts past the last column.
    '& .MuiDataGrid-filler, & .MuiDataGrid-scrollbarFiller': {
        background: 'transparent',
        borderColor: 'transparent',
    },
    '& .MuiDataGrid-columnHeaderTitle': {
        fontWeight: 700,
        color: 'var(--table-head-text)',
        fontSize: '12px',
        letterSpacing: '0.03em',
    },
    '& .MuiDataGrid-columnHeader--alignRight .MuiDataGrid-columnHeaderTitle': {
        fontFamily: 'var(--font-mono)',
    },
    '& .MuiDataGrid-iconSeparator': { display: 'none' },
    // Header affordances must read against navy, not against the card.
    '& .MuiDataGrid-menuIcon, & .MuiDataGrid-sortIcon, & .MuiDataGrid-filterIcon': {
        color: 'var(--table-head-muted)',
    },
    '& .MuiDataGrid-columnHeader .MuiCheckbox-root': { color: 'var(--table-head-muted)' },
    '& .MuiDataGrid-columnHeader:hover .MuiDataGrid-menuIcon, & .MuiDataGrid-columnHeader:hover .MuiDataGrid-sortIcon': {
        color: 'var(--table-head-text)',
    },
    // The sort control is an IconButton — its default hover/ripple circle and
    // the multi-sort index badge both render as a solid light "ball" against
    // the navy bar. Flatten the button and hide the badge.
    '& .MuiDataGrid-columnHeader .MuiIconButton-root': {
        color: 'var(--table-head-muted)',
        backgroundColor: 'transparent',
        '&:hover': { backgroundColor: 'color-mix(in srgb, #ffffff 14%, transparent)' },
        '& .MuiTouchRipple-root': { display: 'none' },
    },
    '& .MuiDataGrid-columnHeader .MuiBadge-badge': { display: 'none' },

    // ── Rows: tinted body; hover = primary wash ──
    // Uniform tint rather than zebra: DataGrid virtualises rows, so an
    // nth-child stripe walks as you scroll. Plain tables get real zebra.
    '& .MuiDataGrid-row': {
        bgcolor: 'var(--table-row)',
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
