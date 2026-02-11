// ─── Shared Premium DataGrid Styles ──────────────────────────────────
// Import in any page:  import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';

export const premiumDataGridStyles = {
    border: 'none',
    '& .MuiDataGrid-columnHeaders': {
        bgcolor: '#f8fafc', borderBottom: '2px solid #e2e8f0',
    },
    '& .MuiDataGrid-columnHeader': { bgcolor: '#f8fafc' },
    '& .MuiDataGrid-columnHeaderTitle': {
        fontWeight: 800, color: '#475569', textTransform: 'uppercase', fontSize: '0.7rem', letterSpacing: '0.06em',
    },
    '& .MuiDataGrid-iconSeparator': { color: '#e2e8f0' },
    '& .MuiDataGrid-menuIcon': { color: '#94a3b8' },
    '& .MuiDataGrid-sortIcon': { color: '#64748b' },
    '& .MuiDataGrid-row': {
        borderBottom: '1px solid #f1f5f9',
        '&:nth-of-type(even)': { bgcolor: '#fafbfc' },
        '&:hover': { bgcolor: '#f1f5f9 !important' },
    },
    '& .MuiDataGrid-cell': { borderBottom: 'none', display: 'flex', alignItems: 'center' },
    '& .MuiDataGrid-withBorderColor': { borderColor: '#e2e8f0' },
    '& .MuiDataGrid-toolbarContainer': {
        p: 1.5, gap: 1,
        '& .MuiButtonBase-root': { fontSize: '12px', fontWeight: 600, color: '#64748b' },
    },
    '& .MuiDataGrid-footerContainer': { borderTop: '1px solid #e2e8f0' },
    '& .MuiTablePagination-root': { color: '#64748b' },
    '& ::-webkit-scrollbar': { width: 6, height: 6 },
    '& ::-webkit-scrollbar-track': { background: '#f8fafc' },
    '& ::-webkit-scrollbar-thumb': { background: '#cbd5e1', borderRadius: 3 },
    '& ::-webkit-scrollbar-thumb:hover': { background: '#94a3b8' },
};

export const premiumTableWrapper = {
    flex: 1, width: '100%', borderRadius: '14px', overflow: 'hidden',
    bgcolor: 'white', border: '1px solid #e2e8f0',
    boxShadow: '0 1px 3px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03)',
};

export const pageContainer = {
    p: 3, bgcolor: '#F8FAFC', minHeight: '100vh', display: 'flex', flexDirection: 'column',
};
