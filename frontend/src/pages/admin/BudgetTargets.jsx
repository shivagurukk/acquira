import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
    Box, Typography, Paper, Grid, TextField, Button, MenuItem,
    IconButton, Tooltip, useTheme, Alert, Snackbar, Table, TableHead,
    TableRow, TableCell, TableBody, LinearProgress, Chip, Divider,
} from '@mui/material';
import { Target, Plus, Trash2, TrendingUp } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';

const METRICS = [
    { key: 'VOLUME',      label: 'Volume' },
    { key: 'NET_REVENUE', label: 'Net Revenue' },
    { key: 'MSF',         label: 'MSF' },
    { key: 'TXNS',        label: 'Transactions' },
];

const STATUS_COLOR = {
    MET:      { bg: 'rgba(5,150,105,0.12)',  fg: '#059669', bar: '#10b981', label: 'Met' },
    ON_TRACK: { bg: 'rgba(202,138,4,0.12)',  fg: '#b45309', bar: '#f59e0b', label: 'On track' },
    BEHIND:   { bg: 'rgba(220,38,38,0.10)',  fg: '#dc2626', bar: '#ef4444', label: 'Behind' },
};

const now = new Date();
const defaultMonthKey = now.getFullYear() * 100 + (now.getMonth() + 1);

