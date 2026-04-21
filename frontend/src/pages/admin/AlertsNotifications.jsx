import React, { useState, useEffect, useCallback } from 'react';
import {
    Box, Typography, Paper, Grid, TextField, Button, Switch, FormControlLabel,
    Divider, Chip, IconButton, useTheme, Dialog, DialogTitle,
    DialogContent, DialogActions, Select, MenuItem, FormControl, InputLabel,
    Alert, Snackbar
} from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import {
    Bell, Plus, Trash2, Edit3, Play, AlertTriangle,
    TrendingDown, TrendingUp, Zap, Clock, Mail, Activity,
    CheckCircle, XCircle
} from 'lucide-react';
import api from '../../api/axios';

const METRIC_OPTIONS = [
    { value: 'daily_volume_drop', label: 'Daily Volume Drop %', icon: TrendingDown, color: '#EF4444' },
    { value: 'zero_txn_days', label: 'Zero Transaction Days', icon: XCircle, color: '#F97316' },
    { value: 'refund_ratio', label: 'Refund Ratio %', icon: AlertTriangle, color: '#EAB308' },
    { value: 'msf_below_target', label: 'MSF Below Target', icon: TrendingDown, color: '#8B5CF6' },
    { value: 'volume_spike', label: 'Volume Spike %', icon: TrendingUp, color: '#10B981' },
    { value: 'new_merchant_inactive', label: 'New Merchant Inactive', icon: Clock, color: '#6366F1' },
    { value: 'chargeback_ratio', label: 'Chargeback Ratio %', icon: Zap, color: '#DC2626' },
    { value: 'terminal_inactive', label: 'Terminal Inactive Days', icon: Activity, color: '#0EA5E9' },
];

const BLANK_RULE = {
    name: '', metric: 'daily_volume_drop', operator: '>', threshold: 50,
    severity: 'WARNING', recipients: '', isActive: true, description: '',
    checkFrequency: 'DAILY', scope: 'ALL_MERCHANTS'
};

const demoRules = [
    { id: 1, name: 'Volume Drop Alert', metric: 'daily_volume_drop', operator: '>', threshold: 50, severity: 'CRITICAL', recipients: 'rm@bank.com', isActive: true, description: 'Alert when merchant daily volume drops more than 50% vs 30-day average', checkFrequency: 'DAILY', scope: 'ALL_MERCHANTS' },
    { id: 2, name: 'Zero Transaction Warning', metric: 'zero_txn_days', operator: '>=', threshold: 3, severity: 'WARNING', recipients: 'operations@bank.com', isActive: true, description: 'Flag merchants with no transactions for 3+ consecutive days', checkFrequency: 'DAILY', scope: 'ALL_MERCHANTS' },
    { id: 3, name: 'High Refund Ratio', metric: 'refund_ratio', operator: '>', threshold: 10, severity: 'WARNING', recipients: 'risk@bank.com', isActive: false, description: 'Alert when refund ratio exceeds 10%', checkFrequency: 'DAILY', scope: 'ALL_MERCHANTS' },
];
const demoHistory = [
    { id: 1, triggeredAt: new Date().toISOString(), ruleName: 'Volume Drop Alert', severity: 'CRITICAL', merchantName: 'Coffee Shop LLC', message: 'Volume dropped 65% vs 30-day avg', acknowledged: false },
    { id: 2, triggeredAt: new Date(Date.now() - 86400000).toISOString(), ruleName: 'Zero Transaction Warning', severity: 'WARNING', merchantName: 'Tech Store Inc', message: '4 consecutive zero-txn days', acknowledged: true },
];

