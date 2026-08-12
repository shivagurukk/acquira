import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { Box, Paper, Typography, Dialog, DialogTitle, DialogContent, DialogActions, Button, Chip, Stack, Tooltip } from '@mui/material';
import { DataGrid, GridToolbar } from '@mui/x-data-grid';
import { BarChart2, DollarSign, Hash, Layers, AlertTriangle } from 'lucide-react';
import PremiumReportHeader from '../../components/PremiumReportHeader';
import BusinessFilters from '../../components/BusinessFilters';
import KpiCards from '../../components/KpiCards';
import { exportToCSV } from '../../utils/exportUtils';
import { premiumDataGridStyles, premiumTableWrapper, pageContainer } from '../../theme/dataGridStyles';
import { useToast } from '../../contexts/ToastContext';
import { useAuth } from '../../contexts/AuthContext';
import { formatMsf, formatCurrency as fmtTenantMoney, formatCompactCurrency } from '../../utils/formatters';
import api from '../../api/axios';

// Despite the name this used to emit a BARE decimal at a hardcoded 2dp — no
// currency at all, and wrong for BHD. It now renders the tenant's currency at
// the tenant's precision.
const formatCurrency = (val) => fmtTenantMoney(val);
const formatNumber = (val) => new Intl.NumberFormat('en-US').format(val || 0);
const formatCompact = (val) =>
    new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(val || 0);

const fmtLocal = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

