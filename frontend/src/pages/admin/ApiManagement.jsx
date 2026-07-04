import React, { useState, useEffect, useCallback } from 'react';
import {
    Box, Typography, Paper, TextField, Button, Switch, FormControlLabel,
    Divider, Chip, IconButton, Tooltip, useTheme, Dialog, DialogTitle,
    DialogContent, DialogActions, Alert, Snackbar, Table, TableHead,
    TableRow, TableCell, TableBody, Tabs, Tab, CircularProgress, MenuItem
} from '@mui/material';
import {
    Key, Plus, Trash2, Copy, Code, Activity, Edit2, ShieldAlert, Clock, Globe, RefreshCw, ExternalLink
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';

const PERMISSIONS = [
    { key: 'read:transactions', label: 'Read Transactions', desc: 'Query raw transaction rows' },
    { key: 'read:merchants', label: 'Read Merchants', desc: 'Query merchant/store data' },
    { key: 'read:analytics', label: 'Read Analytics', desc: 'Volume + scheme breakdowns' },
    { key: 'read:finance', label: 'Read Finance', desc: 'Financial summary (MSF, interchange, VAT)' },
    { key: 'read:reports', label: 'Read Reports', desc: 'Download branded PDF statements' },
    { key: 'write:upload', label: 'Upload Files', desc: 'Upload transaction/merchant files (reserved)' },
];

// Real endpoint registry — drives the Documentation tab. Mirrors ExternalDataApiController
// (/api/v1) and ExternalReportApiController (/api/external/reports).
const API_DOCS = [
    { group: 'Merchants', scope: 'read:merchants', rows: [
        { method: 'GET', path: '/api/v1/merchants', desc: 'List merchants (+ store count)', params: 'status, search, page, size' },
        { method: 'GET', path: '/api/v1/merchants/{mid}/summary', desc: 'Per-merchant settlement totals', params: 'startDate, endDate' },
    ]},
    { group: 'Transactions', scope: 'read:transactions', rows: [
        { method: 'GET', path: '/api/v1/transactions', desc: 'Raw transactions (date range required, ≤92d)', params: 'startDate*, endDate*, mid, sid, page, size' },
    ]},
    { group: 'Analytics', scope: 'read:analytics', rows: [
        { method: 'GET', path: '/api/v1/analytics/volume', desc: 'Volume trend (day/month/scheme)', params: 'startDate, endDate, groupBy' },
        { method: 'GET', path: '/api/v1/analytics/scheme-breakdown', desc: 'Scheme × card-type breakdown', params: 'startDate, endDate' },
    ]},
    { group: 'Finance', scope: 'read:finance', rows: [
        { method: 'GET', path: '/api/v1/finance/summary', desc: 'MSF, interchange, scheme fee, VAT, net revenue', params: 'startDate, endDate' },
    ]},
    { group: 'Reports (PDF)', scope: 'read:reports', rows: [
        { method: 'GET', path: '/api/external/reports/list', desc: 'List statement PDFs for a month', params: 'year, month' },
        { method: 'GET', path: '/api/external/reports/status', desc: 'Report count + total size', params: 'year, month' },
        { method: 'GET', path: '/api/external/reports/download', desc: 'Download one statement', params: 'file*, year, month' },
        { method: 'GET', path: '/api/external/reports/merchant/{mid}', desc: 'Download a merchant statement', params: 'year, month' },
        { method: 'GET', path: '/api/external/reports/download-all', desc: 'ZIP of all statements', params: 'year, month' },
    ]},
];

const emptyForm = { name: '', permissions: ['read:transactions', 'read:merchants'], expiresAt: '', rateLimitPerMinute: 120, allowedIps: '' };

const ApiManagement = () => {
    const { tenantVersion } = useAuth();
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';
    const [keys, setKeys] = useState([]);
    const [loading, setLoading] = useState(true);
    const [dialog, setDialog] = useState(null); // 'create' | 'edit' | null
    const [form, setForm] = useState(emptyForm);
    const [editId, setEditId] = useState(null);
    const [createdKey, setCreatedKey] = useState(null);
    const [snack, setSnack] = useState({ open: false, msg: '', severity: 'success' });
    const [tab, setTab] = useState(0);

    // Usage state
    const [summary, setSummary] = useState(null);
    const [usageDetail, setUsageDetail] = useState(null); // { keyId, data }
    const [usageLoading, setUsageLoading] = useState(false);

    const toast = (msg, severity = 'success') => setSnack({ open: true, msg, severity });

    const loadKeys = useCallback(async () => {
        setLoading(true);
        try { const res = await api.get('/admin/api-keys'); setKeys(res.data || []); }
        catch (e) { toast('Could not load API keys: ' + (e.response?.data?.error || e.message), 'error'); setKeys([]); }
        finally { setLoading(false); }
    }, []);
    useEffect(() => { loadKeys(); }, [loadKeys, tenantVersion]);

    const loadSummary = useCallback(async () => {
        try { const res = await api.get('/admin/api-keys/usage-summary'); setSummary(res.data); }
        catch { setSummary(null); }
    }, []);
    useEffect(() => { if (tab === 2) loadSummary(); }, [tab, loadSummary, tenantVersion]);

    const openCreate = () => { setForm(emptyForm); setEditId(null); setDialog('create'); };
    const openEdit = (k) => {
        setForm({
            name: k.name || '',
            permissions: Array.isArray(k.permissions) ? k.permissions : [],
            expiresAt: k.expiresAt ? String(k.expiresAt).substring(0, 10) : '',
            rateLimitPerMinute: k.rateLimitPerMinute || 120,
            allowedIps: k.allowedIps || '',
        });
        setEditId(k.id);
        setDialog('edit');
    };

    const save = async () => {
        if (!form.name.trim()) { toast('Name is required', 'error'); return; }
        if (form.permissions.length === 0) { toast('Select at least one permission', 'error'); return; }
        const payload = {
            name: form.name.trim(),
            permissions: form.permissions,
            expiresAt: form.expiresAt || null,
            rateLimitPerMinute: Number(form.rateLimitPerMinute) || 120,
            allowedIps: form.allowedIps.trim() || null,
        };
        try {
            if (dialog === 'edit') {
                await api.put(`/admin/api-keys/${editId}`, payload);
                toast('API key updated'); setDialog(null); loadKeys();
            } else {
                const res = await api.post('/admin/api-keys', payload);
                setCreatedKey(res.data); setDialog(null); loadKeys();
            }
        } catch (e) {
            // No fake-key fallback — surface the real error so the operator knows the key was NOT created.
            toast((dialog === 'edit' ? 'Update' : 'Create') + ' failed: ' + (e.response?.data?.error || e.message), 'error');
        }
    };

    const revokeKey = async (id) => {
        if (!window.confirm('Revoke this API key? Any integration using it will immediately start receiving 401s.')) return;
        try { await api.delete(`/admin/api-keys/${id}`); toast('API key revoked'); loadKeys(); }
        catch (e) { toast('Revoke failed: ' + (e.response?.data?.error || e.message), 'error'); }
    };

    const viewUsage = async (id) => {
        setUsageLoading(true); setUsageDetail({ keyId: id, data: null });
        try { const res = await api.get(`/admin/api-keys/${id}/usage`); setUsageDetail({ keyId: id, data: res.data }); }
        catch (e) { toast('Could not load usage: ' + (e.response?.data?.error || e.message), 'error'); setUsageDetail(null); }
        finally { setUsageLoading(false); }
    };

    const copyToClipboard = (text) => { if (!text) return; navigator.clipboard.writeText(text); toast('Copied to clipboard'); };

    const cardSx = { p: 2.5, borderRadius: 2, border: `1px solid ${isDark ? '#333' : '#E5E7EB'}`, bgcolor: isDark ? '#1a1a2e' : '#fff' };
    const methodChip = { bgcolor: isDark ? '#1e3a5f' : '#DBEAFE', color: isDark ? '#93C5FD' : '#1D4ED8', fontWeight: 700, fontFamily: 'monospace', fontSize: 11 };

    const isExpired = (k) => k.expiresAt && new Date(k.expiresAt) < new Date();

    return (
        <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1400, mx: 'auto' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3 }}>
                <Code size={28} color={theme.palette.primary.main} />
                <Typography variant="h5" fontWeight={700}>API Management</Typography>
                <Chip label={`${keys.filter(k => k.isActive).length} Active`} color="primary" variant="outlined" sx={{ ml: 'auto' }} />
            </Box>

            <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 3, borderBottom: 1, borderColor: 'divider' }}>
                <Tab label="API Keys" icon={<Key size={16} />} iconPosition="start" />
                <Tab label="Documentation" icon={<Code size={16} />} iconPosition="start" />
                <Tab label="Usage" icon={<Activity size={16} />} iconPosition="start" />
            </Tabs>

            {/* ── API Keys ── */}
            {tab === 0 && (<>
                <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: 2 }}>
                    <Button variant="contained" startIcon={<Plus size={16} />} onClick={openCreate}>Generate API Key</Button>
                </Box>
                <Paper sx={cardSx}>
                    {loading ? (
                        <Box sx={{ textAlign: 'center', py: 6 }}><CircularProgress size={28} /></Box>
                    ) : (
                        <Table>
                            <TableHead><TableRow>
                                <TableCell>Name</TableCell><TableCell>Key</TableCell><TableCell>Permissions</TableCell>
                                <TableCell>Limits</TableCell><TableCell>Expiry</TableCell><TableCell>Last Used</TableCell>
                                <TableCell>Requests</TableCell><TableCell>Status</TableCell><TableCell align="right">Actions</TableCell>
                            </TableRow></TableHead>
                            <TableBody>
                                {keys.map(k => (
                                    <TableRow key={k.id} sx={{ opacity: k.isActive ? 1 : 0.5 }}>
                                        <TableCell><Typography variant="body2" fontWeight={600}>{k.name}</Typography></TableCell>
                                        <TableCell>
                                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                                <code style={{ fontSize: 12, color: isDark ? '#A5B4FC' : '#4338CA' }}>{k.keyPrefix || 'aqr_****...'}</code>
                                                <IconButton size="small" onClick={() => copyToClipboard(k.keyPrefix)}><Copy size={12} /></IconButton>
                                            </Box>
                                        </TableCell>
                                        <TableCell><Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', maxWidth: 220 }}>
                                            {(k.permissions || []).map(p => <Chip key={p} label={p.replace('read:', 'R:').replace('write:', 'W:')} size="small" variant="outlined" sx={{ fontSize: 10 }} />)}
                                        </Box></TableCell>
                                        <TableCell><Typography variant="caption">{k.rateLimitPerMinute || 120}/min{k.allowedIps ? ' • IP-locked' : ''}</Typography></TableCell>
                                        <TableCell>
                                            {k.expiresAt
                                                ? <Chip size="small" variant="outlined" color={isExpired(k) ? 'error' : 'default'}
                                                        label={isExpired(k) ? 'Expired' : new Date(k.expiresAt).toLocaleDateString()} />
                                                : <Typography variant="caption" color="text.secondary">Never</Typography>}
                                        </TableCell>
                                        <TableCell><Typography variant="caption">{k.lastUsed ? new Date(k.lastUsed).toLocaleDateString() : 'Never'}</Typography></TableCell>
                                        <TableCell>{(k.requestCount || 0).toLocaleString()}</TableCell>
                                        <TableCell><Chip size="small" label={k.isActive ? 'Active' : 'Revoked'} color={k.isActive ? 'success' : 'default'} variant="outlined" /></TableCell>
                                        <TableCell align="right">
                                            <Tooltip title="Usage"><IconButton size="small" onClick={() => { setTab(2); viewUsage(k.id); }}><Activity size={14} /></IconButton></Tooltip>
                                            {k.isActive && <Tooltip title="Edit"><IconButton size="small" onClick={() => openEdit(k)}><Edit2 size={14} /></IconButton></Tooltip>}
                                            {k.isActive && <Tooltip title="Revoke"><IconButton size="small" color="error" onClick={() => revokeKey(k.id)}><Trash2 size={14} /></IconButton></Tooltip>}
                                        </TableCell>
                                    </TableRow>
                                ))}
                                {keys.length === 0 && <TableRow><TableCell colSpan={9} align="center" sx={{ py: 4 }}><Key size={32} color="#9CA3AF" /><Typography color="text.secondary" sx={{ mt: 1 }}>No API keys yet</Typography></TableCell></TableRow>}
                            </TableBody>
                        </Table>
                    )}
                </Paper>
            </>)}

            {/* ── Documentation ── */}
            {tab === 1 && (
                <Paper sx={cardSx}>
                    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1, flexWrap: 'wrap', gap: 1 }}>
                        <Typography variant="h6" fontWeight={600}>API Endpoints</Typography>
                        <Button size="small" variant="outlined" startIcon={<ExternalLink size={14} />}
                            component="a" href="/api/v1/openapi.json" target="_blank" rel="noopener">
                            OpenAPI spec
                        </Button>
                    </Box>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                        All endpoints authenticate with the <code>X-API-Key</code> header. Each is gated by the scope shown; a key only sees endpoints it was granted.
                    </Typography>
                    <Alert severity="info" sx={{ mb: 2 }}>
                        Rate limit is per-key (default 120/min; configurable). Responses are paginated. <code>/api/v1/transactions</code> requires a date range no wider than 92 days.
                    </Alert>

                    {API_DOCS.map(group => (
                        <Box key={group.group} sx={{ mb: 2.5 }}>
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                                <Typography variant="subtitle2" fontWeight={700}>{group.group}</Typography>
                                <Chip label={group.scope} size="small" variant="outlined" sx={{ fontSize: 10 }} />
                            </Box>
                            <Table size="small">
                                <TableBody>
                                    {group.rows.map((doc, i) => (
                                        <TableRow key={i}>
                                            <TableCell width={70}><Chip label={doc.method} size="small" sx={methodChip} /></TableCell>
                                            <TableCell width={280}><code style={{ fontSize: 12 }}>{doc.path}</code></TableCell>
                                            <TableCell>{doc.desc}</TableCell>
                                            <TableCell><Typography variant="caption" color="text.secondary">{doc.params}</Typography></TableCell>
                                        </TableRow>
                                    ))}
                                </TableBody>
                            </Table>
                        </Box>
                    ))}

                    <Divider sx={{ my: 2 }} />
                    <Typography variant="subtitle2" fontWeight={600} sx={{ mb: 1 }}>Example Request</Typography>
                    <Paper sx={{ p: 2, bgcolor: isDark ? '#0D1117' : '#F6F8FA', borderRadius: 1 }}>
                        <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap', fontFamily: 'monospace', color: isDark ? '#C9D1D9' : '#24292F' }}>{`curl -H "X-API-Key: aqr_your_key_here" \\
     "https://your-domain/api/v1/finance/summary?startDate=2026-06-01&endDate=2026-06-30"

