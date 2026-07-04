// ─── Shared DataGrid Styles — Modern Minimal ─────────────────────
// Import: import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

export const premiumDataGridStyles = {
    border: 'none',
    '--DataGrid-rowBorderColor': 'var(--border-light, #f3f4f6)',

    // ── Header: quieter, tighter, uppercase micro-label ──
    '& .MuiDataGrid-columnHeaders': {
        bgcolor: 'var(--bg-subtle, #f8fafc)',
        borderBottom: '1px solid var(--border, #e5e7eb)',
    },
    '& .MuiDataGrid-columnHeader': {
        bgcolor: 'var(--bg-subtle, #f8fafc)',
        '&:focus, &:focus-within': { outline: 'none' },
    },
    '& .MuiDataGrid-columnHeaderTitle': {
        fontWeight: 600,
        color: 'var(--text-secondary, #64748b)',
        fontSize: '0.7rem',
        letterSpacing: '0.04em',
        textTransform: 'uppercase',
    },
    '& .MuiDataGrid-iconSeparator': { display: 'none' },
    '& .MuiDataGrid-menuIcon': { color: 'var(--text-muted, #9ca3af)' },
    '& .MuiDataGrid-sortIcon': { color: 'var(--text-secondary, #64748b)' },

    // ── Rows: subtle zebra + calm hover, no harsh borders ──
    '& .MuiDataGrid-row': {
        transition: 'background-color 0.12s ease',
        '&:nth-of-type(even)': { bgcolor: 'var(--bg-zebra, rgba(148,163,184,0.035))' },
        '&:hover': { bgcolor: 'var(--bg-hover, #f1f5f9) !important' },
        '&.Mui-selected': {
            bgcolor: 'var(--brand-light, #eff6ff) !important',
            '&:hover': { bgcolor: 'var(--brand-light, #e0edff) !important' },
        },
    },
    '& .MuiDataGrid-cell': {
        borderBottom: '1px solid var(--border-light, #f3f4f6)',
        display: 'flex', alignItems: 'center',
        color: 'var(--text, inherit)', fontSize: '0.82rem',
        '&:focus, &:focus-within': { outline: 'none' },
    },
    '& .MuiDataGrid-withBorderColor': { borderColor: 'var(--border, #e5e7eb)' },

    // ── Toolbar ──
    '& .MuiDataGrid-toolbarContainer': {
        p: 1.5, gap: 1,
        borderBottom: '1px solid var(--border-light, #f3f4f6)',
        '& .MuiButtonBase-root': {
            fontSize: '0.75rem', fontWeight: 500,
            color: 'var(--text-secondary, #64748b)',
            borderRadius: 'var(--radius-sm, 6px)',
            '&:hover': { bgcolor: 'var(--bg-hover, #f1f5f9)' },
        },
    },

    // ── Footer / pagination ──
    '& .MuiDataGrid-footerContainer': {
        borderTop: '1px solid var(--border, #e5e7eb)',
        minHeight: 48,
    },
    '& .MuiTablePagination-root': { color: 'var(--text-secondary, #64748b)', fontSize: '0.8rem' },

    // ── Slim scrollbars ──
    '& ::-webkit-scrollbar': { width: 6, height: 6 },
    '& ::-webkit-scrollbar-track': { background: 'transparent' },
    '& ::-webkit-scrollbar-thumb': { background: 'var(--border, #d1d5db)', borderRadius: 3 },
    '& ::-webkit-scrollbar-thumb:hover': { background: 'var(--text-muted, #9ca3af)' },
};

export const premiumTableWrapper = {
    flex: 1, width: '100%', borderRadius: 'var(--radius-lg, 14px)', overflow: 'hidden',
    bgcolor: 'var(--bg-card, white)', border: '1px solid var(--border, #e5e7eb)',
    boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(15,23,42,0.04))',
};

export const pageContainer = {
    p: 'var(--space-page, 28px)', bgcolor: 'var(--bg, #f9fafb)',
    minHeight: '100vh', display: 'flex', flexDirection: 'column', gap: 2.5,
};

export const chartTooltipStyle = {
    background: 'var(--bg-card, #fff)',
    border: '1px solid var(--border, #e5e7eb)',
    borderRadius: '10px',
    fontSize: '12px',
    color: 'var(--text, #111827)',
    boxShadow: '0 4px 12px rgba(0,0,0,0.06)',
    padding: '10px 14px',
};
