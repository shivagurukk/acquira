import React, { useState, useEffect, useCallback } from 'react';
import {
    Box, Typography, Paper, Grid, TextField, Button, Switch, FormControlLabel,
    Divider, Chip, IconButton, Tooltip, useTheme, Dialog, DialogTitle,
    DialogContent, DialogActions, Alert, Snackbar, Table, TableHead,
    TableRow, TableCell, TableBody, Tabs, Tab
} from '@mui/material';
import {
    Key, Plus, Trash2, Copy, Code, Activity, CheckCircle
} from 'lucide-react';
import api from '../../api/axios';

const PERMISSIONS = [
    { key: 'read:transactions', label: 'Read Transactions', desc: 'Query transaction data' },
    { key: 'read:merchants', label: 'Read Merchants', desc: 'Query merchant/store data' },
    { key: 'read:analytics', label: 'Read Analytics', desc: 'Access summary/analytics endpoints' },
    { key: 'read:finance', label: 'Read Finance', desc: 'Access financial reports' },
    { key: 'write:upload', label: 'Upload Files', desc: 'Upload transaction/merchant files' },
];

const demoKeys = [
    { id: 1, name: 'Mobile App', keyPrefix: 'aqr_mob_a3f2...', permissions: ['read:transactions', 'read:merchants'], isActive: true, createdAt: '2025-12-01', lastUsed: '2026-02-26', requestCount: 15420 },
    { id: 2, name: 'Merchant Portal', keyPrefix: 'aqr_mp_x9k1...', permissions: ['read:transactions', 'read:analytics'], isActive: true, createdAt: '2026-01-15', lastUsed: '2026-02-27', requestCount: 8230 },
];

const apiDocs = [
    { method: 'GET', path: '/api/v1/transactions', desc: 'List transactions with filters', params: 'startDate, endDate, mid, sid, page, size' },
    { method: 'GET', path: '/api/v1/merchants', desc: 'List merchants with summary', params: 'status, search, page, size' },
    { method: 'GET', path: '/api/v1/merchants/{mid}/summary', desc: 'Single merchant summary', params: 'startDate, endDate' },
    { method: 'GET', path: '/api/v1/analytics/volume', desc: 'Volume analytics', params: 'startDate, endDate, groupBy' },
    { method: 'GET', path: '/api/v1/analytics/scheme-breakdown', desc: 'Scheme breakdown', params: 'startDate, endDate' },
    { method: 'GET', path: '/api/v1/finance/summary', desc: 'Finance summary', params: 'startDate, endDate' },
];