const BudgetTargets = () => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';
    const { currencySymbol, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);

    const [attainment, setAttainment] = useState({ rows: [], summary: [] });
    const [loading, setLoading] = useState(true);
    const [snack, setSnack] = useState({ open: false, msg: '', severity: 'success' });

    // Entry form
    const [monthKey, setMonthKey] = useState(String(defaultMonthKey));
    const [metricType, setMetricType] = useState('VOLUME');
    const [targetValue, setTargetValue] = useState('');
    const [saving, setSaving] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const res = await api.get('/business/budget/attainment');
            setAttainment(res.data || { rows: [], summary: [] });
        } catch (e) {
            console.error('attainment load failed', e);
            setAttainment({ rows: [], summary: [] });
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load, tenantVersion]);

    const formatMetric = (metric, val) =>
        metric === 'TXNS' ? fmt.number(val) : fmt.currency(val);

    const saveTarget = async () => {
        const mk = parseInt(monthKey, 10);
        if (!mk || String(mk).length !== 6) {
            setSnack({ open: true, msg: 'Month must be YYYYMM (e.g. 202607)', severity: 'error' });
            return;
        }
        if (targetValue === '' || Number(targetValue) < 0) {
            setSnack({ open: true, msg: 'Target must be a non-negative number', severity: 'error' });
            return;
        }
        setSaving(true);
        try {
            await api.post('/business/budget/targets', {
                monthKey: mk, metricType, targetValue: Number(targetValue),
            });
            setSnack({ open: true, msg: 'Target saved', severity: 'success' });
            setTargetValue('');
            load();
        } catch (e) {
            setSnack({ open: true, msg: e?.response?.data?.error || 'Save failed', severity: 'error' });
        } finally {
            setSaving(false);
        }
    };

    const deleteTarget = async (id) => {
        try {
            await api.delete(`/business/budget/targets/${id}`);
            setSnack({ open: true, msg: 'Target removed', severity: 'success' });
            load();
        } catch {
            setSnack({ open: true, msg: 'Delete failed', severity: 'error' });
        }
    };

    const cardSx = {
        p: 2.5, borderRadius: 2,
        border: `1px solid ${isDark ? '#333' : '#E5E7EB'}`,
        bgcolor: isDark ? '#1a1a2e' : '#fff',
    };

    return (
        <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1400, mx: 'auto' }}>
            {/* Header */}
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3 }}>
                <Box sx={{
                    width: 44, height: 44, borderRadius: 2, display: 'flex',
                    alignItems: 'center', justifyContent: 'center',
                    background: 'linear-gradient(135deg,#6366f1,#8b5cf6)',
                }}>
                    <Target size={22} color="#fff" />
                </Box>
                <Box>
                    <Typography variant="h5" sx={{ fontWeight: 700 }}>Budget Targets</Typography>
                    <Typography variant="body2" color="text.secondary">
                        Set monthly goals and track attainment against actuals
                    </Typography>
                </Box>
            </Box>

            {/* Summary tiles (per-metric roll-up over the range) */}
            <Grid container spacing={2} sx={{ mb: 3 }}>
                {attainment.summary?.length === 0 && !loading && (
                    <Grid item xs={12}>
                        <Alert severity="info">
                            No targets set for the current year yet. Add one below to start tracking attainment.
                        </Alert>
                    </Grid>
                )}
                {attainment.summary?.map((s) => {
                    const sc = STATUS_COLOR[s.status] || STATUS_COLOR.BEHIND;
                    const pctClamped = Math.min(Number(s.attainmentPct) || 0, 100);
                    const metricLabel = METRICS.find(m => m.key === s.metricType)?.label || s.metricType;
                    return (
                        <Grid item xs={12} sm={6} md={3} key={s.metricType}>
                            <Paper sx={cardSx} elevation={0}>
                                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
                                    <Typography variant="caption" sx={{ fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.05em', color: 'text.secondary' }}>
                                        {metricLabel}
                                    </Typography>
                                    <Chip size="small" label={sc.label} sx={{ bgcolor: sc.bg, color: sc.fg, fontWeight: 700, fontSize: '.68rem' }} />
                                </Box>
                                <Typography variant="h5" sx={{ fontWeight: 800, fontVariantNumeric: 'tabular-nums' }}>
                                    {Number(s.attainmentPct).toFixed(1)}%
                                </Typography>
                                <LinearProgress
                                    variant="determinate" value={pctClamped}
                                    sx={{ my: 1, height: 7, borderRadius: 4, bgcolor: isDark ? '#222' : '#eef2f7',
                                        '& .MuiLinearProgress-bar': { bgcolor: sc.bar, borderRadius: 4 } }}
                                />
                                <Typography variant="caption" color="text.secondary">
                                    {formatMetric(s.metricType, s.actualValue)} of {formatMetric(s.metricType, s.targetValue)}
                                </Typography>
                            </Paper>
                        </Grid>
                    );
                })}
            </Grid>

            {/* Entry form */}
            <Paper sx={{ ...cardSx, mb: 3 }} elevation={0}>
                <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Plus size={18} /> Add / Update Target
                </Typography>
                <Grid container spacing={2} alignItems="flex-end">
                    <Grid item xs={12} sm={3}>
                        <TextField
                            label="Month (YYYYMM)" fullWidth size="small"
                            value={monthKey} onChange={e => setMonthKey(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
                            placeholder="202607"
                        />
                    </Grid>
                    <Grid item xs={12} sm={3}>
                        <TextField
                            select label="Metric" fullWidth size="small"
                            value={metricType} onChange={e => setMetricType(e.target.value)}
                        >
                            {METRICS.map(m => <MenuItem key={m.key} value={m.key}>{m.label}</MenuItem>)}
                        </TextField>
                    </Grid>
                    <Grid item xs={12} sm={3}>
                        <TextField
                            label="Target Value" fullWidth size="small" type="number"
                            value={targetValue} onChange={e => setTargetValue(e.target.value)}
                            placeholder="1000000"
                        />
                    </Grid>
                    <Grid item xs={12} sm={3}>
                        <Button
                            variant="contained" fullWidth onClick={saveTarget} disabled={saving}
                            sx={{ height: 40, textTransform: 'none', fontWeight: 700,
                                background: 'linear-gradient(135deg,#6366f1,#8b5cf6)' }}
                        >
                            {saving ? 'Saving…' : 'Save Target'}
                        </Button>
                    </Grid>
                </Grid>
                <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: 'block' }}>
                    One target per metric per month — saving the same month + metric updates the existing value.
                </Typography>
            </Paper>

            {/* Attainment detail grid */}
            <Paper sx={cardSx} elevation={0}>
                <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 1, display: 'flex', alignItems: 'center', gap: 1 }}>
                    <TrendingUp size={18} /> Monthly Attainment
                </Typography>
                <Divider sx={{ mb: 1 }} />
                {loading ? (
                    <LinearProgress />
                ) : attainment.rows?.length === 0 ? (
                    <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
                        No targets in the selected range.
                    </Typography>
                ) : (
                    <Table size="small">
                        <TableHead>
                            <TableRow>
                                <TableCell>Month</TableCell>
                                <TableCell>Metric</TableCell>
                                <TableCell align="right">Target</TableCell>
                                <TableCell align="right">Actual</TableCell>
                                <TableCell align="right">Attainment</TableCell>
                                <TableCell align="right">Variance</TableCell>
                                <TableCell align="center">Status</TableCell>
                                <TableCell align="center"></TableCell>
                            </TableRow>
                        </TableHead>
                        <TableBody>
                            {attainment.rows.map((r) => {
                                const sc = STATUS_COLOR[r.status] || STATUS_COLOR.BEHIND;
                                const variancePos = Number(r.variance) >= 0;
                                return (
                                    <TableRow key={r.budgetId} hover>
                                        <TableCell>{r.monthLabel}</TableCell>
                                        <TableCell>{METRICS.find(m => m.key === r.metricType)?.label || r.metricType}</TableCell>
                                        <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                                            {formatMetric(r.metricType, r.targetValue)}
                                        </TableCell>
                                        <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                                            {formatMetric(r.metricType, r.actualValue)}
                                        </TableCell>
                                        <TableCell align="right" sx={{ fontWeight: 700, color: sc.fg, fontVariantNumeric: 'tabular-nums' }}>
                                            {Number(r.attainmentPct).toFixed(1)}%
                                        </TableCell>
                                        <TableCell align="right" sx={{ color: variancePos ? '#059669' : '#dc2626', fontVariantNumeric: 'tabular-nums' }}>
                                            {variancePos ? '+' : ''}{formatMetric(r.metricType, r.variance)}
                                        </TableCell>
                                        <TableCell align="center">
                                            <Chip size="small" label={sc.label} sx={{ bgcolor: sc.bg, color: sc.fg, fontWeight: 700, fontSize: '.68rem' }} />
                                        </TableCell>
                                        <TableCell align="center">
                                            <Tooltip title="Remove target">
                                                <IconButton size="small" onClick={() => deleteTarget(r.budgetId)}>
                                                    <Trash2 size={15} color="#ef4444" />
                                                </IconButton>
                                            </Tooltip>
                                        </TableCell>
                                    </TableRow>
                                );
                            })}
                        </TableBody>
                    </Table>
                )}
            </Paper>

            <Snackbar
                open={snack.open} autoHideDuration={3000}
                onClose={() => setSnack({ ...snack, open: false })}
                anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            >
                <Alert severity={snack.severity} onClose={() => setSnack({ ...snack, open: false })}>
                    {snack.msg}
                </Alert>
            </Snackbar>
        </Box>
    );
};

export default BudgetTargets;
