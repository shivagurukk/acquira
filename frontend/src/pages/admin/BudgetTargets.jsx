import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
    Box, Typography, Paper, Grid, TextField, Button, MenuItem,
    IconButton, Tooltip, useTheme, Alert, Snackbar, Table, TableHead,
    TableRow, TableCell, TableBody, TableFooter, LinearProgress, Chip, Divider,
    ToggleButtonGroup, ToggleButton, Stack,
} from '@mui/material';
import { Target, Plus, Trash2, TrendingUp, Calendar, CalendarRange, AlertTriangle } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { createFmt } from '../../utils/formatters';

/* ────────────────────────────────────────────────────────────────────────────
   Budget Targets
   Storage is monthly (bank_budget_target); this page adds two things on top:
     - a YEARLY entry mode that writes 12 monthly rows in one go, phased
       Equal / Seasonal (prior-year mix) / Manual (12-cell grid)
     - a YEARLY view (year selector) that shows YTD attainment against the
       elapsed-to-date target plus a run-rate projection to the full year
   The in-progress calendar month is always shown with its PACE attainment
   (actual vs. target pro-rated for days elapsed) alongside the raw one, so a
   month that isn't over yet doesn't read as "behind" just because it's early.

   Future / not-yet-ingested months (beyond the backend's `dataThrough`) are
   shown as a neutral "Upcoming" state rather than 0% / Behind — the backend
   already excludes them from YTD/run-rate math (2026-07-10 fix), and the
   yearly tile status now reflects YTD pace rather than the raw annual %
   (which would otherwise read "Behind" for the whole year until December).
   ──────────────────────────────────────────────────────────────────────────── */

const METRICS = [
    { key: 'VOLUME',      label: 'Volume',            basis: 'cardholder' },
    { key: 'BASE_VOLUME', label: 'Settlement Volume',  basis: 'settlement' },
    { key: 'NET_REVENUE', label: 'Net Revenue',        basis: 'cardholder' },
    { key: 'MSF',         label: 'MSF',                basis: 'cardholder' },
    { key: 'TXNS',        label: 'Transactions',       basis: 'cardholder' },
];

const PHASING_OPTIONS = [
    { key: 'EQUAL',    label: 'Equal split',   hint: '1/12 of the annual number each month' },
    { key: 'SEASONAL', label: 'Seasonal',      hint: "Weighted by last year's monthly mix" },
    { key: 'MANUAL',   label: 'Manual grid',   hint: 'Enter each month yourself' },
];

const STATUS_COLOR = {
    MET:      { bg: 'rgba(5,150,105,0.12)',  fg: '#059669', bar: '#10b981', label: 'Met' },
    ON_TRACK: { bg: 'rgba(202,138,4,0.12)',  fg: '#b45309', bar: '#f59e0b', label: 'On track' },
    BEHIND:   { bg: 'rgba(220,38,38,0.10)',  fg: '#dc2626', bar: '#ef4444', label: 'Behind' },
    UPCOMING: { bg: 'rgba(100,116,139,0.12)', fg: '#64748b', bar: '#94a3b8', label: 'Upcoming' },
};

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const now = new Date();
const currentYear = now.getFullYear();
const defaultMonthKey = currentYear * 100 + (now.getMonth() + 1);
const YEAR_OPTIONS = [currentYear - 1, currentYear, currentYear + 1];