const computeDateRange = (preset) => {
    const now = new Date();
    switch (preset) {
        case 'TODAY':      return { startDate: fmtLocal(now), endDate: fmtLocal(now) };
        case 'MONTH':      return { startDate: fmtLocal(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: fmtLocal(now) };
        case 'LAST_MONTH': return { startDate: fmtLocal(new Date(now.getFullYear(), now.getMonth() - 1, 1)), endDate: fmtLocal(new Date(now.getFullYear(), now.getMonth(), 0)) };
        case 'YEAR':       return { startDate: fmtLocal(new Date(now.getFullYear(), 0, 1)), endDate: fmtLocal(now) };
        case 'PY':         return { startDate: fmtLocal(new Date(now.getFullYear() - 1, 0, 1)), endDate: fmtLocal(new Date(now.getFullYear() - 1, 11, 31)) };
        default:           return {};
    }
};

// Segment code → label + colour. Routed through CSS vars so dark mode retints.
// These are the six Phase-1 data-backed segments from MerchantSegmentationService.
const SEGMENT_META = {
    STRATEGIC:     { label: 'Strategic',     color: 'var(--seg-strategic, #7c3aed)', bg: 'var(--seg-strategic-bg, #f3e8ff)' },
    VOLUME_DRIVER: { label: 'Volume Driver', color: 'var(--seg-volume, #2563eb)',    bg: 'var(--seg-volume-bg, #dbeafe)' },
    PROFIT_DRIVER: { label: 'Profit Driver', color: 'var(--seg-profit, #059669)',    bg: 'var(--seg-profit-bg, #d1fae5)' },
    AT_RISK:       { label: 'At Risk',       color: 'var(--seg-atrisk, #dc2626)',    bg: 'var(--seg-atrisk-bg, #fee2e2)' },
    NEW:           { label: 'New',           color: 'var(--seg-new, #ea580c)',       bg: 'var(--seg-new-bg, #ffedd5)' },
    LONG_TAIL:     { label: 'Long Tail',     color: 'var(--seg-longtail, #475569)',  bg: 'var(--seg-longtail-bg, #f1f5f9)' },
    UNCLASSIFIED:  { label: 'Unclassified',  color: 'var(--text-muted, #94a3b8)',    bg: 'var(--bg-subtle, #f8fafc)' },
};
const SEGMENT_ORDER = ['STRATEGIC', 'VOLUME_DRIVER', 'PROFIT_DRIVER', 'AT_RISK', 'NEW', 'LONG_TAIL', 'UNCLASSIFIED'];

const MerchantAnalyticsReport = () => {
    const { show: toast } = useToast();
    const { currencyCode, tenantVersion } = useAuth();
    const [loading, setLoading] = useState(false);
    const [exportLoading, setExportLoading] = useState(false);
    const [data, setData] = useState([]);
    const [totalRows, setTotalRows] = useState(0);
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 25 });
    const [showFilters, setShowFilters] = useState(false);
    const [showExportDialog, setShowExportDialog] = useState(false);

    // Segmentation (precomputed, fetched once per tenant switch — additive overlay).
    const [segByMid, setSegByMid] = useState({});
    const [segMix, setSegMix] = useState([]);
    const [segAvailable, setSegAvailable] = useState(false);
    const [segFilter, setSegFilter] = useState('ALL');

    const [filters, setFilters] = useState(() => ({
        datePreset: 'YEAR',
        ...computeDateRange('YEAR'),
    }));

    // ── useRef so filter-panel Apply always sees the latest filters ──
    const filtersRef = useRef(filters);
    filtersRef.current = filters;
    const paginationRef = useRef(paginationModel);
    paginationRef.current = paginationModel;

    // ── Fetch ──────────────────────────────────────────────────────
    const fetchReport = useCallback(async (overrideFilters, overridePagination) => {
        setLoading(true);
        try {
            const active = overrideFilters || filtersRef.current;
            const pg = overridePagination || paginationRef.current;
            const body = { ...active };
            delete body.datePreset;
            const res = await api.post(
                `/business/merchant-analytics?page=${pg.page}&size=${pg.pageSize}`,
                body
            );
            setData(res.data.content || []);
            setTotalRows(res.data.totalElements || 0);
        } catch (err) {
            console.error(err);
            toast('Failed to load data. Please try again.', 'error');
        } finally {
            setLoading(false);
        }
    }, []); // eslint-disable-line react-hooks/exhaustive-deps

    useEffect(() => {
        fetchReport(undefined, paginationModel);
    }, [paginationModel, tenantVersion]); // eslint-disable-line react-hooks/exhaustive-deps

    // Segments are precomputed and independent of the report filters/pagination, so
    // fetch the whole tenant set once per tenant switch and decorate rows by mid.
    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const [listRes, mixRes] = await Promise.allSettled([
                    api.get('/business/segments'),
                    api.get('/business/segments/mix'),
                ]);
                if (cancelled) return;
                if (listRes.status === 'fulfilled') {
                    const map = {};
                    (listRes.value.data || []).forEach(s => { if (s.mid != null) map[s.mid] = s; });
                    setSegByMid(map);
                    setSegAvailable(Object.keys(map).length > 0);
                } else {
                    setSegByMid({}); setSegAvailable(false);
                }
                setSegMix(mixRes.status === 'fulfilled' ? (mixRes.value.data || []) : []);
            } catch (e) {
                if (!cancelled) { setSegByMid({}); setSegMix([]); setSegAvailable(false); }
            }
        })();
        return () => { cancelled = true; };
    }, [tenantVersion]);

    // ── Full export (all rows, not just current page) ──────────────
    const handleExport = useCallback(() => {
        if (totalRows > data.length) {
            setShowExportDialog(true);
        } else {
            exportToCSV(data, 'merchant_analytics');
            toast(`Exported ${data.length} rows.`, 'success');
        }
    }, [data, totalRows, toast]);

    const handleExportAll = useCallback(async () => {
        setShowExportDialog(false);
        setExportLoading(true);
        toast('Fetching all rows for export…', 'info');
        try {
            const body = { ...filtersRef.current };
            delete body.datePreset;
            const res = await api.post('/business/merchant-analytics?page=0&size=10000', body);
            exportToCSV(res.data.content || [], 'merchant_analytics_full');
            toast(`Exported ${(res.data.content || []).length} rows successfully.`, 'success');
        } catch (err) {
            toast('Export failed: ' + err.message, 'error');
        } finally {
            setExportLoading(false);
        }
    }, [toast]);

    // ── Filter handling ────────────────────────────────────────────
    const handleFilterChange = useCallback((keyOrObj) => {
        if (typeof keyOrObj === 'object') setFilters(prev => ({ ...prev, ...keyOrObj }));
    }, []);

    // Decorate the current page's rows with their segment (by mid).
    const rows = useMemo(
        () => data.map(r => {
            const s = segByMid[r.mid];
            return s ? { ...r, primarySegment: s.primarySegment, secondaryTags: s.secondaryTags, segmentReason: s.segmentReason } : r;
        }),
        [data, segByMid]
    );

    // Client-side segment quick-filter applies to the visible page.
    const visibleRows = useMemo(
        () => segFilter === 'ALL' ? rows : rows.filter(r => r.primarySegment === segFilter),
        [rows, segFilter]
    );

    // ── KPI cards ─────────────────────────────────────────────────
    const kpis = useMemo(() => {
        if (!data.length) return [];
        const totalVol      = data.reduce((s, d) => s + (Number(d.volume)      || 0), 0);
        const totalMsf      = data.reduce((s, d) => s + (Number(d.msf)         || 0), 0);
        const totalCount    = data.reduce((s, d) => s + (Number(d.count)        || 0), 0);
        return [
            { title: 'Total Records',   value: formatNumber(totalRows),                          icon: Layers,     color: '#6366f1', subtitle: `Page ${paginationModel.page + 1} of ${Math.ceil(totalRows / paginationModel.pageSize)}` },
            { title: 'Page Volume',     value: formatCompactCurrency(totalVol),     icon: DollarSign, color: '#3b82f6' },
            { title: 'Page MSF',        value: formatCompactCurrency(totalMsf),     icon: BarChart2,  color: '#10b981' },
            { title: 'Page Trnx Count', value: formatCompact(totalCount),                         icon: Hash,       color: '#f59e0b' },
        ];
    }, [data, totalRows, paginationModel, currencyCode]);

    // Segment mix as an ordered, coloured summary (portfolio-wide, latest calc).
    const mixOrdered = useMemo(() => {
        const byCode = {};
        segMix.forEach(m => { byCode[m.segment] = Number(m.count) || 0; });
        const total = Object.values(byCode).reduce((a, b) => a + b, 0) || 1;
        return SEGMENT_ORDER
            .filter(code => byCode[code] > 0)
            .map(code => ({ code, ...SEGMENT_META[code], count: byCode[code], pct: (byCode[code] / total) * 100 }));
    }, [segMix]);

    // ── Segment cell ──────────────────────────────────────────────
    const segmentCell = (params) => {
        const code = params.row.primarySegment;
        if (!code) return <Typography variant="body2" sx={{ color: 'var(--text-muted, #94a3b8)' }}>—</Typography>;
        const m = SEGMENT_META[code] || SEGMENT_META.UNCLASSIFIED;
        const tags = params.row.secondaryTags;
        const tip = [params.row.segmentReason, tags ? `Also: ${tags.replace(/,/g, ', ')}` : null].filter(Boolean).join(' · ');
        return (
            <Tooltip title={tip || m.label} arrow>
                <Chip label={m.label} size="small" sx={{ bgcolor: m.bg, color: m.color, fontWeight: 700 }} />
            </Tooltip>
        );
    };

    // ── Columns ───────────────────────────────────────────────────
    const columns = useMemo(() => {
        const cols = [
            { field: 'sid',          headerName: 'SID',         width: 140 },
            { field: 'terminalType', headerName: 'Terminal',    width: 120 },
            { field: 'mid',          headerName: 'MID',         width: 160 },
            { field: 'merchantName', headerName: 'Name',        flex: 1, minWidth: 180,
                renderCell: (p) => <Typography variant="body2" fontWeight={600} color="#0f172a">{p.value}</Typography> },
        ];
        if (segAvailable) {
            cols.push({
                field: 'segment', headerName: 'Segment', width: 150, sortable: false,
                valueGetter: (v, row) => row.primarySegment || '',
                renderCell: segmentCell,
            });
        }
        cols.push(
            { field: 'volume',       headerName: 'Volume',      width: 140, type: 'number', valueFormatter: (v) => formatCurrency(v) },
            { field: 'count',        headerName: 'Trnx Count',  width: 120, type: 'number', valueFormatter: (v) => formatNumber(v) },
            { field: 'msf',          headerName: 'MSF',         width: 130, type: 'number', valueFormatter: (v) => formatMsf(v) },
            { field: 'interchange',  headerName: 'Interchange', width: 130, type: 'number', valueFormatter: (v) => formatCurrency(v) },
            { field: 'mcc',          headerName: 'MCC',         width: 90 },
            { field: 'industry',     headerName: 'Industry',    width: 160 },
            { field: 'legalName',    headerName: 'Legal Name',  width: 200 },
            { field: 'dccOptin',     headerName: 'DCC Opt-In',  width: 130, type: 'number', valueFormatter: (v) => formatCurrency(v) },
        );
        return cols;
    }, [segAvailable]);

    return (
        <Box sx={pageContainer}>
            <PremiumReportHeader
                title="Merchant Analytics Report"
                subtitle={`Detailed performance metrics · ${formatNumber(totalRows)} total records`}
                icon={BarChart2}
                onExport={handleExport}
                onRunReport={() => fetchReport()}
                onFilterChange={handleFilterChange}
                onApplyAfterDatePreset={(next) => fetchReport(next)}
                loading={loading || exportLoading}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(v => !v)}
                filters={filters}
            />

            <BusinessFilters
                filters={filters}
                onChange={setFilters}
                onApply={() => fetchReport()}
                isOpen={showFilters}
                onClose={() => setShowFilters(false)}
            />

            <KpiCards cards={kpis} />

            {/* Segment mix + quick-filter — only when the batch has produced segments. */}
            {segAvailable && mixOrdered.length > 0 && (
                <Paper sx={{ ...premiumTableWrapper, p: 2, mb: 2 }}>
                    <Typography variant="caption" fontWeight={700}
                        sx={{ color: 'var(--text-muted, #94a3b8)', textTransform: 'uppercase', letterSpacing: '0.05em', mb: 1.25, display: 'block' }}>
                        Portfolio Segments
                    </Typography>
                    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                        <Chip
                            label={`All (${mixOrdered.reduce((a, b) => a + b.count, 0)})`}
                            size="small" clickable onClick={() => setSegFilter('ALL')}
                            sx={{
                                fontWeight: 700,
                                color: segFilter === 'ALL' ? 'var(--on-accent, #fff)' : 'var(--text, #0f172a)',
                                bgcolor: segFilter === 'ALL' ? 'var(--brand, #6366f1)' : 'var(--border, #e2e8f0)',
                            }}
                        />
                        {mixOrdered.map(s => {
                            const active = segFilter === s.code;
                            return (
                                <Chip key={s.code}
                                    label={`${s.label} · ${s.count} (${s.pct.toFixed(0)}%)`}
                                    size="small" clickable onClick={() => setSegFilter(active ? 'ALL' : s.code)}
                                    sx={{
                                        fontWeight: 700, fontVariantNumeric: 'tabular-nums',
                                        color: active ? 'var(--on-accent, #fff)' : s.color,
                                        bgcolor: active ? s.color : s.bg,
                                        border: active ? `1px solid ${s.color}` : '1px solid transparent',
                                    }} />
                            );
                        })}
                    </Stack>
                    {segFilter !== 'ALL' && (
                        <Typography variant="caption" sx={{ color: 'var(--text-muted, #94a3b8)', mt: 1, display: 'block' }}>
                            Filtering the current page to {SEGMENT_META[segFilter]?.label || segFilter}. Segment counts above are portfolio-wide (latest calc).
                        </Typography>
                    )}
                </Paper>
            )}

            <Paper sx={premiumTableWrapper}>
                <DataGrid
                    rows={visibleRows}
                    columns={columns}
                    getRowId={(row, i) => row.merchantId ?? `${row.sid ?? 'r'}-${i}`}
                    rowCount={totalRows}
                    loading={loading}
                    paginationModel={paginationModel}
                    paginationMode="server"
                    onPaginationModelChange={setPaginationModel}
                    pageSizeOptions={[25, 50, 100]}
                    slots={{ toolbar: GridToolbar }}
                    slotProps={{ toolbar: { showQuickFilter: true } }}
                    sx={premiumDataGridStyles}
                />
            </Paper>

            {/* Export confirmation dialog */}
            <Dialog open={showExportDialog} onClose={() => setShowExportDialog(false)} maxWidth="xs" fullWidth>
                <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <AlertTriangle size={18} color="#f59e0b" />
                    Export confirmation
                </DialogTitle>
                <DialogContent>
                    <Typography variant="body2" color="text.secondary">
                        You're viewing <strong>page {paginationModel.page + 1}</strong> ({data.length} rows).
                        The full dataset has <strong>{formatNumber(totalRows)} rows</strong>.
                    </Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                        Export this page only, or fetch and export all {formatNumber(totalRows)} rows?
                    </Typography>
                </DialogContent>
                <DialogActions>
                    <Button onClick={() => { setShowExportDialog(false); exportToCSV(data, 'merchant_analytics_page'); toast(`Exported ${data.length} rows (current page).`, 'success'); }} size="small">
                        This page ({data.length} rows)
                    </Button>
                    <Button onClick={handleExportAll} variant="contained" size="small" disableElevation>
                        Export all {formatNumber(totalRows)} rows
                    </Button>
                </DialogActions>
            </Dialog>
        </Box>
    );
};

class ErrorBoundary extends React.Component {
    constructor(props) { super(props); this.state = { hasError: false, error: null }; }
    static getDerivedStateFromError(error) { return { hasError: true, error }; }
    componentDidCatch(error, info) { console.error('MerchantAnalyticsReport error:', error, info); }
    render() {
        if (this.state.hasError) {
            return (
                <Box p={4}>
                    <Typography variant="h4" color="error" gutterBottom>Something went wrong</Typography>
                    <Paper sx={{ p: 3, bgcolor: '#FFF1F2', color: '#BE123C' }}>
                        <Typography variant="h6" fontFamily="monospace">{this.state.error?.toString()}</Typography>
                    </Paper>
                </Box>
            );
        }
        return this.props.children;
    }
}

export default function WrappedMerchantAnalyticsReport() {
    return <ErrorBoundary><MerchantAnalyticsReport /></ErrorBoundary>;
}
