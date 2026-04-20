// ─── Shared DataGrid Styles — Modern Minimal ─────────────────────
// Import: import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

export const premiumDataGridStyles = {
    border: 'none',
    '& .MuiDataGrid-columnHeaders': {
        bgcolor: 'var(--bg-subtle, #f3f4f6)', borderBottom: '1px solid var(--border, #e5e7eb)',
    },
    '& .MuiDataGrid-columnHeader': { bgcolor: 'var(--bg-subtle, #f3f4f6)' },
    '& .MuiDataGrid-columnHeaderTitle': {
        fontWeight: 700,
        color: 'var(--text-secondary, #6b7280)',
        fontSize: '0.72rem',
        letterSpacing: '0.03em',
    },
    '& .MuiDataGrid-iconSeparator': { color: 'var(--border, #e5e7eb)' },
    '& .MuiDataGrid-menuIcon': { color: 'var(--text-muted, #9ca3af)' },
    '& .MuiDataGrid-sortIcon': { color: 'var(--text-secondary, #6b7280)' },
    '& .MuiDataGrid-row': {
        borderBottom: '1px solid var(--border-light, #f3f4f6)',
        transition: 'background-color 0.1s ease',
        '&:hover': { bgcolor: 'var(--bg-hover, #f9fafb) !important' },
    },
    '& .MuiDataGrid-cell': {
        borderBottom: 'none', display: 'flex', alignItems: 'center',
        color: 'var(--text, inherit)', fontSize: '0.82rem',
    },
    '& .MuiDataGrid-withBorderColor': { borderColor: 'var(--border, #e5e7eb)' },
    '& .MuiDataGrid-toolbarContainer': {
        p: 1.5, gap: 1,
        '& .MuiButtonBase-root': { fontSize: '0.75rem', fontWeight: 500, color: 'var(--text-secondary, #6b7280)' },
    },
    '& .MuiDataGrid-footerContainer': { borderTop: '1px solid var(--border, #e5e7eb)' },
    '& .MuiTablePagination-root': { color: 'var(--text-secondary, #6b7280)', fontSize: '0.82rem' },
    '& ::-webkit-scrollbar': { width: 4, height: 4 },
    '& ::-webkit-scrollbar-track': { background: 'transparent' },
    '& ::-webkit-scrollbar-thumb': { background: 'var(--border, #d1d5db)', borderRadius: 3 },
    '& ::-webkit-scrollbar-thumb:hover': { background: 'var(--text-muted, #9ca3af)' },
};

export const premiumTableWrapper = {
    flex: 1, width: '100%', borderRadius: 'var(--radius-lg, 14px)', overflow: 'hidden',
    bgcolor: 'var(--bg-card, white)', border: '1px solid var(--border, #e5e7eb)',
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