const BudgetTargets = () => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';
    const { currencySymbol, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol), [currencySymbol]);

    const [viewMode, setViewMode] = useState('YEARLY'); // MONTHLY | YEARLY
    const [viewYear, setViewYear] = useState(currentYear);

    const [attainment, setAttainment] = useState({ rows: [], summary: [], currentMonthKey: defaultMonthKey });
    const [loading, setLoading] = useState(true);
    const [snack, setSnack] = useState({ open: false, msg: '', severity: 'success' });

    // Monthly entry form
    const [monthKey, setMonthKey] = useState(String(defaultMonthKey));
    const [metricType, setMetricType] = useState('VOLUME');
    const [targetValue, setTargetValue] = useState('');
    const [saving, setSaving] = useState(false);

    // Yearly entry form
    const [yEntryYear, setYEntryYear] = useState(currentYear);
    const [yMetricType, setYMetricType] = useState('VOLUME');
    const [phasing, setPhasing] = useState('EQUAL');
    const [annualTarget, setAnnualTarget] = useState('');
    const [manualValues, setManualValues] = useState(Array(12).fill(''));
    const [ySaving, setYSaving] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const params = viewMode === 'YEARLY' ? { year: viewYear } : {};
            const res = await api.get('/business/budget/attainment', { params });
            setAttainment(res.data || { rows: [], summary: [] });
        } catch (e) {
            console.error('attainment load failed', e);
            setAttainment({ rows: [], summary: [] });
        } finally {
            setLoading(false);
        }
    }, [viewMode, viewYear]);

    useEffect(() => { load(); }, [load, tenantVersion]);

    const formatMetric = (metric, val) =>
        metric === 'TXNS' ? fmt.number(val) : fmt.currency(val);

    const dataThroughLabel = useMemo(() => {
        if (!attainment.dataThrough) return null;
        const y = Math.floor(attainment.dataThrough / 100);
        const m = attainment.dataThrough % 100;
        if (m < 1 || m > 12) return null;
        return `${MONTH_NAMES[m - 1]} ${y}`;
    }, [attainment.dataThrough]);

    /* ── Monthly entry ── */
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

    /* ── Yearly entry ── */
    const saveYearlyTarget = async () => {
        if (phasing === 'MANUAL') {
            if (manualValues.some(v => v === '' || Number(v) < 0)) {
                setSnack({ open: true, msg: 'Fill in all 12 months with non-negative numbers', severity: 'error' });
                return;
            }
        } else if (annualTarget === '' || Number(annualTarget) < 0) {
            setSnack({ open: true, msg: 'Annual target must be a non-negative number', severity: 'error' });
            return;
        }
        setYSaving(true);
        try {
            const body = {
                year: yEntryYear, metricType: yMetricType, phasing,
                ...(phasing === 'MANUAL'
                    ? { monthlyValues: manualValues.map(Number) }
                    : { annualTarget: Number(annualTarget) }),
            };
            const res = await api.post('/business/budget/targets/yearly', body);
            const fallbackNote = res.data?.phasingFallback
                ? ' — prior-year data was incomplete, used equal split instead' : '';
            setSnack({ open: true, msg: `Annual target saved across 12 months${fallbackNote}`, severity: res.data?.phasingFallback ? 'warning' : 'success' });
            setAnnualTarget('');
            setManualValues(Array(12).fill(''));
            if (viewMode === 'YEARLY' && viewYear === yEntryYear) load();
        } catch (e) {
            setSnack({ open: true, msg: e?.response?.data?.error || 'Save failed', severity: 'error' });
        } finally {
            setYSaving(false);
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
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 2, mb: 3, flexWrap: 'wrap' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
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
                            Set goals monthly or by year, and track attainment against actuals
                        </Typography>
                    </Box>
                </Box>

                <Stack direction="row" spacing={1.5} alignItems="center">
                    <ToggleButtonGroup value={viewMode} exclusive size="small"
                        onChange={(e, v) => v && setViewMode(v)}>
                        <ToggleButton value="MONTHLY" sx={{ textTransform: 'none', px: 1.5 }}>
                            <Calendar size={14} style={{ marginRight: 6 }} /> Monthly
                        </ToggleButton>
                        <ToggleButton value="YEARLY" sx={{ textTransform: 'none', px: 1.5 }}>
                            <CalendarRange size={14} style={{ marginRight: 6 }} /> Yearly
                        </ToggleButton>
                    </ToggleButtonGroup>
                    {viewMode === 'YEARLY' && (
                        <TextField select size="small" value={viewYear} onChange={e => setViewYear(Number(e.target.value))} sx={{ width: 110 }}>
                            {YEAR_OPTIONS.map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                        </TextField>
                    )}
                </Stack>
            </Box>

            {/* Data-lag notice — only shown when ingestion is behind the calendar */}
            {viewMode === 'YEARLY' && attainment.dataLag && dataThroughLabel && !loading && (
                <Alert severity="warning" icon={<AlertTriangle size={18} />} sx={{ mb: 2 }}>
                    Actuals are loaded through <b>{dataThroughLabel}</b>. Later months are shown as Upcoming and excluded from YTD / run-rate until data lands.
                </Alert>
            )}

            {/* Summary tiles */}
            <Grid container spacing={2} sx={{ mb: 3 }}>
                {attainment.summary?.length === 0 && !loading && (
                    <Grid item xs={12}>
                        <Alert severity="info">
                            {viewMode === 'YEARLY'
                                ? `No targets set for ${viewYear} yet. Use "Add Annual Target" below to start tracking.`
                                : 'No targets set yet. Add one below to start tracking attainment.'}
                        </Alert>
                    </Grid>
                )}
                {attainment.summary?.map((s) => {
                    const sc = STATUS_COLOR[s.status] || STATUS_COLOR.BEHIND;
                    const pctClamped = Math.min(Number(s.attainmentPct) || 0, 100);
                    const metricLabel = METRICS.find(m => m.key === s.metricType)?.label || s.metricType;
                    const isYearly = viewMode === 'YEARLY' && s.fullYearTarget !== undefined;
                    return (
                        <Grid item xs={12} sm={6} md={isYearly ? 4 : 3} key={s.metricType}>
                            <Paper sx={cardSx} elevation={0}>
                                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
                                    <Typography variant="caption" sx={{ fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.05em', color: 'text.secondary' }}>
                                        {metricLabel}
                                        <Box component="span" sx={{ ml: 0.75, fontWeight: 500, color: 'text.disabled', textTransform: 'none' }}>
                                            ({s.basis})
                                        </Box>
                                    </Typography>
                                    <Chip size="small" label={sc.label} sx={{ bgcolor: sc.bg, color: sc.fg, fontWeight: 700, fontSize: '.68rem' }} />
                                </Box>

                                {isYearly ? (
                                    <>
                                        <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700 }}>YTD Attainment</Typography>
                                        <Typography variant="h5" sx={{ fontWeight: 800, fontVariantNumeric: 'tabular-nums', mb: 0.5 }}>
                                            {Number(s.ytdAttainmentPct).toFixed(1)}%
                                        </Typography>
                                        <LinearProgress
                                            variant="determinate" value={Math.min(Number(s.ytdAttainmentPct) || 0, 100)}
                                            sx={{ mb: 1, height: 7, borderRadius: 4, bgcolor: isDark ? '#222' : '#eef2f7',
                                                '& .MuiLinearProgress-bar': { bgcolor: sc.bar, borderRadius: 4 } }}
                                        />
                                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                                            {formatMetric(s.metricType, s.actualValue)} of {formatMetric(s.metricType, s.ytdTarget)} elapsed-to-date
                                        </Typography>
                                        <Divider sx={{ my: 1 }} />
                                        <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                                            Run-rate: <b>{formatMetric(s.metricType, s.runRateProjection)}</b> → projected{' '}
                                            <b style={{ color: (Number(s.projectedAttainmentPct) >= 100) ? '#059669' : '#b45309' }}>
                                                {Number(s.projectedAttainmentPct).toFixed(1)}%
                                            </b> of {formatMetric(s.metricType, s.fullYearTarget)} annual target
                                        </Typography>
                                    </>
                                ) : (
                                    <>
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
                                    </>
                                )}
                            </Paper>
                        </Grid>
                    );
                })}
            </Grid>

            {/* Entry forms */}
            <Grid container spacing={3} sx={{ mb: 3 }}>
                <Grid item xs={12} md={viewMode === 'YEARLY' ? 5 : 12}>
                    <Paper sx={cardSx} elevation={0}>
                        <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
                            <Plus size={18} /> Add / Update Monthly Target
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
                </Grid>

                {viewMode === 'YEARLY' && (
                    <Grid item xs={12} md={7}>
                        <Paper sx={cardSx} elevation={0}>
                            <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
                                <CalendarRange size={18} /> Add Annual Target
                            </Typography>
                            <Grid container spacing={2} alignItems="flex-end">
                                <Grid item xs={6} sm={3}>
                                    <TextField select label="Year" fullWidth size="small" value={yEntryYear}
                                        onChange={e => setYEntryYear(Number(e.target.value))}>
                                        {YEAR_OPTIONS.map(y => <MenuItem key={y} value={y}>{y}</MenuItem>)}
                                    </TextField>
                                </Grid>
                                <Grid item xs={6} sm={3}>
                                    <TextField select label="Metric" fullWidth size="small" value={yMetricType}
                                        onChange={e => setYMetricType(e.target.value)}>
                                        {METRICS.map(m => <MenuItem key={m.key} value={m.key}>{m.label}</MenuItem>)}
                                    </TextField>
                                </Grid>
                                <Grid item xs={12} sm={6}>
                                    <TextField select label="Phasing" fullWidth size="small" value={phasing}
                                        onChange={e => setPhasing(e.target.value)}
                                        helperText={PHASING_OPTIONS.find(p => p.key === phasing)?.hint}>
                                        {PHASING_OPTIONS.map(p => <MenuItem key={p.key} value={p.key}>{p.label}</MenuItem>)}
                                    </TextField>
                                </Grid>

                                {phasing !== 'MANUAL' ? (
                                    <Grid item xs={12} sm={8}>
                                        <TextField
                                            label="Annual Target" fullWidth size="small" type="number"
                                            value={annualTarget} onChange={e => setAnnualTarget(e.target.value)}
                                            placeholder="12000000"
                                        />
                                    </Grid>
                                ) : (
                                    <Grid item xs={12}>
                                        <Typography variant="caption" color="text.secondary" sx={{ mb: 1, display: 'block' }}>
                                            Enter each month's target directly
                                        </Typography>
                                        <Grid container spacing={1}>
                                            {MONTH_NAMES.map((mn, i) => (
                                                <Grid item xs={4} sm={2} key={mn}>
                                                    <TextField
                                                        label={mn} size="small" type="number" fullWidth
                                                        value={manualValues[i]}
                                                        onChange={e => {
                                                            const next = [...manualValues];
                                                            next[i] = e.target.value;
                                                            setManualValues(next);
                                                        }}
                                                    />
                                                </Grid>
                                            ))}
                                        </Grid>
                                    </Grid>
                                )}

                                <Grid item xs={12} sm={phasing !== 'MANUAL' ? 4 : 12}>
                                    <Button
                                        variant="contained" fullWidth onClick={saveYearlyTarget} disabled={ySaving}
                                        sx={{ height: 40, textTransform: 'none', fontWeight: 700,
                                            background: 'linear-gradient(135deg,#10b981,#059669)' }}
                                    >
                                        {ySaving ? 'Saving…' : 'Save Annual Target'}
                                    </Button>
                                </Grid>
                            </Grid>
                            <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: 'block' }}>
                                Writes 12 monthly rows. Seasonal phasing uses {yEntryYear - 1}'s actual monthly mix for this
                                metric — falls back to an equal split automatically if that year is incomplete.
                            </Typography>
                        </Paper>
                    </Grid>
                )}
            </Grid>

            {/* Attainment detail grid */}
            <Paper sx={cardSx} elevation={0}>
                <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 1, display: 'flex', alignItems: 'center', gap: 1 }}>
                    <TrendingUp size={18} /> {viewMode === 'YEARLY' ? `${viewYear} Monthly Attainment` : 'Monthly Attainment'}
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
                                const isUpcoming = r.future || r.status === 'UPCOMING';
                                const variancePos = !isUpcoming && Number(r.variance) >= 0;
                                return (
                                    <TableRow
                                        key={r.budgetId}
                                        hover
                                        sx={{
                                            opacity: isUpcoming ? 0.55 : 1,
                                            bgcolor: r.partial
                                                ? (isDark ? 'rgba(99,102,241,0.06)' : 'rgba(99,102,241,0.04)')
                                                : undefined,
                                        }}
                                    >
                                        <TableCell>
                                            {r.monthLabel}
                                            {r.partial && (
                                                <Tooltip title={`In progress — day ${r.daysElapsed} of ${r.daysInMonth}`}>
                                                    <Chip size="small" label="in progress" sx={{ ml: 1, height: 18, fontSize: '.62rem', bgcolor: 'rgba(99,102,241,0.12)', color: '#6366f1', fontWeight: 700 }} />
                                                </Tooltip>
                                            )}
                                        </TableCell>
                                        <TableCell>{METRICS.find(m => m.key === r.metricType)?.label || r.metricType}</TableCell>
                                        <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                                            {formatMetric(r.metricType, r.targetValue)}
                                        </TableCell>
                                        <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                                            {isUpcoming ? '—' : formatMetric(r.metricType, r.actualValue)}
                                        </TableCell>
                                        <TableCell align="right" sx={{ fontWeight: 700, color: isUpcoming ? 'text.disabled' : sc.fg, fontVariantNumeric: 'tabular-nums' }}>
                                            {isUpcoming ? '—' : `${Number(r.attainmentPct).toFixed(1)}%`}
                                            {!isUpcoming && r.partial && r.paceAttainmentPct !== undefined && (
                                                <Tooltip title="Attainment against the target pro-rated for days elapsed this month">
                                                    <Box component="span" sx={{ display: 'block', fontSize: '.68rem', fontWeight: 600, color: 'text.secondary' }}>
                                                        pace {Number(r.paceAttainmentPct).toFixed(1)}%
                                                    </Box>
                                                </Tooltip>
                                            )}
                                        </TableCell>
                                        <TableCell align="right" sx={{ color: isUpcoming ? 'text.disabled' : (variancePos ? '#059669' : '#dc2626'), fontVariantNumeric: 'tabular-nums' }}>
                                            {isUpcoming ? '—' : `${variancePos ? '+' : ''}${formatMetric(r.metricType, r.variance)}`}
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
                        {viewMode === 'YEARLY' && attainment.summary?.length > 0 && (
                            <TableFooter>
                                {attainment.summary.map((s) => {
                                    const fsc = STATUS_COLOR[s.status] || STATUS_COLOR.BEHIND;
                                    const ytdVariance = Number(s.actualValue) - Number(s.ytdTarget);
                                    return (
                                        <TableRow key={`footer-${s.metricType}`} sx={{ '& td': { borderTop: `2px solid ${isDark ? '#333' : '#E5E7EB'}`, fontWeight: 700 } }}>
                                            <TableCell colSpan={2}>
                                                {METRICS.find(m => m.key === s.metricType)?.label || s.metricType} — {viewYear} full year
                                            </TableCell>
                                            <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                                                {formatMetric(s.metricType, s.fullYearTarget)}
                                            </TableCell>
                                            <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                                                {formatMetric(s.metricType, s.actualValue)}
                                            </TableCell>
                                            <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', color: fsc.fg }}>
                                                {Number(s.ytdAttainmentPct).toFixed(1)}% YTD
                                            </TableCell>
                                            <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', color: ytdVariance >= 0 ? '#059669' : '#dc2626' }}>
                                                {ytdVariance >= 0 ? '+' : ''}{formatMetric(s.metricType, ytdVariance)}
                                            </TableCell>
                                            <TableCell align="center">
                                                <Chip size="small" label={fsc.label} sx={{ bgcolor: fsc.bg, color: fsc.fg, fontWeight: 700, fontSize: '.68rem' }} />
                                            </TableCell>
                                            <TableCell />
                                        </TableRow>
                                    );
                                })}
                            </TableFooter>
                        )}
                    </Table>
                )}
            </Paper>

            <Snackbar
                open={snack.open} autoHideDuration={4000}
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