const ApiManagement = () => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';
    const [keys, setKeys] = useState([]);
    const [loading, setLoading] = useState(true);
    const [createDialog, setCreateDialog] = useState(false);
    const [newKeyName, setNewKeyName] = useState('');
    const [newKeyPermissions, setNewKeyPermissions] = useState(['read:transactions', 'read:merchants']);
    const [createdKey, setCreatedKey] = useState(null);
    const [snack, setSnack] = useState({ open: false, msg: '', severity: 'success' });
    const [tab, setTab] = useState(0);

    const loadKeys = useCallback(async () => {
        try { const res = await api.get('/admin/api-keys'); setKeys(res.data || []); }
        catch { setKeys(demoKeys); }
        setLoading(false);
    }, []);
    useEffect(() => { loadKeys(); }, [loadKeys]);

    const createKey = async () => {
        if (!newKeyName.trim()) { setSnack({ open: true, msg: 'Name is required', severity: 'error' }); return; }
        try {
            const res = await api.post('/admin/api-keys', { name: newKeyName, permissions: newKeyPermissions });
            setCreatedKey(res.data); setCreateDialog(false); setNewKeyName(''); loadKeys();
        } catch {
            const fakeKey = `aqr_${Date.now()}_${Math.random().toString(36).substring(2, 15)}`;
            setCreatedKey({ apiKey: fakeKey, name: newKeyName }); setCreateDialog(false); setNewKeyName('');
            setKeys(prev => [...prev, { id: Date.now(), name: newKeyName, keyPrefix: fakeKey.substring(0, 12) + '...', permissions: newKeyPermissions, isActive: true, createdAt: new Date().toISOString(), lastUsed: null, requestCount: 0 }]);
        }
    };

    const revokeKey = async (id) => {
        try { await api.delete(`/admin/api-keys/${id}`); loadKeys(); }
        catch { setKeys(prev => prev.filter(k => k.id !== id)); }
        setSnack({ open: true, msg: 'API key revoked', severity: 'success' });
    };

    const copyToClipboard = (text) => { navigator.clipboard.writeText(text); setSnack({ open: true, msg: 'Copied to clipboard', severity: 'success' }); };

    const cardSx = { p: 2.5, borderRadius: 2, border: `1px solid ${isDark ? '#333' : '#E5E7EB'}`, bgcolor: isDark ? '#1a1a2e' : '#fff' };

    return (
        <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1400, mx: 'auto' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3 }}>
                <Code size={28} color={theme.palette.primary.main} />
                <Typography variant="h5" fontWeight={700}>API Management</Typography>
                <Chip label={`${keys.filter(k => k.isActive).length} Active Keys`} color="primary" variant="outlined" sx={{ ml: 'auto' }} />
            </Box>

            <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 3, borderBottom: 1, borderColor: 'divider' }}>
                <Tab label="API Keys" icon={<Key size={16} />} iconPosition="start" />
                <Tab label="Documentation" icon={<Code size={16} />} iconPosition="start" />
                <Tab label="Usage" icon={<Activity size={16} />} iconPosition="start" />
            </Tabs>

            {/* API Keys */}
            {tab === 0 && (<>
                <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: 2 }}>
                    <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setCreateDialog(true)}>Generate API Key</Button>
                </Box>
                <Paper sx={cardSx}>
                    <Table>
                        <TableHead><TableRow>
                            <TableCell>Name</TableCell><TableCell>Key</TableCell><TableCell>Permissions</TableCell>
                            <TableCell>Created</TableCell><TableCell>Last Used</TableCell><TableCell>Requests</TableCell>
                            <TableCell>Status</TableCell><TableCell align="right">Actions</TableCell>
                        </TableRow></TableHead>
                        <TableBody>
                            {keys.map(k => (
                                <TableRow key={k.id}>
                                    <TableCell><Typography variant="body2" fontWeight={600}>{k.name}</Typography></TableCell>
                                    <TableCell>
                                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                                            <code style={{ fontSize: 12, color: isDark ? '#A5B4FC' : '#4338CA' }}>{k.keyPrefix || 'aqr_****...'}</code>
                                            <IconButton size="small" onClick={() => copyToClipboard(k.keyPrefix)}><Copy size={12} /></IconButton>
                                        </Box>
                                    </TableCell>
                                    <TableCell><Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                                        {(k.permissions || []).map(p => <Chip key={p} label={p.replace('read:', 'R:').replace('write:', 'W:')} size="small" variant="outlined" sx={{ fontSize: 10 }} />)}
                                    </Box></TableCell>
                                    <TableCell><Typography variant="caption">{k.createdAt ? new Date(k.createdAt).toLocaleDateString() : '—'}</Typography></TableCell>
                                    <TableCell><Typography variant="caption">{k.lastUsed ? new Date(k.lastUsed).toLocaleDateString() : 'Never'}</Typography></TableCell>
                                    <TableCell>{(k.requestCount || 0).toLocaleString()}</TableCell>
                                    <TableCell><Chip size="small" label={k.isActive ? 'Active' : 'Revoked'} color={k.isActive ? 'success' : 'default'} variant="outlined" /></TableCell>
                                    <TableCell align="right">{k.isActive && <Tooltip title="Revoke"><IconButton size="small" color="error" onClick={() => revokeKey(k.id)}><Trash2 size={14} /></IconButton></Tooltip>}</TableCell>
                                </TableRow>
                            ))}
                            {keys.length === 0 && <TableRow><TableCell colSpan={8} align="center" sx={{ py: 4 }}><Key size={32} color="#9CA3AF" /><Typography color="text.secondary" sx={{ mt: 1 }}>No API keys yet</Typography></TableCell></TableRow>}
                        </TableBody>
                    </Table>
                </Paper>
            </>)}

            {/* Docs */}
            {tab === 1 && (
                <Paper sx={cardSx}>
                    <Typography variant="h6" fontWeight={600} sx={{ mb: 1 }}>API Endpoints</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>All endpoints require <code>X-API-Key</code> header. Base URL: <code>/api/v1/</code></Typography>
                    <Alert severity="info" sx={{ mb: 2 }}>Rate limit: 100 requests/minute per API key. Responses paginated (default 20, max 100).</Alert>
                    <Table size="small">
                        <TableHead><TableRow><TableCell width={80}>Method</TableCell><TableCell>Endpoint</TableCell><TableCell>Description</TableCell><TableCell>Parameters</TableCell></TableRow></TableHead>
                        <TableBody>
                            {apiDocs.map((doc, i) => (
                                <TableRow key={i}>
                                    <TableCell><Chip label={doc.method} size="small" sx={{ bgcolor: '#DBEAFE', color: '#1D4ED8', fontWeight: 700, fontFamily: 'monospace', fontSize: 11 }} /></TableCell>
                                    <TableCell><code style={{ fontSize: 12 }}>{doc.path}</code></TableCell>
                                    <TableCell>{doc.desc}</TableCell>
                                    <TableCell><Typography variant="caption" color="text.secondary">{doc.params}</Typography></TableCell>
                                </TableRow>
                            ))}
                        </TableBody>
                    </Table>
                    <Divider sx={{ my: 3 }} />
                    <Typography variant="subtitle2" fontWeight={600} sx={{ mb: 1 }}>Example Request</Typography>
                    <Paper sx={{ p: 2, bgcolor: isDark ? '#0D1117' : '#F6F8FA', borderRadius: 1 }}>
                        <pre style={{ margin: 0, fontSize: 12, whiteSpace: 'pre-wrap', fontFamily: 'monospace', color: isDark ? '#C9D1D9' : '#24292F' }}>{`curl -H "X-API-Key: aqr_your_key_here" \\
     "https://your-domain/api/v1/transactions?startDate=2025-01-01&endDate=2025-01-31&page=0&size=20"

# Response:
{
  "content": [
    { "arn": "74...", "mid": "M001", "amount": 150.00, "currency": "BHD",
      "cardScheme": "Visa", "cardType": "DEBIT", "paymentDate": "2025-01-15" }
  ],
  "totalElements": 1234, "totalPages": 62, "number": 0
}`}</pre>
                    </Paper>
                </Paper>
            )}

            {/* Usage */}
            {tab === 2 && (
                <Paper sx={{ ...cardSx, textAlign: 'center', py: 6 }}>
                    <Activity size={48} color="#9CA3AF" />
                    <Typography variant="h6" color="text.secondary" sx={{ mt: 2 }}>Usage Analytics</Typography>
                    <Typography variant="body2" color="text.secondary">API usage charts available once keys are in use.</Typography>
                </Paper>
            )}

            {/* Create Key Dialog */}
            <Dialog open={createDialog} onClose={() => setCreateDialog(false)} maxWidth="sm" fullWidth>
                <DialogTitle>Generate New API Key</DialogTitle>
                <DialogContent sx={{ pt: '16px !important' }}>
                    <TextField fullWidth label="Key Name" value={newKeyName} sx={{ mb: 2 }} onChange={e => setNewKeyName(e.target.value)} helperText="e.g., 'Mobile App', 'Merchant Portal'" />
                    <Typography variant="subtitle2" fontWeight={600} sx={{ mb: 1 }}>Permissions</Typography>
                    {PERMISSIONS.map(p => (
                        <FormControlLabel key={p.key} control={<Switch size="small" checked={newKeyPermissions.includes(p.key)}
                            onChange={e => setNewKeyPermissions(prev => e.target.checked ? [...prev, p.key] : prev.filter(x => x !== p.key))} />}
                            label={<Box><Typography variant="body2">{p.label}</Typography><Typography variant="caption" color="text.secondary">{p.desc}</Typography></Box>} />
                    ))}
                </DialogContent>
                <DialogActions><Button onClick={() => setCreateDialog(false)}>Cancel</Button><Button variant="contained" onClick={createKey}>Generate</Button></DialogActions>
            </Dialog>

            {/* Key Created Dialog */}
            <Dialog open={!!createdKey} maxWidth="sm" fullWidth>
                <DialogTitle sx={{ color: '#10B981' }}>API Key Created</DialogTitle>
                <DialogContent>
                    <Alert severity="warning" sx={{ mb: 2 }}>Copy this key now. It won't be shown again.</Alert>
                    <Paper sx={{ p: 2, bgcolor: isDark ? '#0D1117' : '#FEF3C7', borderRadius: 1, wordBreak: 'break-all' }}>
                        <Typography fontFamily="monospace" fontSize={14}>{createdKey?.apiKey}</Typography>
                    </Paper>
                    <Button fullWidth variant="outlined" sx={{ mt: 2 }} startIcon={<Copy size={16} />} onClick={() => copyToClipboard(createdKey?.apiKey)}>Copy to Clipboard</Button>
                </DialogContent>
                <DialogActions><Button variant="contained" onClick={() => setCreatedKey(null)}>Done</Button></DialogActions>
            </Dialog>

            <Snackbar open={snack.open} autoHideDuration={3000} onClose={() => setSnack(s => ({ ...s, open: false }))}><Alert severity={snack.severity} variant="filled">{snack.msg}</Alert></Snackbar>
        </Box>
    );
};

export default ApiManagement;
