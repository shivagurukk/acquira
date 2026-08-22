import { useState, useEffect, useCallback } from 'react';
import {
    Download, RefreshCw, Shield, CheckCircle, XCircle, Filter, FileText,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Row, Card, Button, Badge, DataTable,
    FormGrid, Input, Select,
} from '../../components/ui';

/* ────────── helpers ────────── */

/** Safely parse any date value Jackson might send (ISO string, array, epoch) */
const safeParseDateValue = (value) => {
    if (!value) return null;
    // ISO string: "2026-02-24T10:30:15"
    if (typeof value === 'string') {
        const d = new Date(value);
        return isNaN(d.getTime()) ? null : d;
    }
    // Jackson array: [2026,2,24,10,30,15,123456789]
    if (Array.isArray(value)) {
        const [y, mo, d, h = 0, m = 0, s = 0] = value;
        const date = new Date(y, mo - 1, d, h, m, s);
        return isNaN(date.getTime()) ? null : date;
    }
    // Epoch millis
    if (typeof value === 'number') {
        const d = new Date(value);
        return isNaN(d.getTime()) ? null : d;
    }
    return null;
};

const formatTimestamp = (value) => {
    const d = safeParseDateValue(value);
    if (!d) return '—';
    const mo = d.toLocaleString('en-US', { month: 'short' });
    const day = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    const ss = String(d.getSeconds()).padStart(2, '0');
    return `${mo} ${day}, ${hh}:${mm}:${ss}`;
};

/**
 * Categories the backend actually writes — see AuditService.categoryFor() and
 * AuditInterceptor.categorize(). The previous list (ADMIN/DATA/EXPORT/BATCH/AI/
 * BUSINESS) matched nothing the server stores, so every option but AUTH filtered
 * the table down to zero rows and USER_MGMT could not be selected at all.
 */
const CATEGORY_LABELS = {
    AUTH: 'Authentication',
    USER_MGMT: 'User management',
    ADMINISTRATION: 'Administration',
    OPERATIONS: 'Operations',
    REPORTING: 'Reporting',
    API: 'API',
};

/** Category → Badge tone. */
const CATEGORY_TONES = {
    AUTH: 'brand',
    USER_MGMT: 'info',
    ADMINISTRATION: 'danger',
    OPERATIONS: 'warning',
    REPORTING: 'success',
    API: 'neutral',
};

const CATEGORIES = Object.entries(CATEGORY_LABELS).map(([value, label]) => ({ value, label }));

const PAGE_SIZES = [
    { value: 20, label: '20 per page' },
    { value: 50, label: '50 per page' },
    { value: 100, label: '100 per page' },
];

const EMPTY_FILTERS = { search: '', category: '', action: '', username: '', startDate: '', endDate: '' };

/** Small metric tile — no kit primitive for this shape yet. */
const StatTile = ({ label, value, tone = 'var(--text)' }) => (
    <Card pad>
        <div className="ui-field__label" style={{ marginBottom: 4 }}>{label}</div>
        <div style={{ fontSize: '1.5rem', fontWeight: 600, lineHeight: 1.2, color: tone }}>{value}</div>
    </Card>
);

/* ────────── component ────────── */