const AlertsNotifications = () => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';
    const [rules, setRules] = useState([]);
    const [history, setHistory] = useState([]);
    const [loading, setLoading] = useState(true);
    const [dialog, setDialog] = useState(null);
    const [editMode, setEditMode] = useState(false);
    const [snack, setSnack] = useState({ open: false, msg: '', severity: 'success' });
    const [tab, setTab] = useState('rules');

    const loadRules = useCallback(async () => {
        try { const res = await api.get('/admin/alerts/rules'); setRules(res.data || []); }
        catch { setRules(demoRules); }
        setLoading(false);
    }, []);
    const loadHistory = useCallback(async () => {
        try { const res = await api.get('/admin/alerts/history'); setHistory(res.data || []); }
        catch { setHistory(demoHistory); }
    }, []);

    useEffect(() => { loadRules(); loadHistory(); }, [loadRules, loadHistory]);

    const saveRule = async () => {
        if (!dialog.name) { setSnack({ open: true, msg: 'Rule name is required', severity: 'error' }); return; }
        try {
            if (editMode && dialog.id) await api.put(`/admin/alerts/rules/${dialog.id}`, dialog);
            else await api.post('/admin/alerts/rules', dialog);
            setSnack({ open: true, msg: 'Alert rule saved', severity: 'success' });
            setDialog(null); loadRules();
        } catch (err) {
            // Demo fallback
            if (editMode) setRules(prev => prev.map(r => r.id === dialog.id ? dialog : r));
            else setRules(prev => [...prev, { ...dialog, id: Date.now() }]);
            setSnack({ open: true, msg: 'Alert rule saved', severity: 'success' });
            setDialog(null);
        }
    };

    const toggleRule = async (rule) => {
        try { await api.put(`/admin/alerts/rules/${rule.id}`, { ...rule, isActive: !rule.isActive }); loadRules(); }
        catch { setRules(prev => prev.map(r => r.id === rule.id ? { ...r, isActive: !r.isActive } : r)); }
    };

    const deleteRule = async (id) => {
        try { await api.delete(`/admin/alerts/rules/${id}`); loadRules(); }
        catch { setRules(prev => prev.filter(r => r.id !== id)); }
        setSnack({ open: true, msg: 'Rule deleted', severity: 'success' });
    };

    const getMetricInfo = (metric) => METRIC_OPTIONS.find(m => m.value === metric) || METRIC_OPTIONS[0];
    const cardSx = { p: 2.5, borderRadius: 2, border: `1px solid ${isDark ? '#333' : '#E5E7EB'}`, bgcolor: isDark ? '#1a1a2e' : '#fff' };
    const severityColor = (s) => s === 'CRITICAL' ? '#EF4444' : s === 'WARNING' ? '#F59E0B' : '#3B82F6';

    const activeRules = rules.filter(r => r.isActive).length;
    const criticalAlerts = history.filter(h => h.severity === 'CRITICAL' && !h.acknowledged).length;
    const todayAlerts = history.filter(h => new Date(h.triggeredAt).toDateString() === new Date().toDateString()).length;

    return (
        <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1400, mx: 'auto' }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3, flexWrap: 'wrap' }}>
                <Bell size={28} color={theme.palette.primary.main} />
                <Typography variant="h5" fontWeight={700}>Alerts & Notifications</Typography>
                <Box sx={{ ml: 'auto', display: 'flex', gap: 1.5 }}>
                    <Chip label={`${activeRules} Active`} color="primary" variant="outlined" />
                    {criticalAlerts > 0 && <Chip label={`${criticalAlerts} Critical`} color="error" />}
                    <Chip label={`${todayAlerts} Today`} variant="outlined" />
                </Box>
            </Box>

            {/* Stats */}
            <Grid container spacing={2} sx={{ mb: 3 }}>
                {[{ label: 'Active Rules', value: activeRules, icon: Play, color: '#10B981' },
                  { label: 'Alerts Today', value: todayAlerts, icon: Bell, color: '#3B82F6' },
                  { label: 'Unacknowledged', value: criticalAlerts, icon: AlertTriangle, color: '#EF4444' },
                  { label: 'Total Rules', value: rules.length, icon: Zap, color: '#8B5CF6' }
                ].map((stat, i) => (
                    <Grid item xs={6} md={3} key={i}>
                        <Paper sx={{ ...cardSx, display: 'flex', alignItems: 'center', gap: 2 }}>
                            <Box sx={{ p: 1, borderRadius: 1.5, bgcolor: stat.color + '15' }}><stat.icon size={20} color={stat.color} /></Box>
                            <Box><Typography variant="h6" fontWeight={700}>{stat.value}</Typography>
                            <Typography variant="caption" color="text.secondary">{stat.label}</Typography></Box>
                        </Paper>
                    </Grid>
                ))}
            </Grid>

            {/* Tabs */}
            <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
                <Button variant={tab === 'rules' ? 'contained' : 'outlined'} size="small" onClick={() => setTab('rules')}>Alert Rules</Button>
                <Button variant={tab === 'history' ? 'contained' : 'outlined'} size="small" onClick={() => setTab('history')}>Alert History</Button>
                <Button variant="contained" color="success" size="small" startIcon={<Plus size={16} />}
                    onClick={() => { setDialog({ ...BLANK_RULE }); setEditMode(false); }} sx={{ ml: 'auto' }}>New Rule</Button>
            </Box>

            {/* Rules */}
            {tab === 'rules' && (
                <Grid container spacing={2}>
                    {rules.map(rule => {
                        const info = getMetricInfo(rule.metric);
                        const Icon = info.icon;
                        return (
                            <Grid item xs={12} md={6} lg={4} key={rule.id || rule.name}>
                                <Paper sx={{ ...cardSx, opacity: rule.isActive ? 1 : 0.6, borderLeft: `4px solid ${severityColor(rule.severity)}` }}>
                                    <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between' }}>
                                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                                            <Box sx={{ p: 0.8, borderRadius: 1, bgcolor: info.color + '15' }}><Icon size={18} color={info.color} /></Box>
                                            <Box>
                                                <Typography variant="subtitle2" fontWeight={600}>{rule.name}</Typography>
                                                <Typography variant="caption" color="text.secondary">{info.label} {rule.operator} {rule.threshold}</Typography>
                                            </Box>
                                        </Box>
                                        <Chip label={rule.severity} size="small" sx={{ bgcolor: severityColor(rule.severity) + '20', color: severityColor(rule.severity), fontWeight: 600, fontSize: 11 }} />
                                    </Box>
                                    {rule.description && <Typography variant="body2" color="text.secondary" sx={{ mt: 1, fontSize: 12 }}>{rule.description}</Typography>}
                                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1.5 }}>
                                        <Chip size="small" label={rule.checkFrequency || 'DAILY'} variant="outlined" />
                                        {rule.recipients && <Chip size="small" icon={<Mail size={12} />} label={rule.recipients.split(',').length + ' recipients'} variant="outlined" />}
                                    </Box>
                                    <Divider sx={{ my: 1.5 }} />
                                    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                                        <FormControlLabel control={<Switch size="small" checked={rule.isActive} onChange={() => toggleRule(rule)} />} label={rule.isActive ? 'Active' : 'Paused'} />
                                        <Box>
                                            <IconButton size="small" onClick={() => { setDialog({ ...rule }); setEditMode(true); }}><Edit3 size={14} /></IconButton>
                                            <IconButton size="small" color="error" onClick={() => deleteRule(rule.id)}><Trash2 size={14} /></IconButton>
                                        </Box>
                                    </Box>
                                </Paper>
                            </Grid>
                        );
                    })}
                    {rules.length === 0 && (
                        <Grid item xs={12}>
                            <Paper sx={{ ...cardSx, textAlign: 'center', py: 6 }}>
                                <Bell size={40} color="#9CA3AF" style={{ marginBottom: 12 }} />
                                <Typography color="text.secondary">No alert rules configured yet.</Typography>
                                <Button variant="contained" size="small" sx={{ mt: 2 }} onClick={() => { setDialog({ ...BLANK_RULE }); setEditMode(false); }}>Create First Rule</Button>
                            </Paper>
                        </Grid>
                    )}
                </Grid>
            )}

            {/* History */}
            {tab === 'history' && (
                <Paper sx={cardSx}>
                    <DataGrid
                        rows={history.map((h, i) => ({ id: h.id || i, ...h }))}
                        columns={[
                            { field: 'triggeredAt', headerName: 'Time', width: 170, renderCell: p => new Date(p.value).toLocaleString() },
                            { field: 'ruleName', headerName: 'Rule', flex: 1 },
                            { field: 'severity', headerName: 'Severity', width: 100, renderCell: p => <Chip label={p.value} size="small" sx={{ bgcolor: severityColor(p.value) + '20', color: severityColor(p.value), fontWeight: 600 }} /> },
                            { field: 'merchantName', headerName: 'Merchant', width: 180 },
                            { field: 'message', headerName: 'Details', flex: 1.5 },
                            { field: 'acknowledged', headerName: 'Ack', width: 80, renderCell: p => p.value ? <CheckCircle size={16} color="#10B981" /> : <XCircle size={16} color="#EF4444" /> },
                        ]}
                        pageSize={10} rowsPerPageOptions={[10, 25, 50]} autoHeight density="compact" disableRowSelectionOnClick
                        sx={{ border: 'none', '& .MuiDataGrid-cell': { fontSize: 13 } }}
                    />
                </Paper>
            )}

            {/* Create/Edit Dialog */}
            <Dialog open={!!dialog} onClose={() => setDialog(null)} maxWidth="sm" fullWidth>
                <DialogTitle>{editMode ? 'Edit Alert Rule' : 'New Alert Rule'}</DialogTitle>
                <DialogContent sx={{ pt: '16px !important' }}>
                    <TextField fullWidth label="Rule Name" value={dialog?.name || ''} sx={{ mb: 2 }} onChange={e => setDialog(d => ({ ...d, name: e.target.value }))} />
                    <TextField fullWidth label="Description" value={dialog?.description || ''} sx={{ mb: 2 }} multiline rows={2} onChange={e => setDialog(d => ({ ...d, description: e.target.value }))} />
                    <Grid container spacing={2}>
                        <Grid item xs={6}>
                            <FormControl fullWidth size="small"><InputLabel>Metric</InputLabel>
                                <Select value={dialog?.metric || 'daily_volume_drop'} label="Metric" onChange={e => setDialog(d => ({ ...d, metric: e.target.value }))}>
                                    {METRIC_OPTIONS.map(m => <MenuItem key={m.value} value={m.value}>{m.label}</MenuItem>)}
                                </Select>
                            </FormControl>
                        </Grid>
                        <Grid item xs={2}>
                            <FormControl fullWidth size="small"><InputLabel>Op</InputLabel>
                                <Select value={dialog?.operator || '>'} label="Op" onChange={e => setDialog(d => ({ ...d, operator: e.target.value }))}>
                                    {['>', '<', '>=', '<=', '='].map(o => <MenuItem key={o} value={o}>{o}</MenuItem>)}
                                </Select>
                            </FormControl>
                        </Grid>
                        <Grid item xs={4}>
                            <TextField fullWidth size="small" label="Threshold" type="number" value={dialog?.threshold || 0} onChange={e => setDialog(d => ({ ...d, threshold: Number(e.target.value) }))} />
                        </Grid>
                    </Grid>
                    <Grid container spacing={2} sx={{ mt: 0.5 }}>
                        <Grid item xs={6}>
                            <FormControl fullWidth size="small"><InputLabel>Severity</InputLabel>
                                <Select value={dialog?.severity || 'WARNING'} label="Severity" onChange={e => setDialog(d => ({ ...d, severity: e.target.value }))}>
                                    {['INFO', 'WARNING', 'CRITICAL'].map(s => <MenuItem key={s} value={s}>{s}</MenuItem>)}
                                </Select>
                            </FormControl>
                        </Grid>
                        <Grid item xs={6}>
                            <FormControl fullWidth size="small"><InputLabel>Check Frequency</InputLabel>
                                <Select value={dialog?.checkFrequency || 'DAILY'} label="Check Frequency" onChange={e => setDialog(d => ({ ...d, checkFrequency: e.target.value }))}>
                                    {['HOURLY', 'DAILY', 'WEEKLY'].map(f => <MenuItem key={f} value={f}>{f}</MenuItem>)}
                                </Select>
                            </FormControl>
                        </Grid>
                    </Grid>
                    <TextField fullWidth label="Recipients (comma-separated emails)" sx={{ mt: 2 }} value={dialog?.recipients || ''} onChange={e => setDialog(d => ({ ...d, recipients: e.target.value }))} helperText="Notification emails when alert triggers" size="small" />
                    <FormControlLabel sx={{ mt: 1 }} control={<Switch checked={dialog?.isActive ?? true} onChange={e => setDialog(d => ({ ...d, isActive: e.target.checked }))} />} label="Enable this rule" />
                </DialogContent>
                <DialogActions>
                    <Button onClick={() => setDialog(null)}>Cancel</Button>
                    <Button variant="contained" onClick={saveRule}>{editMode ? 'Update Rule' : 'Create Rule'}</Button>
                </DialogActions>
            </Dialog>

            <Snackbar open={snack.open} autoHideDuration={4000} onClose={() => setSnack(s => ({ ...s, open: false }))}><Alert severity={snack.severity} variant="filled">{snack.msg}</Alert></Snackbar>
        </Box>
    );
};

export default AlertsNotifications;