# Response
{
  "startDate": "2026-06-01",
  "endDate": "2026-06-30",
  "totals": {
    "txns": 128340, "volume": 4820150.50, "msf": 68420.30,
    "interchange": 21030.10, "scheme_fee": 8110.00, "vat": 3421.05,
    "net_revenue": 35859.15
  }
}`}</pre>
                    </Paper>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                        <ShieldAlert size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />
                        Rate-limit headers <code>X-RateLimit-Limit</code> / <code>X-RateLimit-Remaining</code> are returned on every response; a <code>429</code> includes <code>Retry-After</code>.
                    </Typography>
                </Paper>
            )}

            {/* ── Usage ── */}
            {tab === 2 && (
                <Box>
                    <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: 2 }}>
                        <Button size="small" startIcon={<RefreshCw size={14} />} onClick={() => { loadSummary(); if (usageDetail) viewUsage(usageDetail.keyId); }}>Refresh</Button>
                    </Box>
                    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' }, gap: 2, mb: 3 }}>
                        {[
                            { label: 'Requests (24h)', value: summary?.requests24h ?? '—' },
                            { label: 'Requests (7d)', value: summary?.requests7d ?? '—' },
                            { label: 'Errors (7d)', value: summary?.errors7d ?? '—' },
                            { label: 'Active Keys', value: summary?.activeKeys ?? '—' },
                        ].map(s => (
                            <Paper key={s.label} sx={cardSx}>
                                <Typography variant="h5" fontWeight={700}>{typeof s.value === 'number' ? s.value.toLocaleString() : s.value}</Typography>
                                <Typography variant="caption" color="text.secondary">{s.label}</Typography>
                            </Paper>
                        ))}
                    </Box>

                    <Paper sx={{ ...cardSx, mb: 3 }}>
                        <Typography variant="subtitle2" fontWeight={600} sx={{ mb: 1 }}>Top Endpoints (7d)</Typography>
                        {(!summary || !summary.topEndpoints || summary.topEndpoints.length === 0) ? (
                            <Typography variant="body2" color="text.secondary">No traffic recorded yet.</Typography>
                        ) : (
                            <Table size="small">
                                <TableBody>
                                    {summary.topEndpoints.map((e, i) => (
                                        <TableRow key={i}>
                                            <TableCell><code style={{ fontSize: 12 }}>{e.endpoint}</code></TableCell>
                                            <TableCell align="right">{Number(e.hits).toLocaleString()}</TableCell>
                                        </TableRow>
                                    ))}
                                </TableBody>
                            </Table>
                        )}
                    </Paper>

                    <Paper sx={cardSx}>
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                            <Typography variant="subtitle2" fontWeight={600}>Per-Key Detail</Typography>
                            <TextField select size="small" value={usageDetail?.keyId || ''} sx={{ minWidth: 220, ml: 1 }}
                                onChange={e => viewUsage(e.target.value)}>
                                <MenuItem value="" disabled>Select a key…</MenuItem>
                                {keys.map(k => <MenuItem key={k.id} value={k.id}>{k.name}</MenuItem>)}
                            </TextField>
                        </Box>
                        {usageLoading ? (
                            <Box sx={{ textAlign: 'center', py: 4 }}><CircularProgress size={24} /></Box>
                        ) : usageDetail?.data ? (
                            <Box>
                                <Box sx={{ display: 'flex', gap: 3, mb: 2, flexWrap: 'wrap' }}>
                                    <Typography variant="body2"><b>{Number(usageDetail.data.requests24h).toLocaleString()}</b> reqs / 24h</Typography>
                                    <Typography variant="body2"><b>{Number(usageDetail.data.requests7d).toLocaleString()}</b> reqs / 7d</Typography>
                                    <Typography variant="body2"><b>{Number(usageDetail.data.errors7d).toLocaleString()}</b> errors / 7d</Typography>
                                    <Typography variant="body2"><b>{usageDetail.data.avgLatencyMs}</b> ms avg</Typography>
                                </Box>
                                <Typography variant="caption" color="text.secondary">Recent requests</Typography>
                                <Table size="small">
                                    <TableHead><TableRow>
                                        <TableCell>Method</TableCell><TableCell>Endpoint</TableCell><TableCell>Status</TableCell>
                                        <TableCell>IP</TableCell><TableCell>Latency</TableCell><TableCell>When</TableCell>
                                    </TableRow></TableHead>
                                    <TableBody>
                                        {(usageDetail.data.recent || []).map((r, i) => (
                                            <TableRow key={i}>
                                                <TableCell><Chip label={r.method} size="small" sx={methodChip} /></TableCell>
                                                <TableCell><code style={{ fontSize: 11 }}>{r.endpoint}</code></TableCell>
                                                <TableCell><Chip size="small" label={r.status} color={r.status >= 400 ? 'error' : 'success'} variant="outlined" /></TableCell>
                                                <TableCell><Typography variant="caption">{r.clientIp}</Typography></TableCell>
                                                <TableCell><Typography variant="caption">{r.latencyMs}ms</Typography></TableCell>
                                                <TableCell><Typography variant="caption">{r.createdAt ? new Date(r.createdAt).toLocaleString() : '—'}</Typography></TableCell>
                                            </TableRow>
                                        ))}
                                        {(!usageDetail.data.recent || usageDetail.data.recent.length === 0) &&
                                            <TableRow><TableCell colSpan={6} align="center" sx={{ py: 2, color: 'text.secondary' }}>No requests yet</TableCell></TableRow>}
                                    </TableBody>
                                </Table>
                            </Box>
                        ) : (
                            <Typography variant="body2" color="text.secondary">Select a key to see its recent traffic.</Typography>
                        )}
                    </Paper>
                </Box>
            )}

            {/* ── Create / Edit Dialog ── */}
            <Dialog open={dialog === 'create' || dialog === 'edit'} onClose={() => setDialog(null)} maxWidth="sm" fullWidth>
                <DialogTitle>{dialog === 'edit' ? 'Edit API Key' : 'Generate New API Key'}</DialogTitle>
                <DialogContent sx={{ pt: '16px !important' }}>
                    <TextField fullWidth label="Key Name" value={form.name} sx={{ mb: 2 }}
                        onChange={e => setForm({ ...form, name: e.target.value })} helperText="e.g. 'Mobile App', 'Partner ETL'" />

                    <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2, mb: 1 }}>
                        <TextField label="Expiry (optional)" type="date" InputLabelProps={{ shrink: true }}
                            value={form.expiresAt} onChange={e => setForm({ ...form, expiresAt: e.target.value })}
                            InputProps={{ startAdornment: <Clock size={14} style={{ marginRight: 6, opacity: 0.6 }} /> }} />
                        <TextField label="Rate limit (req/min)" type="number" value={form.rateLimitPerMinute}
                            onChange={e => setForm({ ...form, rateLimitPerMinute: e.target.value })} inputProps={{ min: 1 }} />
                    </Box>
                    <TextField fullWidth label="IP Allowlist (optional)" value={form.allowedIps} sx={{ mb: 2 }}
                        onChange={e => setForm({ ...form, allowedIps: e.target.value })}
                        helperText="Comma-separated IPs. Blank = any source IP."
                        InputProps={{ startAdornment: <Globe size={14} style={{ marginRight: 6, opacity: 0.6 }} /> }} />

                    <Typography variant="subtitle2" fontWeight={600} sx={{ mb: 1 }}>Permissions (scopes)</Typography>
                    {PERMISSIONS.map(p => (
                        <FormControlLabel key={p.key} control={<Switch size="small" checked={form.permissions.includes(p.key)}
                            onChange={e => setForm(f => ({ ...f, permissions: e.target.checked ? [...f.permissions, p.key] : f.permissions.filter(x => x !== p.key) }))} />}
                            label={<Box><Typography variant="body2">{p.label} <code style={{ fontSize: 11, opacity: 0.7 }}>{p.key}</code></Typography><Typography variant="caption" color="text.secondary">{p.desc}</Typography></Box>} />
                    ))}
                </DialogContent>
                <DialogActions>
                    <Button onClick={() => setDialog(null)}>Cancel</Button>
                    <Button variant="contained" onClick={save}>{dialog === 'edit' ? 'Save' : 'Generate'}</Button>
                </DialogActions>
            </Dialog>

            {/* ── Key Created Dialog ── */}
            <Dialog open={!!createdKey} maxWidth="sm" fullWidth>
                <DialogTitle sx={{ color: '#10B981' }}>API Key Created</DialogTitle>
                <DialogContent>
                    <Alert severity="warning" sx={{ mb: 2 }}>Copy this key now — it will not be shown again. Only a hash is stored.</Alert>
                    <Paper sx={{ p: 2, bgcolor: isDark ? '#0D1117' : '#FEF3C7', borderRadius: 1, wordBreak: 'break-all' }}>
                        <Typography fontFamily="monospace" fontSize={14}>{createdKey?.apiKey}</Typography>
                    </Paper>
                    <Button fullWidth variant="outlined" sx={{ mt: 2 }} startIcon={<Copy size={16} />} onClick={() => copyToClipboard(createdKey?.apiKey)}>Copy to Clipboard</Button>
                </DialogContent>
                <DialogActions><Button variant="contained" onClick={() => setCreatedKey(null)}>Done</Button></DialogActions>
            </Dialog>

            <Snackbar open={snack.open} autoHideDuration={3500} onClose={() => setSnack(s => ({ ...s, open: false }))}
                anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
                <Alert severity={snack.severity} variant="filled" onClose={() => setSnack(s => ({ ...s, open: false }))}>{snack.msg}</Alert>
            </Snackbar>
        </Box>
    );
};

export default ApiManagement;