const AuditLogViewer = () => {
    const { tenantVersion } = useAuth();

    const [logs, setLogs] = useState([]);
    const [loading, setLoading] = useState(false);
    const [exporting, setExporting] = useState(false);
    const [reporting, setReporting] = useState(false);
    const [stats, setStats] = useState({ totalToday: 0, errors: 0, activeUsers: 0, errorRate: 0 });
    const [filters, setFilters] = useState(EMPTY_FILTERS);
    const [paginationModel, setPaginationModel] = useState({ page: 0, pageSize: 20 });
    const [rowCount, setRowCount] = useState(0);

    const fetchLogs = useCallback(async () => {
        setLoading(true);
        try {
            const params = {
                page: paginationModel.page,
                size: paginationModel.pageSize,
            };
            // Only add non-empty filters
            Object.entries(filters).forEach(([key, val]) => {
                if (val) params[key] = val;
            });

            const response = await api.get('/admin/audit-logs', { params });
            const data = response.data;
            setLogs(data.content || []);
            setRowCount(data.totalElements || 0);
        } catch (error) {
            console.error('Failed to fetch audit logs', error);
            setLogs([]);
            showToast('Could not load audit logs', 'error');
        } finally {
            setLoading(false);
        }
    }, [paginationModel.page, paginationModel.pageSize, filters]);

    const fetchStats = async () => {
        try {
            const res = await api.get('/admin/audit-logs/stats');
            setStats(res.data || { totalToday: 0, errorRate: 0 });
        } catch (e) {
            console.error('Stats fetch failed', e);
        }
    };

    useEffect(() => {
        fetchLogs();
    }, [fetchLogs]);

    useEffect(() => {
        fetchStats();
    }, []);

    // Refetch logs + stats when the active tenant changes
    useEffect(() => {
        setPaginationModel(prev => (prev.page === 0 ? prev : { ...prev, page: 0 }));
        fetchStats();
        fetchLogs();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantVersion]);

    const handleExport = async () => {
        setExporting(true);
        try {
            const params = {};
            Object.entries(filters).forEach(([key, val]) => {
                if (val) params[key] = val;
            });
            const response = await api.get('/admin/audit-logs/export', {
                params,
                responseType: 'blob'
            });
            const now = new Date();
            const ts = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}`;
            const url = window.URL.createObjectURL(new Blob([response.data]));
            const link = document.createElement('a');
            link.href = url;
            link.setAttribute('download', `audit_logs_${ts}.csv`);
            document.body.appendChild(link);
            link.click();
            link.remove();
            window.URL.revokeObjectURL(url);
            showToast('Audit log CSV downloaded', 'success');
        } catch (error) {
            console.error('Export failed', error);
            showToast('Export failed', 'error');
        } finally {
            setExporting(false);
        }
    };

    const handleComplianceExport = async () => {
        setReporting(true);
        try {
            const params = {};
            Object.entries(filters).forEach(([key, val]) => {
                if (val) params[key] = val;
            });
            // If no dates set, default to last 30 days
            if (!params.startDate) {
                const d = new Date();
                d.setDate(d.getDate() - 30);
                params.startDate = d.toISOString();
            }
            if (!params.endDate) {
                params.endDate = new Date().toISOString();
            }

            const response = await api.get('/admin/audit-logs/export', { params, responseType: 'blob' });
            const csvText = await response.data.text();
            const lines = csvText.trim().split('\n');
            const totalEvents = lines.length - 1;

            // Build compliance report as formatted text
            const now = new Date();
            const reportTitle = 'COMPLIANCE AUDIT TRAIL REPORT';
            const separator = '='.repeat(60);
            let report = `${separator}\n${reportTitle}\n${separator}\n\n`;
            report += `Generated: ${now.toLocaleString()}\n`;
            report += `Period: ${params.startDate?.split('T')[0] || 'All'} to ${params.endDate?.split('T')[0] || 'All'}\n`;
            report += `Total Events: ${totalEvents}\n`;
            report += `Filters Applied: ${Object.entries(params).filter(([k, v]) => v && k !== 'startDate' && k !== 'endDate').map(([k, v]) => `${k}=${v}`).join(', ') || 'None'}\n\n`;

            // Category breakdown
            const catCounts = {};
            const userCounts = {};
            const statusCounts = { success: 0, error: 0 };
            lines.slice(1).forEach(line => {
                const parts = line.split(',');
                const cat = (parts[2] || '').trim();
                const user = (parts[1] || '').trim();
                const status = parseInt(parts[5] || '0');
                if (cat) catCounts[cat] = (catCounts[cat] || 0) + 1;
                if (user) userCounts[user] = (userCounts[user] || 0) + 1;
                if (status >= 200 && status < 400) statusCounts.success++;
                else if (status >= 400) statusCounts.error++;
            });

            report += `${'-'.repeat(40)}\nSUMMARY BY CATEGORY\n${'-'.repeat(40)}\n`;
            Object.entries(catCounts).sort((a, b) => b[1] - a[1]).forEach(([cat, count]) => {
                report += `  ${cat.padEnd(20)} ${String(count).padStart(6)}\n`;
            });

            report += `\n${'-'.repeat(40)}\nSUMMARY BY USER\n${'-'.repeat(40)}\n`;
            Object.entries(userCounts).sort((a, b) => b[1] - a[1]).forEach(([user, count]) => {
                report += `  ${user.padEnd(20)} ${String(count).padStart(6)}\n`;
            });

            report += `\n${'-'.repeat(40)}\nSTATUS SUMMARY\n${'-'.repeat(40)}\n`;
            report += `  Successful (2xx-3xx)  ${String(statusCounts.success).padStart(6)}\n`;
            report += `  Errors (4xx-5xx)      ${String(statusCounts.error).padStart(6)}\n`;

            report += `\n${separator}\nDETAILED LOG\n${separator}\n\n`;
            report += csvText;

            const blob = new Blob([report], { type: 'text/plain' });
            const url = window.URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            const ts = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`;
            link.setAttribute('download', `compliance_report_${ts}.txt`);
            document.body.appendChild(link);
            link.click();
            link.remove();
            window.URL.revokeObjectURL(url);
            showToast('Compliance report downloaded', 'success');
        } catch (error) {
            console.error('Compliance export failed', error);
            showToast('Compliance report failed', 'error');
        } finally {
            setReporting(false);
        }
    };

    const handleFilterApply = () => {
        setPaginationModel(prev => ({ ...prev, page: 0 }));
        fetchLogs();
    };

    const handleFilterReset = () => {
        setFilters({ ...EMPTY_FILTERS });
        setPaginationModel(prev => ({ ...prev, page: 0 }));
    };

    const setFilter = (key) => (e) => setFilters(f => ({ ...f, [key]: e.target.value }));
    const onEnterApply = (e) => { if (e.key === 'Enter') handleFilterApply(); };

    /* ────────── columns ────────── */

    const columns = [
        {
            key: 'eventTime',
            header: 'Timestamp',
            sortable: true,
            nowrap: true,
            width: 180,
            sortValue: (r) => safeParseDateValue(r.eventTime)?.getTime() ?? null,
            render: (r) => formatTimestamp(r.eventTime),
        },
        { key: 'username', header: 'User', sortable: true, width: 130 },
        {
            key: 'category',
            header: 'Category',
            sortable: true,
            width: 120,
            render: (r) => {
                const cat = r.category || 'N/A';
                return <Badge tone={CATEGORY_TONES[cat] || 'neutral'}>{CATEGORY_LABELS[cat] || cat}</Badge>;
            },
        },
        { key: 'actionType', header: 'Action', sortable: true },
        { key: 'endpoint', header: 'Endpoint', mono: true, muted: true },
        {
            key: 'statusCode',
            header: 'Status',
            sortable: true,
            nowrap: true,
            width: 110,
            render: (r) => {
                const code = r.statusCode;
                if (code == null) return '—';
                const ok = code >= 200 && code < 300;
                return (
                    <span className="ui-row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                        {ok
                            ? <CheckCircle size={14} style={{ color: 'var(--success)', flexShrink: 0 }} />
                            : <XCircle size={14} style={{ color: 'var(--danger)', flexShrink: 0 }} />}
                        {code}
                    </span>
                );
            },
        },
        { key: 'ipAddress', header: 'IP address', mono: true, muted: true, width: 140 },
        {
            key: 'duration',
            header: 'Time (ms)',
            sortable: true,
            numeric: true,
            align: 'right',
            width: 110,
            render: (r) => (r.duration != null ? `${r.duration}ms` : '—'),
        },
    ];

    /* ────────── pagination ────────── */

    const totalPages = Math.max(1, Math.ceil(rowCount / paginationModel.pageSize));
    const currentPage = paginationModel.page;
    const firstRow = rowCount === 0 ? 0 : currentPage * paginationModel.pageSize + 1;
    const lastRow = Math.min(rowCount, (currentPage + 1) * paginationModel.pageSize);

    const tableFooter = (
        <Row between>
            <span className="ui-field__hint">
                {rowCount > 0
                    ? `Showing ${firstRow.toLocaleString()} to ${lastRow.toLocaleString()} of ${rowCount.toLocaleString()} events`
                    : 'No events'}
            </span>
            <Row>
                <Select
                    value={paginationModel.pageSize}
                    onChange={(e) => setPaginationModel({ page: 0, pageSize: Number(e.target.value) })}
                    options={PAGE_SIZES}
                    aria-label="Rows per page"
                    style={{ width: 150 }}
                />
                <Button
                    size="sm"
                    disabled={currentPage === 0}
                    onClick={() => setPaginationModel(p => ({ ...p, page: p.page - 1 }))}
                >
                    Previous
                </Button>
                <span className="ui-field__hint" style={{ whiteSpace: 'nowrap' }}>
                    Page {currentPage + 1} of {totalPages}
                </span>
                <Button
                    size="sm"
                    disabled={currentPage + 1 >= totalPages}
                    onClick={() => setPaginationModel(p => ({ ...p, page: p.page + 1 }))}
                >
                    Next
                </Button>
            </Row>
        </Row>
    );

    /* ────────── render ────────── */

    return (
        <Page
            title="Audit logs and security trail"
            subtitle="Every authenticated request, admin action and export recorded for the active tenant."
            icon={Shield}
            actions={
                <>
                    <Button icon={Download} onClick={handleExport} loading={exporting}>
                        Export CSV
                    </Button>
                    <Button variant="primary" icon={FileText} onClick={handleComplianceExport} loading={reporting}>
                        Compliance report
                    </Button>
                </>
            }
        >
            <Stack gap="md">
                <FormGrid cols={4}>
                    <StatTile label="Total events today" value={stats.totalToday || 0} />
                    <StatTile label="Errors today" value={stats.errors ?? 0} tone="var(--danger)" />
                    <StatTile label="Active users" value={stats.activeUsers ?? 0} tone="var(--success)" />
                    <StatTile label="Error rate" value={`${(stats.errorRate ?? 0).toFixed(1)}%`} tone="var(--warning)" />
                </FormGrid>

                <Card footer={tableFooter}>
                    <DataTable
                        columns={columns}
                        rows={logs}
                        rowKey={(r) => r.logId}
                        loading={loading}
                        skeletonRows={8}
                        emptyVariant="search"
                        toolbarLeft={
                            <>
                                <Input
                                    value={filters.search}
                                    onChange={setFilter('search')}
                                    onKeyDown={onEnterApply}
                                    placeholder="Search logs…"
                                    aria-label="Search logs"
                                    style={{ width: 200 }}
                                />
                                <Select
                                    value={filters.category}
                                    onChange={setFilter('category')}
                                    placeholder="All categories"
                                    options={CATEGORIES}
                                    aria-label="Filter by category"
                                    style={{ width: 160 }}
                                />
                                <Input
                                    value={filters.username}
                                    onChange={setFilter('username')}
                                    onKeyDown={onEnterApply}
                                    placeholder="User"
                                    aria-label="Filter by user"
                                    style={{ width: 140 }}
                                />
                                <Input
                                    type="date"
                                    value={filters.startDate ? filters.startDate.split('T')[0] : ''}
                                    onChange={(e) => setFilters(f => ({ ...f, startDate: e.target.value ? e.target.value + 'T00:00:00' : '' }))}
                                    aria-label="From date"
                                    style={{ width: 150 }}
                                />
                                <Input
                                    type="date"
                                    value={filters.endDate ? filters.endDate.split('T')[0] : ''}
                                    onChange={(e) => setFilters(f => ({ ...f, endDate: e.target.value ? e.target.value + 'T23:59:59' : '' }))}
                                    aria-label="To date"
                                    style={{ width: 150 }}
                                />
                            </>
                        }
                        toolbarRight={
                            <>
                                <Button variant="primary" icon={Filter} onClick={handleFilterApply}>Filter</Button>
                                <Button icon={RefreshCw} onClick={handleFilterReset}>Reset</Button>
                            </>
                        }
                    />
                </Card>
            </Stack>
        </Page>
    );
};

export default AuditLogViewer;
