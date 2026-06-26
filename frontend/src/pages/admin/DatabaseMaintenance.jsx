import React, { useState, useEffect, useCallback } from 'react';
import {
    Box, Typography, Paper, Grid, Button, Switch, FormControlLabel,
    MenuItem, TextField, Chip, Alert, Snackbar, Table, TableHead,
    TableRow, TableCell, TableBody, Tooltip, useTheme, CircularProgress, Divider
} from '@mui/material';
import {
    Database, Save, Play, Clock, CheckCircle, XCircle, AlertTriangle,
    Activity, RefreshCw
} from 'lucide-react';
import api from '../../api/axios';

const HOURS = Array.from({ length: 24 }, (_, i) => i);
const hourLabel = (h) => `${String(h).padStart(2, '0')}:00`;

const RUN_STATUS = {
    SUCCESS: { color: '#10B981', bg: '#10B98118', icon: CheckCircle },
    FAILED:  { color: '#EF4444', bg: '#EF444418', icon: XCircle },
    RUNNING: { color: '#3B82F6', bg: '#3B82F618', icon: Activity },
    SKIPPED: { color: '#64748B', bg: '#64748B18', icon: AlertTriangle },
};

const fmtTs = (s) => s ? new Date(s).toLocaleString() : '—';

const DatabaseMaintenance = () => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';

    const [status, setStatus] = useState(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [running, setRunning] = useState(false);
    const [snack, setSnack] = useState({ open: false, msg: '', severity: 'success' });

    // editable form state
    const [enabled, setEnabled] = useState(true);
    const [startHour, setStartHour] = useState(2);
    const [endHour, setEndHour] = useState(5);
    const [useDefaultTables, setUseDefaultTables] = useState(true);
    const [tablesText, setTablesText] = useState('');

    const applyStatus = (d) => {
        setStatus(d);
        setEnabled(!!d.enabled);
        setStartHour(d.windowStartHour ?? 2);
        setEndHour(d.windowEndHour ?? 5);
        setUseDefaultTables(!!d.usingDefaultTables);
        setTablesText((d.tables || []).join(', '));
    };

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const res = await api.get('/admin/maintenance/status');
            applyStatus(res.data);
        } catch (e) {
            setSnack({ open: true, msg: 'Failed to load maintenance status', severity: 'error' });
        }
        setLoading(false);
    }, []);

    useEffect(() => { load(); }, [load]);

    const save = async () => {
        setSaving(true);
        try {
            const body = {
                enabled, windowStartHour: startHour, windowEndHour: endHour,
                tables: useDefaultTables ? '' : tablesText,
            };
            const res = await api.put('/admin/maintenance/config', body);
            applyStatus(res.data);
            setSnack({ open: true, msg: 'Maintenance settings saved', severity: 'success' });
        } catch (e) {
            setSnack({ open: true, msg: e.response?.status === 403
                ? 'Only a Super Admin can change maintenance settings'
                : 'Failed to save settings', severity: 'error' });
        }
        setSaving(false);
    };

    const runNow = async (overrideBatch = false) => {
        setRunning(true);
        try {
            const res = await api.post(`/admin/maintenance/run?force=true&overrideBatch=${overrideBatch}`);
            const r = res.data;
            if (r.status === 'SKIPPED') {
                setSnack({ open: true, msg: `Skipped: ${r.reason}`, severity: 'warning' });
            } else {
                setSnack({ open: true, msg: `${r.status}: ${r.tablesDone}/${r.tablesTotal} tables in ${(r.durationMs / 1000).toFixed(1)}s`,
                    severity: r.status === 'SUCCESS' ? 'success' : 'error' });
            }
            load();
        } catch (e) {
            setSnack({ open: true, msg: e.response?.status === 403
                ? 'Only a Super Admin can run maintenance'
                : 'Failed to run maintenance', severity: 'error' });
        }
        setRunning(false);
    };

    const cardSx = {
        borderRadius: 3, overflow: 'hidden', height: '100%',
        border: `1px solid ${isDark ? '#2a2a40' : '#E5E7EB'}`,
        bgcolor: isDark ? '#1a1a2e' : '#fff',
    };
    const headStrip = (color) => ({ position: 'absolute', top: 0, left: 0, right: 0, height: 3, background: `linear-gradient(90deg, ${color}, ${color}55)` });

    if (loading) {
        return <Box sx={{ p: 6, textAlign: 'center' }}><CircularProgress size={28} /></Box>;
    }

    const windowDesc = startHour === endHour
        ? 'Window is empty — the job will not run automatically.'
        : `Runs nightly between ${hourLabel(startHour)} and ${hourLabel(endHour)}${startHour > endHour ? ' (crosses midnight)' : ''}, server time.`;

    return (
        <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1200, mx: 'auto' }}>
            {/* Header */}
            <Paper sx={{
                p: 3, mb: 3, borderRadius: 3, border: `1px solid ${isDark ? '#2a2a40' : '#E5E7EB'}`,
                background: isDark ? 'linear-gradient(135deg, #1a1a2e, #16213e)' : 'linear-gradient(135deg, #ffffff, #6366f10d)',
                display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap',
            }}>
                <Box sx={{ width: 48, height: 48, borderRadius: 2.5, display: 'flex', alignItems: 'center', justifyContent: 'center',
                    background: 'linear-gradient(135deg,#6366f1,#8b5cf6)', boxShadow: '0 6px 16px rgba(99,102,241,0.35)' }}>
                    <Database size={24} color="#fff" />
                </Box>
                <Box sx={{ flex: 1, minWidth: 200 }}>
                    <Typography variant="h5" fontWeight={700}>Database Maintenance</Typography>
                    <Typography variant="body2" color="text.secondary">Nightly VACUUM &amp; ANALYZE — runs only when no batch job is active.</Typography>
                </Box>
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                    <Button variant="outlined" startIcon={<RefreshCw size={16} />} onClick={load}>Refresh</Button>
                    <Button variant="contained" startIcon={running ? <CircularProgress size={15} color="inherit" /> : <Play size={16} />}
                        onClick={() => runNow(false)} disabled={running}>
                        {running ? 'Running…' : 'Run now'}
                    </Button>
                </Box>
            </Paper>

            {/* Live state strip */}
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mb: 3 }}>
                <Chip size="small" icon={<Activity size={14} />}
                    label={status?.batchRunning ? 'Batch job running' : 'No batch running'}
                    sx={{ fontWeight: 700, bgcolor: status?.batchRunning ? '#f59e0b22' : '#10b98122', color: status?.batchRunning ? '#b45309' : '#047857' }} />
                <Chip size="small" icon={<Clock size={14} />}
                    label={status?.inWindowNow ? 'In maintenance window' : 'Outside window'}
                    sx={{ fontWeight: 700, bgcolor: status?.inWindowNow ? '#3b82f622' : '#64748b18', color: status?.inWindowNow ? '#1e40af' : '#475569' }} />
                <Chip size="small" label={`Last run: ${status?.lastRunDate || 'never'}`}
                    sx={{ fontWeight: 700, bgcolor: '#64748b18', color: '#475569' }} />
                <Chip size="small" label={enabled ? 'Enabled' : 'Disabled'}
                    sx={{ fontWeight: 700, bgcolor: enabled ? '#10b98122' : '#ef444422', color: enabled ? '#047857' : '#991b1b' }} />
            </Box>

            <Grid container spacing={3}>
                {/* Schedule */}
                <Grid item xs={12} md={5}>
                    <Paper sx={{ ...cardSx, position: 'relative', p: 3 }}>
                        <Box sx={headStrip('#6366f1')} />
                        <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 2 }}>Schedule</Typography>
                        <FormControlLabel
                            control={<Switch checked={enabled} onChange={e => setEnabled(e.target.checked)} />}
                            label="Enable nightly maintenance" />
                        <Box sx={{ display: 'flex', gap: 2, mt: 2 }}>
                            <TextField select fullWidth size="small" label="Window start" value={startHour}
                                onChange={e => setStartHour(Number(e.target.value))}>
                                {HOURS.map(h => <MenuItem key={h} value={h}>{hourLabel(h)}</MenuItem>)}
                            </TextField>
                            <TextField select fullWidth size="small" label="Window end" value={endHour}
                                onChange={e => setEndHour(Number(e.target.value))}>
                                {HOURS.map(h => <MenuItem key={h} value={h}>{hourLabel(h)}</MenuItem>)}
                            </TextField>
                        </Box>
                        <Alert severity={startHour === endHour ? 'warning' : 'info'} variant="outlined" sx={{ mt: 2, py: 0.5 }}>
                            {windowDesc}
                        </Alert>
                    </Paper>
                </Grid>

                {/* Tables */}
                <Grid item xs={12} md={7}>
                    <Paper sx={{ ...cardSx, position: 'relative', p: 3 }}>
                        <Box sx={headStrip('#10b981')} />
                        <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 1 }}>Tables to maintain</Typography>
                        <FormControlLabel
                            control={<Switch checked={useDefaultTables} onChange={e => setUseDefaultTables(e.target.checked)} />}
                            label="Use the recommended high-churn table list" />
                        {useDefaultTables ? (
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mt: 1.5 }}>
                                {(status?.tables || []).map(t => (
                                    <Chip key={t} label={t} size="small" sx={{ fontFamily: 'monospace', fontSize: 11, bgcolor: isDark ? '#16162a' : '#f1f5f9' }} />
                                ))}
                            </Box>
                        ) : (
                            <TextField fullWidth multiline minRows={3} size="small" sx={{ mt: 1.5 }}
                                label="Tables (comma-separated)"
                                value={tablesText} onChange={e => setTablesText(e.target.value)}
                                helperText="VACUUM (ANALYZE) runs on each. Vacuuming a partitioned parent covers all its partitions." />
                        )}
                    </Paper>
                </Grid>
            </Grid>

            <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 3, gap: 2 }}>
                <Button variant="outlined" onClick={load} disabled={saving}>Reset</Button>
                <Button variant="contained" startIcon={<Save size={16} />} onClick={save} disabled={saving} sx={{ minWidth: 160 }}>
                    {saving ? 'Saving…' : 'Save Settings'}
                </Button>
            </Box>

            {/* Recent runs */}
            <Paper sx={{ ...cardSx, position: 'relative', p: 3, mt: 3 }}>
                <Box sx={headStrip('#8b5cf6')} />
                <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 1.5 }}>Recent Runs</Typography>
                {(!status?.recentRuns || status.recentRuns.length === 0) ? (
                    <Alert severity="info" variant="outlined" sx={{ py: 0.5 }}>No maintenance runs recorded yet.</Alert>
                ) : (
                    <Table size="small">
                        <TableHead>
                            <TableRow>
                                <TableCell>Started</TableCell><TableCell>Finished</TableCell>
                                <TableCell>Trigger</TableCell><TableCell>Status</TableCell>
                                <TableCell align="right">Tables</TableCell><TableCell>Detail</TableCell>
                            </TableRow>
                        </TableHead>
                        <TableBody>
                            {status.recentRuns.map(r => {
                                const meta = RUN_STATUS[r.status] || RUN_STATUS.SKIPPED;
                                const Icon = meta.icon;
                                return (
                                    <TableRow key={r.id}>
                                        <TableCell sx={{ whiteSpace: 'nowrap', fontSize: 12 }}>{fmtTs(r.started_at)}</TableCell>
                                        <TableCell sx={{ whiteSpace: 'nowrap', fontSize: 12 }}>{fmtTs(r.finished_at)}</TableCell>
                                        <TableCell><Chip label={r.trigger} size="small" variant="outlined" sx={{ fontSize: 10, fontWeight: 700 }} /></TableCell>
                                        <TableCell>
                                            <Chip size="small" icon={<Icon size={13} />} label={r.status}
                                                sx={{ fontWeight: 700, fontSize: 11, bgcolor: meta.bg, color: meta.color }} />
                                        </TableCell>
                                        <TableCell align="right">{r.tables_done}</TableCell>
                                        <TableCell sx={{ maxWidth: 360 }}>
                                            <Tooltip title={r.detail || ''}>
                                                <Typography variant="caption" color="text.secondary" noWrap sx={{ display: 'block', maxWidth: 360 }}>
                                                    {r.detail || '—'}
                                                </Typography>
                                            </Tooltip>
                                        </TableCell>
                                    </TableRow>
                                );
                            })}
                        </TableBody>
                    </Table>
                )}
                <Divider sx={{ my: 2 }} />
                <Typography variant="caption" color="text.secondary">
                    "Run now" ignores the schedule window but still refuses if a batch job is active. To force a run during ingestion,
                    use the API with <code>overrideBatch=true</code> (not recommended).
                </Typography>
            </Paper>

            <Snackbar open={snack.open} autoHideDuration={4000} onClose={() => setSnack(s => ({ ...s, open: false }))}>
                <Alert severity={snack.severity} variant="filled">{snack.msg}</Alert>
            </Snackbar>
        </Box>
    );
};

export default DatabaseMaintenance;
